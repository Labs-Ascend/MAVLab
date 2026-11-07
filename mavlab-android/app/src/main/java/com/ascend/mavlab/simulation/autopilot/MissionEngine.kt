package com.ascend.mavlab.simulation.autopilot

import com.ascend.mavlab.simulation.engine.DroneState
import com.ascend.mavlab.simulation.mission.MissionCommand
import com.ascend.mavlab.simulation.mission.MissionItem
import com.ascend.mavlab.simulation.mission.MissionProgress
import com.ascend.mavlab.simulation.mission.MissionOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

class MissionEngine {
    private val mutableProgress = MutableStateFlow(MissionProgress())
    val progress: StateFlow<MissionProgress> = mutableProgress.asStateFlow()

    fun load(items: List<MissionItem>, origin: MissionOrigin = MissionOrigin.INTERNAL_FIXTURE) {
        val sorted = items.sortedBy { it.sequence }
        val startIndex = if (isQgcHomeMarker(sorted)) 1 else 0
        mutableProgress.value = MissionProgress(
            items = sorted,
            currentIndex = startIndex,
            complete = sorted.size <= startIndex,
            activeTarget = sorted.getOrNull(startIndex),
            origin = origin,
        )
    }

    fun clear() {
        mutableProgress.value = MissionProgress()
    }

    fun setCurrent(sequence: Int): Boolean {
        val progress = mutableProgress.value
        val index = progress.items.indexOfFirst { it.sequence == sequence }
        if (index < 0) return false
        mutableProgress.value = progress.copy(
            currentIndex = index,
            complete = false,
            lastReachedSequence = null,
            activeTarget = progress.items[index],
        )
        return true
    }

    fun reset() {
        val previous = mutableProgress.value
        load(previous.items, previous.origin)
    }

    fun beginReturn() {
        mutableProgress.value = mutableProgress.value.copy(executionStarted = true)
    }

    fun finishReturn() {
        val p = mutableProgress.value
        if (p.activeTarget?.command != MissionCommand.RTL) return
        mutableProgress.value = p.copy(complete = true, activeTarget = null,
            lastReachedSequence = p.activeTarget.sequence, executionStarted = true)
    }

    fun update(state: DroneState): MissionProgress {
        val progress = mutableProgress.value.copy(executionStarted = true)
        mutableProgress.value = progress
        val current = progress.activeTarget ?: return progress
        if (current.command == MissionCommand.CHANGE_SPEED) {
            val nextIndex = progress.currentIndex + 1
            val next = progress.items.getOrNull(nextIndex)
            val updated = progress.copy(
                currentIndex = nextIndex,
                complete = next == null,
                lastReachedSequence = current.sequence,
                activeTarget = next,
            )
            mutableProgress.value = updated
            return updated
        }
        if(current.command==MissionCommand.CAMERA_TRIGGER_DISTANCE) return progress
        val distance = distanceMeters(state, current)
        val altitudeError = abs(state.altitudeAglMeters - current.altitudeAglMeters)
        if (distance <= effectiveAcceptanceRadius(current) && altitudeError <= altitudeToleranceMeters(current)) {
            val nextIndex = progress.currentIndex + 1
            val next = progress.items.getOrNull(nextIndex)
            val updated = progress.copy(
                currentIndex = nextIndex,
                complete = next == null,
                lastReachedSequence = current.sequence,
                activeTarget = next,
            )
            mutableProgress.value = updated
            return updated
        }
        return progress
    }

    fun completeCameraAction(sequence:Int) {
        val p=mutableProgress.value
        if(p.activeTarget?.sequence!=sequence || p.activeTarget.command!=MissionCommand.CAMERA_TRIGGER_DISTANCE) return
        val index=p.currentIndex+1
        mutableProgress.value=p.copy(currentIndex=index,complete=index>=p.items.size,lastReachedSequence=sequence,
            activeTarget=p.items.getOrNull(index),executionStarted=true)
    }
    fun cameraHold(reason:String?) { mutableProgress.value=mutableProgress.value.copy(cameraHoldReason=reason) }
    fun cameraResult(success:Boolean,simulated:Boolean=false) {
        val p=mutableProgress.value
        mutableProgress.value=p.copy(cameraSavedImages=p.cameraSavedImages+if(success && !simulated) 1 else 0,
            cameraSimulatedCaptures=p.cameraSimulatedCaptures+if(success && simulated) 1 else 0,
            cameraFailedImages=p.cameraFailedImages+if(success) 0 else 1)
    }

    private fun distanceMeters(state: DroneState, item: MissionItem): Float {
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

    private fun isQgcHomeMarker(items: List<MissionItem>): Boolean {
        val first = items.firstOrNull() ?: return false
        val second = items.getOrNull(1) ?: return false
        return first.sequence == 0 &&
            first.command == MissionCommand.WAYPOINT &&
            second.command == MissionCommand.TAKEOFF
    }

    private fun effectiveAcceptanceRadius(item: MissionItem): Float {
        return item.acceptanceRadiusMeters.coerceAtLeast(5f)
    }

    private fun altitudeToleranceMeters(item: MissionItem): Float {
        return when (item.command) {
            MissionCommand.LAND -> 1.25f
            else -> 4f
        }
    }

    private companion object {
        const val MetersPerLatDeg = 111_320.0
    }
}
