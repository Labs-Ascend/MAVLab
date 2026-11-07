# Simulation Model

Audience: Simulation, control, and validation contributors
Status: Current high-level contract
Last verified: 2026-08-31

MAVLab models a quadcopter closely enough to teach control, telemetry, missions, and failures. It is not ArduPilot SITL, a certified flight-dynamics model, or proof that an identical physical aircraft will behave the same way.

## Update loop

`PhysicsSimulationEngine` schedules a nominal 10 ms tick (100 Hz). Scheduler cadence, elapsed-time integration, UI refresh, and telemetry rates are distinct.

```text
control source / mission intent
  -> control authority and flight mode
  -> autopilot / position controller
  -> motor mixing and failure effects
  -> physics integration and environment
  -> battery / mission / safety state
  -> DroneState publication
  -> UI, recording, sound, and MAVLink observers
```

## Components

- `PhysicsModel` — thrust, torque, drag, wind-relative velocity, position, attitude, ground contact, and geographic conversion.
- `Autopilot` — cascaded control behavior for supported modes.
- `PositionController` — position/velocity intent for autonomous modes.
- `MissionEngine` — uploaded mission progression.
- `FailureInjector` — simulated faults and perturbations.
- `DroneState` — authoritative vehicle-state snapshot.

## Supported high-level modes

STABILIZE, ALT_HOLD, AUTO, GUIDED, LOITER, RTL, and LAND exist in the current engine. A familiar mode name does not guarantee complete ArduCopter equivalence.

Working-tree RTL selects an eligible rally/home destination, holds position for climb if required, travels to that endpoint, then lands and disarms. RTL on the ground does not launch. A mission RTL uses the same engine path and completes at landing. Enabled geographic/altitude fences are checked before control output; an actionable breach latches SAFETY_RECOVERY with return/local-landing fallback. Geometry compiles at upload, uses physical local position anchored to the simulation origin, and does not use injected GPS drift as ground truth. Geometry/parameters/stores and reverse paths are detailed in the [MAVLink contract](mavlink-contract.md#simulated-fence-and-rally-contract-working-tree). No terrain or obstacle path planning is added.

Local takeoff uses ALT_HOLD: the autopilot controls altitude while retaining normalized pilot roll, pitch, and yaw intent. GCS takeoff uses GUIDED position control. Fresh local Phone Sensors arming resets a retained autonomous mode to STABILIZE, preserves an explicitly selected ALT_HOLD mode, and clears stale direct-RPM overrides. This canonical sensor-control contract is recorded in [ADR-0005](decisions/0005-canonical-phone-sensor-control.md).

Fresh Custom Input arming also restores manual-mode setup through `armForLocalInput`. Custom setters and Direct RPM targets are applied by AppRuntime independently of selected Compose surface. Direct RPM bypasses autopilot output only while armed with Controller authority; local landing first clears that override and leaves the bench mode. Physics and published DroneState remain authoritative. See [ADR-0006](decisions/0006-controller-operational-layout.md).

## Frames and units

Every boundary states its frame and unit explicitly:

- body axes and motor layout;
- local position/velocity frame;
- geographic latitude/longitude/altitude conversion;
- altitude reference (AGL or MSL);
- angle units (degrees or radians);
- speed and distance units;
- normalized or physical motor/throttle values.

Convert once near the boundary and test the conversion.

Working-tree `mavlab-local-grid-v1` is the existing flat north/east metre convention:
latitude = origin latitude + north / 111320; longitude = origin longitude + east /
(111320 × max(0.2, cos(origin latitude))). The origin stays fixed until a disarmed
simulation-location reset. Moving return-home does not move this grid. This is an
approximation, not UTM or a true ECEF/ENU projection. Map packs use this exact grid,
with a maximum 50 km extent and nonpolar origins; small training areas are preferred.
Fence shapes retain their own geographic local-distance approximation.

Physics integration, guided geographic targets, received mission targets and
home/rally conversion now share the fixed grid. GPS failure noise applies only to
reported latitude/longitude; it cannot accumulate into physical position or offset
an uploaded geographic target. Physical north/east, altitude and yaw remain the
capture truth. The camera must never derive pose from compass-offset heading or
noisy GPS. `PhysicsModel.step` can also be used standalone without an engine frame;
that test-oriented path retains its incremental geographic conversion.

## Fidelity language

Documentation classifies behavior as:

1. **Modeled** — implemented and tested.
2. **Approximated** — useful simplification with stated limits.
3. **Not modeled** — outside current scope.

“Digital twin” means state-linked simulation and visualization, not certified replication of an airframe.

## Determinism and evidence

Tests control time, initial state, parameters, mission, and failure inputs where practical. New randomness exposes or records a seed. Performance/fidelity claims include scenario, device/build, method, and comparison target.

Source areas: `simulation/engine`, `simulation/physics`, `simulation/autopilot`, `simulation/mission`, and `simulation/failures`. Real-device evidence is still needed for timing, rendering, sensors, and Android lifecycle behavior.

## Received mission publication

MissionProgress carries source and actual AUTO execution history. Disarmed AUTO does not advance mission items. GCS upload completion tags received source; internal demo fixtures do not create received routes. Raw MSL mission altitudes normalize to AGL once at engine load; local north/east metre targets are published to all surfaces. SIM fits these targets together with the actual vehicle and observed trail using equal spatial scale, without changing physics or inventing vehicle movement. See [ADR-0007](decisions/0007-contextual-missions.md).
