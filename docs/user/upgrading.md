# Installing or upgrading to v1.6.1

v1.6.1 starts a permanent release-signing identity. Its APK is non-debuggable and
its APK/AAB share certificate SHA-256:

`46ff910b66597a7340d7b7737e598619368b8594bcdc2f05a91efb54d811eb70`

The key differs from earlier debug installations, including the distributed
v1.6.0 APK and local development builds. **Android cannot update those installations
in place with this release.** Save QGC plans, export recordings and any earlier map
photos from Ops → Recordings & review, and preserve important settings externally.
No automatic private-data migration is implemented. Do not uninstall or clear data
merely to silence a signature error: uninstalling erases the app's private files.
Make that migration decision only after preserving what you need.

Future releases signed by the same permanent identity can update v1.6.1 in place.
Land/disarm and stop/save recording before any installation. AAB files are signed
store-upload artifacts; install the APK directly. A signed AAB is not Play approval.
[Public certificate](../operations/mavlab-release-certificate.pem) ·
[Release notes](../v1_6_1_release_notes.md).

## Interface and data continuity

- Navigation becomes **Cockpit · Control · SIM · Ops**. Missions move into a contextual Control row, Cockpit progress, SIM's received-route view and Ops upload evidence.
- There is no sample route, Load route or separate Mission destination. Upload a plan from QGC to show real received geometry.
- Onboarding ends with **Start flying**, not an arm command. Existing completion is retained; replay from Ops → About MAVLab if desired.
- Full screen is optional in Ops → Display. Existing app settings and compact labelled navigation remain available.
- Recording stays opt-in. Existing saved sessions remain; opening the app, uploading a mission or starting AUTO does not implicitly enable automatic capture.

## Saved data and missions

Audio, app, navigation and recording settings are retained by an in-place update. Received mission source remains stored. Older saved missions with unknown source are preserved, but need re-upload before local Start AUTO or route display. Restoring a plan is not restoring an armed/executing flight.

Fence/rally plans and their configuration use separate versioned storage. They start empty/disabled when no snapshot exists. Uploading does not enable fencing. Changing the simulated GPS origin deliberately clears navigation/fence/rally data and disables fencing; land/disarm and save the plan externally before doing this.

## Refresh QGroundControl

Save your QGC plan before restarting/reconnecting it. The updated capability handshake advertises the implemented bounded safety-plan service and uses MAVLink 2. Keep MAVLab disarmed during transfer checks; inspect Ops for navigation evidence and Flight boundaries & rally for separate safety lists.

An upload may still fail for unsupported frames, commands or invalid geometry. Do not repeatedly issue arm/takeoff to diagnose a missing ACK. Check the actual engine state, exact error and version/build first. If a disarmed mission authority remains after clearing navigation, use the confirmed **Take local control** action in Control rather than assuming clear changes authority.

See [mission operation](mission.md), [flight boundaries](flight-boundaries.md), [recording in Ops](ops.md) and [troubleshooting](../known_first_run_issues.md). Build-specific preservation and interoperability evidence is separate from this guidance; [release readiness](../operations/v1.6.0-readiness.md) identifies what remains.
