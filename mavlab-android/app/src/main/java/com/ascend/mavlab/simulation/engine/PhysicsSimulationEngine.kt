package com.ascend.mavlab.simulation.engine

import com.ascend.mavlab.simulation.coordinates.SimulationCoordinateFrame
import com.ascend.mavlab.simulation.mission.MissionOrigin
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
import com.ascend.mavlab.simulation.safety.*
import kotlin.math.PI
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
    private val mutableSafetyPlans = MutableStateFlow(SafetyPlans())
    val safetyPlans: StateFlow<SafetyPlans> = mutableSafetyPlans.asStateFlow()
    private var fenceGeometry = FenceGeometry.compile(emptyList())
    private var geographicOrigin = SimLocation.Nairobi
    private var coordinateFrame = SimulationCoordinateFrame(geographicOrigin.latitudeDeg, geographicOrigin.longitudeDeg)
    private data class ReturnTarget(val north: Float, val east: Float, val altitude: Float, val label: String)
    private var returnTarget: ReturnTarget? = null
    private var returnLabel = "None"
    private var returnClimbed = false
    private var missionReturn = false
    private var fenceRecovering = false
    private var minimumFenceReached = false

    @Synchronized
    fun replaceSafetyPlan(type: Int, items: List<SafetyItem>): Boolean {
        if (state.value.armed) { noteSafetyTransfer("Rejected: disarm before changing safety plans"); return false }
        try { FenceGeometry.validate(items, type) } catch (e: IllegalArgumentException) {
            noteSafetyTransfer("Rejected: ${e.message}"); return false
        }
        val previous = mutableSafetyPlans.value
        if (type == 1) fenceGeometry = FenceGeometry.compile(items)
        mutableSafetyPlans.value = if (type == 1) previous.copy(fence = items.toList(), lastTransfer = "Fence accepted · ${items.size} items")
            else previous.copy(rally = items.toList(), lastTransfer = "Rally accepted · ${items.size} points")
        return true
    }

    @Synchronized
    fun setSafetyParameter(id: String, value: Float): Boolean {
        val previous = mutableSafetyPlans.value
        val updated = previous.config.updated(id, value) ?: return false
        // Configuration is deliberate and disarmed-only, except disabling the fence to leave recovery.
        if (state.value.armed && !(id == "FENCE_ENABLE" && value == 0f)) return false
        mutableSafetyPlans.value = previous.copy(config = updated)
        if (!updated.enabled) fenceRecovering = false
        mutableState.value = state.value.copy(safety = state.value.safety.copy(
            enabled = updated.enabled, breached = false, recovery = if (fenceRecovering) state.value.safety.recovery else "None"),
            controlAuthority = if (!updated.enabled && state.value.controlAuthority == ControlAuthority.SAFETY_RECOVERY) ControlAuthority.CONTROLLER else state.value.controlAuthority)
        return true
    }

    @Synchronized
    fun clearSafetyPlans(type: Int = 255, clearNavigation: Boolean = false, disableFence: Boolean = false): Boolean {
        if (state.value.armed || type !in listOf(1, 2, 255)) return false
        val p = mutableSafetyPlans.value
        if (type == 1 || type == 255) fenceGeometry = FenceGeometry.compile(emptyList())
        mutableSafetyPlans.value = p.copy(
            fence = if (type == 1 || type == 255) emptyList() else p.fence,
            rally = if (type == 2 || type == 255) emptyList() else p.rally,
            config = if (disableFence) p.config.copy(enabled = false) else p.config,
            lastTransfer = "Safety plan $type cleared",
        )
        if (clearNavigation) clearMission()
        if (disableFence) {
            fenceRecovering = false
            mutableState.value = state.value.copy(safety = state.value.safety.copy(enabled = false, breached = false, recovery = "None"))
        }
        return true
    }

    @Synchronized
    fun restoreSafetyPlans(plans: SafetyPlans): Boolean {
        if (state.value.armed) return false
        try { FenceGeometry.validate(plans.fence, 1); FenceGeometry.validate(plans.rally, 2) }
        catch (_: IllegalArgumentException) { return false }
        if (SafetyPlanCodec.decode(SafetyPlanCodec.encode(plans)) == null) return false
        fenceGeometry = FenceGeometry.compile(plans.fence)
        mutableSafetyPlans.value = plans
        mutableState.value = state.value.copy(safety = SafetyStatus(enabled = plans.config.enabled))
        return true
    }

    @Synchronized fun noteSafetyTransfer(message: String) {
        mutableSafetyPlans.value = mutableSafetyPlans.value.copy(lastTransfer = message)
    }

    @Synchronized
    fun physicalPoseSnapshot(): com.ascend.mavlab.simulation.coordinates.PhysicalPoseSnapshot {
        val current = mutableState.value
        return com.ascend.mavlab.simulation.coordinates.PhysicalPoseSnapshot(
            coordinateFrame, current.northMeters.toDouble(), current.eastMeters.toDouble(),
            current.altitudeAglMeters.toDouble(), current.yawRadians.toDouble(), System.nanoTime(), geographicOrigin.altitudeMslMeters.toDouble(),
        )
    }

    private fun physicalPoint(state: DroneState, north: Float = state.northMeters, east: Float = state.eastMeters) = GeoPoint(
        coordinateFrame.latitude(north.toDouble()),
        coordinateFrame.longitude(east.toDouble()),
    )

    private fun fenceBreach(state: DroneState, north: Float = state.northMeters, east: Float = state.eastMeters,
                            altitude: Float = state.altitudeAglMeters, margin: Float = 0f, landing: Boolean = false): Int {
        val c = mutableSafetyPlans.value.config
        if (!c.enabled) return 0
        if (c.types and 1 != 0 && altitude - homeAltitudeMeters > c.maxAltitude - margin) return 2
        if (!landing && state.armed && (minimumFenceReached || margin > 0f) && c.types and 8 != 0 && altitude - homeAltitudeMeters < c.minAltitude + margin) return 1
        if (c.types and 2 != 0 && kotlin.math.hypot(north - homeNorthMeters, east - homeEastMeters) > c.radius - margin) return 3
        if (c.types and 4 != 0 && !fenceGeometry.allows(physicalPoint(state, north, east), c.options and 2 != 0, margin)) return 3
        return 0
    }

    private fun chooseReturnTarget(state: DroneState): ReturnTarget? {
        val plans = mutableSafetyPlans.value
        val c = plans.config
        fun target(item: SafetyItem, label: String): ReturnTarget? {
            val north = coordinateFrame.north(item.latitude).toFloat()
            val east = coordinateFrame.east(item.longitude).toFloat()
            val altitude = if (item.frame in listOf(0, 5)) item.altitude - geographicOrigin.altitudeMslMeters else homeAltitudeMeters + item.altitude
            if (altitude <= 0.25f || !altitude.isFinite() || kotlin.math.hypot(north, east) > 50_000f) return null
            return ReturnTarget(north, east, altitude, label)
        }
        fun distance(t: ReturnTarget) = kotlin.math.hypot(t.north - state.northMeters, t.east - state.eastMeters)
        fun eligible(t: ReturnTarget) = fenceBreach(state, t.north, t.east, t.altitude, c.margin) == 0
        val home = ReturnTarget(homeNorthMeters, homeEastMeters, homeAltitudeMeters + c.rtlAltitude, "Home")
        val candidates = plans.rally.mapNotNull { target(it, "Rally ${it.sequence + 1}") }
            .filter { (c.rallyLimitKm == 0f || distance(it) <= c.rallyLimitKm * 1000f) && eligible(it) }.toMutableList()
        if (c.rallyIncludeHome && eligible(home)) candidates.add(home)
        if (candidates.isNotEmpty()) return candidates.minBy { distance(it) }
        plans.fence.firstOrNull { it.command == 5000 }?.let { target(it, "Fence return") }?.takeIf { eligible(it) }?.let { return it }
        return home.takeIf { eligible(it) }
    }

    private fun startReturn(state: DroneState) {
        motorSpeedOverrideRpm = null
        if (state.altitudeAglMeters <= 0.25f && kotlin.math.abs(state.verticalSpeedMS) <= 0.2f) {
            returnTarget = null
            returnLabel = "Land here · on ground"
            captureLoiterTarget(state)
            autopilot.setTargetAltitude(0f)
            autopilot.land()
            mutableState.value = mutableState.value.copy(safety = state.safety.copy(returnTarget = returnLabel))
            return
        }
        returnTarget = chooseReturnTarget(state)
        returnClimbed = false
        positionController.reset()
        captureLoiterTarget(state)
        if (returnTarget == null || !failureInjector.state.value.gpsEnabled) {
            captureLoiterTarget(state)
            autopilot.setTargetAltitude(state.altitudeAglMeters)
            autopilot.land()
            returnLabel = "Land here · no eligible return"
        } else {
            returnLabel = returnTarget!!.label
        }
        mutableState.value = mutableState.value.copy(safety = state.safety.copy(returnTarget = returnLabel))
    }

    private fun enforceFence(state: DroneState): DroneState {
        val c = mutableSafetyPlans.value.config
        if (!state.armed) minimumFenceReached = false
        else if (state.altitudeAglMeters - homeAltitudeMeters >= c.minAltitude) minimumFenceReached = true
        val type = fenceBreach(state, landing = autopilot.mode == FlightMode.LAND || !state.armed)
        val previous = state.safety
        val newBreach = state.armed && type != 0 && (!previous.breached || previous.breachType != type)
        var status = previous.copy(enabled = c.enabled, breached = type != 0,
            breachType = if (type != 0) type else previous.breachType,
            breachCount = if (newBreach) (previous.breachCount + 1).coerceAtMost(65535) else previous.breachCount,
            breachTimeMs = if (newBreach) state.uptimeMs else previous.breachTimeMs)
        if (!state.armed || !c.enabled) fenceRecovering = false
        if (state.armed && c.enabled && type != 0 && c.action != 0 && !fenceRecovering) {
            fenceRecovering = true
            missionReturn = false
            motorSpeedOverrideRpm = null
            if (c.action == 2 || !failureInjector.state.value.gpsEnabled) {
                captureLoiterTarget(state); autopilot.setTargetAltitude(state.altitudeAglMeters); autopilot.land()
                status = status.copy(returnTarget = "Land here")
                returnLabel = "Land here"
            } else {
                autopilot.setMode(FlightMode.RTL, state)
                mutableState.value = state.copy(safety = status)
                startReturn(mutableState.value)
                status = mutableState.value.safety
            }
            noteAck("FENCE BREACH $type · ${autopilot.mode.displayName}")
        }
        if (fenceRecovering && autopilot.mode !in listOf(FlightMode.RTL, FlightMode.LAND)) {
            autopilot.setMode(FlightMode.RTL, state); startReturn(state)
        }
        status = status.copy(recovery = if (fenceRecovering) "Fence ${autopilot.mode.displayName}" else "None")
        return state.copy(mode = autopilot.mode, safety = status,
            controlAuthority = if (fenceRecovering) ControlAuthority.SAFETY_RECOVERY else state.controlAuthority)
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private var startedAtMs = System.currentTimeMillis()
    private var lastTickNs = 0L
    private var batteryUsedWh = 0f
    private var homeNorthMeters = 0f
    private var homeEastMeters = 0f
    @Volatile
    private var motorSpeedOverrideRpm: Float? = null
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

    @Synchronized fun resetBattery() {
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

    @Synchronized fun setMotorSpeedOverrideRpm(rpm: Float?) {
        if (fenceRecovering && rpm != null) return
        motorSpeedOverrideRpm = rpm
    }

    @Synchronized
    fun setHomeLocation(location: SimLocation): Boolean {
        val safeLocation = location.sanitized()
        if (!safeLocation.latitudeDeg.isFinite() || !safeLocation.longitudeDeg.isFinite() || !safeLocation.altitudeMslMeters.isFinite()) return false
        val current = mutableState.value
        if (current.armed) return false
        clearSafetyPlans(clearNavigation = true, disableFence = true)
        returnLabel = "None"
        returnTarget = null
        geographicOrigin = safeLocation
        coordinateFrame = SimulationCoordinateFrame(safeLocation.latitudeDeg, safeLocation.longitudeDeg)
        homeNorthMeters = 0f
        homeEastMeters = 0f
        homeAltitudeMeters = 0f
        loiterNorthMeters = 0f
        loiterEastMeters = 0f
        guidedNorthMeters = 0f
        guidedEastMeters = 0f
        guidedAltitudeMeters = 0f
        mutableState.value = current.copy(
            latitudeDeg = safeLocation.latitudeDeg,
            longitudeDeg = safeLocation.longitudeDeg,
            northMeters = 0f,
            eastMeters = 0f,
            northVelocityMS = 0f,
            eastVelocityMS = 0f,
            altitudeMslMeters = safeLocation.altitudeMslMeters,
            altitudeAglMeters = 0f,
            verticalSpeedMS = 0f,
            groundSpeedMS = 0f,
            rollRadians = 0f,
            pitchRadians = 0f,
            rollSpeedRadS = 0f,
            pitchSpeedRadS = 0f,
            yawSpeedRadS = 0f,
            motors = List(4) { MotorTelemetry() },
            lastAck = "SIM LOCATION ${safeLocation.label}",
            safety = SafetyStatus(),
        )
        positionController.reset()
        return true
    }

    @Synchronized fun setHomeToCurrentPosition(): Boolean {
        val current = mutableState.value
        homeNorthMeters = current.northMeters
        homeEastMeters = current.eastMeters
        homeAltitudeMeters = current.altitudeAglMeters.coerceAtLeast(0f)
        return true
    }

    @Synchronized fun setHomeToCoordinates(latitudeDeg: Double, longitudeDeg: Double): Boolean {
        if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) return false
        if (latitudeDeg !in -90.0..90.0 || longitudeDeg !in -180.0..180.0) return false
        homeNorthMeters = coordinateFrame.north(latitudeDeg).toFloat()
        homeEastMeters = coordinateFrame.east(longitudeDeg).toFloat()
        homeAltitudeMeters = 0f
        return true
    }

    fun homePosition(): HomePosition {
        val current = mutableState.value
        val point = physicalPoint(current, homeNorthMeters, homeEastMeters)
        return HomePosition(
            latitudeDeg = point.latitude,
            longitudeDeg = point.longitude,
            altitudeMslMeters = geographicOrigin.altitudeMslMeters + homeAltitudeMeters,
        )
    }

    @Synchronized fun setArmed(
        armed: Boolean,
        authority: ControlAuthority = ControlAuthority.CONTROLLER,
    ) {
        if (armed && fenceRecovering) return
        if (armed && fenceBreach(mutableState.value) != 0) { noteAck("ARM DENIED · outside enabled fence"); return }
        if (!armed) { fenceRecovering = false; returnTarget = null; missionReturn = false; minimumFenceReached = false }
        val freshArm = armed && !autopilot.armed
        autopilot.setArmed(armed, mutableState.value)
        if (freshArm) {
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
            safety = state.value.safety.copy(recovery = if (fenceRecovering) state.value.safety.recovery else "None"),
        )
    }

    @Synchronized fun armForLocalInput(input: PilotInput) {
        // A fresh local sensor flight must not inherit a navigation controller
        // or a uniform-RPM override that would mask the pilot's attitude input.
        val manualMode = if (autopilot.mode == FlightMode.ALT_HOLD) {
            FlightMode.ALT_HOLD
        } else {
            FlightMode.STABILIZE
        }
        setMotorSpeedOverrideRpm(null)
        setMode(manualMode, ControlAuthority.CONTROLLER)
        setPilotInput(input)
        setArmed(true, ControlAuthority.CONTROLLER)
    }

    @Synchronized fun setMode(
        mode: FlightMode,
        authority: ControlAuthority = ControlAuthority.CONTROLLER,
    ) {
        val current = mutableState.value
        if (fenceRecovering && mode !in listOf(FlightMode.RTL, FlightMode.LAND)) return
        returnTarget = null
        if (mode !in listOf(FlightMode.RTL, FlightMode.LAND)) returnLabel = "None"
        if (mode != FlightMode.RTL) missionReturn = false
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
        if (mode == FlightMode.RTL && current.armed) startReturn(mutableState.value)
    }

    @Synchronized fun takeoff(
        targetAltitudeM: Float,
        authority: ControlAuthority = ControlAuthority.CONTROLLER,
    ): Boolean {
        val current = mutableState.value
        if (fenceRecovering || (!current.armed && fenceBreach(current) != 0)) { noteAck("TAKEOFF DENIED · fence"); return false }
        guidedNorthMeters = current.northMeters
        guidedEastMeters = current.eastMeters
        guidedAltitudeMeters = targetAltitudeM
        positionController.reset()
        autopilot.takeoff(current, targetAltitudeM)
        if (authority == ControlAuthority.CONTROLLER) {
            // Local takeoff controls height while the pilot retains attitude
            // steering. GCS takeoff continues to use GUIDED position control.
            autopilot.setMode(FlightMode.ALT_HOLD, current)
            autopilot.setTargetAltitude(targetAltitudeM.coerceAtLeast(current.altitudeAglMeters + 0.5f))
        }
        mutableState.value = current.copy(
            armed = autopilot.armed,
            mode = autopilot.mode,
            controlAuthority = authority,
        )
        return autopilot.armed
    }

    @Synchronized fun land(authority: ControlAuthority = ControlAuthority.CONTROLLER) {
        val current = mutableState.value
        captureLoiterTarget(current)
        positionController.reset()
        autopilot.setTargetAltitude(current.altitudeAglMeters)
        autopilot.land()
        returnLabel = "Land here"
        mutableState.value = current.copy(
            mode = FlightMode.LAND,
            controlAuthority = if (fenceRecovering) ControlAuthority.SAFETY_RECOVERY else authority,
        )
    }

    @Synchronized fun setPilotInput(input: PilotInput) {
        pilotInput = input.copy(
            roll = input.roll.coerceIn(-1f, 1f),
            pitch = input.pitch.coerceIn(-1f, 1f),
            throttle = input.throttle.coerceIn(0f, 1f),
            yaw = input.yaw.coerceIn(-1f, 1f),
        )
        val current = mutableState.value
        if (current.armed && current.controlAuthority != ControlAuthority.GCS_MISSION && current.mode !in listOf(FlightMode.RTL, FlightMode.LAND) && !fenceRecovering) {
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

    @Synchronized fun setGuidedTarget(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeAglMeters: Float,
        authority: ControlAuthority = ControlAuthority.GCS_DIRECT,
    ) {
        val current = mutableState.value
        if (fenceRecovering) return
        val lat = if (latitudeDeg == 0.0 && longitudeDeg == 0.0) coordinateFrame.latitude(current.northMeters.toDouble()) else latitudeDeg
        val lon = if (latitudeDeg == 0.0 && longitudeDeg == 0.0) coordinateFrame.longitude(current.eastMeters.toDouble()) else longitudeDeg
        guidedNorthMeters = coordinateFrame.north(lat).toFloat()
        guidedEastMeters = coordinateFrame.east(lon).toFloat()
        guidedAltitudeMeters = altitudeAglMeters.coerceAtLeast(0f)
        autopilot.setTargetAltitude(guidedAltitudeMeters)
        autopilot.setMode(FlightMode.GUIDED, current)
        mutableState.value = current.copy(
            mode = FlightMode.GUIDED,
            controlAuthority = authority,
        )
        noteAck("GUIDED TARGET")
    }

    @Synchronized fun setGuidedOffset(
        northMeters: Float,
        eastMeters: Float,
        altitudeAglMeters: Float,
        authority: ControlAuthority = ControlAuthority.CONTROLLER,
    ) {
        val current = mutableState.value
        if (fenceRecovering) return
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

    @Synchronized fun loadMission(items: List<MissionItem>, origin: MissionOrigin = MissionOrigin.INTERNAL_FIXTURE) {
        val current = mutableState.value
        val sorted = items.sortedBy { it.sequence }
        val homeMarker = sorted.takeIf { isQgcHomeMarker(it) }?.firstOrNull()
        val returnAltitude = sorted
            .filterNot { it == homeMarker }
            .filter { it.command == com.ascend.mavlab.simulation.mission.MissionCommand.TAKEOFF || it.command == com.ascend.mavlab.simulation.mission.MissionCommand.WAYPOINT }
            .map { if (it.altitudeIsMsl) it.altitudeAglMeters - (current.altitudeMslMeters - current.altitudeAglMeters) else it.altitudeAglMeters }
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
                placeholderPosition -> coordinateFrame.latitude(current.northMeters.toDouble())
                else -> item.latitudeDeg
            }
            val lon = when {
                useHomeMarker -> homeMarker.longitudeDeg
                placeholderPosition -> coordinateFrame.longitude(current.eastMeters.toDouble())
                else -> item.longitudeDeg
            }
            val altitude = if (rtlHome && item.altitudeAglMeters <= 0f) {
                returnAltitude.coerceAtLeast(MinimumAirborneReturnAltitudeMeters)
            } else {
                if (item.altitudeIsMsl) (item.altitudeAglMeters - (current.altitudeMslMeters - current.altitudeAglMeters)).coerceAtLeast(0f) else item.altitudeAglMeters
            }
            item.copy(
                latitudeDeg = lat,
                longitudeDeg = lon,
                altitudeAglMeters = altitude,
                altitudeIsMsl = false,
                localNorthMeters = coordinateFrame.north(lat).toFloat(),
                localEastMeters = coordinateFrame.east(lon).toFloat(),
            )
        }
        missionEngine.load(sanitized, origin)
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
        val previous = mutableMissionUploadStatus.value
        mutableMissionUploadStatus.value = MissionUploadStatus(
            phase = MissionUploadPhase.ACCEPTED,
            expectedCount = count,
            receivedCount = count,
            lastRequestedSequence = previous.lastRequestedSequence.takeIf {
                previous.phase == MissionUploadPhase.UPLOADING && previous.expectedCount == count
            },
            lastReceivedSequence = lastReceivedSequence,
        )
    }

    fun rejectMissionUpload(reason: String, expectedCount: Int = 0, receivedCount: Int = 0) {
        val previous = mutableMissionUploadStatus.value.takeIf {
            it.phase == MissionUploadPhase.UPLOADING && it.expectedCount == expectedCount
        }
        mutableMissionUploadStatus.value = MissionUploadStatus(
            phase = MissionUploadPhase.REJECTED,
            expectedCount = expectedCount,
            receivedCount = receivedCount,
            lastRequestedSequence = previous?.lastRequestedSequence,
            lastReceivedSequence = previous?.lastReceivedSequence,
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

    @Synchronized fun clearMission() {
        missionEngine.clear()
        mutableMissionUploadStatus.value = MissionUploadStatus()
        noteAck("MISSION CLEAR")
    }

    @Synchronized fun setMissionCurrent(sequence: Int): Boolean {
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

    @Synchronized fun noteInbound(message: String) {
        mutableState.value = mutableState.value.copy(lastInboundMessage = message)
    }

    @Synchronized fun noteAck(message: String) {
        mutableState.value = mutableState.value.copy(lastAck = message)
    }

    @Synchronized
    private fun tick(fixedDtSeconds: Float? = null) {
        val nowNs = System.nanoTime()
        val dt = fixedDtSeconds ?: ((nowNs - lastTickNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastTickNs = nowNs

        val published = mutableState.value
        val physical = published.copy(
            latitudeDeg = coordinateFrame.latitude(published.northMeters.toDouble()),
            longitudeDeg = coordinateFrame.longitude(published.eastMeters.toDouble()),
        )
        val current = enforceFence(applyFailsafes(physical))
        if (current.batteryRemainingPercent.toInt() <= 0 && autopilot.armed) {
            autopilot.setArmed(false, current)
        }
        val failures = failureInjector.state.value
        val navigationInput = activePilotInput(current, dt)
        val output = autopilot.computeMotorOutput(current, navigationInput, dt)
        val overrideRpm = motorSpeedOverrideRpm?.takeIf { autopilot.armed }
        val baseMotorSpeeds = if (overrideRpm != null) {
            val radS = overrideRpm * (2f * PI.toFloat()) / 60f
            FloatArray(4) { radS }
        } else {
            output.speeds
        }
        val failedMotorSpeeds = failureInjector.applyMotorFailures(baseMotorSpeeds)
        val motorTelemetry = motorTelemetry(
            commandedSpeeds = baseMotorSpeeds,
            postFailureSpeeds = failedMotorSpeeds,
            failures = failures,
        )
        val environment = EnvironmentModel(windNedMS = failureInjector.windVectorNed())
        val physicsState = physics.step(current, failedMotorSpeeds, dt, environment, coordinateFrame)
        val effectiveThrottle = if (overrideRpm != null) {
            val radS = overrideRpm * (2f * PI.toFloat()) / 60f
            (radS / params.motorMaxSpeedRadS).coerceIn(0f, 1f)
        } else {
            output.throttle
        }
        val batteryState = updateBattery(physicsState, effectiveThrottle, dt, failures)
        val nowMs = System.currentTimeMillis()

        val simulatedState = batteryState.copy(
            armed = autopilot.armed,
            mode = autopilot.mode,
            uptimeMs = (nowMs - startedAtMs).coerceAtLeast(0L).toUInt(),
            throttlePercent = autopilot.throttlePercent(),
            motors = motorTelemetry,
            safety = current.safety.copy(returnTarget = returnLabel,
                recovery = if (fenceRecovering && autopilot.armed) "Fence ${autopilot.mode.displayName}" else "None"),
        )
        if (!simulatedState.armed) {
            fenceRecovering = false
            if (missionReturn) { missionEngine.finishReturn(); missionReturn = false }
        }
        mutableState.value = applySensorTelemetry(
            simulatedState.copy(controlAuthority = resolvedAuthorityAfterTick(simulatedState)),
            failures,
        )
    }

    internal fun tickForTest(dtSeconds: Float? = null) {
        tick(dtSeconds)
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
        if (autopilot.mode == FlightMode.AUTO && !autopilot.armed) return PilotInput(throttle = 0f)
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
                if (missionEngine.progress.value.activeTarget?.command == MissionCommand.RTL) {
                    missionReturn = true
                    missionEngine.beginReturn()
                    autopilot.setMode(FlightMode.RTL, state)
                    startReturn(state)
                    return returnPilotInput(state, dt)
                }
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
            autopilot.mode == FlightMode.RTL -> returnPilotInput(state, dt)
            else -> pilotInput
        }
    }

    private fun returnPilotInput(state: DroneState, dt: Float): PilotInput {
        if (returnTarget == null) startReturn(state)
        val target = returnTarget
        if (autopilot.mode == FlightMode.LAND || target == null) return landingPilotInput(state, dt)
        if (state.altitudeAglMeters >= target.altitude - 0.5f) returnClimbed = true
        if (returnClimbed && kotlin.math.hypot(state.northMeters - target.north, state.eastMeters - target.east) <= 1.5f && state.groundSpeedMS < 0.5f) {
            captureLoiterTarget(state)
            autopilot.setTargetAltitude(state.altitudeAglMeters)
            autopilot.land()
            return landingPilotInput(state, dt)
        }
        autopilot.setTargetAltitude(target.altitude)
        return positionController.computePilotInput(state,
            if (returnClimbed) target.north else loiterNorthMeters,
            if (returnClimbed) target.east else loiterEastMeters,
            target.altitude, dt, maxHorizontalSpeedMS = gcsSpeedOverride ?: wpNavSpeedLimitMps)
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
        if (!failures.gpsEnabled && autopilot.mode == FlightMode.RTL) {
            captureLoiterTarget(state); autopilot.setTargetAltitude(state.altitudeAglMeters); autopilot.land()
            returnLabel = "Land here · GPS lost"
            return state.copy(mode = FlightMode.LAND, lastAck = "RTL GPS LOST · LAND")
        }
        if (!failures.gpsEnabled &&
            (autopilot.mode == FlightMode.LOITER || autopilot.mode == FlightMode.GUIDED || autopilot.mode == FlightMode.AUTO)
        ) {
            autopilot.setMode(FlightMode.ALT_HOLD, state)
            noteAck("GPS FAILSAFE")
            return state.copy(mode = FlightMode.ALT_HOLD)
        }
        if (state.batteryRemainingPercent.toInt() <= 20 && autopilot.armed && autopilot.mode !in listOf(FlightMode.RTL, FlightMode.LAND)) {
            autopilot.setMode(FlightMode.RTL, state)
            startReturn(state)
            noteAck("BATTERY RTL")
            return mutableState.value.copy(mode = autopilot.mode)
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
            longitudeDeg = if (failures.gpsEnabled) state.longitudeDeg + eastNoise / coordinateFrame.longitudeMetersPerDegree else state.longitudeDeg,
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
            val lat = if (item.latitudeDeg == 0.0 && item.longitudeDeg == 0.0) coordinateFrame.latitude(state.northMeters.toDouble()) else item.latitudeDeg
            coordinateFrame.north(lat).toFloat()
        }
        val east = item.localEastMeters ?: run {
            val lon = if (item.latitudeDeg == 0.0 && item.longitudeDeg == 0.0) coordinateFrame.longitude(state.eastMeters.toDouble()) else item.longitudeDeg
            coordinateFrame.east(lon).toFloat()
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
            latitudeDeg = coordinateFrame.latitude((state.northMeters + north).toDouble()),
            longitudeDeg = coordinateFrame.longitude((state.eastMeters + east).toDouble()),
            altitudeAglMeters = altitude,
        )
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
