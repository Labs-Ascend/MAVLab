package com.ascend.mavlab.simulation.engine

/**
 * Phase 1 protocol-demo state.
 * Phase 2 replaces the generated telemetry with values from the real physics engine.
 */
data class DroneState(
    val armed: Boolean = false,
    val mode: FlightMode = FlightMode.STABILIZE,
    val controlAuthority: ControlAuthority = ControlAuthority.IDLE,
    val uptimeMs: UInt = 0u,
    val latitudeDeg: Double = SimLocation.Nairobi.latitudeDeg,
    val longitudeDeg: Double = SimLocation.Nairobi.longitudeDeg,
    val northMeters: Float = 0f,
    val eastMeters: Float = 0f,
    val northVelocityMS: Float = 0f,
    val eastVelocityMS: Float = 0f,
    val altitudeMslMeters: Float = SimLocation.Nairobi.altitudeMslMeters,
    val altitudeAglMeters: Float = 0f,
    val rollRadians: Float = 0f,
    val pitchRadians: Float = 0f,
    val yawRadians: Float = 0f,
    val rollSpeedRadS: Float = 0f,
    val pitchSpeedRadS: Float = 0f,
    val yawSpeedRadS: Float = 0f,
    val groundSpeedMS: Float = 0f,
    val verticalSpeedMS: Float = 0f,
    val headingDegrees: Short = 90,
    val batteryVoltageMv: UShort = 12000u,
    val batteryCurrentCa: Short = 120,
    val batteryRemainingPercent: Byte = 85,
    val throttlePercent: UByte = 0u,
    val gpsSatellites: UByte = 12u,
    val gpsFixType: UByte = 3u,
    val motors: List<MotorTelemetry> = List(4) { MotorTelemetry() },
    val lastInboundMessage: String = "None",
    val lastAck: String = "None",
    val safety: com.ascend.mavlab.simulation.safety.SafetyStatus = com.ascend.mavlab.simulation.safety.SafetyStatus(),
)

enum class FlightMode(val customMode: UInt, val displayName: String) {
    STABILIZE(0u, "Stabilize"),
    ALT_HOLD(2u, "Alt Hold"),
    AUTO(3u, "Auto"),
    GUIDED(4u, "Guided"),
    LOITER(5u, "Loiter"),
    RTL(6u, "RTL"),
    LAND(9u, "Land"),
    BRAKE(17u, "Brake");

    companion object {
        /**
         * Maps an externally supplied Copter custom-mode id to an implemented [FlightMode].
         * Returns null for ids MAVLab does not implement (for example mode 99) instead of
         * silently falling back to a different mode.
         */
        fun fromCustomModeOrNull(customMode: UInt): FlightMode? {
            return entries.firstOrNull { it.customMode == customMode }
        }
    }
}
