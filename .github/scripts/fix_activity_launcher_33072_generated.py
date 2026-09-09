from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "app/src/main/java/fr/neamar/kiss/activitylauncher/ActivityLauncherActivity.java"
text = PATH.read_text(encoding="utf-8")

old = r'''        status.setText("Searching all components for "" + rawQuery + ""...");'''
new = r'''        status.setText("Searching all components for \"" + rawQuery + "\"...");'''

count = text.count(old)
if count != 1:
    raise SystemExit(f"expected exactly one malformed generated search literal, found {count}")

PATH.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Generated Activity Launcher search status literal repaired exactly")
