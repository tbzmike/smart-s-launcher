#!/usr/bin/env bash
set -euo pipefail

# Emulator tests are unminified, like the existing 3.30.194 verified suite.
# This substitution is local to CI runner's checkout, never changes release source.
sed -i 's/minifyEnabled true/minifyEnabled false/g; s/shrinkResources = true/shrinkResources = false/g' app/build.gradle
bash ./gradlew --no-configuration-cache :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p verification-results

for test in \
  fr.neamar.kiss.androidTest.LockedHistoryPopup196IntegrationTest \
  fr.neamar.kiss.androidTest.AutoDataWiring196IntegrationTest \
  fr.neamar.kiss.androidTest.HistoryCenterTapRegressionTest \
  fr.neamar.kiss.db.SearchRecallDatabase195RegressionTest \
  fr.neamar.kiss.androidTest.HistoryFirstSearch181RegressionTest; do
  test_name="$(echo "$test" | tr '.' '_')"
  adb shell am instrument -w -r -e class "$test" \
    com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "verification-results/$test_name.log"
  grep -E 'OK \([0-9]+ tests?\)' "verification-results/$test_name.log"
  grep -F 'INSTRUMENTATION_CODE: -1' "verification-results/$test_name.log"
  if grep -q 'FAILURES!!!' "verification-results/$test_name.log"; then exit 1; fi
done
echo "PASS: 3.30.196 locked popup, Auto Data, layouts and saved search on emulator"
