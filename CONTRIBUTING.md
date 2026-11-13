# Contributing to PrintBox

Thanks for helping improve **PrintBox**! Whether you are adding support for a new USB printer, building a new feature, or fixing a bug, this guide explains how the codebase fits together and what checks to run before opening a pull request.

---

## Development Workflow

1. **Fork & clone** the repository.
2. **Create a focused branch** from `main` (e.g., `feat/printer-hp-p1005`, `feat/ipp-duplex`, or `fix/usb-reconnect`).
3. **Make clean, scoped changes** and keep existing comments/licensing headers intact.
4. **Verify tests & build:**
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
5. **Open a Pull Request** using [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md).

---

## How to Add Support for a New Printer

PrintBox separates **USB transport/lifecycle** (`PrinterManager`), **network discovery** (`NsdHelper`), **IPP/Raw job intake** (`AirPrintServer`, `RawPrintServer`), **raster conversion** (`AirPrintConverter`, `RasterToPbm`, `ZjsNative`), and **supply telemetry** (`PjlSupplyParser`, `RefillTracker`).

Follow these steps when onboarding another printer model:

### 1. USB Detection & Device Filter
- Check `app/src/main/res/xml/device_filter.xml`:
  - Standard USB Printer Class (`class="7"`) devices are already matched automatically.
  - If your printer initially enumerates as another USB class (such as Mass Storage / HP Smart Install), add its `<usb-device vendor-id="..." product-id="..." />` decimal entry (or match in `PrinterManager.kt`).
- Check `PrinterManager.kt`:
  - Inspect how `HP_VENDOR_ID` (`0x03F0`) is handled in `scanAndConnect`, `switchSmartInstallMode`, and `initializeDevice`.
  - Generalize vendor/product checks or replace fixed `0x03F0` assumptions with a printer profile table (matching by USB `vendorId:productId` or IEEE-1284 `GET_DEVICE_ID` string from Bulk/Control transfer `0xA1 / 0x00`).

### 2. Mode Switching & Cold-Boot Firmware (If Required)
- **Raw passthrough (`:9100`):** Printers that do not require Smart Install switching or cold-boot firmware often already work with desktop drivers over `RawPrintServer` (`:9100`) once USB Bulk IN/OUT endpoints are claimed.
- **Smart Install / Virtual CD mode:** If the printer enumerates as USB Mass Storage (`USB_CLASS_MASS_STORAGE`), verify whether it expects SCSI vendor command `0xD0` (`PrinterManager.switchSmartInstallMode`) or another mode-switch sequence.
- **Cold-boot firmware:** Host-based printers like the HP LaserJet P1102 require downloading firmware (`FIRMWARE_ASSET_NAME` / `sihpP1102.dl`) on power-on when `FWVER:` is absent from the IEEE-1284 Device ID.
  > **Licensing note:** Do **not** commit proprietary third-party firmware blobs unless redistribution is permitted. Prefer supporting user-supplied firmware paths or documented extraction instructions.

### 3. Raster / Page Description Format (`AirPrintConverter` & `app/src/main/cpp/`)
Incoming AirPrint/IPP jobs (`PDF`, `image/urf`, `image/pwg-raster`, `JPEG`/`PNG`/`WEBP`) go through `AirPrintConverter.convertDocumentToZjs`:
- **Other ZjStream printers** (e.g., HP LaserJet P1005/P1006/P1505/M1120): Verify ZjStream chunk headers and paper/resolution flags in `app/src/main/cpp/foo2zjs_jni.c` and `zjs.h`. Any new JNI path touching global state in C **must** stay serialized (`@Synchronized`).
- **Native PDF / PWG-Raster / URF / PCL / ESC-P printers:** Instead of converting PBM bitmaps through `ZjsNative.convertPbmToZjs`, route directly to the printer's native stream encoder or passthrough in `AirPrintConverter.kt`, and update `document-format-supported` in `AirPrintServer.kt` plus `pdl` TXT records in `NsdHelper.kt`.

### 4. Printer Identity Over mDNS & IPP
- `NsdHelper.kt` advertises `_ipp._tcp,_print`, `_universal._sub._ipp._tcp` (`JmDNS`), `_pdl-datastream._tcp.`, and `_printer._tcp.` with `ty` and `product` attributes.
- `AirPrintServer.kt` reports `printerName`, `printerMakeAndModel`, and `printerInfo` in IPP `Get-Printer-Attributes`.
- Replace hardcoded `"HP LaserJet Pro P1102"` strings cleanly with metadata from the connected printer profile or parsed IEEE-1284 Device ID (`MFG:` / `MDL:`).

### 5. Supply & Toner Telemetry
- `PjlSupplyParser.kt` parses `@PJL INFO SUPPLIES` and `@PJL INFO PAGECOUNT` responses. Add new vendor PJL/status patterns and unit tests in `app/src/test/java/com/usbprint/server/PjlSupplyParserTest.kt`.
- `RefillTracker.kt` estimates remaining toner using dark-pixel coverage weighted against a rated page yield (`1,600` pages at `5%` for `CE285A`). Parameterize yield/coverage constants per cartridge when supporting additional models.

---

## How to Add a Feature

Identify which module owns the change before editing:

| Area | Primary Files |
| :--- | :--- |
| IPP / AirPrint attributes & job queue | `AirPrintServer.kt`, `NsdHelper.kt` |
| Document decoding & dithering | `AirPrintConverter.kt`, `HalftoneDither.kt`, `RasterToPbm.kt` |
| Raw JetDirect TCP (`:9100`) | `RawPrintServer.kt` |
| USB transport & firmware | `PrinterManager.kt`, `device_filter.xml` |
| Persistence & toner tracking | `AppMemoryStore.kt`, `AppState.kt`, `RefillTracker.kt`, `PjlSupplyParser.kt` |
| Diagnostics HTTP (`:9101`) | `DiagnosticHttpServer.kt` |
| Android TV 10-foot UI | `MainActivity.kt`, `res/layout-w600dp/activity_main.xml`, `res/layout/activity_main.xml` |

### UI & Security Guidelines
- **10-Foot TV UX:** Every interactive control on the TV screen must support D-pad focus (`android:focusable="true"`), clear focus rings (`tv_btn_bg.xml`), and clean `nextFocus*` navigation without touch assumptions.
- **Network & Parser Safety:** Keep concurrency caps (`Semaphore`), socket timeouts, bounded header/line reads, duplicate HTTP framing checks, and PDF page/byte limits (`MAX_PDF_PAGES`, `MAX_PBM_BYTES`) intact.
- **IPC & Permissions:** Keep internal Android services `android:exported="false"`, pin dynamic `PendingIntent`s to `packageName`, use `PendingIntent.FLAG_IMMUTABLE`, and register internal broadcast receivers with `ContextCompat.RECEIVER_NOT_EXPORTED`.
- **Clean Commits:** Never commit `local.properties`, `.keystore` files, `.gradle/`, `app/.cxx/`, `app/build/`, or personal filesystem paths/IPs.

---

## How to Fix a Bug or Report an Issue

1. Reproduce cleanly and inspect live logs from the read-only diagnostics endpoint:
   ```bash
   curl -s http://<tv-ip>:9101/
   ```
   Or watch `adb logcat` filtered on the app tag/PID.
2. Add or update unit tests under `app/src/test/java/com/usbprint/server/` covering parsing, dithering, raster validation, or supply math whenever possible.
3. Verify on hardware if USB, mDNS, or TV focus behavior changed (`./scripts/deploy.sh <tv-ip>`). Remember to disable **USB Debugging** on single-port Android TV boxes (like Xiaomi Mi Box S) when testing USB Host attachment.
4. For bug reports or new printer requests, use the templates under [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/) so maintainers receive the VID/PID, client OS, and `:9101` diagnostic snapshot upfront.

---

## License Compliance

- Kotlin/Android source files (`app/src/main/`, `app/src/test/`, `scripts/`) are contributed under the **[MIT License](LICENSE)**.
- Changes inside `app/src/main/cpp/` (`foo2zjs_jni.c`, `jbig.c`, `jbig_ar.c`, headers) remain subject to the **GNU General Public License (GPL v2 or later)**. Keep existing license headers intact.
