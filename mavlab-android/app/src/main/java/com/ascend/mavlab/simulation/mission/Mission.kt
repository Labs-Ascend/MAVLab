package com.ascend.mavlab.simulation.mission

data class Mission(
    val items: List<MissionItem> = emptyList(),
    val currentIndex: Int = 0,
    val complete: Boolean = items.isEmpty(),
) {
    val currentItem: MissionItem? get() = items.getOrNull(currentIndex)
}

data class MissionItem(
    val sequence: Int,
    val command: MissionCommand,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeAglMeters: Float,
    val acceptanceRadiusMeters: Float = 2f,
    val autocontinue: Boolean = true,
    val localNorthMeters: Float? = null,
    val localEastMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val altitudeIsMsl: Boolean = false,
    val wire: MissionWireData? = null,
)

/** Original wire values survive engine normalization, download and persistence. */
data class MissionWireData(val frame: Int, val p1: Float, val p2: Float, val p3: Float, val p4: Float,
    val x: Double, val y: Double, val z: Float)

enum class MissionCommand(val mavCmdId: Int) {
    WAYPOINT(16),
    TAKEOFF(22),
    LAND(21),
    RTL(20),
    LOITER_TIME(19),
    CHANGE_SPEED(178),
    CAMERA_TRIGGER_DISTANCE(206);

    val isNav: Boolean
        get() = this == WAYPOINT ||
            this == TAKEOFF ||
            this == LAND ||
            this == RTL ||
            this == LOITER_TIME

    val isDo: Boolean
        get() = this == CHANGE_SPEED || this == CAMERA_TRIGGER_DISTANCE

    companion object {
        fun fromMavCmdId(mavCmdId: Int): MissionCommand {
            return entries.firstOrNull { it.mavCmdId == mavCmdId } ?: WAYPOINT
        }
    }
}

data class MissionProgress(
    val items: List<MissionItem> = emptyList(),
    val currentIndex: Int = 0,
    val complete: Boolean = true,
    val lastReachedSequence: Int? = null,
    val activeTarget: MissionItem? = null,
    val origin: MissionOrigin = MissionOrigin.UNVERIFIED,
    val executionStarted: Boolean = false,
    val cameraHoldReason: String? = null,
    val cameraSavedImages: Int = 0,
    val cameraSimulatedCaptures: Int = 0,
    val cameraFailedImages: Int = 0,
) {
    val loaded: Boolean get() = items.isNotEmpty()
    val received: Boolean get() = loaded && origin == MissionOrigin.GCS_UPLOAD
    val completedCount: Int get() = if (complete) items.size else currentIndex.coerceAtMost(items.size)
}

/** Provenance belongs to the authoritative mission, including after process restoration. */
enum class MissionOrigin { GCS_UPLOAD, UNVERIFIED, INTERNAL_FIXTURE }
