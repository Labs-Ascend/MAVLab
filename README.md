# MAVLab by Ascend Labs

<p align="center">
  <img src="assets/Screenshot_20260620_184419.jpg" alt="MAVLab Android drone simulator interface" width="300">
</p>

MAVLab makes drone simulation approachable. It is an Android-first drone systems simulation and digital-twin platform for learners who want to understand drones without first fighting the steep learning curve of ROS, Gazebo, ArduPilot/PX4 SITL, Docker, MAVProxy, Linux networking, and multi-window simulator setup.

Built for classroom training, hands-on bootcamps, and flight operators, MAVLab operates completely offline on a phone. It helps students learn autopilot structures, physics, telemetry protocols (MAVLink), control mechanisms, failure conditions, and autonomous missions through a friendly app before they graduate into the full professional toolchain.

Active implementation codebase: [mavlab-android/](file:///home/ambrose/Downloads/Ascend/Drone%20SIM/mavlab-android/).

---

## Features in v1.5

- **High-Fidelity Physics & Autopilot:** Fixed-rate quadcopter physics model and realistic PID attitude/altitude autopilot controls.
- **Diagnostics Dashboard (Cockpit):** Live telemetry panels and rolling real-time graphs charting altitude, roll, pitch, and yaw.
- **Multi-Mode Control Surface:** Interactive dual joysticks or phone accelerometer tilt control.
- **3D Digital Twin View (SIM):** Live 3D rendering of the drone's position, attitude, and propeller speeds utilizing a bundled GLB model.
- **Waypoint Mission Execution:** Full autonomous mission upload, track plotting, active waypoint navigation, and Guided waypoint offsets.
- **MAVLink UDP Protocol Server:** Interoperable with QGroundControl, supporting heartbeats, position/attitude streams, parameter downloads, and mission synchronization.
- **Failure Labs Scenarios:** Test Operator recovery under failure scenarios:
  - GPS Loss, GPS Drift, Compass Loss, and Barometer offset failures.
  - Wind speed, direction, and gust simulation.
  - Individual motor failure.
  - Unsafe battery drain and heavy payload masses.
  - Signal / Link loss scenarios.
- **Session-Based Flight Logging:** Automatically records flight parameters to CSV and events/timelines to JSONL.
- **Markdown Flight Reports:** Generates human-readable flight debrief reports (`report.md`) containing maximum altitude, battery consumption, failure event timelines, and safety observations.
- **Logs Sharesheet Export:** Share and export flight files directly via standard Android Sharesheet intents.
- **First-Launch Onboarding:** Educational onboarding explaining MAVLab features and digital twin concepts.

---

## Quick Start

### Build & Deploy

To compile the app, run the tests, and build the debug package:
```bash
cd mavlab-android
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew lintDebug testDebugUnitTest assembleDebug
```
To install the build to your connected device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Connecting QGroundControl (QGC)

MAVLab integrates directly with QGroundControl:
1. **Split-Screen (On-Device):** Open MAVLab and QGC side-by-side. QGC will automatically detect the vehicle on `127.0.0.1:14550`.
2. **Local Wi-Fi (Cross-Device):** Connect your computer and phone to the same local Wi-Fi router. Open MAVLab on the phone and QGC on your computer; the phone's UDP broadcast will automatically link the vehicle.
3. *Note: Ensure your GCS System ID is set to `255` (default) and MAVLab is set to `1` to prevent system conflicts.*

---

## Project Documentation

- **[Product Purpose](docs/product_purpose.md):** Why MAVLab exists: lowering the drone-simulation learning curve before ROS/Gazebo/SITL.
- **[Vault Documentation Sync Map](docs/vault_documentation_sync_map.md):** Which Obsidian vault docs should drive GitHub documentation updates.
- **[Architecture & Design Model](docs/architecture.md):** In-depth look at state flows, control authorities, and logging lifecycle.
- **[Protocol Invariants & Guardrails](docs/protocol_guardrails.md):** Guidelines for GCS connections and packet rules.
- **[7-Minute Demonstration Script](docs/v1_5_demo_script.md):** Narration script for live bootcamps and presentations.
- **[QGC Integration Acceptance Tests](docs/v1_5_qgc_acceptance.md):** Environment setup and check-lists for QGC validation.
- **[QA Test Matrix](docs/test_matrix.md):** Complete checklist for manual app validation.
- **[v1.5 Release Notes](docs/v1_5_release_notes.md):** Summary of what is new in this release.
- **[Setup Guide](docs/setup_guide.md)**
- **[Teacher Guide](docs/teacher_guide.md)**

---

## License

MIT. See [LICENSE](LICENSE).
