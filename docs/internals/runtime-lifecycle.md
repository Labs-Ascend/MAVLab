# Runtime Lifecycle Contract

Audience: Maintainers and Android contributors
Status: v1.6.0 contract; v1.5.3 is the prior baseline
Last verified: 2026-09-02

This document explains when MAVLab keeps the on-phone runtime alive and when it stops.

## Owners

- `MainActivity` reports visibility and explicit lifecycle actions.
- `SimulationService` owns the foreground service, notification, runtime startup/shutdown, and wake/Wi-Fi locks.
- `BackgroundRuntimePolicy` decides whether background execution is retained.
- `AppRuntime` owns simulation and supporting components.

`AppRuntime.runtimeLifecycle` publishes `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, or `ERROR` for UI state. `stateUpdatedAtElapsedRealtimeMs` records the latest engine-state emission using Android elapsed realtime. These are runtime/freshness signals; neither replaces MAVLink connection status or control authority.

All Controller input application belongs to AppRuntime. Phone samples retain the canonical foreground lifecycle; Custom Input setters send PilotInput directly, and Direct RPM follows armed/mission state through runtime observation. Composing or leaving Control does not apply, cancel or recreate these control paths. This ownership change does not change background retention or phone-sensor suspension. See [ADR-0006](decisions/0006-controller-operational-layout.md).

## Intended model

```text
App visible
  -> retain runtime

App enters background
  -> one-minute QGC handoff window
  -> retain if a validated GCS is connected
  -> retain if the vehicle is armed
  -> retain if an AUTO mission is active
  -> otherwise stop after the grace window

Explicit Stop / close-removal
  -> stop immediately
```

The handoff window lets a user open MAVLab and switch to QGroundControl on the same phone before the simulator disappears.

## Background behavior

While retained in the background:

- simulation and MAVLink may continue;
- the foreground-service notification remains visible;
- wake and Wi-Fi locks support active work;
- phone-sensor control is disabled to prevent hidden gyro input;
- drone sound remains active so arming and motor changes commanded from QGroundControl stay audible.

A backgrounded, idle, disconnected runtime outside the grace period should stop rather than consume battery indefinitely.

## Explicit shutdown

Deliberate shutdown wins over retention conditions. Notification Stop and explicit close/removal paths should release locks, stop protocol work, stop simulation, and terminate the service cleanly.

### Procedural audio output ownership

Each procedural render session owns its AudioTrack from creation through pause/flush/release on the render worker. Stop requests cancellation without releasing a native track underneath an in-progress write or underrun-count read. A mutex serializes a rapid restart with the old session's cleanup and shared synthesis buffer/oscillators; session identity prevents old work from publishing a newer session's running status. Native writes are nonblocking, retrying zero/partial transfers with cancellation checks rather than dropping remaining samples. Output errors become fallback status and still dispose the track. Release is terminal for that synth instance and cancels its scope; runtime reconstruction creates a new instance. No UI-thread join or wait for native audio is introduced. This changes resource disposal, not simulation authority, sound modelling, background retention or service policy.

## Invariants

1. Do not start two independent shared runtimes.
2. Acquire and release wake/Wi-Fi locks with service ownership.
3. Never retain forever only because the app was opened once.
4. Do not use phone tilt as live input after backgrounding.
5. Keep motor audio synchronized with authoritative simulation state for the full retained runtime.
6. Timing or retention changes require policy tests and user-doc review.
7. Manifest foreground-service declarations must match actual use.
8. A stopped, unavailable, or stale runtime must not present default telemetry as live state.

## Evidence

Primary source:

- `mavlab-android/app/src/main/java/com/ascend/mavlab/MainActivity.kt`
- `.../service/SimulationService.kt`
- `.../service/BackgroundRuntimePolicy.kt`
- `.../core/common/AppRuntime.kt`

Focused test:

- `mavlab-android/app/src/test/java/com/ascend/mavlab/service/BackgroundRuntimePolicyTest.kt`
- `mavlab-android/app/src/test/java/com/ascend/mavlab/core/common/RuntimeComponentPolicyTest.kt`

Real-device validation records the device, Android version, battery restrictions, QGC build, exact MAVLab build, and observed notification/background behavior.

## Display and navigation review

Compact navigation, edge-to-edge display and the persisted full-screen preference create no additional runtime owner. Swipes operate only on passive status regions. The pager prepares adjacent UI shells, but their UI lifecycle is capped at CREATED: lifecycle-aware collectors and Back handlers remain inactive. SIM's expensive renderer starts only after arrival and is released when fully outside the viewport. SaveableStateHolder preserves presentation state, without inventing vehicle or authority state. Foreground calibrated phone input remains independent of selected surface and display mode. Keyboard/system-bar handling changes window presentation only. Background retention and sensor suspension remain governed by the existing policy. See [ADR-0009](decisions/0009-display-and-navigation.md).

## Mission consolidation review

Four-tab navigation and contextual mission views create no additional runtime owner. Received source survives process recreation; the stored mission restores as loaded, with fresh execution history and no fabricated upload ACK. Unknown legacy source remains retained but blocked for local AUTO/route display until re-upload. Mission action freshness uses the existing RUNNING and elapsed-realtime state-age contract. Retention, background handoff, locks and phone-sensor suspension are unchanged. See [ADR-0007](decisions/0007-contextual-missions.md).

## Ops observation and recording review

Ops history is derived by AppRuntime on the existing 200 ms recording monitor, independent of selected tab. It is bounded and process-local; it creates no runtime owner or background retention condition. Recording defaults to off. Ops can persist an opt-in to automatic arm capture while disarmed. Begin and Stop/save serialize on IO with sampling; an explicit armed Stop/save suppresses opted-in capture until the next arm or explicit Begin. Runtime restart preserves the saved opt-in value and never enables recording implicitly. The existing service Stop/Start intents are exposed with confirmation and accurate frozen-state language. Foreground sensor eligibility, retention timing and lock ownership are unchanged. See [ADR-0008](decisions/0008-ops-health-and-recording.md).

## Safety-plan lifecycle review (working tree)

Accepted fence/rally/configuration snapshots restore after the persisted simulation origin and navigation mission, without restoring armed flight or recovery authority. AppRuntime observes only distinct persisted safety content on IO; transfer-progress text does not cause repeated snapshot writes. This observer is process-local and idle when content is unchanged, not a new service or retention condition. An explicit endpoint stop discards transfer caches while retaining accepted plans. Origin changes atomically remove old-location plans through the engine; Ops clear does not remove navigation data. Armed/recovery flight continues under the existing armed-flight retention policy, and background phone input remains suspended. Engine safety operations/ticks share a monitor so plan commit, clear and origin change cannot race with arming. See [ADR-0013](decisions/0013-simulated-fence-and-rally.md).

## Virtual map camera review (working tree)

AppRuntime owns the camera independently of Compose. Capture IO has a bounded request queue and decoded tile cache. Camera enablement adds no service-retention condition; Stop and origin reset invalidate capture generations and asynchronously release map resources. Process recreation retains completed files and starts with the camera off. Automatic flight recording remains opt-in, and foreground phone-sensor eligibility is unchanged. See [ADR-0014](decisions/0014-offline-map-camera.md).
