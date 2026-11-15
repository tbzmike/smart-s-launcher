#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results release-binaries/196
gh release download v3.30.196 -R "$GITHUB_REPOSITORY" -p SmartSLauncher.apk -D release-binaries/196
PKG=com.tbzmike.smartslauncher
COMP="$PKG/fr.neamar.kiss.SettingsActivity"
check_settings() {
  local name="$1"
  adb shell am force-stop "$PKG" || true
  adb logcat -c
  adb shell am start -W -n "$COMP" | tee "verification-results/$name-launch.txt"
  sleep 5
  adb shell dumpsys activity activities > "verification-results/$name-activities.txt"
  adb logcat -d -v threadtime > "verification-results/$name-logcat.txt"
  if grep -q 'FATAL EXCEPTION' "verification-results/$name-logcat.txt"; then
    grep -A 30 'FATAL EXCEPTION' "verification-results/$name-logcat.txt" | head -60
    echo "FAIL: Java crash opening Settings: $name"
    return 1
  fi
  grep -q 'fr.neamar.kiss.SettingsActivity' "verification-results/$name-activities.txt"
  echo "PASS: signed Settings opened: $name"
}
adb root
adb wait-for-device
adb install -r -g stage/SmartSLauncher.apk
check_settings fresh-197
adb uninstall "$PKG"
adb install -r -g release-binaries/196/SmartSLauncher.apk
check_settings installed-196
adb install -r -g stage/SmartSLauncher.apk
check_settings upgraded-197
echo "PASS: signed v197 Settings fresh and upgraded without data wipe"
