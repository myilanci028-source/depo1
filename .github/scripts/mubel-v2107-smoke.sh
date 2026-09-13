#!/usr/bin/env bash
set -euo pipefail
REPORT='mubel-v2103/dist/SMOKE_REPORT.txt'
APK='mubel-v2103/dist/MUBEL_KANTAR_2.10.6_STABIL_WIRELESS_R01.apk'
PKG='com.mubel.kantar.v2106wireless'
ACT='com.mubel.kantar.MainActivityWireless'
mkdir -p mubel-v2103/dist

echo '=== INSTALL ===' > "$REPORT"
adb install -r "$APK" | tee -a "$REPORT"
adb shell pm path "$PKG" | tee -a "$REPORT"

echo '=== REQUESTED PERMISSIONS ===' | tee -a "$REPORT"
DUMP="$(adb shell dumpsys package "$PKG")"
printf '%s\n' "$DUMP" | grep -q 'android.permission.TRANSMIT_IR'
printf '%s\n' "$DUMP" | grep -q 'android.permission.BLUETOOTH_SCAN'
printf '%s\n' "$DUMP" | grep -q 'android.permission.BLUETOOTH_CONNECT'
printf '%s\n' "$DUMP" | grep -q 'android.permission.INTERNET'
echo 'TRANSMIT_IR=DECLARED' | tee -a "$REPORT"
echo 'BLUETOOTH_SCAN=DECLARED' | tee -a "$REPORT"
echo 'BLUETOOTH_CONNECT=DECLARED' | tee -a "$REPORT"
echo 'INTERNET=DECLARED' | tee -a "$REPORT"

adb logcat -c
echo '=== COLD LAUNCH ===' | tee -a "$REPORT"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACT" | tee -a "$REPORT"
sleep 8
PID="$(adb shell pidof "$PKG" | tr -d '\r')"
test -n "$PID"
echo "PID=$PID" | tee -a "$REPORT"
adb exec-out screencap -p > mubel-v2103/dist/SCREEN_MAIN.png

adb shell uiautomator dump /sdcard/u.xml >/dev/null || true
adb pull /sdcard/u.xml mubel-v2103/dist/UI_MAIN.xml >/dev/null 2>&1 || true

# Pixel 6 / 1080x2400: KABLOSUZ butonunun gorunen ust bolgesine dokun.
adb shell input tap 930 2280 || true
sleep 2
adb exec-out screencap -p > mubel-v2103/dist/SCREEN_WIRELESS.png
adb shell uiautomator dump /sdcard/w.xml >/dev/null || true
adb pull /sdcard/w.xml mubel-v2103/dist/UI_WIRELESS.xml >/dev/null 2>&1 || true

sleep 2
test -n "$(adb shell pidof "$PKG" | tr -d '\r')"
adb exec-out screencap -p > mubel-v2103/dist/SCREEN_IR_TEST.png
adb shell uiautomator dump /sdcard/ir.xml >/dev/null || true
adb pull /sdcard/ir.xml mubel-v2103/dist/UI_AFTER_IR.xml >/dev/null 2>&1 || true

LOG="$(adb logcat -d)"
if printf '%s\n' "$LOG" | grep -E 'FATAL EXCEPTION|Process: com\.mubel\.kantar\.v2106wireless|SecurityException.*TRANSMIT_IR'; then
  echo 'FAIL: crash/security error found' | tee -a "$REPORT"
  exit 1
fi

echo 'SMOKE_TEST=PASS' | tee -a "$REPORT"
