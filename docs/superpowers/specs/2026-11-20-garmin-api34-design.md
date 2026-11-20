Project: FedWheel-Android
Document: design

# Re-enable Garmin Connect IQ on Android 14+ (design)

## Intent
Owner's InMotion V8 → this app → FedWheel watch app (fork of WheelLog's Garmin companion, fēnix 8).
Upstream disabled the Garmin service in 2023 (`MainActivity.toggleWatch`: "TODO: Fix garmin for API 34"),
so the "Garmin Connect IQ" setting does nothing since 3.1.1. Goal: data reaches the watch again.

## Goals
- The watch button starts/stops `GarminConnectIQ` when the setting is on (as before 3.1.1).
- The service runs on Android 14+ (targetSdk 35) without crashing.
- Watch app opened *after* the phone connected still gets the server port (today it is sent once only).

## Non-goals
Removing features, personal features, Play Store release (next steps, separate design).

## Approaches
- A. Install old WheelLog 3.0.15 — rejected as the long-term path (2023 code), fine as a quick check.
- B. Fix current code (chosen).

## Decided
- Uncomment the Garmin line in `toggleWatch`. Manifest already declares `foregroundServiceType="connectedDevice"`
  and `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- New `GarminPortAnnouncer` (pure Kotlin): re-announce the port while the watch has not polled the web server
  for ≥ 5 s; checked every 2 s by the service; any HTTP request from the watch counts as a poll.
- Build the `debug` variant (`com.cooper.wheellog.debug`), which installs next to the store app without
  signature conflicts.

## Risk
Connect IQ mobile SDK might not deliver to a sideloaded watch app. Verified on device.
