# Smart S Launcher — power and lifecycle optimisation

New release candidate: `3.30.179` (Android versionCode `607`).
Base release: `v3.30.178`, commit `edb5302e299c29ae065371d836ecd8ff5735d205`.

## Changes

- Meaningful battery-state changes trigger samples; noisy identical/current-only broadcasts
  no longer restart samplers or force widget rebuilds.
- Live notification updates are 60 seconds while charging/screen on and 5 minutes when
  screen off. Actual battery samples reset the independent live update deadline.
- Unchanged battery alert latch values are not rewritten.
- On installations without an explicit preference, battery monitoring is opt-in.
  Users with `smart-battery-monitor-enabled=true` keep their existing monitoring.
- Frozen-app fallback defaults to `package-only`. A one-time migration
  retires previously persisted 15-second intervals on existing installations
  because the previous default can be stored on first opening Settings.
  Explicit 30/60/300-second choices and package-only remain unchanged.
  A user can deliberately reselect 15 seconds after the upgrade if necessary.
- Internal live battery-monitor notification updates do not become notification-history
  events and are not sent to the avatar/visual capture pipeline. Battery alerts and all
  other notifications are preserved. When notification history is off, no history
  persistence tasks are enqueued.
- Concurrent asynchronous app-usage synchronisation requests are coalesced and
  re-check user preference before running.

## Existing layout / timer guards verified

- Auto Expand cancels title/preview auto-scroll timers.
- Inactive 3D Wheel and Vertical Cards trees are hidden/cleared; Maps card updates
  are restricted to active Cards.
- Metadata refresh is pending-package and network-driven, not unconditional polling.
- App Usage scheduled interval remains six hours when opted in.

## Verification

Local unit tests: `BatteryBroadcastPolicyTest` and `BatteryTelemetryPolicyTest`.
Android instrumentation and battery/CPU profiling on real devices still required.

This branch is **not** a signed production release and does not replace
the APK installed on the user's phone.
