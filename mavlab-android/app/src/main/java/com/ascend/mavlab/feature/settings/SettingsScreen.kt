package com.ascend.mavlab.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ascend.mavlab.core.common.AppRuntime
import com.ascend.mavlab.core.ui.navigation.screenSwipeArea
import com.ascend.mavlab.core.ui.navigation.screenSwipeExcluded
import com.ascend.mavlab.core.common.RuntimeLifecycleState
import com.ascend.mavlab.core.operations.*
import com.ascend.mavlab.feature.mission.missionLimitations
import com.ascend.mavlab.service.SimulationService
import com.ascend.mavlab.simulation.failures.FailureScenario
import com.ascend.mavlab.simulation.failures.FailureScenarios
import com.ascend.mavlab.simulation.mission.MissionUploadPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onReplayOnboarding: () -> Unit = {},
    onOpenController: () -> Unit = {},
    fullScreen: Boolean = false,
    onFullScreenChange: (Boolean) -> Unit = {},
) {
    val operations by AppRuntime.operations.collectAsStateWithLifecycle()
    var missionExpanded by rememberSaveable { mutableStateOf(false) }
    var setupExpanded by rememberSaveable { mutableStateOf(false) }
    var recordingExpanded by rememberSaveable { mutableStateOf(true) }
    var confirmation by remember { mutableStateOf<OpsConfirmation?>(null) }
    val context = LocalContext.current
    val missionAnchor = remember { BringIntoViewRequester() }
    val setupAnchor = remember { BringIntoViewRequester() }
    val recordingAnchor = remember { BringIntoViewRequester() }
    var reveal by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reveal) {
        val target = reveal ?: return@LaunchedEffect
        withFrameNanos { }
        when (target) {
            "mission" -> missionAnchor.bringIntoView()
            "setup" -> setupAnchor.bringIntoView()
            "recording" -> recordingAnchor.bringIntoView()
        }
        reveal = null
    }

    fun recover(incident: OpsIncident) {
        when (incident.recovery) {
            OpsRecovery.CONTROL -> if (incident.id == "recording") { recordingExpanded = true; reveal = "recording" } else onOpenController()
            OpsRecovery.RESET_FAILURES -> confirmation = OpsConfirmation("Reset simulated failures?",
                "Clears all injected faults and restores normal environment settings. It does not recharge the battery, stop flight, or restore GCS connectivity.", AppRuntime::resetFailures)
            OpsRecovery.RESET_BATTERY -> confirmation = OpsConfirmation("Recharge simulated battery?",
                "Restores the battery to 100% while disarmed. Elevated drain remains active until its scenario is reset.") {
                if (!AppRuntime.state.value.armed) AppRuntime.resetBattery()
            }
            OpsRecovery.QGC_SETUP -> { setupExpanded = true; reveal = "setup" }
            OpsRecovery.REUPLOAD -> { missionExpanded = true; reveal = "mission" }
            OpsRecovery.RESTART -> ContextCompat.startForegroundService(context, SimulationService.appVisibleIntent(context))
        }
    }

    Surface(modifier = modifier.fillMaxSize().screenSwipeArea()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().screenSwipeArea(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ops", style = MaterialTheme.typography.headlineMedium)
                OpsGcsStatus()
            }
            HealthSummary(operations)
            if (operations.incidents.isNotEmpty()) {
                Text("Needs attention · ${operations.incidents.size}", style = MaterialTheme.typography.titleMedium)
                operations.incidents.forEach { incident -> IncidentCard(incident) { recover(incident) } }
            }
            PeerSection()
            SafetySection(onConfirm = { confirmation = it })
            MissionDiagnostics(expanded = missionExpanded, onExpanded = { missionExpanded = it }, headerModifier = Modifier.bringIntoViewRequester(missionAnchor))
            RuntimeSection(operations, onConfirm = { confirmation = it })
            FailureSection(onConfirm = { confirmation = it })
            RecordingSection(expanded = recordingExpanded, onExpanded = { recordingExpanded = it }, headerModifier = Modifier.bringIntoViewRequester(recordingAnchor))
            EventHistory(operations.events)
            DisplaySection(fullScreen, onFullScreenChange)
            ExpandableSection("Advanced configuration", "QGC setup and simulation origin", setupExpanded, { setupExpanded = it }, headerModifier = Modifier.bringIntoViewRequester(setupAnchor)) {
                Text("QGC connection setup", style = MaterialTheme.typography.titleSmall)
                Text("Use different system IDs: MAVLab defaults to 1, QGC to 255. QGC listens on UDP 14550; MAVLab receives commands on 14551. Reconnect the QGC UDP link after changing its system ID.")
                Text("A validated heartbeat indicates a GCS peer. It does not prove delivery of outbound telemetry or give the peer control authority.", style = MaterialTheme.typography.bodySmall)
                LocationSection()
            }
            var aboutExpanded by rememberSaveable { mutableStateOf(false) }
            ExpandableSection("About MAVLab", "MAVLab by Ascend Labs", aboutExpanded, { aboutExpanded = it }) {
                Text("Phone-first, offline-capable drone simulation and digital-twin learning. Cockpit shows readiness, Control owns local inputs and mission actions, SIM shows engine state, and Ops explains health and recovery.")
                val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown" }
                Text("Version $version", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = onReplayOnboarding, modifier = Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true)) { Text("Replay onboarding") }
            }
        }
    }
    confirmation?.let { pending ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(pending.title) }, text = { Text(pending.message) },
            confirmButton = { TextButton(onClick = { confirmation = null; pending.action() }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}

internal data class OpsConfirmation(val title: String, val message: String, val action: () -> Unit)

@Composable
private fun OpsGcsStatus() {
    val connection = remember { AppRuntime.mavlinkIdentityStatus.map { it.gcsConnected }.distinctUntilChanged() }
    val connected by connection.collectAsStateWithLifecycle(false)
    GcsConnectionIndicator(connected)
}

@Composable
private fun DisplaySection(fullScreen: Boolean, onFullScreenChange: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExpandableSection("Display", if (fullScreen) "Full screen" else "Standard", expanded, { expanded = it }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).screenSwipeExcluded(cancelClickOnHorizontalDrag = true)
            .toggleable(value = fullScreen, role = Role.Switch, onValueChange = onFullScreenChange)
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Full screen", style = MaterialTheme.typography.titleSmall)
                Text("Hide Android bars. Swipe from a system edge to reveal them. MAVLab navigation stays visible.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = fullScreen, onCheckedChange = null)
        }
    }
}

@Composable
private fun HealthSummary(operations: OperationsState) {
    val vehicleFlow = remember { AppRuntime.state.map { Triple(it.armed, it.mode.displayName, it.controlAuthority.displayName) }.distinctUntilChanged() }
    val vehicle by vehicleFlow.collectAsStateWithLifecycle(Triple(false, "Stabilize", "Idle"))
    val title = when {
        operations.runtime != RuntimeLifecycleState.RUNNING -> "Simulation ${operations.runtime.name.lowercase()}"
        !operations.stateFresh -> "Waiting for live state"
        operations.incidents.any { it.severity == OpsSeverity.CRITICAL } -> "Critical issue needs attention"
        operations.incidents.isNotEmpty() -> "${operations.incidents.size} active ${if (operations.incidents.size == 1) "issue" else "issues"}"
        else -> "No active issues detected"
    }
    ElevatedCard(Modifier.fillMaxWidth().screenSwipeArea(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text("Authority: ${vehicle.third}", style = MaterialTheme.typography.titleMedium)
            Text("${if (vehicle.first) "Armed" else "Disarmed"} · ${vehicle.second} · ${if (operations.stateFresh) "Live state" else "State unavailable or stale"}", style = MaterialTheme.typography.bodyMedium)
            Text("Recording ${if (operations.recording) "active" else "stopped"}", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun IncidentCard(incident: OpsIncident, onRecover: () -> Unit) {
    val color = when (incident.severity) {
        OpsSeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        OpsSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${incident.severity.label} · ${incident.component.label}", style = MaterialTheme.typography.labelLarge)
            Text(incident.title, style = MaterialTheme.typography.titleMedium)
            Text(incident.consequence, style = MaterialTheme.typography.bodyMedium)
            Text("First observed ${opsTimestamp(incident.observedAtMs)}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onRecover, modifier = Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true)) { Text(incident.actionLabel) }
        }
    }
}

@Composable
private fun PeerSection() {
    val identity by AppRuntime.mavlinkIdentityStatus.collectAsStateWithLifecycle()
    val transport by AppRuntime.status.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val now = rememberOpsClock()
    val label = when {
        identity.identityConflict -> "Identity conflict"
        identity.gcsConnected -> "GCS heartbeat validated"
        identity.lastGcsHeartbeatAtMs != null -> "GCS heartbeat unvalidated or expired"
        identity.lastGcsMessageAtMs != null -> "Packets received · no validated GCS heartbeat"
        else -> "No peer detected"
    }
    ExpandableSection("Peers & identity", label, expanded, { expanded = it }) {
        OpsValue("Vehicle identity", "SYSID ${identity.vehicleSystemId} · COMPID ${identity.vehicleComponentId}")
        OpsValue("Last inbound identity", "SYSID ${identity.lastGcsSystemId ?: "none"} · COMPID ${identity.lastGcsComponentId ?: "none"}")
        OpsValue("Last inbound packet", identity.lastGcsMessageAtMs?.let { "${((now - it).coerceAtLeast(0) / 1000)} s ago" } ?: "None observed")
        OpsValue("Last GCS heartbeat", identity.lastGcsHeartbeatAtMs?.let { "${((now - it).coerceAtLeast(0) / 1000)} s ago" } ?: "None observed")
        OpsValue("Identity", if (identity.identityConflict) "Conflict" else "No conflict detected")
        if (identity.message.isNotBlank()) Text(identity.message, style = MaterialTheme.typography.bodySmall)
        OpsValue("Transport", transport)
        Text("Heartbeat validation, inbound packet receipt and control authority are separate. Outbound telemetry delivery is not measured here.", style = MaterialTheme.typography.bodySmall)
        val diagnosticsFlow = remember { AppRuntime.state.map { it.lastInboundMessage to it.lastAck }.distinctUntilChanged() }
        val diagnostics by diagnosticsFlow.collectAsStateWithLifecycle("None" to "None")
        OpsValue("Last inbound message", diagnostics.first)
        OpsValue("Last command ACK", diagnostics.second)
    }
}

@Composable
private fun MissionDiagnostics(expanded: Boolean, onExpanded: (Boolean) -> Unit, headerModifier: Modifier) {
    val mission by AppRuntime.missionProgress.collectAsStateWithLifecycle()
    val upload by AppRuntime.missionUploadStatus.collectAsStateWithLifecycle()
    if (!mission.loaded && upload.phase == MissionUploadPhase.IDLE) return
    val subtitle = when (upload.phase) {
        MissionUploadPhase.REJECTED -> "Rejected · ${upload.lastError ?: "inspect reason"}"
        MissionUploadPhase.UPLOADING -> "Receiving ${upload.receivedCount}/${upload.expectedCount}"
        MissionUploadPhase.ACCEPTED -> "Accepted · ${upload.receivedCount} items"
        MissionUploadPhase.IDLE -> "Restored from storage · no new upload ACK"
    }
    ExpandableSection("Mission upload", subtitle, expanded, onExpanded, headerModifier) {
        OpsValue("Stored source", mission.origin.name)
        OpsValue("Loaded items", mission.items.size.toString())
        OpsValue("Expected / received", "${upload.expectedCount} / ${upload.receivedCount}")
        OpsValue("Last requested sequence (zero-based)", upload.lastRequestedSequence?.toString() ?: "none")
        OpsValue("Last validated sequence (zero-based)", upload.lastReceivedSequence?.toString() ?: "none")
        upload.lastError?.let { Text("Rejection: $it", color = MaterialTheme.colorScheme.error) }
        if (mission.loaded && !mission.received) Text("Saved source is unverified. Re-upload from QGC before displaying or starting this route. Stored items are retained.")
        if (mission.received) Text("Received items retained. An accepted upload does not prove AUTO execution.", style = MaterialTheme.typography.bodySmall)
        if (upload.phase == MissionUploadPhase.IDLE && mission.received) Text("Restoration generated no upload ACK in this process.", style = MaterialTheme.typography.bodySmall)
        missionLimitations(mission).forEach { Text("Partial behavior: $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun RuntimeSection(operations: OperationsState, onConfirm: (OpsConfirmation) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    ExpandableSection("Runtime components", "${operations.runtime.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (operations.appVisible) "foreground" else "background"}", expanded, { expanded = it }) {
        OpsValue("Simulation engine", if (operations.stateFresh) "Publishing live state" else "No current state verified")
        val sensor by AppRuntime.phoneSensorSource.collectAsStateWithLifecycle()
        OpsValue("Phone sensor monitoring", if (operations.runtime == RuntimeLifecycleState.RUNNING && operations.appVisible) sensor.label else "Suspended")
        Text("Monitoring is separate from control eligibility. AUTO mission authority pauses local phone control.", style = MaterialTheme.typography.bodySmall)
        OpsValue("Motor audio runtime", if (operations.runtime == RuntimeLifecycleState.RUNNING) "Retained with runtime" else "Stopped")
        OpsValue("Telemetry recording", if (operations.recording) "Active · sampled at 5 Hz" else "Stopped")
        Text("Background handoff retains simulation and MAVLink for one minute, then while a validated GCS, armed flight or AUTO mission requires it. Phone input is suspended in the background.", style = MaterialTheme.typography.bodySmall)
        if (operations.runtime == RuntimeLifecycleState.RUNNING) {
            OutlinedButton(modifier = Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), onClick = { onConfirm(OpsConfirmation("Stop simulation?", "Stops physics, MAVLink, sensors and audio, and saves the active recording. Vehicle state freezes; this does not land or disarm it. Starting again resumes the same process state.") { context.startService(SimulationService.stopIntent(context)) }) }) { Text("Stop simulation") }
        } else if (operations.runtime == RuntimeLifecycleState.STOPPED || operations.runtime == RuntimeLifecycleState.ERROR) {
            Button(modifier = Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), onClick = { ContextCompat.startForegroundService(context, SimulationService.appVisibleIntent(context)) }) { Text("Start simulation") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailureSection(onConfirm: (OpsConfirmation) -> Unit) {
    val failures by AppRuntime.failures.collectAsStateWithLifecycle()
    val armedFlow = remember { AppRuntime.state.map { it.armed }.distinctUntilChanged() }
    val armed by armedFlow.collectAsStateWithLifecycle(false)
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExpandableSection("Failure scenarios", "${failures.activeCount} active injected conditions", expanded, { expanded = it }) {
        FlowRow(Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = failures.activeCount > 0, onClick = { onConfirm(OpsConfirmation("Reset simulated failures?", "Clears all injected faults and restores normal environment settings. Battery charge and actual peer connection do not change.", AppRuntime::resetFailures)) }) { Text("Reset all failures") }
            OutlinedButton(enabled = !armed, onClick = { onConfirm(OpsConfirmation("Recharge simulated battery?", "Restores battery charge to 100% while disarmed. Active drain scenarios remain.") { if (!AppRuntime.state.value.armed) AppRuntime.resetBattery() }) }) { Text("Recharge battery") }
        }
        if (armed) Text("Land and disarm before recharging.", style = MaterialTheme.typography.bodySmall)
        FailureScenarios.forEach { scenario ->
            FailureScenarioCard(scenario) { onConfirm(OpsConfirmation("Inject ${scenario.title}?", "${scenario.description}\n\nExpected observation: ${scenario.telemetrySignature.joinToString("; ")}\n\nReset this scenario to remove its injected effects.") { AppRuntime.applyFailureScenario(scenario) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailureScenarioCard(scenario: FailureScenario, onInject: () -> Unit) {
    var learnExpanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(scenario.title, style = MaterialTheme.typography.titleSmall)
            Text("${scenario.severity.label} · ${scenario.learningGoal}", style = MaterialTheme.typography.bodySmall)
            Text(scenario.description, style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onInject) { Text("Inject ${scenario.title}") }
                TextButton(onClick = { AppRuntime.clearFailureScenario(scenario) }) { Text("Reset scenario") }
                TextButton(onClick = { learnExpanded = !learnExpanded }) { Text(if (learnExpanded) "Hide lesson" else "Learn why") }
            }
            if (learnExpanded) Text("Cause: ${scenario.whyItHappened}\nObserve: ${scenario.telemetrySignature.joinToString("; ")}\nResponse: ${scenario.operatorResponse.joinToString("; ")}\nRecovery: ${scenario.recoveryCondition}\nLesson: ${scenario.safetyLesson}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventHistory(events: List<OpsEvent>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var severity by rememberSaveable { mutableStateOf<OpsSeverity?>(null) }
    var component by rememberSaveable { mutableStateOf<OpsComponent?>(null) }
    var eventState by rememberSaveable { mutableStateOf<OpsEventState?>(null) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    val now = rememberOpsClock()
    ExpandableSection("State transitions", "${events.size} recent events · this process", expanded, { expanded = it }) {
        Text("Observed changes are sampled at 5 Hz, retained across tabs, and bounded to 200 events. This history restarts with the app process; saved recording files hold their own timeline.", style = MaterialTheme.typography.bodySmall)
        Text("Severity", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = severity == null, onClick = { severity = null }, label = { Text("All") })
            OpsSeverity.entries.forEach { option -> FilterChip(selected = severity == option, onClick = { severity = option }, label = { Text(option.label) }) }
        }
        Text("Component", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = component == null, onClick = { component = null }, label = { Text("All") })
            OpsComponent.entries.forEach { option -> FilterChip(selected = component == option, onClick = { component = option }, label = { Text(option.label) }) }
        }
        Text("State and time", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = eventState == null, onClick = { eventState = null }, label = { Text("All states") })
            OpsEventState.entries.forEach { option -> FilterChip(selected = eventState == option, onClick = { eventState = option }, label = { Text(option.label) }) }
            FilterChip(selected = recentOnly, onClick = { recentOnly = !recentOnly }, label = { Text("Last 5 minutes") })
        }
        val filtered = filterOpsEvents(events, severity, component, eventState, if (recentOnly) now - 300_000 else null)
        if (filtered.isEmpty()) Text("No transitions match these filters.")
        filtered.take(30).forEach { event ->
            HorizontalDivider()
            Text("${opsTimestamp(event.timestampMs)} · ${event.component.label} · ${event.severity.label} · ${event.state.label}", style = MaterialTheme.typography.labelMedium)
            Text(event.message, style = MaterialTheme.typography.bodyMedium)
        }
        var showAll by rememberSaveable { mutableStateOf(false) }
        if (filtered.size > 30) {
            TextButton(modifier = Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), onClick = { showAll = !showAll }) { Text(if (showAll) "Show fewer events" else "Show ${filtered.size - 30} earlier events") }
            if (showAll) filtered.drop(30).forEach { Text("${opsTimestamp(it.timestampMs)} · ${it.component.label} · ${it.state.label}\n${it.message}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun LocationSection() {
    val state by AppRuntime.state.collectAsStateWithLifecycle()
    val mission by AppRuntime.missionProgress.collectAsStateWithLifecycle()
    val location by AppRuntime.simLocation.collectAsStateWithLifecycle()
    SimLocationCard(state, mission, location)
}

@Composable
internal fun ExpandableSection(title: String, summary: String, expanded: Boolean, onExpanded: (Boolean) -> Unit, headerModifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().then(headerModifier)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(modifier = Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), onClick = { onExpanded(!expanded) }) {
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                Text(if (expanded) "Hide $title" else "Show $title")
            }
            if (expanded) content()
        }
    }
}

@Composable
internal fun OpsValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun rememberOpsClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    return now
}

internal fun opsTimestamp(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
internal fun opsDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}m ${seconds % 60}s"
}
