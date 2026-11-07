# First offline-camera milestone — reference-device evidence

Status: **PARTIAL implementation acceptance**, not a release, complete survey
workflow, all-MAVLink compatibility claim or low-end-device qualification.

Source: `0ae021a` on `feature/offline-map-camera`, based on `c3b5cc1`.
Android source/app: 1.6.0 / 19, experimental debug build. Device:
`reference-samsung-01`, Samsung SM-G970U, Android 12 / SDK31. QGC:
`v5.2.0-dev-143-ga76f22939`. Tester: Codex. Date: 2026-11-07 UTC.
The device is an older flagship, not the requested low-end acceptance device.

[Result and artifact identity](result.json) records the APK checksum, installed
checksum match, source commit, source-file fingerprint and open gates. The APK was
built before committing unchanged source content; the fingerprint ties that
content to the feature commit. These records do not establish reproducible builds
across arbitrary SDK/compiler/library environments.

## Executed checks

- **316 JVM tests, 49 suites**, zero failures/errors/skips. Includes fixed-grid
  geographic references/noise isolation/home independence, camera footprint and
  quaternion axes, existing manual/sensor/takeoff/safety/mission/runtime policies.
- `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`: **PASS**. The final
  test-only follow-up reran the new quaternion test and lint. Logs retained here.
- **18 Android tests: PASS** on the identified phone. Isolated controller/context
  directories and loopback sockets avoided the real AppRuntime/QGC vehicle during
  effect tests. Real WebP decoding, JPEG/commit markers, ZIP contents, 4 MiB LRU
  eviction, independent colored-quadrant yaw references, out-of-coverage/origin
  errors, queued capture Stop/restart, low-storage failure records, failed-import
  rollback, unsupported modes and component targeting were exercised.
- Camera protocol tests produced one real JPEG per accepted unique single shot;
  both sequence-numbered retry and pinned-QGC zero-sequence/confirmation retry
  paths replayed outcomes without another image. A fresh too-early press did not
  replay an older accepted request. Unsupported interval commands were rejected.
- Mission, safety, simulation-origin and recording preferences matched the private
  pre-update backup hashes, including after reopening the real runtime. See the
  preservation JSON files. Update used `adb install -r` with the same public
  signing-certificate fingerprint; no uninstall or data clear occurred.
- The real phone's Ops panel exposed the camera controls and opened the bundled
  map while disarmed. The existing QGC session requested/received camera information
  and settings from **SYSID 1 / component 100**, model `MAVLab Virtual Map Camera`,
  flags 2 and resolution 640 × 480. See [passive discovery receipt](qgc-camera-discovery.json).
  Gimbal/video information requests were rejected. No origin reset, live arm,
  takeoff, mission replacement or shutter command was sent for that observation.

The camera's first live discovery observation is distinct from synthetic shutter
proof. **Live named-QGC shutter, survey/corridor flight and pause/resume remain
open.** The existing QGC file-selection dialog was not changed by this milestone.

## Artifacts and limitations

[Sample JPEG](virtual-photo-sample.jpg) is a 640 × 480, 19,124-byte image from an
isolated synthetic Dandora pose at 60 m. It contains virtual/map attribution; it
is not a phone photograph or evidence of flight. [Sample ZIP](virtual-captures-sample.zip)
contains its manifest and pose ledger. JPEG size is one measured example, not a
universal storage promise. The pixel/attribution output was inspected.

The prepared OSM source pack is 1,159,168 bytes, 85 lossless WebP tiles. The source
and PNG/WebP comparison are supplied under `tools/offline-map/`. All 85 decoded
RGB tiles matched their PNG reference exactly. This proves codec/data handling;
it does not establish OSM completeness, surveyed elevation or map currency.

A new orientation fixture initially failed because opening its copied SQLite
file with Android's default write flags added an `android_metadata` table. The
fixture was corrected to use `NO_LOCALIZED_COLLATORS`; its subsequent independent
north-up/90-degree assertions passed. The failed run remains here. The strict
production schema was not weakened to make that test pass.

OpenCode DeepSeek wrote the bounded mode fix before its provider credit failure;
GLM failed before implementation. Codex completed/reviewed the integration and ran
the checks directly. Neither delegate's terminal status is represented as PASS.

## Impact review

| Row | Applicable evidence / remaining gate |
|---|---|
| Product surfaces | New Ops map/camera/test/export controls exercised on the reference phone; broader font/narrow-screen/history UX acceptance remains open. Cockpit/Control/SIM ownership unchanged. |
| Control paths | Camera wire tests preserve armed/mode/authority; local manual/sensor and takeoff engine regressions pass. Camera mission actions remain unsupported. |
| Runtime states | Real foreground startup/discovery and isolated Stop/restart generation checks pass. Full background, process recreation and all-surface physical sensor acceptance remain open. No new retention condition. |
| Simulation | Fixed origin/physical pose/noise tests plus existing engine/safety suites pass. No independent camera physics or UI-owned pose introduced. |
| Protocol | Component routing, information/status, direct single-shot effects and mode rejection tested; live discovery witnessed. Mission-photo, full outcome history/media services and live shutter remain open. |
| Reverse states | Camera Off/restart, failed import and completed ZIP export covered within the listed tests; no live flight manipulation. Existing landing/disarm unit regressions pass. |
| Evidence | JVM, lint/build, signed in-place APK and identified-device Android logs retained. Upstream-absent offline, 1,000-capture/20-minute benchmark and low-end hardware gates remain open. |
| Documentation | User camera/Ops guide, runtime review, protocol classifications, glossary and ADR-0014 updated. Local ignored execution plan remains separate from current architecture. |

Camera work is bounded by one active job/one pending request, a 4 MiB decoded cache
and 1,000 admitted requests per session. No whole-map bitmap is loaded. Those are
implementation/test bounds, not a measured total-process memory, thermal, battery
or P95 latency qualification. The preserved original private app-data tar and
installed-before APK are under `/tmp` and were not published with these receipts.
