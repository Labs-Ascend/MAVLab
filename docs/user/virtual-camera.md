# Offline virtual map camera (working tree)

The virtual camera saves map-style training images on the phone. It uses a small
bundled OpenStreetMap-derived map, physical simulation position and a stabilized
straight-down view. These are simulated captures, not aerial photographs or images
from the phone camera. Internet and physical camera permission are unnecessary.

## First local capture

1. In Ops, open **Virtual map camera** while disarmed.
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

## Images and limits

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
recreation does not resume it.

The camera adds no background retention rule. Existing visible/armed/GCS/AUTO
runtime policy still applies, and background phone-sensor input remains suspended.

The working tree exposes the enabled camera as component 100 under MAVLab's
vehicle system ID. QGC can request camera information/settings/storage/status and
send a one-shot shutter command. Android loopback tests verify a real saved image
and retry handling. A live run with the named QGC build remains a separate gate.
The camera advertises still capture only: video, zoom/focus, interval capture and
remote image download are unsupported. Review/export happens on the phone.

## QGC distance-triggered survey missions (1.6.1-dev)

Upload a small survey using distance-triggered photos (command 206), takeoff,
waypoints and RTL. Navigation uploads accept up to 1,000 items including home.
Upload is separate from starting flight: it leaves the simulated vehicle disarmed.
Other camera/gimbal/ROI mission commands are still rejected.

For the bundled pack, set **Use Dandora origin before uploading** (it clears old
plans), enable the virtual camera and place a small survey well inside the 896 m
square. At 60 m altitude keep waypoints at least 55 m inside the map edge. Use
25 m trigger spacing and 3 m/s or slower, keep the planned photo count below the
remaining 1,000-request session allowance, and retain at least 32 MiB free storage.
Choose Start AUTO or start from QGC. Readiness checks block starting when the map,
origin, route coverage, requested rate or remaining session budget is unsuitable.
The exact reason and saved/failed photo counts appear in mission details/Ops.

Immediate trigger items save a real image before continuing. Distance triggers
save at actual simulated travel positions. A save/cadence failure pauses in BRAKE;
inspect the reason, resolve it and explicitly Resume AUTO, or take control and land.
BRAKE/LOITER pause progress; LAND/disarm stop mission triggers. Saved files remain
available for export. A mid-route photo mission stopped by disarm needs a restart
from takeoff. A failed exposure remains recorded as a failure after resume.

**Mission 1** (831 uploaded items) is suitable for checking the upload fix. Its
7.646 km² area and 12,570 planned photos exceed the bundled map/session allowance,
so use a fresh small Dandora survey for a complete capture flight. Corridor and
other pattern types need their emitted commands checked separately. Live QGC
upload/start/photo/land acceptance remains a separate evidence gate.
