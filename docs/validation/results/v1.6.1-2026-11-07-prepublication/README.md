# v1.6.1 prepublication build and signing checks

Date: 2026-11-07 UTC. Tester: Codex on Ambrose's Linux workstation.
Version: 1.6.1, code 24. Runtime source: `18994e7` (Ops camera card removal),
plus the release metadata and signing/media preparation committed with this record.
The eventual v1.6.1 tag identifies the complete release source. This record covers
local builds; published CI assets and device launch are verified separately.

## Automated results

- `GRADLE_USER_HOME="$PWD/.gradle" ./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease bundleRelease --console=plain --max-workers=2`: PASS, 19m 22s, 106 tasks (41 executed, 65 up-to-date).
- JVM result set: 328 tests, zero failures/errors/skips. Gradle reused unchanged
  test outputs where eligible; this is not a claim that all tests were re-executed.
- Release-asset workflow tests: 4 PASS. Documentation-checker tests: 9 PASS.
- Real APK/AAB signing tests: 4 PASS using a disposable key. Cover both formats,
  unchanged sources, certificate mismatch with no output, missing secret,
  malformed fingerprint and preservation of an existing output.
- Local release APK: version 1.6.1/code 24; `application-debuggable` absent.
- Local APK and AAB: approved certificate verified. Exact sizes and hashes are
  retained in [local-artifacts.json](local-artifacts.json); these are local bytes,
  not a prediction of CI output hashes.
- Public certificate, protected local signing files and backup were checked;
  backup key/password/credential bytes match their originals. Named Actions secrets
  and the approved public certificate variable are configured. No private key,
  password or test keystore is retained in this repository.
- Both user-uploaded video attachments were downloaded and matched the compressed
  clip SHA-256 values in the [compression report](../../../../assets/media/issue-upload/compression-report.json).

[Full build output](gradle-gate.log) · [Signing-test output](signing-tests.log).

## Impact review and limits

| Area | Review and verification |
|---|---|
| Product surfaces | README now uses the two user-uploaded video URLs. October footage remains labelled historical. Ops camera-card removal is from the runtime source above; release preparation changes no Compose code. |
| Control paths | No new control logic in release preparation. Prior development-build tests cover manual/phone input, GCS direct commands and missions. |
| Runtime states | Foreground/background handoff, armed/AUTO and process recreation policies are unchanged. No new release-build lifecycle acceptance is claimed here. |
| Simulation | No new physics, autopilot, failure, battery or publication changes in release preparation. |
| Protocol | Mission/camera implementation and scoped live QGC evidence are retained in the prior records below. No all-command or real-aircraft compatibility claim. |
| Reverse states | No new arm/disarm, land, stop/disconnect, reset or recording behavior in release preparation. Migration never uninstalls or clears the user's phone. |
| Evidence | Local host/signing gates above; signed-app launch and downloaded publication checks are separate. |
| Documentation | README, versioned notes, upgrade/camera guides, doc indexes, release/signing runbooks, public certificate and code-24 changelog reviewed. |

The first shared-AVD attempt restarted Android's system server during a loaded
cold boot; no MAVLab package was installed there. It was stopped, and a new isolated
AVD was created for the final signed-artifact check. This is an infrastructure
failure, not a passed app launch.

Earlier implementation evidence includes the
[events-only camera checks](../2026-11-07-events-only-camera/README.md) and
[Samsung survey update](../2026-11-07-mission-survey-update/README.md).
Those development artifacts' 25 isolated Samsung tests and live QGC receipt are
not relabelled as tests of these newly signed artifacts. Low-end performance,
sustained battery use, Play acceptance and full MAVLink coverage remain unqualified.
