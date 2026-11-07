package com.ascend.mavlab.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ascend.mavlab.core.common.AppRuntime
import com.ascend.mavlab.core.ui.navigation.screenSwipeExcluded
import com.ascend.mavlab.core.common.RuntimeLifecycleState
import com.ascend.mavlab.simulation.recording.FlightSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingSection(expanded: Boolean, onExpanded: (Boolean) -> Unit, headerModifier: Modifier = Modifier) {
    val recording by AppRuntime.recordingStatus.collectAsStateWithLifecycle()
    val runtime by AppRuntime.runtimeLifecycle.collectAsStateWithLifecycle()
    val armedFlow = remember { AppRuntime.state.map { it.armed }.distinctUntilChanged() }
    val armed by armedFlow.collectAsStateWithLifecycle(false)
    val now = rememberOpsClock()
    val context = LocalContext.current
    val camera=AppRuntime.virtualCamera
    var savedMapPhotos by remember { mutableStateOf(false) }
    var legacyExportFeedback by remember { mutableStateOf<String?>(null) }
    val uiScope=rememberCoroutineScope()
    val exportBusyFlow=remember { camera.status.map { it.exportBusy }.distinctUntilChanged() }
    val exportBusy by exportBusyFlow.collectAsStateWithLifecycle(false)
    LaunchedEffect(expanded) { if(expanded) savedMapPhotos=camera.hasSavedMapPhotos() }
    val exportMapPhotos=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null) camera.exportLatest(uri) { message -> uiScope.launch { legacyExportFeedback=message } }
    }
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedReport by remember { mutableStateOf<FlightSession?>(null) }
    val current = recording.currentSession ?: recording.lastSession
    val summary = when {
        recording.actionInProgress -> "Saving or preparing recording…"
        recording.active -> "Recording active · ${opsDuration(now - (recording.currentSession?.startedAtMs ?: now))}"
        recording.automaticEnabled && recording.automaticPaused -> "Recording stopped · automatic capture paused for this flight"
        current?.endedAtMs != null -> "Recording stopped · saved locally"
        recording.automaticEnabled -> "Not recording · automatic capture enabled"
        else -> "Not recording · automatic capture off"
    }
    ExpandableSection("Recordings & review", summary, expanded, onExpanded, headerModifier) {
        recording.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Automatic recording", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Record flights and missions on arm", modifier = Modifier.weight(1f))
            Switch(checked = recording.automaticEnabled, enabled = !armed && !recording.actionInProgress,
                onCheckedChange = { AppRuntime.setAutomaticRecordingEnabled(it) },
                modifier = Modifier.screenSwipeExcluded().semantics { contentDescription = "Automatic flight and mission recording" })
        }
        Text(if (armed) "Disarm to change this setting." else if (recording.automaticEnabled) "Enabled in Ops · saved for future sessions." else "Off by default. Begin recording manually, or enable automatic capture here.", style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recording.active) {
                Button(enabled = !recording.actionInProgress, onClick = AppRuntime::stopAndSaveRecording) { Text("Stop and save") }
            } else {
                Button(enabled = runtime == RuntimeLifecycleState.RUNNING && !recording.actionInProgress, onClick = AppRuntime::beginRecording) { Text("Begin recording") }
            }
            OutlinedButton(enabled = !recording.actionInProgress, onClick = AppRuntime::refreshSessionHistory) { Text("Refresh recordings") }
        }
        Text("Stop and save does not land, disarm or stop simulation. If automatic capture is enabled, a deliberate stop lasts for this flight. Begin recording can resume it.", style = MaterialTheme.typography.bodySmall)
        if(savedMapPhotos) {
            HorizontalDivider()
            Text("Saved map photos",style=MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled=!armed && !exportBusy,onClick={ exportMapPhotos.launch("MAVLab-saved-map-photos.zip") }) {
                Text(if(exportBusy) "Exporting saved map photos…" else "Export saved map photos")
            }
            Text("Photos from earlier versions remain on this phone. Exporting keeps the originals and leaves simulated captures running.",style=MaterialTheme.typography.bodySmall)
            legacyExportFeedback?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        }
        current?.let { session ->
            HorizontalDivider()
            Text(if (recording.active) "Current recording" else if (session.endedAtMs != null) "Latest saved session" else "Unfinished previous session", style = MaterialTheme.typography.titleSmall)
            SessionRow(session, canShare = !recording.active, onReview = { selectedReport = session }, onShare = { AppRuntime.shareSession(context, session.id) })
        }
        if (current == null) Text("No saved sessions yet. Begin recording for a ground exercise or flight. Arming and starting AUTO do not record unless you enable automatic capture.")
        TextButton(modifier = Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), onClick = { historyExpanded = !historyExpanded }) { Text(if (historyExpanded) "Hide saved recordings" else "Saved recordings (${recording.sessionHistory.size})") }
        if (historyExpanded) {
            if (recording.sessionHistory.isEmpty()) Text("No saved session folders found.")
            var limit by rememberSaveable { mutableIntStateOf(10) }
            recording.sessionHistory.take(limit).forEach { session ->
                HorizontalDivider()
                SessionRow(session, canShare = session.id != recording.currentSession?.id,
                    onReview = { selectedReport = session }, onShare = { AppRuntime.shareSession(context, session.id) })
            }
            if (recording.sessionHistory.size > limit) TextButton(modifier = Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), onClick = { limit += 10 }) { Text("Show 10 more recordings") }
        }
    }
    selectedReport?.let { session ->
        val report by produceState<String?>(null, session.reportPath) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(session.reportPath)
                    if (!file.isFile) "No completed report is available. An unfinished recording is retained for export."
                    else file.bufferedReader().use { reader ->
                        val buffer = CharArray(64_001)
                        var read = 0
                        while (read < buffer.size) {
                            val count = reader.read(buffer, read, buffer.size - read)
                            if (count < 0) break
                            read += count
                        }
                        String(buffer, 0, read.coerceAtMost(64_000)) + if (read > 64_000) "\n\nPreview truncated. Export the session for the full report." else ""
                    }
                }.getOrElse { "Report could not be read: ${it.message}" }
            }
        }
        AlertDialog(onDismissRequest = { selectedReport = null }, title = { Text("Session review") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(session.id, style = MaterialTheme.typography.labelLarge)
                val formatted = remember(report) {
                    buildAnnotatedString {
                        (report ?: "Loading saved report…").lineSequence().forEach { line ->
                            val heading = line.startsWith("#")
                            val clean = line.trimStart('#', ' ').replace("**", "").replace("`", "").trim('_')
                            withStyle(SpanStyle(fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal)) {
                                append(if (clean.startsWith("- ")) "• " + clean.removePrefix("- ") else clean)
                                append("\n")
                            }
                        }
                    }
                }
                Text(formatted, style = MaterialTheme.typography.bodyMedium)
            } }, confirmButton = { TextButton(onClick = { selectedReport = null }) { Text("Close review") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRow(session: FlightSession, canShare: Boolean, onReview: () -> Unit, onShare: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(session.id, style = MaterialTheme.typography.titleSmall)
        Text("Started ${opsTimestamp(session.startedAtMs)} · ${session.endedAtMs?.let { "Saved · ${opsDuration(it - session.startedAtMs)}" } ?: "Active or unfinished"}", style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag = true), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.endedAtMs != null) OutlinedButton(onClick = onReview) { Text("Review session") }
            OutlinedButton(enabled = canShare, onClick = onShare) { Text("Export session") }
        }
    }
}
