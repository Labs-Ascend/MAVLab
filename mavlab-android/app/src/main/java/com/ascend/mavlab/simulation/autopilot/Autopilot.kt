package com.ascend.mavlab.simulation.autopilot

import com.ascend.mavlab.simulation.engine.DroneState
import com.ascend.mavlab.simulation.engine.FlightMode
import com.ascend.mavlab.simulation.physics.MotorMixer
import com.ascend.mavlab.simulation.physics.MotorOutput
import com.ascend.mavlab.simulation.physics.QuadcopterParams
import kotlin.math.roundToInt

class Autopilot(
    private val params: QuadcopterParams = QuadcopterParams(),
    private val mixer: MotorMixer = MotorMixer(params),
) {
    private val rollAttitude = PIDController(kP = 4.2f, outputMin = -4f, outputMax = 4f)
    private val pitchAttitude = PIDController(kP = 4.2f, outputMin = -4f, outputMax = 4f)
    private val rollRate = PIDController(kP = 0.16f, kI = 0.03f, kD = 0.004f, outputMin = -1f, outputMax = 1f)
    private val pitchRate = PIDController(kP = 0.16f, kI = 0.03f, kD = 0.004f, outputMin = -1f, outputMax = 1f)
    private val yawRate = PIDController(kP = 0.22f, kI = 0.02f, outputMin = -1f, outputMax = 1f)
    private val altitude = PIDController(kP = 0.18f, kI = 0.04f, kD = 0.06f, iMax = 5f, outputMin = -0.35f, outputMax = 0.35f)

    var armed: Boolean = false
        private set
    var mode: FlightMode = FlightMode.STABILIZE
        private set
    var targetAltitudeM: Float = 0f
        private set
    var lastThrottle: Float = 0f
        private set

    fun setArmed(value: Boolean, state: DroneState) {
        if (value && state.batteryRemainingPercent.toInt() <= 0) {
            armed = false
            return
        }
        if (value && !armed && mode == FlightMode.LAND) {
            // Arming while still in LAND mode on the ground would instantly
            // re-trigger the landed auto-disarm; a fresh arm starts a new flight.
            mode = FlightMode.STABILIZE
        }
        armed = value
        if (value) {
            targetAltitudeM = state.altitudeAglMeters
        } else {
            resetControllers()
            lastThrottle = 0f
        }
    }

    fun setMode(value: FlightMode, state: DroneState) {
        mode = value
        if (value == FlightMode.ALT_HOLD ||
            value == FlightMode.GUIDED ||
            value == FlightMode.LOITER || value == FlightMode.BRAKE ||
            value == FlightMode.AUTO ||
            value == FlightMode.RTL
        ) {
            targetAltitudeM = state.altitudeAglMeters.coerceAtLeast(0f)
            altitude.reset()
        }
    }

    fun takeoff(state: DroneState, targetAltitude: Float) {
        if (state.batteryRemainingPercent.toInt() <= 0) {
            armed = false
            return
        }
        armed = true
        mode = FlightMode.GUIDED
        targetAltitudeM = targetAltitude.coerceAtLeast(state.altitudeAglMeters + 0.5f)
        altitude.reset()
    }

    fun land() {
        mode = FlightMode.LAND
        altitude.reset()
    }

    fun setTargetAltitude(targetAltitude: Float) {
        targetAltitudeM = targetAltitude.coerceAtLeast(0f)
    }

    fun computeMotorOutput(state: DroneState, input: PilotInput, dt: Float): MotorOutput {
        if (!armed) {
            resetControllers()
            return MotorOutput(FloatArray(4), throttle = 0f).also { lastThrottle = 0f }
        }

        if (mode == FlightMode.LAND) {
            targetAltitudeM = (targetAltitudeM - params.maxDescentRateMS * 0.35f * dt).coerceAtLeast(0f)
            if (state.altitudeAglMeters <= 0.05f && state.verticalSpeedMS <= 0.05f) {
                armed = false
                return MotorOutput(FloatArray(4), throttle = 0f).also { lastThrottle = 0f }
            }
        }

        val desiredRoll = (input.roll * params.maxTiltAngleRad).coerceIn(
            -params.maxTiltAngleRad,
            params.maxTiltAngleRad,
        )
        val desiredPitch = (input.pitch * params.maxTiltAngleRad).coerceIn(
            -params.maxTiltAngleRad,
            params.maxTiltAngleRad,
        )
        val desiredYawRate = input.yaw.coerceIn(-1f, 1f) * 2.5f

        val desiredRollRate = rollAttitude.update(desiredRoll - state.rollRadians, dt)
        val desiredPitchRate = pitchAttitude.update(desiredPitch - state.pitchRadians, dt)
        val rollCommand = rollRate.update(desiredRollRate - state.rollSpeedRadS, dt)
        val pitchCommand = pitchRate.update(desiredPitchRate - state.pitchSpeedRadS, dt)
        val yawCommand = yawRate.update(desiredYawRate - state.yawSpeedRadS, dt)

        if (mode == FlightMode.ALT_HOLD) {
            updateTargetAltitudeFromThrottle(input.throttle, dt)
        }


        val throttle = when (mode) {
            FlightMode.ALT_HOLD,
            FlightMode.GUIDED,
            FlightMode.LOITER,
            FlightMode.BRAKE,
            FlightMode.AUTO,
            FlightMode.LAND,
            FlightMode.RTL,
            -> altitudeThrottle(state, dt)
            FlightMode.STABILIZE -> input.throttle
        }.coerceIn(0f, 1f)

        return mixer.mix(
            throttle = throttle,
            roll = rollCommand,
            pitch = pitchCommand,
            yaw = yawCommand,
        ).also { output ->
            lastThrottle = output.throttle
        }
    }

    fun throttlePercent(): UByte = (lastThrottle * 100f)
        .roundToInt()
        .coerceIn(0, 100)
        .toUInt()
        .toUByte()

    private fun altitudeThrottle(state: DroneState, dt: Float): Float {
        val altitudeError = targetAltitudeM - state.altitudeAglMeters
        val velocityDamping = -state.verticalSpeedMS * 0.10f
        return params.hoverThrottle + altitude.update(altitudeError, dt) + velocityDamping
    }

    private fun updateTargetAltitudeFromThrottle(throttle: Float, dt: Float) {
        val centered = throttle.coerceIn(0f, 1f) - 0.5f
        if (kotlin.math.abs(centered) < 0.04f) return
        val rate = if (centered > 0f) {
            centered * 2f * params.maxClimbRateMS
        } else {
            centered * 2f * params.maxDescentRateMS
        }
        targetAltitudeM = (targetAltitudeM + rate * dt).coerceAtLeast(0f)
    }

    private fun resetControllers() {
        rollAttitude.reset()
        pitchAttitude.reset()
        rollRate.reset()
        pitchRate.reset()
        yawRate.reset()
        altitude.reset()
    }
}
