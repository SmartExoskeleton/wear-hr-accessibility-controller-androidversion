// Created by ravishan_n on 2026-03-09
package com.example.heartratemonitor

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartratemonitor.ui.theme.EmberOrange
import com.example.heartratemonitor.ui.theme.EmberRed
import com.example.heartratemonitor.ui.theme.HeartratemonitorTheme
import com.example.heartratemonitor.ui.theme.HeartBgDark
import com.example.heartratemonitor.ui.theme.HeartSurfaceDark
import com.example.heartratemonitor.ui.theme.Mist
import com.example.heartratemonitor.ui.theme.NightNavy
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private val tag = "HR_UI"
    private val attributionTag = "heart_rate_stream"
    private val appAttributionContext by lazy { createAttributionContext(attributionTag) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HrStore.init(applicationContext)

        setContent {
            HeartratemonitorTheme {
                val hr by HrStore.hr.collectAsState()
                val mode by HrAutomationController.currentMode.collectAsState()
                val autoEnabled by HrAutomationController.autoEnabled.collectAsState()
                val switchTestEnabled by HrAutomationController.switchTestEnabled.collectAsState()
                val config by HrAutomationController.config.collectAsState()
                val decision by HrAutomationController.lastDecision.collectAsState()
                val accessibilityEnabled = ModeAccessibilityService.isEnabled(applicationContext)

                val bpmText = if (hr == "—") "--" else hr
                val streamStatus = if (hr == "—") "No live stream yet" else "Live watch telemetry"
                val automationStatus = when {
                    switchTestEnabled -> "7s switch test running"
                    !accessibilityEnabled -> "Accessibility not connected"
                    !autoEnabled -> "Automation paused"
                    else -> "Automation armed"
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(
                                Brush.verticalGradient(
                                    listOf(HeartBgDark, NightNavy, HeartSurfaceDark)
                                )
                            )
                            .padding(18.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "HR ADAPTIVE CONTROLLER",
                                style = MaterialTheme.typography.labelLarge,
                                color = Mist
                            )

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "$bpmText BPM",
                                        fontSize = 50.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = streamStatus,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Active Mode: ${mode.name}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Auto Control",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Switch(
                                            checked = autoEnabled,
                                            onCheckedChange = { HrAutomationController.setAutoEnabled(it) }
                                        )
                                    }

                                    Text(
                                        text = "Automation Health",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                                .padding(10.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = "State",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                                Text(
                                                    text = automationStatus,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                                .padding(10.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = "Mode",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                                )
                                                Text(
                                                    text = mode.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                                )
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(10.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = "Latest Decision",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = decision,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(10.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = "Thresholds",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "H ${config.highThreshold} / L ${config.lowThreshold}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(10.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = "Timing",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "H ${config.dwellHighMs / 1000}s L ${config.dwellLowMs / 1000}s C ${config.cooldownMs / 1000}s",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { HrAutomationController.forceMode(DeviceMode.ECO) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Force ECO") }

                                Button(
                                    onClick = { HrAutomationController.forceMode(DeviceMode.FITNESS) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Force FITNESS") }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { HrAutomationController.startSwitchTest() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Start 7s Test") }

                                Button(
                                    onClick = { HrAutomationController.stopSwitchTest() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Stop 7s Test") }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        HrAutomationController.updateConfig(
                                            config.copy(highThreshold = config.highThreshold + 5)
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("High +5") }

                                Button(
                                    onClick = {
                                        HrAutomationController.updateConfig(
                                            config.copy(highThreshold = (config.highThreshold - 5).coerceAtLeast(60))
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("High -5") }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        HrAutomationController.updateConfig(
                                            config.copy(lowThreshold = config.lowThreshold + 5)
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Low +5") }

                                Button(
                                    onClick = {
                                        HrAutomationController.updateConfig(
                                            config.copy(lowThreshold = (config.lowThreshold - 5).coerceAtLeast(40))
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Low -5") }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        HrAutomationController.updateConfig(
                                            config.copy(cooldownMs = config.cooldownMs + 1000)
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Cooldown +1s") }

                                Button(
                                    onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Accessibility") }

                                Button(
                                    onClick = { HrAutomationController.emergencyStop() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Stop Auto") }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Brush.horizontalGradient(listOf(EmberRed, EmberOrange)))
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "PHONE LINK ACTIVE",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Mist
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Wearable.getMessageClient(appAttributionContext).addListener(this)
    }

    override fun onStop() {
        Wearable.getMessageClient(appAttributionContext).removeListener(this)
        super.onStop()
    }

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path != "/hr") return
        val hr = String(event.data)
        Log.i(tag, "Foreground receive HR=$hr")
        HrStore.update(applicationContext, hr)
    }
}
