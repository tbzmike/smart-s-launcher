from pathlib import Path
import re

# Trigger note: this temporary script is removed by the successful source commit.
# This script is run once by the existing CI workflow on the feature branch.
# It deliberately refuses to guess if the expected 3.30.106 source shape is absent.

pref = Path('app/src/main/res/xml/preferences.xml')
text = pref.read_text()
marker = '<PreferenceCategory app:title="History icon sizing">'
if marker not in text:
    raise SystemExit('History icon sizing category not found in preferences.xml')

if 'smart-list-notification-icon-size-percent' not in text:
    replacements = [
        ('''            <SwitchPreference
                app:defaultValue="true"
                app:key="smart-list-resize-notification-icons"
                app:title="Resize notification / profile pictures"
                app:summary="WhatsApp and other notification/profile pictures follow App icon size." />''', '''            <SwitchPreference
                app:defaultValue="true"
                app:key="smart-list-resize-notification-icons"
                app:title="Resize notification / profile pictures"
                app:summary="Enable manual sizing for WhatsApp and other notification/profile pictures." />
            <SeekBarPreference
                app:defaultValue="110"
                app:dependency="smart-list-resize-notification-icons"
                app:key="smart-list-notification-icon-size-percent"
                android:max="240"
                app:min="50"
                app:showSeekBarValue="true"
                app:title="Notification / profile picture size (%)" />'''),
        ('''            <SwitchPreference
                app:defaultValue="true"
                app:key="smart-list-resize-shortcut-icons"
                app:title="Resize shortcut icons"
                app:summary="Launcher shortcut icons follow App icon size." />''', '''            <SwitchPreference
                app:defaultValue="true"
                app:key="smart-list-resize-shortcut-icons"
                app:title="Resize shortcut icons"
                app:summary="Enable manual sizing for launcher shortcut icons." />
            <SeekBarPreference
                app:defaultValue="110"
                app:dependency="smart-list-resize-shortcut-icons"
                app:key="smart-list-shortcut-icon-size-percent"
                android:max="240"
                app:min="50"
                app:showSeekBarValue="true"
                app:title="Shortcut icon size (%)" />'''),
        ('''            <SwitchPreference
                app:defaultValue="true"
                app:key="smart-list-resize-feature-icons"
                app:title="Resize settings / feature icons"
                app:summary="Smart S settings and feature icons follow App icon size." />''', '''            <SwitchPreference
                app:defaultValue="true"
                app:key="smart-list-resize-feature-icons"
                app:title="Resize settings / feature icons"
                app:summary="Enable manual sizing for Smart S settings and feature icons." />
            <SeekBarPreference
                app:defaultValue="110"
                app:dependency="smart-list-resize-feature-icons"
                app:key="smart-list-feature-icon-size-percent"
                android:max="240"
                app:min="50"
                app:showSeekBarValue="true"
                app:title="Settings / feature icon size (%)" />'''),
        ('''            <SwitchPreference
                app:defaultValue="true"
                app:key="smart-list-resize-contact-icons"
                app:title="Resize contact / avatar pictures"
                app:summary="Contact and avatar pictures follow App icon size." />''', '''            <SwitchPreference
                app:defaultValue="true"
                app:key="smart-list-resize-contact-icons"
                app:title="Resize contact / avatar pictures"
                app:summary="Enable manual sizing for contact and avatar pictures." />
            <SeekBarPreference
                app:defaultValue="110"
                app:dependency="smart-list-resize-contact-icons"
                app:key="smart-list-contact-icon-size-percent"
                android:max="240"
                app:min="50"
                app:showSeekBarValue="true"
                app:title="Contact / avatar picture size (%)" />''')
    ]
    for old, new in replacements:
        if old not in text:
            raise SystemExit('Expected history icon preference block not found')
        text = text.replace(old, new, 1)
    text = text.replace(
        'app:summary="These switches control which history icon types follow the existing App icon size setting."',
        'app:summary="Each switch enables its own manual 50%–240% size slider. 100% is the native size; 110% preserves the previous default."',
        1)
    pref.write_text(text)

smart = Path('app/src/main/res/xml/preferences_smart_features.xml')
smart_text = smart.read_text()
if 'smart-list-notification-icon-size-percent' not in smart_text:
    smart_replacements = [
        ('''        <SwitchPreference
            app:defaultValue="true"
            app:key="smart-list-resize-notification-icons"
            app:title="Resize notification / profile pictures"
            app:summary="Apply the existing App icon size setting to WhatsApp and other notification/profile pictures." />''', '''        <SwitchPreference
            app:defaultValue="true"
            app:key="smart-list-resize-notification-icons"
            app:title="Resize notification / profile pictures"
            app:summary="Enable manual sizing for WhatsApp and other notification/profile pictures." />
        <SeekBarPreference
            app:defaultValue="110"
            app:dependency="smart-list-resize-notification-icons"
            app:key="smart-list-notification-icon-size-percent"
            android:max="240"
            app:min="50"
            app:showSeekBarValue="true"
            app:title="Notification / profile picture size (%)" />'''),
        ('''        <SwitchPreference
            app:defaultValue="true"
            app:key="smart-list-resize-shortcut-icons"
            app:title="Resize shortcut icons"
            app:summary="Apply the existing App icon size setting to launcher shortcut icons." />''', '''        <SwitchPreference
            app:defaultValue="true"
            app:key="smart-list-resize-shortcut-icons"
            app:title="Resize shortcut icons"
            app:summary="Enable manual sizing for launcher shortcut icons." />
        <SeekBarPreference
            app:defaultValue="110"
            app:dependency="smart-list-resize-shortcut-icons"
            app:key="smart-list-shortcut-icon-size-percent"
            android:max="240"
            app:min="50"
            app:showSeekBarValue="true"
            app:title="Shortcut icon size (%)" />'''),
        ('''        <SwitchPreference
            app:defaultValue="true"
            app:key="smart-list-resize-feature-icons"
            app:title="Resize settings / feature icons"
            app:summary="Apply the existing App icon size setting to Smart S settings and feature result icons." />''', '''        <SwitchPreference
            app:defaultValue="true"
            app:key="smart-list-resize-feature-icons"
            app:title="Resize settings / feature icons"
            app:summary="Enable manual sizing for Smart S settings and feature icons." />
        <SeekBarPreference
            app:defaultValue="110"
            app:dependency="smart-list-resize-feature-icons"
            app:key="smart-list-feature-icon-size-percent"
            android:max="240"
            app:min="50"
            app:showSeekBarValue="true"
            app:title="Settings / feature icon size (%)" />'''),
        ('''        <SwitchPreference
            app:defaultValue="true"
            app:key="smart-list-resize-contact-icons"
            app:title="Resize contact / avatar pictures"
            app:summary="Apply the existing App icon size setting to contact and avatar pictures." />''', '''        <SwitchPreference
            app:defaultValue="true"
            app:key="smart-list-resize-contact-icons"
            app:title="Resize contact / avatar pictures"
            app:summary="Enable manual sizing for contact and avatar pictures." />
        <SeekBarPreference
            app:defaultValue="110"
            app:dependency="smart-list-resize-contact-icons"
            app:key="smart-list-contact-icon-size-percent"
            android:max="240"
            app:min="50"
            app:showSeekBarValue="true"
            app:title="Contact / avatar picture size (%)" />''')
    ]
    for old, new in smart_replacements:
        if old not in smart_text:
            raise SystemExit('Expected Smart Features history icon block not found')
        smart_text = smart_text.replace(old, new, 1)
    smart.write_text(smart_text)

tile = Path('app/src/main/java/fr/neamar/kiss/ui/TileVisualStyle.java')
java = tile.read_text()
old = '''        int percent = safePercent(prefs, "smart-list-icon-size-percent", 110, 50, 240);

        applyConfiguredIconSize(row, prefs, R.id.item_notification_icon,
                "smart-list-resize-notification-icons", percent);
        applyConfiguredIconSize(row, prefs, R.id.item_shortcut_icon,
                "smart-list-resize-shortcut-icons", percent);
        applyConfiguredIconSize(row, prefs, R.id.item_setting_icon,
                "smart-list-resize-feature-icons", percent);
        applyConfiguredIconSize(row, prefs, R.id.item_contact_icon,
                "smart-list-resize-contact-icons", percent);'''
new = '''        // Each history icon type has an independent size slider. If the new preference has
        // never been saved, fall back to the existing App icon size so upgrading preserves the
        // 3.30.106 behaviour until the user chooses a custom value.
        int appIconPercent = safePercent(prefs, "smart-list-icon-size-percent", 110, 50, 240);
        int notificationPercent = safePercent(prefs, "smart-list-notification-icon-size-percent", appIconPercent, 50, 240);
        int shortcutPercent = safePercent(prefs, "smart-list-shortcut-icon-size-percent", appIconPercent, 50, 240);
        int featurePercent = safePercent(prefs, "smart-list-feature-icon-size-percent", appIconPercent, 50, 240);
        int contactPercent = safePercent(prefs, "smart-list-contact-icon-size-percent", appIconPercent, 50, 240);

        applyConfiguredIconSize(row, prefs, R.id.item_notification_icon,
                "smart-list-resize-notification-icons", notificationPercent);
        applyConfiguredIconSize(row, prefs, R.id.item_shortcut_icon,
                "smart-list-resize-shortcut-icons", shortcutPercent);
        applyConfiguredIconSize(row, prefs, R.id.item_setting_icon,
                "smart-list-resize-feature-icons", featurePercent);
        applyConfiguredIconSize(row, prefs, R.id.item_contact_icon,
                "smart-list-resize-contact-icons", contactPercent);'''
if 'smart-list-notification-icon-size-percent' not in java:
    if old not in java:
        raise SystemExit('Expected TileVisualStyle sizing block not found')
    tile.write_text(java.replace(old, new, 1))

gradle = Path('app/build.gradle')
g = gradle.read_text()
vm = re.search(r'versionName\s+["\']([^"\']+)["\']', g)
cm = re.search(r'versionCode\s+(\d+)', g)
if not vm or not cm or vm.group(1) != '3.30.106' or cm.group(1) != '534':
    raise SystemExit('Unexpected version baseline; refusing to edit')
g = g[:vm.start(1)] + '3.30.107' + g[vm.end(1):]
g = g[:cm.start(1)] + '535' + g[cm.end(1):]
g = g.replace('Smart S Launcher 3.30.106 - exposed history icon sizing', 'Smart S Launcher 3.30.107 - independent history icon sizing')
gradle.write_text(g)

temp = Path('.github/workflows/add-manual-history-icon-sliders.yml')
if temp.exists():
    temp.unlink()

print('Prepared Smart S Launcher 3.30.107 with four independent manual icon-size sliders.')
