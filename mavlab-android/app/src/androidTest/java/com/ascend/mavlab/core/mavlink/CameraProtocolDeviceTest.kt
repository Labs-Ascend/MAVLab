package com.ascend.mavlab.core.mavlink

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.ascend.mavlab.simulation.camera.VirtualCameraController
import com.ascend.mavlab.simulation.coordinates.*
import com.ascend.mavlab.simulation.engine.PhysicsSimulationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Isolated camera/runtime/ports and private cache files. Never talks to the user's QGC session. */
class CameraProtocolDeviceTest {
    @Test fun cameraDiscoveryShutterRetryAndRoutingHaveRealEffects() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"camera-wire-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=root
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val camera=VirtualCameraController(scope) { PhysicalPoseSnapshot(SimulationCoordinateFrame(-1.2425,36.9225),0.0,0.0,60.0,0.0,System.nanoTime(),1805.0) }
        val engine=PhysicsSimulationEngine()
        val server=MavlinkUdpServer(engine,MavlinkSocketConfig(localBindPort=0),camera=camera)
        val address=InetAddress.getByName("127.0.0.1")
        val socket=DatagramSocket(0,address)
        val peer=DatagramSocket(0,address).apply { soTimeout=3000 }
        val field=MavlinkUdpServer::class.java.getDeclaredField("socket").apply { isAccessible=true };field.set(server,socket)
        val receive=MavlinkUdpServer::class.java.getDeclaredMethod("handleInbound",ByteArray::class.java,Int::class.javaPrimitiveType,UdpDestination::class.java).apply { isAccessible=true }
        val destination=UdpDestination("127.0.0.1",peer.localPort)
        var sequence=0
        fun command(id:Int,params:List<Float>,component:Int=100,confirmation:Int=0) {
            val payload=ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
            for(i in 0..6) payload.putFloat(params.getOrElse(i){0f})
            payload.putShort(id.toShort()).put(1).put(component.toByte()).put(confirmation.toByte())
            val data=MavlinkWire.frame(76,payload.array(),sequence++,255,190)
            receive.invoke(server,data,data.size,destination)
        }
        fun message(id:Int):MavlinkPacket {
            repeat(30) {
                val data=ByteArray(512);val packet=DatagramPacket(data,data.size);peer.receive(packet)
                val decoded=MavlinkParser.parse(data,packet.length)
                if(decoded?.messageId==id) return decoded
            }
            error("No message $id")
        }
        try {
            camera.initialize(isolated);camera.enable()
            withTimeout(15_000) { camera.status.first { it.phase==VirtualCameraController.Phase.READY || it.phase==VirtualCameraController.Phase.ERROR } }
            assertEquals(camera.status.value.message,VirtualCameraController.Phase.READY,camera.status.value.phase)
            command(512,listOf(259f))
            val ack=message(77);assertEquals(100,ack.componentId);assertEquals(0,ack.payload[2].toInt())
            assertEquals(255,ack.payload[8].toInt() and 255);assertEquals(190,ack.payload[9].toInt() and 255)
            val info=ByteBuffer.wrap(message(259).payload).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2,info.getInt(20));assertEquals(640,info.getShort(24).toInt());assertEquals(480,info.getShort(26).toInt())
            val before=engine.state.value
            command(400,listOf(1f)) // arm addressed to camera must never become an autopilot command
            assertEquals(3,message(77).payload[2].toInt());assertFalse(engine.state.value.armed)
            command(2000,listOf(0f,0f,1f,1f))
            assertEquals(0,message(77).payload[2].toInt())
            val captured=message(263)
            assertEquals(100,captured.componentId);assertEquals(1,captured.payload[49].toInt())
            val index=ByteBuffer.wrap(captured.payload).order(ByteOrder.LITTLE_ENDIAN).getInt(44)
            assertEquals(0,index)
            withTimeout(5000) { camera.status.first { it.saved==1 } }
            assertTrue(File(camera.status.value.latestImage!!).length()>1000)
            command(2000,listOf(0f,0f,1f,1f),confirmation=1)
            assertEquals(0,message(77).payload[2].toInt());assertArrayEquals(captured.payload,message(263).payload)
            assertEquals(1,camera.status.value.saved)
            assertEquals(1,File(root,"mavlab/camera").walkTopDown().count { it.extension=="jpg" })
            command(2000,listOf(0f,0f,1f,2f))
            assertEquals(1,message(77).payload[2].toInt()) // bounded cadence, no second exposure
            command(2000,listOf(0f,3f,0f,0f))
            assertEquals(3,message(77).payload[2].toInt()) // no pretend interval capture
            delay(VirtualCameraController.MinimumIntervalMs+100)
            command(2000,listOf(0f,0f,1f)) // exact zero-sequence single-shot path used by pinned QGC
            assertEquals(0,message(77).payload[2].toInt())
            val legacy=message(263)
            assertEquals(1,ByteBuffer.wrap(legacy.payload).order(ByteOrder.LITTLE_ENDIAN).getInt(44))
            command(2000,listOf(0f,0f,1f),confirmation=1)
            assertEquals(0,message(77).payload[2].toInt());assertArrayEquals(legacy.payload,message(263).payload)
            assertEquals(2,camera.status.value.saved)
            command(2000,listOf(0f,0f,1f)) // fresh zero-sequence press, too soon
            assertEquals(1,message(77).payload[2].toInt())
            command(2000,listOf(0f,0f,1f),confirmation=1) // must not replay an older accepted press
            assertEquals(1,message(77).payload[2].toInt())
            assertEquals(before.mode,engine.state.value.mode);assertEquals(before.controlAuthority,engine.state.value.controlAuthority)
            assertFalse(File(root,"mavlab/flights").exists())
            camera.disable()
            command(2000,listOf(0f,0f,1f,3f))
            assertEquals(1,message(77).payload[2].toInt())
        } finally { server.stopNow();camera.disable();scope.cancel();peer.close();socket.close();root.deleteRecursively() }
    }
}
