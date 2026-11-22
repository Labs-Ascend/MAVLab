# MAVLab Android

MAVLab by Ascend Labs is an Android quadcopter simulator with live telemetry, local flight controls, and an engine-driven 3D view.

## Current Scope

MAVLab v1.6.1 (version code 24) has four product surfaces:

- Cockpit: live operations, telemetry, safety state, and MAVLink status
- Control: calibrated phone/manual input, deliberate flight actions and contextual mission controls
- SIM: 3D physical behavior visualization
- Ops: diagnostics, logs, export staging, QGC setup, and release checks

Current implementation includes:

- Android Compose shell and foreground simulation service
- MAVLink v2 UDP telemetry and QGroundControl command handling
- Quadcopter physics, motor mixing, PID stabilization, takeoff, landing, battery model
- Phone tilt controller with calibration and manual fallback controls
- Telemetry dashboard cards and rolling charts
- SceneView-based 3D drone model tab
- Contextual received missions, spatial route view and engine-owned fence/rally recovery
- Optional full-screen display and guarded continuous swipe navigation
- Ops-controlled, off-by-default recording, review and export
- First-launch onboarding
- CI and release metadata scaffolding

## Build

The project expects Android SDK 35 and Java 17.

```bash
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew lintDebug testDebugUnitTest assembleDebug
```

Install on a connected phone:

```bash
adb install -r app/build/outputs/apk/debug/MAVlab.apk
```

## QGroundControl

MAVLab broadcasts MAVLink telemetry over UDP and tracks validated ground-control heartbeats. When MAVLab is backgrounded, it provides a one-minute handoff window for opening QGroundControl and then keeps the runtime alive while a GCS is connected or a flight is active. If it remains idle and disconnected, it stops automatically. Phone gyro input is suspended in the background, while motor audio remains synchronized with the retained simulation; split-screen is optional.

## Release

GitHub Actions workflows live in the repository root under `.github/workflows/`. Play Store draft metadata lives under `fastlane/metadata/android/en-US/`.

Run `bundleRelease` in addition to the debug gate when preparing a release. The v1.6.1 public APK and AAB use the permanent release certificate. The [release notes](../docs/v1_6_1_release_notes.md) and [release runbook](../docs/operations/release.md) define publication and signing.
