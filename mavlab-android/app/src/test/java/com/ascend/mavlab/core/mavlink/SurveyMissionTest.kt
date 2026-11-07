package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.mission.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class SurveyMissionTest {
    @Test fun actualMissionOneUploadsEveryItemAndPreservesCameraWireFields() {
        val bytes=checkNotNull(javaClass.getResourceAsStream("/mission-1-items.bin")).use { it.readBytes() }
        val count=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertEquals(831,count)
        val session=MissionUploadSession(count,UdpDestination("127.0.0.1"))
        val items=(0 until count).map { sequence ->
            val packet=MavlinkPacket(73,255,190,bytes.copyOfRange(2+37*sequence,2+37*(sequence+1)))
            assertNull(missionItemRejection(packet),"sequence $sequence")
            val item=assertNotNull(MissionUploadSession.parseMissionItemInt(packet))
            val result=session.receive(item)
            if(sequence==count-1) assertEquals(count,assertIs<MissionUploadResult.Complete>(result).items.size)
            else assertEquals(sequence+1,assertIs<MissionUploadResult.RequestNext>(result).sequence)
            item
        }
        val restored=MissionSnapshotCodec.decode(MissionSnapshotCodec.encode(items))
        assertEquals(items,restored)
        val cameras=restored.filter { it.command==MissionCommand.CAMERA_TRIGGER_DISTANCE }
        assertEquals(167,cameras.size)
        assertEquals(25f,cameras.first().wire!!.p1)
        assertEquals(1f,cameras.first().wire!!.p3)
        assertEquals(0f,cameras.last().wire!!.p1)
        assertEquals(2,cameras.first().wire!!.frame)
        val builder=MavlinkMessageBuilder(1,1)
        val engine=com.ascend.mavlab.simulation.engine.PhysicsSimulationEngine()
        engine.loadMission(restored)
        for(item in engine.missionProgress.value.items) {
            val data=builder.missionItemInt(item,0,255,190)
            val downloaded=checkNotNull(MavlinkParser.parse(data,data.size))
            val back=assertNotNull(MissionUploadSession.parseMissionItemInt(downloaded))
            assertEquals(item.wire,back.wire)
        }
    }

    @Test fun boundedCountAccepts1000AndRejects1001() {
        fun count(n:Int)=MavlinkPacket(44,255,190,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(n.toShort()).put(1).put(1).array())
        for(n in listOf(0,100,101,1000)) assertNotNull(MissionUploadSession.parseMissionCount(count(n),UdpDestination("127.0.0.1")))
        assertNull(MissionUploadSession.parseMissionCount(count(1001),UdpDestination("127.0.0.1")))
    }

    @Test fun cameraRejectsUnimplementedShutterOptionsAndRetainsLegalNaNs() {
        fun packet(shutter:Float)=MavlinkPacket(73,255,190,ByteBuffer.allocate(37).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(25f).putFloat(shutter).putFloat(1f).putFloat(Float.NaN)
            .putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).putFloat(Float.NaN)
            .putShort(2).putShort(206).put(1).put(1).put(2).put(0).put(1).array())
        assertEquals(6,missionItemRejection(packet(1f))?.ack)
        val item=assertNotNull(MissionUploadSession.parseMissionItemInt(packet(0f)))
        val restored=MissionSnapshotCodec.decode(MissionSnapshotCodec.encode(listOf(item))).single()
        assertEquals(item.wire,restored.wire)
        assertTrue(restored.wire!!.x.isNaN())
        assertTrue(restored.wire!!.p4.isNaN())
    }
}
