# ADR-0014: Bounded offline planar map capture

Date: 2026-11-07. Status: working-tree first implementation; device and QGC gates
are tracked separately. This is not a release or complete survey-compatibility claim.

Use prepared, north-up tiles in a small custom read-only SQLite pack. The bundled
OSM snapshot is pinned under `tools/offline-map/source/`, stripped of contributor
user metadata, and rendered with a simple project-owned way/line style. Labels,
relation fills, elevation and real photographic appearance are outside this pack.
No map SDK, network service, country extract or full-map bitmap enters the phone's
capture path. The development preparation script is not a runtime prerequisite.

`SimulationCoordinateFrame` defines `mavlab-local-grid-v1`; see the simulation
model for the fixed-origin approximation. Camera requests take an atomic physical
engine snapshot including origin, north/east metres, AGL, yaw and monotonic time.
They never derive truth from GPS/compass perturbations or digital-twin scaling.
The stabilized nadir view uses image-up along heading. Its camera FRD forward axis
points down in world NED, right points image-right; the quaternion is yaw times
pitch -90°. No independent camera-owned flight state or authority is introduced.

`OfflineMapPack` validates file/schema/metadata/key/payload/bitmap limits and owns
a decoded-byte LRU of at most 4 MiB. SQLite is read-only with a bounded page-cache
target. Tiles draw directly into one software output; the renderer makes no
footprint mosaic. Offscreen images include attribution before JPEG encoding.
Decoded cache eviction/recycling is safe because the single IO worker completes
each software draw before the tile can be removed.

`VirtualCameraController` is owned by AppRuntime. Enabling, importing, rendering,
encoding, ledger writes and export run on IO. A camera guard protects admission
and generation bookkeeping; disk encoding/publication does not hold that guard.
Engine snapshots are acquired before the camera guard to avoid inverse lock
ordering. The capture queue has one slot in addition to the active request and
there is a three-second admission interval. Import is staged, bounded to 64 MiB,
checked and atomically renamed; active capture must be off first. The previous
pack survives failure.

Captures use separate private sessions under `files/mavlab/camera`, not automatic
flight-recorder sessions. A manifest describes source/profile. Each completed JPEG
has an atomic JSON commit marker with pose/time/size/hash; unfinished files are
excluded from ZIP export. Image indices are recovered from completed pairs on the
IO worker. Export copies bounded buffers to a user-selected Android document and
retains originals on failure. The initial UI exposes latest-session export; older
session browsing and deletion remain pending. Accepted requests are bounded to 1,000
per session. Failures retain separate JSON records when storage permits, including
requests cancelled across a generation switch; export includes these records and
excludes unfinished images.

Off and origin changes invalidate generations. An in-flight completion may retain
its original-session completed file during a shutdown race; stale work cannot
update a new session. Runtime Stop closes resources asynchronously without waiting
on the UI thread. Camera state is not restored after process recreation. It adds
no foreground-service type, physical camera permission or retention condition.
Phone sensors, audio, simulation and recording keep their established ownership.

The camera component 100 now exposes still capture, information/settings/storage/
capture status and outcomes. Isolated Android wire tests establish these effects.
The request cache retains 32 recent request outcomes; sequence-numbered retries
are idempotent. For the pinned QGC's zero capture-sequence path, confirmation>0
identifies retries. A sequence-zero/confirmation-zero packet cannot distinguish a
legitimate new press from a nonconforming retry. This limit is explicit in the
MAVLink contract. Named QGC discovery/shutter evidence remains pending.

Mission triggers, readiness/hold/resume, complete capture-history recovery, and
low-end/offline/performance acceptance remain subsequent gates. Still-capture
capability does not imply these services.
