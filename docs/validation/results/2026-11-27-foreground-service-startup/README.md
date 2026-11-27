# MAVLab foreground-service startup fix

Candidate tested on 2026-11-27 on local branch `test/sceneview-4.76.0-rotation`, based at `c50d2a76b9965fc91cef93ae4b938931e9506444` with SceneView 4.76.0 and the startup fix. This is local working-tree behavior.

## Change

MainActivity.onStart and both Ops Start/Restart actions call SimulationService.startFromVisibleUi. The helper uses Context.startService, which is permitted for visible UI. SimulationService.onCreate immediately promotes itself with its ongoing notification before acquiring locks or starting AppRuntime.

Previously these visible callers used startForegroundService. On the 16 MiB Android 8.0 emulator, initial Compose layout could occupy the main thread while Android's foreground-promotion deadline expired. The change removes that deadline from visible-UI requests while retaining foreground promotion by the service. The helper is for visible UI callers. [Android background execution rules](https://developer.android.com/about/versions/oreo/background) · [Android foreground-service timeout documentation](https://developer.android.com/develop/background-work/services/fgs/troubleshooting)

The startup regression test uses an Android ContextWrapper to assert that the helper dispatches the expected service component and APP_VISIBLE action through startService. Calling startForegroundService fails the test. MainActivity and both Ops start actions use that helper.

## Build verification

The candidate passed 328 JVM unit tests, lintDebug, assembleDebug and assembleDebugAndroidTest. Lint completed with 44 warnings and zero errors. Gradle was stopped before emulator testing. [Build/check log](build-checks.txt) · [Candidate patch](candidate.patch) · [Focused Android test](SimulationServiceStartupTest.kt)

## Device conditions

The test uses the same offline Android 8.0 API 26 x86_64 AVD, host OpenGL ES 3.0, 1,536 MiB guest RAM and two vCPUs. A new ordinary-process probe verifies a 16 MiB maximum Java heap. The test installs the candidate APK and checks its on-device SHA-256 against the build. The original drone.glb is retained byte-for-byte. [APK digest](apk-sha256.txt) · [Model digest](model-sha256.txt)

Each rotation attempt force-stops MAVLab, launches MainActivity, opens SIM and rotates after the first Filament engine starts while the drone is still absent. The final result records whether the service is foreground, whether the same app process remains, whether the model rendered, and any fatal exception or MAVLab ANR. Capture windows include ADB screenshot-transfer time.


## Results

The focused Android regression test passed on the emulator. All three cold-process rotation trials completed with no fatal exception or MAVLab ANR in the captured logs. Each process survived rotation, started a replacement Filament engine and retained SIM selection. Each service dump confirmed isForeground=true with notification ID 1001. There were no discarded startup attempts in this candidate run.

All three drone models appeared in the 60-second sample, with screenshot transfers completing at 71.68, 71.09 and 68.19 seconds after the rotation command. Each 45-second capture still showed the HUD without the model. Model loading remains slow under these deliberately constrained conditions. [Capture windows and results](rotation-results.json) · [Filtered renderer Logcat](sceneview-startup-fix-logcat.txt) · [Startup regression result](startup-instrumentation.txt)

A Stop action removed the service. Home followed by reopening MainActivity restarted it in foreground state, with no fatal exception in that process. [Stopped service](service-stopped.txt) · [Reopened service](service-reopened.txt)

The owned emulator was stopped after verification. This is emulator evidence for Android 8.0. The startup change has not been retested on a physical phone.

## Impact review

- Product surfaces and runtime states covered app launch, SIM, foreground notification, rotation, explicit stop and reopening the activity.
- Ops Start/Restart use the tested helper. Their buttons were reviewed in source, while device restart used the activity path.
- Simulation control, physics, protocol and flight commands were covered by the existing JVM suite. There was no new QGC or armed-flight device exercise.
- Background retention policy and lock ownership were reviewed. Existing background-policy tests passed. Long background handoff was outside this device run.
- User guidance and the runtime-lifecycle contract were reviewed and updated. No persisted formats, simulation identity or Android minimum API changed.

Tester was Codex via ADB. QGC was absent and the emulator had no default network.
