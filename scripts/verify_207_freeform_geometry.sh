#!/usr/bin/env bash
set -euo pipefail
# Existing regression runner intentionally compiles unminified instrumentation APKs.
# Rebuild the minified app here to prove app_process entry point retention, not merely Java source.
git restore app/build.gradle
gradle_ok=0
for attempt in 1 2 3; do
  refresh=()
  if [ "$attempt" -gt 1 ]; then refresh=(--refresh-dependencies); fi
  if bash ./gradlew "${refresh[@]}" --no-configuration-cache -PshortcutFixtures :app:assembleDebug :shortcut-fixture:assembleTargetDebug :shortcut-fixture:assembleIceboxDebug --stacktrace; then
    gradle_ok=1
    break
  fi
  sleep 6
done
test "$gradle_ok" = 1
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r shortcut-fixture/build/outputs/apk/target/debug/shortcut-fixture-target-debug.apk
adb install -r shortcut-fixture/build/outputs/apk/icebox/debug/shortcut-fixture-icebox-debug.apk
# Real Android tasks in an isolated CI emulator. No production/device settings are changed.
adb root
adb wait-for-device
test "$(adb shell id -u | tr -d '\r')" = 0
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb shell settings put global enable_non_resizable_multi_window 1
adb reboot
adb wait-for-device
booted=0
for attempt in $(seq 1 180); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
    booted=1
    break
  fi
  sleep 1
done
test "$booted" = 1
adb root
adb wait-for-device
test "$(adb shell id -u | tr -d '\r')" = 0
adb shell input keyevent 82
mkdir -p verification-results
python3 scripts/verify_206_task_transitions.py | tee verification-results/native-freeform-transitions.log

# Reinstall the unminified instrumentation build only for the real service/touch test.
# The preceding native transition test ran against the minified app_process helper.
sed -i 's/minifyEnabled true/minifyEnabled false/g; s/shrinkResources = true/shrinkResources = false/g' app/build.gradle
gradle_ok=0
for attempt in 1 2 3; do
  refresh=()
  if [ "$attempt" -gt 1 ]; then refresh=(--refresh-dependencies); fi
  if bash ./gradlew "${refresh[@]}" --no-configuration-cache :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace; then
    gradle_ok=1
    break
  fi
  sleep 6
done
test "$gradle_ok" = 1
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell appops set com.tbzmike.smartslauncher.debug SYSTEM_ALERT_WINDOW allow
adb shell am force-stop com.tbzmike.smartslauncher.debug
file=verification-results/freeform-real-gestures.log
# Collect screenshots even if the test fails. Never skip the test or accept an assertion failure.
set +e
adb shell am instrument -w -r -e class fr.neamar.kiss.freeform.FreeformRealGesture207Test \
  com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$file"
code=${PIPESTATUS[0]}
adb pull /data/user/0/com.tbzmike.smartslauncher.debug/files/freeform207-evidence verification-results/ || true
set -e
test "$code" = 0
grep -E 'OK \([0-9]+ tests?\)' "$file"
grep -F 'INSTRUMENTATION_CODE: -1' "$file"
test -s verification-results/freeform207-evidence/fullscreen.png
test -s verification-results/freeform207-evidence/mid-drag.png
test -s verification-results/freeform207-evidence/mid-resize.png
test -s verification-results/freeform207-evidence/bubble.png
test -s verification-results/freeform207-evidence/rotated.png
git restore app/build.gradle
