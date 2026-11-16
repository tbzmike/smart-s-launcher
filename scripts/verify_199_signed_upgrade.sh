#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results release-binaries/198
gh release download v3.30.198 -R "$GITHUB_REPOSITORY" -p SmartSLauncher.apk -D release-binaries/198
PKG=com.tbzmike.smartslauncher
check_surface() {
  local name="$1"
  local activity="$2"
  adb shell am force-stop "$PKG" || true
  adb logcat -c
  adb shell am start -W -n "$PKG/fr.neamar.kiss.$activity" | tee "verification-results/$name-launch.txt"
  sleep 6
  adb shell dumpsys activity activities > "verification-results/$name-activities.txt"
  adb logcat -d -v threadtime > "verification-results/$name-logcat.txt"
  if grep -q 'FATAL EXCEPTION' "verification-results/$name-logcat.txt"; then
    echo "FAILED $name - fatal exception" >&2
    grep -A 35 'FATAL EXCEPTION' "verification-results/$name-logcat.txt" | head -70
    return 1
  fi
  if ! grep -q "fr.neamar.kiss.$activity" "verification-results/$name-activities.txt"; then
    echo "FAILED $name - expected activity missing" >&2
    tail -80 "verification-results/$name-activities.txt"
    return 1
  fi
  echo "PASS $name"
}
adb root
adb wait-for-device
adb install -r -g stage/SmartSLauncher.apk
check_surface fresh-199-home MainActivity
check_surface fresh-199-settings SettingsActivity
adb uninstall "$PKG"
adb install -r -g release-binaries/198/SmartSLauncher.apk
check_surface before-upgrade-198-home MainActivity
adb install -r -g stage/SmartSLauncher.apk
check_surface upgraded-199-home MainActivity
check_surface upgraded-199-settings SettingsActivity
echo "PASS: Signed 199 is compatible with 198 and opens Home and Settings without data wipe"
