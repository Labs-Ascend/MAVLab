package com.ascend.mavlab.core.common

import android.content.Context
import android.hardware.SensorManager
import com.ascend.mavlab.core.mavlink.DefaultMavLabVehicleSystemId
import com.ascend.mavlab.core.mavlink.MavlinkIdentityStatus
import com.ascend.mavlab.core.mavlink.MavlinkSocketConfig
import com.ascend.mavlab.core.mavlink.MavlinkUdpServer
import com.ascend.mavlab.core.sensors.OrientationData
import com.ascend.mavlab.core.sensors.OrientationSource
import com.ascend.mavlab.core.sensors.PhoneSensorRepository
import com.ascend.mavlab.core.sensors.SensorCalibration
import com.ascend.mavlab.simulation.autopilot.PilotInput
import com.ascend.mavlab.simulation.engine.ControlAuthority
import com.ascend.mavlab.simulation.engine.FlightMode
import com.ascend.mavlab.simulation.engine.PhysicsSimulationEngine
import com.ascend.mavlab.simulation.failures.FailureScenario
import com.ascend.mavlab.simulation.failures.FailureState
import com.ascend.mavlab.simulation.mission.MissionProgress
import com.ascend.mavlab.simulation.mission.MissionUploadStatus
import com.ascend.mavlab.simulation.recording.FlightEvent
import com.ascend.mavlab.simulation.recording.FlightRecorder
import com.ascend.mavlab.simulation.recording.FlightRecordingStatus
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AppRuntime {
    private val simLoop = PhysicsSimulationEngine()
    private val fallbackStatus = MutableStateFlow("Stopped")
    private val mutableSystemId = MutableStateFlow(1)
    private val fallbackIdentityStatus = MutableStateFlow(MavlinkIdentityStatus())
    private val mutableRecordingStatus = MutableStateFlow(FlightRecordingStatus())
    private val mutablePhoneSensorSource = MutableStateFlow(OrientationSource.Unavailable)
    private val mutablePhoneSensorRawOrientation = MutableStateFlow(OrientationData())
    private val mutablePhoneSensorOrientation = MutableStateFlow(OrientationData())
    private val mutablePhoneSensorPilotInput = MutableStateFlow(PilotInput(throttle = 0.5f))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mavlinkServer: MavlinkUdpServer? = null
    private var flightRecorder: FlightRecorder? = null
    private var recordingJob: Job? = null
    private var phoneSensorJob: Job? = null
    private var applicationContext: Context? = null
    private var restoredPersistedMission = false
    private val phoneSensorCalibration = SensorCalibration()
    private var phoneSensorControlEnabled = false
    private var phoneSensorThrottle = 0.5f
    private var phoneSensorYawTrim = 0f

    val state = simLoop.state
    val failures: StateFlow<FailureState> = simLoop.failures
    val missionProgress: StateFlow<MissionProgress> = simLoop.missionProgress
    val missionUploadStatus: StateFlow<MissionUploadStatus> = simLoop.missionUploadStatus
    val recordingStatus: StateFlow<FlightRecordingStatus> = mutableRecordingStatus.asStateFlow()
    val phoneSensorSource: StateFlow<OrientationSource> = mutablePhoneSensorSource.asStateFlow()
    val phoneSensorOrientation: StateFlow<OrientationData> = mutablePhoneSensorOrientation.asStateFlow()
    val phoneSensorPilotInput: StateFlow<PilotInput> = mutablePhoneSensorPilotInput.asStateFlow()
    val status: StateFlow<String>
        get() = mavlinkServer?.status ?: fallbackStatus.asStateFlow()
    val mavlinkIdentityStatus: StateFlow<MavlinkIdentityStatus>
        get() = mavlinkServer?.identityStatus ?: fallbackIdentityStatus.asStateFlow()
    val systemId: StateFlow<Int> = mutableSystemId.asStateFlow()

    fun start(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        if (flightRecorder == null) {
            flightRecorder = FlightRecorder(appContext.filesDir)
        }
        refreshSessionHistory()
        restorePersistedMission(appContext)
        if (mavlinkServer == null) {
            val id = stableSystemId(appContext)
            mutableSystemId.value = id
            mavlinkServer = MavlinkUdpServer(
                simLoop = simLoop,
                config = MavlinkSocketConfig(systemId = id),
                onMissionLoaded = { items ->
                    MissionPersistence.save(appContext, items)
                    recordFailureEvent("mission_upload", "${items.size} items uploaded")
                },
                onMissionCleared = {
                    MissionPersistence.clear(appContext)
                    recordFailureEvent("mission_cleared", "mission cleared")
                },
            )
        }
        simLoop.start()
        mavlinkServer?.start(appContext)
        startPhoneSensorMonitor(appContext)
        startRecordingMonitor()
    }

    fun stop() {
        mavlinkServer?.stopNow()
        recordingJob?.cancel()
        recordingJob = null
        phoneSensorJob?.cancel()
        phoneSensorJob = null
        flightRecorder?.closeSession("runtime stopped")
        refreshSessionHistory()
        syncRecordingStatus()
        simLoop.stop()
    }

    fun refreshSessionHistory() {
        val recorder = flightRecorder ?: return
        val history = recorder.listSessions()
        val currentStatus = mutableRecordingStatus.value
        mutableRecordingStatus.value = currentStatus.copy(sessionHistory = history)
    }

    fun shareSession(context: Context, sessionId: String) {
        val recorder = flightRecorder ?: return
        val dir = recorder.sessionDirectory(sessionId) ?: return
        val files = dir.listFiles() ?: return
        if (files.isEmpty()) return

        val uris = ArrayList<android.net.Uri>()
        val authority = "com.ascend.mavlab.fileprovider"
        for (file in files) {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                uris.add(uri)
            } catch (e: Exception) {
                // Ignore
            }
        }

        if (uris.isEmpty()) return

        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(intent, "Export Session Files").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun setArmed(armed: Boolean) {
        simLoop.setArmed(armed, ControlAuthority.CONTROLLER)
    }

    fun takeoff(targetAltitudeM: Float = 10f) {
        simLoop.takeoff(targetAltitudeM, ControlAuthority.CONTROLLER)
    }

    fun land() {
        simLoop.land(ControlAuthority.CONTROLLER)
    }

    fun setMode(mode: FlightMode) {
        simLoop.setMode(mode, ControlAuthority.CONTROLLER)
    }

    fun setPilotInput(input: PilotInput) {
        simLoop.setPilotInput(input)
    }

    fun setPhoneSensorControlEnabled(enabled: Boolean) {
        phoneSensorControlEnabled = enabled
        if (enabled) {
            refreshPhoneSensorPilotInput()
        }
    }

    fun setPhoneSensorThrottle(value: Float) {
        phoneSensorThrottle = value.coerceIn(0f, 1f)
        refreshPhoneSensorPilotInput()
    }

    fun setPhoneSensorYawTrim(value: Float) {
        phoneSensorYawTrim = value.coerceIn(-1f, 1f)
        refreshPhoneSensorPilotInput()
    }

    fun calibratePhoneSensors() {
        phoneSensorCalibration.calibrate(mutablePhoneSensorRawOrientation.value)
        val calibrated = phoneSensorCalibration.apply(mutablePhoneSensorRawOrientation.value)
        mutablePhoneSensorOrientation.value = calibrated
        refreshPhoneSensorPilotInput(calibrated)
    }

    fun applyFailureScenario(scenario: FailureScenario) {
        simLoop.failureInjector.applyScenario(scenario)
        recordFailureEvent(scenario.logEventName, "${scenario.title}: ${scenario.affectedState}")
    }

    fun clearFailureScenario(scenario: FailureScenario) {
        simLoop.failureInjector.clearScenario(scenario)
        recordFailureEvent("${scenario.logEventName}_cleared", "${scenario.title} cleared")
    }

    fun resetFailures() {
        recordFailureEvent("failure_reset", "all failures cleared")
        simLoop.failureInjector.resetAll()
    }

    fun resetBattery() {
        simLoop.resetBattery()
        recordFailureEvent("battery_reset", "battery recharged to 100%")
    }

    fun setGpsEnabled(enabled: Boolean) {
        simLoop.failureInjector.setGpsEnabled(enabled)
        recordFailureEvent("failure_manual_gps", "gpsEnabled=$enabled")
    }

    fun setGpsNoiseMultiplier(value: Float) {
        simLoop.failureInjector.setGpsNoiseMultiplier(value)
        recordFailureEvent("failure_manual_gps_noise", "gpsNoiseMultiplier=%.1f".format(value))
    }

    fun setCompassEnabled(enabled: Boolean) {
        simLoop.failureInjector.setCompassEnabled(enabled)
        recordFailureEvent("failure_manual_compass", "compassEnabled=$enabled")
    }

    fun setCompassOffsetDeg(value: Float) {
        simLoop.failureInjector.setCompassOffsetDeg(value)
        recordFailureEvent("failure_manual_compass_offset", "compassOffsetDeg=%.0f".format(value))
    }

    fun setWindSpeedMs(value: Float) {
        simLoop.failureInjector.setWindSpeedMs(value)
        recordFailureEvent("failure_manual_wind_speed", "windSpeedMs=%.1f".format(value))
    }

    fun setWindDirectionDeg(value: Float) {
        simLoop.failureInjector.setWindDirectionDeg(value)
        recordFailureEvent("failure_manual_wind_direction", "windDirectionDeg=%.0f".format(value))
    }

    fun setWindGustsMs(value: Float) {
        simLoop.failureInjector.setWindGustsMs(value)
        recordFailureEvent("failure_manual_wind_gusts", "windGustsMs=%.1f".format(value))
    }

    fun setMotorFailed(index: Int, failed: Boolean) {
        simLoop.failureInjector.setMotorFailed(index, failed)
        recordFailureEvent("failure_manual_motor", "motor=${index + 1}, failed=$failed")
    }

    fun setBatteryDrainMultiplier(value: Float) {
        simLoop.failureInjector.setBatteryDrainMultiplier(value)
        recordFailureEvent("failure_manual_battery", "batteryDrainMultiplier=%.1f".format(value))
    }

    fun setPayloadMassKg(value: Float) {
        simLoop.failureInjector.setPayloadMassKg(value)
        recordFailureEvent("failure_manual_payload", "payloadMassKg=%.1f".format(value))
    }

    fun setLostLinkActive(active: Boolean) {
        simLoop.failureInjector.setLostLinkActive(active)
        recordFailureEvent("failure_manual_lost_link", "lostLinkActive=$active")
    }

    fun setBarometerOffsetMeters(value: Float) {
        simLoop.failureInjector.setBarometerOffsetMeters(value)
        recordFailureEvent("failure_manual_barometer", "barometerOffsetMeters=%.1f".format(value))
    }

    fun setUnsafeMissionReserveActive(active: Boolean) {
        simLoop.failureInjector.setUnsafeMissionReserveActive(active)
        recordFailureEvent("failure_manual_unsafe_reserve", "unsafeMissionReserveActive=$active")
    }

    fun loadDemoMission() {
        simLoop.loadDemoMission()
        applicationContext?.let { MissionPersistence.save(it, simLoop.missionProgress.value.items) }
    }

    fun clearMission() {
        simLoop.clearMission()
        applicationContext?.let { MissionPersistence.clear(it) }
    }

    fun startAutoMission() {
        applicationContext?.let { restorePersistedMission(it) }
        if (!simLoop.missionProgress.value.loaded) {
            simLoop.noteAck("MISSION_START NO MISSION")
            return
        }
        simLoop.setArmed(true, ControlAuthority.GCS_MISSION)
        simLoop.setMode(FlightMode.AUTO, ControlAuthority.GCS_MISSION)
    }

    fun sendGuidedOffset(northMeters: Float, eastMeters: Float, altitudeAglMeters: Float) {
        simLoop.setArmed(true, ControlAuthority.CONTROLLER)
        simLoop.setGuidedOffset(northMeters, eastMeters, altitudeAglMeters, ControlAuthority.CONTROLLER)
    }

    private fun stableSystemId(context: Context): Int {
        return DefaultMavLabVehicleSystemId
    }

    private fun restorePersistedMission(context: Context) {
        if (restoredPersistedMission || simLoop.missionProgress.value.loaded) return
        val items = MissionPersistence.load(context)
        restoredPersistedMission = true
        if (items.isEmpty()) return

        simLoop.loadMission(items)
        simLoop.acceptMissionUpload(count = items.size)
        simLoop.noteAck("MISSION RESTORED ${items.size}")
    }

    private fun startRecordingMonitor() {
        if (recordingJob != null) return
        recordingJob = scope.launch {
            var previousArmed = state.value.armed
            var previousMode = state.value.mode
            var previousAuthority = state.value.controlAuthority
            var previousMissionSignature = missionSignature(missionProgress.value)
            var previousGcsConnected = mavlinkIdentityStatus.value.gcsConnected
            var previousLastReachedSequence: Int? = null
            var previousMissionComplete = missionProgress.value.complete
            var batteryWarned = false
            var batteryCriticalWarned = false

            while (isActive) {
                val recorder = flightRecorder
                if (recorder == null) {
                    delay(RecordingSampleMs)
                    continue
                }

                val current = state.value
                val mission = missionProgress.value
                val currentMissionSignature = missionSignature(mission)
                val gcsConnected = mavlinkIdentityStatus.value.gcsConnected
                val sessionActive = recorder.activeSession() != null

                if (current.armed && !sessionActive) {
                    recorder.startSession(recordingStartReason(current))
                    recorder.saveMissionSnapshot(mission)
                    batteryWarned = false
                    batteryCriticalWarned = false
                    previousLastReachedSequence = mission.lastReachedSequence
                    previousMissionComplete = mission.complete
                    refreshSessionHistory()
                }

                val activeSession = recorder.activeSession()
                if (activeSession != null) {
                    if (!previousArmed && current.armed) {
                        recorder.appendEvent(FlightEvent(System.currentTimeMillis(), "vehicle_armed", current.controlAuthority.displayName))
                    }
                    if (current.mode != previousMode) {
                        recorder.appendEvent(FlightEvent(System.currentTimeMillis(), "mode_changed", current.mode.displayName))
                    }
                    if (current.controlAuthority != previousAuthority) {
                        recorder.appendEvent(FlightEvent(System.currentTimeMillis(), "authority_changed", current.controlAuthority.displayName))
                    }
                    if (mission.loaded && currentMissionSignature != previousMissionSignature) {
                        recorder.saveMissionSnapshot(mission)
                        recorder.appendEvent(FlightEvent(System.currentTimeMillis(), "mission_snapshot", "${mission.items.size} items"))
                    }

                    // GCS Connection State Changes
                    if (gcsConnected != previousGcsConnected) {
                        val eventType = if (gcsConnected) "qgc_connected" else "qgc_disconnected"
                        val eventMsg = if (gcsConnected) "GCS connected" else "GCS disconnected"
                        recorder.appendEvent(FlightEvent(System.currentTimeMillis(), eventType, eventMsg))
                    }

                    // Waypoint Reached / Mission Complete
                    if (mission.loaded) {
                        val lastReached = mission.lastReachedSequence
                        if (lastReached != null && lastReached != previousLastReachedSequence) {
                            recorder.appendEvent(FlightEvent(System.currentTimeMillis(), "waypoint_reached", "wp=$lastReached"))
                            previousLastReachedSequence = lastReached
                        }
                        if (mission.complete && !previousMissionComplete) {
                            recorder.appendEvent(FlightEvent(System.currentTimeMillis(), "mission_complete", "all waypoints completed"))
                        }
                    }

                    // Battery Warnings
                    val batPercent = current.batteryRemainingPercent.toInt()
                    if (batPercent <= 15 && !batteryCriticalWarned) {
                        recorder.appendEvent(FlightEvent(System.currentTimeMillis(), "battery_warning", "battery critical ($batPercent%)"))
                        batteryCriticalWarned = true
                        batteryWarned = true
                    } else if (batPercent <= 30 && !batteryWarned) {
                        recorder.appendEvent(FlightEvent(System.currentTimeMillis(), "battery_warning", "battery low ($batPercent%)"))
                        batteryWarned = true
                    }

                    val activeWp = if (mission.loaded) mission.currentIndex else -1
                    val failFlags = formatFailureFlags(failures.value)
                    recorder.appendTelemetry(current, activeWaypoint = activeWp, failureFlags = failFlags)
                }

                if (previousArmed && !current.armed) {
                    recorder.closeSession("vehicle disarmed")
                    refreshSessionHistory()
                }

                previousArmed = current.armed
                previousMode = current.mode
                previousAuthority = current.controlAuthority
                previousMissionSignature = currentMissionSignature
                previousGcsConnected = gcsConnected
                if (activeSession != null) {
                    previousLastReachedSequence = mission.lastReachedSequence
                    previousMissionComplete = mission.complete
                }
                syncRecordingStatus()
                delay(RecordingSampleMs)
            }
        }
    }

    private fun formatFailureFlags(state: FailureState): String {
        val flags = mutableListOf<String>()
        if (!state.gpsEnabled) flags.add("gps_loss")
        if (state.gpsNoiseMultiplier > 1.05f) flags.add("gps_drift")
        if (!state.compassEnabled) flags.add("compass_loss")
        if (abs(state.compassOffsetDeg) > 0.5f) flags.add("compass_offset")
        if (state.windSpeedMs > 0.05f) flags.add("wind_speed")
        if (state.windGustsMs > 0.05f) flags.add("wind_gusts")
        if (state.hasMotorFailure) flags.add("motor_failure")
        if (state.batteryDrainMultiplier > 1.05f) flags.add("battery_drain")
        if (state.payloadMassKg > 0.05f) flags.add("heavy_payload")
        if (state.lostLinkActive) flags.add("lost_link")
        if (abs(state.barometerOffsetMeters) > 0.05f) flags.add("barometer_offset")
        if (state.unsafeMissionReserveActive) flags.add("unsafe_mission_reserve")
        return flags.joinToString(";")
    }

    private fun startPhoneSensorMonitor(context: Context) {
        if (phoneSensorJob != null) return
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val repository = PhoneSensorRepository(sensorManager)
        mutablePhoneSensorSource.value = repository.activeSource()
        phoneSensorJob = scope.launch {
            repository.orientationFlow().collect { raw ->
                mutablePhoneSensorRawOrientation.value = raw
                mutablePhoneSensorSource.value = raw.source
                val calibrated = phoneSensorCalibration.apply(raw)
                mutablePhoneSensorOrientation.value = calibrated
                refreshPhoneSensorPilotInput(calibrated)
            }
        }
    }

    private fun refreshPhoneSensorPilotInput(
        orientation: OrientationData = mutablePhoneSensorOrientation.value,
    ) {
        val input = mapPhoneSensorInput(orientation)
        mutablePhoneSensorPilotInput.value = input
        val currentState = state.value
        val gcsMissionOwnsControl = currentState.armed && currentState.controlAuthority == ControlAuthority.GCS_MISSION
        if (phoneSensorControlEnabled && !gcsMissionOwnsControl) {
            simLoop.setPilotInput(input)
        }
    }

    private fun mapPhoneSensorInput(orientation: OrientationData): PilotInput {
        val mappedYaw = mapSensorAxis(orientation.yaw, PhoneSensorMaxYawAngleRad)
        return PilotInput(
            roll = mapSensorAxis(orientation.roll, PhoneSensorMaxRollAngleRad),
            pitch = mapSensorAxis(-orientation.pitch, PhoneSensorMaxPitchAngleRad),
            throttle = phoneSensorThrottle,
            yaw = (mappedYaw + phoneSensorYawTrim).coerceIn(-1f, 1f),
        )
    }

    private fun mapSensorAxis(value: Float, maxAngle: Float): Float {
        val absolute = abs(value)
        if (absolute < PhoneSensorDeadzoneRad) return 0f
        val normalized = ((absolute - PhoneSensorDeadzoneRad) / (maxAngle - PhoneSensorDeadzoneRad))
            .coerceIn(0f, 1f)
        return normalized.pow(PhoneSensorExpo) * sign(value)
    }

    private fun syncRecordingStatus() {
        flightRecorder?.let { mutableRecordingStatus.value = it.status.value }
    }

    private fun recordFailureEvent(type: String, message: String) {
        flightRecorder?.appendEvent(FlightEvent(System.currentTimeMillis(), type, message))
        syncRecordingStatus()
    }

    private fun recordingStartReason(state: com.ascend.mavlab.simulation.engine.DroneState): String {
        return when {
            state.controlAuthority == ControlAuthority.GCS_MISSION -> "GCS mission"
            state.mode == FlightMode.GUIDED -> "takeoff or guided command"
            else -> "vehicle armed"
        }
    }

    private fun missionSignature(mission: MissionProgress): String {
        return mission.items.joinToString("|") { item ->
            "${item.sequence}:${item.command}:${item.latitudeDeg}:${item.longitudeDeg}:${item.altitudeAglMeters}"
        } + ":${mission.currentIndex}:${mission.complete}"
    }

    private const val RecordingSampleMs = 200L
    private const val PhoneSensorMaxRollAngleRad = 0.5235988f
    private const val PhoneSensorMaxPitchAngleRad = 0.5235988f
    private const val PhoneSensorMaxYawAngleRad = 0.7853982f
    private const val PhoneSensorDeadzoneRad = 0.05235988f
    private const val PhoneSensorExpo = 1.45f
}
