# MAVLink and QGroundControl Contract

Audience: Protocol contributors and release validators
Status: v1.6.0 implementation contract; not a blanket live-QGC qualification
Baseline verified: 2026-09-01

This document maps what MAVLab claims at the QGroundControl boundary.

## Identity

Current defaults:

- simulated vehicle SYSID: `1`;
- autopilot COMPID: `1`;
- recommended QGC SYSID: `255`.

Current code returns SYSID 1. Older documents describing a generated unique per-install ID are aspirational, not implemented behavior.

MAVLab reports a pinned ArduCopter 4.6.3-compatible development identity and custom identifier `MAVLAB`. It never claims to be an official ArduPilot firmware build.

## UDP topology

- MAVLab attempts to bind UDP `14551`, with an ephemeral fallback if unavailable.
- Same-device QGC telemetry targets `127.0.0.1:14550`.
- LAN discovery/peer destinations may also receive telemetry.
- Before peer detection, heartbeat discovery packets go to configured destinations.
- After detection, telemetry goes to active peers.

Documents claiming bind port `14556` are stale.

## Wire protocol (UI/UX branch, 2026-10-11)

MAVLab v1.6.0 emits unsigned MAVLink 2 frames for discovery, telemetry, parameters, commands and missions. This makes the advertised MAVLINK2 capability truthful; the previous implementation advertised the bit but emitted only MAVLink 1. QGC Daily's v2-only non-heartbeat processing is not compatible with that previous implementation.

The receiver accepts CRC-validated MAVLink 1 and unsigned MAVLink 2 for the explicitly defined common-dialect subset in `MavlinkWire`. It decodes every complete frame in a UDP datagram and restores MAVLink 2 omitted trailing zero bytes before checking fields and targets. Unknown IDs, invalid checksums, incomplete frames, signed messages (no signing keys/authentication implementation) and unknown incompatibility flags are not executed. This is not a claim of full common/ArduPilot dialect or signing support. `COMMAND_ACK` extensions address the actual requesting system/component.

`EXTENDED_SYS_STATE` is emitted and available through REQUEST_MESSAGE. VTOL state is undefined for the quadrotor; landed state follows authoritative engine altitude (on ground at <=0.15 m AGL, in air above that). REQUEST_MESSAGE for unimplemented metadata, standard modes, gimbal and other messages returns UNSUPPORTED rather than pretending to provide them. SET_MESSAGE_INTERVAL remains an **accepted no-op for rate selection**; the fixed telemetry cadence is unchanged.

References: [MAVLink serialization](https://mavlink.io/en/guide/serialization.html), [QGC receive handling](https://github.com/mavlink/qgroundcontrol/blob/master/src/Comms/MAVLinkProtocol.cc). Independent wire fixtures use pymavlink 2.4.50; it is a validation tool, not an application/runtime dependency.

## Timing and connection

The telemetry burst is nominally every 200 ms (5 Hz), and the current path includes heartbeat in that burst. Do not describe heartbeat as exactly 1 Hz without changing or measuring implementation.

The connection policy uses roughly three seconds of continuous GCS heartbeat to validate a GCS and fifteen seconds without heartbeat to expire it. Only heartbeat packets whose MAVLink vehicle type is `MAV_TYPE_GCS` establish this state. Protocol rate, peer expiry, and service-retention timing are separate concepts.

## Capability classes

| Class | Meaning |
|---|---|
| Implemented | Produces the documented effect and has focused evidence. |
| Partial | A useful subset works; limits are stated. |
| Accepted no-op | Acknowledged without the complete requested effect. |
| Unsupported | Rejected, appropriately ignored, or not advertised. |

An accepted ACK is never proof of complete implementation.

## Current inbound areas

Code includes mode changes, `COMMAND_LONG`, `COMMAND_INT`, parameters, mission upload/download/current/clear, and selected arm/takeoff/land/mission/calibration flows. Some stream-interval and calibration requests are accepted without full behavior. Keep exact classification synchronized with source and tests.

The canonical phone-sensor correction changes local takeoff to ALT_HOLD so local attitude steering remains effective. MAVLink takeoff still passes GCS_DIRECT authority to the engine and retains GUIDED position control; command ACKs and capability declarations are unchanged. Focused engine tests cover this separation.

### Mode admission (working tree)

`SET_MODE` (11) and `MAV_CMD_DO_SET_MODE` (176, via `COMMAND_LONG` or `COMMAND_INT`) admit only the implemented custom-mode ids 0 (STABILIZE), 2 (ALT_HOLD), 3 (AUTO), 4 (GUIDED), 5 (LOITER), 6 (RTL), 9 (LAND) and 17 (BRAKE). Unknown ids are rejected with `MAV_RESULT_UNSUPPORTED` and leave engine mode and control authority unchanged. BRAKE uses engine position/altitude hold, pauses mission advancement and distance triggers, and retains mission authority when pausing AUTO. `DO_SET_MODE` param2 values that cannot be an exact uint32 id (nonfinite, negative, fractional or out of range) are rejected with `MAV_RESULT_DENIED`, so e.g. `4.9` or `NaN` is not silently coerced into GUIDED or STABILIZE. Rejections keep the requesting endpoint as the ACK destination. Device-boundary coverage lives in `MavlinkProtocolDeviceTest`; the lookup unit test alone is not evidence of UDP behavior.

## Mission evidence

Validate upload count/sequence, item decoding, ACK behavior, engine loading, mission start/progress, clear/set-current, reconnect/re-upload, and malformed/unsupported items.

### Upload recovery (UI/UX branch, 2026-10-11)

- Request the first missing item with MISSION_REQUEST_INT (legacy-item uploads retain legacy requests). Retry at 250 ms or the next receive-loop poll, up to five retries per expected item; polling continues even when no packets arrive. Progress resets the retry budget; lack of progress is bounded by ten seconds. Exhaustion publishes the expected sequence and ERROR ACK; the previous engine mission is retained.
- Upload ownership includes UDP endpoint and sender system/component. An unrelated sender cannot feed or replace an active transaction. Duplicates/gaps request the expected item without overwriting received items.
- Only a complete valid upload loads the engine. A matching retransmitted item receives the final ACCEPTED ACK for up to ten seconds after completion, without loading/resetting the mission again. Clear, new upload and endpoint stop invalidate this bounded completion cache. Endpoint stop rejects an incomplete upload visibly.
- Mission types 0 (navigation), 1 (fence) and 2 (rally) have separate stores. Other types receive UNSUPPORTED with their type echoed. Typed clear removes only its store; CLEAR_ALL type ALL (255) clears all three, while disarmed. See the safety-plan contract below.
- Item download preserves geographic altitude frames and uses MISSION frame for CHANGE_SPEED. Recording policy remains opt-in. The subsequent safety-plan implementation changes RTL execution as described below.

## Simulated fence and rally contract (working tree)

Implemented in v1.6.0. The heading is retained for compatibility with historical evidence links.

This is implemented simulation behavior, not ArduPilot firmware equivalence. `MISSION_FENCE` and `MISSION_RALLY` capability bits advertise the bounded typed mission service; unsupported command/coordinate semantics remain rejected.

- Each safety store accepts at most 100 items. Uploads use ordered `MISSION_REQUEST_INT`, targeted typed ACKs, endpoint/system/component ownership, 250 ms requests with five retries, ten-second progress timeout and a bounded final-ACK replay cache. INT and legacy float geographic items are decoded; current/autocontinue flags are accepted metadata with no safety-plan execution meaning (QGC marks its first safety item current). A complete validated list replaces the previous store atomically. Failed, malformed, interrupted or timed-out replacements retain the previous accepted list. Download snapshots are bounded by type and owner and expire after ten seconds of inactivity.
- Shapes: commands 5001/5002 are contiguous inclusion/exclusion polygon vertices (at least three, consistent vertex count/group, no repeated vertices, collinear-only or self-intersecting geometry). Commands 5003/5004 are inclusion/exclusion circles. Command 5000 is an optional single fence-return point. Rally items use command 5100. Geographic frames 0/5 (MSL) and 3/6 (home-relative) are implemented. Terrain/local/body frames, nonzero reserved parameters and other commands are rejected. Geographic coordinates are bounded to +/-85 degrees latitude; circle radii and local polygon spans are bounded to 50 km. No global/dateline geometry claim is made.
- Exclusions always win. Inclusion regions intersect within a group and unite across groups. `FENCE_OPTIONS=2` instead unites all inclusion regions; 0 is the default. Other options are rejected. Polygon/circle geometry compiles on accepted upload, not in the physics tick; checks use physical local position projected from the stable simulation origin, not injected GPS noise.
- `FENCE_ENABLE` defaults to 0. Uploading shapes never enables enforcement. `FENCE_TYPE` bits 1/2/4/8 select maximum home-relative altitude, home circle, uploaded regions, and minimum home-relative altitude. Minimum monitoring begins after the simulated vehicle first reaches the configured floor and is suspended for landing/disarmed states, allowing takeoff and touchdown.
- `FENCE_ACTION=0` reports without changing flight intent; 1 takes RTL or local LAND if GPS/eligible return is unavailable; 2 lands locally. Values 3+ (SmartRTL/BRAKE variants) are refused. `FENCE_RADIUS`, `FENCE_ALT_MAX`, `FENCE_ALT_MIN` and `FENCE_MARGIN` are enforced; margin is clearance for selecting return endpoints, not a claim of predictive fence braking. Invalid/nonfinite values retain and return the actual current value.
- Configuration and safety-plan replacement/clear are disarmed-only, except `FENCE_ENABLE=0` can disable enforcement while armed. `DO_FENCE_ENABLE` (207) implements global enable/disable with param2=0; selective-type and disable-floor-only forms are denied. Ordinary arm/takeoff/mission-start requests outside an enabled horizontal/maximum-altitude boundary are denied; no successful command ACK is fabricated.
- An actionable breach produces `SAFETY_RECOVERY` authority, clears the direct-RPM override, pauses manual/mission intent, and keeps its RTL/LAND recovery latched after re-entry until landing/disarm. Control and Cockpit identify recovery; Ops identifies breach count/type and destination. Manual input cannot silently reclaim recovery. Disabling the fence releases the recovery latch but leaves the current RTL/LAND intent intact; deliberately choose a manual mode or disarm to leave it. LAND and DISARM remain available.
- `RTL_ALT` (centimetres on the parameter boundary), `RALLY_LIMIT_KM` (0 disables distance limiting), and `RALLY_INCL_HOME` affect selection. Choose the nearest eligible rally/home candidate, with home included when configured; if no rally qualifies, try the fence return point then home. Candidate altitudes are converted to flat-world AGL once, must be above 0.25 m, within 50 km of simulation origin and within enabled fence limits including margin. If none qualifies, land in place. RTL holds position while climbing to the return altitude, travels to the selected endpoint, then LANDs and auto-disarms on touchdown. RTL on the ground lands/disarms without launching. GPS loss in RTL also triggers local LAND. A mission RTL is terminal and records completion/reached sequence only after landing, not just when a home waypoint is reached.
- Return travel is straight-line position control, **not** terrain/building/obstacle avoidance or a guarantee that a path through nonconvex/exclusion geometry remains inside the fence. Uploaded points are alternative simulated return destinations, not certified real-world landing sites. Plan usable return paths explicitly.
- `FENCE_STATUS` (162) reports authoritative breach status, bounded breach count, last type/time; it is emitted while fencing is enabled and available through REQUEST_MESSAGE. Mitigation is NONE for no action, UNKNOWN for RTL/LAND because the protocol has no matching enum; MAVLab never claims velocity limiting. SYS_STATUS geofence present/enabled/health bits reflect subsystem availability, enablement and breach state.
- Fence/rally/configuration persist in the separate version-1 `mavlab_safety` snapshot. Restore validates its version, limits and geometry; corrupt/future snapshots are not executed. Flights/authority are never restored as armed. Changing the simulation origin through Ops clears safety and navigation plans and disables fencing. Explicit runtime stop preserves accepted plans but discards transfer sessions. Recording remains opt-in; no new background-retention policy is introduced.

References: [typed mission protocol](https://mavlink.io/en/services/mission.html), [command/frame/fence definitions](https://mavlink.io/en/messages/common.html), [QGC fence item construction](https://github.com/mavlink/qgroundcontrol/blob/master/src/MissionManager/GeoFenceManager.cc), [QGC rally manager](https://github.com/mavlink/qgroundcontrol/blob/master/src/MissionManager/RallyPointManager.cc), [ArduPilot fence concepts](https://ardupilot.org/copter/docs/common-geofencing-landing-page.html), [rally concepts](https://ardupilot.org/copter/docs/common-rally-points.html).

Focused unit and isolated Android loopback checks are separate from live QGC acceptance. See dated results under `docs/validation/results/` for the actual tested build and any outstanding checks.

## Scope

MAVLab exposes a simulated vehicle. Protocol compatibility does not certify real-aircraft control, ArduPilot firmware equivalence, or safe hardware operation.

Primary evidence: `MavlinkUdpServer.kt`, `MavlinkMessageBuilder.kt`, `MavlinkIdentityStatus.kt`, `ArduPilotCompatibilityProfile.kt`, focused tests, and versioned records under `docs/validation/results/`.

## Mission consolidation boundary changes (UI/UX branch)

- Upload completion records GCS source; persisted restoration is not a new upload ACK. AUTO mission progress requires an armed autopilot; mode/ACK alone does not establish execution.
- Unknown mission command IDs are rejected with UNSUPPORTED (3). Unsupported frames are rejected with UNSUPPORTED_FRAME (2); diagnostics name command/frame/zero-based sequence. Invalid values retain INVALID (5). Accepted command IDs are 16, 22, 21, 20, 19, 178 and 206. An unsuccessful replacement does not remove the previous loaded mission.
- Navigation coordinates accept geographic GLOBAL / GLOBAL_INT (0/5, MSL) or GLOBAL_RELATIVE_ALT / INT (3/6, home-relative metres). Frame MISSION (2) accepts CHANGE_SPEED, DO_SET_CAM_TRIGG_DIST or RTL. Local/body/terrain frames are rejected; no terrain model is implemented. Raw MSL altitude converts to AGL at engine load using current simulation ground MSL. Flat simulation ground makes relative-home altitude equal AGL. See [MAVLink frame definitions](https://mavlink.io/en/messages/common.html#MAV_FRAME) and [mission results](https://mavlink.io/en/messages/common.html#MAV_MISSION_RESULT).
- LOITER_TIME is **partial**: arrival is implemented, timed dwell is not. autocontinue=false is an **accepted no-op for pausing**: the engine still advances on arrival. These limitations appear in Ops and affected navigation nodes are hollow in SIM. CHANGE_SPEED retains the existing speed handling and has no spatial node.
- Local Control's Start AUTO/Clear use guarded application actions; camera readiness also gates external AUTO/MISSION_START; clear, replacement upload and set-current require disarm. No new autopilot capability bit is advertised. Live QGC upload/rejection/start/land interoperability needs separate evidence.

## Virtual still-camera component (working tree)

Camera enablement exposes component 100 under the vehicle SYSID. It has its own
MAV_TYPE_CAMERA/MAV_AUTOPILOT_INVALID heartbeat at approximately 1 Hz and advertises
only CAMERA_CAP_FLAGS_CAPTURE_IMAGE (2). Disabled cameras are not advertised.
Camera commands never change flight mode, armed state or control authority.
Command ACKs originate from component 100 and address the requester; foreign
system/component traffic cannot fall through into autopilot control.

| Boundary | Working-tree effect and limit |
|---|---|
| REQUEST_MESSAGE 512 | Camera information 259, settings 260, storage 261, capture status 262; outcome 263 re-request for an index in the last 32 retained successful outcomes |
| Legacy requests 521/522/525/527 | Camera information/settings/storage/capture status, with validated implemented parameters |
| SET_CAMERA_MODE 530 | Select the implemented image mode (0); other modes rejected |
| IMAGE_START_CAPTURE 2000 | One shot, interval 0, total 1, target camera 0/100; accepted request schedules a real saved JPEG and later outcome |
| IMAGE_STOP_CAPTURE 2001 | Idempotent stop with no interval service active; an already exposed single-shot pose may finish saving |
| Camera-addressed COMMAND_INT | COMMAND_LONG_ONLY; no attempted reinterpretation of its x/y fields |
| Other camera commands/options | Rejected; no video, interval/count sequence, gimbal, zoom, focus, formatting or file-transfer capability |

The camera uses the virtual optics documented in [ADR-0014](decisions/0014-offline-map-camera.md).
CAMERA_IMAGE_CAPTURED has physical simulated geographic coordinates and AGL/MSL,
a stabilized-nadir camera quaternion in NED (camera FRD optical forward points
down), a zero-based image index and real completion outcome. Its local file path
is not a MAVLink FTP or HTTP download service. Image count/index recover from
completed on-phone ledger/image pairs; no active capture resumes after restart.
The in-memory 32-outcome re-request window does not restore after process death.
This is partial camera-protocol history support, not a complete media service.

Sequence-numbered single shots deduplicate by requester and capture sequence;
reusing a sequence for different parameters is denied. The pinned QGC
[a76f22939 takePhoto implementation](https://github.com/mavlink/qgroundcontrol/blob/a76f22939/src/Camera/VehicleCameraControl.cc)
omits capture param4, so its value is zero. For that path, command confirmation>0
replays the last matching accepted request within 30 seconds. Fresh zero-sequence,
zero-confirmation presses are distinct and obey the three-second cadence limit.
A client retransmitting the same request with both numbers zero is ambiguous;
exactly-once semantics cannot be guaranteed for that non-identifying retry.

Admission ACK and completed capture outcome are separate. Busy/unavailable and
invalid-pose requests are rejected before exposure. File/decoder/storage errors
after admission emit a failed outcome, with no fabricated JPEG. Work is bounded
to one active job/one pending request and 1,000 accepted requests per session.
A stopped generation cannot report into a new session. These paths have focused
Android loopback coverage; a synthetic wire test is not a live QGC acceptance run.

Wire offsets, lengths and CRC extras were checked against
[c_library_v2 085c8344](https://github.com/mavlink/c_library_v2/tree/085c8344fa721a3d80bf574ae77c4d873f4985f3/common)
and the [camera protocol](https://mavlink.io/en/services/camera.html).
## Distance-triggered survey subset (1.6.1-dev)

Navigation uploads are bounded to **1,000 items**, including QGC's home marker.
Oversize counts receive MAV_MISSION_NO_SPACE (4); the previous mission is retained.
This limit is separate from the unchanged 100-item fence/rally stores. Replacement,
clear and set-current are denied while armed. Upload alone never arms or claims
mission authority. Actual Mission 1 expands to 831 items and is a regression fixture;
this is isolated protocol evidence, not a live QGC upload acceptance result.

Mission command 206 is implemented through the engine and the real virtual JPEG
writer: param1 is horizontal physical travel distance in metres (0 disables distance
triggering), param2 is 0/-1 (ignore integration time), param3 is 0/1 (one immediate
photo even when param1=0), param4 targets 0/100 (or NaN default); reserved fields
accept zero/NaN only. Other targets/options are rejected. Each immediate photo holds
position until saving finishes; repeated command items execute once at their item
index. Distance accumulates only during armed AUTO under GCS_MISSION. No missing
past exposures are invented. BRAKE/LOITER pause progress and triggers; explicit AUTO
resumes the retained item. LAND/disarm/manual recovery stop new mission triggers.
A late save may finish recording its original outcome but cannot override recovery.

AUTO/MISSION_START preflight requires an enabled camera, matching origin, conservative
whole-route footprint coverage (maximum route altitude, diagonal radius plus 8 m),
at least 32 MiB free in the worker-refreshed storage snapshot (each save also checks actual free space), planned distance/speed >=3 seconds, and a conservative estimate
within the remaining 1,000-request session budget. Readiness failure leaves arming,
mode and authority unchanged and reports a reason. An admitted save failure, missed
cadence or required-photo timeout holds in BRAKE with visible mission diagnostics.
Immediate-photo waiting has a ten-second simulation-time limit. Explicit resume
rechecks readiness; failed images remain counted and in exported failure records.
A stopped mid-route photo mission must restart from takeoff after disarm.

Completed mission captures emit CAMERA_IMAGE_CAPTURED from component 100 and enter
its 32-outcome request window. The pending mission-event window is also bounded to
32; disconnect/history expiry does not delete saved on-phone image/record pairs.
Mission snapshots now write V3, retaining original frames/parameters/coordinates
separately from normalized engine navigation values. V1/V2 remain readable; an
unreadable saved snapshot is retained with re-upload guidance. Camera/runtime flight
state is not persisted in the mission snapshot. Original uploaded yaw and hold
values round-trip; finite navigation yaw, timed dwell and autocontinue=false remain
partial/accepted no-op as documented, rather than a full flight-stack claim.

Mission 1 covers about 7.646 km² with 12,570 planned photos, outside the bundled
896 m square map and above the session budget. It can test upload; it cannot pass
camera-flight preflight with that map. Other camera/gimbal/ROI mission IDs, spline,
jumps and unsupported layouts remain rejected. No camera no-ops are introduced.
Reference: [command 206 definitions](https://mavlink.io/en/messages/common.html#MAV_CMD_DO_SET_CAM_TRIGG_DIST).
