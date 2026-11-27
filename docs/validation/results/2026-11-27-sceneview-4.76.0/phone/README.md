# SceneView 4.76.0 rotation during load on Galaxy S10e

Ran 2026-11-27 on a Samsung Galaxy S10e (SM-G970U), Android 12 API 31, Adreno 640 with OpenGL ES 3.2. The phone reported its normal 256 MiB Java heap growth limit. The test used MAVLab 1.6.1 debug with SceneView 4.76.0 and the original `drone.glb`. The QA build used its own package ID and confined MAVLink to loopback with no telemetry destinations. The installed QA APK matched the built APK by SHA-256. [QA patch](phone-qa.patch) · [Resolved dependency](../sceneview-dependency.txt) · [Packaged GLB digest](apk-asset-sha256.txt) · [APK digest](apk-sha256.txt)

Each counted trial started a fresh QA app process, opened SIM and issued a portrait-to-landscape rotation after the first Filament engine started. The pre-rotation portrait captures showed the SIM HUD while the drone was still absent. The harness checked the first landscape sample at 15 seconds and checked again at 45 seconds. Capture windows include ADB screenshot transfer.

## Results

All three rotation trials stayed in the same MAVLab process with no fatal exception, out-of-memory error, model-load retry or MAVLab ANR. Each previous Filament engine drained in 540–617 ms and a replacement engine started. The drone was visible in each 15-second sample, with screenshot transfers complete at 15.94–16.03 seconds. It remained visible and SIM stayed selected in every 45-second capture. [Trial data](results.json) · [Filtered Logcat](sceneview-4.76.0-phone-logcat.txt)

The initial harness attempted to read the SIM header but could not confirm it. The counted trials checked the visible altitude, battery and RPM labels instead. One later command ended before the third trial's 45-second capture and that trial was repeated. The QA app was stopped after the test and remains installed alongside the existing MAVLab release. The phone's rotation and stay-awake settings were restored and the release package's installed path and update time matched before and after.

This test covered rotation during initial SIM rendering on one physical Android 12 phone. The low-memory retry was exercised separately on the Android 8.0 emulator. Physical Android 8 hardware remains untested. [Android 8.0 emulator report](../README.md)
