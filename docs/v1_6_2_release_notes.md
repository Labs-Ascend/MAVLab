# MAVLab v1.6.2 Release Notes

Status: Release candidate
Android version code: 25.

MAVLab v1.6.2 improves SIM rendering and application startup. Android 8.0
(API 26) and OpenGL ES 3.0 remain the minimum requirements.

## Changes

- Upgrade SceneView from 4.16.10 to 4.76.0, including recovery when a model-file
  allocation runs out of Java heap during rotation.
- Restore the selected SIM tab and pager together after rotation and activity
  recreation.
- Start the simulation service from visible UI without a foreground-promotion
  deadline during initial layout. The service still promotes itself with an
  ongoing notification before starting the simulation runtime.
- Update the operator documentation with navigation, screenshots and QGroundControl
  connection guidance.

## Upgrade and distribution

The release APK and AAB use the permanent MAVLab signing identity introduced in
v1.6.1. The signed APK can update a matching v1.6.1 installation while preserving
app data. Older debug-signed installations require the migration described in
[upgrade guidance](user/upgrading.md). Save recordings and land/disarm the simulated
vehicle before installing. The AAB is an app-store upload artifact.

## Validation and limits

Pre-release debug checks passed 328 JVM tests, the focused Android startup test,
and lint with 44 warnings. Three Android 8.0 emulator cold-start rotation trials
at a verified 16 MiB heap rendered the original model without fatal exceptions
or MAVLab ANRs after the service startup fix.
[Emulator evidence](validation/results/2026-11-27-foreground-service-startup/README.md).

Three earlier SceneView 4.76.0 trials on a Galaxy S10e running Android 12 rendered
the model. Those runs preceded the service startup fix and used a separate QA app.
[Earlier phone evidence](validation/results/2026-11-27-sceneview-4.76.0/phone/README.md).

Additional signed-candidate phone testing was omitted at the maintainer's request.
The phone's installed v1.6.1 certificate matches the permanent release identity.
Existing debug and emulator results are retained at their original scope.
Physical Android 8 GPU drivers remain untested. Model loading was slow at 16 MiB
and the five-second backend teardown warning occurred before teardown completed.
Signing does not establish Play Console acceptance.

[Signed release preparation](validation/results/v1.6.2-2026-11-27-preparation/README.md).
