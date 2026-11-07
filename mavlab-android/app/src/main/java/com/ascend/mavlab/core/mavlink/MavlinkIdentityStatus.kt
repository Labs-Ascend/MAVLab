package com.ascend.mavlab.core.mavlink

data class MavlinkIdentityStatus(
    val vehicleSystemId: Int = DefaultMavLabVehicleSystemId,
    val vehicleComponentId: Int = DefaultMavLabAutopilotComponentId,
    val cameraComponentId: Int? = null,
    val lastGcsSystemId: Int? = null,
    val lastGcsComponentId: Int? = null,
    val lastGcsMessageAtMs: Long? = null,
    val lastGcsHeartbeatAtMs: Long? = null,
    val gcsHeartbeatSeriesStartedAtMs: Long? = null,
    val gcsConnected: Boolean = false,
    val identityConflict: Boolean = false,
    val recommendedGcsSystemId: Int = DefaultQgcSystemId,
    val message: String = "",
) {
    val healthLabel: String
        get() = if (identityConflict) "CONFLICT" else "OK"

    fun withGcsHeartbeat(timestampMs: Long): MavlinkIdentityStatus {
        val previousHeartbeat = lastGcsHeartbeatAtMs
        val recentlyConnected = gcsConnected &&
            previousHeartbeat != null &&
            timestampMs - previousHeartbeat in 0..GcsConnectionTimeoutMs
        val seriesStart = if (
            previousHeartbeat != null &&
            timestampMs - previousHeartbeat in 0..GcsHeartbeatContinuityGapMs
        ) {
            gcsHeartbeatSeriesStartedAtMs ?: previousHeartbeat
        } else {
            timestampMs
        }
        return copy(
            lastGcsHeartbeatAtMs = timestampMs,
            gcsHeartbeatSeriesStartedAtMs = seriesStart,
            gcsConnected = recentlyConnected || timestampMs - seriesStart >= GcsConnectionWarmupMs,
        )
    }

    fun refreshGcsConnection(nowMs: Long): MavlinkIdentityStatus {
        val lastHeartbeat = lastGcsHeartbeatAtMs
        val heartbeatAgeMs = lastHeartbeat?.let { nowMs - it }
        return when {
            heartbeatAgeMs == null -> copy(gcsConnected = false, gcsHeartbeatSeriesStartedAtMs = null)
            heartbeatAgeMs > GcsConnectionTimeoutMs -> copy(gcsConnected = false, gcsHeartbeatSeriesStartedAtMs = null)
            !gcsConnected && heartbeatAgeMs > GcsHeartbeatContinuityGapMs -> copy(gcsHeartbeatSeriesStartedAtMs = null)
            else -> this
        }
    }
}

const val GcsConnectionWarmupMs: Long = 3_000L
const val GcsHeartbeatContinuityGapMs: Long = 2_500L
const val GcsConnectionTimeoutMs: Long = 15_000L

internal fun MavlinkPacket.isGroundControlHeartbeat(): Boolean {
    return messageId == MavlinkHeartbeatMessageId &&
        payload.size > MavlinkHeartbeatTypeOffset &&
        payload[MavlinkHeartbeatTypeOffset].toInt() and 0xff == MavTypeGroundControlStation
}

private const val MavlinkHeartbeatMessageId = 0
private const val MavlinkHeartbeatTypeOffset = 4
private const val MavTypeGroundControlStation = 6
