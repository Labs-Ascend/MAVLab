package com.ascend.mavlab.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ascend.mavlab.core.common.AppRuntime
import com.ascend.mavlab.core.common.RuntimeLifecycleState
import com.ascend.mavlab.core.ui.navigation.screenSwipeExcluded
import com.ascend.mavlab.simulation.camera.VirtualCameraController
import com.ascend.mavlab.simulation.engine.SimLocation
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CameraSection(onConfirm:(OpsConfirmation)->Unit) {
    val camera=AppRuntime.virtualCamera
    val status by camera.status.collectAsStateWithLifecycle()
    val armedFlow=remember { AppRuntime.state.map { it.armed }.distinctUntilChanged() }
    val armed by armedFlow.collectAsStateWithLifecycle(false)
    val runtime by AppRuntime.runtimeLifecycle.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val off=status.phase==VirtualCameraController.Phase.OFF
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if(uri!=null) camera.exportLatest(uri) }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) camera.importPack(uri,!AppRuntime.state.value.armed) }
    val mapPhotos=status.mode==VirtualCameraController.Mode.MAP_PHOTOS
    ExpandableSection("Virtual camera",status.message,expanded,{ expanded=it }) {
        Text(status.mode.label,style=MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            VirtualCameraController.Mode.entries.forEach { mode ->
                FilterChip(selected=status.mode==mode,enabled=off && !armed && !status.exportBusy,
                    onClick={ camera.selectMode(mode,!AppRuntime.state.value.armed) },
                    label={ Text(if(mode==VirtualCameraController.Mode.EVENTS_ONLY) "Simulated captures" else "Map photos") })
            }
        }
        Text("Turn the camera off while disarmed to change mode. Flight control and recording are independent.",style=MaterialTheme.typography.bodySmall)
        if(mapPhotos) {
            Text("Offline map-style training captures · 640 × 480 JPEG. No phone camera permission needed.")
            Text("Bundled Dandora map: © OpenStreetMap contributors · openstreetmap.org/copyright · ODbL. Flat simulated ground; these are not aerial photographs.",style=MaterialTheme.typography.bodySmall)
        } else Text("QGC receives simulated capture events at the vehicle's position. No map is loaded and no image files are created. Recent feedback is held in memory only.")
        Text(status.message,color=if(status.phase==VirtualCameraController.Phase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text("${status.simulated} simulated · ${status.saved} JPEGs saved · ${status.failed} failed · ${status.attempted}/${status.mode.limit} requests · cache ${status.cachedBytes/1024} KiB",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.screenSwipeExcluded(cancelClickOnHorizontalDrag=true)) {
            if(mapPhotos) OutlinedButton(enabled=!armed && off && !status.exportBusy,onClick={ onConfirm(OpsConfirmation("Use Dandora map origin?","This resets the disarmed simulation location and clears navigation and safety plans. The bundled map covers 896 × 896 metres.") {
                AppRuntime.setSimLocation(SimLocation.custom(-1.2425,36.9225,1805f))
            }) }) { Text("Use Dandora origin") }
            if(off) Button(enabled=runtime==RuntimeLifecycleState.RUNNING && !status.exportBusy,onClick=camera::enable) { Text("Enable camera") }
            else OutlinedButton(onClick=camera::disable) { Text("Camera off") }
            Button(enabled=!off && status.phase!=VirtualCameraController.Phase.LOADING,onClick={camera.requestCaptureWithFeedback("Ops test capture")}) { Text(if(mapPhotos) "Test photo" else "Simulate capture") }
            if(mapPhotos) {
                OutlinedButton(enabled=off && !status.exportBusy,onClick={export.launch("MAVLab-virtual-captures.zip")}) { Text("Export latest session") }
                OutlinedButton(enabled=off && !armed && !status.exportBusy,onClick={import.launch(arrayOf("application/octet-stream","application/vnd.sqlite3","*/*"))}) { Text("Import map pack") }
            }
        }
        if(mapPhotos) Text("Take off above 0.5 m within map coverage before a photo. Minimum interval: 3 seconds. Turn camera off before export/import.",style=MaterialTheme.typography.bodySmall)
        else Text("Minimum simulated capture interval: 250 ms. Runtime Start enables this mode automatically. Camera off pauses capture availability; it does not land the vehicle.",style=MaterialTheme.typography.bodySmall)
        if(status.latestImage!=null) Text("Latest JPEG saved on this phone. Export the session to review images and pose records.",style=MaterialTheme.typography.bodySmall)
    }
}
