package com.ascend.mavlab.simulation.camera

import kotlin.math.*

/** Still camera looking straight down, stabilized in roll/pitch. Image-up follows heading. */
data class VirtualCameraProfile(
    val width: Int = 640, val height: Int = 480,
    val sensorWidthMm: Double = 6.4, val sensorHeightMm: Double = 4.8,
    val focalLengthMm: Double = 6.4 / (2.0 * tan(PI / 6.0)),
) {
    init {
        require(width in 1..1920 && height in 1..1080)
        require(listOf(sensorWidthMm, sensorHeightMm, focalLengthMm).all { it.isFinite() && it in 0.01..1000.0 })
        require(abs(width.toDouble() / height - sensorWidthMm / sensorHeightMm) < 1e-6)
    }
    val horizontalFovDegrees get() = Math.toDegrees(2 * atan(sensorWidthMm / (2 * focalLengthMm)))
}

data class GroundPoint(val east: Double, val north: Double)
data class ImagePoint(val x: Double, val y: Double)
data class CameraPose(val east: Double, val north: Double, val altitude: Double, val headingRadians: Double) {
    init {
        require(listOf(east, north, altitude, headingRadians).all { it.isFinite() })
        require(abs(east) <= 50_000 && abs(north) <= 50_000 && altitude in 0.5..5_000.0)
    }
}

class PlanarCamera(val profile: VirtualCameraProfile, val pose: CameraPose) {
    val groundWidth = pose.altitude * profile.sensorWidthMm / profile.focalLengthMm
    val groundHeight = pose.altitude * profile.sensorHeightMm / profile.focalLengthMm
    val metersPerPixel = groundWidth / profile.width
    private val c = cos(pose.headingRadians)
    private val s = sin(pose.headingRadians)
    /** Continuous image edge coordinates: top-left edge (0,0), first pixel centre (0.5,0.5). */
    fun groundAt(x: Double, y: Double): GroundPoint {
        val right = (x / profile.width - 0.5) * groundWidth
        val up = (0.5 - y / profile.height) * groundHeight
        return GroundPoint(pose.east + right*c + up*s, pose.north - right*s + up*c)
    }
    fun imageAt(east: Double, north: Double): ImagePoint {
        val de = east - pose.east
        val dn = north - pose.north
        return ImagePoint((de*c - dn*s) / groundWidth * profile.width + profile.width/2.0,
            profile.height/2.0 - (de*s + dn*c) / groundHeight * profile.height)
    }
    /** Camera FRD frame in world NED: forward optical axis points down, right is image-right. */
    fun quaternionNed(): FloatArray {
        return nadirCameraQuaternionNed(pose.headingRadians)
    }
    val corners: List<GroundPoint> get() = listOf(groundAt(0.0,0.0), groundAt(profile.width.toDouble(),0.0),
        groundAt(profile.width.toDouble(),profile.height.toDouble()), groundAt(0.0,profile.height.toDouble()))
}

/** Orientation has no map footprint or minimum-altitude dependency. */
fun nadirCameraQuaternionNed(headingRadians:Double):FloatArray {
    require(headingRadians.isFinite())
    val ch=cos(headingRadians/2)/sqrt(2.0)
    val sh=sin(headingRadians/2)/sqrt(2.0)
    return floatArrayOf(ch.toFloat(),sh.toFloat(),(-ch).toFloat(),sh.toFloat())
}
