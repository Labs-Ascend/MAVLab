package com.ascend.mavlab.simulation.coordinates

/** Taken atomically from the engine; excludes GPS/compass noise and UI transforms. */
data class PhysicalPoseSnapshot(
    val frame: SimulationCoordinateFrame, val northMeters: Double, val eastMeters: Double,
    val altitudeAglMeters: Double, val yawRadians: Double, val monotonicNanos: Long,
    val groundAltitudeMslMeters: Double = 0.0,
)
