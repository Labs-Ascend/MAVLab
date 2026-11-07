package com.ascend.mavlab.core.common

import com.ascend.mavlab.simulation.mission.missionActionPolicy
import com.ascend.mavlab.simulation.mission.MissionActionPolicy
import com.ascend.mavlab.simulation.mission.MissionOrigin
import android.content.Context
import android.hardware.SensorManager
import android.os.SystemClock
import com.ascend.mavlab.core.mavlink.DefaultMavLabVehicleSystemId
import com.ascend.mavlab.core.mavlink.MavlinkIdentityStatus
import com.ascend.mavlab.core.mavlink.MavlinkSocketConfig
import com.ascend.mavlab.core.mavlink.MavlinkUdpServer
import com.ascend.mavlab.core.sensors.OrientationData
import com.ascend.mavlab.core.sensors.OrientationSource
import com.ascend.mavlab.core.sensors.PhoneSensorRepository
import com.ascend.mavlab.core.sensors.SensorCalibration
import com.ascend.mavlab.core.operations.OperationsObserver
import com.ascend.mavlab.core.operations.OperationsState
import com.ascend.mavlab.core.operations.OpsObservation
import com.ascend.mavlab.simulation.autopilot.PilotInput
import com.ascend.mavlab.simulation.audio.DroneSoundController
import com.ascend.mavlab.simulation.audio.DroneSoundDebugState
import com.ascend.mavlab.simulation.audio.DroneSoundSettings
import com.ascend.mavlab.simulation.audio.DroneSynthQuality
import com.ascend.mavlab.simulation.engine.ControlAuthority
import com.ascend.mavlab.simulation.engine.FlightMode
import com.ascend.mavlab.simulation.engine.PhysicsSimulationEngine
import com.ascend.mavlab.simulation.engine.SimLocation
import com.ascend.mavlab.simulation.failures.FailureScenario
import com.ascend.mavlab.simulation.failures.FailureState
import com.ascend.mavlab.simulation.mission.MissionProgress
import com.ascend.mavlab.simulation.mission.MissionUploadStatus
import com.ascend.mavlab.simulation.recording.FlightEvent
import com.ascend.mavlab.simulation.recording.FlightRecorder
import com.ascend.mavlab.simulation.recording.FlightRecordingStatus
import com.ascend.mavlab.simulation.recording.RecordingPolicy
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.update

object AppRuntime {
    private val simLoop = PhysicsSimulationEngine()
    private val fallbackStatus = MutableStateFlow("Stopped")
    private val mutableRuntimeLifecycle = MutableStateFlow(RuntimeLifecycleState.STOPPED)
    private val mutableStateUpdatedAtElapsedRealtimeMs = MutableStateFlow<Long?>(null)
    private val mutableSystemId = MutableStateFlow(1)
    private val fallbackIdentityStatus = MutableStateFlow(MavlinkIdentityStatus())
    private val mutableRecordingStatus = MutableStateFlow(FlightRecordingStatus())
    private val mutableOperations = MutableStateFlow(OperationsState())
    private val operationsObserver = OperationsObserver()
    private val recordingPolicy = RecordingPolicy()
    private val recordingMutex = Mutex()
    private val mutableSimLocation = MutableStateFlow(SimLocation.Nairobi)
    private val mutablePhoneSensorSource = MutableStateFlow(OrientationSource.Unavailable)
    private val mutablePhoneSensorRawOrientation = MutableStateFlow(OrientationData())
    private val mutablePhoneSensorOrientation = MutableStateFlow(OrientationData())
    private val mutablePhoneSensorPilotInput = MutableStateFlow(PilotInput(throttle = 0.5f))
    private val mutableControllerInputState = MutableStateFlow(ControllerInputState())
    private val mutableMotorSpeedOverrideRpm = MutableStateFlow<Float?>(null)
    private val fallbackSoundSettings = MutableStateFlow(DroneSoundSettings())
    private val fallbackSoundDebugState = MutableStateFlow(DroneSoundDebugState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val virtualCamera = com.ascend.mavlab.simulation.camera.VirtualCameraController(scope, simLoop::physicalPoseSnapshot)
    private var mavlinkServer: MavlinkUdpServer? = null
    private var flightRecorder: FlightRecorder? = null
    private var droneSoundController: DroneSoundController? = null
    private var recordingJob: Job? = null
    private var stateFreshnessJob: Job? = null
    private var phoneSensorJob: Job? = null
    private var applicationContext: Context? = null
    private var runtimeActive = false
    private var appVisible = true
    private var restoredPersistedMission = false
    private val phoneSensorCalibration = SensorCalibration()
    private var phoneSensorThrottle = 0.5f
    private var phoneSensorYawTrim = 0f

    val state = simLoop.state
    val runtimeLifecycle: StateFlow<RuntimeLifecycleState> = mutableRuntimeLifecycle.asStateFlow()
    val stateUpdatedAtElapsedRealtimeMs: StateFlow<Long?> =
        mutableStateUpdatedAtElapsedRealtimeMs.asStateFlow()
    val failures: StateFlow<FailureState> = simLoop.failures
    val missionProgress: StateFlow<MissionProgress> = simLoop.missionProgress
    val missionUploadStatus: StateFlow<MissionUploadStatus> = simLoop.missionUploadStatus
    val safetyPlans = simLoop.safetyPlans
    private var safetyPersistenceJob: Job? = null
    private var restoredSafetyPlans = false

    fun setFenceEnabled(enabled: Boolean) = simLoop.setSafetyParameter("FENCE_ENABLE", if (enabled) 1f else 0f)
    fun clearSafetyPlans(): Boolean {
        return simLoop.clearSafetyPlans(disableFence = true)
    }
    val recordingStatus: StateFlow<FlightRecordingStatus> = mutableRecordingStatus.asStateFlow()
    val operations: StateFlow<OperationsState> = mutableOperations.asStateFlow()
    val simLocation: StateFlow<SimLocation> = mutableSimLocation.asStateFlow()
    val phoneSensorSource: StateFlow<OrientationSource> = mutablePhoneSensorSource.asStateFlow()
    val phoneSensorOrientation: StateFlow<OrientationData> = mutablePhoneSensorOrientation.asStateFlow()
    val phoneSensorPilotInput: StateFlow<PilotInput> = mutablePhoneSensorPilotInput.asStateFlow()
    val controllerInputState: StateFlow<ControllerInputState> = mutableControllerInputState.asStateFlow()
    val motorSpeedOverrideRpm: StateFlow<Float?> = mutableMotorSpeedOverrideRpm.asStateFlow()
    val soundSettings: StateFlow<DroneSoundSettings>
        get() = droneSoundController?.settings ?: fallbackSoundSettings.asStateFlow()
    val soundDebugState: StateFlow<DroneSoundDebugState>
        get() = droneSoundController?.debugState ?: fallbackSoundDebugState.asStateFlow()
    val status: StateFlow<String>
        get() = mavlinkServer?.status ?: fallbackStatus.asStateFlow()
    val mavlinkIdentityStatus: StateFlow<MavlinkIdentityStatus>
        get() = mavlinkServer?.identityStatus ?: fallbackIdentityStatus.asStateFlow()
    val systemId: StateFlow<Int> = mutableSystemId.asStateFlow()

    fun start(context: Context) {
        if (runtimeActive) {
            mutableRuntimeLifecycle.value = RuntimeLifecycleState.RUNNING
            return
        }
        mutableRuntimeLifecycle.value = RuntimeLifecycleState.STARTING
        publishOperations()
        val appContext = context.applicationContext
        try {
            applicationContext = appContext
            virtualCamera.initialize(appContext)
            recordingPolicy.setAutomaticEnabled(appContext.getSharedPreferences(RecordingPrefsName, Context.MODE_PRIVATE)
                .getBoolean(AutomaticRecordingKey, false))
            if (flightRecorder == null) {
                flightRecorder = FlightRecorder(appContext.filesDir)
            }
            syncRecordingStatus()
            refreshSessionHistory()
            restoreSimLocation(appContext)
            restorePersistedMission(appContext)
            if (!restoredSafetyPlans) {
                SafetyPlanPersistence.load(appContext)?.let { simLoop.restoreSafetyPlans(it) }
                restoredSafetyPlans = true
            }
            if (safetyPersistenceJob == null) {
                safetyPersistenceJob = scope.launch(Dispatchers.IO) {
                    safetyPlans.map { it.copy(lastTransfer = "") }.distinctUntilChanged()
                        .collect { SafetyPlanPersistence.save(appContext, it) }
                }
            }
            if (mavlinkServer == null) {
                val id = stableSystemId(appContext)
                mutableSystemId.value = id
                mavlinkServer = MavlinkUdpServer(
                    simLoop = simLoop,
                    config = MavlinkSocketConfig(systemId = id),
                    camera = virtualCamera,
                    onMissionLoaded = { items ->
                        MissionPersistence.save(appContext, items, MissionOrigin.GCS_UPLOAD)
                        recordFailureEvent("mission_upload", "${items.size} items uploaded")
                    },
                    onMissionCleared = {
                        MissionPersistence.clear(appContext)
                        recordFailureEvent("mission_cleared", "mission cleared")
                    },
                )
            }
            startStateFreshnessMonitor()
            simLoop.start()
            mavlinkServer?.start(appContext)
            runtimeActive = true
            mutableRuntimeLifecycle.value = RuntimeLifecycleState.RUNNING
            applyRuntimeComponentPolicy(appContext)
            startRecordingMonitor()
        } catch (error: Throwable) {
            stateFreshnessJob?.cancel()
            stateFreshnessJob = null
            runtimeActive = false
            mutableRuntimeLifecycle.value = RuntimeLifecycleState.ERROR
            publishOperations()
            throw error
        }
    }

    fun setAppVisible(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        applyRuntimeComponentPolicy(applicationContext)
        publishOperations()
    }

    fun stop() {
        mutableRuntimeLifecycle.value = RuntimeLifecycleState.STOPPING
        virtualCamera.disable()
        mavlinkServer?.stopNow()
        recordingJob?.cancel()
        recordingJob = null
        stateFreshnessJob?.cancel()
        stateFreshnessJob = null
        runtimeActive = false
        applyRuntimeComponentPolicy(applicationContext)
        flightRecorder?.closeSession("runtime stopped")
        recordingPolicy.runtimeStopped()
        refreshSessionHistory()
        syncRecordingStatus()
        simLoop.stop()
        mutableStateUpdatedAtElapsedRealtimeMs.value = null
        mutableRuntimeLifecycle.value = RuntimeLifecycleState.STOPPED
        publishOperations()
    }

    private fun startStateFreshnessMonitor() {
        stateFreshnessJob?.cancel()
        mutableStateUpdatedAtElapsedRealtimeMs.value = null
        stateFreshnessJob = scope.launch {
            state.drop(1).collect {
                mutableStateUpdatedAtElapsedRealtimeMs.value = SystemClock.elapsedRealtime()
                syncControllerMotorOverride()
            }
        }
    }

    private fun applyRuntimeComponentPolicy(context: Context?) {
        val components = RuntimeComponentPolicy.decide(
            runtimeActive = runtimeActive,
            appVisible = appVisible,
        )
        if (components.droneAudioEnabled && context != null) {
            startDroneAudio(context)
        } else {
            stopDroneAudio()
        }
        if (components.phoneSensorInputEnabled && context != null) {
            startPhoneSensorInteraction(context)
        } else {
            stopPhoneSensorInteraction()
        }
    }

    private fun startDroneAudio(context: Context) {
        if (droneSoundController == null) {
            droneSoundController = DroneSoundController(
                context = context,
                state = state,
                failures = failures,
                scope = scope,
            )
        }
        droneSoundController?.start()
    }

    private fun stopDroneAudio() {
        droneSoundController?.release()
        droneSoundController = null
    }

    private fun startPhoneSensorInteraction(context: Context) {
        startPhoneSensorMonitor(context)
    }

    private fun stopPhoneSensorInteraction() {
        phoneSensorJob?.cancel()
        phoneSensorJob = null
        mutablePhoneSensorSource.value = OrientationSource.Unavailable
        val neutralInput = PilotInput(throttle = ControllerInputState.DefaultThrottle)
        mutablePhoneSensorPilotInput.value = neutralInput
        simLoop.setPilotInput(neutralInput)
    }

    fun refreshSessionHistory() {
        val recorder = flightRecorder ?: return
        scope.launch(Dispatchers.IO) {
            val history = recorder.listSessions()
            mutableRecordingStatus.update { it.copy(sessionHistory = history) }
        }
    }

    fun beginRecording() = changeRecording(start = true)

    fun stopAndSaveRecording() = changeRecording(start = false)

    fun setAutomaticRecordingEnabled(enabled: Boolean): Boolean {
        if (state.value.armed) return false
        val context = applicationContext ?: return false
        recordingPolicy.setAutomaticEnabled(enabled)
        context.getSharedPreferences(RecordingPrefsName, Context.MODE_PRIVATE)
            .edit().putBoolean(AutomaticRecordingKey, enabled).apply()
        syncRecordingStatus()
        publishOperations()
        return true
    }

    private fun changeRecording(start: Boolean) {
        if (mutableRecordingStatus.value.actionInProgress) return
        mutableRecordingStatus.update { it.copy(actionInProgress = true, actionError = null) }
        scope.launch(Dispatchers.IO) {
            try {
                recordingMutex.withLock {
                    check(runtimeActive) { "Start the simulation before recording." }
                    val recorder = checkNotNull(flightRecorder) { "Recording is unavailable." }
                    if (start) {
                        recordingPolicy.startedByUser()
                        recorder.startSession("started explicitly in Ops")
                        recorder.saveMissionSnapshot(missionProgress.value)
                    } else {
                        recorder.saveMissionSnapshot(missionProgress.value)
                        recorder.closeSession("stopped explicitly in Ops")
                        recordingPolicy.stoppedByUser(state.value.armed)
                    }
                    syncRecordingStatus()
                    refreshSessionHistory()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableRecordingStatus.update { it.copy(actionError = error.message ?: "Recording action failed") }
            } finally {
                mutableRecordingStatus.update { it.copy(actionInProgress = false) }
                publishOperations()
            }
        }
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
        val inputs = mutableControllerInputState.value
        if (armed && !state.value.armed && inputs.inputMode != ControllerInputMode.DIRECT_RPM) {
            mutableMotorSpeedOverrideRpm.value = null
            val pilotInput = if (inputs.inputMode == ControllerInputMode.PHONE_SENSORS) {
                mapPhoneSensorInput(mutablePhoneSensorOrientation.value)
            } else {
                inputs.toPilotInput()
            }
            simLoop.armForLocalInput(pilotInput)
            applySelectedControllerInput()
        } else {
            simLoop.setArmed(armed, ControlAuthority.CONTROLLER)
            syncControllerMotorOverride()
        }
        if (!armed) {
            resetControllerInputsAfterDisarm()
        }
    }

    fun takeoff(targetAltitudeM: Float = 10f) {
        simLoop.takeoff(targetAltitudeM, ControlAuthority.CONTROLLER)
    }

    fun land() {
        if (mutableControllerInputState.value.inputMode == ControllerInputMode.DIRECT_RPM) {
            setControllerInputMode(
                if (mutablePhoneSensorSource.value != OrientationSource.Unavailable) {
                    ControllerInputMode.PHONE_SENSORS
                } else {
                    ControllerInputMode.CUSTOM_INPUT
                },
            )
        }
        simLoop.land(ControlAuthority.CONTROLLER)
    }

    fun setMode(mode: FlightMode) {
        simLoop.setMode(mode, ControlAuthority.CONTROLLER)
        syncControllerMotorOverride()
    }

    fun setPilotInput(input: PilotInput) {
        simLoop.setPilotInput(input)
    }

    internal fun tickSimulationForTest(dtSeconds: Float) = simLoop.tickForTest(dtSeconds)

    fun setControllerInputMode(mode: ControllerInputMode) {
        val previous = mutableControllerInputState.value.inputMode
        if (previous != mode) {
            setMotorSpeedOverrideRpm(null)
        }
        updateControllerInputState {
            it.copy(
                inputMode = mode,
                directRpm = if (mode == ControllerInputMode.DIRECT_RPM && previous != mode) {
                    if (state.value.armed) state.value.motors.map { motor -> motor.rpm }.average().toFloat() else 0f
                } else it.directRpm,
            )
        }
        applySelectedControllerInput()
    }

    fun setControllerThrottle(value: Float) {
        val throttle = value.coerceIn(0f, 1f)
        updateControllerInputState { it.copy(throttle = throttle) }
        phoneSensorThrottle = throttle
        applySelectedControllerInput()
    }

    fun setControllerManualRoll(value: Float) {
        updateControllerInputState { it.copy(manualRoll = value.coerceIn(-1f, 1f)) }
        applySelectedControllerInput()
    }

    fun setControllerManualPitch(value: Float) {
        updateControllerInputState { it.copy(manualPitch = value.coerceIn(-1f, 1f)) }
        applySelectedControllerInput()
    }

    fun setControllerManualYaw(value: Float) {
        val yaw = value.coerceIn(-1f, 1f)
        updateControllerInputState { it.copy(manualYaw = yaw) }
        phoneSensorYawTrim = yaw
        applySelectedControllerInput()
    }

    fun setControllerDirectRpm(value: Float) {
        updateControllerInputState { it.copy(directRpm = value.coerceIn(0f, ControllerInputState.MaxDirectRpm)) }
        syncControllerMotorOverride()
    }

    private fun applySelectedControllerInput() {
        when (mutableControllerInputState.value.inputMode) {
            ControllerInputMode.PHONE_SENSORS -> refreshPhoneSensorPilotInput()
            ControllerInputMode.CUSTOM_INPUT -> if (state.value.controlAuthority !in listOf(ControlAuthority.GCS_MISSION, ControlAuthority.SAFETY_RECOVERY)) {
                simLoop.setPilotInput(mutableControllerInputState.value.toPilotInput())
            }
            ControllerInputMode.DIRECT_RPM -> syncControllerMotorOverride()
        }
    }

    private fun syncControllerMotorOverride() {
        val inputs = mutableControllerInputState.value
        if (inputs.inputMode != ControllerInputMode.DIRECT_RPM) return
        val rpm = inputs.activeDirectRpm(state.value.armed, state.value.controlAuthority)
        if (mutableMotorSpeedOverrideRpm.value != rpm) setMotorSpeedOverrideRpm(rpm)
    }

    fun setPhoneSensorThrottle(value: Float) {
        setControllerThrottle(value)
    }

    fun setPhoneSensorYawTrim(value: Float) {
        setControllerManualYaw(value)
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

    fun setSimLocation(location: SimLocation): Boolean {
        if (state.value.armed) return false
        val safeLocation = location.sanitized()
        val applied = simLoop.setHomeLocation(safeLocation)
        if (!applied) return false
        virtualCamera.disable()
        mutableSimLocation.value = safeLocation
        applicationContext?.let { context ->
            persistSimLocation(context, safeLocation)
            MissionPersistence.clear(context)
        }
        recordFailureEvent(
            "sim_location_changed",
            "${safeLocation.label}: %.6f, %.6f @ %.0f m MSL".format(
                safeLocation.latitudeDeg,
                safeLocation.longitudeDeg,
                safeLocation.altitudeMslMeters,
            ),
        )
        return true
    }

    fun setMotorSpeedOverrideRpm(rpm: Float?) {
        mutableMotorSpeedOverrideRpm.value = rpm
        simLoop.setMotorSpeedOverrideRpm(rpm)
        if (rpm != null) {
            recordFailureEvent("motor_rpm_override", "motor RPM override set to ${rpm.toInt()} RPM")
        } else {
            recordFailureEvent("motor_rpm_override_cleared", "motor RPM override cleared")
        }
    }

    fun setDroneSoundEnabled(enabled: Boolean) {
        updateDroneSoundSettings { it.copy(enabled = enabled) }
    }

    fun setDroneSoundMasterVolume(volume: Float) {
        updateDroneSoundSettings { it.copy(masterVolume = volume) }
    }

    fun setDroneSoundRoughness(value: Float) {
        updateDroneSoundSettings { it.copy(roughness = value) }
    }

    fun setDroneSoundPerMotorMix(value: Float) {
        updateDroneSoundSettings { it.copy(perMotorMix = value) }
    }

    fun setDroneSoundAlertEnabled(enabled: Boolean) {
        updateDroneSoundSettings { it.copy(alertsEnabled = enabled) }
    }

    fun setDroneSoundTestMode(enabled: Boolean) {
        updateDroneSoundSettings { it.copy(testMode = enabled) }
    }

    fun setDroneSoundTestRpm(rpm: Float) {
        updateDroneSoundSettings { it.copy(testRpm = rpm) }
    }

    fun setDroneSoundProceduralEnabled(enabled: Boolean) {
        updateDroneSoundSettings { it.copy(proceduralEnabled = enabled) }
    }

    fun setDroneSoundSampleBedAmount(value: Float) {
        updateDroneSoundSettings { it.copy(sampleBedAmount = value) }
    }

    fun setDroneSoundBladeHarmonicsAmount(value: Float) {
        updateDroneSoundSettings { it.copy(bladeHarmonicsAmount = value) }
    }

    fun setDroneSoundPropWashAmount(value: Float) {
        updateDroneSoundSettings { it.copy(propWashAmount = value) }
    }

    fun setDroneSoundMotorWhineAmount(value: Float) {
        updateDroneSoundSettings { it.copy(motorWhineAmount = value) }
    }

    fun setDroneSoundBladeCount(value: Int) {
        updateDroneSoundSettings { it.copy(bladeCount = value) }
    }

    fun setDroneSoundAcousticProfile(profileId: String) {
        updateDroneSoundSettings { it.copy(acousticProfileId = profileId) }
    }

    fun setDroneSoundSynthQuality(quality: DroneSynthQuality) {
        updateDroneSoundSettings { it.copy(synthQuality = quality) }
    }

    fun setDroneSoundShowAcousticTelemetry(enabled: Boolean) {
        updateDroneSoundSettings { it.copy(showAcousticTelemetry = enabled) }
    }

    fun resetDroneSoundSettings() {
        val controller = droneSoundController
        if (controller != null) {
            controller.resetSettings()
        } else {
            fallbackSoundSettings.value = DroneSoundSettings()
        }
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
    }

    fun clearMission(): Boolean {
        if (!localMissionPolicy().canClear) return false
        simLoop.clearMission()
        applicationContext?.let { MissionPersistence.clear(it) }
        return true
    }

    fun startAutoMission(): Boolean {
        applicationContext?.let { restorePersistedMission(it) }
        val policy = localMissionPolicy()
        if (!policy.canStart) {
            simLoop.noteAck("LOCAL AUTO BLOCKED: ${policy.startBlockedReason}")
            return false
        }
        simLoop.setArmed(true, ControlAuthority.GCS_MISSION)
        if (!state.value.armed) return false
        simLoop.setMode(FlightMode.AUTO, ControlAuthority.GCS_MISSION)
        syncControllerMotorOverride()
        return state.value.mode == FlightMode.AUTO
    }

    private fun localMissionPolicy(): MissionActionPolicy {
        val age = mutableStateUpdatedAtElapsedRealtimeMs.value?.let { SystemClock.elapsedRealtime() - it }
        return missionActionPolicy(
            state.value, missionProgress.value, missionUploadStatus.value, failures.value,
            mutableRuntimeLifecycle.value == RuntimeLifecycleState.RUNNING && age != null && age in 0L..5_000L,
        )
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
        val stored = MissionPersistence.load(context)
        restoredPersistedMission = true
        if (stored.items.isEmpty()) return

        simLoop.loadMission(stored.items, stored.origin)
        // Restoration is not an upload ACK and does not restore an executing flight.
        simLoop.noteAck("MISSION RESTORED ${stored.items.size} ${stored.origin}")
    }

    private fun restoreSimLocation(context: Context) {
        val prefs = context.getSharedPreferences(SimLocationPrefsName, Context.MODE_PRIVATE)
        val id = prefs.getString(SimLocationKeyId, null) ?: SimLocation.Nairobi.id
        val location = if (id == SimLocation.CustomId) {
            SimLocation.custom(
                latitudeDeg = Double.fromBits(prefs.getLong(SimLocationKeyLatitude, SimLocation.Nairobi.latitudeDeg.toBits())),
                longitudeDeg = Double.fromBits(prefs.getLong(SimLocationKeyLongitude, SimLocation.Nairobi.longitudeDeg.toBits())),
                altitudeMslMeters = Float.fromBits(prefs.getInt(SimLocationKeyAltitude, SimLocation.Nairobi.altitudeMslMeters.toBits())),
            )
        } else {
            SimLocation.byId(id)
        }
        if (simLoop.setHomeLocation(location)) {
            mutableSimLocation.value = location
        }
    }

    private fun persistSimLocation(context: Context, location: SimLocation) {
        context.getSharedPreferences(SimLocationPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(SimLocationKeyId, location.id)
            .putLong(SimLocationKeyLatitude, location.latitudeDeg.toBits())
            .putLong(SimLocationKeyLongitude, location.longitudeDeg.toBits())
            .putInt(SimLocationKeyAltitude, location.altitudeMslMeters.toBits())
            .apply()
    }

    private fun startRecordingMonitor() {
        if (recordingJob != null) return
        recordingJob = scope.launch(Dispatchers.IO) {
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

                try {
                    recordingMutex.withLock {
                        if (!runtimeActive) return@withLock
                        val current = state.value
                        val mission = missionProgress.value
                        val currentMissionSignature = missionSignature(mission)
                        val gcsConnected = mavlinkIdentityStatus.value.gcsConnected
                        val sessionActive = recorder.activeSession() != null

                        if (recordingPolicy.shouldStartAutomatically(current.armed, sessionActive)) {
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
                            resetControllerInputsAfterDisarm()
                            recorder.saveMissionSnapshot(mission)
                            recorder.closeSession("vehicle disarmed")
                            recordingPolicy.disarmed()
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
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    recordingPolicy.stoppedByUser(state.value.armed)
                    mutableRecordingStatus.update { it.copy(actionError = "Recording write failed: ${error.message ?: "storage unavailable"}") }
                    delay(1000L)
                }
                publishOperations()
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
            // Auto-calibrate: drop the first batch of sensor samples to let
            // the Android sensor-fusion filter (Kalman/complementary) converge,
            // then average a window of subsequent samples to establish a stable
            // zero-reference. This fixes phones whose rotation-vector sensor
            // reports a drifting offset during the first ~500ms after
            // registration, which would otherwise push past the deadzone and
            // cause erratic gyro/altitude readings.
            if (!phoneSensorCalibration.isCalibrated()) {
                val warmup = mutableListOf<OrientationData>()
                repository.orientationFlow()
                    .drop(AutoCalibrationDropCount)
                    .take(AutoCalibrationSampleCount)
                    .collect { raw ->
                    warmup.add(raw)
                    mutablePhoneSensorRawOrientation.value = raw
                    mutablePhoneSensorSource.value = raw.source
                }
                if (warmup.isNotEmpty()) {
                    val last = warmup.last()
                    val avg = OrientationData(
                        roll = warmup.map { it.roll }.average().toFloat(),
                        pitch = warmup.map { it.pitch }.average().toFloat(),
                        yaw = warmup.map { it.yaw }.average().toFloat(),
                        timestampNanos = last.timestampNanos,
                        source = last.source,
                    )
                    phoneSensorCalibration.calibrate(avg)
                }
            }
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
        val controllerState = mutableControllerInputState.value
        if (
            controllerState.acceptsPhoneSensorInput(
                sensorAvailable = mutablePhoneSensorSource.value != OrientationSource.Unavailable,
                controlAuthority = state.value.controlAuthority,
            )
        ) {
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
        flightRecorder?.let { recorder ->
            mutableRecordingStatus.update { current ->
                recorder.status.value.copy(sessionHistory = current.sessionHistory,
                    actionInProgress = current.actionInProgress, actionError = current.actionError,
                    automaticPaused = recordingPolicy.automaticPaused,
                    automaticEnabled = recordingPolicy.automaticEnabled)
            }
        }
    }

    @Synchronized
    private fun publishOperations() {
        val age = mutableStateUpdatedAtElapsedRealtimeMs.value?.let { SystemClock.elapsedRealtime() - it }
        mutableOperations.value = operationsObserver.update(OpsObservation(
            state.value, failures.value, mavlinkIdentityStatus.value, missionProgress.value,
            missionUploadStatus.value, runtimeLifecycle.value, appVisible,
            runtimeActive && age != null && age in 0..2_000L,
            recordingStatus.value.active, recordingStatus.value.actionError,
            recordingStatus.value.automaticEnabled,
        ), System.currentTimeMillis())
    }

    private fun updateDroneSoundSettings(transform: (DroneSoundSettings) -> DroneSoundSettings) {
        val controller = droneSoundController
        if (controller != null) {
            controller.updateSettings(transform)
        } else {
            fallbackSoundSettings.value = transform(fallbackSoundSettings.value).sanitized()
        }
    }

    private fun updateControllerInputState(transform: (ControllerInputState) -> ControllerInputState) {
        mutableControllerInputState.value = transform(mutableControllerInputState.value).sanitized()
    }

    private fun resetControllerInputsAfterDisarm() {
        mutableControllerInputState.value = ControllerInputState()
        phoneSensorThrottle = ControllerInputState.DefaultThrottle
        phoneSensorYawTrim = 0f
        mutableMotorSpeedOverrideRpm.value = null
        simLoop.setMotorSpeedOverrideRpm(null)
        simLoop.setPilotInput(PilotInput(throttle = ControllerInputState.DefaultThrottle))
        refreshPhoneSensorPilotInput()
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
    private const val RecordingPrefsName = "mavlab_recording"
    private const val AutomaticRecordingKey = "automatic_on_arm"
    private const val PhoneSensorMaxRollAngleRad = 0.5235988f
    private const val PhoneSensorMaxPitchAngleRad = 0.5235988f
    private const val PhoneSensorMaxYawAngleRad = 0.7853982f
    private const val PhoneSensorDeadzoneRad = 0.05235988f
    private const val PhoneSensorExpo = 1.45f
    private const val AutoCalibrationDropCount = 25  // ~500ms at SENSOR_DELAY_GAME
    private const val AutoCalibrationSampleCount = 20 // ~400ms of averaged readings
    private const val SimLocationPrefsName = "mavlab_sim_location"
    private const val SimLocationKeyId = "id"
    private const val SimLocationKeyLatitude = "latitude"
    private const val SimLocationKeyLongitude = "longitude"
    private const val SimLocationKeyAltitude = "altitude_msl"
}
