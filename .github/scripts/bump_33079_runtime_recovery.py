from pathlib import Path

path = Path("app/build.gradle")
text = path.read_text()
old = '''        // Smart S Launcher 3.30.78 - lightweight flashing notification attention in Vertical Cards\n        versionCode 506\n        versionName "3.30.78"'''
new = '''        // Smart S Launcher 3.30.79 - Home lifecycle and historical notification recovery\n        versionCode 507\n        versionName "3.30.79"'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"app/build.gradle: expected exact 3.30.78 baseline once, found {count}")
path.write_text(text.replace(old, new, 1))
print("Smart S Launcher version bumped exactly to 3.30.79 / 507")
