# Control

Phone Sensors is the default local input mode. While MAVLab is visible, calibrated phone tilt and rotation feed the simulated vehicle through the shared engine, including when Cockpit or SIM is selected.

1. Select Phone Sensors and calibrate with the phone in a comfortable neutral position.
2. Arm the simulated vehicle. A fresh local sensor flight restores manual control and clears old motor-RPM overrides.
3. Use Takeoff to climb to the selected height. Local takeoff uses altitude hold, so phone tilt keeps steering roll and pitch and phone rotation keeps commanding yaw.
4. View SIM to observe the same vehicle attitude, position, and motor state shown in telemetry. Changing surfaces does not interrupt sensor control.
5. Land to return to the ground, then Disarm to stop the motors.

Arming and takeoff are separate actions. On the ground, accepted attitude input changes motor commands; physical ground contact constrains vehicle movement. At centered throttle, altitude hold maintains the target height. Raising or lowering throttle adjusts the height target.

An active GCS mission owns autonomous flight intent. Custom Input and Direct RPM are explicit alternative input modes. Backgrounding MAVLab suspends phone-sensor control. These control states remain distinct from ordinary foreground Phone Sensors operation.

## Control layout

The header keeps authority, flight mode, armed state, selected input, battery, GPS and QGC peer status visible. A peer connection is separate from control authority. Critical warnings remain in the header when you scroll.

**Fence recovery** is automatic engine authority, not a GCS takeover. Motor/manual manipulation and mode changes pause while its latch is active; Land and Disarm remain available. Disable the fence in Ops and deliberately choose a manual mode to resume steering. RTL returns to the selected eligible rally/home point, lands and disarms. See [flight boundaries](flight-boundaries.md).

The flight-action dock remains reachable below the controls. It offers Arm while disarmed, Takeoff with the chosen height while armed on the ground, Land during flight, and Disarm after touchdown. Disarm remains reachable during flight but requires confirmation because it immediately cuts motor thrust. Landing and disarming remain separate actions. Landscape places the dock beside the scrolling controls at ordinary text sizes; larger text or short viewports use the bottom action row so neither action is covered by navigation. Status fields wrap as whole items instead of squeezing the vehicle state into a narrow fixed column.

Input-mode selection and neutral calibration are locked while armed. Disarm before changing Phone sensors, Custom input or Direct RPM. The phone display is a calibrated input preview; SIM remains a view of the engine's physical vehicle state. `INPUT ACCEPTED` denotes local manual intent, while `PREVIEW`, `INPUT PAUSED` and `INPUT UNAVAILABLE` explain other states. Stale or unavailable simulation state disables commands and provides Open Ops.

## Setup and advanced modes

- Calibrate is available in the grounded action dock and in **Calibration & control setup**. Hold the phone comfortably and still; a confirmation says the neutral position was saved. This is the existing immediate calibration, not a new timed sampling procedure.
- Custom input uses throttle from 0–100% and normalized roll/pitch/yaw from −100% to +100%. Center inputs restores neutral attitude and 50% throttle. Inputs are applied by AppRuntime, including when another surface is selected.
- Choose a takeoff height of 5, 10 or 20 metres above ground before arming. The default remains 10 metres.
- Phone sensitivity remains the established runtime mapping: 30° roll/pitch, 45° yaw and 3° dead zone. It is described in setup; this update does not add an adjustable mapping.
- Direct RPM requires explicit confirmation while disarmed. It starts at 0 RPM, bypasses the autopilot and commands the same target to all four simulated motors. After arming, adjust the 0–10,000 RPM target and observe actual motor telemetry. **Stop motors** disarms and restores Phone sensors. Changing surfaces retains the command. GCS mission/direct authority suspends the RPM override; a local Land command clears it before landing.
- Flight-mode settings, control explanations, failure injection and sound experiments are below the main input area. Loiter, RTL and Land retain their navigation semantics; manual attitude controls are shown as limited in those modes.

An active GCS mission blocks local input and mode selection. **Take control & land** requires confirmation, then uses the existing local engine landing command. Direct GCS commands retain existing behavior: subsequent phone input may reclaim Controller authority. The UI reports observed authority and does not promise a persistent GCS-direct lock. Screen-swiping is available on the passive operational header and phone-tilt preview; dragging a Control slider cannot switch surfaces. See [Navigation and display](navigation-and-display.md).

## Received missions

A collapsed mission row appears only while receiving, loaded, active, completed or blocked. Expand it for source, current/next item, completed count and percentage, remaining horizontal route distance, valid actions, and View route in SIM. A horizontal ETA estimate appears only during moving AUTO flight; it uses current ground speed and excludes climb/descent time.

**Start AUTO** requires received source, live simulation state, a grounded vehicle, GPS 3D fix, healthy motors and battery reserve, and no active upload or injected lost link. Confirmation explains that it arms the simulated vehicle and gives GCS Mission authority. It continues from the current item; it does not reset a completed mission. **Clear mission** is offered only while disarmed with live state and no upload, and requires confirmation. Both actions recheck state when submitted.

During GCS Mission control, the local input area is replaced by a visible paused state. **Take control & land** remains the supported way to leave autonomous flight. This is a landing takeover, not an implemented mission pause/resume operation. See [Mission operation](mission.md).

While disarmed under GCS Mission authority, **Take local control** is a confirmed return to manual STABILIZE through the existing engine mode command. Clearing route data does not silently change control authority; the action remains available even after the mission row disappears.

Unsupported custom flight-mode requests from a GCS are rejected with the current mode and authority retained. They do not silently switch the simulated vehicle to Stabilize.
