#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results
python3 - <<'PY'
from pathlib import Path
p = Path("app/build.gradle")
source = p.read_text()
assert 'versionName "3.30.180"' in source
assert 'versionCode 608' in source
assert source.count("minifyEnabled true") == 2
p.write_text(source.replace("minifyEnabled true", "minifyEnabled false")
                   .replace("shrinkResources = true", "shrinkResources = false"))
PY
bash ./gradlew assembleDebug assembleDebugAndroidTest --stacktrace
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r -e class fr.neamar.kiss.androidTest.BackupRestore180RegressionTest \
  com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner \
  | tee verification-results/backup-180-android35.log
python3 - <<'PY'
from pathlib import Path
result = Path("verification-results/backup-180-android35.log").read_text()
assert "OK (" in result and "FAILURES!!!" not in result, result[-6000:]
assert "INSTRUMENTATION_CODE: -1" in result, result[-6000:]
PY
adb logcat -d > verification-results/backup-180-logcat.txt || true
echo "PASS: Smart S 180 backups include settings stores, SQLite catalog and portable tag links; old format accepted"
