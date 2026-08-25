# QA test suite
This document contains a small QA suite that can be run after big changes to the app.
As best as possible, only actual KISS code is tested, not standard Android system behavior.

> This document assumes all settings are at their default value when you start

### Get started...
* [ ] Loader appears when opening the app
* [ ] Help text ("search apps, contacts, ...") appears while app are loading
* [ ] After some time, loader disappears and is replaced by launcher icon
* [ ] Touching the search field displays the keyboard
* [ ] Searching for text displays results
* [ ] Clicking the launcher icon displays the list of apps
* [ ] When clicking on a search item, the corresponding intent is triggered
* [ ] When going back to KISS, search results have been cleared
* [ ] When going back to KISS, the item has been added to history
* [ ] History is displayed when search box is empty
* [ ] Three dots menu is displayed to the right
* [ ] Entering a search query replaces the three-dots menu with an "X"
* [ ] Clicking the "X" empties the search field, displays three dots menu and displays history
* [ ] When searching, pressing enter on the keyboard launches the first result
* [ ] When keyboard is displayed, scrolling the list down hides the keyboard
* [ ] When searching, pressing space as the first character does nothing (left-hand side trimming)
* [ ] Press kiss icon. App list is displayed, and kiss bar appears with a circular reveal animation
* [ ] Press kiss icon again. App list is hidden, and kiss bar disappears with a circular (un)reveal animation
* [ ] With keyboard open, press kiss icon. App list is displayed, and keyboard stays there
* [ ] With keyboard open, press kiss icon. The app list is displayed. When typing something on the keyboard, app list is hidden, and search results start appearing
* [ ] Search for something, press kiss icon. The app list is displayed. Press kiss icon again, search query has been emptied
* [ ] With KISS set as default launcher, pressing home empties the search field and displays history
* [ ] With KISS set as default launcher and app list displayed, pressing home hides the app list and displays history
* [ ] When app bar is displayed, pressing back hides the bar and displays history
* [ ] When search results are displayed, pressing back empties then search field and displays history
* [ ] When history is displayed, pressing back does not quit KISS
* [ ] When searching for something, touching the edit text moves the cursor

#### Menus
* [ ] Clicking the three dots menu open a popup
* [ ] Long-clicking the three dots menu open a popup
* [ ] If device has physical menu button, pressing menu displays the three-dots menu
* [ ] Long pressing a search result displays contextual menu
* [ ] When clicking three dots menu, pressing back dismisses the popup

### History
#### Standard history manipulation
* [ ] Reset history preference displays number of items in history if history length > 5
* [ ] Reset history clears existing history instantly
* [ ] Reset history summary does not display the old history length after reset
* [ ] Pressing cancel on reset history does not reset history
* [ ] Adding an excluded app hides the app from search results
* [ ] Adding an excluded app hides the app from history
* [ ] Adding an excluded app hides the app from app list
* [ ] Resetting excluded app removes all excluded apps
* [ ] Resetting excluded apps allows apps previously hidden to be displayed in app list
* [ ] No more than Max number of results in search is displayed to the user
* [ ] Changing the value updates the history dynamically
* [ ] Freezing history prevents new items from being added to history
* [ ] Unchecking "Freeze history" ensures history is populated again
* [ ] TODO: history mode

#### Vertical Cards viewport invariants
* [ ] Enter a query with enough matches to scroll: the strongest bottom card is visible immediately without manual scrolling
* [ ] Keep typing while results update: every completed result set remains anchored to its strongest bottom card
* [ ] Open the keyboard during a search: the strongest bottom card remains fully above the keyboard throughout the resize
* [ ] Hide and show the external favorites bar: result cards remain above whichever bottom bar is actually visible
* [ ] Repeat the checks with Flexible Workspace enabled: the history pane uses the same visible bottom boundary
* [ ] Manually browse older history, leave and return with Back: the exact non-bottom position is preserved
* [ ] Launch an app from a non-bottom history position, then press or swipe Home from that app: the exact paused history position returns
* [ ] From that restored launcher position, press or swipe Home again: the launcher moves to the true bottom card
* [ ] Repeat the two-stage Home check with an app shortcut and with several notifications/history re-ranks arriving while away
* [ ] Kill the paused launcher process after launching an app, then press Home: the persisted card position and offset are restored
* [ ] Press Home while a query is active: the query clears, favorites return when enabled, and the final history card is visible above them

#### Jump to newest history card
* [ ] Scroll upward until the newest bottom card is fully hidden: downward-pointing fingers appear in the marked left and right side areas
* [ ] Leave the newest card even slightly below the visible bottom boundary: the controls remain visible until that card is fully inside the viewport
* [ ] Stop with only the spacing below the newest card clipped: the controls stay hidden because the card itself is fully visible
* [ ] Tap either finger: history moves to the true bottom card and both controls disappear immediately
* [ ] Manually scroll upward again: the controls reappear from the real ScrollView position, without a timer or delayed guess
* [ ] Enter a search or show the keyboard: the controls remain hidden and the existing search/IME bottom-pinning behavior stays authoritative
* [ ] Launch an app from older history and return Home: the saved position and visible finger controls return; a second Home press still goes to the bottom

#### Performance and battery preservation
* [ ] Leave Smart S behind another app for at least one minute: the fallback installed-app state scan does not run while launcher Home is stopped
* [ ] Return Home after freezing or unfreezing an app: reconciliation runs immediately and the correct state appears without a background polling delay
* [ ] Rebuild a long notification history: card messages remain identical while one grouped newest-notification query replaces per-card database queries
* [ ] Rebuild the same cards repeatedly: sampled accent colors remain stable and the bounded cache does not grow beyond 256 entries
* [ ] Verify notifications, widgets, app usage, battery monitoring, animations, search, favourites and every history layout remain available

#### Tile text overflow
* [ ] Use long app, shortcut, contact, notification, and communication labels: every clipped single-line tile field auto-scrolls while Smart S is visible
* [ ] Verify notification previews and live/usage/map card labels auto-scroll instead of ending with an ellipsis
* [ ] Open a full communication body or expanded card detail: intentional long-form text remains readable as multiline content

#### Flexible workspace assignments
* [ ] In two-pane mode, select pane 1 and pane 2 for Apps & history: widgets always occupy the other pane
* [ ] In four-pane mode, assign Apps & history and Widgets independently to each of the four positions
* [ ] Select the other live panel's occupied four-pane position: the two panels swap and neither is duplicated or lost
* [ ] Switch between two- and four-pane layouts: each geometry restores its own saved assignments and divider sizes
* [ ] Upgrade a legacy Widgets-first layout: the same visual order is preserved by the migrated pane assignments

#### Overlapping workspace widgets
* [ ] Move one freeform widget partly or fully over another: both saved bounds are preserved without collision correction
* [ ] Touch or long-press a visible portion of the lower widget: it becomes the top widget
* [ ] In edit mode, use the up/down layer controls: the selected widget moves in front of or behind the other widgets
* [ ] Restart Smart S: overlapping positions and exact back-to-front layer order are restored

#### Compact app-usage timeline
* [ ] Open Usage history on a gesture-navigation device: the title and actions start below the status bar and the final row ends above the navigation inset
* [ ] With tracking enabled and Usage Access granted, no permanent tracking-status strip consumes timeline space
* [ ] Verify app rows contain only time, compact icon, app name and duration; package/activity names are absent from the default timeline
* [ ] Move between multiple activities inside one app: adjacent activity records render as one continuous app session with the full elapsed duration
* [ ] Leave an app and return later, or place another app/screen event between uses: the distinct sessions remain separate
* [ ] Use a long app or event label: the two-line row remains fixed-height and overflowing text auto-scrolls instead of creating another line

#### Battery monitor
* [ ] Change charging state or battery level: monitor notification, dashboard, and hosted widgets show the new Android status without waiting for the periodic history sample
* [ ] With charge-counter support, verify capacity resolves from either a 5% charging or discharging range and remains available across restarts
* [ ] Without charge-counter support, verify current/design or direct-level fallbacks produce a terminal value or explicit Unavailable state—never permanent Learning
* [ ] Keep the screen on for two one-minute samples: screen-on rate becomes available; repeat with screen off for the screen-off bucket
* [ ] Compare Current now, percent, charging state, temperature, and voltage against Android's battery state; signed current is not rewritten from an assumed state

#### Authoritative notification actions in Vertical List
* [ ] Post a notification and open ordinary Vertical List: its exact live row shows Mark read and dismisses only that Android notification
* [ ] Swipe the notification away in Android's notification panel, then return Home: its saved history row remains readable but Mark read is absent
* [ ] Post a newer notification from the same app while an older saved row exists: the older row cannot open, dismiss, or mark the newer notification
* [ ] Repeat with saved SMS and Truecaller/message history: Mark read appears only while the matched notification is currently present in Android's panel; Open message remains available
* [ ] Kill and restart Smart S with stale notification cache data: no notification dot, bottom-pinned live row, native notification view, or Mark read action appears until Android supplies a verified active snapshot
* [ ] Disconnect and reconnect notification-listener access: live actions disappear immediately on disconnect and return only for notifications in the new platform snapshot

#### U-style icon loading
* [ ] Clear Smart S from memory, launch directly into U style, and verify every visible card replaces its temporary icon with the correct app, shortcut, contact, setting, notification, or communication icon
* [ ] Rotate or rebuild U style while icons are still loading: renderer-owned cards update in place and never remain transparent
* [ ] Verify the icon-derived card accent updates when the asynchronous icon arrives
* [ ] Repeat the cold-cache check in horizontal Icons, Names, and Cards modes
* [ ] Scroll and search a long cold-cache history: icon decoding stays off the UI thread and interaction remains responsive

#### Automated history
* [ ] When "Show incoming calls" is disabled, callers are not added to history
* [ ] When "Show incoming calls" is enabled, callers are added to history
* [ ] When "Show newly installed apps" is disabled, new apps are not added to history
* [ ] When "Show newly installed apps" is enabled, new apps are added to history

### Favorites
* [ ] Favorite bar is displayed automatically at startup
* [ ] Clicking on a favorite trigger the correct Intent
* [ ] When going back to KISS, the favorite has been added to history
* [ ] Long-clicking favorite displays the menu
* [ ] Long-click menu can be used to remove the favorite
* [ ] Empty favorites are not displayed (favorites takes all available space in the bar)
* [ ] When entering a search query, favorite bar is hidden
* [ ] When search query is removed, bar appears again
* [ ] When coming back from an application launched through search, bar is displayed again
* [ ] When kiss bar is opened, favorites bar is hidden
* [ ] When kiss bar is opened, internal favorites bar is hidden
* [ ] When kiss bar is opened, you can't click on the menu button behind the kiss bar (not even visible, doesn't respond to touch events either)
* [ ] When searching and pressing home, query is cleared, and favorites are displayed
* [ ] When adding a favorite, it appears automatically, and favorites are evenly spaced
* [ ] When removing a favorite, it disappears automatically, and favorites are evenly spaced
* [ ] When viewing the search list and adding an application to favorites, the app list remains visible and the favorite appears
* [ ] When searching and adding a result to favorites, the search remains visible and the favorite appears

#### Minimalistic mode on for favorites
* [ ] In settings, UX, enable Minimalistic mode and Minimalistic mode for favorites
* [ ] Favorites bar is hidden by default
* [ ] Touching the screen on an empty area displays history and the favorites
* [ ] When entering a search query, favorite bar is hidden
* [ ] When search query is removed, favorite bar is hidden
* [ ] When searching and pressing home, favorite bar is hidden
* [ ] When coming back from an application launched through search, favorite bar is hidden
* [ ] When kiss bar is opened, favorites are displayed

#### When using the internal favorite bar
* [ ] In settings, Favorites settings, disable "Show favorites above search bar", disable Minimalistic mode and Minimalistic mode for favorites
* [ ] The external favorite bar is hidden by default
* [ ] When entering a search query, external favorite bar is hidden
* [ ] When search query is removed, external favorite bar is still hidden
* [ ] When coming back from an application launched through search, external favorite bar is hidden
* [ ] When kiss bar is opened, favorites are visible in the kiss bar
* [ ] When kiss bar is opened, favorites can be clicked
* [ ] When kiss bar is opened, favorites can be long-clicked
* [ ] When kiss bar is opened, favorites' context menu can be interacted with

#### When using the external favorite bar, with transparent favorite bar
* [ ] In settings, Favorites settings, enable "Show favorites above search bar". In UI, enable "Transparent favorite bar"
* [ ] The bar background is transparent


#### When using the internal favorite bar, with transparent favorite bar
* [ ] In settings, Favorites settings, disable "Show favorites above search bar". In UI, enable "Transparent favorite bar"
* [ ] The bar background is *not* transparent, but still green.

#### First run
* [ ] On the first run, favorites are set by default (browser, contact, and phone)

### UI
#### Theming
* [ ] Picking a theme in the settings updates the settings UI (light or dark)
* [ ] Picking a theme in the settings updates the main screen theme
* [ ] Loader circle is properly tinted according to the primary color
* [ ] Launcher icon is properly tinted according to the primary color
* [ ] KISS bar background is properly tinted according to the primary color
* [ ] Notification bar is of the selected color on the main screen theme

#### Icons pack
* [ ] TODO: theme icon packs

#### General UI
* [ ] Transparent search bar is displayed transparent
* [ ] Large search bar is... large
* [ ] When using large search bar and internal favorites bar, favorites are scaled appropriately
* [ ] When using large search bar and internal favorites bar, kiss bar is scaled appropriately (same height as search field)

### UX
#### Keyboard
* [ ] Disable keyboard on start
* [ ] Press home. Keyboard is not displayed
* [ ] Press back. Keyboard is not displayed
* [ ] Display app list. Hide app list, keyboard is not displayed
* [ ] Touch search field. Keyboard is displayed
* [ ] Open a search result. Press home, keyboard is not displayed
* [ ] Open a search result. Press back, keyboard is not displayed
* [ ] Enable keyboard on start
* [ ] Press home. Keyboard is displayed
* [ ] Display app list. Hide keyboard. Hide app list, keyboard is displayed
* [ ] Open a search result. Press home, keyboard is displayed
* [ ] Open a search result. Press back, keyboard is displayed
* [ ] (using Swiftkey keyboard) Disable "keyboard suggestions fix". Keyboard displays suggestions when typing
* [ ] (using Swiftkey keyboard) Enable "keyboard suggestions fix". Keyboard does not display suggestions when typing

#### Minimalistic mode
* [ ] When using minimalistic mode and pressing home, history is not displayed
* [ ] When using minimalistic mode and pressing the search bar, history is not displayed
* [ ] When using minimalistic mode and pressing anywhere else, history is not displayed
* [ ] When using minimalistic mode with history-touch and pressing anywhere else (outside of a widget), history is displayed
* [ ] When using minimalistic mode with history-touch, display history then press back, history is hidden
* [ ] When using minimalistic mode with history-touch, display history then press home, history is hidden
* [ ] When scrolling down on the history, the keyboard disappears
* [ ] Disable show keyboard on start
* [ ] When using immersive mode for the notification bar, notification bar disappears when pressing home
* [ ] When using immersive mode for the notification bar, notification bar appears when searching for a text
* [ ] When using immersive mode for the navigation bar, navigation bar disappears when pressing home

##### Widgets
* [ ] When using minimalistic mode, the three dot menu has an option to Add a widget
* [ ] Selecting the option displays a list of all available widgets
* [ ] Selecting a widget displays the widget when history is empty
* [ ] Widget is hidden when displaying app list
* [ ] Widget is hidden when searching
* [ ] When opening a search result and pressing back, Widget is displayed
* [ ] When opening a search result and pressing home, Widget is displayed
* [ ] Clicking on the widget opens the widget app
* [ ] Clicking outside of the widget with history-touch replace the widget with history

#### Portrait / landscape
* [ ] When portrait-locked, app can't pivot
* [ ] When not locked, app can pivot
* [ ] When not locked and searching, app can pivot and search remains
* [ ] When not locked and viewing app list, app can pivot and app list remains available

#### Apps
* [ ] App list is displayed alphabetically when A-Z is selected in "App list sort order"
* [ ] App list is displayed in reverse order when Z-A is selected in "App list sort order"
* [ ] With show app tags enabled, long press an app and add a tag. Tag is displayed
* [ ] Search for the tag you just added, app is displayed and tag is highlighted
* [ ] With show app tags disabled, long press an app and add a tag. Tag is not displayed
* [ ] Search for the tag you just added, the app is displayed with matching tag
* [ ] With hide app icons disabled, app icons are displayed
* [ ] With hide app icons enabled, app icons are not displayed (empty space)

#### Wallpaper
* [ ] Wallpaper reacts to touch events
* [ ] TODO: drag events

### Providers settings
* [ ] Search for a contact. Make sure it is displayed as a result and appended to history on click
* [ ] Search for a device setting. Make sure it is displayed as a result and appended to history on click
* [ ] Search for a phone number. Make sure it is displayed as a result and appended to history on click
* [ ] Search for a shortcut (you'll probably need to create one from an app first, for instance, WhatsApp). Make sure it is displayed as a result and appended to history on click
* [ ] Search for a long text. Make sure a web search option is displayed as a result
* [ ] From Providers selection, disable Contacts. From now on, they are not displayed anymore in search or history
* [ ] From Providers selection, disable Device settings. From now on, they are not displayed anymore in search or history
* [ ] From Providers selection, disable Phone numbers. From now on, they are not displayed anymore in search or history
* [ ] From Providers selection, disable Shortcuts. From now on, they are not displayed anymore in search or history
* [ ] From Providers selection, disable Web Search. From now on, they are not displayed anymore in search
* [ ] Add a search provider. Ensure it is available in the select search provider setting
* [ ] Select a search provider. Ensure it is available in search
* [ ] Reset search providers. Ensure only default providers are visible
* [ ] Delete a search provider. Ensure it is not available in search anymore
* [ ] Disable search providers. Disable minimalistic mode. Enter a query with no results. Help text is displayed.
* [ ] Disable search providers. Enable minimalistic mode. Enter a query with no results. Nothing is displayed

### Advanced settings
* [ ] Change default launcher option opens system dialog to pick a launcher
* [ ] TODO: root mode
* [ ] "Restart KISS" option closes the settings and reopen KISS

### Misc
* [ ] Rate the app settings appears if history has more than 300 items
* [ ] Help icon opens help website
