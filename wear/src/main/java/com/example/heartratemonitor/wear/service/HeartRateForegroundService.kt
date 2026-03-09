// Created by ravishan_n on 2026-03-09
package com.example.heartratemonitor.wear.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.heartratemonitor.wear.presentation.HeartrateWatchApp
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class HeartRateForegroundService : Service(), SensorEventListener {

    private val tag = "WEAR_HR_SERVICE"
    private val channelId = "hr_background_channel"
    private val notificationId = 1001
    private val attributionTag = "heart_rate_stream"
    private val readHeartRatePermission = "android.permission.health.READ_HEART_RATE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attributedContext by lazy { createAttributionContext(attributionTag) }

    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private var sensorRegistered = false
    private var lastSentAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = attributedContext.getSystemService(SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notificationId, buildNotification())

        if (!hasAnySensorPermission()) {
            Log.w(tag, "No sensor permission; stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        heartRateSensor = resolveHeartRateSensor()
        val sensor = heartRateSensor
        if (sensor == null) {
            Log.w(tag, "No heart-rate sensor available")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!sensorRegistered) {
            sensorRegistered =
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        Log.d(tag, "service registerListener=$sensorRegistered")
        if (!sensorRegistered) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (sensorRegistered) {
            sensorManager.unregisterListener(this)
            sensorRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        val hr = event.values.firstOrNull()?.toInt() ?: return
        if (hr <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastSentAtMs < 1000L) return
        lastSentAtMs = now
        scope.launch { sendHr(hr.toString()) }
    }

    private fun hasAnySensorPermission(): Boolean {
        return availableSensorPermissions().any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun availableSensorPermissions(): List<String> {
        return listOf(readHeartRatePermission, Manifest.permission.BODY_SENSORS)
            .filter { permission ->
                try {
                    packageManager.getPermissionInfo(permission, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
    }

    private fun resolveHeartRateSensor(): Sensor? {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_HEART_RATE)
        return sensors.firstOrNull { it.isWakeUpSensor } ?: sensors.firstOrNull()
    }

    private suspend fun sendHr(hr: String) {
        try {
            val nodes = Wearable.getNodeClient(attributedContext).connectedNodes.await()
            for (node in nodes) {
                Wearable.getMessageClient(attributedContext)
                    .sendMessage(node.id, "/hr", hr.toByteArray())
                    .await()
                Log.i(tag, "Sent HR=$hr to ${node.displayName}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Send failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, HeartrateWatchApp::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Heart Rate Monitor")
            .setContentText("Monitoring in background")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "HR Background",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }
}
