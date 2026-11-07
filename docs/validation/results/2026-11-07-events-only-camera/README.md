# Events-only virtual camera validation

Executed 2026-11-07 by Codex automated checks and ADB on Samsung SM-G970U,
Android 12 / SDK 31. Application source commit:
`886bebfbc5a9468e5a0438b1e407e5803b163b21`. Installed **1.6.2-dev / code 22**.
APK SHA-256: `f6c936f35765783df57e2246f9ff0d70eea72cadac10c003df85f66c7cca55fb`.
Installed bytes matched the built APK. Debug signing certificate SHA-256:
`ceb07790e125e11da41d7ec675f0d2330ad34768af1083890fbd30681bb3da1d`.
This is a feature-branch development build, not a published release.

## Verified effects

- **328 JVM tests passed**, zero skipped/failures/errors. Includes the ground-level
  feedback regression: valid nadir quaternion, relative altitude zero, success,
  no file URL, named virtual camera and unsupported image storage. Engine tests
  separate simulated exposures from saved JPEG counts and retain the established
  manual/phone-input, mission, pause/resume and recovery regression suite.
- **testDebugUnitTest, lintDebug, assembleDebug and assembleDebugAndroidTest passed**.
  Lint has zero errors and 42 warnings. Full build log, lint XML, unit summary and
  selected JUnit XML accompany this receipt.
- **All 25 isolated Android tests passed** in 11.163 s against code 22. Existing
  JPEG capture/export/cache/geometry/cancellation and camera protocol tests select
  MAP_PHOTOS explicitly. New tests forbid map-asset access, exercise Nairobi/Tokyo
  physical poses without maps, test full Mission 1 readiness without the Dandora
  coverage gate, run an engine mission through a simulated exposure and RTL/land,
  validate wire feedback/empty URL/no storage/retry/cadence, and reject stale
  generation feedback. Mode selection persistence and disarmed/off guards are covered.
- **Live QGC feedback verified** with v5.2.0-dev-143-ga76f22939. After launch, the
  default EVENTS_ONLY camera became ready without manually enabling it or opening
  a map. With the vehicle disarmed, one Ops **Simulate capture** action produced
  CAMERA_IMAGE_CAPTURED (263), sys1/component100, success1, index0, AGL0, empty URL.
  The event was received in QGC's real `.mavlink` log at
  `2026-11-07T16:50:34.476Z`. CRC-validated camera information names
  `MAVLab Events Only - No Images`; capture status reaches one. A subsequent vehicle
  heartbeat remained disarmed in STABILIZE. See live-qgc-event.json.
- The phone displayed **1 simulated, 0 JPEGs saved, 0 failed, cache 0 KiB**.
  No flight was started. The user's **89-item mission** was retained unchanged.
  QGC's marker UI was not visually inspected; receipt of feedback is established
  by the actual QGC log, not an inference from isolated ACK tests.

## Resource and data evidence

The corrected-build resource checks produced **20 simulated captures, zero capture
files, and zero decoded tile-cache bytes**. The 5.017 s elapsed time includes the
intentional admission spacing and is not a CPU benchmark. The separate map-photo
sample produced one 19,124-byte JPEG and used 1,048,576 decoded tile-cache bytes.
These are recorded samples, not percentage CPU, battery or universal size claims.
The Samsung is an old flagship; low-end qualification remains pending.

A fresh screen showed READY / DISARMED before installation. A private host backup
preceded `adb install -r`; no uninstall/data clear was used. After device tests,
**430 original user files and six preferences** matched their checksums. Only the
runtime-generated `files/profileInstalled` marker changed. Repeating the comparison
after the live simulated event found no changed or newly created user files.
Original JPEGs, ledgers, recording data and uploaded mission remain retained.
Backup contents are not published; hashes/counts are in installed-data-preservation.json.

## Corrected initial failure

The initial code-21 device candidate passed 24/25 tests. The ground-level feedback
case timed out because the serializer constructed a map-only CameraPose that
requires AGL >=0.5 m. Orientation calculation was separated from map footprint
validation. Two JVM wire regressions were added, and the corrected code-22 APK
then passed all 25 device tests plus the actual ground-level QGC feedback check.
The initial-device-tests.log and initial-data-preservation.json are historical
stage evidence, not the final build's acceptance status. A temporary USB disconnect
prevented the first corrected install attempt; after user reconnection a fresh
backup, in-place update, installed-hash check and full retest completed.

## Impact review

| Area | Evidence and scope |
|---|---|
| Product surfaces | Ops has explicit Simulated captures / Map photos selection and no-image labeling. Control separates simulated captures and JPEGs saved. Cockpit, SIM and onboarding use existing state; full visual review was not performed. |
| Control paths | Engine pose drives exposures. Binding/enablement does not arm or claim authority. Existing manual/phone-input tests pass; new simulated mission counters and RTL/disarm have isolated device coverage. |
| Runtime | EVENTS_ONLY auto-enables on Runtime Start; explicit off is respected within the runtime. MAP_PHOTOS remains explicit. Origin changes restart an enabled events-only session. Actual foreground launch was observed. Background policy/service retention conditions are unchanged; no new background acceptance claim. |
| Simulation | Same distance/immediate trigger scheduler, pending-result handling, BRAKE/resume and LAND/disarm precedence. Separate latched capture mode prevents simulated successes being counted as saved images. |
| Protocol | Component100 model/feedback/status, empty URL, zero storage count / NOT_SUPPORTED, replay and rate handling verified. Camera information may remain cached by QGC until reconnection after a mode switch; MAVLab's displayed mode is authoritative. |
| Reverse states | Mode changes require camera off, no outstanding capture and disarmed vehicle. Runtime Stop disables capture. Generations suppress stale feedback; map files survive mode changes. |
| Persistence | Small camera-mode preference added; no camera counters, active captures or event ledger persist. Navigation V3 format is unchanged. Test contexts isolate both files and preferences. |
| Documentation | User camera guide, ADR-0014, MAVLink contract, glossary and compatibility note reviewed/updated. |

EVENTS_ONLY removes camera map-coverage/origin/free-space requirements, not
unsupported mission commands, mission item limits, local-coordinate assumptions
or vehicle launch setup. It caps admission at one event per 250 ms and 100,000
requests per session, retaining only bounded recent feedback/history. No persistent
event ledger or event export is implemented. Existing opt-in flight recording is
independent. Standard capture success is an explicit virtual exposure in this
mode; it does not claim a stored image, image transfer or physical camera use.

A full user-operated events-only survey flight, visual marker inspection and
sustained CPU/battery/low-end measurements remain separate follow-up checks.
