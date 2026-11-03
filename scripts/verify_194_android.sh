#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results
python3 - <<'PY'
from pathlib import Path
p=Path('app/build.gradle')
s=p.read_text()
assert 'versionCode 622' in s and 'versionName "3.30.194"' in s
assert s.count('minifyEnabled true') == 2
p.write_text(s.replace('minifyEnabled true','minifyEnabled false')
              .replace('shrinkResources = true','shrinkResources = false'))
PY
bash ./gradlew --no-configuration-cache -PshortcutFixtures :app:assembleDebug :app:assembleDebugAndroidTest \
  :shortcut-fixture:assembleTargetDebug :shortcut-fixture:assembleIceboxDebug \
  :shortcut-fixture:assembleIceboxSdkDebug --stacktrace
adb root
adb wait-for-device
adb install -r shortcut-fixture/build/outputs/apk/iceboxSdk/debug/shortcut-fixture-iceboxSdk-debug.apk
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r -g shortcut-fixture/build/outputs/apk/target/debug/shortcut-fixture-target-debug.apk
adb install -r -g shortcut-fixture/build/outputs/apk/icebox/debug/shortcut-fixture-icebox-debug.apk
adb logcat -c
collect() {
  adb logcat -d > verification-results/logcat.txt || true
  adb pull /sdcard/Android/data/com.tbzmike.smartslauncher.debug/files/verification188 verification-results/screenshots || true
}
trap collect EXIT
FAILED=()
run_test() {
  local class="$1" log="$2"
  if ! adb shell am instrument -w -r -e class "$class" \
      com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"; then
    FAILED+=("$class"); return
  fi
  if ! python3 - "$log" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
assert 'OK (' in s and 'FAILURES!!!' not in s and 'INSTRUMENTATION_CODE: -1' in s, s[-8000:]
PY
  then FAILED+=("$class"); fi
}
reset_app() {
  adb shell am force-stop com.tbzmike.smartslauncher.debug
  adb shell pm clear com.tbzmike.smartslauncher.debug
  for permission in READ_CONTACTS CALL_PHONE READ_PHONE_STATE READ_CALL_LOG READ_SMS \
    ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION POST_NOTIFICATIONS; do
    adb shell pm grant com.tbzmike.smartslauncher.debug "android.permission.$permission"
  done
}
reset_app
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test fr.neamar.kiss.androidTest.HomeRotation192RegressionTest verification-results/home-rotation.log
reset_app
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
adb shell pm revoke com.tbzmike.smartslauncher.debug com.catchingnow.icebox.SDK
run_test 'fr.neamar.kiss.ShortcutLaunch186IntegrationTest#realIceBoxIdsOpenDisabledHiddenAndComponentDisabledAppsWithoutSdkOrRoot' verification-results/icebox-native-routes.log
reset_app
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
adb shell pm grant com.tbzmike.smartslauncher.debug com.catchingnow.icebox.SDK
run_test 'fr.neamar.kiss.ShortcutLaunch186IntegrationTest#missingIceBoxShortcutUsesItsSdkWithoutRoot' verification-results/icebox-sdk.log
reset_app
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test fr.neamar.kiss.androidTest.LowOverhead187RegressionTest verification-results/disabled-features.log
reset_app
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test fr.neamar.kiss.androidTest.NotificationShutdown190RegressionTest verification-results/listener-screen-off.log
reset_app
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test fr.neamar.kiss.androidTest.CommunicationIndexLock191RegressionTest verification-results/database-lock-crash.log
reset_app
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test fr.neamar.kiss.androidTest.HomeReturnAnimationRegressionTest verification-results/home-animation.log
reset_app
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test 'fr.neamar.kiss.androidTest.HomeColdRestart192RegressionTest#saveProcessBeforeManualRestart' verification-results/home-restart-seed.log
adb shell am force-stop com.tbzmike.smartslauncher.debug
run_test 'fr.neamar.kiss.androidTest.HomeColdRestart192RegressionTest#homeAfterManualRestartDoesNotRestartTheProcessAgain' verification-results/home-restart-check.log
reset_app
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test 'fr.neamar.kiss.ShortcutLaunch186IntegrationTest#openedShortcutIsPinnedWithoutLosingOtherPinsAndSurvivesDynamicRemoval,fr.neamar.kiss.ShortcutLaunch186IntegrationTest#disabledAndHiddenNativeShortcutActuallyUnfreezesAndOpensExactTarget,fr.neamar.kiss.ShortcutLaunch186IntegrationTest#iceBoxMetadataRetainsTargetEvenWhenItsPackageIsHidden,fr.neamar.kiss.ShortcutLaunch186IntegrationTest#legacyExactIntentRetainsItsDataExtrasAndEnablesDisabledComponent,fr.neamar.kiss.ShortcutLaunch186IntegrationTest#iceboxNativeRouteWorksAndStaleWrapperUnfreezesVerifiedTarget,fr.neamar.kiss.ShortcutLaunch186IntegrationTest#exactRouteCodecPreservesTypedArraysAndIntentStack' verification-results/shortcut-destinations.log
STOCK_HOME="$(adb shell cmd package query-activities --brief -a android.intent.action.MAIN \
  -c android.intent.category.HOME | python3 -c 'import re,sys; print(next(x.strip() for x in sys.stdin if re.fullmatch(r"[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+",x.strip()) and "com.tbzmike.smartslauncher" not in x and "FallbackHome" not in x))')"
adb shell cmd package set-home-activity --user 0 "$STOCK_HOME"
reset_app
adb shell input keyevent KEYCODE_HOME
run_test fr.neamar.kiss.androidTest.SearchQuickActionAppearance189Test verification-results/wallpaper-search-appearance.log
run_test fr.neamar.kiss.androidTest.SearchQuickActions188RegressionTest verification-results/search-actions.log
run_test 'fr.neamar.kiss.androidTest.NotificationRestart188RegressionTest#seedReceivedAndDismissedNotification' verification-results/notification-restart-seed.log
adb shell am force-stop com.tbzmike.smartslauncher.debug
run_test 'fr.neamar.kiss.androidTest.NotificationRestart188RegressionTest#savedNotificationSurvivesARealProcessRestart' verification-results/notification-restart-check.log
run_test fr.neamar.kiss.androidTest.NotificationRetention188RegressionTest verification-results/notification-retention.log
adb pull /sdcard/Android/data/com.tbzmike.smartslauncher.debug/files/verification188 verification-results/screenshots || true
reset_app
run_test fr.neamar.kiss.androidTest.HistoryRendererPagingRegressionTest verification-results/history-layouts.log
if (( ${#FAILED[@]} > 0 )); then printf 'FAIL: %s\n' "${FAILED[@]}"; exit 1; fi
echo 'PASS: HOME redelivery, real screen rotations, process restart, crash diagnostics, database lock recovery, notification and launcher regressions.'
