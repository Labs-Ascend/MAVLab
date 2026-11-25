# SceneView 4.73.0 rotation during load on Galaxy S10e

Ran 2026-11-25 on a Samsung Galaxy S10e (SM-G970U), Android 12 API 31, Adreno 640 with OpenGL ES 3.2. The device reported its usual 256 MiB heap growth limit. The test APK used MAVLab 1.6.1 debug from commit `1c552346afe2aaccbe2f7d502ab4ff165a383393` with a [QA-only patch](phone-qa.patch) for its separate package and loopback-only MAVLink. The developer APK SHA-256 was `d8b363416502204949a4591983542eb3ddb2b6b5ab7132df02737520fb046a87`. The original GLB in the APK had SHA-256 `266e3cc6d2165db959df1fa30dad2d8c57bf07fbb952542364cd4cd87a27b3b2`.

Each of three trials started a fresh app process, opened SIM and issued a portrait-to-landscape rotation after Filament created the first engine. The pre-rotation captures showed the HUD before the model became visible. Each old engine drained in 436–464 ms and a replacement engine started. The drone was visible in the first landscape sample at 15 seconds and in each 45-second capture. SIM stayed selected, and each app process survived with no fatal exception, reported out-of-memory error or MAVLab ANR in the captured logs. ADB screenshot transfer is included in the recorded capture windows.

[Capture windows](results.json) · [Filtered Logcat](sceneview-4.73.0-phone-logcat.txt)
