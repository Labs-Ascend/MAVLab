package com.ascend.mavlab.feature.mission

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ascend.mavlab.core.common.AppRuntime
import com.ascend.mavlab.simulation.mission.MissionProgress

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MissionControlRow(mission: MissionProgress, ui: MissionUiState, expanded: Boolean, onToggle: () -> Unit, onOpenSim: () -> Unit, onOpenOps: () -> Unit) {
    if (!ui.visible) return
    var confirmation by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text("${if (mission.received) "GCS Mission" else "Mission"}  ·  ${ui.summary}", Modifier.weight(1f))
                Text(if (expanded) "⌃" else "⌄")
            }
            if (expanded) {
                Text(ui.phase.label, style = MaterialTheme.typography.titleSmall)
                mission.cameraHoldReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (mission.items.any { it.command == com.ascend.mavlab.simulation.mission.MissionCommand.CAMERA_TRIGGER_DISTANCE }) Text("Photos saved · ${mission.cameraSavedImages} · failed ${mission.cameraFailedImages}", style = MaterialTheme.typography.bodySmall)
                Text("Source · ${if (mission.received) "GCS upload (QGC workflow)" else "Unverified"}", style = MaterialTheme.typography.bodySmall)
                if (mission.received) {
                    Text("Route progress · ${missionProgressPercent(mission)}%", style = MaterialTheme.typography.bodyMedium)
                    if (!mission.complete) {
                        val current = mission.items.getOrNull(mission.currentIndex)
                        val next = mission.items.getOrNull(mission.currentIndex + 1)
                        Text("Current · ${current?.let { "${it.sequence + 1} ${it.command}" } ?: "None"}\nNext · ${next?.let { "${it.sequence + 1} ${it.command}" } ?: "None"}", style = MaterialTheme.typography.bodySmall)
                    }
                    ui.remainingMeters?.let { Text("Remaining horizontal route · %.1f m".format(it), style = MaterialTheme.typography.bodySmall) }
                    ui.horizontalEtaSeconds?.let { Text("Horizontal ETA estimate · ${it}s at current ground speed", style = MaterialTheme.typography.bodySmall) }
                    if (missionLimitations(mission).isNotEmpty()) Text("Some items have partial behavior · details in Ops", style = MaterialTheme.typography.bodySmall)
                }
                ui.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.policy.canStart) Button(onClick = { confirmation = "start" }) { Text(if (mission.executionStarted) "Resume AUTO" else "Start AUTO") }
                    if (mission.received) OutlinedButton(onClick = onOpenSim) { Text("View route in SIM") }
                    if (ui.policy.canClear) OutlinedButton(onClick = { confirmation = "clear" }) { Text("Clear mission") }
                    TextButton(onClick = onOpenOps) { Text("Upload details in Ops") }
                }
            }
        }
    }
    confirmation?.let { action ->
        val start = action == "start"
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (start) "Start AUTO?" else "Clear received mission?") },
            text = { Text(if (start) "This arms the simulated vehicle and gives GCS Mission control. Local inputs pause. AUTO continues from the current item. Use Take control & land to stop autonomous flight." else "This removes the stored mission. Upload again from QGC to restore it.") },
            confirmButton = { TextButton(onClick = {
                val accepted = if (start) AppRuntime.startAutoMission() else AppRuntime.clearMission()
                feedback = if (accepted) null else "State changed · action blocked; check Ops"
                confirmation = null
            }, enabled = if (start) ui.policy.canStart else ui.policy.canClear) { Text(if (start) "Start AUTO" else "Clear mission") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}
