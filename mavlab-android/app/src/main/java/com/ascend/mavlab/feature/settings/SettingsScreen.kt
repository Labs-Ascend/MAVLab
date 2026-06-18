package com.ascend.mavlab.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ascend.mavlab.core.common.AppRuntime
import com.ascend.mavlab.core.mavlink.MavlinkIdentityStatus
import com.ascend.mavlab.simulation.failures.FailureScenario
import com.ascend.mavlab.simulation.failures.FailureScenarios
import com.ascend.mavlab.simulation.failures.FailureSeverity
import com.ascend.mavlab.simulation.failures.FailureState
import com.ascend.mavlab.simulation.recording.FlightRecordingStatus
import com.ascend.mavlab.simulation.recording.FlightSession
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onReplayOnboarding: () -> Unit = {},
) {
    val state by AppRuntime.state.collectAsState()
    val mission by AppRuntime.missionProgress.collectAsState()
    val uploadStatus by AppRuntime.missionUploadStatus.collectAsState()
    val recording by AppRuntime.recordingStatus.collectAsState()
    val failures by AppRuntime.failures.collectAsState()
    val status by AppRuntime.status.collectAsState()
    val systemId by AppRuntime.systemId.collectAsState()
    val identityStatus by AppRuntime.mavlinkIdentityStatus.collectAsState()
    val gcsConnected = identityStatus.gcsConnected

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ops", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Diagnostics, logs, QGC readiness, and export staging for MAVLab operations.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            InfoCard(
                title = "About MAVLab",
                body = "MAVLab by Ascend Labs is a phone-based drone digital twin and training platform. Cockpit is live operations, Controller is local/manual control, Mission is autonomous route execution, SIM is physical behavior visualization, and Ops is diagnostics, logs, export, and setup.",
            )
            Button(
                onClick = onReplayOnboarding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Replay onboarding")
            }
            GcsConnectionIndicator(
                connected = gcsConnected,
                identityStatus = identityStatus,
            )
            InfoCard(
                title = "MAVLink",
                body = buildString {
                    append("Status: $status")
                    append("\nVehicle SYSID: $systemId")
                    append("\nVehicle COMPID: ${identityStatus.vehicleComponentId}")
                    append("\nExpected QGC SYSID: ${identityStatus.recommendedGcsSystemId}")
                    append("\nLast GCS SYSID: ${identityStatus.lastGcsSystemId ?: "none"}")
                    append("\nLast GCS COMPID: ${identityStatus.lastGcsComponentId ?: "none"}")
                    append("\nGCS link: ${if (gcsConnected) "connected" else "disconnected"}")
                    append("\nIdentity: ${identityStatus.healthLabel}")
                    if (identityStatus.message.isNotBlank()) {
                        append("\n${identityStatus.message}")
                    }
                },
            )
            InfoCard(
                title = "GCS diagnostics",
                body = "Last inbound: ${state.lastInboundMessage}\nLast ACK: ${state.lastAck}\n${uploadStatus.displayText}\nMission: ${mission.items.size} items, active ${mission.activeTarget?.sequence?.plus(1) ?: "none"}",
            )
            FailureOpsCatalog(failures = failures)
            FlightLogSection(recording = recording)
            InfoCard(
                title = "Troubleshooting",
                body = "Recommended QGC setup: set QGC MAVLink System ID to 255, keep MAVLab Vehicle SYSID at 1, keep QGC UDP on 14550, and restart QGC or reconnect the UDP link after changing System ID. Do not set QGC to the same system ID as MAVLab.",
            )
            InfoCard(
                title = "Release QA",
                body = "Run onboarding, QGC discovery, mission upload, demo mission, GPS loss, and QGC split-screen before tagging a release.",
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FailureOpsCatalog(failures: FailureState) {
    var catalogExpanded by remember { mutableStateOf(false) }
    val activeScenarioCount = FailureScenarios.count { isScenarioActive(it, failures) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header row with title, active count, and toggle button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Failure scenario catalog", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${failures.activeCount} active failures",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        )
                        if (activeScenarioCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "$activeScenarioCount scenarios",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { catalogExpanded = !catalogExpanded }) {
                    Icon(
                        imageVector = if (catalogExpanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (catalogExpanded) "Collapse" else "Expand",
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (catalogExpanded) "Hide" else "Show")
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = AppRuntime::resetFailures) {
                    Text("Reset failures")
                }
                Button(onClick = AppRuntime::resetBattery) {
                    Text("Reset battery")
                }
            }

            AnimatedVisibility(
                visible = catalogExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FailureScenarios.forEach { scenario ->
                        FailureScenarioOpsCard(
                            scenario = scenario,
                            isActive = isScenarioActive(scenario, failures),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FailureScenarioOpsCard(scenario: FailureScenario, isActive: Boolean) {
    val containerColor = if (isActive) {
        when (scenario.severity) {
            FailureSeverity.Critical -> MaterialTheme.colorScheme.errorContainer
            FailureSeverity.Warning -> MaterialTheme.colorScheme.tertiaryContainer
            FailureSeverity.Caution -> MaterialTheme.colorScheme.secondaryContainer
        }
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(scenario.title, style = MaterialTheme.typography.titleSmall)
                        if (isActive) {
                            Text(
                                text = "● ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Text(
                        scenario.severity.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isActive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (isActive) {
                    OutlinedButton(onClick = { AppRuntime.clearFailureScenario(scenario) }) {
                        Text("Clear")
                    }
                } else {
                    Button(onClick = { AppRuntime.applyFailureScenario(scenario) }) {
                        Text("Inject")
                    }
                }
            }
            Text(scenario.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = buildString {
                    append("Why: ${scenario.whyItHappened}")
                    append("\nTelemetry: ${scenario.telemetrySignature.joinToString("; ")}")
                    append("\nOperator: ${scenario.operatorResponse.joinToString("; ")}")
                    append("\nRecovery: ${scenario.recoveryCondition}")
                    append("\nLesson: ${scenario.safetyLesson}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GcsConnectionIndicator(
    connected: Boolean,
    identityStatus: MavlinkIdentityStatus,
) {
    val indicatorColor = if (connected) ConnectedGreen else DisconnectedRed
    val indicatorLabel = if (connected) "GCS connected" else "GCS disconnected"
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
                    .semantics { contentDescription = indicatorLabel },
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "GCS link",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = gcsDetail(identityStatus, connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun FlightLogSection(recording: FlightRecordingStatus) {
    val context = LocalContext.current
    var historyExpanded by remember { mutableStateOf(false) }
    var selectedReportSession by remember { mutableStateOf<FlightSession?>(null) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header row with title and expand/collapse
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Flight logs", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (recording.active) "Recording: Active" else "Recording: Idle",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { AppRuntime.refreshSessionHistory() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh session history",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    OutlinedButton(onClick = { historyExpanded = !historyExpanded }) {
                        Icon(
                            imageVector = if (historyExpanded) Icons.Filled.KeyboardArrowUp
                            else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (historyExpanded) "Collapse" else "Expand",
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (historyExpanded) "Hide" else "History")
                    }
                }
            }

            // Current session display (primary)
            val currentSession = recording.currentSession ?: recording.lastSession
            if (currentSession != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (recording.active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (recording.active) "ACTIVE RECORDING" else "MOST RECENT SESSION",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (recording.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.semantics { contentDescription = "Session status header" }
                    )
                    Text("ID: ${currentSession.id}", style = MaterialTheme.typography.bodyMedium)
                    Text("Started: ${formatTimestamp(currentSession.startedAtMs)}", style = MaterialTheme.typography.bodySmall)
                    if (currentSession.endedAtMs != null) {
                        Text("Duration: ${formatDuration(currentSession.endedAtMs - currentSession.startedAtMs)}", style = MaterialTheme.typography.bodySmall)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentSession.hasReport) {
                            TextButton(onClick = { selectedReportSession = currentSession }) {
                                Text("View Report")
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = { AppRuntime.shareSession(context, currentSession.id) }) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "Share session data",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "No flight logs yet. Arm the vehicle or run an autonomous mission to start recording.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Collapsible Session History
            AnimatedVisibility(
                visible = historyExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Text(
                        text = "Session History (${recording.sessionHistory.size} total)",
                        style = MaterialTheme.typography.titleSmall
                    )

                    val history = recording.sessionHistory
                    if (history.isEmpty()) {
                        Text(
                            text = "No saved session folders found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            history.forEach { session ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                            shape = MaterialTheme.shapes.extraSmall
                                        )
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(session.id, style = MaterialTheme.typography.bodyMedium)
                                        val durationText = if (session.endedAtMs != null) {
                                            "Duration: ${formatDuration(session.endedAtMs - session.startedAtMs)}"
                                        } else {
                                            "Active / Unfinished"
                                        }
                                        Text(durationText, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (session.hasReport) {
                                            TextButton(onClick = { selectedReportSession = session }) {
                                                Text("Report")
                                            }
                                        }
                                        IconButton(onClick = { AppRuntime.shareSession(context, session.id) }) {
                                            Icon(
                                                imageVector = Icons.Filled.Share,
                                                contentDescription = "Share flight session",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Report Dialog
    selectedReportSession?.let { session ->
        val reportFile = java.io.File(session.reportPath)
        val reportText = if (reportFile.isFile) {
            try {
                reportFile.readText()
            } catch (e: Exception) {
                "Error reading report: ${e.localizedMessage}"
            }
        } else {
            "No report found for this session."
        }

        AlertDialog(
            onDismissRequest = { selectedReportSession = null },
            title = {
                Text(text = "Flight Report: ${session.id}", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = reportText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedReportSession = null }) {
                    Text("Close")
                }
            }
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun gcsDetail(identityStatus: MavlinkIdentityStatus, connected: Boolean): String {
    val systemId = identityStatus.lastGcsSystemId
        ?: return "Disconnected - waiting for MAVLink from QGC or another GCS"
    val componentId = identityStatus.lastGcsComponentId ?: "unknown"
    val status = if (connected) "Connected" else "Disconnected - no recent GCS packets"
    return "$status - last GCS SYSID $systemId, COMPID $componentId"
}

private fun formatTimestamp(timestampMs: Long): String {
    return OpsTimeFormatter.format(Instant.ofEpochMilli(timestampMs))
}

@Composable
private fun InfoCard(title: String, body: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
            )
        }
    }
}

private val OpsTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
    .withZone(ZoneOffset.UTC)

private val ConnectedGreen = Color(0xFF1B8F3A)
private val DisconnectedRed = Color(0xFFD12F2F)

private fun isScenarioActive(scenario: FailureScenario, state: FailureState): Boolean {
    return when (scenario.id) {
        "gps_loss" -> !state.gpsEnabled
        "gps_drift" -> state.gpsNoiseMultiplier > 1.05f
        "windy_day" -> state.windSpeedMs > 0.05f
        "motor_failure" -> state.hasMotorFailure
        "battery_low" -> state.batteryDrainMultiplier in 6f..10f
        "battery_critical" -> state.batteryDrainMultiplier > 10f
        "compass_interference" -> kotlin.math.abs(state.compassOffsetDeg) > 0.5f
        "heavy_payload" -> state.payloadMassKg > 0.05f
        "lost_link" -> state.lostLinkActive
        "barometer_issue" -> kotlin.math.abs(state.barometerOffsetMeters) > 0.05f
        "unsafe_mission_reserve" -> state.unsafeMissionReserveActive
        else -> false
    }
}

