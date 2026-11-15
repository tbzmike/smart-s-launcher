#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results release-binaries
test -n "$GH_TOKEN"
gh release download v3.30.195 -R "$GITHUB_REPOSITORY" -p SmartSLauncher.apk -D release-binaries/195
gh release download v3.30.196 -R "$GITHUB_REPOSITORY" -p SmartSLauncher.apk -D release-binaries/196
PKG=com.tbzmike.smartslauncher
COMP="$PKG/fr.neamar.kiss.SettingsActivity"
check_settings() {
  local stage="$1"
  adb shell am force-stop "$PKG" || true
  adb logcat -c
  echo "START $stage" | tee "verification-results/$stage.txt"
  adb shell am start -W -n "$COMP" | tee -a "verification-results/$stage.txt"
  sleep 4
  adb shell dumpsys activity activities > "verification-results/$stage-activities.txt"
  adb logcat -d -v threadtime > "verification-results/$stage-logcat.txt"
  if grep -q 'FATAL EXCEPTION' "verification-results/$stage-logcat.txt"; then
      echo "FAIL $stage: fatal exception while SettingsActivity opened" >&2
      grep -E -A 25 'FATAL EXCEPTION' "verification-results/$stage-logcat.txt" | head -60
      return 1
  fi
  if ! grep -q 'fr.neamar.kiss.SettingsActivity' "verification-results/$stage-activities.txt"; then
    echo "FAIL $stage: SettingsActivity not in active activities" >&2
    tail -100 "verification-results/$stage-activities.txt"
    return 1
  fi
  echo "PASS $stage"
}
adb root
adb wait-for-device
adb install -r release-binaries/196/SmartSLauncher.apk
check_settings fresh-196
adb uninstall "$PKG"
adb install -r release-binaries/195/SmartSLauncher.apk
check_settings before-upgrade-195
adb install -r release-binaries/196/SmartSLauncher.apk
check_settings upgraded-196
echo "PASS: signed production Settings opens on emulator, fresh and upgrade"
