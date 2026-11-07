package com.ascend.mavlab.feature.mission

import com.ascend.mavlab.simulation.engine.DroneState
import com.ascend.mavlab.simulation.engine.FlightMode
import com.ascend.mavlab.simulation.failures.FailureState
import com.ascend.mavlab.simulation.mission.MissionCommand
import com.ascend.mavlab.simulation.mission.MissionItem
import com.ascend.mavlab.simulation.mission.MissionProgress
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun missionRunStatus(
    state: DroneState,
    mission: MissionProgress,
): String {
    return when {
        !mission.loaded -> "No mission loaded"
        mission.complete && mission.cameraFailedImages > 0 -> "Mission complete · image failures recorded"
        mission.complete -> "Mission complete"
        mission.cameraHoldReason != null -> mission.cameraHoldReason
        state.mode == FlightMode.AUTO -> "Mission running"
        else -> "Mission ready"
    }
}

internal fun missionObjectiveLabel(mission: MissionProgress): String {
    if (!mission.loaded) return "Load or upload a mission"
    if (mission.complete) return "Mission complete"
    val active = mission.activeTarget ?: mission.items.getOrNull(mission.currentIndex)
    return active?.let { item ->
        when (item.command) {
            MissionCommand.TAKEOFF -> "Takeoff to WP${item.sequence + 1}"
            MissionCommand.WAYPOINT -> "Fly to WP${item.sequence + 1}"
            MissionCommand.LAND -> "Land at WP${item.sequence + 1}"
            MissionCommand.RTL -> "Return to launch"
            MissionCommand.LOITER_TIME -> "Loiter at WP${item.sequence + 1}"
            MissionCommand.CHANGE_SPEED -> "Apply speed change"
            MissionCommand.CAMERA_TRIGGER_DISTANCE -> "Set distance photo trigger"
        }
    } ?: "Mission waiting"
}

internal fun missionProgressPercent(mission: MissionProgress): Int {
    if (!mission.loaded) return 0
    if (mission.complete) return 100
    if (!mission.executionStarted) return 0
    return ((mission.completedCount.toFloat() / mission.items.size.toFloat()) * 100f)
        .roundToInt()
        .coerceIn(0, 100)
}

internal fun missionDistanceToActiveMeters(
    state: DroneState,
    mission: MissionProgress,
): Float? {
    val target = mission.activeTarget ?: return null
    return distanceToItemMeters(state, target)
}

internal fun missionTargetSpeedMetersPerSecond(mission: MissionProgress): Float? {
    if (!mission.loaded) return null
    val activeIndex = mission.currentIndex.coerceIn(0, mission.items.lastIndex.coerceAtLeast(0))
    val activeSpeed = mission.items.getOrNull(activeIndex)?.speedMetersPerSecond
    if (activeSpeed != null && activeSpeed > 0f) return activeSpeed
    return mission.items
        .take((activeIndex + 2).coerceAtMost(mission.items.size))
        .filter { it.command == MissionCommand.CHANGE_SPEED }
        .mapNotNull { it.speedMetersPerSecond?.takeIf { speed -> speed > 0f } }
        .lastOrNull()
}

internal fun missionEtaLabel(
    distanceMeters: Float?,
    state: DroneState,
    targetSpeedMetersPerSecond: Float?,
): String {
    val distance = distanceMeters ?: return "ETA unavailable"
    val speed = sequenceOf(
        targetSpeedMetersPerSecond,
        state.groundSpeedMS.takeIf { it > 0.3f },
    ).filterNotNull().firstOrNull { it > 0.3f } ?: return "ETA waiting for speed"
    val seconds = (distance / speed).roundToInt().coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}

internal fun missionFailureWarnings(failures: FailureState): List<String> {
    return buildList {
        if (!failures.gpsEnabled) add("GPS unavailable: do not start or continue AUTO.")
        if (failures.lostLinkActive) add("Lost link active: verify GCS control before mission changes.")
        if (failures.unsafeMissionReserveActive) add("Unsafe reserve: shorten the mission or recharge before AUTO.")
        if (failures.batteryDrainMultiplier >= 8f) add("Battery drain elevated: preserve landing reserve.")
        if (failures.windSpeedMs >= 7f) add("Wind drift active: increase waypoint and landing clearance.")
        if (kotlin.math.abs(failures.barometerOffsetMeters) > 0.05f) add("Barometer offset active: avoid low-altitude autonomous segments.")
    }
}

internal fun waypointStatusLabel(
    item: MissionItem,
    mission: MissionProgress,
): String {
    return when {
        mission.complete || item.sequence < mission.currentIndex -> "Reached"
        item.sequence == mission.currentIndex -> "Active"
        else -> "Queued"
    }
}

internal fun waypointCoordinateLabel(item: MissionItem): String {
    val localNorth = item.localNorthMeters
    val localEast = item.localEastMeters
    return if (localNorth != null && localEast != null) {
        "N %.1f m, E %.1f m".format(localNorth, localEast)
    } else {
        "%.6f, %.6f".format(item.latitudeDeg, item.longitudeDeg)
    }
}

internal fun waypointDetailLabel(item: MissionItem): String {
    val speed = item.speedMetersPerSecond
    val speedLabel = if (speed != null && speed > 0f) {
        " | target %.1f m/s".format(speed)
    } else {
        ""
    }
    return "${waypointCoordinateLabel(item)} | %.1f m AGL%s".format(item.altitudeAglMeters, speedLabel)
}

internal fun distanceToItemMeters(
    state: DroneState,
    item: MissionItem,
): Float {
    val localNorth = item.localNorthMeters
    val localEast = item.localEastMeters
    if (localNorth != null && localEast != null) {
        val north = localNorth - state.northMeters
        val east = localEast - state.eastMeters
        return sqrt(north * north + east * east)
    }
    val north = ((item.latitudeDeg - state.latitudeDeg) * MetersPerLatDeg).toFloat()
    val lonScale = MetersPerLatDeg * max(0.2, cos(Math.toRadians(state.latitudeDeg)))
    val east = ((item.longitudeDeg - state.longitudeDeg) * lonScale).toFloat()
    return sqrt(north * north + east * east)
}

private const val MetersPerLatDeg = 111_320.0
