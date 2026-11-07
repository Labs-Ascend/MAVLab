# MAVLab by Ascend Labs

A phone-first, offline-capable drone simulator and digital twin. Learn attitude, control, telemetry, missions and failure recovery on Android before taking on ROS, Gazebo, ArduPilot/PX4 SITL, Docker or a multi-process simulator stack.

Physics, autopilot, visualization and the MAVLink endpoint run on the phone. QGroundControl is optional for the core learning loop; no aircraft, cloud service or external simulation server is required.

**MAVLab v1.6.1** · Android 8.0+ · version code **24** · [Downloads](https://github.com/Labs-Ascend/MAVLab/releases) · [Release notes](docs/v1_6_1_release_notes.md).

The release uses a new permanent signing identity. Export important data before migrating from older debug installations; see [upgrade guidance](docs/user/upgrading.md).

## Four connected views

- [**Cockpit**](docs/user/cockpit.md): vehicle state, telemetry and mission progress.
- [**Control**](docs/user/controller.md): local input, calibration and flight actions.
- [**SIM**](docs/user/sim.md): the engine-driven digital twin and received mission route.
- [**Ops**](docs/user/ops.md): health, connectivity, recording and setup.

Missions span these views: concise Cockpit progress, expandable Control actions, received-route geometry in SIM and exact upload evidence in Ops. There is no separate Mission tab, built-in sample route or onboard mission planner.

## Demos

### MAVLab on-device

Local controls, live telemetry/charts and the 3D digital twin, with original recorded sound.

https://github.com/user-attachments/assets/9f67a5bb-a83a-4d4b-a351-1c08257268eb

84 seconds · compressed issue-upload copy: 30 fps, H.264 MP4, 6.90 MB. Recorded in October 2026; this illustrates the earlier interface, not the v1.6.1 capture changes.

### MAVLab + QGroundControl

QGC plan editing and simulated AUTO flight, with the MAVLab phone view and its audio alongside it.

https://github.com/user-attachments/assets/6ba838d5-6c53-495b-b535-c90d67b0a061

118 seconds · 1080p, 30 fps · compressed issue-upload copy: H.264 MP4, 6.75 MB. The recording ends during RTL before touchdown and does not establish all-command compatibility.

[Media provenance](assets/media/2026-10/README.md) · [Issue-upload copies](assets/media/issue-upload/README.md).

## What v1.6.1 adds

- **Bounded survey missions:** up to 1,000 navigation items, preserved mission parameters and physical engine distance triggers.
- **Automatic simulated captures:** QGC receives virtual exposure events without map loading or image-file writes. No Dandora map is needed; choose the simulated launch location before uploading a mission elsewhere.
- **Real hold/resume:** BRAKE pauses movement, progression and capture scheduling; AUTO resumes and LAND/disarm remains a reverse path.
- **Simpler Ops:** no camera setup card; prior map photos remain exportable under Recordings & review. Flight recording remains opt-in.
- **Permanent signing:** non-debuggable release APK and signed AAB use the new published certificate. This is a deliberate migration from older debug identities.

## Core learning features

- **A focused interface:** Cockpit, Control, SIM and Ops; contextual missions; a nine-page onboarding tour ending with **Start flying**.
- **Fluid navigation:** continuous guarded horizontal swipes, compact labelled tabs and optional full-screen display. Sliders, route gestures and the 3D camera retain their own input.
- **Engine-owned control:** calibrated Phone Sensors, Custom Input and deliberate Direct RPM. Foreground phone steering continues across Cockpit, Control and SIM; GCS missions and fence recovery remain distinct authorities.
- **Readable telemetry and SIM:** one combined altitude/attitude chart, a centered starting camera and inspection lighting that keeps the model visible when orbiting.
- **Ops health and recovery:** explicit GCS link state, incidents, bounded transition history, diagnostics and failure exercises.
- **Opt-in recording:** recording is **off by default**, including AUTO missions. Begin explicitly in Ops or enable recording on arm while disarmed; stop/save, review and export sessions.
- **MAVLink 2 and resilient transfers:** unsigned MAVLink 2 output, CRC-checked supported input, targeted ACKs, missing-item retries and preservation of accepted plans after failed replacement uploads.
- **Simulated fences and rally returns:** separate safety-plan stores, explicit fence enablement, engine-driven breach recovery, eligible rally/home RTL, landing and disarming. See [flight boundaries](docs/user/flight-boundaries.md).

The simulator uses flat ground and straight-line return control. It does not implement terrain/obstacle routing, SmartRTL, MAVLink signing, full ArduPilot equivalence or real-aircraft safety qualification. Timed loiter and waypoint pause semantics remain limited. Implemented, partial, no-op and unsupported behavior is documented in the [MAVLink contract](docs/internals/mavlink-contract.md).

## Start learning

**Migration:** v1.6.1 cannot update earlier debug or differently signed installations in place. Export recordings and earlier map photos, save QGC plans and preserve settings before deciding to migrate. Uninstalling erases private data; do not uninstall merely to bypass a signature error. [Upgrade guidance](docs/user/upgrading.md).

### Signed app builds

Get the versioned APK and AAB from [GitHub Releases](https://github.com/Labs-Ascend/MAVLab/releases). Install the APK directly. The signed AAB is a store-upload artifact, not directly installable or proof of Play approval. Only APK/AAB files are release attachments; videos use the README/gallery.

[Permanent public certificate](docs/operations/mavlab-release-certificate.pem) · SHA-256 `46ff910b66597a7340d7b7737e598619368b8594bcdc2f05a91efb54d811eb70`.

### Build from source

Requires Java 17 and Android SDK 35; minimum Android version is 8.0 (API 26).

```bash
cd mavlab-android
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease --console=plain
```

Local debug builds use the local debug certificate. Unsigned release builds are signed by the guarded [release procedure](docs/operations/release.md); never commit private signing material.

### First local flight

1. Complete or skip onboarding; **Start flying** opens Cockpit without arming.
2. In Control, choose Phone Sensors and calibrate while grounded.
3. **Arm → Takeoff**; observe the same simulated vehicle in Cockpit and SIM.
4. **Land → Disarm**. Disarming cuts motor thrust; do not use it as normal airborne landing.
5. For a saved session, begin recording in Ops before flight. Recording never starts merely because you opened the app or uploaded a mission.

## Connect QGroundControl

- **Same phone:** open MAVLab, then switch to QGC within the one-minute handoff window. Telemetry targets `127.0.0.1:14550`; split-screen is optional.
- **Desktop over LAN:** use the same trusted Wi-Fi network/subnet. Client isolation and a firewall blocking UDP 14550 can prevent discovery.
- **Identity:** MAVLab currently uses SYSID 1 / COMPID 1; QGC normally uses SYSID 255. Do not place multiple same-ID simulators or real aircraft on the test network.
- **Continuity:** a validated GCS heartbeat, armed flight or active mission retains the background runtime. Phone input suspends in the background; motor audio follows retained simulation. Idle/disconnected runtime stops after the handoff policy expires.
- **Uploads:** keep the simulated vehicle disarmed while diagnosing connection and plan transfers. After upgrading, save your plan and reconnect QGC to refresh capabilities. Upload navigation, fence and rally lists independently; uploading a fence does not enable it.

GCS connection is not control authority or proof of successful command/mission delivery. See [setup](docs/setup_guide.md), [troubleshooting](docs/known_first_run_issues.md) and [mission operation](docs/user/mission.md).

## Documentation and evidence

- [Documentation home](docs/README.md), [user guides](docs/user/README.md) and [teacher guide](docs/teacher_guide.md).
- [Architecture](docs/internals/overview.md), [control authority](docs/internals/control-authority.md), [runtime lifecycle](docs/internals/runtime-lifecycle.md) and [MAVLink contract](docs/internals/mavlink-contract.md).
- [Contributor guide](AGENTS.md), [Android implementation](mavlab-android/README.md), [release runbook](docs/operations/release.md) and [v1.6.0 readiness](docs/operations/v1.6.0-readiness.md).
- [Validation records](docs/validation/results/README.md). Prior build/device passes are not automatically acceptance of the new versioned artifact.
- Release notes: [v1.6.1](docs/v1_6_1_release_notes.md), [v1.6.0](docs/v1_6_0_release_notes.md), [v1.5.3](docs/v1_5_3_release_notes.md), [v1.5.2](docs/v1_5_2_release_notes.md), [v1.5.1](docs/v1_5_1_release_notes.md) and [v1.5](docs/v1_5_release_notes.md).

## Technical paper

[DOI: 10.5281/zenodo.21319694](https://doi.org/10.5281/zenodo.21319694)

**MAVLab: A Phone-First Drone Digital-Twin Simulator for Flattening the Drone-Systems Learning Curve** — Kioko & Kaira, Ascend Labs, 2026. [DOI](https://doi.org/10.5281/zenodo.21319694) · [Markdown source](docs/paper/paper.md) · [How to cite](CITATION.cff).

The paper describes its evaluated release snapshot. MAVLab v1.6.1 does not silently inherit those acceptance results or revise the published research claims.

## License

Apache License 2.0. See [LICENSE](LICENSE).
