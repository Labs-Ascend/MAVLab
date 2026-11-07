package com.ascend.mavlab.simulation.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Lookup-level coverage only. The UDP-boundary effect of rejecting these ids is
 * exercised by MavlinkProtocolDeviceTest on a device.
 */
class FlightModeAdmissionTest {
    @Test
    fun mapsOnlyImplementedCustomModeIds() {
        val implemented = mapOf(
            0u to FlightMode.STABILIZE,
            2u to FlightMode.ALT_HOLD,
            3u to FlightMode.AUTO,
            4u to FlightMode.GUIDED,
            5u to FlightMode.LOITER,
            6u to FlightMode.RTL,
            9u to FlightMode.LAND,
            17u to FlightMode.BRAKE,
        )
        implemented.forEach { (customMode, mode) ->
            assertEquals(mode, FlightMode.fromCustomModeOrNull(customMode))
        }
    }

    @Test
    fun unknownIdsReturnNullInsteadOfFallingBack() {
        listOf(1u, 7u, 8u, 99u, 216u, UInt.MAX_VALUE).forEach { customMode ->
            assertNull(FlightMode.fromCustomModeOrNull(customMode))
        }
    }
}