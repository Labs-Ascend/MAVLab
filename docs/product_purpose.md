# MAVLab Product Purpose

MAVLab is a phone-first quadcopter simulator for Android. It runs the physics, autopilot, flight controls, telemetry, and 3D view on the phone. Local flight works offline. An optional MAVLink connection gives QGroundControl access to supported telemetry, commands, and mission workflows.

Use MAVLab to fly manually with phone sensors or on-screen input, inspect a simulated vehicle, run received missions, explore simulated failures and recovery, and review recorded sessions. Learning and demonstrations use the same simulation and workflows.

## Product surfaces

- **Cockpit** shows live telemetry, flight mode, control authority, and immediate actions.
- **Control** gives access to local input, calibration, flight actions, and received mission actions.
- **SIM** shows the simulated vehicle in 3D and the received route alongside its observed trail.
- **Ops** shows runtime health, GCS connectivity, mission upload evidence, failure scenarios, and recordings.

## Fidelity and compatibility

Every view observes the same simulated vehicle and engine state. Document capabilities with their supported operating conditions and verification evidence. The model uses flat ground and straight-line return control. See the [simulation model](internals/simulation-model.md) and [MAVLink contract](internals/mavlink-contract.md) for the supported behavior and limitations.

The [user guides](user/README.md) start with operational walkthroughs. The [technical paper](paper/paper.md) records the original product rationale and evidence for its evaluated version.
