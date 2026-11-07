package com.ascend.mavlab.feature.dashboard

import com.ascend.mavlab.core.mavlink.MavlinkIdentityStatus
import com.ascend.mavlab.simulation.engine.DroneState
import com.ascend.mavlab.simulation.mission.MissionCommand
import com.ascend.mavlab.simulation.mission.MissionProgress
import kotlin.math.sqrt

internal fun armedLabel(state: DroneState): String {
    return if (state.armed) "ARMED" else "DISARMED"
}

internal fun batteryLabel(state: DroneState): String {
    val percent = state.batteryRemainingPercent.toInt()
    val level = when {
        percent <= 15 -> "Critical"
        percent <= 30 -> "Low"
        else -> "Normal"
    }
    return "$percent% $level"
}

internal fun gpsStatusLabel(state: DroneState): String {
    return if (state.gpsFixType.toInt() >= 3) {
        "Locked (${state.gpsSatellites} sats)"
    } else {
        "No lock (${state.gpsSatellites} sats)"
    }
}

internal fun mavlinkStatusLabel(
    runtimeStatus: String,
    identityStatus: MavlinkIdentityStatus,
): String {
    return when {
        identityStatus.identityConflict -> "SYSID conflict"
        identityStatus.gcsConnected -> "QGC connected"
        else -> runtimeStatus
    }
}

internal fun missionFocusLabel(mission: MissionProgress): String {
    if (!mission.loaded) return "No active mission"
    if (mission.complete) return "Mission complete"
    val active = mission.activeTarget ?: mission.items.getOrNull(mission.currentIndex)
    return active?.let { item ->
        "${objectiveLabel(item.command)} ${item.sequence + 1}"
    } ?: "Mission waiting"
}

internal fun missionProgressLabel(mission: MissionProgress): String {
    return if (mission.loaded) {
        "${mission.completedCount}/${mission.items.size} reached"
    } else {
        "No route"
    }
}

internal fun distanceFromHomeMeters(state: DroneState): Float {
    return sqrt(state.northMeters * state.northMeters + state.eastMeters * state.eastMeters)
}

internal fun distanceFromHomeLabel(state: DroneState): String {
    return "%.1f m".format(distanceFromHomeMeters(state))
}

private fun objectiveLabel(command: MissionCommand): String {
    return when (command) {
        MissionCommand.TAKEOFF -> "Takeoff WP"
        MissionCommand.WAYPOINT -> "Fly to WP"
        MissionCommand.LAND -> "Land WP"
        MissionCommand.RTL -> "Return"
        MissionCommand.LOITER_TIME -> "Loiter WP"
        MissionCommand.CHANGE_SPEED -> "Speed change"
        MissionCommand.CAMERA_TRIGGER_DISTANCE -> "Distance photo trigger"
    }
}
