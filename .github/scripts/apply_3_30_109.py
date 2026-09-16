from pathlib import Path

BASE = Path('.')

settings = BASE / 'app/src/main/java/fr/neamar/kiss/SettingsFragment.java'
text = settings.read_text(encoding='utf-8')
marker = '        setPreferencesFromResource(R.xml.preferences, rootKey);\n        addSearchKeyboardPreferences();'
replacement = '        setPreferencesFromResource(R.xml.preferences, rootKey);\n        addPixelLauncherCleanupPreference(rootKey);\n        addSearchKeyboardPreferences();'
if text.count(marker) != 1:
    raise SystemExit(f'SettingsFragment insertion marker count={text.count(marker)}')
text = text.replace(marker, replacement, 1)
method_marker = '    private void addSearchKeyboardPreferences() {'
method = '''    private void addPixelLauncherCleanupPreference(@Nullable String rootKey) {
        PreferenceGroup parent;
        if ("ui-holder".equals(rootKey)) {
            parent = getPreferenceScreen();
        } else {
            parent = findPreference("ui-holder");
        }
        if (parent == null || parent.findPreference("performance-background-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("performance-background-category");
        category.setTitle("Performance & background");
        parent.addPreference(category);

        SwitchPreference killPixelLauncher = new SwitchPreference(requireContext());
        killPixelLauncher.setKey(PixelLauncherScreenOffReceiver.PREFERENCE_KEY);
        killPixelLauncher.setTitle("Force-stop Pixel Launcher when screen turns off");
        killPixelLauncher.setSummary("Uses Smart S root access to stop com.google.android.apps.nexuslauncher on every screen-off event and release its background memory. No background service is kept running for this feature.");
        killPixelLauncher.setDefaultValue(false);
        category.addPreference(killPixelLauncher);
    }

'''
if text.count(method_marker) != 1:
    raise SystemExit(f'SettingsFragment method marker count={text.count(method_marker)}')
text = text.replace(method_marker, method + method_marker, 1)
settings.write_text(text, encoding='utf-8')

build = BASE / 'app/build.gradle'
text = build.read_text(encoding='utf-8')
if 'versionCode 536' not in text or 'versionName "3.30.108"' not in text:
    raise SystemExit('Expected 3.30.108 version markers were not found')
text = text.replace('// Smart S Launcher 3.30.108 - exact launch target search ranking for notification, shortcut and feature icons',
                    '// Smart S Launcher 3.30.109 - optional Pixel Launcher screen-off cleanup', 1)
text = text.replace('versionCode 536', 'versionCode 537', 1)
text = text.replace('versionName "3.30.108"', 'versionName "3.30.109"', 1)
build.write_text(text, encoding='utf-8')

print('Applied 3.30.109 settings and version changes exactly once.')
