package com.ascend.mavlab.simulation.coordinates

import com.ascend.mavlab.simulation.engine.*
import com.ascend.mavlab.simulation.mission.*
import kotlin.test.*

class SimulationCoordinateFrameTest {
    @Test fun independentNorthEastReferencesAndRoundTrip() {
        val f = SimulationCoordinateFrame(60.0, 20.0)
        assertEquals(61.0, f.latitude(111_320.0), 1e-12)
        assertEquals(21.0, f.longitude(55_660.0), 1e-12)
        for (n in listOf(-50_000.0, 0.0, 50_000.0)) {
            assertEquals(n, f.north(f.latitude(n)), 1e-8)
            assertEquals(n, f.east(f.longitude(n)), 1e-8)
        }
        assertFailsWith<IllegalArgumentException> { SimulationCoordinateFrame(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { SimulationCoordinateFrame(0.0, 181.0) }
    }

    @Test fun geographyDoesNotAccumulateGpsNoiseAndMissionTargetsIgnoreIt() {
        val engine = PhysicsSimulationEngine()
        val origin = engine.state.value
        engine.failureInjector.setGpsNoiseMultiplier(8f)
        // On-ground physical position stays zero. Even after many ticks, noise is bounded
        // around the fixed origin instead of being reintegrated into future coordinates.
        repeat(500) {
            engine.tickForTest(0.01f)
            assertEquals(0f, engine.state.value.northMeters)
            assertTrue(kotlin.math.abs(engine.state.value.latitudeDeg - origin.latitudeDeg) < 0.0001)
        }
        engine.loadMission(listOf(MissionItem(0, MissionCommand.WAYPOINT,
            origin.latitudeDeg + 100.0 / 111_320.0, origin.longitudeDeg, 20f)))
        assertEquals(100f, engine.missionProgress.value.items.single().localNorthMeters!!, 0.001f)
        assertEquals(0f, engine.missionProgress.value.items.single().localEastMeters!!, 0.001f)
    }

    @Test fun movingReturnHomeDoesNotChangeSimulationOriginOrMissionGrid() {
        val engine = PhysicsSimulationEngine()
        val origin = engine.state.value
        assertTrue(engine.setHomeToCoordinates(origin.latitudeDeg + 0.001, origin.longitudeDeg))
        engine.loadMission(listOf(MissionItem(0, MissionCommand.WAYPOINT,
            origin.latitudeDeg, origin.longitudeDeg, 10f)))
        assertEquals(0f, engine.missionProgress.value.items.single().localNorthMeters!!, 0.001f)
        engine.tickForTest(0.01f)
        assertEquals(origin.latitudeDeg, engine.state.value.latitudeDeg, 0.0001)
    }
}
