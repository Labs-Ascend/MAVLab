package com.ascend.mavlab.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ascend.mavlab.core.common.AppRuntime
import com.ascend.mavlab.core.ui.navigation.screenSwipeExcluded
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun SafetySection(onConfirm: (OpsConfirmation) -> Unit) {
    val plans by AppRuntime.safetyPlans.collectAsStateWithLifecycle()
    val safetyFlow = remember { AppRuntime.state.map { it.safety to it.armed }.distinctUntilChanged() }
    val safety by safetyFlow.collectAsStateWithLifecycle(initialValue = null)
    var expanded by remember { mutableStateOf(false) }
    val (status, armed) = safety ?: return
    val summary = if (status.recovery != "None") status.recovery else if (status.breached) "Fence breached" else if (plans.config.enabled) "Fence enabled" else "Fence disabled"
    ExpandableSection("Flight boundaries & rally", "$summary · ${plans.rally.size} rally points", expanded, { expanded = it }) {
        Text("${plans.fence.size} fence items · ${plans.rally.size} rally points")
        Text("Breach response: ${when (plans.config.action) { 0 -> "Report only"; 1 -> "RTL, otherwise land"; else -> "Land" }}")
        Text("Breaches: ${status.breachCount} · Return: ${status.returnTarget}")
        Text(plans.lastTransfer, style = MaterialTheme.typography.bodySmall)
        Text("Uploads do not enable the fence. Configure boundaries and the response in QGC while disarmed. RTL uses an eligible rally point or home, then lands and disarms. This is a flat-world simulation, not obstacle-aware routing.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Enable fence")
            Switch(checked = plans.config.enabled, enabled = !armed || plans.config.enabled,
                modifier = Modifier.screenSwipeExcluded(), onCheckedChange = { enabled ->
                    onConfirm(OpsConfirmation(if (enabled) "Enable simulated fence?" else "Disable simulated fence?",
                        if (enabled) "An armed breach will trigger the configured response. Verify the QGC boundaries before flying."
                        else "Disables enforcement. An ongoing RTL or landing continues until you explicitly select another mode or disarm.") { AppRuntime.setFenceEnabled(enabled) })
                })
        }
        OutlinedButton(enabled = !armed && (plans.fence.isNotEmpty() || plans.rally.isNotEmpty()),
            modifier = Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true),
            onClick = { onConfirm(OpsConfirmation("Clear safety plans?", "Removes fence and rally points and disables the fence. The navigation mission remains unchanged.") { AppRuntime.clearSafetyPlans() }) }) {
            Text("Clear fence & rally")
        }
    }
}
