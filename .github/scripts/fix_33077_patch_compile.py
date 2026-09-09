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

print("3.30.77 compile/Home-refresh correction applied successfully")
