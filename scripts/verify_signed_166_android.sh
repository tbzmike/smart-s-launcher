#!/usr/bin/env bash
set -euo pipefail
APP_PACKAGE="com.tbzmike.smartslauncher"
RESULT_DIR="verification-results"
mkdir -p "$RESULT_DIR"
adb logcat -c
adb install -r verified-apk/Smart-S-Launcher-3.30.166.apk
adb shell input keyevent 82
adb shell am start -W -n "$APP_PACKAGE/fr.neamar.kiss.MainActivity" | tee "$RESULT_DIR/release-launch.txt"
grep -F 'Status: ok' "$RESULT_DIR/release-launch.txt"
adb shell pidof "$APP_PACKAGE"

# Find the actual search field instead of assuming screen coordinates or keyboard layout.
for attempt in $(seq 1 15); do
  adb shell uiautomator dump /sdcard/release-window.xml >/dev/null
  adb pull /sdcard/release-window.xml "$RESULT_DIR/release-window.xml" >/dev/null
  if python3 - "$RESULT_DIR/release-window.xml" "$RESULT_DIR/search-coordinates.txt" <<'PY'
import re,sys,xml.etree.ElementTree as ET
from pathlib import Path
nodes = ET.parse(sys.argv[1]).getroot().iter('node')
search = next((n for n in nodes if n.attrib.get('resource-id','').endswith(':id/searchEditText')), None)
if search is None: raise SystemExit(1)
left,top,right,bottom = map(int,re.findall(r'\d+',search.attrib['bounds']))
Path(sys.argv[2]).write_text(f'{(left+right)//2} {(top+bottom)//2}\n')
PY
  then break; fi
  sleep 1
done
read -r search_x search_y < "$RESULT_DIR/search-coordinates.txt"
adb shell input tap "$search_x" "$search_y"
adb shell input text '1+1'
for attempt in $(seq 1 15); do
  adb shell uiautomator dump /sdcard/release-query.xml >/dev/null
  adb pull /sdcard/release-query.xml "$RESULT_DIR/release-query.xml" >/dev/null
  if python3 - "$RESULT_DIR/release-query.xml" <<'PY'
import sys,xml.etree.ElementTree as ET
nodes = ET.parse(sys.argv[1]).getroot().iter('node')
assert any('= 2' in n.attrib.get('text','') for n in nodes), 'Calculator search has not completed'
PY
  then break; fi
  sleep 1
done
python3 - "$RESULT_DIR/release-query.xml" <<'PY'
import sys,xml.etree.ElementTree as ET
assert any('= 2' in n.attrib.get('text','') for n in ET.parse(sys.argv[1]).getroot().iter('node'))
print('PASS: final production-signed, optimized APK launches and returns the calculator result.')
PY
adb exec-out screencap -p > "$RESULT_DIR/signed-release-search.png"
adb logcat -d > "$RESULT_DIR/release-logcat.txt"
python3 - "$RESULT_DIR/release-logcat.txt" <<'PY'
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
assert 'FATAL EXCEPTION' not in text, 'Fatal exception while verifying signed release'
assert 'ANR in com.tbzmike.smartslauncher' not in text, 'Launcher ANR while verifying signed release'
PY
