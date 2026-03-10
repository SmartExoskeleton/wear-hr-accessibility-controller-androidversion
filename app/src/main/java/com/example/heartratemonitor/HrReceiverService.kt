// Created by ravishan_n on 2026-03-09
package com.example.heartratemonitor

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class HrReceiverService : WearableListenerService() {

    private val tag = "HR_SVC"

    override fun onCreate() {
        super.onCreate()
        HrStore.init(applicationContext)
        HrAutomationController.init(applicationContext)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != "/hr") {
            return
        }

        val hrStr = String(messageEvent.data)
        Log.i(tag, "Received HR=$hrStr from ${messageEvent.sourceNodeId}")
        HrStore.update(applicationContext, hrStr)
    }
}
