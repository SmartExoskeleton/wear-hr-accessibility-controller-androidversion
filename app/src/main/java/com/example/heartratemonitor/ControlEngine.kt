// Created by ravishan_n on 2026-03-10
package com.example.heartratemonitor

enum class DeviceMode { ECO, FITNESS }

data class ControlConfig(
    val highThreshold: Int = 100,
    val lowThreshold: Int = 88,
    val dwellHighMs: Long = 2000,
    val dwellLowMs: Long = 2500,
    val cooldownMs: Long = 2000,
    val manualOverrideHoldMs: Long = 15000,
)

data class EngineDecision(
    val message: String,
    val switchTarget: DeviceMode? = null,
    val reason: String = "",
)

class ControlEngine(
    initialMode: DeviceMode = DeviceMode.ECO,
    private var config: ControlConfig = ControlConfig(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var currentMode = initialMode
    private var candidateMode: DeviceMode? = null
    private var candidateSinceMs: Long = 0L
    private var lastSwitchAtMs: Long? = null
    private var manualHoldUntilMs: Long = 0L

    fun updateConfig(newConfig: ControlConfig) {
        config = newConfig
    }

    fun snapshotMode(): DeviceMode = currentMode

    fun onManualOverride(mode: DeviceMode): EngineDecision {
        currentMode = mode
        candidateMode = null
        candidateSinceMs = 0L
        val now = nowMs()
        lastSwitchAtMs = now
        manualHoldUntilMs = now + config.manualOverrideHoldMs
        return EngineDecision(
            message = "Manual override -> ${mode.name}. Auto hold ${config.manualOverrideHoldMs}ms",
            reason = "manual_override"
        )
    }

    fun onSwitchSuccess(mode: DeviceMode) {
        currentMode = mode
        lastSwitchAtMs = nowMs()
        candidateMode = null
        candidateSinceMs = 0L
    }

    fun onSample(bpm: Int, autoEnabled: Boolean): EngineDecision {
        if (bpm <= 0) return EngineDecision("Invalid BPM")
        val now = nowMs()

        if (!autoEnabled) {
            return EngineDecision("Auto paused ($bpm BPM)", reason = "auto_off")
        }

        if (now < manualHoldUntilMs) {
            val remaining = manualHoldUntilMs - now
            return EngineDecision(
                "Manual hold active: ${remaining}ms",
                reason = "manual_hold"
            )
        }

        val desiredMode = desiredModeForHr(bpm)

        if (desiredMode == currentMode) {
            candidateMode = null
            candidateSinceMs = 0L
            return EngineDecision("Holding ${desiredMode.name} ($bpm BPM)", reason = "hold")
        }

        if (candidateMode != desiredMode) {
            candidateMode = desiredMode
            candidateSinceMs = now
            return EngineDecision(
                "Candidate ${desiredMode.name} started ($bpm BPM)",
                reason = "candidate_start"
            )
        }

        val dwellTarget = if (bpm >= config.highThreshold) config.dwellHighMs else config.dwellLowMs
        val dwellElapsed = now - candidateSinceMs
        if (dwellElapsed < dwellTarget) {
            return EngineDecision(
                "Candidate ${desiredMode.name}: ${dwellElapsed}ms/${dwellTarget}ms",
                reason = "dwell_wait"
            )
        }

        val lastSwitchAt = lastSwitchAtMs
        if (lastSwitchAt != null) {
            val cooldownElapsed = now - lastSwitchAt
            if (cooldownElapsed < config.cooldownMs) {
                return EngineDecision(
                    "Cooldown active: ${cooldownElapsed}ms/${config.cooldownMs}ms",
                    reason = "cooldown"
                )
            }
        }

        return EngineDecision(
            message = "Switch request -> ${desiredMode.name}",
            switchTarget = desiredMode,
            reason = "switch_request"
        )
    }

    private fun desiredModeForHr(bpm: Int): DeviceMode {
        return when (currentMode) {
            DeviceMode.ECO -> {
                if (bpm <= config.lowThreshold) DeviceMode.FITNESS else DeviceMode.ECO
            }
            DeviceMode.FITNESS -> {
                if (bpm >= config.highThreshold) DeviceMode.ECO else DeviceMode.FITNESS
            }
        }
    }
}
