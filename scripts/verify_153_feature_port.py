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
    "app/src/main/java/fr/neamar/kiss/IconsHandler.java",
    "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java",
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
    "searcher/SearchHandler.java", "searcher/HistorySearcher.java",
    "ui/GlobalTextStyler.java", "ui/SmartTextAppearance.java", "ui/AutoMarqueeTextView.java",
    "ui/SearchEditText.java", "ui/BuiltInQwertyKeyboard.java", "ui/BuiltInKeyboardSizing.java",
    "forwarder/ForwarderManager.java", "forwarder/HistoryDisplayForwarder.java",
    "forwarder/SmartCardListForwarder.java", "forwarder/VerticalCardViewportController.java",
    "notification/NotificationListener.java", "utils/NotificationHistoryResolver.java",
    "ui/RichNotificationHistoryDialog.java", "appusage/AppUsageStore.java",
]:
    path = f"app/src/main/java/fr/neamar/kiss/{name}"
    assert (ROOT / path).read_bytes() == git("show", f"{BASE}:{path}"), path

for name in ["MainActivity.java", "DataHandler.java"]:
    path = f"app/src/main/java/fr/neamar/kiss/{name}"
    patch = git("diff", "--unified=0", BASE, "--", path).decode()
    removed = [line for line in patch.splitlines() if line.startswith("-") and not line.startswith("---")]
    assert not removed, f"Original behavior removed from {name}: {removed}"

assert not (ROOT / "app/src/main/java/fr/neamar/kiss/ui/HistoryAppearanceMath.java").exists()
icons_path = "app/src/main/java/fr/neamar/kiss/IconsHandler.java"
original_icons = git("show", f"{BASE}:{icons_path}").decode()
assert original_icons.count("if (!dir.exists() && !dir.mkdir())") == 2
assert (ROOT / icons_path).read_text() == original_icons.replace(
    "if (!dir.exists() && !dir.mkdir())",
    "if (!dir.isDirectory() && !dir.mkdir() && !dir.isDirectory())")
build = (ROOT / "app/build.gradle").read_text()
assert 'versionName "3.30.167"' in build and "versionCode 595" in build
for name in ["searcher/AppSourceMetadataUpdater.java", "searcher/SemanticHnswIndex.java",
             "searcher/AppMetadataSyncJobService.java", "ui/HistoryDateNavigator.java",
             "ui/ChronologicalDateScroller.java"]:
    assert (ROOT / f"app/src/main/java/fr/neamar/kiss/{name}").is_file(), name
print(f"PASS: {unchanged} original files byte-identical; feature changes restricted to allowlist.")
adapter_path = "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java"
original_adapter = git("show", f"{BASE}:{adapter_path}").decode()
expected_adapter = original_adapter.replace(
    "// No notification enrichment, overflow traversal, timestamp formatting, style traversal,\n"
    "        // width mutation, click rewiring or bell work is allowed in the fling hot path.",
    "// Keep timestamp identity correct even for a new row or a row recycled from search.\n"
    "        // Its binder uses the shared in-memory snapshot; database/usage loading stays at idle.\n"
    "        // Notification enrichment, overflow/style traversal, width mutation and bell work remain\n"
    "        // outside the fling hot path.").replace(
    "if (hardScrollFreeze) return view;",
    "if (hardScrollFreeze) {\n"
    "            UniversalHistoryTimestamp.bind(view, result, renderContext);\n"
    "            return view;\n        }").replace(
    "                UniversalHistoryTimestamp.bind(view, result, context);\n", "")
assert (ROOT / adapter_path).read_text() == expected_adapter, "Unrelated adapter behavior changed"
print("PASS: original row/icon sizing and notification actions retained; timestamp binding is the only adapter fix.")
