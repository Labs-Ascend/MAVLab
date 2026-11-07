package com.ascend.mavlab.simulation.camera

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.ascend.mavlab.core.maps.OfflineMapPack
import com.ascend.mavlab.simulation.coordinates.*
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Private files, independent controller and synthetic pose; no real runtime, flight or sockets. */
class OfflineCameraDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private fun packFile():File = File.createTempFile("camera-pack-",".mavmap",app.cacheDir).also { file ->
        app.assets.open("maps/dandora.mavmap").use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun snapshot(east:Double=0.0,north:Double=0.0,heading:Double=0.0)=PhysicalPoseSnapshot(
        SimulationCoordinateFrame(-1.2425,36.9225),north,east,60.0,heading,System.nanoTime())
    @Test fun engineMissionSavesActualJpegAndReportsBoundedCaptureOutcome() = runBlocking {
        val root=File(app.cacheDir,"camera-mission-${System.nanoTime()}").apply { mkdirs() }
        val isolated=CameraTestContext(app,root)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine=com.ascend.mavlab.simulation.engine.PhysicsSimulationEngine()
        val camera=VirtualCameraController(scope) { engine.physicalPoseSnapshot() }
        try {
            assertTrue(engine.setHomeLocation(com.ascend.mavlab.simulation.engine.SimLocation("test-dandora","Dandora",-1.2425,36.9225,1805f)))
            camera.initialize(isolated);assertTrue(camera.selectMode(VirtualCameraController.Mode.MAP_PHOTOS,true));camera.enable()
            withTimeout(15_000) { camera.status.first { it.phase==VirtualCameraController.Phase.READY } }
            engine.missionCamera=camera.missionLink
            val frame=engine.physicalPoseSnapshot().frame
            fun nav(seq:Int,command:com.ascend.mavlab.simulation.mission.MissionCommand)=com.ascend.mavlab.simulation.mission.MissionItem(seq,command,frame.latitudeDeg,frame.longitudeDeg,8f)
            fun trigger(seq:Int,d:Float,now:Float)=com.ascend.mavlab.simulation.mission.MissionItem(seq,com.ascend.mavlab.simulation.mission.MissionCommand.CAMERA_TRIGGER_DISTANCE,0.0,0.0,0f,
                wire=com.ascend.mavlab.simulation.mission.MissionWireData(2,d,0f,now,0f,0.0,0.0,0f))
            engine.loadMission(listOf(nav(0,com.ascend.mavlab.simulation.mission.MissionCommand.TAKEOFF),trigger(1,25f,1f),trigger(2,0f,0f),nav(3,com.ascend.mavlab.simulation.mission.MissionCommand.RTL)))
            assertNull(engine.missionStartBlockReason())
            assertTrue(engine.startMission())
            withTimeout(15_000) {
                while(!engine.missionProgress.value.complete || engine.state.value.armed) {
                    engine.tickForTest(.02f)
                    if(camera.status.value.phase==VirtualCameraController.Phase.CAPTURING) delay(10)
                }
            }
            assertEquals(1,engine.missionProgress.value.cameraSavedImages)
            assertEquals(1,camera.status.value.saved)
            val image=File(root,"mavlab/camera").walkTopDown().single { it.extension=="jpg" }
            val bitmap=BitmapFactory.decodeFile(image.path);assertEquals(640,bitmap.width);bitmap.recycle()
            val endpoint=com.ascend.mavlab.core.mavlink.CameraMavlinkEndpoint(1,camera) { _,_ -> }
            val events=endpoint.periodic().map { checkNotNull(com.ascend.mavlab.core.mavlink.MavlinkParser.parse(it,it.size)) }
            assertEquals(1,events.count { it.messageId==263 })
            assertEquals(100,events.single { it.messageId==263 }.componentId)
            assertEquals(0,camera.drainMissionEvents().size)
            val pose=engine.physicalPoseSnapshot()
            val items=engine.missionProgress.value.items
            assertTrue(camera.missionLink.preflight(items,pose.copy(frame=SimulationCoordinateFrame(0.0,0.0)),3f)!!.contains("origin"))
            val outside=items.map { if(it.command.isNav) it.copy(localNorthMeters=1000f) else it }
            assertTrue(camera.missionLink.preflight(outside,pose,3f)!!.contains("coverage"))
            assertTrue(camera.missionLink.preflight(items,pose,20f)!!.contains("3000 ms"))
            val tooMany=items+List(1001) { trigger(it+4,25f,1f) }
            assertTrue(camera.missionLink.preflight(tooMany,pose,3f)!!.contains("budget"))
            camera.disable();assertNotNull(camera.missionLink.preflight(items,pose,3f))
        } finally { engine.stop();camera.disable();scope.cancel();isolated.close();root.deleteRecursively() }
    }

    @Test fun realPackRendersAcrossTilesRotationsAndEvictionsWithinBudget() {
        val file=packFile()
        try { OfflineMapPack(file).use { map ->
            map.verifyTiles()
            for (north in listOf(-330.0,-150.0,0.0,150.0,330.0)) for(east in listOf(-330.0,0.0,330.0)) {
                for(heading in listOf(0.0,Math.PI/2,0.73)) {
                    val bitmap=map.render(map.camera(snapshot(east,north,heading)))
                    assertEquals(640,bitmap.width);assertEquals(480,bitmap.height)
                    assertTrue(bitmap.getPixel(10,10)!=0)
                    assertTrue(map.cachedBytes<=4*1024*1024)
                    bitmap.recycle()
                }
            }
            try { map.render(map.camera(snapshot(440.0)));fail("Edge footprint must fail") } catch(_:IllegalArgumentException) { }
            try { map.camera(snapshot().copy(frame=SimulationCoordinateFrame(0.0,0.0)));fail("Origin mismatch must fail") } catch(_:IllegalArgumentException) { }
        } } finally { file.delete() }
    }
    @Test fun independentQuadrantReferenceProvesNorthUpAndClockwiseHeading() {
        val file=packFile()
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(file.path,null,android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.execSQL("DELETE FROM tiles")
                val changes=mapOf("width" to "512","height" to "512","levels" to "1","west" to "-128",
                    "north" to "128","valid_west" to "-120","valid_east" to "120","valid_south" to "-120","valid_north" to "120","codec" to "\"png\"")
                for((key,value) in changes) db.execSQL("UPDATE metadata SET value=? WHERE key=?",arrayOf(value,key))
                val colors=listOf(android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.BLUE,android.graphics.Color.YELLOW)
                for(row in 0..1) for(col in 0..1) {
                    val tile=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
                    tile.eraseColor(colors[row*2+col])
                    val bytes=java.io.ByteArrayOutputStream().use { output -> tile.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);output.toByteArray() }
                    tile.recycle();db.execSQL("INSERT INTO tiles VALUES(0,?,?,?)",arrayOf(col,row,bytes))
                }
            }
            OfflineMapPack(file).use { map ->
                val yaw0=map.render(map.camera(snapshot()))
                assertEquals(android.graphics.Color.RED,yaw0.getPixel(100,100))
                assertEquals(android.graphics.Color.GREEN,yaw0.getPixel(540,100))
                assertEquals(android.graphics.Color.BLUE,yaw0.getPixel(100,350))
                assertEquals(android.graphics.Color.YELLOW,yaw0.getPixel(540,350));yaw0.recycle()
                val yaw90=map.render(map.camera(snapshot(heading=Math.PI/2)))
                assertEquals(android.graphics.Color.GREEN,yaw90.getPixel(100,100))
                assertEquals(android.graphics.Color.YELLOW,yaw90.getPixel(540,100))
                assertEquals(android.graphics.Color.RED,yaw90.getPixel(100,350))
                assertEquals(android.graphics.Color.BLUE,yaw90.getPixel(540,350));yaw90.recycle()
            }
        } finally { file.delete() }
    }
    @Test fun stopRestartCancelsQueuedPoseWithoutPublishingIntoNewSession() = runBlocking {
        val root=File(app.cacheDir,"camera-generation-${System.nanoTime()}").apply { mkdirs() }
        val isolated=CameraTestContext(app,root)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val camera=VirtualCameraController(scope) { snapshot() }
        try {
            camera.initialize(isolated);assertTrue(camera.selectMode(VirtualCameraController.Mode.MAP_PHOTOS,true));camera.enable()
            withTimeout(15_000) { camera.status.first { it.phase==VirtualCameraController.Phase.READY } }
            val mutex=VirtualCameraController::class.java.getDeclaredField("ioMutex").apply { isAccessible=true }.get(camera) as kotlinx.coroutines.sync.Mutex
            mutex.lock()
            val cancelled=CompletableDeferred<VirtualCameraController.Result>()
            try {
                assertEquals(VirtualCameraController.Admission.ACCEPTED,camera.requestCapture("cancel before IO") { cancelled.complete(it) })
                camera.disable();camera.enable()
            } finally { mutex.unlock() }
            assertFalse(withTimeout(15_000) { cancelled.await() }.success)
            withTimeout(15_000) { camera.status.first { it.phase==VirtualCameraController.Phase.READY } }
            val completed=CompletableDeferred<VirtualCameraController.Result>()
            assertEquals(VirtualCameraController.Admission.ACCEPTED,camera.requestCapture("new generation") { completed.complete(it) })
            assertTrue(withTimeout(15_000) { completed.await() }.success)
            assertEquals(1,camera.status.value.saved)
            assertEquals(1,File(root,"mavlab/camera").walkTopDown().count { it.extension=="jpg" })
            val failures=File(root,"mavlab/camera").walkTopDown().filter { it.name.startsWith("failed-") && it.extension=="json" }.toList()
            assertEquals(1,failures.size)
            assertFalse(JSONObject(failures.single().readText()).getBoolean("success"))
            assertTrue(failures.single().parentFile!!.name!=camera.status.value.sessionId)
        } finally { camera.disable();scope.cancel();isolated.close();root.deleteRecursively() }
    }

    @Test fun lowStorageAndBadImportFailVisiblyAndKeepPreviousPack() = runBlocking {
        val root=File(app.cacheDir,"camera-failure-${System.nanoTime()}").apply { mkdirs() }
        val emptySpace=object:File(root.path) { override fun getUsableSpace():Long=0 }
        val isolated=CameraTestContext(app,emptySpace)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val camera=VirtualCameraController(scope) { snapshot() }
        try {
            camera.initialize(isolated);assertTrue(camera.selectMode(VirtualCameraController.Mode.MAP_PHOTOS,true));camera.enable()
            withTimeout(15_000) { camera.status.first { it.phase==VirtualCameraController.Phase.READY } }
            val failed=CompletableDeferred<VirtualCameraController.Result>()
            assertEquals(VirtualCameraController.Admission.ACCEPTED,camera.requestCapture("low storage") { failed.complete(it) })
            val result=withTimeout(15_000) { failed.await() }
            assertFalse(result.success);assertTrue(result.error!!.contains("Low storage"))
            assertEquals(VirtualCameraController.Phase.ERROR,camera.status.value.phase)
            assertEquals(1,camera.status.value.failed);assertEquals(0,camera.status.value.saved)
            assertEquals(0,File(root,"mavlab/camera").walkTopDown().count { it.extension=="jpg" })
            camera.disable()
            val active=File(root,"mavlab/maps/active.mavmap");val previous=active.readBytes()
            val bad=File(root,"bad.mavmap").apply { writeBytes(byteArrayOf(1,2,3)) }
            camera.importPack(Uri.fromFile(bad),true)
            withTimeout(15_000) { camera.status.first { !it.exportBusy } }
            assertArrayEquals(previous,active.readBytes())
            val zip=File(root,"failure-export.zip")
            camera.exportLatest(Uri.fromFile(zip))
            withTimeout(15_000) { camera.status.first { !it.exportBusy } }
            ZipFile(zip).use { archive ->
                assertEquals(2,archive.size())
                assertTrue(archive.entries().asSequence().any { it.name.startsWith("failed-") })
            }
        } finally { camera.disable();scope.cancel();isolated.close();root.deleteRecursively() }
    }
    @Test fun actualJpegCommittedWithLedgerAndExportedWithoutFlightRecording() = runBlocking {
        // Test controller uses a separate ContextWrapper files directory, preserving all app sessions.
        val root=File(app.cacheDir,"camera-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated=CameraTestContext(app,root)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val camera=VirtualCameraController(scope) { snapshot() }
        try {
            camera.initialize(isolated);assertTrue(camera.selectMode(VirtualCameraController.Mode.MAP_PHOTOS,true));camera.enable()
            withTimeout(15_000) { camera.status.first { it.phase==VirtualCameraController.Phase.READY || it.phase==VirtualCameraController.Phase.ERROR } }
            assertEquals(camera.status.value.message,VirtualCameraController.Phase.READY,camera.status.value.phase)
            val completion=CompletableDeferred<VirtualCameraController.Result>()
            assertEquals(VirtualCameraController.Admission.ACCEPTED,camera.requestCapture("instrumentation") { completion.complete(it) })
            assertEquals(VirtualCameraController.Admission.BUSY,camera.requestCapture("too soon"))
            val result=withTimeout(15_000) { completion.await() }
            assertTrue(result.error,result.success)
            val image=result.image!!
            println("MAP_RESOURCE captures=1 image_bytes=${image.length()} decoded_cache_bytes=${camera.status.value.cachedBytes}")
            val bitmap=BitmapFactory.decodeFile(image.path)
            assertNotNull(bitmap);assertEquals(640,bitmap.width);assertEquals(480,bitmap.height);bitmap.recycle()
            image.copyTo(File(app.cacheDir,"mavlab-validation-virtual-photo.jpg"),overwrite=true)
            val entry=JSONObject(File(image.parentFile,image.nameWithoutExtension+".json").readText())
            assertEquals(image.length(),entry.getLong("bytes"));assertTrue(entry.getBoolean("virtual"))
            assertFalse(File(root,"mavlab/flights").exists())
            camera.disable()
            File(image.parentFile,"999999.jpg").writeBytes(byteArrayOf(1,2,3)) // unfinished image has no commit marker
            File(image.parentFile,"999998.jpg.tmp").writeBytes(byteArrayOf(1,2,3))
            val zip=File(root,"export.zip")
            camera.exportLatest(Uri.fromFile(zip))
            withTimeout(15_000) { camera.status.first { !it.exportBusy } }
            assertTrue(camera.status.value.message,zip.isFile)
            ZipFile(zip).use { archive ->
                assertEquals(3,archive.size())
                assertNotNull(archive.getEntry("manifest.json"));assertNotNull(archive.getEntry(image.name))
                assertEquals(image.length(),archive.getEntry(image.name).size)
            }
            zip.copyTo(File(app.cacheDir,"mavlab-validation-virtual-captures.zip"),overwrite=true)
            assertEquals(VirtualCameraController.Admission.UNAVAILABLE,camera.requestCapture("off"))
        } finally { camera.disable();scope.cancel();isolated.close();root.deleteRecursively() }
    }
}
