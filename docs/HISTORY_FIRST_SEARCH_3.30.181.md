# Smart S Launcher 3.30.181 — saved History first

- Live History count (403 in the request) is read from `number-of-history-results`, not fixed at 400.
- First stage: immediate Home/History preview and memory-resident launch labels; a bounded History worker resolves saved items when needed.
- Full provider, fuzzy/semantic HNSW and remaining database search begins only after **5,000 ms since last keystroke**.
- Every query cancels the pending previous timer; Activity lifecycle and query generation gate stale searches.
- `number-of-search-results` is cached for one query instance instead of statically across the process, respecting changes without restart.
- UI regression preserves launch-target bottom placement, History relevance ordering, Auto Expand, notifications and full backup.
- Android 35 instrumentation verifies no early full search and cancellation. Unit tests check the dynamic History count and idle policy.
- Installable production release must pass Android regression tests, unit tests, APK compile and certificate verification.
