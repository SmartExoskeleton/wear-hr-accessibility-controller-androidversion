// Created by ravishan_n on 2026-03-09
package com.example.heartratemonitor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.example.heartratemonitor.ui.theme.HeartratemonitorTheme
import com.example.heartratemonitor.ui.theme.EmberOrange
import com.example.heartratemonitor.ui.theme.EmberRed
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
                val status = if (hr == "—") "Waiting for watch stream" else "Live from watch"
                val bpmText = if (hr == "—") "--" else hr

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
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "HEART RATE MONITOR",
                                style = MaterialTheme.typography.labelLarge,
                                color = Mist
                            )

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(28.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "$bpmText BPM",
                                        fontSize = 52.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        Brush.horizontalGradient(listOf(EmberRed, EmberOrange))
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "PHONE LISTENER ACTIVE",
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
