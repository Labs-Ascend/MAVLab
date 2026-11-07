package com.ascend.mavlab.simulation.camera

import com.ascend.mavlab.simulation.coordinates.PhysicalPoseSnapshot
import com.ascend.mavlab.simulation.mission.MissionItem

/** Nonblocking engine boundary; only the runtime implementation owns Android IO. */
interface MissionCamera {
    enum class Admission { ACCEPTED, BUSY, REJECTED }
    fun preflight(items:List<MissionItem>,pose:PhysicalPoseSnapshot,speed:Float):String?
    fun capture(pose:PhysicalPoseSnapshot,trigger:String,completed:(String?)->Unit):Admission
}
