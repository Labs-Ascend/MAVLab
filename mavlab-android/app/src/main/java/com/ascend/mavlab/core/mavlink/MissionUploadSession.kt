package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.mission.MissionCommand
import com.ascend.mavlab.simulation.mission.MissionItem
import com.ascend.mavlab.simulation.mission.MissionWireData
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MissionUploadSession(
    val expectedCount: Int,
    val peer: UdpDestination,
    val startedAtMs: Long = System.currentTimeMillis(),
    val sourceSystemId: Int = 255,
    val sourceComponentId: Int = 190,
) {
    private val receivedItems = mutableMapOf<Int, MissionItem>()
    private var lastProgressAtMs = startedAtMs
    private var lastRequestAtMs = startedAtMs
    private var retries = 0

    val receivedCount: Int get() = receivedItems.size
    val nextSequence: Int
        get() = (0 until expectedCount).firstOrNull { receivedItems[it] == null } ?: expectedCount
    val complete: Boolean get() = receivedItems.size == expectedCount
    val expired: Boolean get() = timedOut(System.currentTimeMillis())

    fun owns(packet: MavlinkPacket, destination: UdpDestination): Boolean =
        peer == destination && sourceSystemId == packet.systemId && sourceComponentId == packet.componentId

    fun timedOut(nowMs: Long): Boolean = nowMs - lastProgressAtMs > UploadTimeoutMs

    fun pollRetry(nowMs: Long): MissionUploadRetry {
        if (complete || nowMs - lastRequestAtMs < RequestTimeoutMs) return MissionUploadRetry.None
        if (retries >= MaxRetries || timedOut(nowMs)) return MissionUploadRetry.Exhausted
        retries++
        lastRequestAtMs = nowMs
        return MissionUploadRetry.Request(nextSequence)
    }

    fun markRequestSent(nowMs: Long) { lastRequestAtMs = nowMs }

    fun matchesReceived(item: MissionItem): Boolean = receivedItems[item.sequence] == item

    fun receive(item: MissionItem, nowMs: Long = System.currentTimeMillis()): MissionUploadResult {
        if (item.sequence !in 0 until expectedCount) {
            return MissionUploadResult.Rejected("invalid seq ${item.sequence}")
        }
        // A gap or a duplicate must re-request the expected item, not overwrite progress.
        if (item.sequence != nextSequence) return MissionUploadResult.RequestNext(nextSequence)
        receivedItems[item.sequence] = item
        lastProgressAtMs = nowMs
        lastRequestAtMs = nowMs
        retries = 0
        return if (complete) {
            MissionUploadResult.Complete(receivedItems.values.sortedBy { it.sequence })
        } else {
            MissionUploadResult.RequestNext(nextSequence)
        }
    }

    companion object {
        const val MaxMissionItems = 1000
        const val UploadTimeoutMs = 10_000L
        const val RequestTimeoutMs = 250L
        const val MaxRetries = 5

        fun parseMissionCount(packet: MavlinkPacket, peer: UdpDestination): MissionUploadSession? {
            if (packet.payload.size < 4) return null
            val count = packet.payload.leUInt16(0)
            if (count !in 0..MaxMissionItems) return null
            return MissionUploadSession(
                expectedCount = count,
                peer = peer,
                sourceSystemId = packet.systemId,
                sourceComponentId = packet.componentId,
            )
        }

        fun parseMissionItemInt(packet: MavlinkPacket): MissionItem? {
            if (missionItemRejection(packet) != null) return null
            val latitudeDeg = packet.payload.leInt32(16) / 1e7
            val longitudeDeg = packet.payload.leInt32(20) / 1e7
            return missionItem(
                sequence = packet.payload.leUInt16(28),
                commandId = packet.payload.leUInt16(30),
                latitudeDeg = latitudeDeg,
                longitudeDeg = longitudeDeg,
                altitudeAglMeters = packet.payload.leFloat(24),
                acceptanceRadiusMeters = packet.payload.leFloat(4),
                speedMetersPerSecond = packet.payload.leFloat(4),
                autocontinue = packet.payload[36].toInt() != 0,
                altitudeIsMsl = packet.payload[34].toInt() in listOf(0, 5),
                wire = wireData(packet),
            )
        }

        fun parseMissionItem(packet: MavlinkPacket): MissionItem? {
            if (missionItemRejection(packet) != null) return null
            return missionItem(
                sequence = packet.payload.leUInt16(28),
                commandId = packet.payload.leUInt16(30),
                latitudeDeg = packet.payload.leFloat(16).toDouble(),
                longitudeDeg = packet.payload.leFloat(20).toDouble(),
                altitudeAglMeters = packet.payload.leFloat(24),
                acceptanceRadiusMeters = packet.payload.leFloat(4),
                speedMetersPerSecond = packet.payload.leFloat(4),
                autocontinue = packet.payload[36].toInt() != 0,
                altitudeIsMsl = packet.payload[34].toInt() in listOf(0, 5),
                wire = wireData(packet),
            )
        }

        private fun wireData(packet: MavlinkPacket): MissionWireData {
            val p=packet.payload
            val frame=p[34].toInt() and 255
            val spatial=MissionCommand.entries.first { it.mavCmdId==p.leUInt16(30) }.isNav
            fun coordinate(offset:Int):Double = if(packet.messageId==39) p.leFloat(offset).toDouble()
                else if(spatial) p.leInt32(offset)/1e7
                else if(p.leInt32(offset)==Int.MAX_VALUE) Double.NaN else p.leInt32(offset).toDouble()
            return MissionWireData(frame,p.leFloat(0),p.leFloat(4),p.leFloat(8),p.leFloat(12),coordinate(16),coordinate(20),p.leFloat(24))
        }

        private fun missionItem(
            sequence: Int,
            commandId: Int,
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeAglMeters: Float,
            acceptanceRadiusMeters: Float,
            speedMetersPerSecond: Float,
            autocontinue: Boolean,
            altitudeIsMsl: Boolean,
            wire: MissionWireData,
        ): MissionItem? {
            val command = MissionCommand.entries.firstOrNull { it.mavCmdId == commandId } ?: return null
            if (command.isNav &&
                (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || !altitudeAglMeters.isFinite() || latitudeDeg !in -90.0..90.0 || longitudeDeg !in -180.0..180.0)
            ) {
                return null
            }
            return MissionItem(
                sequence = sequence,
                command = command,
                wire = wire,
                latitudeDeg = latitudeDeg.takeIf { it.isFinite() } ?: 0.0,
                longitudeDeg = longitudeDeg.takeIf { it.isFinite() } ?: 0.0,
                altitudeAglMeters = altitudeAglMeters.takeIf { it.isFinite() }?.let { if (altitudeIsMsl) it else it.coerceAtLeast(0f) } ?: 0f,
                altitudeIsMsl = altitudeIsMsl,
                acceptanceRadiusMeters = acceptanceRadiusMeters
                    .takeIf { it.isFinite() && it > 0f }
                    ?: 2f,
                autocontinue = autocontinue,
                speedMetersPerSecond = if (command == MissionCommand.CHANGE_SPEED) {
                    speedMetersPerSecond.takeIf { it.isFinite() && it > 0f }
                } else {
                    null
                },
            )
        }
    }
}

sealed interface MissionUploadRetry {
    data object None : MissionUploadRetry
    data object Exhausted : MissionUploadRetry
    data class Request(val sequence: Int) : MissionUploadRetry
}

sealed interface MissionUploadResult {
    data class RequestNext(val sequence: Int) : MissionUploadResult
    data class Complete(val items: List<MissionItem>) : MissionUploadResult
    data class Rejected(val reason: String) : MissionUploadResult
}

private fun ByteArray.leUInt16(offset: Int): Int {
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.leInt32(offset: Int): Int {
    return (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        (this[offset + 3].toInt() shl 24)
}

private fun ByteArray.leFloat(offset: Int): Float {
    return ByteBuffer.wrap(this, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .float
}
