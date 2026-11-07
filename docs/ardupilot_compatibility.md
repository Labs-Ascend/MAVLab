# ArduPilot Compatibility Contract

MAVLab is an independent simulator that implements an ArduPilot-compatible
MAVLink surface. It is not an official ArduPilot firmware build and must never
advertise itself as one.

## Advertised profile

The active profile is defined in
`core/mavlink/ArduPilotCompatibilityProfile.kt` and is the only source of truth
for `AUTOPILOT_VERSION` and MAVLink capability flags.

- Profile: `ArduCopter 4.6 protocol profile`
- Compatibility baseline: `4.6.3`
- Firmware type: `DEVELOPMENT` (`0`), never `OFFICIAL` (`255`)
- Custom version identifier: `MAVLAB`
- Middleware, operating-system, and board versions: unknown/not represented
  (`0` on the wire)

The compatibility version is deliberately pinned. A newer stable ArduPilot
release does not automatically change this value.

## Capability policy

MAVLab advertises only protocol paths that have an implementation and tests:

- float and integer mission items
- float parameters
- `COMMAND_INT`
- MAVLink 2 parsing/communication

Capability bits for `SET_POSITION_TARGET_LOCAL_NED` and
`SET_POSITION_TARGET_GLOBAL_INT` remain disabled until their message handlers
and acceptance tests exist.

## Upgrade procedure

When ArduPilot publishes a new stable release:

1. Keep the current MAVLab profile and development firmware type. QGC updates
   must not force a version bump.
2. Compare the new ArduCopter MAVLink behavior, parameters, modes, commands,
   mission semantics, and capability flags against the pinned profile.
3. Run the Android unit suite and the QGC acceptance checklist against both the
   currently supported QGC build and the candidate newer build.
4. Add or update protocol contract tests for every intentional behavior change.
5. Update the compatibility baseline only after all checks pass and document
   the change in release notes.

Never silence a QGC warning by claiming the latest official ArduPilot version.
The development firmware type truthfully identifies MAVLab as a compatible
implementation and prevents QGC's official-firmware update warning from being
applied to the simulator.

## Required release gate

Every release that changes MAVLink behavior must verify:

- decoded `AUTOPILOT_VERSION` version bytes and development type
- `MAVLAB` custom-version identifier
- exact advertised capability mask
- parameter list/read/write behavior
- mission upload/download/current-item behavior
- command acknowledgement and unsupported-command behavior
- connection, reconnect, and telemetry behavior in QGroundControl

## 1.6.1-dev survey subset

Navigation mission uploads now admit at most 1,000 items and implement distance
trigger command 206 with real offline-map JPEG capture, readiness gates and BRAKE
(17) hold/resume. This is a bounded subset, not all MAVLink or ArduPilot parity.
See the [canonical contract](internals/mavlink-contract.md#distance-triggered-survey-subset-161-dev)
and [user workflow](user/virtual-camera.md). Mission 1 is the exact 831-item
upload regression input; live QGC flight validation is tracked separately.
