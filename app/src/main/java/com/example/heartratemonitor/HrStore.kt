// Created by ravishan_n on 2026-03-09
package com.example.heartratemonitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object HrStore {
    private const val PREF_FILE = "hr_store"
    private const val KEY_LAST_HR = "last_hr"
    private var lastProcessedValue: String = "—"
    private var lastProcessedAtMs: Long = 0L
    private val _hr = MutableStateFlow("—")
    val hr: StateFlow<String> = _hr

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        _hr.value = prefs.getString(KEY_LAST_HR, "—") ?: "—"
        HrAutomationController.init(context.applicationContext)
    }

    fun update(context: Context, value: String) {
        val now = System.currentTimeMillis()
        if (value == lastProcessedValue && now - lastProcessedAtMs < 500L) {
            return
        }
        lastProcessedValue = value
        lastProcessedAtMs = now

        _hr.value = value
        val prefs = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_HR, value).apply()

        value.toIntOrNull()?.let { bpm ->
            ResearchLogger.logEvent(
                event = "hr_sample",
                bpm = bpm,
                fromMode = HrAutomationController.currentMode.value,
                toMode = HrAutomationController.currentMode.value,
                details = "incoming_sample"
            )
            HrAutomationController.onHeartRateSample(bpm)
        }
    }
}
