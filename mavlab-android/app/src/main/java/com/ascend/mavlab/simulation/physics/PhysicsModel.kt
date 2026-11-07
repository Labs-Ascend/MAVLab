package com.ascend.mavlab.simulation.physics

import com.ascend.mavlab.simulation.engine.DroneState
import com.ascend.mavlab.simulation.coordinates.SimulationCoordinateFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class PhysicsModel(
    private val params: QuadcopterParams = QuadcopterParams(),
) {
    fun step(
        state: DroneState,
        motorSpeeds: FloatArray,
        dt: Float,
        environment: EnvironmentModel = EnvironmentModel(),
        coordinateFrame: SimulationCoordinateFrame? = null,
    ): DroneState {
        if (dt <= 0f) return state
        require(motorSpeeds.size == 4) { "Quadcopter physics requires exactly 4 motor speeds." }

        val motors = FloatArray(4) { index ->
            motorSpeeds[index].coerceIn(params.motorMinSpeedRadS, params.motorMaxSpeedRadS)
        }

        val thrusts = FloatArray(4) { index ->
            params.thrustCoefficient * motors[index] * motors[index]
        }
        val totalThrust = thrusts.sum()

        val roll = state.rollRadians
        val pitch = state.pitchRadians
        val yaw = state.yawRadians
        val cosRoll = cos(roll)
        val sinRoll = sin(roll)
        val cosPitch = cos(pitch).coerceAtLeast(0.05f)
        val sinPitch = sin(pitch)
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)

        val relativeNorth = state.northVelocityMS - environment.windNedMS.x
        val relativeEast = state.eastVelocityMS - environment.windNedMS.y
        val relativeUp = state.verticalSpeedMS + environment.windNedMS.z

        val northAcceleration = (
            totalThrust * (cosYaw * sinPitch * cosRoll + sinYaw * sinRoll) -
                params.dragCoefficientXY * relativeNorth
            ) / params.massKg
        val eastAcceleration = (
            totalThrust * (sinYaw * sinPitch * cosRoll - cosYaw * sinRoll) -
                params.dragCoefficientXY * relativeEast
            ) / params.massKg
        val verticalAcceleration = (
            totalThrust * cosPitch * cosRoll -
                params.massKg * params.gravityMS2 -
                params.dragCoefficientZ * relativeUp
            ) / params.massKg

        val newNorthVelocity = state.northVelocityMS + northAcceleration * dt
        val newEastVelocity = state.eastVelocityMS + eastAcceleration * dt
        var newVerticalVelocity = state.verticalSpeedMS + verticalAcceleration * dt

        val deltaNorth = state.northVelocityMS * dt + 0.5f * northAcceleration * dt * dt
        val deltaEast = state.eastVelocityMS * dt + 0.5f * eastAcceleration * dt * dt
        val deltaAltitude = state.verticalSpeedMS * dt + 0.5f * verticalAcceleration * dt * dt

        val newNorth = state.northMeters + deltaNorth
        val newEast = state.eastMeters + deltaEast
        var newAltitudeAgl = state.altitudeAglMeters + deltaAltitude

        val onGround = newAltitudeAgl <= 0f
        if (onGround) {
            newAltitudeAgl = 0f
            if (newVerticalVelocity < 0f) newVerticalVelocity = 0f
        }

        val m1 = motors[0] * motors[0]
        val m2 = motors[1] * motors[1]
        val m3 = motors[2] * motors[2]
        val m4 = motors[3] * motors[3]
        val arm = params.armLengthM / sqrt(2f)
        val tauRoll = arm * params.thrustCoefficient * (m2 + m3 - m1 - m4)
        val tauPitch = arm * params.thrustCoefficient * (m1 + m2 - m3 - m4)
        val tauYaw = params.torqueCoefficient * (m1 + m3 - m2 - m4)

        val p = state.rollSpeedRadS
        val q = state.pitchSpeedRadS
        val r = state.yawSpeedRadS
        val pDot = (tauRoll - (params.izz - params.iyy) * q * r - params.rotationalDrag * p) / params.ixx
        val qDot = (tauPitch - (params.ixx - params.izz) * p * r - params.rotationalDrag * q) / params.iyy
        val rDot = (tauYaw - (params.iyy - params.ixx) * p * q - params.rotationalDrag * r) / params.izz

        val newP = if (onGround) 0f else p + pDot * dt
        val newQ = if (onGround) 0f else q + qDot * dt
        val newR = if (onGround) 0f else r + rDot * dt
        val rollDot = p + (q * sinRoll + r * cosRoll) * tan(pitch)
        val pitchDot = q * cosRoll - r * sinRoll
        val yawDot = (q * sinRoll + r * cosRoll) / cosPitch

        val newRoll = if (onGround) roll * 0.92f else (roll + rollDot * dt).coerceIn(-1.2f, 1.2f)
        val newPitch = if (onGround) pitch * 0.92f else (pitch + pitchDot * dt).coerceIn(-1.2f, 1.2f)
        val newYaw = normalizeRadians(yaw + yawDot * dt)

        val latChange = deltaNorth / METERS_PER_LAT_DEG
        val lonScale = METERS_PER_LAT_DEG * cos(Math.toRadians(state.latitudeDeg)).coerceAtLeast(0.2)
        val lonChange = deltaEast / lonScale
        val groundSpeed = sqrt(newNorthVelocity * newNorthVelocity + newEastVelocity * newEastVelocity)
        val heading = ((Math.toDegrees(newYaw.toDouble()).roundToInt() + 360) % 360).toShort()

        val terrainMslMeters = state.altitudeMslMeters - state.altitudeAglMeters

        return state.copy(
            latitudeDeg = coordinateFrame?.latitude(newNorth.toDouble()) ?: (state.latitudeDeg + latChange),
            longitudeDeg = coordinateFrame?.longitude(newEast.toDouble()) ?: (state.longitudeDeg + lonChange),
            northMeters = newNorth,
            eastMeters = newEast,
            northVelocityMS = if (onGround) 0f else newNorthVelocity,
            eastVelocityMS = if (onGround) 0f else newEastVelocity,
            altitudeAglMeters = newAltitudeAgl,
            altitudeMslMeters = terrainMslMeters + newAltitudeAgl,
            verticalSpeedMS = newVerticalVelocity,
            rollRadians = newRoll,
            pitchRadians = newPitch,
            yawRadians = newYaw,
            rollSpeedRadS = newP,
            pitchSpeedRadS = newQ,
            yawSpeedRadS = newR,
            groundSpeedMS = groundSpeed,
            headingDegrees = heading,
        )
    }

    private fun normalizeRadians(value: Float): Float {
        var angle = value
        val twoPi = (2.0 * PI).toFloat()
        while (angle > PI) angle -= twoPi
        while (angle < -PI) angle += twoPi
        return angle
    }

    private companion object {
        const val METERS_PER_LAT_DEG = 111_320.0
    }
}
