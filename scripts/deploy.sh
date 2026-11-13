#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
IP="${1:-${PRINTBOX_TV_IP:-}}"
APK="${APK_PATH:-$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk}"

if [[ -z "$IP" ]]; then
    echo "Usage: $0 <tv-box-ip> (or set PRINTBOX_TV_IP)"
    exit 1
fi

if [[ ! -f "$APK" ]]; then
    echo "APK not found at $APK"
    echo "Build it first with: ./gradlew assembleDebug"
    exit 1
fi

echo "Waiting for ADB over TCP on $IP:5555..."
for _ in {1..30}; do
    adb connect "$IP:5555" >/dev/null 2>&1 || true
    if adb -s "$IP:5555" get-state 2>/dev/null | grep -q "device"; then
        echo "Connected to $IP:5555. Installing APK..."
        adb -s "$IP:5555" install -r "$APK"
        echo "Launching PrintBox..."
        adb -s "$IP:5555" shell am start -n com.usbprint.server/.MainActivity
        echo "Deployed PrintBox to $IP. If your Mi Box shares ADB and USB host on one port, disable USB Debugging afterward."
        exit 0
    fi
    sleep 1
done

echo "Timed out waiting for ADB connection to $IP:5555."
exit 1
