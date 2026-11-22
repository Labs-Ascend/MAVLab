# MAVLab Contributor and Agent Guide

MAVLab is a phone-first, offline-capable quadcopter simulator from Ascend Labs. Android runs the flight model, controls, telemetry, and 3D digital twin. QGroundControl extends the simulator with supported MAVLink and mission workflows.

This file records the engineering defaults that make changes to MAVLab coherent. It is not a substitute for understanding the task. When a rule conflicts with a real constraint, name the conflict and get a deliberate decision rather than silently working around it.

## What MAVLab must not compromise

### 1. Friendly first, technically honest

MAVLab should reduce setup complexity without lying about drone concepts. Simplify the tooling cliff, not the meaning of attitude, control authority, telemetry, missions, failures, or safety.

### 2. Phone-first and offline-capable

The core flight simulation must run on the Android device. QGroundControl may connect locally or across a LAN, but Docker, cloud services, Python bridges, ROS, Gazebo, or SITL must not become prerequisites for the core product.

### 3. Protocol truth over cosmetic success

QGroundControl compatibility is an engineering contract, not a green connection icon. Advertise only capabilities MAVLab implements. Distinguish implemented behavior, accepted no-ops, partial behavior, and unsupported behavior.

### 4. One authoritative simulation state

Commands must pass through the simulation engine and produce state that every surface observes. Do not animate the 3D drone, telemetry, or mission UI independently of the engine just to make a feature appear complete.

### 5. Safe and observable control

The user must be able to tell who controls the simulated vehicle, how to leave that state, and what happens after disconnection or interruption. Arm/disarm, start/stop, connect/disconnect, mission/manual control, and failure/reset flows need visible reverse paths.

### 6. Performance without accidental machinery

MAVLab runs physics, networking, recording, audio, sensors, and Compose UI on a phone. Avoid UI-thread blocking, unnecessary repainting, unbounded state, and abstraction built only for hypothetical future products. Measure the real constraint and prefer the smallest model that makes correct behavior unsurprising.

### 7. Armed manual phone-sensor control is canonical

With Phone Sensors selected, an armed local simulated vehicle must accept calibrated phone input through the simulation engine while MAVLab is visible, regardless of whether Cockpit, Control, or SIM is selected. A new local sensor flight must restore a manual mode and clear stale motor overrides. Local takeoff must retain phone attitude steering while controlling altitude. Future UI/UX changes must preserve this invariant and its regression tests; screen composition must never own sensor eligibility. Explicit GCS mission authority, navigation/recovery commands, chosen advanced input modes, and background sensor suspension remain distinct control states with visible meaning. Never substitute a UI-only 3D rotation for engine response.

## A small glossary

Use these terms consistently. The complete glossary is in `docs/internals/glossary.md`.

- **MAVLab**: the Android application and its on-device simulation runtime.
- **simulated vehicle**: the drone represented by the engine; never imply it is a real aircraft.
- **GCS**: ground control station software; QGroundControl is the actively supported GCS.
- **control authority**: the currently dominant high-level source of flight intent: `IDLE`, `CONTROLLER`, `GCS_DIRECT`, `GCS_MISSION`, or `SAFETY_RECOVERY` (automatic fence recovery).
- **simulation state**: authoritative engine state such as attitude, position, velocity, battery, mode, and armed state.
- **digital twin view**: the SIM surface rendering simulation state. It is not a separate physics model.
- **peer**: a detected MAVLink endpoint receiving telemetry and/or sending commands.
- **working-tree behavior**: behavior present in local uncommitted code; do not describe it as released.

## The ways to hurt MAVLab

1. **Claiming more protocol support than exists.** An accepted ACK is not proof that a command changed simulation behavior. Update `docs/internals/mavlink-contract.md` and tests together.
2. **Bypassing the engine.** UI-only movement or state creates a false digital twin. Route control through `PhysicsSimulationEngine` and observe `DroneState`.
3. **Confusing release truth with local work.** Check Git status and app version before writing release notes. Uncommitted behavior is draft behavior.
4. **Running forever in the background.** Background retention affects battery, networking, Android policy, and user trust. Changes to `SimulationService` or `BackgroundRuntimePolicy` require lifecycle documentation and tests.
5. **Changing units or frames silently.** State the coordinate frame and unit at every boundary. Convert once, near the boundary, and test the conversion.
6. **Testing against uncontrolled endpoints.** Use isolated ports and known QGC/test environments. Never imply real-aircraft compatibility without an explicit safety review.
7. **Duplicating canonical documentation.** Public technical behavior is canonical under root `docs/`. Private Obsidian notes may add operating context but must link back rather than silently diverge.

## Impact matrix

Before calling a change complete, state which rows apply and what was verified:

- **Product surfaces**: Cockpit, Control, SIM, Ops, contextual missions, onboarding.
- **Control paths**: local/manual input, phone sensors, GCS direct commands, GCS missions.
- **Runtime states**: foreground, background handoff, validated GCS, armed flight, AUTO mission, explicit stop, process recreation.
- **Simulation**: physics, autopilot, failures, battery, mission progression, state publication.
- **Protocol**: discovery, peer tracking, telemetry, commands, parameters, mission protocol, identity/capabilities.
- **Reverse states**: arm/disarm, takeoff/land, start/stop, connect/disconnect, inject/reset, begin/end recording.
- **Evidence**: focused unit tests, lint/build where applicable, real-device/QGC evidence where behavior crosses Android or protocol boundaries.
- **Documentation**: user-visible behavior in `docs/user/`; architecture/contracts in `docs/internals/`; recurring procedures in `docs/operations/`; release evidence in `docs/validation/results/`; new terms in the glossary.

A row may be `not applicable`, but it should not be silently forgotten.

## Where things live

- `mavlab-android/app/src/main/java/com/ascend/mavlab/MainActivity.kt` — Android activity and app entry.
- `.../service/SimulationService.kt` — foreground-service and runtime lifecycle owner.
- `.../core/common/AppRuntime.kt` — process-wide composition root and UI-facing facade.
- `.../simulation/engine/PhysicsSimulationEngine.kt` — authoritative simulation orchestration.
- `.../simulation/physics/` — quadcopter and environment model.
- `.../simulation/autopilot/` — flight control and mission behavior.
- `.../core/mavlink/MavlinkUdpServer.kt` — QGC/MAVLink boundary.
- `.../feature/` — Compose product surfaces.
- `docs/user/` — shipped-product guidance.
- `docs/internals/` — architecture, contracts, glossary, and decisions.
- `docs/operations/` — release and maintenance runbooks.
- `docs/validation/` — specifications and immutable execution records.

## Verification defaults

Use the smallest meaningful proof first.

```bash
cd mavlab-android
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew testDebugUnitTest --console=plain
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew lintDebug assembleDebug --console=plain
```

The debug artifact is renamed by the build and should be expected at:

`mavlab-android/app/build/outputs/apk/debug/MAVlab.apk`

Do not convert a manual checklist into a permanent `PASS` claim without recording the app version, commit, device, Android version, QGC version, tester, date, and evidence.

## Change discipline

- Understand the constraint before adding architecture.
- Keep one concern per commit or pull request.
- Keep human commit attribution intact. Do not append Claude `Co-authored-by` or `Claude-Session` trailers to this project's commits.
- Add focused tests for changed behavior.
- User-visible changes need user docs.
- Changes to `SimulationService`, `AppRuntime`, `PhysicsSimulationEngine`, `MavlinkUdpServer`, navigation, persisted formats, or compatibility identity require an explicit documentation review.
- Plans and scratch notes are not current architecture. Put durable decisions in `docs/internals/decisions/`; keep historical plans under `docs/archive/` or in an issue.
- Do not push, tag, publish, or open a pull request unless the developer asks.
