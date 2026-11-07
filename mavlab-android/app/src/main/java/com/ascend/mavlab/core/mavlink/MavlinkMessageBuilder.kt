package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.engine.DroneState
import com.ascend.mavlab.simulation.engine.HomePosition
import com.ascend.mavlab.simulation.mission.MissionCommand
import com.ascend.mavlab.simulation.mission.MissionItem
import com.ascend.mavlab.simulation.safety.SafetyItem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class MavlinkMessageBuilder(
    private val systemId: Int,
    private val componentId: Int,
    private val compatibilityProfile: ArduPilotCompatibilityProfile = MavLabArduPilotCompatibility.Current,
) {
    private var sequence = 0
    internal val systemIdForTransfers get() = systemId

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
            .putShort(speedCentimetersPerSecond(state.northVelocityMS).toShort())
            .putShort(speedCentimetersPerSecond(state.eastVelocityMS).toShort())
            .putShort(speedCentimetersPerSecond(-state.verticalSpeedMS).toShort())
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
            .putU16(speedCentimetersPerSecond(state.groundSpeedMS).coerceAtLeast(0))
            .putU16((state.headingDegrees * 100).coerceAtLeast(0))
            .putU8(state.gpsFixType.toInt())
            .putU8(state.gpsSatellites.toInt())
            .array()
        return frame(messageId = 24, crcExtra = 24, payload = payload)
    }

    fun vfrHud(state: DroneState): ByteArray {
        val payload = littleEndian(20)
            .putFloat(0f)
            .putFloat(saneSpeedMetersPerSecond(state.groundSpeedMS))
            .putFloat(state.altitudeMslMeters)
            .putFloat(state.verticalSpeedMS)
            .putShort(state.headingDegrees)
            .putU16(state.throttlePercent.toInt())
            .array()
        return frame(messageId = 74, crcExtra = 20, payload = payload)
    }

    private fun saneSpeedMetersPerSecond(value: Float): Float {
        return if (value.isFinite()) value.coerceIn(0f, MaxTelemetrySpeedMS) else 0f
    }

    private fun speedCentimetersPerSecond(value: Float): Int {
        return if (value.isFinite()) {
            (value.coerceIn(-MaxTelemetrySpeedMS, MaxTelemetrySpeedMS) * 100f).roundToInt()
        } else {
            0
        }
    }

    fun sysStatus(state: DroneState): ByteArray {
        // MAVLink SYS_STATUS (#1) wire order: the int8 battery_remaining sorts last,
        // so it must be the final byte (offset 30) — after the six comm-error uint16s.
        val payload = littleEndian(31)
            .putInt(1 or (1 shl 20))                // geofence subsystem is implemented
            .putInt(1 or if (state.safety.enabled) (1 shl 20) else 0)
            .putInt(1 or if (!state.safety.breached) (1 shl 20) else 0)
            .putU16(250)                            // load
            .putU16(state.batteryVoltageMv.toInt()) // voltage_battery
            .putShort(state.batteryCurrentCa)       // current_battery
            .putU16(0)                              // drop_rate_comm
            .putU16(0)                              // errors_comm
            .putU16(0)                              // errors_count1
            .putU16(0)                              // errors_count2
            .putU16(0)                              // errors_count3
            .putU16(0)                              // errors_count4
            .put(state.batteryRemainingPercent)     // battery_remaining (int8, last)
            .array()
        return frame(messageId = 1, crcExtra = 124, payload = payload)
    }

    fun batteryStatus(state: DroneState): ByteArray {
        // MAVLink BATTERY_STATUS (#147) wire order (size-sorted): the two int32s first,
        // then temperature + the ten cell voltages + current_battery, then the four int8s
        // (id, battery_function, type, battery_remaining) last. battery_remaining is byte 35.
        val payload = littleEndian(36)
            .putInt(-1)                             // current_consumed (-1 = not provided)
            .putInt(-1)                             // energy_consumed (-1 = not provided)
            .putShort(Short.MAX_VALUE)              // temperature (INT16_MAX = unknown)
            .putU16(state.batteryVoltageMv.toInt()) // voltages[0]
            .putU16(UShort.MAX_VALUE.toInt())       // voltages[1]
            .putU16(UShort.MAX_VALUE.toInt())       // voltages[2]
            .putU16(UShort.MAX_VALUE.toInt())       // voltages[3]
            .putU16(UShort.MAX_VALUE.toInt())       // voltages[4]
            .putU16(UShort.MAX_VALUE.toInt())       // voltages[5]
            .putU16(UShort.MAX_VALUE.toInt())       // voltages[6]
            .putU16(UShort.MAX_VALUE.toInt())       // voltages[7]
            .putU16(UShort.MAX_VALUE.toInt())       // voltages[8]
            .putU16(UShort.MAX_VALUE.toInt())       // voltages[9]
            .putShort(state.batteryCurrentCa)       // current_battery
            .putU8(0)                               // id
            .putU8(0)                               // battery_function (0 = UNKNOWN)
            .putU8(3)                               // type (3 = MAV_BATTERY_TYPE_LION)
            .put(state.batteryRemainingPercent)     // battery_remaining (int8, last)
            .array()
        return frame(messageId = 147, crcExtra = 154, payload = payload)
    }

    fun homePosition(home: HomePosition): ByteArray {
        val payload = littleEndian(52)
            .putInt((home.latitudeDeg * 1e7).roundToInt())
            .putInt((home.longitudeDeg * 1e7).roundToInt())
            .putInt((home.altitudeMslMeters * 1000).roundToInt())
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(1f)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(0f)
            .putFloat(0f)
            .array()
        return frame(messageId = 242, crcExtra = 104, payload = payload)
    }

    fun autopilotVersion(): ByteArray {
        // MAVLink wire order is type-sorted by the generator, not XML declaration order.
        // AUTOPILOT_VERSION v1 offsets: capabilities@0, uid@8, flight_sw_version@16,
        // middleware@20, os@24, board@28, vendor@32, product@34, custom versions@36.
        val payload = littleEndian(60)
            .putLong(compatibilityProfile.capabilityMask)
            .putLong(systemId.toLong())
            .putInt(compatibilityProfile.flightVersion.encoded)
            .putInt(0) // middleware_sw_version is not represented by MAVLab.
            .putInt(0) // os_sw_version is not an ArduPilot compatibility claim.
            .putInt(0) // board_version is unavailable for the phone simulator.
            .putU16(0)
            .putU16(0)
            .put(compatibilityProfile.flightCustomVersionBytes())
            .put(ByteArray(8))
            .put(ByteArray(8))
            .array()
        return frame(messageId = 148, crcExtra = 178, payload = payload)
    }

    fun commandAck(command: Int, result: Int, targetSystem: Int = 0, targetComponent: Int = 0): ByteArray {
        val payload = littleEndian(10)
            .putU16(command)
            .putU8(result)
            .putU8(0) // progress (not used for final results)
            .putInt(0) // result_param2
            .putU8(targetSystem)
            .putU8(targetComponent)
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
        missionType: Int = 0,
    ): ByteArray {
        val payload = littleEndian(5)
            .putU16(count)
            .putU8(targetSystem)
            .putU8(targetComponent)
            .putU8(missionType)
            .array()
        return frame(messageId = 44, crcExtra = 221, payload = payload)
    }

    fun missionRequestInt(
        sequence: Int,
        targetSystem: Int,
        targetComponent: Int,
        missionType: Int = 0,
    ): ByteArray {
        val payload = littleEndian(5)
            .putU16(sequence.coerceAtLeast(0))
            .putU8(targetSystem)
            .putU8(targetComponent)
            .putU8(missionType)
            .array()
        return frame(messageId = 51, crcExtra = 196, payload = payload)
    }

    fun safetyItem(item: SafetyItem, type: Int, targetSystem: Int, targetComponent: Int, legacy: Boolean = false): ByteArray {
        val payload = littleEndian(38).putFloat(item.param1).putFloat(item.param2).putFloat(0f).putFloat(0f)
        if (legacy) payload.putFloat(item.latitude.toFloat()).putFloat(item.longitude.toFloat())
        else payload.putInt((item.latitude * 1e7).roundToInt()).putInt((item.longitude * 1e7).roundToInt())
        payload.putFloat(item.altitude).putU16(item.sequence).putU16(item.command)
            .putU8(targetSystem).putU8(targetComponent).putU8(item.frame).putU8(0).putU8(0).putU8(type)
        return frame(if (legacy) 39 else 73, if (legacy) 254 else 38, payload.array())
    }

    fun fenceStatus(state: DroneState): ByteArray {
        val s = state.safety
        val payload = littleEndian(9).putInt(s.breachTimeMs.toInt()).putU16(s.breachCount)
            .putU8(if (s.breached) 1 else 0).putU8(s.breachType)
            .putU8(if (s.recovery == "None") 1 else 0).array() // RTL/LAND have no specific FENCE_MITIGATE enum.
        return frame(162, 189, payload)
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
        missionType: Int = 0,
    ): ByteArray {
        val payload = littleEndian(4)
            .putU8(targetSystem)
            .putU8(targetComponent)
            .putU8(type)
            .putU8(missionType)
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
        val commandParams = missionCommandParams(item)
        val payload = littleEndian(37)
            .putFloat(commandParams[0])
            .putFloat(commandParams[1])
            .putFloat(commandParams[2])
            .putFloat(commandParams[3])
            .putInt(wireCoordinate(item, true))
            .putInt(wireCoordinate(item, false))
            .putFloat(item.wire?.z ?: item.altitudeAglMeters)
            .putU16(item.sequence)
            .putU16(item.command.mavCmdId)
            .putU8(targetSystem)
            .putU8(targetComponent)
            .putU8(missionFrame(item, integer = true))
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
        val commandParams = missionCommandParams(item)
        val payload = littleEndian(37)
            .putFloat(commandParams[0])
            .putFloat(commandParams[1])
            .putFloat(commandParams[2])
            .putFloat(commandParams[3])
            .putFloat((item.wire?.x ?: item.latitudeDeg).toFloat())
            .putFloat((item.wire?.y ?: item.longitudeDeg).toFloat())
            .putFloat(item.wire?.z ?: item.altitudeAglMeters)
            .putU16(item.sequence)
            .putU16(item.command.mavCmdId)
            .putU8(targetSystem)
            .putU8(targetComponent)
            .putU8(missionFrame(item, integer = false))
            .putU8(if (item.sequence == currentSequence) 1 else 0)
            .putU8(if (item.autocontinue) 1 else 0)
            .array()
        return frame(messageId = 39, crcExtra = 254, payload = payload)
    }

    private fun missionCommandParams(item: MissionItem): FloatArray {
        item.wire?.let { return floatArrayOf(it.p1,it.p2,it.p3,it.p4) }
        return if (item.command == MissionCommand.CHANGE_SPEED) {
            floatArrayOf(1f, item.speedMetersPerSecond ?: 0f, -1f, 0f)
        } else {
            floatArrayOf(0f, item.acceptanceRadiusMeters, 0f, 0f)
        }
    }

    private fun wireCoordinate(item:MissionItem,north:Boolean):Int {
        if(item.wire==null && !item.command.isNav) return 0
        val value=if(north) item.wire?.x ?: item.latitudeDeg else item.wire?.y ?: item.longitudeDeg
        if(value.isNaN()) return Int.MAX_VALUE
        return (if(item.command.isNav) value*1e7 else value).roundToInt()
    }

    private fun missionFrame(item: MissionItem, integer: Boolean): Int {
        item.wire?.let { return when(it.frame) { 0,5 -> if(integer) 5 else 0;3,6 -> if(integer) 6 else 3;else -> it.frame } }
        return when {
        item.command.isDo -> 2 // MAV_FRAME_MISSION (not spatial)
        item.altitudeIsMsl -> if (integer) 5 else 0
        else -> if (integer) MAV_FRAME_GLOBAL_RELATIVE_ALT_INT else MAV_FRAME_GLOBAL_RELATIVE_ALT
        }
    }

    private fun frame(messageId: Int, crcExtra: Int, payload: ByteArray): ByteArray {
        check(MavlinkWire.definitions[messageId]?.crcExtra == crcExtra)
        return MavlinkWire.frame(messageId, payload, nextSequence(), systemId, componentId)
    }

    @Synchronized
    private fun nextSequence(): Int {
        val current = sequence
        sequence = (sequence + 1) and 0xff
        return current
    }

    private fun littleEndian(size: Int): ByteBuffer {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun ByteBuffer.putU8(value: Int): ByteBuffer = put((value and 0xff).toByte())
    private fun ByteBuffer.putU16(value: Int): ByteBuffer = putShort((value and 0xffff).toShort())

    fun extendedSysState(state: DroneState): ByteArray {
        val landedState = if (state.altitudeAglMeters > 0.15f) 2 else 1
        return frame(245, 130, byteArrayOf(0, landedState.toByte()))
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
        const val MaxTelemetrySpeedMS = 80f
    }
}
