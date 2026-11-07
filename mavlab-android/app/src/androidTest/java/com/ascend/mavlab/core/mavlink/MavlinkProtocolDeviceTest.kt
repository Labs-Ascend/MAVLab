package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.engine.ControlAuthority
import com.ascend.mavlab.simulation.engine.FlightMode
import com.ascend.mavlab.simulation.engine.PhysicsSimulationEngine
import com.ascend.mavlab.simulation.mission.MissionCommand
import com.ascend.mavlab.simulation.mission.MissionItem
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Isolated engine and loopback sockets: never connects to QGC or changes the app runtime. */
class MavlinkProtocolDeviceTest {
    @Test
    fun batchedCompressedUploadAndRepeatedFinalItemLoadEngineOnlyOnce() = withServer { server, engine, peer ->
        var sequence = 0
        val sender = MavlinkMessageBuilder(255, 190)
        inject(server, sender.missionCount(4, 1, 1), peer)
        assertEquals(0, receive(peer, 51).payload[0].toInt())
        val items = (0..3).map {
            MissionItem(it, if (it == 3) MissionCommand.CHANGE_SPEED else MissionCommand.WAYPOINT,
                -1.2921, 36.8219, 10f, autocontinue = false, speedMetersPerSecond = if (it == 3) 100f else null)
        }
        val batch = items.map { sender.missionItemInt(it, 0, 1, 1) }.reduce { a, b -> a + b }
        inject(server, batch, peer)
        repeat(3) { assertEquals(++sequence, receive(peer, 51).payload[0].toInt()) }
        assertEquals(0, receive(peer, 47).payload[2].toInt())
        val mission = engine.missionProgress.value
        assertEquals(4, mission.items.size)
        assertEquals(100f, mission.items.last().speedMetersPerSecond!!, 0f)
        inject(server, sender.missionItemInt(items.last(), 0, 1, 1), peer)
        assertEquals(0, receive(peer, 47).payload[2].toInt())
        assertSame(mission, engine.missionProgress.value) // no second load/reset on a lost final ACK
        assertFalse(engine.state.value.armed)
    }

    @Test
    fun unsupportedMissionTypeCannotClearPreviousMission() = withServer { server, engine, peer ->
        engine.loadMission(listOf(MissionItem(0, MissionCommand.WAYPOINT, -1.2921, 36.8219, 10f)))
        val clearFence = MavlinkWire.frame(45, byteArrayOf(1, 1, 99), 0, 255, 190)
        inject(server, clearFence, peer)
        val ack = receive(peer, 47)
        assertEquals(3, ack.payload[2].toInt())
        assertEquals(99, ack.payload[3].toInt())
        assertEquals(1, engine.missionProgress.value.items.size)
    }

    @Test
    fun exhaustedUploadRetainsPreviouslyLoadedMission() = withServer { server, engine, peer ->
        engine.loadMission(listOf(MissionItem(0, MissionCommand.WAYPOINT, -1.2921, 36.8219, 10f)))
        val field = MavlinkUdpServer::class.java.getDeclaredField("missionUploadSession").apply { isAccessible = true }
        val session = MissionUploadSession(2, UdpDestination("127.0.0.1", peer.localPort), startedAtMs = 0)
        field.set(server, session)
        val retry = MavlinkUdpServer::class.java.getDeclaredMethod("retryMissionUpload").apply { isAccessible = true }
        retry.invoke(server)
        assertEquals(1, receive(peer, 47).payload[2].toInt())
        assertNull(field.get(server))
        assertEquals(1, engine.missionProgress.value.items.size)
    }

    @Test
    fun unsupportedMessageRequestGetsV2AckRatherThanTimeout() = withServer { server, _, peer ->
        val payload = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(435f).put(ByteArray(24)).putShort(512).put(1).put(1).put(0).array()
        inject(server, MavlinkWire.frame(76, payload, 0, 255, 190), peer)
        val ack = receive(peer, 77)
        assertEquals(3, ack.payload[2].toInt())
        assertEquals(255, ack.payload[8].toInt() and 0xff)
        assertEquals(190, ack.payload[9].toInt() and 0xff)
    }

    @Test
    fun setModeRejectsUnknownCustomModeWithoutEngineChange() = withServer { server, engine, peer ->
        val before = engine.state.value
        inject(server, MavlinkWire.frame(11, setModePayload(17u), 0, 255, 190), peer)
        val ack = receive(peer, 77)
        assertEquals(0, ackCommand(ack))
        assertEquals(3, ack.payload[2].toInt()) // MAV_RESULT_UNSUPPORTED
        assertEquals(before.mode, engine.state.value.mode)
        assertEquals(before.controlAuthority, engine.state.value.controlAuthority)
    }

    @Test
    fun setModeStillAcceptsImplementedCustomMode() = withServer { server, engine, peer ->
        inject(server, MavlinkWire.frame(11, setModePayload(5u), 0, 255, 190), peer)
        val ack = receiveType(peer, 77)
        assertEquals(0, ackCommand(ack))
        assertEquals(0, ack.payload[2].toInt()) // MAV_RESULT_ACCEPTED
        assertEquals(FlightMode.LOITER, engine.state.value.mode)
        assertEquals(ControlAuthority.GCS_DIRECT, engine.state.value.controlAuthority)
    }

    @Test
    fun doSetModeRejectsUnknownCustomModeWithoutEngineChange() = withServer { server, engine, peer ->
        val before = engine.state.value
        inject(server, MavlinkWire.frame(76, doSetModeCommand(17f), 0, 255, 190), peer)
        val ack = receive(peer, 77)
        assertEquals(176, ackCommand(ack))
        assertEquals(3, ack.payload[2].toInt()) // MAV_RESULT_UNSUPPORTED
        assertEquals(before.mode, engine.state.value.mode)
        assertEquals(before.controlAuthority, engine.state.value.controlAuthority)
    }

    @Test
    fun doSetModeRejectsInvalidCustomModeValuesWithoutCoercion() = withServer { server, engine, peer ->
        val before = engine.state.value
        for (value in listOf(4.9f, Float.NaN, Float.POSITIVE_INFINITY, -4f, 4_294_967_296f)) {
            inject(server, MavlinkWire.frame(76, doSetModeCommand(value), 0, 255, 190), peer)
            val ack = receive(peer, 77)
            assertEquals(176, ackCommand(ack))
            assertEquals("value=$value", 2, ack.payload[2].toInt()) // MAV_RESULT_DENIED
            assertEquals("value=$value", before.mode, engine.state.value.mode)
            assertEquals("value=$value", before.controlAuthority, engine.state.value.controlAuthority)
        }
    }

    @Test
    fun doSetModeStillAcceptsImplementedCustomMode() = withServer { server, engine, peer ->
        inject(server, MavlinkWire.frame(76, doSetModeCommand(4f), 0, 255, 190), peer)
        val ack = receiveType(peer, 77)
        assertEquals(176, ackCommand(ack))
        assertEquals(0, ack.payload[2].toInt()) // MAV_RESULT_ACCEPTED
        assertEquals(FlightMode.GUIDED, engine.state.value.mode)
        assertEquals(ControlAuthority.GCS_DIRECT, engine.state.value.controlAuthority)
    }

    private fun ackCommand(ack: MavlinkPacket): Int {
        val low = ack.payload[0].toInt() and 0xff
        val high = (ack.payload[1].toInt() and 0xff) shl 8
        return low or high
    }

    private fun receiveType(peer: DatagramSocket, id: Int): MavlinkPacket {
        repeat(64) {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            peer.receive(packet)
            val parsed = requireNotNull(MavlinkParser.parse(packet.data, packet.length))
            if (parsed.messageId == id) return parsed
        }
        throw AssertionError("no message id=$id")
    }

    private fun setModePayload(customMode: UInt): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(customMode.toInt())
            .put(1) // target_system
            .put(0) // base_mode
            .array()

    private fun doSetModeCommand(customMode: Float): ByteArray =
        ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0f) // param1 base_mode
            .putFloat(customMode) // param2 custom mode
            .put(ByteArray(20)) // param3..param7
            .putShort(176) // MAV_CMD_DO_SET_MODE
            .put(1) // target_system
            .put(1) // target_component
            .put(0) // confirmation
            .array()

    private fun withServer(block: (MavlinkUdpServer, PhysicsSimulationEngine, DatagramSocket) -> Unit) {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { socket ->
            DatagramSocket(0, loopback).use { peer ->
                peer.soTimeout = 1500
                val engine = PhysicsSimulationEngine()
                val server = MavlinkUdpServer(engine)
                MavlinkUdpServer::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(server, socket)
                try { block(server, engine, peer) } finally { server.stopNow(); engine.stop() }
            }
        }
    }

    private fun inject(server: MavlinkUdpServer, data: ByteArray, peer: DatagramSocket) {
        MavlinkUdpServer::class.java.getDeclaredMethod("handleInbound", ByteArray::class.java, Int::class.javaPrimitiveType, UdpDestination::class.java)
            .apply { isAccessible = true }
            .invoke(server, data, data.size, UdpDestination("127.0.0.1", peer.localPort))
    }

    private fun receive(peer: DatagramSocket, id: Int): MavlinkPacket {
        val packet = DatagramPacket(ByteArray(2048), 2048)
        peer.receive(packet)
        assertEquals(0xfd, packet.data[0].toInt() and 0xff)
        val parsed = requireNotNull(MavlinkParser.parse(packet.data, packet.length))
        assertEquals(id, parsed.messageId)
        return parsed
    }

    @Test
    fun typedFenceAndRallyRoundTripAndClearNeverEraseNavigationMission() = withServer { server, engine, peer ->
        val sender = MavlinkMessageBuilder(255, 190)
        engine.loadMission(listOf(MissionItem(0, MissionCommand.WAYPOINT, -1.2921, 36.8219, 10f)))
        for ((type, item) in listOf(1 to com.ascend.mavlab.simulation.safety.SafetyItem(0, 5003, 0, -1.2921, 36.8219, param1 = 100f),
            2 to com.ascend.mavlab.simulation.safety.SafetyItem(0, 5100, 3, -1.2921, 36.8219, 5f))) {
            inject(server, sender.missionCount(1, 1, 1, type), peer)
            assertEquals(type, receive(peer, 51).payload[4].toInt())
            inject(server, sender.safetyItem(item, type, 1, 1), peer)
            val ack = receive(peer, 47)
            assertEquals(0, ack.payload[2].toInt()); assertEquals(type, ack.payload[3].toInt())
            inject(server, MavlinkWire.frame(43, byteArrayOf(1, 1, type.toByte()), 0, 255, 190), peer)
            assertEquals(type, receive(peer, 44).payload[4].toInt())
            inject(server, MavlinkWire.frame(51, byteArrayOf(0, 0, 1, 1, type.toByte()), 0, 255, 190), peer)
            assertEquals(type, receive(peer, 73).payload[37].toInt())
        }
        inject(server, MavlinkWire.frame(45, byteArrayOf(1, 1, 1), 0, 255, 190), peer)
        assertEquals(0, receive(peer, 47).payload[2].toInt())
        assertTrue(engine.safetyPlans.value.fence.isEmpty())
        assertEquals(1, engine.safetyPlans.value.rally.size)
        assertEquals(1, engine.missionProgress.value.items.size)
        assertFalse(engine.state.value.armed)
        inject(server, MavlinkWire.frame(45, byteArrayOf(1, 1, 255.toByte()), 0, 255, 190), peer)
        val all = receive(peer, 47)
        assertEquals(0, all.payload[2].toInt()); assertEquals(255, all.payload[3].toInt() and 0xff)
        assertTrue(engine.safetyPlans.value.rally.isEmpty()); assertFalse(engine.missionProgress.value.loaded)
    }

    @Test
    fun isolatedAndroidEngineActuallyReturnsToRallyLandsAndDisarms() {
        val engine = PhysicsSimulationEngine()
        val origin = engine.state.value
        engine.replaceSafetyPlan(2, listOf(com.ascend.mavlab.simulation.safety.SafetyItem(0, 5100, 3, origin.latitudeDeg + 8 / 111_320.0, origin.longitudeDeg, 5f)))
        engine.setSafetyParameter("RALLY_INCL_HOME", 0f)
        engine.takeoff(5f)
        engine.setPilotInput(com.ascend.mavlab.simulation.autopilot.PilotInput(throttle = .5f))
        repeat(750) { engine.tickForTest(.02f) }
        engine.setMode(com.ascend.mavlab.simulation.engine.FlightMode.RTL)
        assertEquals("Rally 1", engine.state.value.safety.returnTarget)
        repeat(3250) { engine.tickForTest(.02f) }
        assertFalse(engine.state.value.armed)
        assertTrue(kotlin.math.abs(engine.state.value.northMeters - 8f) < 3f)
        assertTrue(engine.state.value.altitudeAglMeters < .1f)
    }

    @Test
    fun isolatedAndroidFenceBreachReturnsAndFinishesDisarmed() {
        val engine = PhysicsSimulationEngine()
        engine.setSafetyParameter("FENCE_TYPE", 2f)
        engine.setSafetyParameter("FENCE_RADIUS", 10f)
        engine.setSafetyParameter("RTL_ALT", 500f)
        engine.setSafetyParameter("FENCE_ENABLE", 1f)
        engine.takeoff(5f)
        engine.setPilotInput(com.ascend.mavlab.simulation.autopilot.PilotInput(throttle = .5f))
        repeat(750) { engine.tickForTest(.02f) }
        engine.setGuidedOffset(30f, 0f, 5f)
        repeat(3250) { engine.tickForTest(.02f) }
        assertFalse(engine.state.value.armed)
        assertEquals(1, engine.state.value.safety.breachCount)
        assertFalse(engine.state.value.safety.breached)
        assertTrue(kotlin.math.abs(engine.state.value.northMeters) < 3f)
    }
}
