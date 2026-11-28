#!/usr/bin/env bash
set -euo pipefail
# Existing regression runner intentionally compiles unminified instrumentation APKs.
# Rebuild the minified app here to prove app_process entry point retention, not merely Java source.
if [ -n "${SMART_S_RUNTIME_INPUT:-}" ]; then
  adb install -r -g "$SMART_S_RUNTIME_INPUT/minified-debug.apk"
  adb install -r "$SMART_S_RUNTIME_INPUT/target.apk"
  adb install -r "$SMART_S_RUNTIME_INPUT/icebox.apk"
else
  git restore app/build.gradle
  mkdir -p shortcut-fixture/src/main/res/raw
  if ! command -v ffmpeg >/dev/null; then sudo apt-get update -qq && sudo apt-get install -y ffmpeg >/dev/null; fi
  ffmpeg -nostdin -loglevel error -y -f lavfi -i testsrc2=size=64x48:rate=12 -f lavfi -i sine=frequency=440 -t 18 -c:v libx264 -pix_fmt yuv420p -c:a aac shortcut-fixture/src/main/res/raw/bubble_playback.mp4
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
fi
# Real Android tasks in an isolated CI emulator. No production/device settings are changed.
ensure_root() {
  for attempt in 1 2 3; do
    timeout 45 adb root || true
    if timeout 45 adb wait-for-device && [ "$(timeout 10 adb shell id -u | tr -d '\r')" = 0 ]; then
      return 0
    fi
  done
  return 1
}
ensure_root
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
ensure_root
adb shell input keyevent 82
mkdir -p verification-results
python3 scripts/verify_206_task_transitions.py | tee verification-results/native-freeform-transitions.log

# Reinstall the unminified instrumentation build only for the real service/touch test.
# The preceding native transition test ran against the minified app_process helper.
if [ -n "${SMART_S_RUNTIME_INPUT:-}" ]; then
  adb install -r -g "$SMART_S_RUNTIME_INPUT/instrumented-debug.apk"
  adb install -r "$SMART_S_RUNTIME_INPUT/instrumentation.apk"
else
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
fi
adb shell appops set com.tbzmike.smartslauncher.debug SYSTEM_ALERT_WINDOW allow
adb shell am force-stop com.tbzmike.smartslauncher.debug
# Exercise Home with the launcher under test. The stock Pixel launcher in the
# emulator can leave its own ANR dialog covering otherwise valid app pixels.
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell input keyevent KEYCODE_HOME
file=verification-results/freeform-real-gestures.log
# Collect screenshots even if the test fails. Never skip the test or accept an assertion failure.
set +e
adb shell am instrument -w -r -e class fr.neamar.kiss.freeform.FreeformRealGesture207Test \
  com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$file"
code=${PIPESTATUS[0]}
if ! grep -qE 'OK \([0-9]+ tests?\)' "$file"; then
  adb shell dumpsys window -a windows > verification-results/window-state.txt
  cat verification-results/window-state.txt | sed -n '/Window #[0-9].*Fixture/,/Window #[0-9]/p'
fi
adb pull /data/user/0/com.tbzmike.smartslauncher.debug/files/freeform207-evidence verification-results/ || true
if ! grep -qE 'OK \([0-9]+ tests?\)' "$file"; then
  for file_dump in verification-results/freeform207-evidence/{activity,window,SurfaceFlinger,live-activity,live-window,live-display,live-SurfaceFlinger,live-logcat}.txt; do
    if test -f "$file_dump"; then grep -Ei 'tbzmike.shortcutfixture|mCurrentFocus|mFocusedApp|mResumedActivity|RequestedLayerState\{Task=' "$file_dump" | tail -80 || true; fi
  done
fi
set -e
test "$code" = 0
grep -E 'OK \([0-9]+ tests?\)' "$file"
grep -F 'INSTRUMENTATION_CODE: -1' "$file"
test -s verification-results/freeform207-evidence/fullscreen.png
test -s verification-results/freeform207-evidence/mid-drag.png
test -s verification-results/freeform207-evidence/mid-resize.png
test -s verification-results/freeform207-evidence/bubble.png
test -s verification-results/freeform207-evidence/rotated.png
test -s verification-results/freeform207-evidence/restored-from-fullscreen.png
test -s verification-results/freeform207-evidence/restored-repeated.png
for live in live-expanded live-miniature live-restored live-repeated live-maximized live-after-maximize; do
  test -s "verification-results/freeform207-evidence/$live.png"
done
adb logcat -d -s SmartSFreeform220:I > verification-results/restore-latency.log
if [ -z "${SMART_S_RUNTIME_INPUT:-}" ]; then git restore app/build.gradle; fi
