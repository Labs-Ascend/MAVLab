# MAVLab Teacher Guide

This archived guide records the earlier classroom framing. For current instructions see [First flight on Android](../user/first-flight.md), [Navigate MAVLab](../user/navigation-and-display.md), and [Connect QGroundControl](../user/connect-qgroundcontrol.md).

MAVLab is designed for classroom drone systems lessons without physical aircraft and without making students begin with the hardest simulation stack. The teaching purpose is to let learners understand drone behaviour first, then later graduate into ROS, Gazebo, ArduPilot/PX4 SITL, Webots, JSBSim, hardware-in-the-loop, and real aircraft.

## Teaching Philosophy

Professional tools are powerful, but they can bury beginners under installation, networking, simulator, and autopilot details before the drone concepts are clear. MAVLab should give instructors a friendlier first layer:

1. Show the concept in MAVLab.
2. Let students operate it from a phone.
3. Connect the concept to QGroundControl/MAVLink.
4. Only then explain how the same concept appears in ArduPilot SITL, PX4 SITL, ROS, Gazebo, or real aircraft.

## Recommended Lesson Order

1. Your First Flight
2. Understanding MAVLink
3. Phone as Controller
4. Flight Modes Explained
5. PID Control
6. Sensors
7. Failsafes

## Classroom Format

- Students run MAVLab on their phones.
- QGroundControl can run on the same phone by switching to it within MAVLab's one-minute handoff window, or on an instructor desktop over Wi-Fi. Split-screen is optional; a connected GCS keeps telemetry active in the background.
- Start each session with the problem MAVLab solves: drone simulation usually requires a steep toolchain, but the concepts can be learned first in one friendly app.
- Use Ops failure exercises only after students understand normal flight. Lessons retained in the codebase are not a visible Lessons destination in the v1.6.0 candidate.

## Assessment Ideas

- Explain the difference between Stabilize, Alt Hold, Guided, Loiter, Auto, and RTL.
- Identify which telemetry messages QGC uses to show attitude, position, GPS, and battery.
- Demonstrate GPS loss and explain why Auto/Loiter degrade.
- Author/upload a small mission in QGC, follow progression in Cockpit/Control/SIM, and inspect upload evidence in Ops. There is no built-in demo route in current navigation.
- Compare disabled fencing, report-only breaches and engine RTL/LAND recovery. Identify who owns intent and the deliberate reverse path; do not frame straight-line simulated returns as obstacle avoidance.
- Begin recording explicitly in Ops, stop/save, and distinguish observed flight history from a planned route. Automatic capture requires opt-in.
- Compare behavior with and without wind.

## Safety Framing

MAVLab is a simulator, but its lessons should reinforce real-world safety: never arm near people, validate GPS before autonomous flight, and understand failsafes before relying on them.
