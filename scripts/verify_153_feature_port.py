#!/usr/bin/env python3
"""Verify that the additive feature port retains the original launcher rendering and loading."""
import subprocess
from pathlib import Path

BASE = "145b42e7f4f954068b087f7958f62d47c0f10905"
ROOT = Path(__file__).resolve().parents[1]

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)

allowed = {
    "app/build.gradle", "app/src/main/AndroidManifest.xml", "README.md",
    "app/src/main/java/fr/neamar/kiss/AppUsageActivity.java",
    "app/src/main/java/fr/neamar/kiss/DataHandler.java",
    "app/src/main/java/fr/neamar/kiss/IndexingSettingsActivity.java",
    "app/src/main/java/fr/neamar/kiss/MainActivity.java",
    "app/src/main/java/fr/neamar/kiss/NotificationHistoryActivity.java",
    "app/src/main/java/fr/neamar/kiss/SettingsActivity.java",
    "app/src/main/java/fr/neamar/kiss/SettingsFragment.java",
    "app/src/main/java/fr/neamar/kiss/appusage/AppUsagePackageReceiver.java",
    "app/src/main/java/fr/neamar/kiss/appusage/AppUsageSync.java",
    "app/src/main/java/fr/neamar/kiss/db/DB.java",
    "app/src/main/java/fr/neamar/kiss/db/DBHelper.java",
    "app/src/main/java/fr/neamar/kiss/forwarder/HistoryVisualEnhancer.java",
    "app/src/main/java/fr/neamar/kiss/searcher/QuerySearcher.java",
    "app/src/main/java/fr/neamar/kiss/searcher/SemanticEmbeddingScorer.java",
    "app/src/main/java/fr/neamar/kiss/ui/SettingsSearchIndex.java",
    "app/src/main/java/fr/neamar/kiss/ui/UniversalHistoryTimestamp.java",
    "app/src/main/res/menu/menu_main.xml", "app/src/main/res/xml/preferences.xml",
    "app/src/test/java/fr/neamar/kiss/searcher/SemanticEmbeddingScorerTest.java",
    ".github/workflows/ci.yml",
}
originals = git("ls-tree", "-r", "--name-only", BASE).decode().splitlines()
unchanged = 0
for name in originals:
    current = ROOT / name
    if not current.is_file():
        raise AssertionError(f"Original file removed: {name}")
    original = git("show", f"{BASE}:{name}")
    if current.read_bytes() == original:
        unchanged += 1
    elif name not in allowed:
        raise AssertionError(f"Unrelated baseline file changed: {name}")

for name in [
    "adapter/RecordAdapter.java", "searcher/SearchHandler.java", "searcher/HistorySearcher.java",
    "ui/GlobalTextStyler.java", "ui/SmartTextAppearance.java", "ui/AutoMarqueeTextView.java",
    "ui/SearchEditText.java", "ui/BuiltInQwertyKeyboard.java", "ui/BuiltInKeyboardSizing.java",
    "forwarder/ForwarderManager.java", "forwarder/HistoryDisplayForwarder.java",
    "forwarder/SmartCardListForwarder.java", "forwarder/VerticalCardViewportController.java",
    "notification/NotificationListener.java", "utils/NotificationHistoryResolver.java",
    "ui/RichNotificationHistoryDialog.java", "appusage/AppUsageStore.java",
]:
    path = f"app/src/main/java/fr/neamar/kiss/{name}"
    assert (ROOT / path).read_bytes() == git("show", f"{BASE}:{path}"), path

for name in ["MainActivity.java", "DataHandler.java", "ui/UniversalHistoryTimestamp.java",
             "forwarder/HistoryVisualEnhancer.java"]:
    path = f"app/src/main/java/fr/neamar/kiss/{name}"
    patch = git("diff", "--unified=0", BASE, "--", path).decode()
    removed = [line for line in patch.splitlines() if line.startswith("-") and not line.startswith("---")]
    assert not removed, f"Original behavior removed from {name}: {removed}"

assert not (ROOT / "app/src/main/java/fr/neamar/kiss/ui/HistoryAppearanceMath.java").exists()
build = (ROOT / "app/build.gradle").read_text()
assert 'versionName "3.30.166"' in build and "versionCode 594" in build
for name in ["searcher/AppSourceMetadataUpdater.java", "searcher/SemanticHnswIndex.java",
             "searcher/AppMetadataSyncJobService.java", "ui/HistoryDateNavigator.java",
             "ui/ChronologicalDateScroller.java"]:
    assert (ROOT / f"app/src/main/java/fr/neamar/kiss/{name}").is_file(), name
print(f"PASS: {unchanged} original files byte-identical; feature changes restricted to allowlist.")
print("PASS: original row sizing, styling, history workers, renderers and notification actions retained.")
