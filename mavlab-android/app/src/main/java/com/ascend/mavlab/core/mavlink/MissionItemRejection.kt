package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.mission.MissionCommand

internal data class MissionItemRejection(val ack: Int, val reason: String)

/** Values follow MAVLink MAV_FRAME and MAV_MISSION_RESULT; reject unsupported units before loading. */
internal fun missionItemRejection(packet: MavlinkPacket): MissionItemRejection? {
    if (packet.payload.size < 37) return MissionItemRejection(5, "Invalid mission item: truncated payload")
    fun unsignedShort(offset: Int) = (packet.payload[offset].toInt() and 0xff) or ((packet.payload[offset + 1].toInt() and 0xff) shl 8)
    val sequence = unsignedShort(28)
    val commandId = unsignedShort(30)
    val command = MissionCommand.entries.firstOrNull { it.mavCmdId == commandId }
        ?: return MissionItemRejection(3, "Unsupported command $commandId at sequence $sequence")
    val frame = packet.payload[34].toInt() and 0xff
    val frameValid = frame in listOf(0, 3, 5, 6) || frame == 2 && (command.isDo || command == MissionCommand.RTL)
    if(command==MissionCommand.CAMERA_TRIGGER_DISTANCE) {
        val b=java.nio.ByteBuffer.wrap(packet.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val distance=b.getFloat(0);val immediate=b.getFloat(8)
        if(!distance.isFinite() || distance !in 0f..50_000f || b.getFloat(4) !in listOf(-1f,0f) || immediate !in listOf(0f,1f) ||
            (b.getFloat(12) !in listOf(0f,100f) && !b.getFloat(12).isNaN()) || (b.getFloat(24)!=0f && !b.getFloat(24).isNaN()))
            return MissionItemRejection(6,"Unsupported distance-camera parameters at sequence $sequence")
        // Reserved x/y may be zero or NaN/INT_MAX, never silently reinterpret a position.
        for(offset in listOf(16,20)) {
            val valid=if(packet.messageId==73) b.getInt(offset) in listOf(0,Int.MAX_VALUE)
                else b.getFloat(offset)==0f || b.getFloat(offset).isNaN()
            if(!valid) return MissionItemRejection(6,"Reserved camera coordinate at sequence $sequence")
        }
    }
    return if (frameValid) null else MissionItemRejection(2, "Unsupported frame $frame for command $commandId at sequence $sequence")
}
