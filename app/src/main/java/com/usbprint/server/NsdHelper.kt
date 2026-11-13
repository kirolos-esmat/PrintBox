package com.usbprint.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class NsdHelper(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var pdlRegistrationListener: NsdManager.RegistrationListener? = null
    private var printerRegistrationListener: NsdManager.RegistrationListener? = null
    private var ippRegistrationListener: NsdManager.RegistrationListener? = null

    private val registrationLock = Any()
    private val jmdnsMutex = Mutex()
    private val nsdScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registrationGeneration = 0
    private var jmdns: JmDNS? = null
    private var jmdnsAirPrintService: ServiceInfo? = null

    fun registerServices(
        pdlPort: Int = 9100,
        ippPort: Int = 8631,
        serviceName: String = "HP LaserJet Pro P1102 (MiBox)"
    ) {
        unregisterNsdListeners()
        val generation = synchronized(registrationLock) { ++registrationGeneration }

        // 1. Register _pdl-datastream._tcp (Port 9100 JetDirect discovery) via Android NSD
        val pdlInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_pdl-datastream._tcp."
            this.port = pdlPort
            setAttribute("ty", "HP LaserJet Professional P1102")
            setAttribute("pdl", "application/vnd.hp-PCL,application/octet-stream")
            setAttribute("qtotal", "1")
        }

        pdlRegistrationListener = createListener("PDL")
        try {
            nsdManager.registerService(pdlInfo, NsdManager.PROTOCOL_DNS_SD, pdlRegistrationListener)
        } catch (e: Exception) {
            AppState.log("Failed to register _pdl-datastream._tcp: ${e.message}", isError = true)
        }

        // 2. Register _printer._tcp (legacy raw printer discovery) via Android NSD
        val printerInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_printer._tcp."
            this.port = pdlPort
            setAttribute("txtvers", "1")
            setAttribute("ty", "HP LaserJet Professional P1102")
        }

        printerRegistrationListener = createListener("PRINTER")
        try {
            nsdManager.registerService(printerInfo, NsdManager.PROTOCOL_DNS_SD, printerRegistrationListener)
        } catch (e: Exception) {
            AppState.log("Failed to register _printer._tcp: ${e.message}", isError = true)
        }

        // 3. Register _ipp._tcp via Android NSD (for general IPP clients)
        val ippInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_ipp._tcp,_print"
            this.port = ippPort
            setAttribute("txtvers", "1")
            setAttribute("qtotal", "1")
            setAttribute("rp", "ipp/print")
            setAttribute("ty", "HP LaserJet Professional P1102")
            setAttribute("product", "(HP LaserJet Professional P1102)")
            setAttribute("pdl", "application/pdf,${RasterToPbm.URF_MIME},${RasterToPbm.PWG_MIME}")
            setAttribute("URF", "W8,SRGB24,CP1,RS300,RS600")
            setAttribute("Color", "F")
            setAttribute("Duplex", "F")
            setAttribute("Copies", "F")
            setAttribute("priority", "10")
            setAttribute("note", "MiBox Print Server")
            setAttribute("UUID", "e48d3c50-256b-4f9e-8c9a-b44e05b5f631")
        }

        ippRegistrationListener = createListener("AirPrint IPP")
        try {
            nsdManager.registerService(ippInfo, NsdManager.PROTOCOL_DNS_SD, ippRegistrationListener)
        } catch (e: Exception) {
            AppState.log("Failed to register AirPrint _ipp._tcp: ${e.message}", isError = true)
        }

        // 4. Register Apple AirPrint with _universal subtype via JmDNS
        // iOS specifically queries '_universal._sub._ipp._tcp.local.' which Android's NsdManager cannot register on API 30.
        nsdScope.launch {
            jmdnsMutex.withLock {
                closeJmDnsLocked()
                registerJmDnsAirPrintLocked(serviceName, ippPort, generation)
            }
        }
    }

    private fun registerJmDnsAirPrintLocked(serviceName: String, ippPort: Int, generation: Int) {
        synchronized(registrationLock) {
            if (generation != registrationGeneration) return
        }
        var instance: JmDNS? = null
        try {
            val localAddr = getLocalInetAddress() ?: return
            val props = HashMap<String, String>().apply {
                put("txtvers", "1")
                put("qtotal", "1")
                put("rp", "ipp/print")
                put("ty", "HP LaserJet Professional P1102")
                put("product", "(HP LaserJet Professional P1102)")
                put("pdl", "application/pdf,${RasterToPbm.URF_MIME},${RasterToPbm.PWG_MIME}")
                put("URF", "W8,SRGB24,CP1,RS300,RS600")
                put("Color", "F")
                put("Duplex", "F")
                put("Copies", "F")
                put("priority", "10")
                put("note", "MiBox Print Server")
                put("UUID", "e48d3c50-256b-4f9e-8c9a-b44e05b5f631")
                put("mopria-certified", "1.3")
                put("adminurl", "http://${localAddr.hostAddress}:9101/")
            }

            val j = JmDNS.create(localAddr, "HP-LaserJet-MiBox")
            instance = j
            val serviceInfo = ServiceInfo.create(
                "_ipp._tcp.local.",
                serviceName,
                "_universal",
                ippPort,
                0,
                0,
                props
            )
            j.registerService(serviceInfo)
            synchronized(registrationLock) {
                if (generation != registrationGeneration) return
                jmdns = j
                jmdnsAirPrintService = serviceInfo
                instance = null
            }
            AppState.log("JmDNS AirPrint published with _universal subtype on port $ippPort (${localAddr.hostAddress})")
        } catch (e: Exception) {
            AppState.log("JmDNS subtype publication notice: ${e.message}", isError = true)
        } finally {
            try { instance?.close() } catch (_: Exception) {}
        }
    }

    private fun getLocalInetAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr
                    }
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    private fun createListener(label: String): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                AppState.log("mDNS [$label] registered: ${info.serviceName} (${info.serviceType}) on port ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                AppState.log("mDNS [$label] registration failed, code: $errorCode", isError = true)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                AppState.log("mDNS [$label] unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                AppState.log("mDNS [$label] unregistration failed: $errorCode", isError = true)
            }
        }
    }

    private fun unregisterNsdListeners() {
        pdlRegistrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (ignored: Exception) {}
        }
        pdlRegistrationListener = null

        printerRegistrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (ignored: Exception) {}
        }
        printerRegistrationListener = null

        ippRegistrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (ignored: Exception) {}
        }
        ippRegistrationListener = null
    }

    private fun closeJmDnsLocked() {
        val (j, svc) = synchronized(registrationLock) {
            val pair = jmdns to jmdnsAirPrintService
            jmdns = null
            jmdnsAirPrintService = null
            pair
        }
        if (j != null) {
            try {
                svc?.let { j.unregisterService(it) }
                j.close()
            } catch (e: Exception) {
                AppState.log("JmDNS shutdown failed: ${e.message}", isError = true)
            }
        }
    }

    fun unregisterServices() {
        unregisterNsdListeners()
        synchronized(registrationLock) { registrationGeneration++ }
        nsdScope.launch {
            jmdnsMutex.withLock {
                closeJmDnsLocked()
            }
        }
    }
}
