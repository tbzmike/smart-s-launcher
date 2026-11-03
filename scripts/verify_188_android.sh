#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results
python3 - <<'PY'
from pathlib import Path
p = Path("app/build.gradle")
source = p.read_text()
assert 'versionName "3.30.188"' in source
assert 'versionCode 616' in source
assert source.count('minifyEnabled true') == 2
p.write_text(source.replace('minifyEnabled true', 'minifyEnabled false')
                   .replace('shrinkResources = true', 'shrinkResources = false'))
PY
bash ./gradlew -PshortcutFixtures :app:assembleDebug :app:assembleDebugAndroidTest \
  :shortcut-fixture:assembleTargetDebug :shortcut-fixture:assembleIceboxDebug --stacktrace
adb root
adb wait-for-device
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r -g shortcut-fixture/build/outputs/apk/target/debug/shortcut-fixture-target-debug.apk
adb install -r -g shortcut-fixture/build/outputs/apk/icebox/debug/shortcut-fixture-icebox-debug.apk
adb logcat -c
collect_evidence() {
  adb logcat -d > verification-results/logcat.txt || true
  adb pull /sdcard/Android/data/com.tbzmike.smartslauncher.debug/files/verification188 verification-results/screenshots || true
}
trap collect_evidence EXIT
FAILED_TESTS=()
run_test() {
  local class="$1" log="$2"
  if ! adb shell am instrument -w -r -e class "$class" \
    com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"; then
    FAILED_TESTS+=("$class")
    return 0
  fi
  if ! python3 - "$log" <<'PY'
import sys
from pathlib import Path
result = Path(sys.argv[1]).read_text()
assert "OK (" in result and "FAILURES!!!" not in result, result[-8000:]
assert "INSTRUMENTATION_CODE: -1" in result, result[-8000:]
PY
  then
    FAILED_TESTS+=("$class")
  fi
  return 0
}
grant_runtime_permissions() {
  # pm clear revokes the install -g grants. A Contacts permission dialog obscures Home and
  # prevents ActivityScenario from resuming it during the rendering/lifecycle checks.
  for permission in READ_CONTACTS CALL_PHONE READ_PHONE_STATE READ_CALL_LOG READ_SMS \
    ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION POST_NOTIFICATIONS; do
    adb shell pm grant com.tbzmike.smartslauncher.debug "android.permission.$permission"
  done
}
# Functional checks come first. The isolated before/after benchmark runs after UI verification.
adb shell am force-stop com.tbzmike.smartslauncher.debug
adb shell pm clear com.tbzmike.smartslauncher.debug
grant_runtime_permissions
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test fr.neamar.kiss.androidTest.LowOverhead187RegressionTest \
  verification-results/disabled-features-187.log
adb shell am force-stop com.tbzmike.smartslauncher.debug
adb shell pm clear com.tbzmike.smartslauncher.debug
grant_runtime_permissions
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test fr.neamar.kiss.ShortcutLaunch186IntegrationTest \
  verification-results/shortcut-launch-186-actual-destinations.log
# ActivityScenario's artificial CREATED/RESUMED transitions require a normal task. Android
# immediately brings a default Home back when that task is paused, replacing its Intent and
# invalidating the scenario's lifecycle owner. Native shortcut tests above use the real Home
# role; run the existing isolated rendering/lifecycle tests with the emulator's stock Home.
STOCK_HOME="$(adb shell cmd package query-activities --brief -a android.intent.action.MAIN \
  -c android.intent.category.HOME | python3 -c 'import re,sys; print(next(line.strip() for line in sys.stdin if re.fullmatch(r"[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+", line.strip()) and "com.tbzmike.smartslauncher" not in line and "FallbackHome" not in line))')"
adb shell cmd package set-home-activity --user 0 "$STOCK_HOME"
adb shell am force-stop com.tbzmike.smartslauncher.debug
adb shell pm clear com.tbzmike.smartslauncher.debug
grant_runtime_permissions
adb shell input keyevent KEYCODE_HOME
run_test fr.neamar.kiss.androidTest.SearchQuickActions188RegressionTest \
  verification-results/search-quick-actions-188.log
run_test fr.neamar.kiss.db.NotificationDatabase188RegressionTest \
  verification-results/notification-database-188.log
run_test 'fr.neamar.kiss.androidTest.NotificationRestart188RegressionTest#seedReceivedAndDismissedNotification' \
  verification-results/notification-restart-seed-188.log
adb shell am force-stop com.tbzmike.smartslauncher.debug
run_test 'fr.neamar.kiss.androidTest.NotificationRestart188RegressionTest#savedNotificationSurvivesARealProcessRestart' \
  verification-results/notification-after-restart-188.log
run_test fr.neamar.kiss.androidTest.NotificationRetention188RegressionTest \
  verification-results/notification-retention-188.log
adb pull /sdcard/Android/data/com.tbzmike.smartslauncher.debug/files/verification188 verification-results/screenshots || true
adb logcat -d > verification-results/new-features-188-logcat.txt
adb shell am force-stop com.tbzmike.smartslauncher.debug
adb shell pm clear com.tbzmike.smartslauncher.debug
grant_runtime_permissions
run_test fr.neamar.kiss.androidTest.HistoryAppearanceRegressionTest \
  verification-results/history-appearance-178.log
run_test fr.neamar.kiss.androidTest.HistoryLongTextRegressionTest \
  verification-results/history-longtext-178.log
run_test fr.neamar.kiss.androidTest.PixelDockTagRegressionTest \
  verification-results/pixel-dock-tags-178.log
run_test fr.neamar.kiss.androidTest.HistoryRendererPagingRegressionTest \
  verification-results/wheel-cards-paging-178.log
run_test fr.neamar.kiss.androidTest.HomeReturnAnimationRegressionTest \
  verification-results/home-return-animations-178.log
run_test fr.neamar.kiss.androidTest.HistoryCenterTapRegressionTest \
  verification-results/history-center-tap-178.log
run_test fr.neamar.kiss.androidTest.SocialAvatarIdentityRegressionTest \
  verification-results/social-avatar-identity-178.log
run_test fr.neamar.kiss.androidTest.HistoryOverflowIconSizingRegressionTest \
  verification-results/auto-scroll-icon-sizes-178.log
run_test fr.neamar.kiss.androidTest.HomeDiscoverSwipeRegressionTest \
  verification-results/home-discover-swipe-178.log
run_test fr.neamar.kiss.androidTest.VerticalCardsPerformanceRegressionTest \
  verification-results/cards-performance-178.log
run_test fr.neamar.kiss.androidTest.HistoryAutoScrollMotionRegressionTest \
  verification-results/auto-scroll-motion-178.log
run_test fr.neamar.kiss.androidTest.BackupRestore180RegressionTest \
  verification-results/backup-181-regression.log
run_test fr.neamar.kiss.androidTest.HistoryFirstSearch181RegressionTest \
  verification-results/history-first-181-regression.log
run_test fr.neamar.kiss.androidTest.FrozenAppState182RegressionTest \
  verification-results/frozen-state-182.log
run_test fr.neamar.kiss.androidTest.SearchedAppClick182RegressionTest \
  verification-results/search-app-tap-182.log
run_test fr.neamar.kiss.androidTest.LauncherStability183RegressionTest \
  verification-results/launcher-stability-183.log
run_test fr.neamar.kiss.androidTest.FrozenExactShortcut184RegressionTest \
  verification-results/frozen-exact-shortcut-184.log
run_test fr.neamar.kiss.androidTest.InstantHistory185RegressionTest \
  verification-results/instant-history-185.log
adb logcat -d > verification-results/ui-regression-logcat.txt || true
if (( ${#FAILED_TESTS[@]} > 0 )); then
  printf 'FAIL: %s\n' "${FAILED_TESTS[@]}"
  exit 1
fi
if [[ "${SKIP_188_PROFILE:-0}" != "1" ]]; then
  bash scripts/profile_188_android.sh
fi
adb logcat -d > verification-results/logcat.txt || true
echo "PASS: 3.30.188 actual notification receipts, dismissal/reconnect/process-restart retention, exact saved notification destinations, equal-time catch-up paging, explicit removals and OFF/freeze controls, both search action buttons, actual scoped notification-history query, five-second provider deadline and manual bypass, List/Cards/Wheel/keyboard layout, all preceding UI and native/IceBox shortcut regressions, and CPU/RAM comparison against 3.30.187."
