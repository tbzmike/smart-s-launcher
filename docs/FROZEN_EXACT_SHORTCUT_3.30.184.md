# Smart S Launcher 3.30.184 — Frozen exact shortcut repair

Base: released and signed **v3.30.183**, source commit `cdefec2afdc6b24030d572cb5dd1a4343f089dab`. Version code **612**.

## Changes

1. **Exact saved Activity check:** Frozen applications can disable only the selected launcher/shortcut Activity without disabling their entire package. A frozen legacy Intent shortcut checks both the package and its exact component, and the on-demand root worker enables the exact component if required.
2. **Verified enable result:** Return success only if PackageManager confirms the selected component is enabled. A deleted/renamed component is not silently replaced with an unrelated shortcut.
3. **No root blocking on tap:** Legacy shortcut uses the same on-demand worker as the already-fixed Oreo and app paths.
4. **IceBox app fallback:** When IceBox exact route is unavailable and a verified target is already enabled, launch that app without spawning another root process on the UI thread. Root enable for frozen verified targets remains async.
5. **App fallback:** A freshly enabled app does not spawn a redundant second root command when retrying its package launch.
6. **Preservation:** Search, Home, 3D Wheel, Vertical Cards, Vertical List, Auto Expand, Auto Scroll, backup/restore, tags, avatar logic and battery optimisations are not touched.

## Tests

Android 35 focused `FrozenExactShortcut184RegressionTest` checks an enabled exact Activity, missing exact Activity, Intent component serialization, successful async enabled-component handling, and rejection of nonexistent Activities. Run the complete existing `verify_183_android.sh` suite through `verify_184_android.sh`, plus unit tests, compile and unchanged production signing certificate.

On-device root/IceBox tests cannot be simulated by the stock emulator. After installing 3.30.184, verify a genuinely frozen package, a frozen individual shortcut Activity, a remembered Oreo shortcut, and an IceBox-published app shortcut on a rooted device, including the user's configured permission path.
