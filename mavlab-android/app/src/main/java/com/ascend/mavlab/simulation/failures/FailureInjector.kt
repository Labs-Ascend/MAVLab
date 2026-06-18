package com.ascend.mavlab.simulation.failures

import com.ascend.mavlab.simulation.physics.Vector3
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FailureState(
    val gpsEnabled: Boolean = true,
    val gpsNoiseMultiplier: Float = 1f,
    val compassEnabled: Boolean = true,
    val compassOffsetDeg: Float = 0f,
    val windSpeedMs: Float = 0f,
    val windDirectionDeg: Float = 0f,
    val windGustsMs: Float = 0f,
    val motorFailureMask: Int = 0,
    val batteryDrainMultiplier: Float = 1f,
    val payloadMassKg: Float = 0f,
    val lostLinkActive: Boolean = false,
    val barometerOffsetMeters: Float = 0f,
    val unsafeMissionReserveActive: Boolean = false,
) {
    val hasMotorFailure: Boolean get() = motorFailureMask != 0
    val activeCount: Int
        get() = listOf(
            !gpsEnabled,
            gpsNoiseMultiplier > 1.05f,
            !compassEnabled,
            kotlin.math.abs(compassOffsetDeg) > 0.5f,
            windSpeedMs > 0.05f,
            windGustsMs > 0.05f,
            hasMotorFailure,
            batteryDrainMultiplier > 1.05f,
            payloadMassKg > 0.05f,
            lostLinkActive,
            kotlin.math.abs(barometerOffsetMeters) > 0.05f,
            unsafeMissionReserveActive,
        ).count { it }
}

class FailureInjector {
    private val mutableState = MutableStateFlow(FailureState())
    val state: StateFlow<FailureState> = mutableState.asStateFlow()

    fun setGpsEnabled(enabled: Boolean) = update { it.copy(gpsEnabled = enabled) }
    fun setGpsNoiseMultiplier(value: Float) = update { it.copy(gpsNoiseMultiplier = value.coerceIn(1f, 8f)) }
    fun setCompassEnabled(enabled: Boolean) = update { it.copy(compassEnabled = enabled) }
    fun setCompassOffsetDeg(value: Float) = update { it.copy(compassOffsetDeg = value.coerceIn(-90f, 90f)) }
    fun setWindSpeedMs(value: Float) = update { it.copy(windSpeedMs = value.coerceIn(0f, 15f)) }
    fun setWindDirectionDeg(value: Float) = update { it.copy(windDirectionDeg = normalizeDegrees(value)) }
    fun setWindGustsMs(value: Float) = update { it.copy(windGustsMs = value.coerceIn(0f, 8f)) }
    fun setBatteryDrainMultiplier(value: Float) = update { it.copy(batteryDrainMultiplier = value.coerceIn(1f, 15f)) }
    fun setPayloadMassKg(value: Float) = update { it.copy(payloadMassKg = value.coerceIn(0f, 2.5f)) }
    fun setLostLinkActive(active: Boolean) = update { it.copy(lostLinkActive = active) }
    fun setBarometerOffsetMeters(value: Float) = update { it.copy(barometerOffsetMeters = value.coerceIn(-8f, 8f)) }
    fun setUnsafeMissionReserveActive(active: Boolean) = update { it.copy(unsafeMissionReserveActive = active) }

    fun setMotorFailed(index: Int, failed: Boolean) {
        require(index in 0..3) { "Motor index must be 0..3." }
        update {
            val bit = 1 shl index
            val mask = if (failed) it.motorFailureMask or bit else it.motorFailureMask and bit.inv()
            it.copy(motorFailureMask = mask)
        }
    }

    fun applyScenario(scenario: FailureScenario) {
        scenario.apply(this)
    }

    fun clearScenario(scenario: FailureScenario) {
        scenario.clear(this)
    }

    fun resetAll() {
        mutableState.value = FailureState()
    }

    fun windVectorNed(): Vector3 {
        val snapshot = state.value
        val direction = Math.toRadians(snapshot.windDirectionDeg.toDouble())
        val gust = snapshot.windGustsMs * ((Math.random().toFloat() * 2f) - 1f)
        val speed = (snapshot.windSpeedMs + gust).coerceAtLeast(0f)
        return Vector3(
            x = -(speed * cos(direction)).toFloat(),
            y = -(speed * sin(direction)).toFloat(),
            z = 0f,
        )
    }

    fun applyMotorFailures(motorSpeeds: FloatArray): FloatArray {
        val mask = state.value.motorFailureMask
        return FloatArray(motorSpeeds.size) { index ->
            if (mask and (1 shl index) != 0) 0f else motorSpeeds[index]
        }
    }

    private fun update(block: (FailureState) -> FailureState) {
        mutableState.value = block(mutableState.value)
    }

    private fun normalizeDegrees(value: Float): Float {
        var degrees = value % 360f
        if (degrees < 0f) degrees += 360f
        return degrees
    }
}
