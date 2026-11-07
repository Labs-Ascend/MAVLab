package com.ascend.mavlab.simulation.engine

import com.ascend.mavlab.simulation.camera.MissionCamera
import com.ascend.mavlab.simulation.coordinates.PhysicalPoseSnapshot
import com.ascend.mavlab.simulation.mission.*
import kotlin.test.*

class MissionCameraEngineTest {
    private class Camera:MissionCamera {
        var simulatedMode=false
        override val simulated get()=simulatedMode
        var blocked:String?=null
        var deferred=false
        val poses=mutableListOf<PhysicalPoseSnapshot>()
        var completion:((String?)->Unit)?=null
        override fun preflight(items:List<MissionItem>,pose:PhysicalPoseSnapshot,speed:Float)=blocked
        override fun capture(pose:PhysicalPoseSnapshot,trigger:String,completed:(String?)->Unit):MissionCamera.Admission {
            poses.add(pose);completion=completed
            if(!deferred) completed(null)
            return MissionCamera.Admission.ACCEPTED
        }
    }
    private fun setup(camera:Camera):PhysicsSimulationEngine {
        val engine=PhysicsSimulationEngine();engine.missionCamera=camera
        val s=engine.state.value
        fun nav(seq:Int,cmd:MissionCommand,n:Double)=MissionItem(seq,cmd,s.latitudeDeg+n/111_320.0,s.longitudeDeg,8f)
        engine.loadMission(listOf(nav(0,MissionCommand.TAKEOFF,0.0),
            MissionItem(1,MissionCommand.CAMERA_TRIGGER_DISTANCE,0.0,0.0,0f,
                wire=MissionWireData(2,15f,0f,1f,0f,0.0,0.0,0f)),
            nav(2,MissionCommand.WAYPOINT,65.0),
            MissionItem(3,MissionCommand.CAMERA_TRIGGER_DISTANCE,0.0,0.0,0f,
                wire=MissionWireData(2,0f,0f,1f,0f,0.0,0.0,0f)),nav(4,MissionCommand.RTL,0.0)))
        return engine
    }
    private fun until(engine:PhysicsSimulationEngine,condition:()->Boolean) {
        repeat(15000) { if(condition()) return;engine.tickForTest(.02f) }
        error("Timed out: ${engine.state.value.mode}, ${engine.missionProgress.value}")
    }
    @Test fun simulatedExposureCountsNeverClaimSavedImages() {
        val camera=Camera().apply { simulatedMode=true };val e=setup(camera);assertTrue(e.startMission())
        until(e) { e.missionProgress.value.complete && !e.state.value.armed }
        assertTrue(camera.poses.size>=3)
        assertEquals(0,e.missionProgress.value.cameraSavedImages)
        assertEquals(camera.poses.size,e.missionProgress.value.cameraSimulatedCaptures)
    }
    @Test fun blockedPreflightDoesNotArmOrTakeAuthority() {
        val camera=Camera().apply { blocked="Map unavailable" };val e=setup(camera);val before=e.state.value
        assertFalse(e.startMission());assertFalse(e.state.value.armed)
        assertEquals(before.mode,e.state.value.mode);assertEquals(before.controlAuthority,e.state.value.controlAuthority)
        assertEquals("Map unavailable",e.missionProgress.value.cameraHoldReason)
    }
    @Test fun distanceCapturesUseEnginePoseAndStopBeforeReturnAndLand() {
        val camera=Camera();val e=setup(camera);assertTrue(e.startMission())
        until(e) { e.missionProgress.value.complete && !e.state.value.armed }
        assertTrue(camera.poses.size>=3,"Actual distance photographs required: ${camera.poses.size}")
        assertTrue(camera.poses.first().altitudeAglMeters>.5)
        assertTrue(camera.poses.last().northMeters>30)
        assertEquals(camera.poses.size,e.missionProgress.value.cameraSavedImages)
        assertEquals(0,e.missionProgress.value.cameraFailedImages)
    }
    @Test fun pendingImmediateCapturePausesAndResumeDoesNotDuplicateExposure() {
        val camera=Camera().apply { deferred=true };val e=setup(camera);assertTrue(e.startMission())
        until(e) { camera.poses.isNotEmpty() }
        val index=e.missionProgress.value.currentIndex
        e.setMode(FlightMode.BRAKE,ControlAuthority.GCS_DIRECT)
        repeat(300) { e.tickForTest(.02f) }
        assertEquals(index,e.missionProgress.value.currentIndex);assertEquals(1,camera.poses.size)
        assertEquals(ControlAuthority.GCS_MISSION,e.state.value.controlAuthority)
        camera.completion!!(null)
        assertTrue(e.startMission());e.tickForTest(.02f)
        assertEquals(2,e.missionProgress.value.currentIndex);assertEquals(1,camera.poses.size)
    }
    @Test fun brakeHoldsMovingVehicleAndStopsDistanceExposures() {
        val camera=Camera();val e=setup(camera);e.startMission()
        until(e) { camera.poses.size>=2 }
        val before=e.state.value;val count=camera.poses.size;val index=e.missionProgress.value.currentIndex
        e.setMode(FlightMode.BRAKE,ControlAuthority.GCS_DIRECT)
        repeat(500) { e.tickForTest(.02f) }
        val held=e.state.value
        assertEquals(FlightMode.BRAKE,held.mode)
        assertTrue(held.groundSpeedMS<.3f,"Brake speed: ${held.groundSpeedMS}")
        assertTrue(kotlin.math.hypot(held.northMeters-before.northMeters,held.eastMeters-before.eastMeters)<3f)
        assertEquals(index,e.missionProgress.value.currentIndex);assertEquals(count,camera.poses.size)
    }
    @Test fun failedSaveHoldsButLateFailureCannotOverrideLandOrDisarm() {
        val camera=Camera().apply { deferred=true };val e=setup(camera);e.startMission()
        until(e) { camera.poses.isNotEmpty() };camera.completion!!("Disk full")
        assertEquals(FlightMode.BRAKE,e.state.value.mode);assertEquals(1,e.missionProgress.value.cameraFailedImages)
        val index=e.missionProgress.value.currentIndex
        repeat(100) { e.tickForTest(.02f) };assertEquals(index,e.missionProgress.value.currentIndex)
        e.setMode(FlightMode.LAND);camera.completion!!("late failure")
        assertEquals(FlightMode.LAND,e.state.value.mode)
        e.setArmed(false);camera.completion!!("later failure")
        assertFalse(e.state.value.armed);assertEquals(FlightMode.LAND,e.state.value.mode)
    }
}
