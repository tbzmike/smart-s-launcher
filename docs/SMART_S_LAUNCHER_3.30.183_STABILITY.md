# Smart S Launcher 3.30.183 — Stability and IceBox route recovery

Base: signed v3.30.182; new version code 611.

## Confirmed static defects and targeted repairs

1. ShortcutsProvider and AppProvider registered LauncherApps callbacks without
   guarding unavailable services, revoked permission or Android profile transitions.
   Registration is guarded, and shortcut callbacks are unregistered on destruction.
2. ShortcutUtil queried LauncherApps and UserManager without null/permission/state
   checks. A locked work profile no longer invalidates other profiles or crashes Home.
3. Optional background integrations (screen-off receiver, portable tag repair,
   usage migrations, social/media integration and updater) cannot abort app startup.
   Critical SQLite initialization remains strict.
4. A crashing Java thread records a bounded, app-private last-failure report.
   Settings > Advanced > Share last launcher crash report is explicit opt-in,
   and no telemetry service or scheduled task was introduced.
5. Saved IceBox launcher shortcuts with a missing Android ShortcutInfo may recover
   their exact saved target package, or a single uniquely matching app in the
   current app catalog. Ambiguous matches are rejected, not launched.
   Exact Android shortcut routes are still attempted first.
6. Preserve the History-first 5-second search, Auto Expand/Auto Scroll, 3D Wheel,
   Vertical Cards, backup/restore, tags, and package-only reconciliation.

## Limits

Without a device logcat, a Java crash, Android process kill, and an expected
Activity recreation cannot be conclusively distinguished. The crash report
will capture subsequent Java exceptions, not system kills or native crashes.
A developer-signed APK should only be shipped if green CI confirms
compilation, unit tests, Android 35 regressions, and production signing.
