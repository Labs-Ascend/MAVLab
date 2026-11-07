package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.camera.*
import com.ascend.mavlab.simulation.coordinates.PhysicalPoseSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Wire offsets/CRC pinned to c_library_v2 085c8344fa721a3d80bf574ae77c4d873f4985f3. */
internal class CameraMessageBuilder(private val systemId:Int) {
    private var sequence=0
    private val profile=VirtualCameraProfile()
    @Synchronized private fun frame(id:Int,b:ByteBuffer)=MavlinkWire.frame(id,b.array(),sequence++,systemId,100)
    private fun le(size:Int)=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun heartbeat()=frame(0,le(9).putInt(0).put(30).put(8).put(0).put(4).put(3))
    fun information(bootMs:Int,eventsOnly:Boolean=false):ByteArray {
        val b=le(237).putInt(bootMs).putInt(1).putFloat(profile.focalLengthMm.toFloat())
            .putFloat(profile.sensorWidthMm.toFloat()).putFloat(profile.sensorHeightMm.toFloat())
            .putInt(2) // CAMERA_CAP_FLAGS_CAPTURE_IMAGE only
            .putShort(profile.width.toShort()).putShort(profile.height.toShort()).putShort(0)
        "Ascend Labs".toByteArray().copyInto(b.array(),30)
        (if(eventsOnly) "MAVLab Events Only - No Images" else "MAVLab Virtual Map Camera").toByteArray().copyInto(b.array(),62)
        return frame(259,b)
    }
    fun settings(bootMs:Int)=frame(260,le(14).putInt(bootMs).put(0).putFloat(Float.NaN).putFloat(Float.NaN).put(0))
    fun storage(bootMs:Int,totalMiB:Float,freeMiB:Float,eventsOnly:Boolean=false)=frame(261,le(61).apply {
        putInt(bootMs);putFloat(totalMiB);putFloat((totalMiB-freeMiB).coerceAtLeast(0f));putFloat(freeMiB)
        putFloat(0f);putFloat(0f);put(if(eventsOnly) 0 else 1);put(if(eventsOnly) 0 else 1);put(if(eventsOnly) 3 else 2);put(0) // ready; unspecified storage type
        (if(eventsOnly) "No image storage" else "Phone internal storage").toByteArray().copyInto(array(),28)
    })
    fun captureStatus(bootMs:Int,busy:Boolean,imageCount:Int,freeMiB:Float)=frame(262,le(23).putInt(bootMs).putFloat(0f)
        .putInt(0).putFloat(freeMiB).put(if(busy) 1.toByte() else 0.toByte()).put(0).putInt(imageCount).put(0))
    fun captured(bootMs:Int,utcMicros:Long,index:Int,success:Boolean,snapshot:PhysicalPoseSnapshot,path:String):ByteArray {
        val b=le(255).putLong(utcMicros).putInt(bootMs)
            .putInt((snapshot.frame.latitude(snapshot.northMeters)*1e7).roundToInt())
            .putInt((snapshot.frame.longitude(snapshot.eastMeters)*1e7).roundToInt())
            .putInt(((snapshot.groundAltitudeMslMeters+snapshot.altitudeAglMeters)*1000).roundToInt())
            .putInt((snapshot.altitudeAglMeters*1000).roundToInt())
        nadirCameraQuaternionNed(snapshot.yawRadians).forEach { b.putFloat(it) }
        b.putInt(index).put(0).put(if(success) 1.toByte() else 0.toByte())
        path.toByteArray(Charsets.UTF_8).take(204).toByteArray().copyInto(b.array(),50)
        return frame(263,b)
    }
    fun ack(command:Int,result:Int,request:MavlinkPacket)=frame(77,le(10).putShort(command.toShort()).put(result.toByte()).put(0).putInt(0)
        .put(request.systemId.toByte()).put(request.componentId.toByte()))
}
