# MAVLab Release Runbook

Audience: Maintainers
Status: Active baseline
Last reviewed: 2026-11-07

## 1. Establish release truth

- Review Git status and decide which working-tree changes belong.
- Confirm `versionName` and `versionCode` in `mavlab-android/app/build.gradle.kts`.
- Do not ship protocol/runtime changes under notes claiming none occurred.
- Confirm user docs match the actual product surfaces and artifact name.
- Candidate preparation, development merge, device/QGC acceptance and public release are different gates. The [v1.6.0 readiness record](v1.6.0-readiness.md) is historical; use version-specific evidence for each new release.

## 2. Run automated gates

```bash
cd mavlab-android
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease bundleRelease --console=plain
python3 ../scripts/test_release_signing.py --apk app/build/outputs/apk/release/MAVlab.apk --aab app/build/outputs/bundle/release/app-release.aab
```

Expected debug artifact:

`mavlab-android/app/build/outputs/apk/debug/MAVlab.apk`

Use repository release/AAB automation and the configured signing environment. Never commit signing secrets.

Local Gradle builds produce a debug APK and unsigned release-variant APK/AAB. The release workflow signs the release-variant APK and AAB using scripts/sign-release-build.sh, verifies their approved certificate and publishes only those two signed builds. The AAB is not directly installable; Play Console acceptance remains separate. Versioned notes marked `Status: Release candidate` or `Status: Draft` block tag publication.

The tag workflow signs the non-debuggable release-variant APK and the AAB using the approved permanent release identity before creating final checksums. Configure `MAVLAB_APK_KEYSTORE_B64`, `MAVLAB_APK_STORE_PASSWORD`, `MAVLAB_APK_KEY_ALIAS`, and `MAVLAB_APK_KEY_PASSWORD` as Actions secrets, plus `MAVLAB_APK_CERT_SHA256` as a repository variable. These names do not imply they are configured. Never print or commit values; uploading private key material requires explicit maintainer authorization. The existing opaque `MAVLAB` secret must not be assumed to hold a keystore.

**v1.6.0-only exception (maintainer approved 2026-10-12):** stable signing is deferred to the next release. Publish the verified local debug APK manually with prominent certificate/data-loss warnings and the unsigned AAB's limits. Tag CI archives comparison assets, skips stable signing and does not auto-publish or overwrite public v1.6.0 assets. All other tags retain the signing guard. This exception does not promise seamless upgrades from the official v1.5.3 APK. See [next-release signing priority](release-signing-next.md).

Verify the official previous APK certificate and choose the same signing identity for an in-place update. The guard rejects missing inputs, malformed fingerprints and mismatched certificates. A new key is a deliberate migration decision, not a fix by uninstalling user data. Local and CI builds may use different debug keys unless made stable; compare certificates, not just app IDs or filenames. The workflow signs both artifacts but does not claim Play acceptance.

## 3. Validate changed surfaces

As applicable, cover onboarding; the four destinations (Cockpit, Control, SIM and Ops), including contextual mission actions and the received-route view; phone control/calibration; background handoff and explicit stop; same-phone and LAN QGC; arm/disarm, takeoff/land, direct commands, mission upload/start/completion; telemetry and reconnect; failures/reset; recording/export; and supported devices. Keep build-specific passes and explicit qualification gaps separate; a development merge is not release acceptance.

## 4. Record evidence

Create an immutable record under `docs/validation/results/` with release/tag and commit, artifact checksum, device/Android, QGC build, tester/date, automated results, manual scenarios, evidence, and known exceptions.

## 5. Update release surfaces

Update release notes, README download/version text, changed user docs, MAVLink compatibility, paper addendum/revision where needed, and Play metadata.

Verify the media pack's `SHA256SUMS`, captions, audio and source dates for README/gallery use. Release attachments are **app builds only**: the versioned APK and AAB. Do not upload videos, screenshots, posters, media ZIPs or checksum text files to the release. Keep checksums in validation records/CI evidence; use repository links for demos rather than treating old footage as fresh acceptance. From the repository root run `python3 scripts/check-release-docs.py` for candidate metadata and local documentation/media links, and `python3 scripts/test_release_assets.py` for build-only packaging/publication checks. Finalize candidate status and published download links only after the corresponding gates pass.

## 6. Publish and verify

Tag the reviewed commit, publish through repository automation, download and verify the published artifact, and confirm release links/instructions. Do not force-move a published tag; use a patch release unless an explicit retag decision is made.

Check that the canonical release has exactly the versioned APK and AAB as uploaded attachments. Independently verify downloaded checksums against the validation record and GitHub asset digests. GitHub also supplies automatic source ZIP/tar archives; those are not manually uploaded attachments. The personal mirror copies Git refs, not GitHub release assets. Preserve historical notes, results and paper claims; create new identified evidence for this artifact. Existing-release asset cleanup requires an explicit maintainer request, resolved asset names/IDs and recoverable copies before deletion.

## Permanent identity from v1.6.1

Maintainer authorized a new permanent release key on 2026-11-07. The public SHA-256
fingerprint is `46ff910b66597a7340d7b7737e598619368b8594bcdc2f05a91efb54d811eb70`.
[Public certificate](mavlab-release-certificate.pem). Private key/passwords are kept
outside Git, with a verified private backup and encrypted Actions secrets. Signing
guards check the approved repository variable, reject missing/mismatched inputs and
never overwrite input builds. v1.6.1 is a deliberate migration from older debug
identities; do not install it over or uninstall the user's existing app for testing.
Use an isolated emulator for the new identity and retain explicit qualification gaps.
