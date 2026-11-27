#!/usr/bin/env bash
set -euo pipefail
sed -i 's/minifyEnabled true/minifyEnabled false/g; s/shrinkResources = true/shrinkResources = false/g' app/build.gradle
gradle_ok=0
for attempt in 1 2 3; do
  if bash ./gradlew --no-configuration-cache :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace; then
    gradle_ok=1
    break
  fi
  sleep 6
done
test "$gradle_ok" = 1
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p verification-results
for test in \
  fr.neamar.kiss.result.ExactHistoryPopup200Test \
  fr.neamar.kiss.result.FreeformLockedAction206Test \
  fr.neamar.kiss.androidTest.HistoryCenterTapRegressionTest \
  fr.neamar.kiss.androidTest.VisibleHistoryTime199IntegrationTest \
  fr.neamar.kiss.androidTest.HistoryRestart198SeedTest \
  fr.neamar.kiss.androidTest.HistoryRestart198VerifyTest \
  fr.neamar.kiss.androidTest.LockedHistoryPopup196IntegrationTest \
  fr.neamar.kiss.androidTest.AutoDataWiring196IntegrationTest \
  fr.neamar.kiss.db.SearchRecallDatabase195RegressionTest \
  fr.neamar.kiss.backup.BackupHistoryMergerTest; do
  echo "RUN $test"
  adb shell am force-stop com.tbzmike.smartslauncher.debug
  file="verification-results/$(echo "$test" | tr '.' '_').log"
  adb shell am instrument -w -r -e class "$test" \
    com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$file"
  grep -E 'OK \([0-9]+ tests?\)' "$file"
  grep -F 'INSTRUMENTATION_CODE: -1' "$file"
done
echo 'PASS: Real exact popup, ordinary taps, time range and retained regressions'
