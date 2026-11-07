# MAVLab Glossary

Audience: Everyone changing or documenting MAVLab
Status: Living canonical vocabulary
Last verified: 2026-08-31

## Product and runtime

**MAVLab** — the Android application and its on-device drone simulation runtime.

**Simulated vehicle** — the drone represented by MAVLab state and exposed through the UI and MAVLink; never a real aircraft.

**Simulation runtime** — the engine, protocol server, recording, sensor, sound, and supporting state coordinated by `AppRuntime` and owned through `SimulationService`.

**Digital twin view** — the SIM surface rendering authoritative simulation state. Here “digital twin” means state-linked simulation and visualization, not certified fidelity to a specific airframe.

**Product surface** — Cockpit, Control, SIM, or Ops. Mission is a contextual operating state.

## Control and flight

**Flight mode** — autopilot behavior such as STABILIZE, ALT_HOLD, AUTO, GUIDED, LOITER, RTL, or LAND.

**Control authority** — dominant high-level flight intent: `IDLE`, `CONTROLLER`, `GCS_DIRECT`, `GCS_MISSION`, or working-tree `SAFETY_RECOVERY` (an engine-owned fence response, not a GCS peer).

**Fence recovery** — latched simulated RTL/LAND after an actionable geofence breach. Manual input resumes only after explicit disable-and-mode selection or disarm; re-entry alone does not cancel recovery.

**Rally point** — uploaded alternative simulated RTL destination, selected by distance and eligibility. It is not an independently animated route or a real-aircraft landing-site certification.

**Pilot input** — roll, pitch, yaw, and throttle intent from phone sensors or built-in controls.

**Mission** — an ordered set of autonomous commands/waypoints uploaded or loaded into the simulation.

**Failure injection** — a deliberate simulated fault or environmental perturbation.

## Protocol

**GCS** — ground control station software. QGroundControl is MAVLab’s active integration target.

**QGC** — QGroundControl.

**MAVLink** — telemetry and command protocol between MAVLab’s simulated vehicle and a GCS.

**SYSID** — MAVLink system identifier. MAVLab currently defaults to vehicle SYSID 1; QGC commonly uses 255.

**COMPID** — component identifier inside a MAVLink system.

**Peer** — a MAVLink endpoint detected by MAVLab and eligible for telemetry/command exchange.

**Accepted no-op** — a request that receives acceptance without the complete requested effect; not full implementation.

## State and evidence

**DroneState** — authoritative vehicle-state snapshot published by the physics engine.

**Data freshness** — whether the latest authoritative runtime state is live, delayed, stale, or unavailable. Freshness describes trust in displayed values, not MAVLink peer connectivity.

**Cockpit flight phase** — UI-facing presentation derived conservatively from authoritative armed state, mode, altitude, vertical speed, and session evidence until the engine publishes an explicit phase contract.

**AGL** — altitude above ground level.

**MSL** — altitude relative to mean sea level.

**Working-tree behavior** — behavior present in local uncommitted files; not a released capability.

**Validation specification** — repeatable description of what to test and how.

**Validation result** — immutable execution record tied to a version/commit, device, Android version, QGC version, tester, date, and evidence.

**ADR** — architecture decision record: context, decision, consequences, and replacement conditions.

**Control** — the product destination for local inputs and received-mission actions; the local control-authority enum remains `CONTROLLER`.

**Received mission** — non-empty mission items explicitly loaded from a successful GCS upload, with provenance retained by the engine and persistence. A received ACK does not establish execution.

**Unverified saved mission** — retained legacy items with no source metadata; local AUTO start and spatial route display require a new GCS upload.

**Simulation local grid** — `mavlab-local-grid-v1`, the fixed-origin flat north/east metre convention shared by physical/geographic conversion and virtual map packs. Moving return-home does not move this origin; GPS noise is reported separately.

**Virtual map capture** — a saved image rendered from an offline map pack at an authoritative physical simulated pose. It is labelled simulated data and is neither an aerial photograph nor phone-camera imagery.

- **Distance photo trigger**: mission command 206 schedules virtual exposures from actual horizontal engine travel; zero distance stops that cadence, while its immediate-photo flag still requests one saved image.
- **Photo hold**: an observable BRAKE state after a required capture cannot complete; mission progression and distance triggers wait for an explicit AUTO resume or recovery command.
