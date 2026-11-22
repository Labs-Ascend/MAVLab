# Install MAVLab

MAVLab v1.6.1 runs its flight simulation, onboard controls, telemetry, and 3D view on Android. Android 8.0 (API 26) is the minimum install version.

1. Download the versioned **APK** from [MAVLab releases](https://github.com/Labs-Ascend/MAVLab/releases/tag/v1.6.1).
2. Open the APK on your phone. If Android asks, allow the app that opened the file to install it.
3. Open MAVLab and complete or skip the introductory tour. Check the installed version under **Ops → About MAVLab**.
4. Continue with [First flight on Android](user/first-flight.md), [Navigate MAVLab](user/navigation-and-display.md), or [Connect QGroundControl](user/connect-qgroundcontrol.md).

MAVLab v1.6.1 uses a permanent release certificate. Before replacing a differently signed install, export anything you need to retain and read the [upgrade guide](user/upgrading.md). Uninstalling erases the app's private data.

## Build from source

The Android checkout expects Java 17 and Android SDK 35. See the [Android project README](../mavlab-android/README.md) for implementation scope.

```bash
cd mavlab-android
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew lintDebug testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/MAVlab.apk
```

Use the [release runbook](operations/release.md) to build and sign distribution artifacts. For installation or first-run problems, see [Known first-run issues](known_first_run_issues.md).
