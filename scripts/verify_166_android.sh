#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(pwd)"
BASELINE_DIR="$ROOT_DIR/baseline153"
RESULT_DIR="$ROOT_DIR/verification-results"
APP_PACKAGE="com.tbzmike.smartslauncher.debug"
RUNNER="$APP_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
mkdir -p "$RESULT_DIR"

run_test() {
  local test_class="$1" log_file="$2"
  adb shell am instrument -w -r -e class "$test_class" "$RUNNER" | tee "$log_file"
  python3 - "$log_file" <<'PY'
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
assert "OK (" in text and "FAILURES!!!" not in text, text
assert "INSTRUMENTATION_CODE: -1" in text, text
PY
}

git worktree add --detach "$BASELINE_DIR" 145b42e7f4f954068b087f7958f62d47c0f10905
cp app/src/androidTest/java/fr/neamar/kiss/androidTest/BaselineBehaviorTest.java \
  "$BASELINE_DIR/app/src/androidTest/java/fr/neamar/kiss/androidTest/"
# Instrumentation and the app share Kotlin and desugared Java runtime classes. Build both
# comparison APKs without shrinking so a partially retained test runtime cannot shadow the app's
# runtime. The separately built, signed release retains the original production optimization.
python3 - "$BASELINE_DIR" "$ROOT_DIR" <<'PY'
import sys
from pathlib import Path
for directory in sys.argv[1:]:
    build = Path(directory) / 'app/build.gradle'
    source = build.read_text()
    assert source.count('minifyEnabled true') == 2
    assert source.count('shrinkResources = true') == 2
    build.write_text(source.replace('minifyEnabled true','minifyEnabled false')
                           .replace('shrinkResources = true','shrinkResources = false'))
PY
(
  cd "$BASELINE_DIR"
  bash ./gradlew assembleDebug assembleDebugAndroidTest --stacktrace
)
bash ./gradlew assembleDebug assembleDebugAndroidTest --stacktrace
adb logcat -c
adb shell input keyevent 82
adb install -r "$BASELINE_DIR/app/build/outputs/apk/debug/app-debug.apk"
adb install -r "$BASELINE_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
run_test fr.neamar.kiss.androidTest.BaselineBehaviorTest "$RESULT_DIR/baseline-153.log"
adb pull "/sdcard/Android/data/$APP_PACKAGE/files/baseline-behavior.json" "$RESULT_DIR/153.json"

# Upgrade the same installed app so the real schema migration is exercised.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_test 'fr.neamar.kiss.androidTest.FeaturePortBehaviorTest#schemaUpgradeKeepsBaselineHistoryAndMetadataCacheInvalidatesOnce' \
  "$RESULT_DIR/upgrade-166.log"
run_test fr.neamar.kiss.androidTest.BaselineBehaviorTest "$RESULT_DIR/baseline-166.log"
adb pull "/sdcard/Android/data/$APP_PACKAGE/files/baseline-behavior.json" "$RESULT_DIR/166.json"
python3 - "$RESULT_DIR" <<'PY'
import json,sys
from pathlib import Path
root = Path(sys.argv[1])
baseline = json.loads((root / "153.json").read_text())
updated = json.loads((root / "166.json").read_text())
assert baseline == updated, json.dumps({"153":baseline,"166":updated},indent=2)
print("PASS: all row text/icon measurements match 3.30.153 through five lifecycle/recycling stages.")
(root / "baseline-comparison.txt").write_text("PASS: all measured row text/icon sizes match 3.30.153.\n")
PY
run_test fr.neamar.kiss.androidTest.FeaturePortBehaviorTest "$RESULT_DIR/features-166.log"
adb pull "/sdcard/Android/data/$APP_PACKAGE/files/verification-screenshots" "$RESULT_DIR/screenshots"
adb logcat -d > "$RESULT_DIR/logcat.txt"
python3 - "$RESULT_DIR/logcat.txt" <<'PY'
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
assert "FATAL EXCEPTION" not in text, "Fatal exception found in Android verification log"
assert "ANR in com.tbzmike.smartslauncher" not in text, "Launcher ANR during verification"
print("PASS: no fatal exception or launcher ANR in Android verification log.")
PY
