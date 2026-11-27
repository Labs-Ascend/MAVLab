# MAVLab SceneView 4.76.0 Android 8.0 rotation retest

Tested on 2026-11-27 with MAVLab 1.6.1 debug on the offline Android 8.0 API 26 x86_64 emulator used for 4.73.0. It used host OpenGL ES 3.0, 1,536 MiB guest RAM and two vCPUs. An ordinary app process confirmed a 16 MiB Java heap ceiling. The MAVLab package was speed-compiled on the emulator. [Heap probe](heap-probe-logcat.txt) · [Emulator fingerprint](emulator-fingerprint.txt) · [APK metadata](apk-metadata.txt)

The local candidate used base commit `c50d2a76b9965fc91cef93ae4b938931e9506444` with a one-line SceneView version bump. The packaged `drone.glb` matched the original asset by SHA-256. The installed APK matched the built APK. [Build patch](build.patch) · [Resolved dependency](sceneview-dependency.txt) · [APK SHA-256](apk-sha256.txt) · [Packaged model SHA-256](apk-asset-sha256.txt) · [SceneView #5296](https://github.com/sceneview/sceneview/pull/5296)

Each rotation trial started a new MAVLab process, opened SIM, and switched from portrait to landscape after Filament started while the drone was still absent. Capture windows use elapsed host time from the rotation command and include the ADB screenshot transfer.

## Results

SceneView retried in all three rotation trials after the same 5,794,592-byte allocation failed with 3.5–4.1 MiB free. Two retries waited for one abandoned load. The third found zero abandoned loads and retried. All three rendered the original drone in the 60-second sample, with screenshot transfers complete at 66.64, 67.57 and 67.53 seconds. The 45-second samples showed the landscape SIM HUD without the drone. All three stayed in the same process, with no fatal exception or MAVLab ANR. On 4.73.0, the same allocation failure was fatal in three of three rotation trials.

All three previous Filament backends logged the five-second teardown warning and then drained in 6.16–8.40 seconds. Each new engine started and rendered a model. [Filtered Logcat](sceneview-4.76.0-rotation-logcat.txt) · [Trial data](android-8-rotation-results.json) · [Trial 1 at 45 seconds](rotation/run-2/at-45s.png) · [Trial 1 at 60 seconds](rotation/run-2/at-60s.png)

One launch hit an Android foreground-service timeout before Filament started. It was recorded separately and repeated to obtain three rotation trials. Android System UI also showed startup dialogs before the trials. [MAVLab launch Logcat](launch-failure-run1-logcat.txt) · [ANR event](rotation/run-1/mavlab-anr.txt)

Verification covered the SIM screen and renderer lifecycle during activity recreation. Control, simulation behavior, MAVLink/QGC and recording were outside this test. Physical Android 8 hardware remains untested.

[Galaxy S10e Android 12 phone results](phone/README.md)

## Follow-up runs

The initial emulator and phone results above precede the MAVLab service startup change.

- [Implementation review and host/cache cleanup rerun](../2026-11-27-sceneview-4.76.0-review-cleanup/README.md) records the additional startup failures, three completed renderer trials, and the final cache-cleared trial.
- [Foreground-service fix and final verification](../2026-11-27-foreground-service-startup/README.md) records three new cold-start rotation trials after the application fix, plus Stop/reopen verification.
