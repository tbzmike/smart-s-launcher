from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    s = p.read_text()
    count = s.count(old)
    assert count == 1, f"{path}: expected one match, found {count}"
    p.write_text(s.replace(old, new, 1))

replace_once(
    "app/build.gradle",
    "        // Smart S Launcher 3.30.08\n        versionCode 436\n        versionName \"3.30.08\"",
    "        // Smart S Launcher 3.30.09\n        versionCode 437\n        versionName \"3.30.09\"",
)

replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/Searcher.java",
    "    protected int getMaxResultCount() {\n        return DEFAULT_MAX_RESULTS;\n    }\n",
    "    protected int getMaxResultCount() {\n        return DEFAULT_MAX_RESULTS;\n    }\n\n    /** Publish a stable snapshot without ending the active search. */\n    protected final void publishCurrentResults() {\n        if (isCancelled()) return;\n        MainActivity activity = activityWeakReference.get();\n        if (activity == null) return;\n\n        PriorityQueue<Pojo> copy = new PriorityQueue<>(processedPojos);\n        int maxResults = Math.max(0, getMaxResultCount());\n        while (copy.size() > maxResults) copy.poll();\n        List<Pojo> snapshot = new ArrayList<>(copy.size());\n        while (copy.peek() != null) {\n            Pojo pojo = copy.poll();\n            if (pojo != null) snapshot.add(pojo);\n        }\n        if (snapshot.isEmpty()) return;\n\n        activity.runOnUiThread(() -> {\n            if (isCancelled()) return;\n            MainActivity currentActivity = activityWeakReference.get();\n            if (currentActivity == null) return;\n            currentActivity.adapter.updateWithPojos(currentActivity, snapshot, true, query);\n        });\n    }\n",
)

replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/QuerySearcher.java",
    "        if (semanticPass) {\n            List<Pojo> semanticMatches = new ArrayList<>();\n            for (Pojo pojo : pojos) {\n                if (pojo == null || lexicalIds.contains(pojo.id)) continue;",
    "        if (semanticPass) {\n            List<Pojo> semanticMatches = new ArrayList<>();\n            int checked = 0;\n            for (Pojo pojo : pojos) {\n                if ((checked++ & 31) == 0 && isCancelled()) return false;\n                if (pojo == null || lexicalIds.contains(pojo.id)) continue;",
)

replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/QuerySearcher.java",
    "        configureSemanticSearch();\n        KissApplication.getApplication(activity).getDataHandler().requestResults(query, this);\n\n        if (semanticEnabled) {\n            semanticPass = true;\n            KissApplication.getApplication(activity).getDataHandler().requestAllRecords(this);\n            semanticPass = false;\n        }",
    "        configureSemanticSearch();\n        KissApplication.getApplication(activity).getDataHandler().requestResults(query, this);\n\n        // Do not hold useful lexical matches behind the full semantic-record scan. The same\n        // Searcher remains active, so the deeper pass can still improve the final ranking and is\n        // cancelled immediately if the user types again or chooses a result.\n        if (semanticEnabled && !lexicalIds.isEmpty() && !isCancelled()) {\n            publishCurrentResults();\n        }\n\n        if (semanticEnabled && !isCancelled()) {\n            semanticPass = true;\n            KissApplication.getApplication(activity).getDataHandler().requestAllRecords(this);\n            semanticPass = false;\n        }",
)

replace_once(
    "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java",
    "        View.OnClickListener openHistory = v -> {\n            RecentLaunchTracker.remember(result.getPojo());",
    "        View.OnClickListener openHistory = v -> {\n            SearchHandler.getInstance().cancelSearch();\n            RecentLaunchTracker.remember(result.getPojo());",
)

replace_once(
    "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java",
    "    public void onClick(final int position, View v) {\n        try {\n            final Result<?> result = getItem(position);",
    "    public void onClick(final int position, View v) {\n        try {\n            // A selection ends the search session immediately, including any semantic pass.\n            SearchHandler.getInstance().cancelSearch();\n            final Result<?> result = getItem(position);",
)
