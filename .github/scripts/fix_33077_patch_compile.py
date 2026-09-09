from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old!r}")
    p.write_text(text.replace(old, new, 1))


# RecordAdapter's helper was intentionally converted from Context-taking to cached no-arg form.
# Keep the notification duplicate-collapse path on that same cached decision.
replace_once(
    "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java",
    "if (!isVerticalHistory(view.getContext())) return;",
    "if (!isVerticalHistory()) return;")

# A warm Home Result snapshot is already true History state, not a temporary query. Publishing it
# with an empty query lets the authoritative HistorySearcher short-circuit updateResults when the
# order/content is unchanged instead of forcing a second notifyDataSetChanged() on Home return.
replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/SearchHandler.java",
    'activity.adapter.updateResults(activity, restored, false, "<history>");',
    'activity.adapter.updateResults(activity, restored, false, "");')

# Some Result subclasses (notably ContactsResult) retain QueryInterface/Activity state. The
# singleton SearchHandler must never keep those alive. Retain only Result implementations whose
# verified fields are process-safe; every other type continues through the existing Pojo fallback.
replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/SearchHandler.java",
    '''    public void rememberHomeResults(@NonNull List<Result<?>> results) {\n        if (lastSearchType != Searcher.Type.HISTORY) return;\n        homeResultSnapshot = Collections.unmodifiableList(new ArrayList<>(results));\n    }\n\n    public void rememberLaunchedResult(@NonNull Result<?> result) {\n        if (lastSearchType == Searcher.Type.HISTORY) return;\n        pendingLaunchedResult = result;\n    }''',
    '''    public void rememberHomeResults(@NonNull List<Result<?>> results) {\n        if (lastSearchType != Searcher.Type.HISTORY) return;\n        List<Result<?>> safe = new ArrayList<>(results.size());\n        for (Result<?> result : results) {\n            if (canRetainWarmResult(result)) safe.add(result);\n        }\n        homeResultSnapshot = Collections.unmodifiableList(safe);\n    }\n\n    public void rememberLaunchedResult(@NonNull Result<?> result) {\n        if (lastSearchType == Searcher.Type.HISTORY || !canRetainWarmResult(result)) return;\n        pendingLaunchedResult = result;\n    }\n\n    private boolean canRetainWarmResult(Result<?> result) {\n        return result instanceof fr.neamar.kiss.result.AppResult\n                || result instanceof fr.neamar.kiss.result.ShortcutsResult\n                || result instanceof fr.neamar.kiss.result.SettingsResult\n                || result instanceof fr.neamar.kiss.result.PhoneResult\n                || result instanceof fr.neamar.kiss.result.CommunicationResult;\n    }''')

print("3.30.77 compile/Home-refresh/memory-safety correction applied successfully")
