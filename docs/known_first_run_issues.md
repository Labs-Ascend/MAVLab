# Known first-run issues (read this before posting)

This guidance applies to MAVLab v1.6.1.

## 1. QGroundControl doesn't discover the vehicle

Start with [Connect QGroundControl](user/connect-qgroundcontrol.md#same-android-device). Open MAVLab, switch to QGC within one minute, and confirm UDP auto-connect is enabled. If QGC displays a protocol mismatch, save your plan, check both app versions, and reconnect. MAVLab v1.6.1 sends unsigned MAVLink 2.

## 2. Desktop QGC over Wi-Fi — no link

Use the [desktop setup steps](user/connect-qgroundcontrol.md#desktop-over-wi-fi). Verify that the devices share a local network without client isolation and that the firewall allows inbound UDP `14550` from that network.

## 3. APK won't install

MAVLab v1.6.1 uses a permanent signing certificate. If the existing install uses another certificate, Android rejects an in-place update. Export data first and check the [upgrade guide](user/upgrading.md) before choosing how to migrate.

When Android prompts for permission, allow installs from the file manager or browser you used to open the [signed APK](setup_guide.md).

## 4. Backgrounded networking

When MAVLab is backgrounded, phone-sensor input stops immediately, but motor audio remains synchronized with the retained simulation so QGroundControl arm and flight commands stay audible. Telemetry remains active for a one-minute GCS handoff and continues while a validated GCS heartbeat is present or the vehicle is armed/running an AUTO mission. If MAVLab is idle and disconnected, the runtime, audio, wake/Wi-Fi locks, and service notification stop automatically. Returning to MAVLab restarts them. Explicitly closing MAVLab or tapping **Stop simulation** in its notification stops immediately.

## 5. Mission upload fails / no progress

Keep the simulated vehicle **disarmed** while diagnosing uploads. Open Ops → Mission upload for the exact requested/received sequence and rejection. An accepted upload is not an executing AUTO mission. Do not repeatedly send arm/takeoff commands to resolve a timeout; a command can execute even if its ACK is not received. See [mission operation](user/mission.md).

## 6. Fence/rally upload errors

Save your QGC plan and reconnect to refresh capabilities. Navigation, fence and rally are separate lists. Upload safety plans while disarmed. Invalid or unsupported coordinates, geometry, and commands are rejected without replacing the accepted plan. Enable the uploaded fence separately in Ops. See [Flight boundaries and rally](user/flight-boundaries.md) for supported geometry and recovery behavior.

## Still stuck?

Open a [First-run problem](https://github.com/Labs-Ascend/MAVLab/issues/new?template=first_run_problem.md) issue with MAVLab version, device/Android, exact QGC build, connection mode and the error text. Include Ops upload/ACK evidence when available.
