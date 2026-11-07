package com.ascend.mavlab.simulation.mission

import com.ascend.mavlab.simulation.engine.ControlAuthority
import com.ascend.mavlab.simulation.engine.DroneState
import com.ascend.mavlab.simulation.engine.FlightMode
import com.ascend.mavlab.simulation.failures.FailureState
import kotlin.math.abs

/** Guards local mission actions. MAVLink's separate command contract is unchanged. */
data class MissionActionPolicy(val startBlockedReason: String?, val canClear: Boolean) {
    val canStart: Boolean get() = startBlockedReason == null
}

fun missionActionPolicy(state: DroneState, mission: MissionProgress, upload: MissionUploadStatus, failures: FailureState, live: Boolean): MissionActionPolicy {
    val reason = when {
        !live -> "Live simulation state required"
        state.controlAuthority == ControlAuthority.SAFETY_RECOVERY -> "Fence recovery active; resolve it in Ops before starting AUTO"
        upload.phase == MissionUploadPhase.UPLOADING -> "Mission upload in progress"
        upload.phase == MissionUploadPhase.REJECTED -> "Upload rejected; inspect Ops and upload again"
        !mission.received -> "Upload a mission from QGC to verify its source"
        !state.northMeters.isFinite() || !state.eastMeters.isFinite() || !state.altitudeAglMeters.isFinite() || !state.verticalSpeedMS.isFinite() -> "Valid vehicle position required"
        mission.complete -> "Mission complete; upload a new mission to start again"
        state.armed && state.mode == FlightMode.AUTO && state.controlAuthority == ControlAuthority.GCS_MISSION -> "AUTO already active"
        (state.altitudeAglMeters > 0.25f || abs(state.verticalSpeedMS) > 0.2f) && !(state.armed && state.controlAuthority == ControlAuthority.GCS_MISSION && state.mode in setOf(FlightMode.BRAKE, FlightMode.LOITER)) -> "Start AUTO from the ground"
        state.controlAuthority == ControlAuthority.GCS_DIRECT -> "Take local control before starting AUTO"
        !failures.gpsEnabled || state.gpsFixType.toInt() < 3 -> "GPS 3D fix required"
        failures.hasMotorFailure || state.motors.any { it.failed } -> "Reset motor failures before AUTO"
        state.batteryRemainingPercent.toInt() <= 15 || failures.unsafeMissionReserveActive -> "Insufficient mission battery reserve"
        failures.lostLinkActive -> "Reset lost link before AUTO"
        else -> null
    }
    return MissionActionPolicy(reason, live && mission.loaded && !state.armed && upload.phase != MissionUploadPhase.UPLOADING)
}
