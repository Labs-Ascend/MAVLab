# MAVLab v1.6.2 release preparation

Prepared on 2026-11-27. Status is release candidate. The tested SceneView branch
was fast-forwarded into canonical main at f8133ba. The release source is
`6659e902cd158a623c6ed09c3227234fffeb16b0` on release/v1.6.2, with version code 25. This receipt adds evidence
after that source commit.

## Artifact verification

The release APK and AAB were built with Java 21 and Android SDK 37.
The build completed successfully. Both artifacts were signed and independently
checked against the permanent MAVLab certificate
`46ff910b66597a7340d7b7737e598619368b8594bcdc2f05a91efb54d811eb70`.
The APK is non-debuggable, version 1.6.2/code 25, minimum API 26, target API 35.
The APK on the connected phone was still v1.6.1 and used that same certificate.

[Build output](build-checks.txt) · [Signing verification](signing.txt) ·
[APK certificate](apk-signature.txt) · [AAB certificate](aab-certificate.txt) ·
[APK metadata](apk-metadata.txt) · [Artifact SHA-256](SHA256SUMS) ·
[Installed v1.6.1 certificate](phone-before-certificate.txt).

The build reused the existing unit-test result. Candidate metadata checks
and the release-asset workflow checks completed during preparation.
Existing renderer and startup evidence remains identified with its original build.
[Startup fix verification](../2026-11-27-foreground-service-startup/README.md) ·
[SceneView emulator and phone evidence](../2026-11-27-sceneview-4.76.0/README.md).

## Maintainer direction and device scope

The maintainer requested no additional tests after release preparation started.
Further phone/emulator behavioral checks and the proposed signed-update installation
were omitted. The signed v1.6.2 APK was not installed on the phone.
Physical Android 8 GPU drivers remain untested.

During setup, mobile data was briefly disabled for an isolated local QGC check.
It was re-enabled immediately after the maintainer clarified that the laptop uses
the phone hotspot. The test setup was stopped. The saved connectivity and display
settings match their original values. [Restored settings](phone-settings-restored.json).

The release automation was aligned with Java 21, SDK 37 and current Gradle APK
filenames. Only the named signed APK and AAB are intended as GitHub draft attachments.
Checksums and evidence remain in this repository. Publication and Play Console
acceptance are separate from this prepared draft.

Tester was Codex. No QGC session was started for the candidate.
