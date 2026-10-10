#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results
python3 - <<'PY'
from pathlib import Path
p = Path("app/build.gradle")
text = p.read_text()
assert 'versionName "3.30.167"' in text
assert 'versionCode 595' in text
text = text.replace("minifyEnabled true", "minifyEnabled false")
text = text.replace("shrinkResources = true", "shrinkResources = false")
p.write_text(text)
PY
bash ./gradlew assembleDebug assembleDebugAndroidTest --stacktrace
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
set +e
adb shell am instrument -w -r -e class \
  fr.neamar.kiss.androidTest.HistoryAppearanceRegressionTest \
  com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner \
  | tee verification-results/history-regression.log
rc=${PIPESTATUS[0]}
set -e
adb logcat -d > verification-results/logcat.txt || true
python3 - <<'PY'
from pathlib import Path
text = Path('verification-results/history-regression.log').read_text()
assert 'OK (1 test)' in text, 'Regression instrumentation did not pass: ' + text[-4000:]
assert 'INSTRUMENTATION_CODE: -1' in text, 'Instrumentation failed'
log = Path('verification-results/logcat.txt').read_text()
assert 'FATAL EXCEPTION: main' not in log, 'Launcher main thread crashed'
print('PASS: configured row sizes and timestamps survive rebinding and lifecycle')
PY
exit "$rc"
