# MAVLab v1.6.1 Release Notes

Status: Release
Android version code: 24.

MAVLab v1.6.1 adds bounded QGroundControl survey capture simulation and starts a
permanent release-signing identity. Core simulation remains on-device and offline.

## Mission and capture changes

- Navigation missions accept up to 1,000 items including QGC's home marker. Failed
  replacements retain the prior accepted mission; oversize uploads report NO_SPACE.
- Distance-trigger command 206 executes from actual engine travel and supports its
  immediate-exposure flag, including an immediate exposure when distance is zero.
- Simulated captures are automatic. They send camera feedback with pose, timestamp
  and index without map loading, image rendering or image-file writes. No Dandora
  map is required. These virtual exposures do not produce downloadable photos.
- Admission is bounded to one exposure per 250 ms and 100,000 requests per session;
  recent feedback/history are bounded. No persistent event ledger is added.
- BRAKE (17) physically holds the simulated vehicle and pauses mission progression.
  Explicit AUTO resumes it; LAND/disarm/manual recovery stop new mission triggers.
- Original mission frames/parameters survive normalization, download and V3 storage;
  older V1/V2 records remain readable. Unsupported values remain explicitly rejected.
- Ops no longer has a camera setup card. Existing map-photo data remains exportable
  from Recordings & review without disabling simulated capture. Recording stays opt-in.
- Unknown custom modes are rejected, and geographic control uses physical engine
  coordinates. Foreground manual phone steering remains effective across the views.

## Signed distribution and migration

The APK is built from the non-debuggable release variant. APK and AAB are signed
with the same new permanent MAVLab release certificate:

`46ff910b66597a7340d7b7737e598619368b8594bcdc2f05a91efb54d811eb70`

[Public certificate](operations/mavlab-release-certificate.pem).

**This new identity cannot update older debug/other-certificate installations in
place.** Export recordings and saved map photos, save QGC plans and preserve
settings before deciding to migrate. Uninstalling erases private app data; no
uninstall or data clear is performed automatically. Future releases using this
identity are intended to support in-place upgrades. See [upgrade guidance](user/upgrading.md).

The signed AAB is an app-store upload artifact, not directly installable. Signing
alone does not establish Play Console acceptance or store availability. Release
attachments contain only the versioned APK and AAB.

## Evidence and limits

Local release APK/AAB signing, non-debuggable packaging and rejection guards passed;
see the [prepublication record](validation/results/v1.6.1-2026-11-07-prepublication/README.md).

Host and device evidence for the implementation is retained under
[validation results](validation/results/README.md), including 328 JVM checks, 25
isolated Samsung device checks, and real QGC receipt of a no-image capture event
in the development build. Final signed-artifact verification is recorded separately
for this release; prior debug-build results are not relabelled as release-device tests.

The simulator still uses a local flat-ground model. All-MAVLink, full ArduPilot
parity, terrain/obstacle routing, arbitrary camera/gimbal commands, real imagery,
video capture and real-aircraft qualification are not claimed. Timed loiter and
waypoint pause semantics remain partial as described in the
[MAVLink contract](internals/mavlink-contract.md). Low-end qualification and sustained
CPU/battery savings remain unmeasured. Demo recordings from October 2026 illustrate
older product flows; they are not acceptance evidence for these new features.
