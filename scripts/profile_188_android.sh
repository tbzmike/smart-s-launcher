#!/usr/bin/env bash
set -euo pipefail
BASELINE_SHA=9b31f0decdda7b7fc030b88af9d67741c9963ae8
BASELINE_DIR="$RUNNER_TEMP/smart-s-power-baseline-187"
PACKAGE=com.tbzmike.smartslauncher.debug
git fetch --quiet --depth=1 origin "$BASELINE_SHA"
git worktree add --detach "$BASELINE_DIR" "$BASELINE_SHA"
python3 - "$BASELINE_DIR/app/build.gradle" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); source=p.read_text()
assert 'versionName "3.30.187"' in source
p.write_text(source.replace('minifyEnabled true','minifyEnabled false')
                   .replace('shrinkResources = true','shrinkResources = false'))
PY
bash "$BASELINE_DIR/gradlew" -p "$BASELINE_DIR" :app:assembleDebug --stacktrace
adb install -r -d -g "$BASELINE_DIR/app/build/outputs/apk/debug/app-debug.apk"
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am force-stop "$PACKAGE"
# Configure before Application.onCreate, so the baseline and update start with identical OFF
# choices and the historical migration marker. Android usage/notification permissions stay granted.
cat > "$RUNNER_TEMP/power188-preferences.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="enable-notification-history" value="false" />
    <boolean name="enable-app-usage-tracking" value="false" />
    <boolean name="app-usage-default-repaired-v33001" value="true" />
    <boolean name="smart-battery-monitor-enabled" value="false" />
    <boolean name="semantic-search-enabled" value="false" />
    <string name="smart-history-layout">vertical</string>
    <string name="smart-text-overflow-mode">auto_expand</string>
    <string name="number-of-history-results">803</string>
</map>
XML
configure_clean_profile() {
  # Benchmark data only: give both versions the same empty archive. A production upgrade keeps
  # the user's existing files, which would otherwise be mistaken here for new OFF-mode writes.
  adb shell am force-stop "$PACKAGE"
  adb shell pm clear "$PACKAGE"
  # Measure the visible launcher with the same install -g grants in both versions. Clearing
  # data revokes them; leaving the Contacts dialog on top would invalidate foreground metrics.
  for permission in READ_CONTACTS CALL_PHONE READ_PHONE_STATE READ_CALL_LOG READ_SMS \
    ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION POST_NOTIFICATIONS; do
    adb shell pm grant "$PACKAGE" "android.permission.$permission"
  done
  adb push "$RUNNER_TEMP/power188-preferences.xml" /data/local/tmp/power188-preferences.xml
  adb shell run-as "$PACKAGE" mkdir -p shared_prefs
  adb shell run-as "$PACKAGE" cp /data/local/tmp/power188-preferences.xml "shared_prefs/${PACKAGE}_preferences.xml"
  adb shell cmd package set-home-activity --user 0 "$PACKAGE/fr.neamar.kiss.MainActivity"
}
configure_clean_profile
profile() {
  local label="$1"
  adb logcat -c
  adb shell am instrument -w -r -e class fr.neamar.kiss.androidTest.PowerProfile188Test \
    -e profile "$label" "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" \
    | tee "verification-results/power-${label}-188.log"
  adb logcat -d -s PowerProfile188:I '*:S' > "verification-results/power-${label}-188-metrics.log"
  python3 - "$label" <<'PY'
from pathlib import Path
import sys
s=Path('verification-results/power-'+sys.argv[1]+'-188.log').read_text()
assert 'OK (' in s and 'FAILURES!!!' not in s and 'INSTRUMENTATION_CODE: -1' in s,s[-8000:]
PY
}
profile baseline
adb shell am force-stop "$PACKAGE"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
configure_clean_profile
profile current
python3 - <<'PY'
import json
from pathlib import Path
def read(label):
 lines=Path('verification-results/power-'+label+'-188-metrics.log').read_text().splitlines()
 values=[json.loads(s.split('POWER_PROFILE_188 ',1)[1]) for s in lines if 'POWER_PROFILE_188 ' in s]
 assert len(values)==1,(label,lines)
 return values[0]
baseline,current=read('baseline'),read('current')
assert baseline['version'].startswith('3.30.187')
assert current['version'].startswith('3.30.188')
assert current['notificationArchiveFiles']==0,current
assert current['avatarArchiveFiles']==0,current
# Large regressions fail the release gate; report raw measurements rather than promising that
# emulator PSS or CPU percentages predict a particular phone's temperature.
assert current['pssKb'] <= baseline['pssKb']*1.25+16384,(baseline,current)
assert current['idleCpuMs'] <= max(100,baseline['idleCpuMs']+100),(baseline,current)
report={'method':'Same Android 35 emulator, same unminified debug settings, 803 saved History rows, '
        'three features OFF with permissions granted, foreground idle then background real notification traffic. '
        'PSS is sampled process memory; idle CPU is percent of one logical core, not a temperature measurement.',
        'baseline':baseline,'updated':current,
        'backgroundPssChangeKb':current['pssKb']-baseline['pssKb'],
        'notificationBurstCpuChangeMs':current['notificationBurstCpuMs']-baseline['notificationBurstCpuMs']}
Path('verification-results/power-profile-188.json').write_text(json.dumps(report,indent=2)+'\n')
print('POWER_PROFILE_COMPARISON_188 '+json.dumps(report,separators=(',',':')))
PY
