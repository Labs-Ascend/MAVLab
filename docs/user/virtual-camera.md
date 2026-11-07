# Simulated captures (1.6.3-dev)

MAVLab automatically simulates camera exposures for QGC mission training. There
is no Virtual camera section in Ops and no camera setup step. Runtime Start enables
simulated captures; they do not load a map, render pixels or create image files.

Upload a mission using supported photo-trigger commands and start it normally.
Each accepted exposure uses the engine's physical position, camera orientation,
timestamp and capture index. QGC receives standard capture feedback; photo markers
represent simulated exposures. There is no image URL or image download service.
Mission details distinguish simulated captures from saved JPEGs and failures.

## Flight setup

No Dandora map, matching map origin or image-storage reserve is required. Choose
the simulated launch location in **Ops → Advanced configuration** before uploading
a mission in another area. Changing that location still clears navigation and
safety plans. An enabled capture session restarts at the new location.

This removes camera-map restrictions, not unsupported mission commands or the
simulator's local-coordinate assumptions. Navigation uploads remain bounded to
1,000 items including QGC's home marker. Distance-trigger command 206 supports
changing the trigger distance, stopping it, and requesting an immediate exposure.
Other unsupported camera/gimbal/ROI mission commands remain rejected.

Immediate trigger items await a completed simulated exposure before continuing.
Distance triggers use actual simulated horizontal travel. BRAKE/LOITER pause
progress; explicit AUTO resumes the retained item. LAND/disarm/manual recovery
stop new mission triggers. An unavailable capture or missed cadence produces a
visible hold reason rather than silently dropping required exposures.

## Limits and ownership

The capture limit is one exposure per 250 ms, one active request plus one pending
request, and 100,000 accepted requests per camera session. Recent outcomes and
pending feedback each have a bounded 32-entry memory window. There is no persistent
event ledger or event export in this increment. Counters/history do not restore
after process recreation; Runtime Start begins a new session.

Simulated captures never arm or take control of the vehicle. They add no background
retention rule or physical camera permission. Existing service/GCS/AUTO behavior
still applies, and background phone-sensor input remains suspended. Runtime Stop
disables capture. Optional flight recording remains independent and off by default;
QGC's own telemetry logging is configured separately.

## Photos saved by earlier versions

Existing map-photo JPEGs and pose/failure records remain on the phone. If saved
capture data exists, **Ops → Recordings & review → Export saved map photos** saves
the latest retained map session to a ZIP through Android's document picker.
Export while disarmed; the originals remain intact and simulated capture stays
enabled. Export failures retain the originals and show a result in Recordings &
review. Export includes completed image/record pairs and recorded failures, while
excluding unfinished images.

Camera mode selection, map import and test-photo controls were removed from Ops.
An old map-mode preference cannot disable automatic simulated captures after an
upgrade. Map-style images from earlier builds are labelled virtual and carry
OpenStreetMap attribution; they are not aerial photographs or phone-camera images.
The legacy map backend is retained internally for data compatibility and tests.
