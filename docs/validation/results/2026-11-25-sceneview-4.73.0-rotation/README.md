# MAVLab SceneView 4.73.0 Android 8 rotation retest

Ran on 2026-11-25 on the same offline Android 8.0 API 26 x86_64 emulator used for the 4.72.0 comparison. This run used host OpenGL ES 3.0, 1,536 MiB guest RAM and two vCPUs. After a reboot measured 384 MiB by default, the emulator's heap growth limit was set to 16 MiB and independently verified in an ordinary app process. The debug APK used ART speed compilation. [Heap probe](heap-probe-logcat.txt) · [Resolved SceneView version](sceneview-dependency.txt)

The candidate used MAVLab 1.6.1 debug from commit `1c552346afe2aaccbe2f7d502ab4ff165a383393`. APK SHA-256 `07c74769086ca8767ab1867635ca40159eb43adcce6164c11791cd7ef589e3c0`. Packaged `drone.glb` was byte-identical to the previous asset, SHA-256 `266e3cc6d2165db959df1fa30dad2d8c57bf07fbb952542364cd4cd87a27b3b2`.

## Results

- **Rotate while loading.** Three cold-process trials rotated after the first Filament engine started and while the pre-rotation screenshot still showed only the HUD. Every trial started a replacement engine and crashed in FileLoader.readFully before its 45-second capture. ART reported an attempted allocation of 5,794,592 bytes with 3.5–4.8 MiB free. Each old backend finished draining in 5.55–8.84 seconds. [Results and capture windows](android-8-rotation-results.json)
- **Hold portrait through loading.** One cold-process run kept SIM open at the same heap limit. The 45-second sample showed the HUD. The 60-second sample showed the drone, and its screenshot transfer completed at 72.81 seconds after the initial SIM capture. [HUD](android-8-steady-hud.png) · [Model](android-8-steady-model.png)

An emulator System UI dialog blocked a preliminary launch before SIM opened. It was dismissed before the three counted trials. Screenshot windows include ADB transfer time. These checks cover the loading and rotation cases under emulator conditions. Physical Android 8 hardware remains untested.

[Filtered Logcat for Thomas](sceneview-4.73.0-rotation-logcat.txt) · [SceneView upstream change](https://github.com/sceneview/sceneview/pull/5271)

[Galaxy S10e Android 12 phone results](phone/README.md)
