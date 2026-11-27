# SceneView 4.76.0 implementation review and emulator rerun

Reviewed and tested on 2026-11-27. The candidate is local branch `test/sceneview-4.76.0-rotation`, base `c50d2a76b9965fc91cef93ae4b938931e9506444`, plus the SceneView version change from 4.73.0 to 4.76.0.

## Code review

The review covered Drone3DScreen and DroneModelController, MavLabAppShell, ScreenSwipeMotion and ScreenSwipePager, MainActivity and SimulationService, the version catalog, merged manifest, and the published SceneView 4.76.0 source.

- DroneScene uses rememberEngine, rememberModelLoader and rememberModelInstance. The model load follows the loader lifetime. The SDK cancels the load on disposal and disposes the model and engine through its lifecycle helpers.
- SceneView 4.76.0 checks cancellation while reading the asset and after preparing it. A failed large allocation waits for abandoned loads for up to 30 seconds and retries once. A second allocation failure returns a logged null model.
- The SDK rewrites compatible loader-owned GLB JSON in place when it fits, avoiding the additional whole-file copy.
- The restored selected tab initializes the pager. Renderer startup waits for arrival at SIM, while a fully departed renderer is disposed. DroneState remains the source of model pose.
- The merged application manifest requires Android API 26 and OpenGL ES 3.0. The original drone asset is byte-identical to the earlier candidate.
- The local emulator setup script had a readiness bug. Matching the word “found” also accepted “not found”. The rerun checks an actual connectivity dump before continuing.

## Open findings

1. Three preliminary cold launches failed before Filament started, and a later launch failed after its first engine started with Context.startForegroundService() did not then call Service.startForeground(). The main-thread ANR snapshot was measuring Compose layout. MainActivity starts a foreground service in onStart, and the service promotes itself in onCreate on that same main thread. Heavy initial layout can consume the deadline before promotion runs. This is a MAVLab startup problem requiring further targeted verification. The trace establishes the failure and layout activity, while the scheduling explanation is an inference. [Captured startup trace](startup-anr.txt) · [First launch exception](rotation/run-1/sceneview-logcat.txt)
2. The current SIM only adds ModelNode when the instance is available. A slow or failed model load leaves the HUD visible without a loading or failure status. SceneView reports the cause in Logcat. A visible status/retry remains a product improvement.

Application behavior was reviewed without changing the startup or SIM presentation code. The updated setup check is confined to the local test script.

## Automated checks

328 unit tests across 52 suites passed with zero failures or errors. lintDebug and assembleDebug completed successfully. Lint reported 44 warnings and zero errors. [Combined build/check log](build-tests-lint.txt) · [Test counts](unit-test-summary.json) · [Lint counts](lint-summary.json)

## Emulator conditions

The rerun uses the same offline Android 8.0 API 26 x86_64 AVD, host OpenGL ES 3.0, 1,536 MiB guest RAM and two vCPUs. A fresh ordinary-app probe confirmed maxMemoryBytes=16777216 and memoryClassMiB=16 after restarting zygote. The installed application SHA-256 matches the previous 4.76.0 candidate. [Heap probe](heap-probe-logcat.txt) · [Installed APK digest](installed-apk-sha256.txt)

The initial three launches never reached a Filament engine and are preserved as startup failures. Rotation results below count only attempts whose pre-rotation portrait screenshot shows SIM before the drone appears. ADB capture windows include screenshot transfer and are sampling intervals, rather than exact model load times.

## Rerun results

Three completed rotation trials started replacement engines, retained the same app process and rendered the original drone. Their first model-positive captures were the 90-, 75- and 60-second samples respectively. Capture transfers completed at 102.88, 82.73 and 67.26 seconds. Each 45-second screenshot showed the SIM HUD without the drone. No fatal exception or MAVLab ANR was captured during these three completed trials. [Results with capture windows](completed-rotation-results.json) · [Combined renderer Logcat](sceneview-4.76.0-review-logcat.txt)

The first and final completed trials logged ModelLoader's allocation-recovery retry. In the second completed trial ART logged a failed 5,794,592-byte allocation without a ModelLoader retry warning, and the replacement model still rendered. The allocation event is included in the combined trace.

The initial three launches failed before rendering with a foreground-service timeout. After an emulator restart, another launch started one engine and then failed with the same foreground-service exception. Those failures are retained separately and excluded from the three completed renderer trials. One attempt was also interrupted when the emulator host process shut down. Successful renderer recovery therefore does not establish reliable cold-start behavior.

## Host cleanup

At the user's request the completed Gradle daemon and nonessential user applications were stopped. Available RAM increased from 6,448 MiB at the cleanup snapshot to over 10,400 MiB. T3 Code, its helper processes, the emulator and supporting desktop services continued running. MAVLab's emulator cache and code_cache directories were cleared before the final completed trial. Both directories were already empty apart from filesystem metadata. App data and the original model asset were retained. The final trial used the same verified 16 MiB heap ceiling. [Memory before](memory-before-cleanup.txt) · [Memory after](memory-after-cleanup.txt) · [Cache after](cache-after.txt)

The simulator rendering, navigation restoration, activity recreation and foreground-service startup paths were reviewed. Control, missions, physics, MAVLink/QGC and reverse control flows were covered only by the existing JVM tests, with no new protocol or physical-flight exercise. This remains emulator evidence for Android 8.0. Physical Android 8 hardware is untested.

The owned test emulator was stopped after verification to release host memory.

[Subsequent foreground-service startup fix and verification](../2026-11-27-foreground-service-startup/README.md)
