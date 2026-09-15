from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def remove_array_items(text, array_name, values):
    pattern = re.compile(
        rf'(<string-array name="{re.escape(array_name)}">)(.*?)(</string-array>)',
        re.S,
    )
    match = pattern.search(text)
    if not match:
        raise SystemExit(f"Missing array {array_name}")
    body = match.group(2)
    for value in values:
        line = f"\n        <item>{value}</item>"
        if body.count(line) != 1:
            raise SystemExit(f"{array_name}: expected one {value!r}, found {body.count(line)}")
        body = body.replace(line, "", 1)
    return text[:match.start(2)] + body + text[match.end(2):]


# Do not advertise newly added Toast styles until a real Toast rendering path consumes them.
arrays_path = "app/src/main/res/values/arrays_smart_features.xml"
arrays = read(arrays_path)
arrays = remove_array_items(arrays, "smart_toast_animation_entries", ["Pop", "Swing", "Drop"])
arrays = remove_array_items(arrays, "smart_toast_animation_values", ["pop", "swing", "drop"])
write(arrays_path, arrays)

# Keep the original 3.30.85 patch reproducible with the final verified source state.
patch_path = ".github/scripts/apply_33085_animation_styles.py"
patch = read(patch_path)
patch = replace_once(
    patch,
    '    ("smart_toast_animation_entries", ["Pop", "Swing", "Drop"]),\n'
    '    ("smart_toast_animation_values", ["pop", "swing", "drop"]),\n',
    "",
    "remove unwired Toast patch entries",
)
write(patch_path, patch)

# Hard-stop list animation capture/playback while a touch scroll or fling is active. Preserve the
# file's existing mixed line endings byte-for-byte outside the two guarded insertion points.
list_path = ROOT / "app/src/main/java/fr/neamar/kiss/ui/AnimatedListView.java"
data = list_path.read_bytes()
old_prepare = (
    b"    public void prepareChangeAnim() {\r\n"
    b"        cancelPendingChangeAnimation();\r\n"
    b"        mItemMap.clear();\r\n\r\n"
    b"        int firstVisiblePosition"
)
new_prepare = (
    b"    public void prepareChangeAnim() {\r\n"
    b"        cancelPendingChangeAnimation();\r\n"
    b"        mItemMap.clear();\r\n"
    b"        // Do zero list-animation bookkeeping while the user is scrolling or a fling is active.\r\n"
    b"        if (isScrollInProgress()) return;\r\n\r\n"
    b"        int firstVisiblePosition"
)
if data.count(old_prepare) != 1:
    raise SystemExit(f"AnimatedListView prepare guard: expected one match, found {data.count(old_prepare)}")
data = data.replace(old_prepare, new_prepare, 1)

old_animate = (
    b"    public void animateChange() {\r\n"
    b"        if (mItemMap.isEmpty()) return;\r\n\r\n"
    b"        cancelPendingChangeAnimation();"
)
new_animate = (
    b"    public void animateChange() {\r\n"
    b"        if (mItemMap.isEmpty()) return;\r\n"
    b"        // A scroll/fling that began after prepareChangeAnim() wins over decorative motion.\r\n"
    b"        if (isScrollInProgress()) {\r\n"
    b"            cancelPendingChangeAnimation();\r\n"
    b"            mItemMap.clear();\r\n"
    b"            return;\r\n"
    b"        }\r\n\r\n"
    b"        cancelPendingChangeAnimation();"
)
if data.count(old_animate) != 1:
    raise SystemExit(f"AnimatedListView animate guard: expected one match, found {data.count(old_animate)}")
data = data.replace(old_animate, new_animate, 1)
list_path.write_bytes(data)

print("Final 3.30.85 animation audit corrections applied")
