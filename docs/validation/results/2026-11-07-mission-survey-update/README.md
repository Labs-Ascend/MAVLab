# Mission 1 upload and offline capture update

Executed 2026-11-07 by Codex automated tests and ADB. Application source:
`eb9656b7ef1cfd06e1788a051673dde67754be68`, version **1.6.1-dev / 20**,
Samsung SM-G970U, Android 12 / SDK 31. Debug APK SHA-256:
`5d2f5ce26e890259ff2f7da22617f90ebc3b42a56983d7c13d29fc24768c9197`.
The APK read back from the installed package matched these exact bytes.
This is a development-branch installation, not a release or complete QGC acceptance.

The former 100-item rejection is replaced by a bounded 1,000-item navigation store
and NO_SPACE response above that limit. Mission command 206 performs real distance
and immediate photo captures, including the supplied plan's final distance=0,
immediate=1 item. Original mission wire values survive engine normalization,
download and V3 persistence. No camera mission command is accepted as a fake no-op.

## Recorded checks

- **325 JVM tests passed**, zero skipped/failures/errors. Includes complete derived
  Mission 1 transfer (831 items, 167 camera items), preserved wire values through
  engine loading/download/persistence, V1/V2 restore, count boundaries, real distance
  exposures, pause/resume without duplicate immediate capture, BRAKE deceleration,
  failed-save hold and late completion respecting LAND/disarm. Established phone-input
  and local-takeoff regression tests remain included in the suite.
- **lintDebug, assembleDebug and assembleDebugAndroidTest passed**. Lint reports
  zero errors and 42 warnings; the full XML is retained. Kotlin also reports an
  unnecessary non-null assertion in a test and the existing deprecated APK-output
  API; these do not fail compilation.
- **21 isolated Android tests passed** in 6.239 s on the named Samsung. The actual
  Mission 1 payload transfer accepts all 831 items without arming, oversize replacement
  retains the old mission, missing camera blocks start, armed transfer/clear is denied,
  and an independent engine/controller saves a readable JPEG and emits a component-100
  capture outcome. Existing image geometry/cache/export/cancellation/failure and
  direct camera retry tests also pass. These tests never target QGC or AppRuntime's
  live simulated vehicle.
- A fresh phone screen showed READY / DISARMED before installation. A private
  on-host tar backup preceded `adb install -r`; no uninstall or data clear was used.
  All **395 original user files and six preferences** matched their preinstall
  checksums after device tests. Only the generated `files/profileInstalled` marker
  changed. Backup contents are not published; the digest is in data-preservation.json.
- The app reopened successfully to READY / DISARMED, STABILIZE, IDLE, with QGC
  connected. No mission start or live flight command was sent. First startup's
  immediate UI dump returned a transient null root; a subsequent read succeeded.

## Impact and limits

| Area | Review and evidence |
|---|---|
| Product surfaces | Control mission details expose photo counts/hold reason and Resume AUTO; Cockpit labels the new item. Ops diagnostics remain the command/preflight explanation. SIM uses existing engine-derived navigation. Full manual visual acceptance is pending. |
| Control paths | Upload does not arm or claim authority; engine start and external AUTO share camera preflight. Manual/phone-sensor regression tests pass. No physical sensor flight was performed in this update. |
| Runtime | Foreground launch verified. Service/background retention policy is unchanged; camera binding adds no authority or service type. No new background handoff acceptance claim. |
| Simulation | Actual engine position drives triggers. Immediate captures await IO completion; distance/cadence failures hold in BRAKE. RTL/land/disarm and late callback precedence have focused coverage. |
| Protocol | Navigation count limit, command 206, original wire download, component-100 mission outcomes and armed replacement/clear/set-current guards reviewed. No new autopilot capability bit. |
| Reverse states | BRAKE/LOITER pause; explicit AUTO resumes the item. LAND/disarm/manual recovery stop new mission requests; saved/failure records remain exportable. Full live disconnect/recovery workflow remains pending. |
| Persistence | V3 wire tuple added; V1/V2 decode covered. Unknown saved formats produce re-upload guidance while retaining original preferences. No flight/camera activity resumes from mission storage. |
| Documentation | Canonical MAVLink contract, camera ADR, persistence ADR, glossary, compatibility note and user camera instructions updated. |

QGC fixture version is **v5.2.0-dev-143-ga76f22939**. The supplied `.plan` is
preserved unchanged; binary test payloads are independently derived from it with
a synthetic QGC home marker, not a newly captured full QGC wire exchange. See the
fixture's generator and hashes. Synthetic/device ACK evidence is separate from
live interoperability. **The user's real QGC upload retry is pending.**

Mission 1 covers about 7.646 km² and plans 12,570 images; its route is outside the
bundled 896 m square Dandora map and exceeds the camera session budget. Upload can
now be tested, but its AUTO camera preflight must fail with this pack. A fresh small
Dandora survey is needed for live upload/start/progress/photo/land acceptance.
Corridor command breadth still needs its own fixture and live validation. All-MAVLink,
3D imagery, gimbal/video, terrain, full camera media history, low-end-device qualification
and sustained offline performance are not established here. The Samsung is an old
flagship, not a low-end qualification device. External GLM/DeepSeek review was not
an acceptance input; earlier attempts were blocked by provider credits.

Machine-readable result, source fingerprint, unit summaries, selected JUnit XML,
build log, lint XML, device log and data-preservation record accompany this file.
