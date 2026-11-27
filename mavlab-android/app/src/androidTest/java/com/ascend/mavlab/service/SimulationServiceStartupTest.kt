package com.ascend.mavlab.service

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class SimulationServiceStartupTest {
    @Test
    fun visibleUiStartsServiceWithoutForegroundDeadline() {
        var startedIntent: Intent? = null
        val context = object : ContextWrapper(null) {
            override fun getPackageName() = "com.ascend.mavlab"

            override fun startService(service: Intent): ComponentName? {
                startedIntent = service
                return service.component
            }

            override fun startForegroundService(service: Intent): ComponentName? {
                throw AssertionError("Visible UI must not start a foreground-promotion deadline")
            }
        }

        SimulationService.startFromVisibleUi(context)

        val expected = SimulationService.appVisibleIntent(context)
        assertEquals(expected.component, startedIntent?.component)
        assertEquals(expected.action, startedIntent?.action)
    }
}
