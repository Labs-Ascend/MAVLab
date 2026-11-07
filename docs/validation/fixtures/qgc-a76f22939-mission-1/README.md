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
