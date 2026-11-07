# Mission 1 — QGC survey fixture as received

Created by the user in QGroundControl Daily a76f22939, received 2026-11-07.
Original `/tmp/Mission 1.plan` was copied unchanged; SHA and counts are in
`analysis.json`. This is a real saved QGC plan, not a manually invented route.

The JSON has 830 expanded mission items; QGC uploaded count 831 including its
home marker. Both the phone log and CRC-checked QGC wire record show immediate
count rejection. The installed build allows 100 and sends generic
MAV_MISSION_ERROR (1), producing the screenshot's unspecified-error wording.
No navigation mission items were requested in this rejected transfer.

Commands present: TAKEOFF 22 (1), WAYPOINT 16 (661), DO_SET_CAM_TRIGG_DIST
206 (167), RTL 20 (1). The count limit is the first blocker; mission camera
triggers remain unimplemented and must not be accepted as fake successes.

The survey covers approximately 7.646 square kilometres, plans 12,570 photos,
and is outside the bundled Dandora map. It is an oversized/out-of-coverage
regression input, not a runnable first-map demonstration or acceptance PASS.
Camera settings are manual, 60 m relative, trigger/spacing both 25 m.

No upload retry, arming, takeoff, origin change or plan alteration was performed
while diagnosing this case. Source code and runtime behavior were not modified.
Android's unrelated system camera/SurfaceFlinger messages were excluded.

The derived `app/src/test/resources/mission-1-items.bin` contains a little-endian
16-bit count followed by 831 37-byte MISSION_ITEM_INT payloads. Regenerate it with
`python3 docs/validation/fixtures/qgc-a76f22939-mission-1/generate-wire-fixture.py`.
The script independently packs original expanded items and a synthetic home marker;
it does not call MAVLab's encoder and is not a captured QGC wire stream. Its SHA-256
is `ca8a56d5b4f631b71259983bfe6308421eb258bcda59438a83df7cd738847283`.
JVM tests exercise parsing, normalized engine loading, download and V3 persistence;
isolated Android tests replay the transfer without accessing the user's runtime.
