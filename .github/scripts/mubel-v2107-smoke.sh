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
adb logcat -c

echo '=== COLD LAUNCH ===' | tee -a "$REPORT"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACT" | tee -a "$REPORT"
sleep 7
PID="$(adb shell pidof "$PKG" | tr -d '\r')"
test -n "$PID"
echo "PID=$PID" | tee -a "$REPORT"
adb exec-out screencap -p > mubel-v2103/dist/SCREEN_MAIN.png

# WebView erişilebilirlik ağacı üzerinden KABLOSUZ düğmesini doğrula ve dokun.
adb shell uiautomator dump /sdcard/u.xml >/dev/null
adb pull /sdcard/u.xml /tmp/u.xml >/dev/null
grep -q 'KABLOSUZ' /tmp/u.xml
python3 - <<'PY'
import re, subprocess, xml.etree.ElementTree as ET
root=ET.parse('/tmp/u.xml').getroot()
for n in root.iter('node'):
    if n.attrib.get('text')=='KABLOSUZ':
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.attrib['bounds'])
        if not m: raise SystemExit('KABLOSUZ bounds invalid')
        x=(int(m.group(1))+int(m.group(3)))//2
        y=(int(m.group(2))+int(m.group(4)))//2
        subprocess.check_call(['adb','shell','input','tap',str(x),str(y)])
        break
else:
    raise SystemExit('KABLOSUZ button not found')
PY
sleep 2

adb shell uiautomator dump /sdcard/w.xml >/dev/null
adb pull /sdcard/w.xml /tmp/w.xml >/dev/null
grep -q 'IR DONANIM TEST' /tmp/w.xml
adb exec-out screencap -p > mubel-v2103/dist/SCREEN_WIRELESS.png

# IR donanım testine dokun. Emülatörde IR yoktur; amaç izin/crash yolunun güvenli olmasıdır.
python3 - <<'PY'
import re, subprocess, xml.etree.ElementTree as ET
root=ET.parse('/tmp/w.xml').getroot()
for n in root.iter('node'):
    if n.attrib.get('text')=='IR DONANIM TEST':
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.attrib['bounds'])
        if not m: raise SystemExit('IR button bounds invalid')
        x=(int(m.group(1))+int(m.group(3)))//2
        y=(int(m.group(2))+int(m.group(4)))//2
        subprocess.check_call(['adb','shell','input','tap',str(x),str(y)])
        break
else:
    raise SystemExit('IR test button not found')
PY
sleep 2

test -n "$(adb shell pidof "$PKG" | tr -d '\r')"
adb shell uiautomator dump /sdcard/ir.xml >/dev/null
adb pull /sdcard/ir.xml /tmp/ir.xml >/dev/null
cp /tmp/ir.xml mubel-v2103/dist/UI_AFTER_IR.xml
adb exec-out screencap -p > mubel-v2103/dist/SCREEN_IR_TEST.png

# Önceki gerçek telefon hatasını doğrudan engelle: TRANSMIT_IR SecurityException veya crash olursa build başarısız.
LOG="$(adb logcat -d)"
if printf '%s\n' "$LOG" | grep -E 'FATAL EXCEPTION|Process: com\.mubel\.kantar\.v2106wireless|SecurityException.*TRANSMIT_IR'; then
  echo 'FAIL: crash/security error found' | tee -a "$REPORT"
  exit 1
fi

echo 'SMOKE_TEST=PASS' | tee -a "$REPORT"
