package com.ascend.mavlab.simulation.coordinates

import kotlin.math.cos

/** MAVLab local-grid-v1: north/east metres on a flat plane, fixed geographic origin.
 * This preserves the simulator's 111320 m/degree convention; it is not UTM or ECEF ENU.
 * Return-home may move independently. Sensor noise must never change this frame.
 */
data class SimulationCoordinateFrame(val latitudeDeg: Double, val longitudeDeg: Double) {
    init {
        require(latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0)
        require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
    }

    val longitudeMetersPerDegree = MetersPerLatitudeDegree * cos(Math.toRadians(latitudeDeg)).coerceAtLeast(0.2)
    fun latitude(northMeters: Double): Double = latitudeDeg + northMeters / MetersPerLatitudeDegree
    fun longitude(eastMeters: Double): Double = longitudeDeg + eastMeters / longitudeMetersPerDegree
    fun north(latitude: Double): Double = (latitude - latitudeDeg) * MetersPerLatitudeDegree
    fun east(longitude: Double): Double = (longitude - longitudeDeg) * longitudeMetersPerDegree

    companion object {
        const val Id = "mavlab-local-grid-v1"
        const val MetersPerLatitudeDegree = 111_320.0
        // Map preparation and capture support a local area, not global wrapping or polar mapping.
        const val MapExtentMeters = 50_000.0
    }
}
