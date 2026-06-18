package com.ascend.mavlab.simulation.failures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureScenarioTest {
    @Test
    fun phaseSixPresetsArePresentAndTeachOperatorResponse() {
        val requiredIds = setOf(
            "gps_loss",
            "battery_low",
            "battery_critical",
            "compass_interference",
            "windy_day",
            "motor_failure",
            "lost_link",
            "heavy_payload",
            "barometer_issue",
            "unsafe_mission_reserve",
        )

        assertTrue(FailureScenarios.map { it.id }.containsAll(requiredIds))
        FailureScenarios.forEach { scenario ->
            assertTrue(scenario.title.isNotBlank(), scenario.id)
            assertTrue(scenario.whatHappened.isNotBlank(), scenario.id)
            assertTrue(scenario.whyItHappened.isNotBlank(), scenario.id)
            assertTrue(scenario.telemetrySignature.isNotEmpty(), scenario.id)
            assertTrue(scenario.operatorResponse.isNotEmpty(), scenario.id)
            assertTrue(scenario.safetyLesson.isNotBlank(), scenario.id)
            assertTrue(scenario.recoveryCondition.isNotBlank(), scenario.id)
            assertTrue(scenario.affectedState.isNotBlank(), scenario.id)
            assertTrue(scenario.logEventName.startsWith("failure_"), scenario.id)
        }
    }

    @Test
    fun scenarioInjectionChangesExpectedSimulationState() {
        val injector = FailureInjector()

        scenario("gps_loss").apply(injector)
        assertFalse(injector.state.value.gpsEnabled)

        scenario("lost_link").apply(injector)
        assertTrue(injector.state.value.lostLinkActive)

        scenario("barometer_issue").apply(injector)
        assertEquals(3f, injector.state.value.barometerOffsetMeters)

        scenario("unsafe_mission_reserve").apply(injector)
        assertTrue(injector.state.value.unsafeMissionReserveActive)
        assertEquals(4f, injector.state.value.batteryDrainMultiplier)
    }

    @Test
    fun resetClearsActiveFailures() {
        val injector = FailureInjector()

        scenario("gps_loss").apply(injector)
        scenario("windy_day").apply(injector)
        scenario("unsafe_mission_reserve").apply(injector)

        assertTrue(injector.state.value.activeCount > 0)

        injector.resetAll()

        assertEquals(FailureState(), injector.state.value)
        assertEquals(0, injector.state.value.activeCount)
    }

    @Test
    fun clearScenarioRestoresState() {
        val injector = FailureInjector()

        val gpsLoss = scenario("gps_loss")
        gpsLoss.apply(injector)
        assertFalse(injector.state.value.gpsEnabled)

        injector.clearScenario(gpsLoss)
        assertTrue(injector.state.value.gpsEnabled)

        val windy = scenario("windy_day")
        windy.apply(injector)
        assertEquals(8f, injector.state.value.windSpeedMs)

        injector.clearScenario(windy)
        assertEquals(0f, injector.state.value.windSpeedMs)
    }

    private fun scenario(id: String): FailureScenario {
        return FailureScenarios.first { it.id == id }
    }
}
