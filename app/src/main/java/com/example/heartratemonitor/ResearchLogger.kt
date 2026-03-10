// Created by ravishan_n on 2026-03-10
package com.example.heartratemonitor

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ResearchLogger {
    private const val tag = "HR_RESEARCH"
    private const val fileName = "hr_mode_events.csv"

    private var logFile: File? = null
    private var sessionId: String = UUID.randomUUID().toString().take(8)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    fun init(context: Context) {
        if (logFile != null) return
        sessionId = UUID.randomUUID().toString().take(8)
        val dir = File(context.filesDir, "research_logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, fileName)
        if (logFile?.exists() != true) {
            logFile?.appendText("timestamp,session,event,bpm,from_mode,to_mode,success,latency_ms,details\n")
        }
    }

    @Synchronized
    fun logEvent(
        event: String,
        bpm: Int? = null,
        fromMode: DeviceMode? = null,
        toMode: DeviceMode? = null,
        success: Boolean? = null,
        latencyMs: Long? = null,
        details: String = "",
    ) {
        val ts = isoFormatter.format(Date())
        val sanitized = details.replace(',', ';').replace('\n', ' ')
        val line = listOf(
            ts,
            sessionId,
            event,
            bpm?.toString() ?: "",
            fromMode?.name ?: "",
            toMode?.name ?: "",
            success?.toString() ?: "",
            latencyMs?.toString() ?: "",
            sanitized,
        ).joinToString(separator = ",") + "\n"

        try {
            logFile?.appendText(line)
        } catch (t: Throwable) {
            Log.e(tag, "Failed writing research log", t)
        }
        Log.i(tag, "[$sessionId] $event | bpm=$bpm from=$fromMode to=$toMode success=$success latency=$latencyMs $details")
    }
}
