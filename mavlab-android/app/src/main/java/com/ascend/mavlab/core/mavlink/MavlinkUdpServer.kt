package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.mission.MissionOrigin
import android.content.Context
import android.util.Log
import com.ascend.mavlab.simulation.engine.ControlAuthority
import com.ascend.mavlab.simulation.engine.FlightMode
import com.ascend.mavlab.simulation.engine.PhysicsSimulationEngine
import com.ascend.mavlab.simulation.mission.MissionItem
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
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

class MavlinkUdpServer(
    private val simLoop: PhysicsSimulationEngine,
    private val config: MavlinkSocketConfig = MavlinkSocketConfig(),
    private val onMissionLoaded: (List<MissionItem>) -> Unit = {},
    private val onMissionCleared: () -> Unit = {},
) : MavlinkEndpoint {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vehicleSystemId = config.systemId
    private val builder = MavlinkMessageBuilder(vehicleSystemId, config.componentId)
    private val safetyTransfer = SafetyMissionTransfer(simLoop, builder, ::send)
    private val mutableStatus = MutableStateFlow("Stopped")
    val status: StateFlow<String> = mutableStatus.asStateFlow()
    private val mutableIdentityStatus = MutableStateFlow(
        MavlinkIdentityStatus(
            vehicleSystemId = vehicleSystemId,
            vehicleComponentId = config.componentId,
        ),
    )
    val identityStatus: StateFlow<MavlinkIdentityStatus> = mutableIdentityStatus.asStateFlow()

    private var socket: DatagramSocket? = null
    private var telemetryJob: Job? = null
    private var receiveJob: Job? = null
    private var gcsConnectionJob: Job? = null
    private var lastPeer: UdpDestination? = null
    private val activePeers = ConcurrentHashMap<UdpDestination, Long>()
    private var lastReachedSequenceSent: Int? = null
    private var missionUploadSession: MissionUploadSession? = null
    private var completedUpload: MissionUploadSession? = null
    private var completedUploadAtMs = 0L
    private var uploadUsesLegacyItems = false
    @Volatile
    private var telemetryDestinations: List<UdpDestination> = emptyList()
    @Volatile
    private var wpNavSpeed: Float = 300f
    private var telemetryBurstCount = 0

    fun start(context: Context) {
        if (socket != null) return
        val openedSocket = openSocket()
        openedSocket.broadcast = true
        openedSocket.soTimeout = 100
        socket = openedSocket
        mutableStatus.value = "Running UDP ${openedSocket.localPort} -> QGC ${config.sameDeviceQgcPort}"

        val destinations = destinations(context)
        telemetryDestinations = destinations
        Log.i(
            TAG,
            "MAVLink started sys=$vehicleSystemId comp=${config.componentId} " +
                "local=${openedSocket.localPort} destinations=${destinations.joinToString()}",
        )
        telemetryJob = scope.launch { telemetryLoop(destinations) }
        receiveJob = scope.launch { receiveLoop() }
        gcsConnectionJob = scope.launch { gcsConnectionLoop() }
    }

    private fun sendImmediateTelemetry() {
        val state = simLoop.state.value
        val heartbeatData = builder.heartbeat(state)
        val peers = gcsPeers()
        if (peers.isNotEmpty()) {
            peers.forEach { send(heartbeatData, it) }
        } else {
            val destinations = telemetryDestinations
            destinations.forEach { send(heartbeatData, it) }
        }
    }

    private fun gcsPeers(): List<UdpDestination> {
        val now = System.currentTimeMillis()
        activePeers.entries.removeIf { now - it.value > PeerActivityTimeoutMs }
        return activePeers.keys.toList()
    }

    override suspend fun start() {
        error("Use start(context) so broadcast destinations can be discovered.")
    }

    override suspend fun stop() {
        stopNow()
    }

    fun stopNow() {
        telemetryJob?.cancel()
        receiveJob?.cancel()
        gcsConnectionJob?.cancel()
        socket?.close()
        socket = null
        missionUploadSession?.let { session ->
            simLoop.rejectMissionUpload("upload interrupted: MAVLink stopped", session.expectedCount, session.receivedCount)
        }
        missionUploadSession = null
        completedUpload = null
        safetyTransfer.stop()
        activePeers.clear()
        lastPeer = null
        mutableIdentityStatus.value = mutableIdentityStatus.value.copy(
            gcsConnected = false,
            gcsHeartbeatSeriesStartedAtMs = null,
        )
        mutableStatus.value = "Stopped"
        Log.i(TAG, "MAVLink stopped")
    }

    private fun openSocket(): DatagramSocket {
        return try {
            ipv4Socket(config.localBindPort)
        } catch (_: Exception) {
            ipv4Socket(0)
        }
    }

    private fun ipv4Socket(port: Int): DatagramSocket {
        val channel = DatagramChannel.open(StandardProtocolFamily.INET)
        channel.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        return channel.socket()
    }

    private suspend fun telemetryLoop(destinations: List<UdpDestination>) {
        while (scope.coroutineContext.isActive) {
            sendTelemetryBurst(destinations)
            delay(200)
        }
    }

    private fun sendTelemetryBurst(destinations: List<UdpDestination>) {
        val state = simLoop.state.value
        val missionProgress = simLoop.missionProgress.value
        val peers = gcsPeers()

        if (peers.isEmpty()) {
            val heartbeatData = builder.heartbeat(state)
            destinations.forEach { send(heartbeatData, it) }
            return
        }

        telemetryBurstCount += 1
        val messages = buildList {
            add(builder.heartbeat(state))
            add(builder.attitude(state))
            add(builder.globalPosition(state))
            add(builder.gpsRaw(state))
            add(builder.vfrHud(state))
            add(builder.sysStatus(state))
            add(builder.batteryStatus(state))
            add(builder.extendedSysState(state))
            if (state.safety.enabled) add(builder.fenceStatus(state))
            if (telemetryBurstCount % HomePositionBurstInterval == 0) {
                add(builder.homePosition(simLoop.homePosition()))
            }
            if (missionProgress.loaded) {
                add(builder.missionCurrent(missionProgress.currentIndex.coerceAtMost(missionProgress.items.lastIndex.coerceAtLeast(0))))
                val reached = missionProgress.lastReachedSequence
                if (reached != null && reached != lastReachedSequenceSent) {
                    add(builder.missionItemReached(reached))
                    lastReachedSequenceSent = reached
                }
            }
        }
        peers.forEach { peer ->
            messages.forEach { send(it, peer) }
        }
    }

    private fun send(data: ByteArray, destination: UdpDestination) {
        val currentSocket = socket ?: return
        try {
            val packet = DatagramPacket(
                data,
                data.size,
                InetAddress.getByName(destination.host),
                destination.port,
            )
            currentSocket.send(packet)
        } catch (error: Exception) {
            mutableStatus.value = "Send failed: ${error.message}"
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(65_507) // maximum UDP payload; a datagram may contain many frames
        while (scope.coroutineContext.isActive) {
            val currentSocket = socket ?: return
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                currentSocket.receive(packet)
                val host = packet.address.hostAddress ?: return
                val sender = UdpDestination(host = host, port = packet.port)
                handleInbound(packet.data.copyOf(packet.length), packet.length, sender)
            } catch (_: SocketTimeoutException) {
                // Poll below even without traffic, so a lost mission item can be retried.
            } catch (error: Exception) {
                if (scope.coroutineContext.isActive) {
                    mutableStatus.value = "Receive failed: ${error.message}"
                }
            }
            retryMissionUpload()
        }
    }

    private fun handleInbound(data: ByteArray, length: Int, peer: UdpDestination?) {
        MavlinkParser.parseAll(data, length).forEach { packet -> handlePacket(packet, length, peer) }
    }

    private fun handlePacket(packet: MavlinkPacket, length: Int, peer: UdpDestination?) {
        val timestampMs = System.currentTimeMillis()
        if (peer != null && packet.systemId != vehicleSystemId) {
            activePeers[peer] = timestampMs
            lastPeer = peer
        }
        noteGcsPacket(packet, timestampMs)
        logInbound(packet, peer, length, "received")
        when (packet.messageId) {
            0 -> handleHeartbeat(packet, peer, length, timestampMs)
            11 -> handleSetMode(packet, peer)
            20 -> handleParamRequestRead(packet, peer, length)
            21 -> sendParams(packet, peer, length)
            23 -> handleParamSet(packet, peer, length)
            39 -> handleMissionItemUpload(packet, peer, legacy = true, length = length)
            40 -> sendRequestedMissionItem(packet, peer, legacy = true)
            41 -> handleMissionSetCurrent(packet, peer, length)
            43 -> sendMissionCount(packet, peer)
            44 -> handleMissionCountUpload(packet, peer, length)
            45 -> handleMissionClearAll(packet, peer, length)
            51 -> sendRequestedMissionItem(packet, peer)
            73 -> handleMissionItemUpload(packet, peer, legacy = false, length = length)
            75 -> handleCommandInt(packet, peer)
            76 -> handleCommandLong(packet, peer)
            else -> logInbound(packet, peer, length, "unsupported")
        }
    }

    private fun noteGcsPacket(packet: MavlinkPacket, timestampMs: Long) {
        mutableIdentityStatus.value = mutableIdentityStatus.value.copy(
            lastGcsSystemId = packet.systemId,
            lastGcsComponentId = packet.componentId,
            lastGcsMessageAtMs = timestampMs,
        )
    }

    private suspend fun gcsConnectionLoop() {
        while (scope.coroutineContext.isActive) {
            delay(1_000L)
            mutableIdentityStatus.value = mutableIdentityStatus.value.refreshGcsConnection(System.currentTimeMillis())
        }
    }

    private fun handleHeartbeat(packet: MavlinkPacket, peer: UdpDestination?, length: Int, timestampMs: Long) {
        if (!packet.isGroundControlHeartbeat()) {
            logInbound(packet, peer, length, "non-gcs-heartbeat")
            return
        }
        val collision = packet.systemId == vehicleSystemId
        val result = if (collision) {
            "gcs-heartbeat SYSID_COLLISION"
        } else {
            "gcs-heartbeat"
        }
        logInbound(packet, peer, length, result)
        if (collision) {
            val message = "QGC/GCS is using MAVLab vehicle SYSID $vehicleSystemId. Set QGC MAVLink System ID to $DefaultQgcSystemId."
            mutableIdentityStatus.value = mutableIdentityStatus.value
                .withGcsHeartbeat(timestampMs)
                .copy(
                    lastGcsSystemId = packet.systemId,
                    lastGcsComponentId = packet.componentId,
                    identityConflict = true,
                    recommendedGcsSystemId = DefaultQgcSystemId,
                    message = message,
                )
            mutableStatus.value = "MAVLink identity conflict: set QGC SYSID $DefaultQgcSystemId"
            simLoop.noteInbound("MAVLink SYSID conflict: GCS=${packet.systemId}, vehicle=$vehicleSystemId")
            simLoop.noteAck("SYSID conflict")
            Log.w(
                TAG,
                "QGC/GCS heartbeat uses MAVLab vehicle system ID $vehicleSystemId; " +
                    "set QGC MAVLink System ID to $DefaultQgcSystemId.",
            )
        } else {
            mutableIdentityStatus.value = mutableIdentityStatus.value
                .withGcsHeartbeat(timestampMs)
                .copy(
                    lastGcsSystemId = packet.systemId,
                    lastGcsComponentId = packet.componentId,
                    identityConflict = false,
                    recommendedGcsSystemId = DefaultQgcSystemId,
                    message = "",
                )
            if (mutableStatus.value.startsWith("MAVLink identity conflict")) {
                mutableStatus.value = "Running UDP ${socket?.localPort ?: config.localBindPort} -> QGC ${config.sameDeviceQgcPort}"
            }
        }
    }

    private fun handleSetMode(packet: MavlinkPacket, peer: UdpDestination?) {
        if (packet.payload.size < 6) return
        if (!isTargetedToVehicle(packet, targetSystemOffset = 4, label = "SET_MODE")) return
        val customMode = packet.payload.leUInt32(0)
        val mode = FlightMode.fromCustomModeOrNull(customMode)
        if (mode == null) {
            simLoop.noteInbound("SET_MODE unsupported custom mode $customMode")
            ack(0, MAV_RESULT_UNSUPPORTED, peer, "SET_MODE unsupported $customMode", packet)
            return
        }
        simLoop.setMode(mode, ControlAuthority.GCS_DIRECT)
        simLoop.noteInbound("SET_MODE ${mode.displayName}")
        sendImmediateTelemetry()
        ack(0, if (simLoop.state.value.mode == mode) MAV_RESULT_ACCEPTED else MAV_RESULT_DENIED, peer, "SET_MODE", packet)
    }

    private data class CommandInvocation(
        val command: Int,
        val param1: Float,
        val param2: Float,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val altitude: Float,
    )

    private fun handleCommandLong(packet: MavlinkPacket, peer: UdpDestination?) {
        if (packet.payload.size < 33) return
        if (!isTargetedToVehicle(packet, targetSystemOffset = 30, label = "COMMAND_LONG")) return
        val payload = packet.payload
        val invocation = CommandInvocation(
            command = payload.leUInt16(28),
            param1 = payload.leFloat(0),
            param2 = payload.leFloat(4),
            latitudeDeg = payload.leFloat(16).toDouble(),
            longitudeDeg = payload.leFloat(20).toDouble(),
            altitude = payload.leFloat(24),
        )
        Log.i(TAG, "rx COMMAND_LONG: command=${invocation.command}")
        simLoop.noteInbound("COMMAND_LONG ${invocation.command}")
        executeCommand(invocation, peer, packet)
    }

    private fun handleCommandInt(packet: MavlinkPacket, peer: UdpDestination?) {
        if (packet.payload.size < 32) return
        if (!isTargetedToVehicle(packet, targetSystemOffset = 30, label = "COMMAND_INT")) return
        // MAVLink v2 truncates trailing zero bytes; pad back to the full 35-byte payload.
        val payload = if (packet.payload.size >= 35) packet.payload else packet.payload.copyOf(35)
        val invocation = CommandInvocation(
            command = payload.leUInt16(28),
            param1 = payload.leFloat(0),
            param2 = payload.leFloat(4),
            latitudeDeg = payload.leInt32(16) / 1e7,
            longitudeDeg = payload.leInt32(20) / 1e7,
            altitude = payload.leFloat(24),
        )
        Log.i(TAG, "rx COMMAND_INT: command=${invocation.command}")
        simLoop.noteInbound("COMMAND_INT ${invocation.command}")
        executeCommand(invocation, peer, packet)
    }

    private fun executeCommand(invocation: CommandInvocation, peer: UdpDestination?, source: MavlinkPacket) {
        fun ack(command: Int, result: Int, peer: UdpDestination?, label: String) =
            this@MavlinkUdpServer.ack(command, result, peer, label, source)
        val command = invocation.command
        when (command) {
            MAV_CMD_COMPONENT_ARM_DISARM -> {
                val arm = invocation.param1 >= 0.5f
                simLoop.setArmed(arm, ControlAuthority.GCS_DIRECT)
                sendImmediateTelemetry()
                ack(command, if (simLoop.state.value.armed == arm) MAV_RESULT_ACCEPTED else MAV_RESULT_DENIED, peer, if (arm) "ARM" else "DISARM")
            }
            MAV_CMD_NAV_TAKEOFF -> {
                val requestedAltitude = invocation.altitude
                    .takeIf { it.isFinite() && it > 0f }
                    ?: 10f
                val accepted = simLoop.takeoff(requestedAltitude, ControlAuthority.GCS_DIRECT)
                sendImmediateTelemetry()
                ack(command, if (accepted) MAV_RESULT_ACCEPTED else MAV_RESULT_DENIED, peer, "TAKEOFF")
            }
            MAV_CMD_NAV_LAND -> {
                simLoop.land(ControlAuthority.GCS_DIRECT)
                sendImmediateTelemetry()
                ack(command, MAV_RESULT_ACCEPTED, peer, "LAND")
            }
            MAV_CMD_DO_SET_HOME -> {
                val useCurrent = invocation.param1 >= 0.5f ||
                    (invocation.latitudeDeg == 0.0 && invocation.longitudeDeg == 0.0)
                val accepted = if (useCurrent) {
                    simLoop.setHomeToCurrentPosition()
                } else {
                    simLoop.setHomeToCoordinates(invocation.latitudeDeg, invocation.longitudeDeg)
                }
                if (accepted) sendHomePosition(peer)
                ack(
                    command,
                    if (accepted) MAV_RESULT_ACCEPTED else MAV_RESULT_DENIED,
                    peer,
                    if (accepted) "DO_SET_HOME" else "DO_SET_HOME INVALID",
                )
            }
            MAV_CMD_GET_HOME_POSITION -> {
                sendHomePosition(peer)
                ack(command, MAV_RESULT_ACCEPTED, peer, "GET_HOME_POSITION")
            }
            MAV_CMD_SET_MESSAGE_INTERVAL -> {
                ack(command, MAV_RESULT_ACCEPTED, peer, "SET_MESSAGE_INTERVAL")
            }
            MAV_CMD_REQUEST_MESSAGE -> {
                handleRequestMessage(invocation.param1.toInt(), peer, command, source)
            }
            MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES -> {
                sendAutopilotVersion(peer)
                ack(command, MAV_RESULT_ACCEPTED, peer, "REQUEST_AUTOPILOT_CAPABILITIES")
            }
            MAV_CMD_MISSION_START -> {
                var accepted = simLoop.missionProgress.value.loaded
                if (accepted) {
                    simLoop.setArmed(true, ControlAuthority.GCS_MISSION)
                    simLoop.setMode(FlightMode.AUTO, ControlAuthority.GCS_MISSION)
                    accepted = simLoop.state.value.armed && simLoop.state.value.mode == FlightMode.AUTO
                    sendImmediateTelemetry()
                }
                ack(
                    command,
                    if (accepted) MAV_RESULT_ACCEPTED else MAV_RESULT_DENIED,
                    peer,
                    if (accepted) "MISSION_START" else "MISSION_START NO MISSION",
                )
            }
            MAV_CMD_DO_SET_MODE -> {
                val customMode = invocation.param2.toCustomModeIdOrNull()
                val mode = customMode?.let { FlightMode.fromCustomModeOrNull(it) }
                if (customMode == null) {
                    ack(command, MAV_RESULT_DENIED, peer, "DO_SET_MODE invalid custom mode ${invocation.param2}")
                } else if (mode == null) {
                    ack(command, MAV_RESULT_UNSUPPORTED, peer, "DO_SET_MODE unsupported custom mode $customMode")
                } else {
                    simLoop.setMode(mode, ControlAuthority.GCS_DIRECT)
                    sendImmediateTelemetry()
                    ack(command, if (simLoop.state.value.mode == mode) MAV_RESULT_ACCEPTED else MAV_RESULT_DENIED, peer, "DO_SET_MODE ${mode.displayName}")
                }
            }
            207 -> {
                // Selective type/floor control is not silently treated as enabling all boundaries.
                val accepted = invocation.param2 == 0f && invocation.param1 in listOf(0f, 1f) && simLoop.setSafetyParameter("FENCE_ENABLE", invocation.param1)
                ack(command, if (accepted) MAV_RESULT_ACCEPTED else MAV_RESULT_DENIED, peer, "DO_FENCE_ENABLE")
            }
            MAV_CMD_PREFLIGHT_CALIBRATION -> {
                ack(command, MAV_RESULT_ACCEPTED, peer, "PREFLIGHT_CALIBRATION")
            }
            MAV_CMD_DO_START_MAG_CAL -> {
                ack(command, MAV_RESULT_ACCEPTED, peer, "DO_START_MAG_CAL")
            }
            MAV_CMD_DO_ACCEPT_MAG_CAL -> {
                ack(command, MAV_RESULT_ACCEPTED, peer, "DO_ACCEPT_MAG_CAL")
            }
            MAV_CMD_DO_CANCEL_MAG_CAL -> {
                ack(command, MAV_RESULT_ACCEPTED, peer, "DO_CANCEL_MAG_CAL")
            }
            MAV_CMD_DO_CHANGE_SPEED -> {
                simLoop.setWpNavSpeed(invocation.param2)
                ack(command, MAV_RESULT_ACCEPTED, peer, "DO_CHANGE_SPEED")
            }
            else -> {
                ack(command, MAV_RESULT_UNSUPPORTED, peer, "UNSUPPORTED $command")
            }
        }
    }

    private fun handleRequestMessage(requestedMessageId: Int, peer: UdpDestination?, command: Int, source: MavlinkPacket) {
        fun ack(command: Int, result: Int, peer: UdpDestination?, label: String) =
            this@MavlinkUdpServer.ack(command, result, peer, label, source)
        when (requestedMessageId) {
            MAVLINK_MSG_ID_AUTOPILOT_VERSION -> {
                sendAutopilotVersion(peer)
                ack(command, MAV_RESULT_ACCEPTED, peer, "REQUEST_MESSAGE AUTOPILOT_VERSION")
            }
            MAVLINK_MSG_ID_HOME_POSITION -> {
                sendHomePosition(peer)
                ack(command, MAV_RESULT_ACCEPTED, peer, "REQUEST_MESSAGE HOME_POSITION")
            }
            245 -> {
                val destination = peer ?: lastPeer ?: return
                send(builder.extendedSysState(simLoop.state.value), destination)
                ack(command, MAV_RESULT_ACCEPTED, peer, "REQUEST_MESSAGE EXTENDED_SYS_STATE")
            }
            162 -> {
                val destination = peer ?: lastPeer ?: return
                send(builder.fenceStatus(simLoop.state.value), destination)
                ack(command, MAV_RESULT_ACCEPTED, peer, "REQUEST_MESSAGE FENCE_STATUS")
            }
            else -> {
                ack(command, MAV_RESULT_UNSUPPORTED, peer, "REQUEST_MESSAGE unsupported $requestedMessageId")
            }
        }
    }

    private fun sendHomePosition(peer: UdpDestination?) {
        val destination = peer ?: lastPeer ?: return
        send(builder.homePosition(simLoop.homePosition()), destination)
        simLoop.noteAck("HOME_POSITION")
    }

    private fun sendAutopilotVersion(peer: UdpDestination?) {
        val destination = peer ?: lastPeer ?: return
        send(builder.autopilotVersion(), destination)
        simLoop.noteAck("AUTOPILOT_VERSION")
    }

    private fun handleParamSet(packet: MavlinkPacket, peer: UdpDestination?, length: Int) {
        if (!isTargetedToVehicle(packet, targetSystemOffset = 4, label = "PARAM_SET")) {
            logInbound(packet, peer, length, "ignored target-mismatch")
            return
        }
        val payload = packet.payload
        if (payload.size < 23) return
        val paramValue = payload.leFloat(0)
        val paramIdBytes = payload.copyOfRange(6, 22)
        val paramId = String(paramIdBytes, Charsets.US_ASCII).trimEnd { it == '\u0000' }

        if (paramId == "WPNAV_SPEED" && paramValue.isFinite() && paramValue > 0f) {
            wpNavSpeed = paramValue
            simLoop.setWpNavSpeedParameter(paramValue / 100f)
            Log.i(TAG, "PARAM_SET: WPNAV_SPEED set to $paramValue cm/s (${paramValue / 100f} m/s)")
        }
        simLoop.setSafetyParameter(paramId, paramValue)

        val params = getParamsList()
        val index = params.indexOfFirst { it.first == paramId }
        if (index == -1) return // Unknown/read-only parameters are not falsely echoed as writes.
        val finalValue = params[index].second
        val destination = peer ?: lastPeer ?: return
        send(
            builder.paramValue(paramId, finalValue, if (index != -1) index else 65535, params.size),
            destination
        )
    }

    private fun getParamsList(): List<Pair<String, Float>> {
        return listOf(
            "SYSID_THISMAV" to vehicleSystemId.toFloat(),
            "MAV_SYSID" to vehicleSystemId.toFloat(),
            "SYSID_MYGCS" to DefaultQgcSystemId.toFloat(),
            "MAV_GCS_SYSID" to DefaultQgcSystemId.toFloat(),
            "MAV_TYPE" to 2f,
            "WPNAV_SPEED" to wpNavSpeed,
            "RCMAP_ROLL" to 1f,
            "RCMAP_PITCH" to 2f,
            "RCMAP_THROTTLE" to 3f,
            "RCMAP_YAW" to 4f,
            "RC0_MIN" to 1000f,
            "RC0_MAX" to 2000f,
            "RC0_TRIM" to 1500f,
            "RC1_MIN" to 1000f,
            "RC1_MAX" to 2000f,
            "RC1_TRIM" to 1500f,
            "RC2_MIN" to 1000f,
            "RC2_MAX" to 2000f,
            "RC2_TRIM" to 1500f,
            "RC3_MIN" to 1000f,
            "RC3_MAX" to 2000f,
            "RC3_TRIM" to 1500f,
            "RC4_MIN" to 1000f,
            "RC4_MAX" to 2000f,
            "RC4_TRIM" to 1500f,
            "COMPASS_DEV_ID" to 1f,
            "COMPASS_DEV_ID2" to 2f,
            "COMPASS_DEV_ID3" to 3f,
            "COMPASS_USE" to 1f,
            "COMPASS_USE2" to 1f,
            "COMPASS_USE3" to 0f,
            "INS_ACCOFFS_X" to 0.001f,
            "INS_ACCOFFS_Y" to 0.001f,
            "INS_ACCOFFS_Z" to 0.001f,
            "INS_ACCSCAL_X" to 1.001f,
            "INS_ACCSCAL_Y" to 1.001f,
            "INS_ACCSCAL_Z" to 1.001f,
            "FLTMODE1" to 0f,
            "FLTMODE2" to 0f,
            "FLTMODE3" to 0f,
            "FLTMODE4" to 0f,
            "FLTMODE5" to 0f,
            "FLTMODE6" to 0f,
            "COMPASS_OFS_X" to 5f,
            "COMPASS_OFS_Y" to 5f,
            "COMPASS_OFS_Z" to 5f,
            "COMPASS_OFS2_X" to 5f,
            "COMPASS_OFS2_Y" to 5f,
            "COMPASS_OFS2_Z" to 5f,
            "COMPASS_OFS3_X" to 5f,
            "COMPASS_OFS3_Y" to 5f,
            "COMPASS_OFS3_Z" to 5f,
            "COMPASS_DEC" to 0f,
            "BATT_MONITOR" to 0f,
            "ARMING_CHECK" to 0f,
            "AHRS_ORIENTATION" to 0f,
            "COMPASS_AUTODEC" to 1f,
            "RC1_REV" to 0f,
            "RC2_REV" to 0f,
            "RC3_REV" to 0f,
            "RC4_REV" to 0f,
            "r.RC6_OPTION" to 0f,
            "r.RC7_OPTION" to 0f,
            "r.RC8_OPTION" to 0f,
            "r.RC9_OPTION" to 0f,
            "r.RC10_OPTION" to 0f,
            "r.RC11_OPTION" to 0f,
            "r.RC12_OPTION" to 0f,
            "r.RC13_OPTION" to 0f,
            "r.RC14_OPTION" to 0f,
            "r.RC15_OPTION" to 0f,
            "r.RC16_OPTION" to 0f,
            "r.TUNE_MIN" to 0f,
            "r.TUNE_MAX" to 0f,
            "TUNE" to 0f,
            "AUTOTUNE_AXES" to 0f,
            "MOT_SPIN_MIN" to 0.15f,
            "MOT_SPIN_ARM" to 0.10f,
            "PSC_ACCZ_P" to 0.50f,
            "PSC_ACCZ_I" to 1.00f,
            "ATC_RAT_RLL_P" to 0.15f,
            "ATC_RAT_RLL_I" to 0.15f,
            "ATC_RAT_PIT_P" to 0.15f,
            "ATC_RAT_PIT_I" to 0.15f,
            "LAND_SPEED" to 50f,
            "RTL_ALT_FINAL" to 0f,
            "RTL_LOIT_TIME" to 5000f,
            "FS_THR_VALUE" to 975f,
            "FS_THR_ENABLE" to 0f,
            "FS_GCS_ENABLE" to 0f,
        ) + simLoop.safetyPlans.value.config.parameters() + listOf(
            "FENCE_TOTAL" to simLoop.safetyPlans.value.fence.size.toFloat(),
            "RALLY_TOTAL" to simLoop.safetyPlans.value.rally.size.toFloat(),
        )
    }

    private fun sendParams(packet: MavlinkPacket, peer: UdpDestination?, length: Int) {
        if (!isTargetedToVehicle(packet, targetSystemOffset = 0, label = "PARAM_REQUEST_LIST")) {
            logInbound(packet, peer, length, "ignored target-mismatch")
            return
        }
        simLoop.noteInbound("PARAM_REQUEST_LIST")
        val destination = peer ?: lastPeer ?: return
        scope.launch {
            val params = getParamsList()
            params.forEachIndexed { index, param ->
                send(builder.paramValue(param.first, param.second, index, params.size), destination)
                delay(2)
            }
        }
    }

    private fun handleParamRequestRead(packet: MavlinkPacket, peer: UdpDestination?, length: Int) {
        if (!isTargetedToVehicle(packet, targetSystemOffset = 2, label = "PARAM_REQUEST_READ")) {
            logInbound(packet, peer, length, "ignored target-mismatch")
            return
        }
        val payload = packet.payload
        if (payload.size < 20) return

        val paramIndex = payload.leInt16(0)
        val paramIdBytes = payload.copyOfRange(4, 20)
        val paramId = String(paramIdBytes, Charsets.US_ASCII).trimEnd { it == '\u0000' }

        val params = getParamsList()
        val foundIndex: Int
        val foundParam: Pair<String, Float>?

        if (paramIndex >= 0 && paramIndex < params.size) {
            foundIndex = paramIndex
            foundParam = params[paramIndex]
        } else {
            foundIndex = params.indexOfFirst { it.first == paramId }
            foundParam = if (foundIndex != -1) params[foundIndex] else null
        }

        if (foundParam != null) {
            simLoop.noteInbound("PARAM_REQUEST_READ index=$foundIndex id=$paramId (found: ${foundParam.first})")
            val destination = peer ?: lastPeer ?: return
            send(
                builder.paramValue(foundParam.first, foundParam.second, foundIndex, params.size),
                destination
            )
        } else {
            simLoop.noteInbound("PARAM_REQUEST_READ index=$paramIndex id=$paramId (not found)")
        }
    }

    private fun sendMissionCount(packet: MavlinkPacket, peer: UdpDestination?) {
        if (!isTargetedToVehicle(packet, targetSystemOffset = 0, label = "MISSION_REQUEST_LIST")) return
        if (!acceptMissionType(packet, peer, 2)) return
        val type = missionType(packet, 2)
        if (type != 0) { safetyTransfer.readCount(packet, peer ?: lastPeer ?: return, type); return }
        simLoop.noteInbound("MISSION_REQUEST_LIST")
        val destination = peer ?: lastPeer ?: return
        send(
            builder.missionCount(
                count = simLoop.missionProgress.value.items.size,
                targetSystem = packet.systemId,
                targetComponent = packet.componentId,
            ),
            destination,
        )
    }

    private fun sendRequestedMissionItem(
        packet: MavlinkPacket,
        peer: UdpDestination?,
        legacy: Boolean = false,
    ) {
        if (packet.payload.size < 4) return
        val targetMatches = isTargetedToVehicle(packet, targetSystemOffset = 2, label = if (legacy) "MISSION_REQUEST" else "MISSION_REQUEST_INT")
        if (!targetMatches) return
        if (!acceptMissionType(packet, peer, 4)) return
        val type = missionType(packet, 4)
        if (type != 0) { safetyTransfer.readItem(packet, peer ?: lastPeer ?: return, type, legacy); return }
        val sequence = packet.payload.leUInt16(0)
        val progress = simLoop.missionProgress.value
        val item = progress.items.getOrNull(sequence) ?: return
        val destination = peer ?: lastPeer ?: return
        simLoop.noteInbound("${if (legacy) "MISSION_REQUEST" else "MISSION_REQUEST_INT"} $sequence")
        val data = if (legacy) {
            builder.missionItem(item, progress.currentIndex, packet.systemId, packet.componentId)
        } else {
            builder.missionItemInt(item, progress.currentIndex, packet.systemId, packet.componentId)
        }
        send(data, destination)
    }

    private fun handleMissionCountUpload(packet: MavlinkPacket, peer: UdpDestination?, length: Int) {
        val destination = peer ?: lastPeer ?: return
        if (!isTargetedToVehicle(packet, targetSystemOffset = 2, label = "MISSION_COUNT")) {
            logInbound(packet, peer, length, "ignored target-mismatch")
            return
        }
        if (!acceptMissionType(packet, destination, 4)) return
        val type = missionType(packet, 4)
        if (type != 0) {
            if (isIdentityConflicted(packet, "SAFETY_COUNT")) {
                sendMissionAck(MAV_MISSION_DENIED, packet, destination, "Safety identity conflict", type); return
            }
            safetyTransfer.count(packet, destination, type); return
        }
        val activeUpload = missionUploadSession
        if (activeUpload != null && !activeUpload.owns(packet, destination)) {
            sendMissionAck(MAV_MISSION_DENIED, packet, destination, "MISSION_COUNT other uploader")
            return
        }
        if (isIdentityConflicted(packet, "MISSION_COUNT")) {
            missionUploadSession = null
            simLoop.rejectMissionUpload("MAVLink system ID conflict. Set QGC system ID to $DefaultQgcSystemId.")
            sendMissionAck(MAV_MISSION_DENIED, packet, destination, "MISSION_COUNT SYSID CONFLICT")
            logInbound(packet, peer, length, "rejected sysid-conflict")
            return
        }
        val session = MissionUploadSession.parseMissionCount(packet, destination)
        if (session == null) {
            simLoop.rejectMissionUpload("invalid mission count")
            sendMissionAck(MAV_MISSION_ERROR, packet, destination, "MISSION_COUNT rejected")
            logInbound(packet, peer, length, "rejected invalid-count")
            return
        }
        if (session.expectedCount == 0) {
            missionUploadSession = null
            completedUpload = null
            simLoop.clearMission()
            onMissionCleared()
            simLoop.acceptMissionUpload(0)
            sendMissionAck(MAV_MISSION_ACCEPTED, packet, destination, "MISSION_CLEAR_EMPTY")
            logInbound(packet, peer, length, "accepted clear-empty")
            return
        }
        missionUploadSession = session
        completedUpload = null
        uploadUsesLegacyItems = false
        simLoop.beginMissionUpload(expectedCount = session.expectedCount, requestedSequence = 0)
        requestMissionItem(session)
        logInbound(packet, peer, length, "accepted upload-count=${session.expectedCount} request=0")
    }

    private fun handleMissionItemUpload(
        packet: MavlinkPacket,
        peer: UdpDestination?,
        legacy: Boolean,
        length: Int,
    ) {
        val destination = peer ?: lastPeer ?: return
        val targetOffset = if (legacy) 32 else 32
        if (!isTargetedToVehicle(packet, targetSystemOffset = targetOffset, label = if (legacy) "MISSION_ITEM" else "MISSION_ITEM_INT")) {
            logInbound(packet, peer, length, "ignored target-mismatch")
            return
        }
        if (!acceptMissionType(packet, destination, 37)) return
        val type = missionType(packet, 37)
        if (type != 0) { safetyTransfer.item(packet, destination, type, legacy); return }
        val session = missionUploadSession
        if (session == null) {
            val previous = completedUpload
            val duplicate = if (legacy) MissionUploadSession.parseMissionItem(packet) else MissionUploadSession.parseMissionItemInt(packet)
            if (previous != null && previous.owns(packet, destination) && duplicate != null &&
                System.currentTimeMillis() - completedUploadAtMs <= MissionUploadSession.UploadTimeoutMs &&
                previous.matchesReceived(duplicate)
            ) {
                sendMissionAck(MAV_MISSION_ACCEPTED, packet, destination, "MISSION_UPLOAD repeated final ACK")
                return
            }
            simLoop.rejectMissionUpload("no active upload session")
            sendMissionAck(MAV_MISSION_ERROR, packet, destination, "MISSION_ITEM no session")
            logInbound(packet, peer, length, "rejected no-session")
            return
        }
        if (!session.owns(packet, destination)) {
            sendMissionAck(MAV_MISSION_DENIED, packet, destination, "MISSION_ITEM other uploader")
            return
        }
        if (session.expired) {
            missionUploadSession = null
            simLoop.rejectMissionUpload("upload timeout", expectedCount = session.expectedCount, receivedCount = session.receivedCount)
            sendMissionAck(MAV_MISSION_ERROR, packet, destination, "MISSION_UPLOAD timeout")
            logInbound(packet, peer, length, "rejected timeout")
            return
        }
        val item = if (legacy) {
            MissionUploadSession.parseMissionItem(packet)
        } else {
            MissionUploadSession.parseMissionItemInt(packet)
        }
        if (item != null) {
            Log.i(TAG, "Parsed mission item: seq=${item.sequence} cmd=${item.command} lat=${item.latitudeDeg} lon=${item.longitudeDeg} alt=${item.altitudeAglMeters}")
        }
        if (item == null) {
            missionUploadSession = null
            val rejection = missionItemRejection(packet) ?: MissionItemRejection(MAV_MISSION_INVALID, "Invalid mission item at sequence ${packet.payload.leUInt16(28)}")
            simLoop.rejectMissionUpload(rejection.reason, expectedCount = session.expectedCount, receivedCount = session.receivedCount)
            sendMissionAck(rejection.ack, packet, destination, "MISSION_ITEM ${rejection.reason}")
            logInbound(packet, peer, length, "rejected invalid-item")
            return
        }
        when (val result = session.receive(item)) {
            is MissionUploadResult.RequestNext -> {
                simLoop.recordMissionUploadProgress(
                    expectedCount = session.expectedCount,
                    receivedCount = session.receivedCount,
                    lastReceivedSequence = item.sequence,
                    nextRequestedSequence = result.sequence,
                )
                uploadUsesLegacyItems = legacy
                requestMissionItem(session)
                logInbound(packet, peer, length, "accepted item=${item.sequence} request=${result.sequence}")
            }
            is MissionUploadResult.Complete -> {
                missionUploadSession = null
                completedUpload = session
                completedUploadAtMs = System.currentTimeMillis()
                simLoop.loadMission(result.items, MissionOrigin.GCS_UPLOAD)
                onMissionLoaded(result.items)
                simLoop.acceptMissionUpload(count = result.items.size, lastReceivedSequence = item.sequence)
                lastReachedSequenceSent = null
                sendMissionAck(MAV_MISSION_ACCEPTED, packet, destination, "MISSION_UPLOAD ${result.items.size}")
                logInbound(packet, peer, length, "accepted complete=${result.items.size}")
            }
            is MissionUploadResult.Rejected -> {
                missionUploadSession = null
                simLoop.rejectMissionUpload(
                    reason = result.reason,
                    expectedCount = session.expectedCount,
                    receivedCount = session.receivedCount,
                )
                sendMissionAck(MAV_MISSION_INVALID_SEQUENCE, packet, destination, "MISSION_ITEM ${result.reason}")
                logInbound(packet, peer, length, "rejected ${result.reason}")
            }
        }
    }

    private fun handleMissionClearAll(packet: MavlinkPacket, peer: UdpDestination?, length: Int) {
        val destination = peer ?: lastPeer ?: return
        if (!isTargetedToVehicle(packet, targetSystemOffset = 0, label = "MISSION_CLEAR_ALL")) {
            logInbound(packet, peer, length, "ignored target-mismatch")
            return
        }
        if (!acceptMissionType(packet, destination, 2, allowAll = true)) return
        val type = missionType(packet, 2)
        if (type != 0) {
            if (simLoop.state.value.armed || isIdentityConflicted(packet, "SAFETY_CLEAR")) {
                sendMissionAck(MAV_MISSION_DENIED, packet, destination, "Disarm / resolve identity before safety clear", type); return
            }
            if (safetyTransfer.clear(packet, destination, type) && type == 255) {
                missionUploadSession = null
                completedUpload = null
                onMissionCleared()
                lastReachedSequenceSent = null
            }
            return
        }
        missionUploadSession = null
        completedUpload = null
        simLoop.clearMission()
        onMissionCleared()
        lastReachedSequenceSent = null
        sendMissionAck(MAV_MISSION_ACCEPTED, packet, destination, "MISSION_CLEAR_ALL", type)
        logInbound(packet, peer, length, "accepted clear-all")
    }

    private fun handleMissionSetCurrent(packet: MavlinkPacket, peer: UdpDestination?, length: Int) {
        if (packet.payload.size < 4) return
        val destination = peer ?: lastPeer ?: return
        if (!isTargetedToVehicle(packet, targetSystemOffset = 2, label = "MISSION_SET_CURRENT")) {
            logInbound(packet, peer, length, "ignored target-mismatch")
            return
        }
        val sequence = packet.payload.leUInt16(0)
        val accepted = simLoop.setMissionCurrent(sequence)
        sendMissionAck(
            if (accepted) MAV_MISSION_ACCEPTED else MAV_MISSION_INVALID_SEQUENCE,
            packet,
            destination,
            if (accepted) "MISSION_SET_CURRENT $sequence" else "MISSION_SET_CURRENT INVALID $sequence",
        )
        logInbound(packet, peer, length, if (accepted) "accepted current=$sequence" else "rejected current=$sequence")
    }

    private fun sendMissionAck(type: Int, packet: MavlinkPacket, destination: UdpDestination, label: String, missionType: Int = 0) {
        simLoop.noteAck(label)
        send(builder.missionAck(type, packet.systemId, packet.componentId, missionType), destination)
    }

    private fun acceptMissionType(packet: MavlinkPacket, peer: UdpDestination?, offset: Int, allowAll: Boolean = false): Boolean {
        val type = packet.payload.getOrNull(offset)?.toInt()?.and(0xff) ?: 0
        if (type in 0..2 || allowAll && type == 255) return true
        val destination = peer ?: lastPeer ?: return false
        sendMissionAck(3, packet, destination, "Unsupported mission type $type", type)
        return false
    }

    private fun missionType(packet: MavlinkPacket, offset: Int) = packet.payload.getOrNull(offset)?.toInt()?.and(0xff) ?: 0

    private fun requestMissionItem(session: MissionUploadSession) {
        val request = if (uploadUsesLegacyItems) {
            builder.missionRequest(session.nextSequence, session.sourceSystemId, session.sourceComponentId)
        } else {
            builder.missionRequestInt(session.nextSequence, session.sourceSystemId, session.sourceComponentId)
        }
        send(request, session.peer)
        session.markRequestSent(System.currentTimeMillis())
    }

    private fun retryMissionUpload() {
        safetyTransfer.retry()
        val session = missionUploadSession ?: return
        when (val retry = session.pollRetry(System.currentTimeMillis())) {
            MissionUploadRetry.None -> Unit
            is MissionUploadRetry.Request -> {
                requestMissionItem(session)
                Log.i(TAG, "MISSION_UPLOAD retry request=${retry.sequence}")
            }
            MissionUploadRetry.Exhausted -> {
                missionUploadSession = null
                simLoop.rejectMissionUpload("upload timeout at sequence ${session.nextSequence}", session.expectedCount, session.receivedCount)
                send(builder.missionAck(MAV_MISSION_ERROR, session.sourceSystemId, session.sourceComponentId), session.peer)
                Log.w(TAG, "MISSION_UPLOAD timed out at sequence ${session.nextSequence}")
            }
        }
    }

    private fun ack(command: Int, result: Int, peer: UdpDestination?, label: String, source: MavlinkPacket) {
        simLoop.noteAck(label)
        val destination = peer ?: lastPeer ?: return
        send(builder.commandAck(command, result, source.systemId, source.componentId), destination)
    }

    private fun isIdentityConflicted(packet: MavlinkPacket, label: String): Boolean {
        val conflicted = mutableIdentityStatus.value.identityConflict || packet.systemId == vehicleSystemId
        if (conflicted) {
            val message = "MAVLink system ID conflict. Set QGC system ID to $DefaultQgcSystemId."
            mutableIdentityStatus.value = mutableIdentityStatus.value.copy(
                lastGcsSystemId = packet.systemId,
                lastGcsComponentId = packet.componentId,
                identityConflict = true,
                recommendedGcsSystemId = DefaultQgcSystemId,
                message = message,
            )
            simLoop.noteInbound("$label blocked: GCS SYSID ${packet.systemId} conflicts with vehicle SYSID $vehicleSystemId")
        }
        return conflicted
    }

    private fun isTargetedToVehicle(packet: MavlinkPacket, targetSystemOffset: Int, label: String): Boolean {
        if (packet.payload.size <= targetSystemOffset) {
            simLoop.noteInbound("Ignored $label missing target_system")
            return false
        }
        val targetSystem = packet.payload[targetSystemOffset].toInt() and 0xff
        val targetComponent = if (packet.messageId == 11) 0 else
            packet.payload.getOrNull(targetSystemOffset + 1)?.toInt()?.and(0xff) ?: 0
        val matches = (targetSystem == 0 || targetSystem == vehicleSystemId) &&
            (targetComponent == 0 || targetComponent == config.componentId)
        if (!matches) {
            simLoop.noteInbound("Ignored $label target_system=$targetSystem vehicle_system=$vehicleSystemId")
            Log.w(TAG, "Ignored $label target_system=$targetSystem vehicle_system=$vehicleSystemId")
        }
        return matches
    }

    private fun logInbound(packet: MavlinkPacket, peer: UdpDestination?, length: Int, result: String) {
        val peerLabel = peer?.let { "${it.host}:${it.port}" } ?: "unknown"
        simLoop.noteInbound("rx id=${packet.messageId} from=$peerLabel len=$length $result")
        Log.i(TAG, "rx id=${packet.messageId} sys=${packet.systemId} comp=${packet.componentId} from=$peerLabel len=$length $result")
    }

    private fun destinations(context: Context): List<UdpDestination> {
        return buildList {
            add(UdpDestination(config.sameDeviceHost, config.sameDeviceQgcPort))
            addAll(config.lanDestinations)
            addAll(networkBroadcasts())
            add(UdpDestination("255.255.255.255", 14550))
        }.distinct()
    }

    private fun networkBroadcasts(): List<UdpDestination> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { network ->
                    network.interfaceAddresses.mapNotNull { address ->
                        val broadcast = address.broadcast
                        if (broadcast is Inet4Address) {
                            UdpDestination(broadcast.hostAddress ?: return@mapNotNull null, 14550)
                        } else {
                            null
                        }
                    }
                }
        }.getOrDefault(emptyList())
    }

    private fun ByteArray.leUInt16(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun ByteArray.leInt16(offset: Int): Int {
        return ((this[offset + 1].toInt() shl 8) or (this[offset].toInt() and 0xff)).toShort().toInt()
    }

    private fun ByteArray.leUInt32(offset: Int): UInt {
        val value = (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
        return value.toUInt()
    }

    private fun ByteArray.leInt32(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            (this[offset + 3].toInt() shl 24)
    }

    private fun ByteArray.leFloat(offset: Int): Float {
        return ByteBuffer.wrap(this, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .float
    }

    /**
     * Converts a COMMAND_LONG/COMMAND_INT param float into the exact uint32 custom-mode id it
     * represents, or null when it cannot be a valid id (nonfinite, negative, fractional or
     * outside the uint32 range). Rejecting here prevents silent coercion of values such as
     * 4.9 or NaN into an implemented mode.
     */
    private fun Float.toCustomModeIdOrNull(): UInt? {
        if (!isFinite() || this < 0f) return null
        val asLong = toLong()
        if (asLong > UInt.MAX_VALUE.toLong() || asLong.toFloat() != this) return null
        return asLong.toUInt()
    }

    private companion object {
        const val MAV_RESULT_ACCEPTED = 0
        const val MAV_RESULT_DENIED = 2
        const val MAV_RESULT_UNSUPPORTED = 3
        const val MAV_MISSION_ACCEPTED = 0
        const val MAV_MISSION_ERROR = 1
        const val MAV_MISSION_DENIED = 14
        const val MAV_MISSION_INVALID_SEQUENCE = 13
        const val MAV_MISSION_INVALID = 5
        const val MAV_CMD_NAV_TAKEOFF = 22
        const val MAV_CMD_NAV_LAND = 21
        const val MAV_CMD_MISSION_START = 300
        const val MAV_CMD_COMPONENT_ARM_DISARM = 400
        const val MAV_CMD_DO_SET_MODE = 176
        const val MAV_CMD_PREFLIGHT_CALIBRATION = 241
        const val MAV_CMD_DO_START_MAG_CAL = 424
        const val MAV_CMD_DO_ACCEPT_MAG_CAL = 425
        const val MAV_CMD_DO_CANCEL_MAG_CAL = 426
        const val MAV_CMD_DO_CHANGE_SPEED = 178
        const val MAV_CMD_DO_SET_HOME = 179
        const val MAV_CMD_GET_HOME_POSITION = 410
        const val MAV_CMD_SET_MESSAGE_INTERVAL = 511
        const val MAV_CMD_REQUEST_MESSAGE = 512
        const val MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES = 520
        const val MAVLINK_MSG_ID_AUTOPILOT_VERSION = 148
        const val MAVLINK_MSG_ID_HOME_POSITION = 242
        const val HomePositionBurstInterval = 5
        const val PeerActivityTimeoutMs = 10_000L
        const val TAG = "MavlinkUdpServer"
    }
}
