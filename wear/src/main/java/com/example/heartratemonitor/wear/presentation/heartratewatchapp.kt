// Created by ravishan_n on 2026-03-09
package com.example.heartratemonitor.wear.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import com.example.heartratemonitor.wear.presentation.theme.HeartratemonitorTheme
import com.example.heartratemonitor.wear.service.HeartRateForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HeartrateWatchApp : ComponentActivity(), SensorEventListener {

    private val tag = "WEAR_HR"
    private val attributionTag = "heart_rate_stream"
    private val readHeartRatePermission = "android.permission.health.READ_HEART_RATE"
    private val appAttributionContext by lazy { createAttributionContext(attributionTag) }
    private var currentHr by mutableStateOf("—")
    private var statusText by mutableStateOf("Waiting for permission")
    private var hasBodySensorPermission by mutableStateOf(false)
    private var permissionPermanentlyDenied by mutableStateOf(false)
    private var hasRequestedSensorPermission = false
    private var lastSensorEventAtMs = 0L
    private var sensorRegistered = false
    private var sensorWatchdogJob: Job? = null

    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            Log.d(tag, "Permission results: $grants")
            hasRequestedSensorPermission = true
            hasBodySensorPermission = hasAnySensorPermission()
            permissionPermanentlyDenied =
                !hasBodySensorPermission && isPermissionPermanentlyDenied()
            if (hasBodySensorPermission) {
                statusText = "Starting sensor..."
                startMonitoringService()
                startHrSensor()
            } else {
                statusText =
                    if (permissionPermanentlyDenied) {
                        "Permission blocked. Open settings."
                    } else {
                        "Sensor permission denied"
                    }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = appAttributionContext.getSystemService(SENSOR_SERVICE) as SensorManager
        hasBodySensorPermission = hasAnySensorPermission()
        heartRateSensor = if (hasBodySensorPermission) resolveHeartRateSensor() else null
        Log.d(tag, "onCreate: hasPermission=$hasBodySensorPermission, sensorFound=${heartRateSensor != null}")

        setContent {
            WearApp(
                hr = currentHr,
                status = statusText,
                canRequestPermission = !hasBodySensorPermission,
                canOpenSettings = permissionPermanentlyDenied,
                onRequestPermission = { requestBodySensorPermission() },
                onOpenSettings = { openAppSettings() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hasBodySensorPermission = hasAnySensorPermission()
        permissionPermanentlyDenied =
            !hasBodySensorPermission && isPermissionPermanentlyDenied()
        heartRateSensor = if (hasBodySensorPermission) resolveHeartRateSensor() else null
        Log.d(tag, "onResume: hasPermission=$hasBodySensorPermission, sensorFound=${heartRateSensor != null}")

        when {
            !hasBodySensorPermission && permissionPermanentlyDenied -> {
                statusText = "Permission blocked. Open settings."
            }

            !hasBodySensorPermission -> {
                statusText = "Tap button to grant sensor permission"
            }

            heartRateSensor == null -> {
                statusText = "Heart-rate sensor not available"
            }

            else -> {
                statusText = "Starting sensor..."
                startMonitoringService()
                startHrSensor()
            }
        }
    }

    override fun onPause() {
        Log.d(tag, "onPause: stopping sensor")
        stopHrSensor()
        super.onPause()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(tag, "onAccuracyChanged: $accuracy")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        val hrValue = event.values.firstOrNull()?.toInt() ?: return
        Log.d(tag, "onSensorChanged: $hrValue")

        lastSensorEventAtMs = System.currentTimeMillis()
        if (hrValue <= 0) {
            statusText = "Sensor active, waiting for lock..."
            return
        }

        statusText = "Live heart rate"
        currentHr = hrValue.toString()
    }

    private fun startHrSensor() {
        val sensor = heartRateSensor ?: return
        if (!sensorRegistered) {
            sensorRegistered =
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        Log.d(tag, "startHrSensor: registered=$sensorRegistered")
        if (!sensorRegistered) {
            statusText = "Failed to register sensor listener"
            return
        }
        statusText = "Waiting for heart-rate sample..."
        startSensorWatchdog()
    }

    private fun stopHrSensor() {
        if (sensorRegistered) {
            sensorManager.unregisterListener(this)
            sensorRegistered = false
        }
        sensorWatchdogJob?.cancel()
        sensorWatchdogJob = null
    }

    private fun startSensorWatchdog() {
        sensorWatchdogJob?.cancel()
        sensorWatchdogJob = lifecycleScope.launch {
            delay(12_000L)
            if (System.currentTimeMillis() - lastSensorEventAtMs >= 12_000L) {
                statusText = "No sample yet. Wear watch snugly and stay still."
            }
        }
    }

    private fun resolveHeartRateSensor(): Sensor? {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_HEART_RATE)
        val selected = sensors.firstOrNull { it.isWakeUpSensor } ?: sensors.firstOrNull()
        if (selected == null) {
            Log.w(tag, "No TYPE_HEART_RATE sensor on this watch")
        } else {
            Log.i(tag, "Using HR sensor: ${selected.name} (${selected.vendor})")
        }
        return selected
    }

    private fun requestBodySensorPermission() {
        if (hasBodySensorPermission) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            statusText = "Open app and try again"
            return
        }
        val permissionsToRequest =
            availableSensorPermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (permissionsToRequest.isEmpty()) {
            hasBodySensorPermission = true
            statusText = "Starting sensor..."
            startMonitoringService()
            startHrSensor()
            return
        }
        hasRequestedSensorPermission = true
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun openAppSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        startActivity(intent)
    }

    private fun startMonitoringService() {
        val intent = Intent(this, HeartRateForegroundService::class.java)
        ContextCompat.startForegroundService(appAttributionContext, intent)
    }

    private fun hasAnySensorPermission(): Boolean {
        return availableSensorPermissions().any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isPermissionPermanentlyDenied(): Boolean {
        if (!hasRequestedSensorPermission) return false
        return availableSensorPermissions().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(it)
        }
    }

    private fun availableSensorPermissions(): List<String> {
        return listOf(readHeartRatePermission, Manifest.permission.BODY_SENSORS)
            .filter { isPermissionDeclaredOnDevice(it) }
    }

    private fun isPermissionDeclaredOnDevice(permission: String): Boolean {
        return try {
            packageManager.getPermissionInfo(permission, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

@androidx.compose.runtime.Composable
fun WearApp(
    hr: String,
    status: String,
    canRequestPermission: Boolean,
    canOpenSettings: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    HeartratemonitorTheme {
        AppScaffold {
            val displayHr = if (hr == "—") "--" else hr
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F1318))
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(top = 26.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "LIVE HEART RATE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFB8C1CC)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF1A2129))
                            .border(1.dp, Color(0xFF2A3642), RoundedCornerShape(18.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "$displayHr BPM",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF3F6FA)
                            )
                            Text(
                                text = status,
                                fontSize = 11.sp,
                                color = Color(0xFFA8B3BE)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF182027))
                            .border(1.dp, Color(0xFF2A3642), RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = "Background monitoring active",
                            fontSize = 11.sp,
                            color = Color(0xFFA9B6C4)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1B2125))
                            .border(1.dp, Color(0xFF3A3F45), RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = "Wear the watch snug for accuracy",
                            fontSize = 11.sp,
                            color = Color(0xFFB4BDC7)
                        )
                    }

                    if (canRequestPermission) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Grant permission")
                        }
                    }
                    if (canOpenSettings) {
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open settings")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@androidx.compose.runtime.Composable
fun DefaultPreview() {
    WearApp(
        hr = "72",
        status = "Reading heart rate",
        canRequestPermission = false,
        canOpenSettings = false,
        onRequestPermission = {},
        onOpenSettings = {},
    )
}
