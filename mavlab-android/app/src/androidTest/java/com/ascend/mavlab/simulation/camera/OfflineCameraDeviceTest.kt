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
        val isolated=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():android.content.Context=this
            override fun getFilesDir():File=root
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val camera=VirtualCameraController(scope) { snapshot() }
        try {
            camera.initialize(isolated);camera.enable()
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
        } finally { camera.disable();scope.cancel();root.deleteRecursively() }
    }

    @Test fun lowStorageAndBadImportFailVisiblyAndKeepPreviousPack() = runBlocking {
        val root=File(app.cacheDir,"camera-failure-${System.nanoTime()}").apply { mkdirs() }
        val emptySpace=object:File(root.path) { override fun getUsableSpace():Long=0 }
        val isolated=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():android.content.Context=this
            override fun getFilesDir():File=emptySpace
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val camera=VirtualCameraController(scope) { snapshot() }
        try {
            camera.initialize(isolated);camera.enable()
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
        } finally { camera.disable();scope.cancel();root.deleteRecursively() }
    }
    @Test fun actualJpegCommittedWithLedgerAndExportedWithoutFlightRecording() = runBlocking {
        // Test controller uses a separate ContextWrapper files directory, preserving all app sessions.
        val root=File(app.cacheDir,"camera-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():android.content.Context=this
            override fun getFilesDir():File=root
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val camera=VirtualCameraController(scope) { snapshot() }
        try {
            camera.initialize(isolated);camera.enable()
            withTimeout(15_000) { camera.status.first { it.phase==VirtualCameraController.Phase.READY || it.phase==VirtualCameraController.Phase.ERROR } }
            assertEquals(camera.status.value.message,VirtualCameraController.Phase.READY,camera.status.value.phase)
            val completion=CompletableDeferred<VirtualCameraController.Result>()
            assertEquals(VirtualCameraController.Admission.ACCEPTED,camera.requestCapture("instrumentation") { completion.complete(it) })
            assertEquals(VirtualCameraController.Admission.BUSY,camera.requestCapture("too soon"))
            val result=withTimeout(15_000) { completion.await() }
            assertTrue(result.error,result.success)
            val image=result.image!!
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
        } finally { camera.disable();scope.cancel();root.deleteRecursively() }
    }
}
