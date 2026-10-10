# Smart S Launcher

Smart S Launcher is a fast, search-focused Android home screen built from the open-source [KISS Launcher](https://github.com/Neamar/KISS) project and extended with additional launcher, history, notification, usage, battery, and appearance features.

From your home screen, type the first letters of apps, contacts, shortcuts, or other supported results and launch them quickly. Frequently used items can become easier to reach through local usage history.

## How Smart S Launcher differs from KISS Launcher

Smart S Launcher keeps the search-first foundation of KISS while adding and extending features for users who want more information and control directly from their launcher. Current Smart S-specific additions include:

- Launch/open/click history and usage information
- Notification history with content previews when Android makes that content available; tapping a history notification opens that selected notification directly, while long-press opens the rich history viewer at that selected entry
- Extensive text, appearance, and wallpaper-readability controls
- Battery monitoring and battery widgets
- Optional accessibility-based double-tap-to-lock support
- Additional local launcher preferences and history behavior

Version 3.30.166 is built directly on the verified 3.30.153 source. It adds background HNSW
semantic retrieval, richer task matching, locally cached app-store descriptions and automatic
install/update metadata refresh, a Data Activity Viewer, a dedicated semantic settings screen,
settings-search highlighting, and date navigation in native Home history, Notification History,
and chronological App Usage views. The original row adapter, history loading, text styling,
keyboard, card/wheel renderers and notification actions remain unchanged.

Semantic search remains optional. Its existing dimension choices are 64, 128 and 256, with 128
as the default. Description refresh does not change the user's semantic or description settings.
Metadata requests are deduplicated by app revision; newer pending updates survive older fetch
completion. HNSW rebuild bursts coalesce into the latest replacement and optional feature work
pauses during launcher scrolling. Verification compares rendered row measurements against
3.30.153 through search, resume, recycling, scrolling and recreation on Android emulators.

These changes are maintained in this repository under the Smart S Launcher application ID `com.tbzmike.smartslauncher`.

## Features

- Fast search-first launcher interface
- App, shortcut, and supported contact search
- Local usage-based result history
- Notification history and previews where Android permits them
- Appearance, text, and wallpaper readability controls
- Battery monitoring and widgets
- Optional double-tap-to-lock through Android Accessibility Service

Some optional features require Android permissions such as notification access, usage access, contacts, phone-related permissions, location, or Accessibility Service access. Features remain subject to Android's permission and privacy controls.

## Free and open source

Smart S Launcher is free and open-source software licensed under the GNU General Public License v3.0.

Smart S Launcher is derived from the KISS Launcher open-source project. The original KISS Launcher source is available at [Neamar/KISS](https://github.com/Neamar/KISS).
