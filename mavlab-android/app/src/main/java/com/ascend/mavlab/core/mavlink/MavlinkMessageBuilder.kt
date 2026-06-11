package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.engine.DroneState
import com.ascend.mavlab.simulation.mission.MissionItem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class MavlinkMessageBuilder(
    private val systemId: Int,
    private val componentId: Int,
) {
    private var sequence = 0

    fun heartbeat(state: DroneState): ByteArray {
        val baseMode = MAV_MODE_FLAG_CUSTOM_MODE_ENABLED or
            MAV_MODE_FLAG_STABILIZE_ENABLED or
            MAV_MODE_FLAG_GUIDED_ENABLED or
            MAV_MODE_FLAG_AUTO_ENABLED or
            MAV_MODE_FLAG_MANUAL_INPUT_ENABLED or
            if (state.armed) MAV_MODE_FLAG_SAFETY_ARMED else 0
        val payload = littleEndian(9)
            .putInt(state.mode.customMode.toInt())
            .putU8(MAV_TYPE_QUADROTOR)
            .putU8(MAV_AUTOPILOT_ARDUPILOTMEGA)
            .putU8(baseMode)
            .putU8(if (state.armed) MAV_STATE_ACTIVE else MAV_STATE_STANDBY)
            .putU8(3)
            .array()
        return frame(messageId = 0, crcExtra = 50, payload = payload)
    }

    fun attitude(state: DroneState): ByteArray {
        val payload = littleEndian(28)
            .putInt(state.uptimeMs.toInt())
            .putFloat(state.rollRadians)
            .putFloat(state.pitchRadians)
            .putFloat(state.yawRadians)
            .putFloat(state.rollSpeedRadS)
            .putFloat(state.pitchSpeedRadS)
            .putFloat(state.yawSpeedRadS)
            .array()
        return frame(messageId = 30, crcExtra = 39, payload = payload)
    }

    fun globalPosition(state: DroneState): ByteArray {
        val payload = littleEndian(28)
            .putInt(state.uptimeMs.toInt())
            .putInt((state.latitudeDeg * 1e7).roundToInt())
            .putInt((state.longitudeDeg * 1e7).roundToInt())
            .putInt((state.altitudeMslMeters * 1000).roundToInt())
            .putInt((state.altitudeAglMeters * 1000).roundToInt())
            .putShort((state.groundSpeedMS * 100).roundToInt().toShort())
            .putShort(0)
            .putShort((-state.verticalSpeedMS * 100).roundToInt().toShort())
            .putU16((state.headingDegrees * 100).coerceAtLeast(0))
            .array()
        return frame(messageId = 33, crcExtra = 104, payload = payload)
    }

    fun gpsRaw(state: DroneState): ByteArray {
        val payload = littleEndian(30)
            .putLong(System.currentTimeMillis() * 1000L)
            .putInt((state.latitudeDeg * 1e7).roundToInt())
            .putInt((state.longitudeDeg * 1e7).roundToInt())
            .putInt((state.altitudeMslMeters * 1000).roundToInt())
            .putU16(80)
            .putU16(120)
            .putU16((state.groundSpeedMS * 100).roundToInt())
            .putU16((state.headingDegrees * 100).coerceAtLeast(0))
            .putU8(state.gpsFixType.toInt())
            .putU8(state.gpsSatellites.toInt())
            .array()
        return frame(messageId = 24, crcExtra = 24, payload = payload)
    }

    fun vfrHud(state: DroneState): ByteArray {
        val payload = littleEndian(20)
            .putFloat(state.groundSpeedMS)
            .putFloat(state.groundSpeedMS)
            .putShort(state.headingDegrees)
            .putU16(state.throttlePercent.toInt())
            .putFloat(state.altitudeMslMeters)
            .putFloat(state.verticalSpeedMS)
            .array()
        return frame(messageId = 74, crcExtra = 20, payload = payload)
    }

    fun sysStatus(state: DroneState): ByteArray {
        val payload = littleEndian(31)
            .putInt(1)
            .putInt(1)
            .putInt(1)
            .putU16(250)
            .putU16(state.batteryVoltageMv.toInt())
            .putShort(state.batteryCurrentCa)
            .put(state.batteryRemainingPercent)
            .putU16(0)
            .putU16(0)
            .putU16(0)
            .putU16(0)
            .putU16(0)
            .putU16(0)
            .array()
        return frame(messageId = 1, crcExtra = 124, payload = payload)
    }

    fun batteryStatus(state: DroneState): ByteArray {
        val payload = littleEndian(36)
            .putU16(state.batteryVoltageMv.toInt())
            .putU16(UShort.MAX_VALUE.toInt())
            .putU16(UShort.MAX_VALUE.toInt())
            .putU16(UShort.MAX_VALUE.toInt())
            .putU16(UShort.MAX_VALUE.toInt())
            .putU16(UShort.MAX_VALUE.toInt())
            .putU16(UShort.MAX_VALUE.toInt())
            .putU16(UShort.MAX_VALUE.toInt())
            .putU16(UShort.MAX_VALUE.toInt())
            .putU16(UShort.MAX_VALUE.toInt())
            .putShort(state.batteryCurrentCa)
            .putInt(-1)
            .putInt(-1)
            .put(state.batteryRemainingPercent)
            .putU8(0)
            .putU8(0)
            .putU8(3)
            .putShort(Short.MAX_VALUE)
            .array()
        return frame(messageId = 147, crcExtra = 154, payload = payload)
    }

    fun autopilotVersion(): ByteArray {
        val capabilities = MAV_PROTOCOL_CAPABILITY_MISSION_FLOAT or
            MAV_PROTOCOL_CAPABILITY_PARAM_FLOAT or
            MAV_PROTOCOL_CAPABILITY_MISSION_INT or
            MAV_PROTOCOL_CAPABILITY_COMMAND_INT or
            MAV_PROTOCOL_CAPABILITY_SET_POSITION_TARGET_LOCAL_NED or
            MAV_PROTOCOL_CAPABILITY_SET_POSITION_TARGET_GLOBAL_INT or
            MAV_PROTOCOL_CAPABILITY_MAVLINK2
        val payload = littleEndian(60)
            .putLong(capabilities)
            .putInt(encodeVersion(4, 6, 3, FirmwareVersionTypeDev))
            .putInt(encodeVersion(4, 6, 3, FirmwareVersionTypeDev))
            .putInt(encodeVersion(4, 6, 3, FirmwareVersionTypeDev))
            .putInt(1)
            .put(ByteArray(8))
            .put(ByteArray(8))
            .put(ByteArray(8))
            .putU16(0)
            .putU16(0)
            .putLong(systemId.toLong())
            .array()
        return frame(messageId = 148, crcExtra = 178, payload = payload)
    }

    fun commandAck(command: Int, result: Int): ByteArray {
        val payload = littleEndian(3)
            .putU16(command)
            .putU8(result)
            .array()
        return frame(messageId = 77, crcExtra = 143, payload = payload)
    }

    fun paramValue(id: String, value: Float, index: Int, count: Int): ByteArray {
        val paramId = ByteArray(16)
        id.encodeToByteArray().copyInto(paramId, endIndex = minOf(id.length, 16))
        val payload = littleEndian(25)
            .putFloat(value)
            .putU16(count)
            .putU16(index)
            .put(paramId)
            .putU8(MAV_PARAM_TYPE_REAL32)
            .array()
        return frame(messageId = 22, crcExtra = 220, payload = payload)
    }

    fun missionCount(
        count: Int,
        targetSystem: Int = MavlinkGroundStationSystemId,
        targetComponent: Int = MavlinkGroundStationComponentId,
    ): ByteArray {
        val payload = littleEndian(4)
            .putU16(count)
            .putU8(targetSystem)
            .putU8(targetComponent)
            .array()
        return frame(messageId = 44, crcExtra = 221, payload = payload)
    }

    fun missionRequestInt(
        sequence: Int,
        targetSystem: Int,
        targetComponent: Int,
    ): ByteArray {
        val payload = littleEndian(4)
            .putU16(sequence.coerceAtLeast(0))
            .putU8(targetSystem)
            .putU8(targetComponent)
            .array()
        return frame(messageId = 51, crcExtra = 196, payload = payload)
    }

    fun missionRequest(
        sequence: Int,
        targetSystem: Int,
        targetComponent: Int,
    ): ByteArray {
        val payload = littleEndian(4)
            .putU16(sequence.coerceAtLeast(0))
            .putU8(targetSystem)
            .putU8(targetComponent)
            .array()
        return frame(messageId = 40, crcExtra = 230, payload = payload)
    }

    fun missionAck(
        type: Int,
        targetSystem: Int,
        targetComponent: Int,
    ): ByteArray {
        val payload = littleEndian(3)
            .putU8(targetSystem)
            .putU8(targetComponent)
            .putU8(type)
            .array()
        return frame(messageId = 47, crcExtra = 153, payload = payload)
    }

    fun missionCurrent(sequence: Int): ByteArray {
        val payload = littleEndian(2)
            .putU16(sequence.coerceAtLeast(0))
            .array()
        return frame(messageId = 42, crcExtra = 28, payload = payload)
    }

    fun missionItemReached(sequence: Int): ByteArray {
        val payload = littleEndian(2)
            .putU16(sequence.coerceAtLeast(0))
            .array()
        return frame(messageId = 46, crcExtra = 11, payload = payload)
    }

    fun missionItemInt(
        item: MissionItem,
        currentSequence: Int,
        targetSystem: Int = MavlinkGroundStationSystemId,
        targetComponent: Int = MavlinkGroundStationComponentId,
    ): ByteArray {
        val payload = littleEndian(37)
            .putFloat(0f)
            .putFloat(item.acceptanceRadiusMeters)
            .putFloat(0f)
            .putFloat(0f)
            .putInt((item.latitudeDeg * 1e7).roundToInt())
            .putInt((item.longitudeDeg * 1e7).roundToInt())
            .putFloat(item.altitudeAglMeters)
            .putU16(item.sequence)
            .putU16(item.command.mavCmdId)
            .putU8(targetSystem)
            .putU8(targetComponent)
            .putU8(MAV_FRAME_GLOBAL_RELATIVE_ALT_INT)
            .putU8(if (item.sequence == currentSequence) 1 else 0)
            .putU8(if (item.autocontinue) 1 else 0)
            .array()
        return frame(messageId = 73, crcExtra = 38, payload = payload)
    }

    fun missionItem(
        item: MissionItem,
        currentSequence: Int,
        targetSystem: Int,
        targetComponent: Int,
    ): ByteArray {
        val payload = littleEndian(37)
            .putFloat(0f)
            .putFloat(item.acceptanceRadiusMeters)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(item.latitudeDeg.toFloat())
            .putFloat(item.longitudeDeg.toFloat())
            .putFloat(item.altitudeAglMeters)
            .putU16(item.sequence)
            .putU16(item.command.mavCmdId)
            .putU8(targetSystem)
            .putU8(targetComponent)
            .putU8(MAV_FRAME_GLOBAL_RELATIVE_ALT)
            .putU8(if (item.sequence == currentSequence) 1 else 0)
            .putU8(if (item.autocontinue) 1 else 0)
            .array()
        return frame(messageId = 39, crcExtra = 254, payload = payload)
    }

    private fun frame(messageId: Int, crcExtra: Int, payload: ByteArray): ByteArray {
        require(messageId in 0..255) { "MAVLink v1 only supports message IDs 0..255" }
        val header = ByteArray(6)
        header[0] = 0xfe.toByte()
        header[1] = payload.size.toByte()
        header[2] = nextSequence().toByte()
        header[3] = systemId.toByte()
        header[4] = componentId.toByte()
        header[5] = (messageId and 0xff).toByte()

        val checksum = MavlinkX25.crc(header.copyOfRange(1, header.size), payload, crcExtra)
        return header + payload + byteArrayOf(
            (checksum and 0xff).toByte(),
            ((checksum ushr 8) and 0xff).toByte(),
        )
    }

    private fun nextSequence(): Int {
        val current = sequence
        sequence = (sequence + 1) and 0xff
        return current
    }

    private fun littleEndian(size: Int): ByteBuffer {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun encodeVersion(major: Int, minor: Int, patch: Int, type: Int): Int {
        return (major.coerceIn(0, 255) shl 24) or
            (minor.coerceIn(0, 255) shl 16) or
            (patch.coerceIn(0, 255) shl 8) or
            type.coerceIn(0, 255)
    }

    private fun ByteBuffer.putU8(value: Int): ByteBuffer = put((value and 0xff).toByte())
    private fun ByteBuffer.putU16(value: Int): ByteBuffer = putShort((value and 0xffff).toShort())

    private object MavlinkX25 {
        fun crc(headerWithoutMagic: ByteArray, payload: ByteArray, extra: Int): Int {
            var crc = 0xffff
            (headerWithoutMagic + payload + byteArrayOf(extra.toByte())).forEach { byte ->
                var tmp = (byte.toInt() and 0xff) xor (crc and 0xff)
                tmp = (tmp xor (tmp shl 4)) and 0xff
                crc = ((crc ushr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp ushr 4)) and 0xffff
            }
            return crc
        }
    }

    private companion object {
        const val MAV_TYPE_QUADROTOR = 2
        const val MAV_AUTOPILOT_ARDUPILOTMEGA = 3
        const val MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 1
        const val MAV_MODE_FLAG_AUTO_ENABLED = 4
        const val MAV_MODE_FLAG_GUIDED_ENABLED = 8
        const val MAV_MODE_FLAG_STABILIZE_ENABLED = 16
        const val MAV_MODE_FLAG_MANUAL_INPUT_ENABLED = 64
        const val MAV_MODE_FLAG_SAFETY_ARMED = 128
        const val MAV_STATE_STANDBY = 3
        const val MAV_STATE_ACTIVE = 4
        const val MAV_PARAM_TYPE_REAL32 = 9
        const val MAV_FRAME_GLOBAL_RELATIVE_ALT = 3
        const val MAV_FRAME_GLOBAL_RELATIVE_ALT_INT = 6
        const val MavlinkGroundStationSystemId = 255
        const val MavlinkGroundStationComponentId = 190
        const val FirmwareVersionTypeDev = 255
        const val MAV_PROTOCOL_CAPABILITY_MISSION_FLOAT = 1L
        const val MAV_PROTOCOL_CAPABILITY_PARAM_FLOAT = 2L
        const val MAV_PROTOCOL_CAPABILITY_MISSION_INT = 4L
        const val MAV_PROTOCOL_CAPABILITY_COMMAND_INT = 8L
        const val MAV_PROTOCOL_CAPABILITY_SET_POSITION_TARGET_LOCAL_NED = 128L
        const val MAV_PROTOCOL_CAPABILITY_SET_POSITION_TARGET_GLOBAL_INT = 256L
        const val MAV_PROTOCOL_CAPABILITY_MAVLINK2 = 8192L
    }
}
