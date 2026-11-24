# MAVLab rotation during SIM loading

Ran on 2026-11-24 using MAVLab 1.6.1 debug from commit `cf409aedb2a1ab549c9cdf793aadcd12f261f242` with the pager fix at `0898544626a08c76a1e51daf7f69b7f2919e5a2e`. The candidate used SceneView 4.72.0 and declared minSdk 26. APK SHA-256 `d6dac367017e7096423c7af091686f082b8941dcc4c6a288697c7f935a7dd9e0`.

The offline Android 8.0 API 26 emulator used Google APIs build OSR1.180418.026, x86_64, host OpenGL ES 3.0, 1,536 MiB guest RAM and two vCPUs. Each trial restarted MAVLab, opened SIM and rotated to landscape after Filament created the first engine and before the model was visible. Capture windows are measured from the rotation command and include screenshot transfer time.

- **4.72.0 with the original heap configuration, three trials.** ART reported a 16 MiB growth limit. The old backend drained after 7.270–9.923 seconds. Another engine started and the app hit an OutOfMemoryError before the 45-second capture in each trial. Two traces point to FileLoader.readFully and one to GltfMaterialCompatibility.prepare.
- **4.16.10 on the same configuration, one trial.** A capture completed at 55.32 seconds showing the model with SIM selected. The scheduled 45-second sample ran late and completed at 92.73 seconds. [First visible capture](baseline-4.16.10-model.png)
- **4.72.0 with a configured 192 MiB heap growth limit, one diagnostic trial.** The same candidate APK was installed with ART verify optimization after restarting Android's runtime. SIM stayed selected with a HUD-only capture at 45.00–52.92 seconds and a visible model at 60.00–70.23 seconds. [HUD capture](4.72.0-higher-heap-hud.png) · [Model capture](4.72.0-higher-heap-model.png)

[Filtered Logcat for Thomas](sceneview-4.72.0-rotation-logcat.txt) includes the Sceneview teardown tags, engine starts and exception stacks. The diagnostic changed the emulator heap and install optimization, which limits timing comparisons. Physical Android 8 hardware remains untested.
