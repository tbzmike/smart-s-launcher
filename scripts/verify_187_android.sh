#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results
python3 - <<'PY'
from pathlib import Path
p = Path("app/build.gradle")
source = p.read_text()
assert 'versionName "3.30.187"' in source
assert 'versionCode 615' in source
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
trap 'adb logcat -d > verification-results/logcat.txt || true' EXIT
run_test() {
  local class="$1" log="$2"
  adb shell am instrument -w -r -e class "$class" \
    com.tbzmike.smartslauncher.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  python3 - "$log" <<'PY'
import sys
from pathlib import Path
result = Path(sys.argv[1]).read_text()
assert "OK (" in result and "FAILURES!!!" not in result, result[-8000:]
assert "INSTRUMENTATION_CODE: -1" in result, result[-8000:]
PY
}
# Functional checks come first. The isolated before/after benchmark runs after UI verification.
adb shell am force-stop com.tbzmike.smartslauncher.debug
adb shell pm clear com.tbzmike.smartslauncher.debug
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
run_test fr.neamar.kiss.androidTest.LowOverhead187RegressionTest \
  verification-results/disabled-features-187.log
adb shell am force-stop com.tbzmike.smartslauncher.debug
adb shell pm clear com.tbzmike.smartslauncher.debug
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
adb shell input keyevent KEYCODE_HOME
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
bash scripts/profile_187_android.sh
adb logcat -d > verification-results/logcat.txt || true
echo "PASS: 3.30.187 disabled feature gates, CPU/RAM comparison, idle worker release, notification history OFF, live avatar/preview parity, actual native and IceBox shortcut destinations, pin retention, disabled and hidden package enable, legacy exact extras, immediate configured 803/403 History window, five-second full-search delay, dynamic saved History, full backup, and  measured horizontal/vertical Auto Scroll motion, Auto Expand cancellation, Cards/List/Wheel/avatar/sizing and previous Android 35 regressions"
