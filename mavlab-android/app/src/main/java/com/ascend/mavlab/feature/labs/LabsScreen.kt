package com.ascend.mavlab.feature.labs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ascend.mavlab.core.common.AppRuntime
import com.ascend.mavlab.simulation.engine.DroneState
import com.ascend.mavlab.simulation.failures.FailureScenario
import com.ascend.mavlab.simulation.failures.FailureScenarios
import com.ascend.mavlab.simulation.failures.FailureState
import com.ascend.mavlab.simulation.mission.MissionProgress

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabsScreen(modifier: Modifier = Modifier) {
    val droneState by AppRuntime.state.collectAsState()
    val failures by AppRuntime.failures.collectAsState()
    val mission by AppRuntime.missionProgress.collectAsState()

    var catalogExpanded by remember { mutableStateOf(false) }
    val activeScenarioCount = FailureScenarios.count { isScenarioActive(it, failures) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Failure Lab", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "${failures.activeCount} active failures | ${droneState.mode.displayName} | GPS ${gpsLabel(droneState)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            // Collapsible scenario catalog header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Scenario Catalog",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (activeScenarioCount > 0) {
                        Text(
                            text = "$activeScenarioCount active",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
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

            AnimatedVisibility(
                visible = catalogExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FailureScenarios.forEach { scenario ->
                        ScenarioChip(
                            scenario = scenario,
                            isActive = isScenarioActive(scenario, failures),
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = AppRuntime::resetFailures) {
                    Text("Reset all failures")
                }
                Button(onClick = AppRuntime::resetBattery) {
                    Text("Reset battery")
                }
            }

            FailureControls(failures)

            HorizontalDivider()
            MissionLab(droneState = droneState, mission = mission)
        }
    }
}

@Composable
private fun ScenarioChip(scenario: FailureScenario, isActive: Boolean) {
    FilterChip(
        selected = isActive,
        onClick = {
            if (isActive) {
                AppRuntime.clearFailureScenario(scenario)
            } else {
                AppRuntime.applyFailureScenario(scenario)
            }
        },
        label = {
            Text(
                text = if (isActive) "● ${scenario.title}" else scenario.title,
            )
        },
        colors = if (isActive) {
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = when (scenario.severity) {
                    com.ascend.mavlab.simulation.failures.FailureSeverity.Critical ->
                        MaterialTheme.colorScheme.errorContainer
                    com.ascend.mavlab.simulation.failures.FailureSeverity.Warning ->
                        MaterialTheme.colorScheme.tertiaryContainer
                    com.ascend.mavlab.simulation.failures.FailureSeverity.Caution ->
                        MaterialTheme.colorScheme.secondaryContainer
                },
                selectedLabelColor = when (scenario.severity) {
                    com.ascend.mavlab.simulation.failures.FailureSeverity.Critical ->
                        MaterialTheme.colorScheme.onErrorContainer
                    com.ascend.mavlab.simulation.failures.FailureSeverity.Warning ->
                        MaterialTheme.colorScheme.onTertiaryContainer
                    com.ascend.mavlab.simulation.failures.FailureSeverity.Caution ->
                        MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        } else {
            FilterChipDefaults.filterChipColors()
        },
    )
}

@Composable
private fun FailureControls(failures: FailureState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleRow(
            label = "GPS enabled",
            checked = failures.gpsEnabled,
            onCheckedChange = AppRuntime::setGpsEnabled,
        )
        SliderRow(
            label = "GPS noise",
            value = failures.gpsNoiseMultiplier,
            range = 1f..8f,
            display = "%.1fx".format(failures.gpsNoiseMultiplier),
            onValueChange = AppRuntime::setGpsNoiseMultiplier,
        )
        ToggleRow(
            label = "Compass enabled",
            checked = failures.compassEnabled,
            onCheckedChange = AppRuntime::setCompassEnabled,
        )
        SliderRow(
            label = "Compass offset",
            value = failures.compassOffsetDeg,
            range = -90f..90f,
            display = "%.0f deg".format(failures.compassOffsetDeg),
            onValueChange = AppRuntime::setCompassOffsetDeg,
        )
        SliderRow(
            label = "Wind speed",
            value = failures.windSpeedMs,
            range = 0f..15f,
            display = "%.1f m/s".format(failures.windSpeedMs),
            onValueChange = AppRuntime::setWindSpeedMs,
        )
        SliderRow(
            label = "Wind direction",
            value = failures.windDirectionDeg,
            range = 0f..359f,
            display = "%.0f deg".format(failures.windDirectionDeg),
            onValueChange = AppRuntime::setWindDirectionDeg,
        )
        SliderRow(
            label = "Wind gusts",
            value = failures.windGustsMs,
            range = 0f..8f,
            display = "%.1f m/s".format(failures.windGustsMs),
            onValueChange = AppRuntime::setWindGustsMs,
        )
        MotorFailureRow(failures.motorFailureMask)
        SliderRow(
            label = "Battery drain",
            value = failures.batteryDrainMultiplier,
            range = 1f..15f,
            display = "%.1fx".format(failures.batteryDrainMultiplier),
            onValueChange = AppRuntime::setBatteryDrainMultiplier,
        )
        SliderRow(
            label = "Payload mass",
            value = failures.payloadMassKg,
            range = 0f..2.5f,
            display = "%.1f kg".format(failures.payloadMassKg),
            onValueChange = AppRuntime::setPayloadMassKg,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MotorFailureRow(mask: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Motor failures", style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (0..3).forEach { index ->
                val failed = mask and (1 shl index) != 0
                FilterChip(
                    selected = failed,
                    onClick = { AppRuntime.setMotorFailed(index, !failed) },
                    label = { Text("Motor ${index + 1}") },
                )
            }
        }
    }
}

@Composable
private fun MissionLab(
    droneState: DroneState,
    mission: MissionProgress,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mission Lab", style = MaterialTheme.typography.headlineMedium)
        MissionStatusCard(droneState, mission)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = AppRuntime::loadDemoMission,
                modifier = Modifier.weight(1f),
            ) {
                Text("Load demo")
            }
            OutlinedButton(
                onClick = AppRuntime::startAutoMission,
                modifier = Modifier.weight(1f),
            ) {
                Text("Start Auto")
            }
            OutlinedButton(
                onClick = AppRuntime::clearMission,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { AppRuntime.sendGuidedOffset(northMeters = 10f, eastMeters = 0f, altitudeAglMeters = 8f) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Guided N")
            }
            OutlinedButton(
                onClick = { AppRuntime.sendGuidedOffset(northMeters = 0f, eastMeters = 10f, altitudeAglMeters = 8f) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Guided E")
            }
        }
        mission.items.forEach { item ->
            val status = when {
                mission.complete || item.sequence < mission.currentIndex -> "Reached"
                item.sequence == mission.currentIndex -> "Active"
                else -> "Queued"
            }
            Text(
                text = "#${item.sequence + 1} ${item.command.name} | %.6f, %.6f | %.1f m | $status".format(
                    item.latitudeDeg,
                    item.longitudeDeg,
                    item.altitudeAglMeters,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MissionStatusCard(
    droneState: DroneState,
    mission: MissionProgress,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (mission.loaded) {
                    "Mission ${mission.completedCount}/${mission.items.size}"
                } else {
                    "No mission loaded"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Mode: ${droneState.mode.displayName}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = mission.activeTarget?.let {
                    "Target: waypoint ${it.sequence + 1}, %.1f m AGL".format(it.altitudeAglMeters)
                } ?: "Target: none",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Position: N %.1f m, E %.1f m, Alt %.1f m".format(
                    droneState.northMeters,
                    droneState.eastMeters,
                    droneState.altitudeAglMeters,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun gpsLabel(state: DroneState): String {
    return if (state.gpsFixType.toInt() >= 3) "3D" else "Lost"
}

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
