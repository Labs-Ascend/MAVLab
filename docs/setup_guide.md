# MAVLab Setup Guide

This guide describes v1.6.0. Earlier v1.5.3 installations retain the previous layout; read the upgrade guide before changing installations because signing compatibility is not guaranteed.

MAVLab's core learning loop is designed to run from one Android-first app. You do not need ROS, Gazebo, ArduPilot SITL, PX4 SITL, Docker, Python bridges, cloud servers, or Linux networking knowledge to start learning drone simulation concepts.

## Install

1. Install MAVLab on an Android 8.0+ phone.
2. Install QGroundControl if you want ground-station integration.
3. Open MAVLab and complete onboarding.
4. Use the guided surfaces to learn the basics: Cockpit, Control, SIM, and Ops.

The final **Start flying** button opens Cockpit; it does not arm or begin recording. In Control calibrate, Arm, Takeoff, Land and then Disarm. Recording is off by default; begin explicitly in Ops or opt into recording on arm while disarmed. See [upgrade guidance](user/upgrading.md).

## QGroundControl

For same-device use, open MAVLab and then switch to QGroundControl within one minute. QGC connects over `127.0.0.1:14550`. MAVLab continues telemetry while a validated GCS heartbeat is present or a flight is active. When backgrounded, it suspends phone-sensor input while retaining motor audio for QGC-controlled arming and flight; if it is idle and disconnected after the handoff window, it stops the runtime, audio, and power locks automatically. Split-screen is optional.

For desktop QGC, put the Android phone and desktop on the same Wi-Fi network. MAVLab broadcasts to common LAN destinations and UDP port `14550`.

## Build From Source

```bash
cd mavlab-android
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew lintDebug testDebugUnitTest assembleDebug
```

Install the debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

- If QGC does not connect, restart QGC and MAVLab, then confirm both apps are on the same network.
- If the phone controller is unavailable, use manual fallback sliders in the Control tab.
- If the 3D view is unavailable, return to Cockpit/Control and inspect Ops. Do not assume a visual failure has stopped the engine; check authority and land/disarm deliberately.
