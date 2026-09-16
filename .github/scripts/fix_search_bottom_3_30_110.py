from pathlib import Path

BUILD = Path("app/build.gradle")
ADAPTER = Path("app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java")

build = BUILD.read_text()
if 'versionName "3.30.109"' not in build or 'versionCode 537' not in build:
    raise SystemExit("Expected 3.30.109 baseline was not found")
build = build.replace('versionCode 537', 'versionCode 538', 1).replace('versionName "3.30.109"', 'versionName "3.30.110"', 1)
BUILD.write_text(build)

text = ADAPTER.read_text()
old_import = 'import fr.neamar.kiss.pojo.ShortcutPojo;\n'
new_import = old_import + 'import fr.neamar.kiss.pojo.SettingPojo;\n'
if 'import fr.neamar.kiss.pojo.SettingPojo;' not in text:
    if old_import not in text:
        raise SystemExit("Expected ShortcutPojo import not found")
    text = text.replace(old_import, new_import, 1)

old = '''        for (Pojo pojo : pojos) {\n            if (pojo == null) continue;\n            Result<?> existing = existingResults.get(reuseKey(pojo));\n            if (existing != null && samePojoState(existing.getPojo(), pojo)) {\n                updatedResults.add(existing);\n            } else if (pojo instanceof CommunicationPojo) {\n                updatedResults.add(new CommunicationResult((CommunicationPojo) pojo));\n            } else {\n                updatedResults.add(Result.fromPojo(parent, pojo));\n            }\n        }\n        updateResults(context, updatedResults, isRefresh, query);\n    }\n'''
new = '''        for (Pojo pojo : pojos) {\n            if (pojo == null) continue;\n            Result<?> existing = existingResults.get(reuseKey(pojo));\n            if (existing != null && samePojoState(existing.getPojo(), pojo)) {\n                updatedResults.add(existing);\n            } else if (pojo instanceof CommunicationPojo) {\n                updatedResults.add(new CommunicationResult((CommunicationPojo) pojo));\n            } else {\n                updatedResults.add(Result.fromPojo(parent, pojo));\n            }\n        }\n\n        // Final presentation boundary: exact App/Shortcut/Setting matches belong at the bottom.\n        // Preserve the existing order of every other result; this does not change relevance scoring.\n        moveExactLaunchTargetsToBottom(updatedResults, query);\n        updateResults(context, updatedResults, isRefresh, query);\n    }\n\n    private void moveExactLaunchTargetsToBottom(List<Result<?>> items, String query) {\n        if (items == null || items.size() < 2 || TextUtils.isEmpty(query)) return;\n\n        String normalizedQuery = normalizeSearchName(query);\n        if (normalizedQuery.isEmpty()) return;\n\n        List<Result<?>> normalResults = new ArrayList<>(items.size());\n        List<Result<?>> exactLaunchTargets = new ArrayList<>(3);\n        for (Result<?> result : items) {\n            if (result == null || !isExactLaunchTarget(result.getPojo(), normalizedQuery)) {\n                normalResults.add(result);\n            } else {\n                exactLaunchTargets.add(result);\n            }\n        }\n        if (exactLaunchTargets.isEmpty()) return;\n\n        items.clear();\n        items.addAll(normalResults);\n        items.addAll(exactLaunchTargets);\n    }\n\n    private boolean isExactLaunchTarget(Pojo pojo, String normalizedQuery) {\n        if (pojo == null || pojo.isDisabled()) return false;\n        if (!(pojo instanceof AppPojo)\n                && !(pojo instanceof ShortcutPojo)\n                && !(pojo instanceof SettingPojo)) return false;\n        return normalizedQuery.equals(normalizeSearchName(pojo.getName()));\n    }\n\n    private String normalizeSearchName(String value) {\n        if (value == null) return "";\n        return value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\\\s+", " ");\n    }\n'''
if old not in text:
    raise SystemExit("Expected updateWithPojos block not found; refusing to patch")
text = text.replace(old, new, 1)
ADAPTER.write_text(text)
print("Applied 3.30.110 exact-launch-target bottom ordering")
