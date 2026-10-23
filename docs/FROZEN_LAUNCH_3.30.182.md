# Smart S Launcher 3.30.182 — Frozen app and exact shortcut launch

Based on verified v3.30.181, versionCode 610.

## Contract

- A frozen app click, with Auto-enable frozen apps on and authorized root, checks current PackageManager state, enables the actual current-profile package and any independently disabled saved launcher Activity on demand, verifies the package became enabled, and launches it automatically.
- A frozen Android shortcut enables its publisher, then retries the exact publisher/shortcut identity while the app republishes; it never records a failed attempt as success.
- A verified IceBox app alias first uses its own exact shortcut route (preserving Device Owner/Shizuku permissions). If Android reports the exact route unavailable, it enables its verified real target via authorized root and opens that app. No display-name guessing for privileged target selection.
- Non-frozen shortcuts launch immediately as before. Legacy shortcuts keep their original Intent.
- No root or polling process runs merely because the launcher is open. One zero-core worker handles user taps; its idle thread expires. Retries are bounded.
- Root command exit code is checked, root availability and enable settings are revalidated, no infinite command wait.
- Hidden packages are not assumed uninstalled. When the package is known from a saved shortcut or app record, authorised root enable is tried and the result is verified.
- Saved History, favorite items and tags are never removed due to a failed frozen launch.
- Package/shortcut providers get one refresh on successful re-enable, not two redundant scheduled refreshes.
- All frozen-state settings remain live and independently wired, including Package changes only; that setting does not prevent launching.

## Limitations

Android must grant Smart S the necessary privileged/root access to enable third-party frozen packages. LauncherApps must still recognize the exact saved shortcut, which may no longer exist after uninstall/reinstall or app changes. Work/private profile enablement is not attempted using owner-user privileges.

## Verification

Unit tests: FrozenShortcutLaunchPolicyTest. Android 35 regression: FrozenAppState182RegressionTest plus 3.30.181 History-first, backup, History layouts, Auto Scroll and UI suites. A real frozen third-party app with root/IceBox must still be measured on the user's phone; CI emulator cannot emulate all device-specific freezer permissions.
