# SceneView 4.70.0 on Android emulators

Codex ran these checks on 2026-11-23 using the local MAVLab 1.6.1 debug APK built from the SceneView 4.70.0 test branch. The app revision was `71e4c750736a181514ee9064d3a95a596a936091`. The APK SHA-256 was `59627287fe1ac5ae573a71beaed5a7c5b159224c5da53c8a08a35709ceee6327`. It declared Android API 26 and OpenGL ES 3.0 as minimum requirements.

## Emulator setup

Android Emulator 36.6.11 ran x86_64 guests with KVM and host graphics. Filament reported the Android Emulator OpenGL ES 3.0 translator on an Intel UHD Graphics 620 with Mesa 25.2.8. The disposable Android 8 images used temporary ramdisks that made userdata encryption optional to bypass lengthy first-boot encryption. The SDK system images were left intact. Final Android 8 runs used disconnected emulator networks and a disarmed simulator.

| Android version | System image |
| --- | --- |
| 8.0, API 26 | Google APIs, OSR1.180418.026 build 6741039 |
| 8.1, API 27 | AOSP, OSM1.180201.023 build 4931629 |
| 15, API 35 | Google APIs, AE3A.240806.043 build 12960925 |

## Model visibility

After leaving SIM open, all three emulators displayed the drone in the 45-second sample. Their 15-second samples showed the HUD and grid. These are sample times. The first rendered model frame could have appeared between samples. [Android 8.0 SIM](android-8.0-sim.webp) · [Android 8.1 SIM](android-8.1-sim.webp) · [Android 15 SIM](android-15-sim.webp)

## Android 8 results

| Scenario | Android 8.0 | Android 8.1 |
| --- | --- | --- |
| 20 rapid SIM → Ops cycles | 20 starts and 20 engine teardowns in 88.5 seconds of input. Two five-second pending warnings. Longest reported drain 7,284 ms. | 20 starts and 20 engine teardowns in 71.0 seconds of input. Zero five-second pending warnings in this run. Longest reported drain 4,695 ms. |
| Rotate just after an OpenGL engine starts | Engine shut down. Landscape displayed Control with SIM highlighted. | Engine shut down. Landscape displayed Control with SIM highlighted. [Screenshot](android-8.1-rotation.webp) |
| Wake after one minute with screen off | The drone was visible before Home. About 50 seconds after wake and reopening MAVLab, the same app process showed Ops with SIM highlighted. | The drone was visible before Home. About 50 seconds after wake and reopening MAVLab, the same app process showed Ops with SIM highlighted. |

Each rapid-cycle run stayed in one app process. Neither reported an engine creation error, MAVLab crash, or app ANR. The taps used a nominal 0.7-second pause on SIM and 0.25 seconds on Ops. Actual input timing included ADB and emulator overhead. Additional pending warnings appeared in earlier runs with multiple emulators active, including on Android 8.1. Configurations and app workload differed between those runs, so the timing differences do not isolate the effect of host contention.

The five-second message matches the warning described by [SceneView #4969](https://github.com/sceneview/sceneview/issues/4969). The engine creation error described in [SceneView #5202](https://github.com/sceneview/sceneview/issues/5202) did not occur in these rapid runs.

## Android 15 results

Twenty rapid input pairs produced three OpenGL engine starts, and all three shut down. One five-second teardown warning appeared in that part of the run. Rotating after the engine started left the SIM HUD visible in landscape. The drone was visible before Home and again at the 45-second sample after returning from a 60-second screen-off interval. The app stayed in one process. Across the complete run, all eight engines shut down and Logcat recorded four pending warnings. The captured app logs show no MAVLab ANR, fatal exception, or engine creation error. The emulator's System UI showed an ANR dialog during initial startup, before the completed checks.

## Evidence and limits

[Filtered Logcat](renderer-logcat.txt) preserves renderer lifecycle events from the rapid and follow-up runs. Engine pointer addresses are redacted. These checks cover rendering and SIM navigation on emulator images. Real Android 8 GPU drivers remain untested. The cause of the Android 8 tab mismatch and whether it also occurs with the previous SceneView version remain open. Flight actions and QGroundControl were outside this run.
