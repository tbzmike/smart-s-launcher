#!/usr/bin/env bash
set -euo pipefail
# Existing regression runner intentionally compiles unminified instrumentation APKs.
# Rebuild the minified app here to prove app_process entry point retention, not merely Java source.
git restore app/build.gradle
gradle_ok=0
for attempt in 1 2 3; do
  if bash ./gradlew --no-configuration-cache :app:assembleDebug :shortcut-fixture:assembleTargetDebug :shortcut-fixture:assembleIceboxDebug --stacktrace; then
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
