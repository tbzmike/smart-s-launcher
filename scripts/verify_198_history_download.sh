#!/usr/bin/env bash
set -euo pipefail
sed -i 's/minifyEnabled true/minifyEnabled false/g; s/shrinkResources = true/shrinkResources = false/g' app/build.gradle
bash ./gradlew --no-configuration-cache :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

for test in \
  fr.neamar.kiss.androidTest.HistoryRestart198SeedTest \
  fr.neamar.kiss.androidTest.HistoryRestart198VerifyTest \
  fr.neamar.kiss.androidTest.LockedHistoryPopup196IntegrationTest \
  fr.neamar.kiss.androidTest.AutoDataWiring196IntegrationTest \
  fr.neamar.kiss.db.SearchRecallDatabase195RegressionTest; do
  echo "RUN $test"
  adb shell am force-stop com.tbzmike.smartslauncher.debug
  test_name="$(echo "$test" | tr '.' '_')"
  adb shell am instrument -w -r -e class "$test" \
    com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "result-$test_name.log"
  grep -E 'OK \([0-9]+ tests?\)' "result-$test_name.log"
  grep -F 'INSTRUMENTATION_CODE: -1' "result-$test_name.log"
  if grep -q 'FAILURES!!!' "result-$test_name.log"; then exit 1; fi
done
echo 'PASS: Cold process restart, locked popup, Auto Data and search recall'
