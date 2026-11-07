# Ops

Ops answers what changed, what is affected and how to recover. The health summary shows current runtime, authority, armed state, mode and recording state. Active issues appear before diagnostics, with severity, affected system, first-observed time and an implemented corrective action. No peer detected is a normal offline state.

## Health and recovery

Injected faults remain separate from actual battery and GCS heartbeat state. Resetting failures restores injected settings; it does not recharge the battery, stop flight or reconnect QGC. A GPS incident clears after observed GPS fix returns, not merely after tapping Reset. Battery recharge is available while disarmed. Flight recovery opens Control, where landing and authority actions use the simulation engine.

Incident times describe when this app process first observed the problem. The state-transition history is sampled at 5 Hz and bounded to 200 entries. Filter by severity, component, changed/active/recovered state or the last five minutes. History survives tab switching and rotation, but starts again after a cold process restart. Saved recordings hold a separate timeline; this recent history is not a permanent audit log.

## Peers and mission evidence

The indicator beside **Ops** shows a green dot and **GCS connected** after a continuous validated GCS heartbeat stream has warmed up (three seconds). It shows a red dot and **GCS disconnected** before validation, after heartbeats expire (15 seconds without a heartbeat, checked once per second), or when MAVLink stops. Red describes the link only; offline simulation is still normal and available. Packet receipt alone does not turn it green. The text label also identifies the state without relying on colour.

Expand **Peers & identity** for vehicle/GCS system and component IDs, packet/heartbeat age, validation, conflicts, transport status, latest inbound message and command ACK. A validated heartbeat does not grant control authority or prove that outgoing telemetry arrived at QGC.

**Mission upload** appears for a stored mission or a non-idle upload. It shows exact phase, expected/received counts, zero-based requested/validated sequences, rejection, stored source and partial behavior. Accepted upload is separate from AUTO execution. Restoration generates no new upload ACK. A rejected replacement retains the prior loaded mission; inspect the reason, correct it in QGC and re-upload. Unverified saved source requires re-upload before route display/local Start AUTO, or clear while disarmed in Control.

## Flight boundaries and rally

**Flight boundaries & rally** reports separate fence/rally uploads, enabled/breached state, configured response and return destination. Enable while disarmed; disable can release recovery while armed without cancelling the current RTL/LAND mode. Clear requires disarmed state and disables fencing without deleting the navigation mission. See [simulated flight boundaries](flight-boundaries.md) for configuration and reverse paths.

## Failure exercises

Expand **Failure scenarios**, choose an exercise and confirm injection after reading the expected observation. **Learn why** exposes cause, telemetry signature, response and lesson. Reset a scenario explicitly, or confirm **Reset all failures**. The active issue area remains visible independently of whether the catalog is expanded. Recharging leaves accelerated drain active until that scenario is reset.

## Recordings and review

**Recording is off by default**, including AUTO missions. In Ops, use **Begin recording** for a session you want to capture, or enable **Record flights and missions on arm** while disarmed. This opt-in setting is saved across app restarts and applies to both local flights and received missions. Beginning one recording does not enable automatic capture. Existing saved recordings are retained.

**Stop and save** closes the files and creates a local session report without landing, disarming or stopping simulation. If automatic capture is enabled, it stays paused after a deliberate stop for the rest of that armed flight; Begin resumes it, and the next arm can start a new recording. With automatic capture off, arming, takeoff and Start AUTO never begin a recording by themselves.

Recording has its own active indicator and elapsed time. Saved sessions offer **Review session** and **Export session**. Review includes duration, sampled horizontal path, modes, authority, altitude, battery, mission outcome and recorded events. This is observed evidence, not a safety assessment or planned-route distance. Export opens Android's chooser for the session files. Unfinished prior sessions remain available for export. Very long reports show a bounded preview; export contains the full report.

## Runtime and setup

**Display** contains the remembered **Full screen** preference. It hides Android system bars while retaining MAVLab's compact four-tab navigation. Standard display is the initial default. See [Navigation and display](navigation-and-display.md).

**Runtime components** separates engine freshness, phone monitoring, audio and recording. Phone sensors remain globally eligible for foreground local sensor control regardless of selected tab. Mission authority and background sensor suspension remain distinct states.

Confirmed **Stop simulation** stops physics/networking and saves the recording. It freezes vehicle state; it does not land or disarm. **Start simulation** resumes the same process state. Background handoff behavior remains unchanged.

**Advanced configuration** contains QGC setup and the simulated GPS origin. Changing origin requires confirmation and disarmed state, clears stored navigation/fence/rally data and disables fencing. **About MAVLab** contains the Ascend Labs identity, installed version and onboarding replay.

## Virtual map camera (working tree)

Ops exposes the offline virtual camera, map-origin setup, a test photo and latest-session ZIP export. See [virtual camera](virtual-camera.md) for the supported local workflow and current integration limits. Camera activation does not enable automatic flight recording.
