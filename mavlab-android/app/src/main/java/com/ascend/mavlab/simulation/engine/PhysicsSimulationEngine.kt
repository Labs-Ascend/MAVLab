package com.ascend.mavlab.simulation.engine

import com.ascend.mavlab.simulation.autopilot.Autopilot
import com.ascend.mavlab.simulation.autopilot.MissionEngine
import com.ascend.mavlab.simulation.autopilot.PilotInput
import com.ascend.mavlab.simulation.autopilot.PositionController
import com.ascend.mavlab.simulation.failures.FailureInjector
import com.ascend.mavlab.simulation.failures.FailureState
import com.ascend.mavlab.simulation.physics.EnvironmentModel
import com.ascend.mavlab.simulation.physics.PhysicsModel
import com.ascend.mavlab.simulation.physics.QuadcopterParams
import com.ascend.mavlab.simulation.mission.MissionCommand
import com.ascend.mavlab.simulation.mission.MissionItem
import com.ascend.mavlab.simulation.mission.MissionProgress
import com.ascend.mavlab.simulation.mission.MissionUploadPhase
import com.ascend.mavlab.simulation.mission.MissionUploadStatus
import com.ascend.mavlab.simulation.physics.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PhysicsSimulationEngine(
    private val params: QuadcopterParams = QuadcopterParams(),
    private val physics: PhysicsModel = PhysicsModel(params),
    private val autopilot: Autopilot = Autopilot(params),
    private val positionController: PositionController = PositionController(params),
    val failureInjector: FailureInjector = FailureInjector(),
    val missionEngine: MissionEngine = MissionEngine(),
) : SimulationEngine {
    private val mutableState = MutableStateFlow(DroneState())
    override val state: StateFlow<DroneState> = mutableState.asStateFlow()
    val failures: StateFlow<FailureState> = failureInjector.state
    val missionProgress: StateFlow<MissionProgress> = missionEngine.progress
    private val mutableMissionUploadStatus = MutableStateFlow(MissionUploadStatus())
    val missionUploadStatus: StateFlow<MissionUploadStatus> = mutableMissionUploadStatus.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private var startedAtMs = System.currentTimeMillis()
    private var lastTickNs = 0L
    private var batteryUsedWh = 0f
    private var homeNorthMeters = 0f
    private var homeEastMeters = 0f
    private var homeAltitudeMeters = 0f
    private var loiterNorthMeters = 0f
    private var loiterEastMeters = 0f
    private var guidedNorthMeters = 0f
    private var guidedEastMeters = 0f
    private var guidedAltitudeMeters = 0f
    @Volatile
    private var pilotInput = PilotInput(throttle = params.hoverThrottle)

    @Volatile
    var gcsSpeedOverride: Float? = null

    @Volatile
    var wpNavSpeedLimitMps: Float = 3.0f

    override fun start() {
        if (job != null) return
        startedAtMs = System.currentTimeMillis() - mutableState.value.uptimeMs.toLong()
        lastTickNs = System.nanoTime()
        job = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    fun resetBattery() {
        batteryUsedWh = 0f
        val current = mutableState.value
        mutableState.value = current.copy(
            batteryRemainingPercent = 100,
            batteryVoltageMv = (params.batteryVoltageFull * 1000f)
                .roundToInt()
                .coerceIn(0, UShort.MAX_VALUE.toInt())
                .toUInt()
                .toUShort()
        )
    }

    fun setArmed(
        armed: Boolean,
        authority: ControlAuthority = ControlAuthority.CONTROLLER,
    ) {
        autopilot.setArmed(armed, mutableState.value)
        if (armed) {
            val current = mutableState.value
            homeNorthMeters = current.northMeters
            homeEastMeters = current.eastMeters
            homeAltitudeMeters = current.altitudeAglMeters.coerceAtLeast(0f)
            captureLoiterTarget(current)
            positionController.reset()
        }
        mutableState.value = mutableState.value.copy(
            armed = autopilot.armed,
            controlAuthority = authorityAfterArmedChange(armed = autopilot.armed, authority = authority),
        )
    }

    fun setMode(
        mode: FlightMode,
        authority: ControlAuthority = ControlAuthority.CONTROLLER,
    ) {
        val current = mutableState.value
        if (mode == FlightMode.LOITER) {
            captureLoiterTarget(current)
        }
        if (mode == FlightMode.GUIDED) {
            guidedNorthMeters = current.northMeters
            guidedEastMeters = current.eastMeters
            guidedAltitudeMeters = current.altitudeAglMeters.coerceAtLeast(0f)
        }
        val resolvedAuthority = if (mode == FlightMode.AUTO) {
            ControlAuthority.GCS_MISSION
        } else {
            authority
        }
        positionController.reset()
        autopilot.setMode(mode, current)
        mutableState.value = current.copy(
            mode = mode,
            controlAuthority = resolvedAuthority,
        )
    }

    fun takeoff(
        targetAltitudeM: Float,
        authority: ControlAuthority = ControlAuthority.CONTROLLER,
    ) {
        val current = mutableState.value
        guidedNorthMeters = current.northMeters
        guidedEastMeters = current.eastMeters
        guidedAltitudeMeters = targetAltitudeM
        positionController.reset()
        autopilot.takeoff(current, targetAltitudeM)
        mutableState.value = current.copy(
            armed = autopilot.armed,
            mode = autopilot.mode,
            controlAuthority = authority,
        )
    }

    fun land(authority: ControlAuthority = ControlAuthority.CONTROLLER) {
        val current = mutableState.value
        captureLoiterTarget(current)
        positionController.reset()
        autopilot.land()
        mutableState.value = current.copy(
            mode = FlightMode.LAND,
            controlAuthority = authority,
        )
    }

    fun setPilotInput(input: PilotInput) {
        pilotInput = input.copy(
            roll = input.roll.coerceIn(-1f, 1f),
            pitch = input.pitch.coerceIn(-1f, 1f),
            throttle = input.throttle.coerceIn(0f, 1f),
            yaw = input.yaw.coerceIn(-1f, 1f),
        )
        val current = mutableState.value
        if (current.armed && current.controlAuthority != ControlAuthority.GCS_MISSION) {
            mutableState.value = current.copy(controlAuthority = ControlAuthority.CONTROLLER)
        }
    }

    fun setWpNavSpeed(speedMps: Float) {
        if (speedMps == -1f) {
            return
        }
        if (speedMps == -2f) {
            gcsSpeedOverride = null
        } else if (speedMps >= 0f) {
            gcsSpeedOverride = speedMps
        }
    }

    fun setWpNavSpeedParameter(speedMps: Float) {
        if (speedMps > 0f) {
            wpNavSpeedLimitMps = speedMps
        }
    }

    fun setGuidedTarget(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeAglMeters: Float,
        authority: ControlAuthority = ControlAuthority.GCS_DIRECT,
    ) {
        val current = mutableState.value
        val lat = if (latitudeDeg == 0.0 && longitudeDeg == 0.0) current.latitudeDeg else latitudeDeg
        val lon = if (latitudeDeg == 0.0 && longitudeDeg == 0.0) current.longitudeDeg else longitudeDeg
        guidedNorthMeters = current.northMeters + ((lat - current.latitudeDeg) * METERS_PER_LAT_DEG).toFloat()
        guidedEastMeters = current.eastMeters + ((lon - current.longitudeDeg) * lonMetersPerDeg(current)).toFloat()
        guidedAltitudeMeters = altitudeAglMeters.coerceAtLeast(0f)
        autopilot.setTargetAltitude(guidedAltitudeMeters)
        autopilot.setMode(FlightMode.GUIDED, current)
        mutableState.value = current.copy(
            mode = FlightMode.GUIDED,
            controlAuthority = authority,
        )
        noteAck("GUIDED TARGET")
    }

    fun setGuidedOffset(
        northMeters: Float,
        eastMeters: Float,
        altitudeAglMeters: Float,
        authority: ControlAuthority = ControlAuthority.CONTROLLER,
    ) {
        val current = mutableState.value
        guidedNorthMeters = current.northMeters + northMeters
        guidedEastMeters = current.eastMeters + eastMeters
        guidedAltitudeMeters = altitudeAglMeters.coerceAtLeast(0f)
        autopilot.setTargetAltitude(guidedAltitudeMeters)
        autopilot.setMode(FlightMode.GUIDED, current)
        mutableState.value = current.copy(
            mode = FlightMode.GUIDED,
            controlAuthority = authority,
        )
        noteAck("GUIDED OFFSET")
    }

    fun loadMission(items: List<MissionItem>) {
        val current = mutableState.value
        val sorted = items.sortedBy { it.sequence }
        val homeMarker = sorted.takeIf { isQgcHomeMarker(it) }?.firstOrNull()
        val returnAltitude = sorted
            .filterNot { it == homeMarker }
            .filter { it.command == com.ascend.mavlab.simulation.mission.MissionCommand.TAKEOFF || it.command == com.ascend.mavlab.simulation.mission.MissionCommand.WAYPOINT }
            .map { it.altitudeAglMeters }
            .filter { it.isFinite() && it > 0f }
            .maxOrNull()
            ?: current.altitudeAglMeters.coerceAtLeast(0f)
        val sanitized = sorted.map { item ->
            val placeholderPosition = item.latitudeDeg == 0.0 && item.longitudeDeg == 0.0
            val rtlHome = item.command == com.ascend.mavlab.simulation.mission.MissionCommand.RTL
            val takeoffHome = item.command == MissionCommand.TAKEOFF
            val useHomeMarker = placeholderPosition && homeMarker != null && (rtlHome || takeoffHome)
            val lat = when {
                useHomeMarker -> homeMarker.latitudeDeg
                placeholderPosition -> current.latitudeDeg
                else -> item.latitudeDeg
            }
            val lon = when {
                useHomeMarker -> homeMarker.longitudeDeg
                placeholderPosition -> current.longitudeDeg
                else -> item.longitudeDeg
            }
            val altitude = if (rtlHome && item.altitudeAglMeters <= 0f) {
                returnAltitude.coerceAtLeast(MinimumAirborneReturnAltitudeMeters)
            } else {
                item.altitudeAglMeters
            }
            item.copy(
                latitudeDeg = lat,
                longitudeDeg = lon,
                altitudeAglMeters = altitude,
                localNorthMeters = current.northMeters + ((lat - current.latitudeDeg) * METERS_PER_LAT_DEG).toFloat(),
                localEastMeters = current.eastMeters + ((lon - current.longitudeDeg) * lonMetersPerDeg(current)).toFloat(),
            )
        }
        missionEngine.load(sanitized)
        missionEngine.progress.value.activeTarget?.let { autopilot.setTargetAltitude(it.altitudeAglMeters) }
        noteAck("MISSION ${items.size}")
    }

    fun beginMissionUpload(expectedCount: Int, requestedSequence: Int) {
        mutableMissionUploadStatus.value = MissionUploadStatus(
            phase = MissionUploadPhase.UPLOADING,
            expectedCount = expectedCount,
            receivedCount = 0,
            lastRequestedSequence = requestedSequence,
        )
    }

    fun recordMissionUploadProgress(
        expectedCount: Int,
        receivedCount: Int,
        lastReceivedSequence: Int,
        nextRequestedSequence: Int,
    ) {
        mutableMissionUploadStatus.value = MissionUploadStatus(
            phase = MissionUploadPhase.UPLOADING,
            expectedCount = expectedCount,
            receivedCount = receivedCount,
            lastRequestedSequence = nextRequestedSequence,
            lastReceivedSequence = lastReceivedSequence,
        )
    }

    fun acceptMissionUpload(count: Int, lastReceivedSequence: Int? = null) {
        mutableMissionUploadStatus.value = MissionUploadStatus(
            phase = MissionUploadPhase.ACCEPTED,
            expectedCount = count,
            receivedCount = count,
            lastReceivedSequence = lastReceivedSequence,
        )
    }

    fun rejectMissionUpload(reason: String, expectedCount: Int = 0, receivedCount: Int = 0) {
        mutableMissionUploadStatus.value = MissionUploadStatus(
            phase = MissionUploadPhase.REJECTED,
            expectedCount = expectedCount,
            receivedCount = receivedCount,
            lastError = reason,
        )
    }

    fun loadDemoMission() {
        val current = mutableState.value
        loadMission(
            listOf(
                MissionItem(sequence = 0, command = com.ascend.mavlab.simulation.mission.MissionCommand.WAYPOINT, latitudeDeg = current.latitudeDeg, longitudeDeg = current.longitudeDeg, altitudeAglMeters = 0f),
                missionItem(current, sequence = 1, north = 8f, east = 0f, altitude = 8f),
                missionItem(current, sequence = 2, north = 8f, east = 8f, altitude = 10f),
                missionItem(current, sequence = 3, north = 0f, east = 8f, altitude = 10f),
                missionItem(current, sequence = 4, north = 0f, east = 0f, altitude = 6f),
            ),
        )
    }

    fun clearMission() {
        missionEngine.clear()
        mutableMissionUploadStatus.value = MissionUploadStatus()
        noteAck("MISSION CLEAR")
    }

    fun setMissionCurrent(sequence: Int): Boolean {
        val updated = missionEngine.setCurrent(sequence)
        if (updated) {
            missionEngine.progress.value.activeTarget?.let { autopilot.setTargetAltitude(it.altitudeAglMeters) }
            noteAck("MISSION CURRENT $sequence")
        }
        return updated
    }

    internal fun previewAutoPathTarget(
        state: DroneState,
        progress: MissionProgress,
        target: MissionItem,
    ): Vector3 {
        return pathTargetFor(state, progress, target, missionSpeedFor(progress))
    }

    internal fun previewAutoMissionSpeed(progress: MissionProgress): Float {
        return missionSpeedFor(progress)
    }

    internal fun previewLandingPilotInput(
        state: DroneState,
        dt: Float,
    ): PilotInput {
        return landingPilotInput(state, dt)
    }

    fun noteInbound(message: String) {
        mutableState.value = mutableState.value.copy(lastInboundMessage = message)
    }

    fun noteAck(message: String) {
        mutableState.value = mutableState.value.copy(lastAck = message)
    }

    private fun tick() {
        val nowNs = System.nanoTime()
        val dt = ((nowNs - lastTickNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastTickNs = nowNs

        val current = applyFailsafes(mutableState.value)
        val failures = failureInjector.state.value
        val navigationInput = activePilotInput(current, dt)
        val output = autopilot.computeMotorOutput(current, navigationInput, dt)
        val failedMotorSpeeds = failureInjector.applyMotorFailures(output.speeds)
        val motorTelemetry = motorTelemetry(
            commandedSpeeds = output.speeds,
            postFailureSpeeds = failedMotorSpeeds,
            failures = failures,
        )
        val environment = EnvironmentModel(windNedMS = failureInjector.windVectorNed())
        val physicsState = physics.step(current, failedMotorSpeeds, dt, environment)
        val batteryState = updateBattery(physicsState, output.throttle, dt, failures)
        val nowMs = System.currentTimeMillis()

        val simulatedState = batteryState.copy(
            armed = autopilot.armed,
            mode = autopilot.mode,
            uptimeMs = (nowMs - startedAtMs).coerceAtLeast(0L).toUInt(),
            throttlePercent = autopilot.throttlePercent(),
            motors = motorTelemetry,
        )
        mutableState.value = applySensorTelemetry(
            simulatedState.copy(controlAuthority = resolvedAuthorityAfterTick(simulatedState)),
            failures,
        )
    }

    internal fun tickForTest() {
        tick()
    }

    private fun authorityAfterArmedChange(
        armed: Boolean,
        authority: ControlAuthority,
    ): ControlAuthority {
        return when {
            armed -> authority
            authority == ControlAuthority.GCS_DIRECT -> ControlAuthority.GCS_DIRECT
            else -> ControlAuthority.IDLE
        }
    }

    private fun resolvedAuthorityAfterTick(state: DroneState): ControlAuthority {
        val progress = missionEngine.progress.value
        return when {
            !state.armed && state.controlAuthority != ControlAuthority.GCS_DIRECT -> ControlAuthority.IDLE
            state.controlAuthority == ControlAuthority.GCS_MISSION &&
                (!progress.loaded || progress.complete) &&
                state.mode != FlightMode.AUTO -> if (state.armed) ControlAuthority.GCS_DIRECT else ControlAuthority.IDLE
            else -> state.controlAuthority
        }
    }

    private fun activePilotInput(state: DroneState, dt: Float): PilotInput {
        return when {
            !state.armed -> PilotInput(throttle = 0f)
            autopilot.mode == FlightMode.LOITER -> {
                autopilot.setTargetAltitude(autopilot.targetAltitudeM.coerceAtLeast(state.altitudeAglMeters))
                positionController.computePilotInput(
                    state = state,
                    targetNorthMeters = loiterNorthMeters,
                    targetEastMeters = loiterEastMeters,
                    targetAltitudeMeters = autopilot.targetAltitudeM,
                    dt = dt,
                )
            }
            autopilot.mode == FlightMode.GUIDED -> {
                autopilot.setTargetAltitude(guidedAltitudeMeters)
                positionController.computePilotInput(
                    state = state,
                    targetNorthMeters = guidedNorthMeters,
                    targetEastMeters = guidedEastMeters,
                    targetAltitudeMeters = guidedAltitudeMeters,
                    dt = dt,
                    maxHorizontalSpeedMS = gcsSpeedOverride ?: wpNavSpeedLimitMps,
                )
            }
            autopilot.mode == FlightMode.AUTO -> {
                val progress = missionEngine.update(state)
                val target = progress.activeTarget
                if (target == null) {
                    captureLoiterTarget(state)
                    autopilot.setMode(FlightMode.LOITER, state)
                    PilotInput(throttle = 0.5f)
                } else {
                    val missionSpeed = missionSpeedFor(progress)
                    val velSetpoint = computeAutoVelocitySetpoint(state, progress, target, missionSpeed)
                    autopilot.setTargetAltitude(velSetpoint.z)
                    positionController.computePilotInputDirect(
                        state = state,
                        desiredVelNorth = velSetpoint.x,
                        desiredVelEast = velSetpoint.y,
                        targetAltitudeMeters = velSetpoint.z,
                        dt = dt,
                    )
                }
            }
            autopilot.mode == FlightMode.LAND -> landingPilotInput(state, dt)
            autopilot.mode == FlightMode.RTL -> {
                val rtlAltitudeMeters = if (state.altitudeAglMeters > AirborneAltitudeThresholdMeters) {
                    max(homeAltitudeMeters, MinimumAirborneReturnAltitudeMeters)
                } else {
                    homeAltitudeMeters
                }
                autopilot.setTargetAltitude(rtlAltitudeMeters)
                positionController.computePilotInput(
                    state = state,
                    targetNorthMeters = homeNorthMeters,
                    targetEastMeters = homeEastMeters,
                    targetAltitudeMeters = autopilot.targetAltitudeM,
                    dt = dt,
                    maxHorizontalSpeedMS = gcsSpeedOverride ?: wpNavSpeedLimitMps,
                )
            }
            else -> pilotInput
        }
    }

    private fun landingPilotInput(state: DroneState, dt: Float): PilotInput {
        return positionController.computePilotInput(
            state = state,
            targetNorthMeters = loiterNorthMeters,
            targetEastMeters = loiterEastMeters,
            targetAltitudeMeters = autopilot.targetAltitudeM,
            dt = dt,
            maxHorizontalSpeedMS = LandingHorizontalHoldSpeedMps,
        )
    }

    private fun applyFailsafes(state: DroneState): DroneState {
        val failures = failureInjector.state.value
        if (!failures.gpsEnabled &&
            (autopilot.mode == FlightMode.LOITER || autopilot.mode == FlightMode.GUIDED || autopilot.mode == FlightMode.AUTO)
        ) {
            autopilot.setMode(FlightMode.ALT_HOLD, state)
            noteAck("GPS FAILSAFE")
            return state.copy(mode = FlightMode.ALT_HOLD)
        }
        if (state.batteryRemainingPercent.toInt() <= 20 && autopilot.armed && autopilot.mode != FlightMode.RTL) {
            autopilot.setMode(FlightMode.RTL, state)
            noteAck("BATTERY RTL")
            return state.copy(mode = FlightMode.RTL)
        }
        return state
    }

    private fun applySensorTelemetry(state: DroneState, failures: FailureState): DroneState {
        val noisyNorth = if (failures.gpsEnabled) {
            state.northMeters + randomSigned(failures.gpsNoiseMultiplier - 1f) * 0.35f
        } else {
            state.northMeters
        }
        val noisyEast = if (failures.gpsEnabled) {
            state.eastMeters + randomSigned(failures.gpsNoiseMultiplier - 1f) * 0.35f
        } else {
            state.eastMeters
        }
        val northNoise = noisyNorth - state.northMeters
        val eastNoise = noisyEast - state.eastMeters
        val headingOffset = if (failures.compassEnabled) failures.compassOffsetDeg else 0f
        return state.copy(
            latitudeDeg = if (failures.gpsEnabled) state.latitudeDeg + northNoise / METERS_PER_LAT_DEG else state.latitudeDeg,
            longitudeDeg = if (failures.gpsEnabled) state.longitudeDeg + eastNoise / lonMetersPerDeg(state) else state.longitudeDeg,
            gpsFixType = if (failures.gpsEnabled) 3u else 0u,
            gpsSatellites = if (failures.gpsEnabled) 12u else 0u,
            headingDegrees = (((state.headingDegrees + headingOffset).toInt() % 360 + 360) % 360).toShort(),
            lastInboundMessage = when {
                failures.lostLinkActive -> "Lost link active"
                failures.unsafeMissionReserveActive -> "Unsafe mission reserve"
                kotlin.math.abs(failures.barometerOffsetMeters) > 0.05f -> "Barometer offset %.1f m".format(failures.barometerOffsetMeters)
                !failures.gpsEnabled -> "GPS unavailable"
                failures.hasMotorFailure -> "Motor failure mask ${failures.motorFailureMask}"
                failures.windSpeedMs > 0f -> "Wind %.1f m/s".format(failures.windSpeedMs)
                else -> state.lastInboundMessage
            },
        )
    }

    private fun motorTelemetry(
        commandedSpeeds: FloatArray,
        postFailureSpeeds: FloatArray,
        failures: FailureState,
    ): List<MotorTelemetry> {
        return List(4) { index ->
            val failed = failures.motorFailureMask and (1 shl index) != 0
            MotorTelemetry(
                rpm = radPerSecondToRpm(postFailureSpeeds.getOrElse(index) { 0f }),
                command = (commandedSpeeds.getOrElse(index) { 0f } / params.motorMaxSpeedRadS).coerceIn(0f, 1f),
                failed = failed,
            )
        }
    }

    private fun radPerSecondToRpm(radPerSecond: Float): Float {
        return radPerSecond.coerceAtLeast(0f) * 60f / (2f * PI.toFloat())
    }

    private fun updateBattery(
        state: DroneState,
        throttle: Float,
        dt: Float,
        failures: FailureState,
    ): DroneState {
        val payloadFactor = 1f + failures.payloadMassKg * 0.18f
        val currentA = if (autopilot.armed) {
            (params.hoverCurrentDrawA * (0.25f + throttle * 1.5f) * failures.batteryDrainMultiplier * payloadFactor)
                .coerceAtLeast(0f)
        } else {
            0.15f
        }
        val previousRemaining = state.batteryRemainingPercent.toInt().coerceIn(0, 100) / 100f
        val voltage = params.batteryVoltageEmpty +
            (params.batteryVoltageFull - params.batteryVoltageEmpty) * previousRemaining
        batteryUsedWh = (batteryUsedWh + voltage * currentA * dt / 3600f)
            .coerceIn(0f, params.batteryCapacityWh)
        val remaining = (1f - batteryUsedWh / params.batteryCapacityWh).coerceIn(0f, 1f)
        return state.copy(
            armed = autopilot.armed,
            batteryVoltageMv = (params.batteryVoltageEmpty +
                (params.batteryVoltageFull - params.batteryVoltageEmpty) * remaining)
                .times(1000f)
                .roundToInt()
                .coerceIn(0, UShort.MAX_VALUE.toInt())
                .toUInt()
                .toUShort(),
            batteryCurrentCa = (currentA * 100f)
                .roundToInt()
                .coerceIn(0, Short.MAX_VALUE.toInt())
                .toShort(),
            batteryRemainingPercent = (remaining * 100f)
                .roundToInt()
                .coerceIn(0, 100)
                .toByte(),
        )
    }

    private fun captureLoiterTarget(state: DroneState) {
        loiterNorthMeters = state.northMeters
        loiterEastMeters = state.eastMeters
    }

    private fun localTargetFor(state: DroneState, item: MissionItem): Vector3 {
        val north = item.localNorthMeters ?: run {
            val lat = if (item.latitudeDeg == 0.0 && item.longitudeDeg == 0.0) state.latitudeDeg else item.latitudeDeg
            state.northMeters + ((lat - state.latitudeDeg) * METERS_PER_LAT_DEG).toFloat()
        }
        val east = item.localEastMeters ?: run {
            val lon = if (item.latitudeDeg == 0.0 && item.longitudeDeg == 0.0) state.longitudeDeg else item.longitudeDeg
            state.eastMeters + ((lon - state.longitudeDeg) * lonMetersPerDeg(state)).toFloat()
        }
        return Vector3(x = north, y = east, z = item.altitudeAglMeters)
    }

    private fun pathTargetFor(
        state: DroneState,
        progress: MissionProgress,
        target: MissionItem,
        missionSpeedMetersPerSecond: Float,
    ): Vector3 {
        if (target.command == MissionCommand.TAKEOFF) {
            if (state.altitudeAglMeters < target.altitudeAglMeters - TakeoffHorizontalGateAltitudeToleranceMeters) {
                return Vector3(
                    x = state.northMeters,
                    y = state.eastMeters,
                    z = target.altitudeAglMeters,
                )
            }
            return localTargetFor(state, target)
        }
        if (target.command == MissionCommand.LAND) {
            return localTargetFor(state, target)
        }
        val endpoint = localTargetFor(state, target)
        val start = previousPathAnchorFor(state, progress) ?: return endpoint
        val legNorth = endpoint.x - start.x
        val legEast = endpoint.y - start.y
        val legLength = sqrt(legNorth * legNorth + legEast * legEast)
        if (legLength < 0.5f) return endpoint

        val vehicleNorth = state.northMeters - start.x
        val vehicleEast = state.eastMeters - start.y
        val alongTrack = ((vehicleNorth * legNorth + vehicleEast * legEast) / (legLength * legLength))
            .coerceIn(0f, 1f)
        val lookaheadMeters = max(
            PathLookaheadMeters,
            missionSpeedMetersPerSecond.coerceAtLeast(DefaultMissionSpeedMetersPerSecond) *
                MissionSpeedLookaheadSeconds,
        )
        val lookahead = (lookaheadMeters / legLength).coerceIn(0f, 1f)
        val targetFraction = (alongTrack + lookahead).coerceAtMost(1f)
        val altitude = start.z + (endpoint.z - start.z) * targetFraction
        return Vector3(
            x = start.x + legNorth * targetFraction,
            y = start.y + legEast * targetFraction,
            z = altitude,
        )
    }

    private fun missionSpeedFor(progress: MissionProgress): Float {
        val activeNavIndex = progress.currentIndex
        var nextNavIndex = activeNavIndex + 1
        while (nextNavIndex < progress.items.size) {
            if (progress.items[nextNavIndex].command.isNav) break
            nextNavIndex++
        }
        for (index in nextNavIndex - 1 downTo 0) {
            val item = progress.items.getOrNull(index) ?: continue
            if (item.command == MissionCommand.CHANGE_SPEED && item.speedMetersPerSecond != null && item.speedMetersPerSecond > 0f) {
                return item.speedMetersPerSecond
            }
        }
        return gcsSpeedOverride ?: wpNavSpeedLimitMps
    }

    private fun computeAutoVelocitySetpoint(
        state: DroneState,
        progress: MissionProgress,
        target: MissionItem,
        speed: Float,
    ): Vector3 {
        if (target.command == MissionCommand.TAKEOFF) {
            if (state.altitudeAglMeters < target.altitudeAglMeters - TakeoffHorizontalGateAltitudeToleranceMeters) {
                return Vector3(0f, 0f, target.altitudeAglMeters)
            }
        }
        
        val endpoint = localTargetFor(state, target)
        val start = previousPathAnchorFor(state, progress) ?: Vector3(state.northMeters, state.eastMeters, state.altitudeAglMeters)
        
        // 2D calculations (North-East plane)
        val legN = endpoint.x - start.x
        val legE = endpoint.y - start.y
        val legLenSq = legN * legN + legE * legE
        val legLen = sqrt(legLenSq)
        
        if (legLen < 0.1f) {
            // Target is virtually at start, just command velocity towards target
            val errN = endpoint.x - state.northMeters
            val errE = endpoint.y - state.eastMeters
            val dist = sqrt(errN * errN + errE * errE)
            if (dist < 0.1f) return Vector3(0f, 0f, endpoint.z)
            
            // Decel speed calculation
            val decelLimit = sqrt(2 * 1.5f * dist)
            val targetSpeed = speed.coerceAtMost(decelLimit)
            return Vector3(
                x = (errN / dist) * targetSpeed,
                y = (errE / dist) * targetSpeed,
                z = endpoint.z
            )
        }
        
        // Vector from start to drone
        val droneN = state.northMeters - start.x
        val droneE = state.eastMeters - start.y
        
        // Project drone onto the leg vector (2D along track)
        val t = ((droneN * legN + droneE * legE) / legLenSq).coerceIn(0f, 1f)
        
        // Along-track projection point
        val projN = start.x + t * legN
        val projE = start.y + t * legE
        
        // Remaining distance to endpoint along track
        val remainingDist = sqrt((endpoint.x - projN) * (endpoint.x - projN) + (endpoint.y - projE) * (endpoint.y - projE))
        
        // Target speed along track with smooth physical braking/deceleration
        val alongTrackLimit = sqrt(2 * 1.5f * remainingDist)
        val alongTrackSpeed = speed.coerceAtMost(alongTrackLimit)
        
        // Along-track velocity vector (unit direction of leg * speed)
        val unitLegN = legN / legLen
        val unitLegE = legE / legLen
        val velAlongN = unitLegN * alongTrackSpeed
        val velAlongE = unitLegE * alongTrackSpeed
        
        // Cross-track error vector (from projection point to drone)
        val crossN = state.northMeters - projN
        val crossE = state.eastMeters - projE
        
        // Cross-track correction velocity (K_cross = 1.0f)
        val kCross = 1.0f
        val velCrossN = -crossN * kCross
        val velCrossE = -crossE * kCross
        
        // Combine along-track and cross-track velocities
        var totalVelN = velAlongN + velCrossN
        var totalVelE = velAlongE + velCrossE
        
        // Clamp total velocity magnitude to `speed`
        val totalVelMag = sqrt(totalVelN * totalVelN + totalVelE * totalVelE)
        if (totalVelMag > speed) {
            val scale = speed / totalVelMag
            totalVelN *= scale
            totalVelE *= scale
        }
        
        // Smoothly interpolate altitude target
        val targetAlt = start.z + (endpoint.z - start.z) * t
        
        return Vector3(x = totalVelN, y = totalVelE, z = targetAlt)
    }

    private fun previousPathAnchorFor(state: DroneState, progress: MissionProgress): Vector3? {
        for (index in progress.currentIndex - 1 downTo 0) {
            val item = progress.items.getOrNull(index) ?: continue
            if (item.command == MissionCommand.WAYPOINT ||
                item.command == MissionCommand.TAKEOFF ||
                item.command == MissionCommand.RTL
            ) {
                return localTargetFor(state, item)
            }
        }
        return null
    }

    private fun isQgcHomeMarker(items: List<MissionItem>): Boolean {
        val first = items.firstOrNull() ?: return false
        val second = items.getOrNull(1) ?: return false
        return first.sequence == 0 &&
            first.command == MissionCommand.WAYPOINT &&
            second.command == MissionCommand.TAKEOFF
    }

    private fun missionItem(
        state: DroneState,
        sequence: Int,
        north: Float,
        east: Float,
        altitude: Float,
    ): MissionItem {
        return MissionItem(
            sequence = sequence,
            command = com.ascend.mavlab.simulation.mission.MissionCommand.WAYPOINT,
            latitudeDeg = state.latitudeDeg + north / METERS_PER_LAT_DEG,
            longitudeDeg = state.longitudeDeg + east / lonMetersPerDeg(state),
            altitudeAglMeters = altitude,
        )
    }

    private fun lonMetersPerDeg(state: DroneState): Double {
        return METERS_PER_LAT_DEG * max(0.2, cos(Math.toRadians(state.latitudeDeg)))
    }

    private fun randomSigned(scale: Float): Float {
        if (scale <= 0f) return 0f
        return ((Math.random().toFloat() * 2f) - 1f) * scale
    }

    private companion object {
        const val TICK_MS = 10L
        const val METERS_PER_LAT_DEG = 111_320.0
        const val PathLookaheadMeters = 8f
        const val MissionSpeedLookaheadSeconds = 1.5f
        const val TakeoffHorizontalGateAltitudeToleranceMeters = 1.5f
        const val DefaultMissionSpeedMetersPerSecond = 3.0f
        const val LandingHorizontalHoldSpeedMps = 1.5f
        const val MinimumAirborneReturnAltitudeMeters = 8f
        const val AirborneAltitudeThresholdMeters = 0.5f
    }
}
