#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results
python3 - <<'PY'
from pathlib import Path
p = Path("app/build.gradle")
source = p.read_text()
assert 'versionName "3.30.168"' in source
assert 'versionCode 596' in source
assert source.count('minifyEnabled true') == 2
p.write_text(source.replace('minifyEnabled true', 'minifyEnabled false')
                   .replace('shrinkResources = true', 'shrinkResources = false'))
PY
bash ./gradlew assembleDebug assembleDebugAndroidTest --stacktrace
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
run_test() {
  local class="$1" log="$2"
  adb shell am instrument -w -r -e class "$class" \
    com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  python3 - "$log" <<'PY'
import sys
from pathlib import Path
result = Path(sys.argv[1]).read_text()
assert "OK (" in result and "FAILURES!!!" not in result, result[-8000:]
assert "INSTRUMENTATION_CODE: -1" in result, result[-8000:]
PY
}
run_test fr.neamar.kiss.androidTest.HistoryAppearanceRegressionTest \
  verification-results/history-appearance-168.log
run_test fr.neamar.kiss.androidTest.HistoryLongTextRegressionTest \
  verification-results/history-longtext-168.log
adb logcat -d > verification-results/logcat.txt || true
echo "PASS: saved appearance + auto-scroll/auto-expand + date rail fade on Android 35"
