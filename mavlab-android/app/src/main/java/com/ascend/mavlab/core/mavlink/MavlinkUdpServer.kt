package com.ascend.mavlab.core.mavlink

import android.content.Context
import android.util.Log
import com.ascend.mavlab.simulation.engine.ControlAuthority
import com.ascend.mavlab.simulation.engine.FlightMode
import com.ascend.mavlab.simulation.engine.PhysicsSimulationEngine
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
) : MavlinkEndpoint {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vehicleSystemId = config.systemId
    private val builder = MavlinkMessageBuilder(vehicleSystemId, config.componentId)
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
    private var lastPeer: UdpDestination? = null
    private var lastReachedSequenceSent: Int? = null
    private var missionUploadSession: MissionUploadSession? = null

    fun start(context: Context) {
        if (socket != null) return
        val openedSocket = openSocket()
        openedSocket.broadcast = true
        openedSocket.soTimeout = 1000
        socket = openedSocket
        mutableStatus.value = "Running UDP ${openedSocket.localPort} -> QGC ${config.sameDeviceQgcPort}"

        val destinations = destinations(context)
        Log.i(
            TAG,
            "MAVLink started sys=$vehicleSystemId comp=${config.componentId} " +
                "local=${openedSocket.localPort} destinations=${destinations.joinToString()}",
        )
        telemetryJob = scope.launch { telemetryLoop(destinations) }
        receiveJob = scope.launch { receiveLoop() }
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
        socket?.close()
        socket = null
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
        val messages = buildList {
            add(
                builder.heartbeat(state),
            )
            add(builder.attitude(state))
            add(builder.globalPosition(state))
            add(builder.gpsRaw(state))
            add(builder.vfrHud(state))
            add(builder.sysStatus(state))
            add(builder.batteryStatus(state))
            if (missionProgress.loaded) {
                add(builder.missionCurrent(missionProgress.currentIndex.coerceAtMost(missionProgress.items.lastIndex.coerceAtLeast(0))))
                val reached = missionProgress.lastReachedSequence
                if (reached != null && reached != lastReachedSequenceSent) {
                    add(builder.missionItemReached(reached))
                    lastReachedSequenceSent = reached
                }
            }
        }
        messages.forEach { data -> sendToDestinations(data, destinations) }
    }

    private fun sendToDestinations(data: ByteArray, destinations: List<UdpDestination>) {
        val dynamicDestinations = buildList {
            addAll(destinations)
            lastPeer?.let { add(it) }
        }.distinct()

        dynamicDestinations.forEach { destination ->
            send(data, destination)
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
        val buffer = ByteArray(512)
        while (scope.coroutineContext.isActive) {
            val currentSocket = socket ?: return
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                currentSocket.receive(packet)
                val host = packet.address.hostAddress ?: return
                lastPeer = UdpDestination(host = host, port = packet.port)
                handleInbound(packet.data.copyOf(packet.length), packet.length, lastPeer)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: Exception) {
                if (scope.coroutineContext.isActive) {
                    mutableStatus.value = "Receive failed: ${error.message}"
                }
            }
        }
    }

    private fun handleInbound(data: ByteArray, length: Int, peer: UdpDestination?) {
        val packet = MavlinkParser.parse(data, length) ?: return
        logInbound(packet, peer, length, "received")
        when (packet.messageId) {
            0 -> handleHeartbeat(packet, peer, length)
            11 -> handleSetMode(packet, peer)
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
            76 -> handleCommandLong(packet, peer)
            else -> logInbound(packet, peer, length, "unsupported")
        }
    }

    private fun handleHeartbeat(packet: MavlinkPacket, peer: UdpDestination?, length: Int) {
        val collision = packet.systemId == vehicleSystemId
        val result = if (collision) {
            "gcs-heartbeat SYSID_COLLISION"
        } else {
            "gcs-heartbeat"
        }
        logInbound(packet, peer, length, result)
        if (collision) {
            val message = "QGC/GCS is using MAVLab vehicle SYSID $vehicleSystemId. Set QGC MAVLink System ID to $DefaultQgcSystemId."
            mutableIdentityStatus.value = mutableIdentityStatus.value.copy(
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
            mutableIdentityStatus.value = mutableIdentityStatus.value.copy(
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
        val mode = FlightMode.fromCustomMode(customMode)
        simLoop.setMode(mode, ControlAuthority.GCS_DIRECT)
        simLoop.noteInbound("SET_MODE ${mode.displayName}")
        ack(0, MAV_RESULT_ACCEPTED, peer, "SET_MODE")
    }

    private fun handleCommandLong(packet: MavlinkPacket, peer: UdpDestination?) {
        if (packet.payload.size < 33) return
        if (!isTargetedToVehicle(packet, targetSystemOffset = 30, label = "COMMAND_LONG")) return
        val command = packet.payload.leUInt16(28)
        simLoop.noteInbound("COMMAND_LONG $command")
        when (command) {
            MAV_CMD_COMPONENT_ARM_DISARM -> {
                val arm = packet.payload.leFloat(0) >= 0.5f
                simLoop.setArmed(arm, ControlAuthority.GCS_DIRECT)
                ack(command, MAV_RESULT_ACCEPTED, peer, if (arm) "ARM" else "DISARM")
            }
            MAV_CMD_NAV_TAKEOFF -> {
                val requestedAltitude = packet.payload.leFloat(24)
                    .takeIf { it.isFinite() && it > 0f }
                    ?: 10f
                simLoop.takeoff(requestedAltitude, ControlAuthority.GCS_DIRECT)
                ack(command, MAV_RESULT_ACCEPTED, peer, "TAKEOFF")
            }
            MAV_CMD_NAV_LAND -> {
                simLoop.land(ControlAuthority.GCS_DIRECT)
                ack(command, MAV_RESULT_ACCEPTED, peer, "LAND")
            }
            MAV_CMD_SET_MESSAGE_INTERVAL -> {
                ack(command, MAV_RESULT_ACCEPTED, peer, "SET_MESSAGE_INTERVAL")
            }
            MAV_CMD_REQUEST_MESSAGE -> {
                handleRequestMessage(packet, peer, command)
            }
            MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES -> {
                sendAutopilotVersion(peer)
                ack(command, MAV_RESULT_ACCEPTED, peer, "REQUEST_AUTOPILOT_CAPABILITIES")
            }
            MAV_CMD_MISSION_START -> {
                val accepted = simLoop.missionProgress.value.loaded
                if (accepted) {
                    simLoop.setArmed(true, ControlAuthority.GCS_MISSION)
                    simLoop.setMode(FlightMode.AUTO, ControlAuthority.GCS_MISSION)
                }
                ack(
                    command,
                    if (accepted) MAV_RESULT_ACCEPTED else MAV_RESULT_DENIED,
                    peer,
                    if (accepted) "MISSION_START" else "MISSION_START NO MISSION",
                )
            }
            else -> {
                ack(command, MAV_RESULT_UNSUPPORTED, peer, "UNSUPPORTED $command")
            }
        }
    }

    private fun handleRequestMessage(packet: MavlinkPacket, peer: UdpDestination?, command: Int) {
        val requestedMessageId = packet.payload.leFloat(0).toInt()
        when (requestedMessageId) {
            MAVLINK_MSG_ID_AUTOPILOT_VERSION -> {
                sendAutopilotVersion(peer)
                ack(command, MAV_RESULT_ACCEPTED, peer, "REQUEST_MESSAGE AUTOPILOT_VERSION")
            }
            else -> {
                ack(command, MAV_RESULT_UNSUPPORTED, peer, "REQUEST_MESSAGE unsupported $requestedMessageId")
            }
        }
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
        ack(0, MAV_RESULT_ACCEPTED, peer, "PARAM_SET")
    }

    private fun sendParams(packet: MavlinkPacket, peer: UdpDestination?, length: Int) {
        if (!isTargetedToVehicle(packet, targetSystemOffset = 0, label = "PARAM_REQUEST_LIST")) {
            logInbound(packet, peer, length, "ignored target-mismatch")
            return
        }
        simLoop.noteInbound("PARAM_REQUEST_LIST")
        val destination = peer ?: lastPeer ?: return
        val params = listOf(
            "SYSID_THISMAV" to vehicleSystemId.toFloat(),
            "MAV_SYSID" to vehicleSystemId.toFloat(),
            "SYSID_MYGCS" to DefaultQgcSystemId.toFloat(),
            "MAV_GCS_SYSID" to DefaultQgcSystemId.toFloat(),
            "MAV_TYPE" to 2f,
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
            "INS_ACCOFFS_X" to 0f,
            "INS_ACCOFFS_Y" to 0f,
            "INS_ACCOFFS_Z" to 0f,
            "FLTMODE1" to 0f,
            "FLTMODE2" to 0f,
            "FLTMODE3" to 0f,
            "FLTMODE4" to 0f,
            "FLTMODE5" to 0f,
            "FLTMODE6" to 0f,
            "COMPASS_OFS_X" to 0f,
            "COMPASS_OFS_Y" to 0f,
            "COMPASS_OFS_Z" to 0f,
            "COMPASS_OFS2_X" to 0f,
            "COMPASS_OFS2_Y" to 0f,
            "COMPASS_OFS2_Z" to 0f,
            "COMPASS_OFS3_X" to 0f,
            "COMPASS_OFS3_Y" to 0f,
            "COMPASS_OFS3_Z" to 0f,
            "COMPASS_DEC" to 0f,
            "BATT_MONITOR" to 0f,
            "ARMING_CHECK" to 0f,
        )
        params.forEachIndexed { index, param ->
            send(builder.paramValue(param.first, param.second, index, params.size), destination)
        }
    }

    private fun sendMissionCount(packet: MavlinkPacket, peer: UdpDestination?) {
        if (!isTargetedToVehicle(packet, targetSystemOffset = 0, label = "MISSION_REQUEST_LIST")) return
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
            simLoop.clearMission()
            simLoop.acceptMissionUpload(0)
            sendMissionAck(MAV_MISSION_ACCEPTED, packet, destination, "MISSION_CLEAR_EMPTY")
            logInbound(packet, peer, length, "accepted clear-empty")
            return
        }
        missionUploadSession = session
        simLoop.beginMissionUpload(expectedCount = session.expectedCount, requestedSequence = 0)
        send(
            builder.missionRequestInt(
                sequence = 0,
                targetSystem = packet.systemId,
                targetComponent = packet.componentId,
            ),
            destination,
        )
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
        val session = missionUploadSession
        if (session == null) {
            simLoop.rejectMissionUpload("no active upload session")
            sendMissionAck(MAV_MISSION_ERROR, packet, destination, "MISSION_ITEM no session")
            logInbound(packet, peer, length, "rejected no-session")
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
        if (item == null) {
            missionUploadSession = null
            simLoop.rejectMissionUpload("invalid mission item", expectedCount = session.expectedCount, receivedCount = session.receivedCount)
            sendMissionAck(MAV_MISSION_INVALID, packet, destination, "MISSION_ITEM invalid")
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
                val request = if (legacy) {
                    builder.missionRequest(result.sequence, packet.systemId, packet.componentId)
                } else {
                    builder.missionRequestInt(result.sequence, packet.systemId, packet.componentId)
                }
                send(request, destination)
                logInbound(packet, peer, length, "accepted item=${item.sequence} request=${result.sequence}")
            }
            is MissionUploadResult.Complete -> {
                missionUploadSession = null
                simLoop.loadMission(result.items)
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
        missionUploadSession = null
        simLoop.clearMission()
        lastReachedSequenceSent = null
        sendMissionAck(MAV_MISSION_ACCEPTED, packet, destination, "MISSION_CLEAR_ALL")
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

    private fun sendMissionAck(type: Int, packet: MavlinkPacket, destination: UdpDestination, label: String) {
        simLoop.noteAck(label)
        send(builder.missionAck(type, packet.systemId, packet.componentId), destination)
    }

    private fun ack(command: Int, result: Int, peer: UdpDestination?, label: String) {
        simLoop.noteAck(label)
        val destination = peer ?: lastPeer ?: return
        send(builder.commandAck(command, result), destination)
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
        val matches = targetSystem == 0 || targetSystem == vehicleSystemId
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

    private fun ByteArray.leUInt32(offset: Int): UInt {
        val value = (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
        return value.toUInt()
    }

    private fun ByteArray.leFloat(offset: Int): Float {
        return ByteBuffer.wrap(this, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .float
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
        const val MAV_CMD_SET_MESSAGE_INTERVAL = 511
        const val MAV_CMD_REQUEST_MESSAGE = 512
        const val MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES = 520
        const val MAVLINK_MSG_ID_AUTOPILOT_VERSION = 148
        const val TAG = "MavlinkUdpServer"
    }
}
