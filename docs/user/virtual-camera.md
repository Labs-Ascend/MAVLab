# Virtual camera (1.6.2-dev)

MAVLab defaults to **Simulated captures — no image files**. It models camera
exposures for QGC mission training without loading a map, rendering pixels or
writing images. The virtual camera is enabled on Runtime Start in this mode.
Existing saved JPEGs remain on the phone; changing the default does not delete them.

## Default: simulated captures

Upload a mission using supported photo-trigger commands and start it normally.
Each accepted exposure uses the engine's physical position, camera orientation,
timestamp and capture index. QGC receives standard capture feedback; its photo
markers represent simulated exposures. There is no downloadable image or image URL.
MAVLab shows **Simulated captures** separately from **JPEGs saved**.

No Dandora map, matching map origin or image-storage reserve is required in this
mode. Choose the simulated launch location in Ops before uploading a mission in
another area; changing that location still clears navigation and safety plans.
This removes camera-map restrictions, not the simulator's local-coordinate model
or unsupported-command restrictions.

The limit is one exposure per 250 ms, one active request plus one pending request,
and 100,000 accepted requests per camera session. Only the most recent 32 outcomes
are retained for protocol re-requests; queued feedback is also bounded to 32.
There is no persistent event ledger or event export in this increment. Counters
and recent outcomes reset when the camera is enabled again or the process restarts.
Keep optional flight recording independent; choosing simulated captures does not
turn it on. QGC's own telemetry logging is configured separately.

In **Ops → Virtual camera**, turn the camera off while disarmed to select a mode,
then enable it. The selected mode persists. Explicit Camera off is respected until
you enable it again or restart the runtime. Runtime Stop disables the camera;
Runtime Start enables the selected events-only mode again. Changing launch location
restarts an already-enabled events-only session, invalidating old feedback.
The camera adds no background-retention rule and does not arm or claim authority.

## Optional: map photos

Select **Map photos** while the camera is off and the simulated vehicle is disarmed.
This mode saves map-style training JPEGs on the phone using a small bundled
OpenStreetMap-derived map and a stabilized straight-down view. These are simulated
map captures, not aerial photographs or phone-camera images. Internet and physical
camera permission are unnecessary. This mode must be enabled explicitly after
Runtime Start; it retains its coverage, storage and capture-rate checks.

### First map photo

1. In Ops, open **Virtual camera** while disarmed.
2. Choose **Use Dandora origin** and confirm. This resets the simulated location
   and clears navigation and safety plans, as other origin changes do.
3. Enable the camera, then take off using the existing flight controls. The
   camera requires at least 0.5 m altitude and its entire footprint inside the map.
4. Choose **Test photo**. Successful capture means a readable JPEG and its pose
   record were saved, rather than merely accepting a button press.
5. Turn the camera off and choose **Export latest session**. Android asks where
   to save a ZIP containing the images, manifest and individual pose records.

The map's usable area is 896 × 896 m around latitude -1.2425, longitude 36.9225.
Its flat ground altitude is simulated. Imported packs must use the supported
MAVLab schema and their own matching origin; they are not arbitrary MBTiles, GIS,
OSM XML or street-map downloads. Bad imports leave the previous pack in place.

### Map images and limits

The initial fixed profile is 640 × 480 JPEG at quality 80, a 6.4 × 4.8 mm virtual
sensor, 5.5425626 mm focal length, 60° horizontal field of view. At 60 m altitude
its footprint is about 69.28 × 51.96 m. Image-up follows physical vehicle yaw;
roll/pitch are stabilized. This is not a controllable gimbal.

Capture admission is limited to one image every three seconds, one active job and
at most one pending request. Each session admits at most 1,000 requests, keeping file/export bookkeeping bounded; turn the camera off and on for a new session. An unavailable map, invalid altitude, mismatched
origin, footprint outside coverage, missing tile or low storage causes a visible
failure. There is no blank-map success or internet fallback. Source resolution
is 0.5 m/pixel, so a larger JPEG does not create additional map detail.

A visible footer labels each image as a virtual map capture and credits
OpenStreetMap contributors. That footer covers the bottom image strip; these
images are intended for learning capture workflow, not photogrammetric accuracy.
The manifest includes source attribution, profile, map hash and map metadata. Individual
records contain physical pose, frame, simulated geographic coordinates, exposure
snapshot time, completion time, file size and checksum. GPS EXIF is not inserted.

The phone retains originals under MAVLab's private capture storage. Failed accepted requests retain a failure record when storage permits; no placeholder image is created. Export streams
completed image/record pairs and failure records into a ZIP; it does not duplicate the source map into
every flight. Export failures retain originals. An interrupted image without its
completed pose record is excluded from export. The current UI exports the latest
completed session; deletion and browsing older sessions are not yet provided.

## Ownership and stopping

Camera capture works independently of the SIM screen and automatic flight
recording. Turning it on does not opt into telemetry recording or take control of
the simulated vehicle. Camera off stops new requests and invalidates queued work;
a capture completing at the same instant may remain as a completed file in its
original session. It cannot publish into a later session. Camera off does not
land, disarm or stop flight recording. Runtime Stop disables the camera; process
recreation does not resume a map-photo session; events-only mode starts a new session when the runtime starts.

The camera adds no background retention rule. Existing visible/armed/GCS/AUTO
runtime policy still applies, and background phone-sensor input remains suspended.

The working tree exposes the enabled camera as component 100 under MAVLab's
vehicle system ID. QGC can request camera information/settings/storage/status and
send a one-shot shutter command. The active mode determines whether it creates a JPEG or a simulated exposure. Android loopback tests verify a real saved image
and retry handling. A live run with the named QGC build remains a separate gate.
The camera advertises still capture only: video, zoom/focus, interval capture and
remote image download are unsupported. Review/export happens on the phone.

## QGC distance-triggered survey missions

Upload a small survey using distance-triggered photos (command 206), takeoff,
waypoints and RTL. Navigation uploads accept up to 1,000 items including home.
Upload is separate from starting flight: it leaves the simulated vehicle disarmed.
Other camera/gimbal/ROI mission commands are still rejected.

For **Map photos** with the bundled pack, set **Use Dandora origin before uploading** (it clears old
plans), enable the virtual camera and place a small survey well inside the 896 m
square. At 60 m altitude keep waypoints at least 55 m inside the map edge. Use
25 m trigger spacing and 3 m/s or slower, keep the planned photo count below the
remaining 1,000-request session allowance, and retain at least 32 MiB free storage.
Choose Start AUTO or start from QGC. Readiness checks block starting when the map,
origin, route coverage, requested rate or remaining session budget is unsuitable.
The exact reason and simulated/saved/failed capture counts appear in mission details/Ops.

Immediate trigger items await a completed virtual exposure before continuing; Map photos waits for the JPEG save. Distance triggers use actual simulated travel positions. A save/cadence failure pauses in BRAKE;
inspect the reason, resolve it and explicitly Resume AUTO, or take control and land.
BRAKE/LOITER pause progress; LAND/disarm stop mission triggers. Saved files remain
available for export. A mid-route photo mission stopped by disarm needs a restart
from takeoff. A failed exposure remains recorded as a failure after resume.

**Mission 1** (831 uploaded items) is suitable for checking the upload fix. Its
7.646 km² area and 12,570 planned photos exceed the bundled map/session allowance,
so use a fresh small Dandora survey for a complete map-photo flight. Simulated captures has no map gate and uses its separate 100,000-event budget. Corridor and
other pattern types need their emitted commands checked separately. Live QGC
upload/start/photo/land acceptance remains a separate evidence gate.

QGC may cache the camera model name until its camera connection refreshes. The
active mode shown in MAVLab is authoritative. No image download service is advertised
in either mode. A simulated exposure's success feedback does not mean a JPEG exists.
