#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification-results
python3 - <<'PY'
from pathlib import Path
p = Path("app/build.gradle")
source = p.read_text()
assert 'versionName "3.30.186"' in source
assert 'versionCode 614' in source
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
adb install -r shortcut-fixture/build/outputs/apk/target/debug/shortcut-fixture-target-debug.apk
adb install -r shortcut-fixture/build/outputs/apk/icebox/debug/shortcut-fixture-icebox-debug.apk
adb shell cmd package set-home-activity --user 0 com.tbzmike.smartslauncher.debug/fr.neamar.kiss.MainActivity
adb logcat -c
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
run_test fr.neamar.kiss.ShortcutLaunch186IntegrationTest \
  verification-results/shortcut-launch-186-actual-destinations.log
adb logcat -d > verification-results/logcat.txt || true
echo "PASS: 3.30.186 actual native and IceBox shortcut destinations, pin retention, disabled and hidden package enable, legacy exact extras, immediate configured 803/403 History window, five-second full-search delay, dynamic saved History, full backup, and  measured horizontal/vertical Auto Scroll motion, Auto Expand cancellation, Cards/List/Wheel/avatar/sizing and previous Android 35 regressions"
