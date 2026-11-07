package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.coordinates.PhysicalPoseSnapshot
import com.ascend.mavlab.simulation.coordinates.SimulationCoordinateFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class EventsOnlyFeedbackTest {
    @Test fun groundLevelExposureHasValidWirePoseAndNoFileUrl() {
        val pose=PhysicalPoseSnapshot(SimulationCoordinateFrame(35.68,139.76),10.0,20.0,0.0,.4,1_000_000_000,50.0)
        val wire=CameraMessageBuilder(1).captured(100,1_700_000_000_000_000,7,true,pose,"")
        val packet=assertNotNull(MavlinkParser.parse(wire,wire.size))
        assertEquals(263,packet.messageId);assertEquals(100,packet.componentId)
        val p=ByteBuffer.wrap(packet.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(50_000,p.getInt(20));assertEquals(0,p.getInt(24))
        assertEquals(7,p.getInt(44));assertEquals(1,packet.payload[49].toInt())
        assertTrue(packet.payload.drop(50).all { it==0.toByte() })
        val q=(0..3).map { p.getFloat(28+it*4).toDouble() }
        assertEquals(1.0,q.sumOf { it*it },1e-6)
        // Camera optical-forward FRD axis points down in NED at ground level too.
        val forwardDown=2*(q[1]*q[3]-q[0]*q[2])
        assertEquals(1.0,forwardDown,1e-6)
    }
    @Test fun eventCameraIdentifiesNoImagesAndReportsNoStorage() {
        val builder=CameraMessageBuilder(1)
        val info=builder.information(100,eventsOnly=true)
        val i=assertNotNull(MavlinkParser.parse(info,info.size))
        assertTrue(String(i.payload.copyOfRange(62,94)).contains("No Images"))
        assertEquals(2,ByteBuffer.wrap(i.payload).order(ByteOrder.LITTLE_ENDIAN).getInt(20))
        val storage=builder.storage(100,0f,0f,eventsOnly=true)
        val p=assertNotNull(MavlinkParser.parse(storage,storage.size)).payload
        assertEquals(0,p[25].toInt());assertEquals(3,p[26].toInt())
        assertEquals(0f,ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getFloat(4))
    }
}
