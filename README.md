# Smart S Launcher

Smart S Launcher is a fast, highly configurable Android launcher focused on search, history, notifications, shortcuts, app usage context, flexible layouts, and launcher-owned animations.

The application ID is `com.tbzmike.smartslauncher`.

## Current release candidate

- Version: **3.29.83**
- Version code: **411**
- Minimum Android version: **Android 5.0 (API 21)**
- Target/compile SDK: **36**

## Free and open-source software

Smart S Launcher is distributed under the **GNU General Public License version 3 (GPL-3.0)**. See [`LICENSE`](LICENSE).

Smart S Launcher is derived from the open-source [KISS Launcher](https://github.com/Neamar/KISS). The original KISS project and its contributors retain attribution for the upstream work. Smart S Launcher has its own application ID, branding, features, release history, and source repository.

## Build from source

Requirements are defined by the Gradle project. A normal release build can be produced with:

```bash
./gradlew assembleRelease
```

Debug builds can be produced with:

```bash
./gradlew assembleDebug
```

The repository CI also runs Android lint and unit tests for release validation.

## F-Droid

The repository includes a root [`.fdroid.yml`](.fdroid.yml) build recipe so the project can be tested with the F-Droid build stack directly from source. F-Droid builds are independent of the normal Smart S Launcher update workflow.

The current F-Droid recipe is pinned to the verified Smart S Launcher **3.29.83 / versionCode 411** source commit. The committed debug keystore is used only by local/CI debug builds and is removed from the temporary F-Droid source scan through `scandelete`; it is not used by the release build.

Upstream metadata for store presentation is maintained under:

```text
fastlane/metadata/android/
```

## Source and issues

Source repository: https://github.com/tbzmike/smart-s-launcher

Issue tracker: https://github.com/tbzmike/smart-s-launcher/issues

## Updating Smart S Launcher

Normal feature and bug-fix development can continue as usual. Each release should:

1. increment `versionCode` and `versionName`;
2. add the matching Fastlane changelog;
3. pass lint and unit tests;
4. pass debug/release APK generation;
5. update the F-Droid build block to point to the exact verified release commit before submission.
