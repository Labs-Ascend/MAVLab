package com.ascend.mavlab.simulation.camera

import androidx.test.platform.app.InstrumentationRegistry
import com.ascend.mavlab.core.mavlink.*
import com.ascend.mavlab.simulation.coordinates.*
import com.ascend.mavlab.simulation.engine.*
import com.ascend.mavlab.simulation.mission.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Events-only tests forbid map-asset access and keep all user files/preferences separate. */
class EventsOnlyCameraDeviceTest {
    private fun withCamera(block:suspend (VirtualCameraController,PhysicsSimulationEngine,File,CameraTestContext,CoroutineScope)->Unit)=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"events-camera-${System.nanoTime()}").apply { mkdirs() }
        val context=CameraTestContext(app,root,forbidAssets=true)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine=PhysicsSimulationEngine()
        val camera=VirtualCameraController(scope,engine::physicalPoseSnapshot)
        try {
            camera.initialize(context);assertEquals(VirtualCameraController.Mode.EVENTS_ONLY,camera.mode)
            camera.enable();withTimeout(5000) { camera.status.first { it.phase==VirtualCameraController.Phase.READY } }
            engine.missionCamera=camera.missionLink
            block(camera,engine,root,context,scope)
        } finally { engine.stop();camera.disable();scope.cancel();context.close();root.deleteRecursively() }
    }
    @Test fun noMapNoImageWritesAndMissionOneHasNoDandoraCoverageGate()=withCamera { camera,engine,root,_,_ ->
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("mission-1-items.bin").use { it.readBytes() }
        val count=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        engine.loadMission((0 until count).map { seq -> checkNotNull(MissionUploadSession.parseMissionItemInt(MavlinkPacket(73,255,190,bytes.copyOfRange(2+37*seq,2+37*(seq+1))))) })
        assertNull(engine.missionStartBlockReason()) // 831 items / >12k planned events, away from Dandora
        assertFalse(engine.state.value.armed)
        val start=System.nanoTime()
        repeat(20) { i ->
            val completion=CompletableDeferred<String?>()
            val frame=if(i%2==0) SimulationCoordinateFrame(-1.2921,36.8219) else SimulationCoordinateFrame(35.68,139.76)
            val pose=PhysicalPoseSnapshot(frame,10.0,20.0,0.0,.4,System.nanoTime(),50.0)
            assertEquals(MissionCamera.Admission.ACCEPTED,camera.missionLink.capture(pose,"resource check") { completion.complete(it) })
            assertNull(withTimeout(3000) { completion.await() })
            if(i<19) delay(260)
        }
        assertEquals(20,camera.status.value.simulated);assertEquals(20,camera.captureCount)
        assertEquals(0,camera.status.value.saved);assertEquals(0,camera.status.value.cachedBytes)
        assertNull(camera.status.value.latestImage);assertNull(camera.status.value.sessionId)
        assertFalse(root.walkTopDown().any { it.isFile })
        val feedback=camera.drainMissionEvents();assertEquals(20,feedback.size)
        assertTrue(feedback.all { it.simulated && it.image==null })
        println("EVENTS_RESOURCE captures=20 files=0 decoded_cache_bytes=0 elapsed_ms=${(System.nanoTime()-start)/1_000_000}")
    }
    @Test fun isolatedEngineMissionCountsSimulationsAndNeverSavedJpegs()=withCamera { camera,engine,root,_,_ ->
        val s=engine.state.value
        fun nav(seq:Int,cmd:MissionCommand)=MissionItem(seq,cmd,s.latitudeDeg,s.longitudeDeg,8f)
        fun trigger(seq:Int,d:Float,now:Float)=MissionItem(seq,MissionCommand.CAMERA_TRIGGER_DISTANCE,0.0,0.0,0f,wire=MissionWireData(2,d,0f,now,0f,0.0,0.0,0f))
        engine.loadMission(listOf(nav(0,MissionCommand.TAKEOFF),trigger(1,25f,1f),trigger(2,0f,0f),nav(3,MissionCommand.RTL)))
        assertTrue(engine.startMission())
        withTimeout(10_000) {
            while(!engine.missionProgress.value.complete || engine.state.value.armed) {
                engine.tickForTest(.02f)
                if(camera.status.value.phase==VirtualCameraController.Phase.CAPTURING) delay(2)
            }
        }
        assertEquals(1,engine.missionProgress.value.cameraSimulatedCaptures)
        assertEquals(0,engine.missionProgress.value.cameraSavedImages)
        assertFalse(root.walkTopDown().any { it.isFile })
    }
    @Test fun eventsProtocolHasNamedCameraEmptyUrlReplayAndNoImageStorage()=withCamera { camera,engine,root,_,_ ->
        val messages=LinkedBlockingQueue<ByteArray>(128)
        val endpoint=CameraMavlinkEndpoint(1,camera) { data,_ -> check(messages.offer(data)) }
        fun command(id:Int,params:List<Float>,confirmation:Int=0) {
            val b=ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
            repeat(7) { b.putFloat(params.getOrElse(it) { 0f }) }
            b.putShort(id.toShort()).put(1).put(100).put(confirmation.toByte())
            assertTrue(endpoint.handle(MavlinkPacket(76,255,190,b.array()),null))
        }
        suspend fun next(id:Int):MavlinkPacket = withTimeout(3000) {
            while(true) {
                val data=messages.poll()
                if(data==null) { delay(2);continue }
                val packet=checkNotNull(MavlinkParser.parse(data,data.size))
                if(packet.messageId==id) return@withTimeout packet
            }
            error("No message")
        }
        command(512,listOf(259f));assertEquals(0,next(77).payload[2].toInt())
        val info=next(259);assertEquals(100,info.componentId)
        assertTrue(String(info.payload.copyOfRange(62,94)).contains("Events Only"))
        command(512,listOf(261f));next(77)
        val storage=next(261);assertEquals(0,storage.payload[25].toInt());assertEquals(3,storage.payload[26].toInt())
        command(2000,listOf(0f,0f,1f,7f));assertEquals(0,next(77).payload[2].toInt())
        val exposure=next(263);assertEquals(1,exposure.payload[49].toInt());assertTrue(exposure.payload.drop(50).all { it==0.toByte() })
        command(2000,listOf(0f,0f,1f,7f),1);assertEquals(0,next(77).payload[2].toInt());assertArrayEquals(exposure.payload,next(263).payload)
        assertEquals(1,camera.captureCount)
        command(2000,listOf(0f,0f,1f,8f));assertEquals(1,next(77).payload[2].toInt()) // bounded 250 ms cadence
        assertFalse(engine.state.value.armed);assertEquals(ControlAuthority.IDLE,engine.state.value.controlAuthority)
        assertFalse(root.walkTopDown().any { it.isFile });endpoint.stop()
    }
    @Test fun canceledGenerationCannotPublishIntoNewModeAndSelectionPersists()=withCamera { camera,_,_,context,scope ->
        assertFalse(camera.selectMode(VirtualCameraController.Mode.MAP_PHOTOS,true))
        val mutex=VirtualCameraController::class.java.getDeclaredField("ioMutex").apply { isAccessible=true }.get(camera) as kotlinx.coroutines.sync.Mutex
        mutex.lock();val completed=CompletableDeferred<String?>()
        try {
            val pose=PhysicalPoseSnapshot(SimulationCoordinateFrame(0.0,0.0),0.0,0.0,1.0,0.0,System.nanoTime())
            assertEquals(MissionCamera.Admission.ACCEPTED,camera.missionLink.capture(pose,"cancel") { completed.complete(it) })
            camera.disable();camera.enable()
        } finally { mutex.unlock() }
        assertNotNull(withTimeout(3000) { completed.await() })
        withTimeout(3000) { camera.status.first { it.phase==VirtualCameraController.Phase.READY } }
        assertTrue(camera.drainMissionEvents().isEmpty());assertEquals(0,camera.captureCount)
        camera.disable()
        assertFalse(camera.selectMode(VirtualCameraController.Mode.MAP_PHOTOS,false))
        assertTrue(camera.selectMode(VirtualCameraController.Mode.MAP_PHOTOS,true))
        val restored=VirtualCameraController(scope) { error("No capture expected") }
        restored.initialize(context);assertEquals(VirtualCameraController.Mode.MAP_PHOTOS,restored.mode)
        assertEquals(VirtualCameraController.Phase.OFF,restored.status.value.phase)
    }
}
