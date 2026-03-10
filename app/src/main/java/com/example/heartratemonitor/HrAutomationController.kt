// Created by ravishan_n on 2026-03-10
package com.example.heartratemonitor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object HrAutomationController {
    private const val PREF_FILE = "hr_control"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_HIGH_THRESHOLD = "high_threshold"
    private const val KEY_LOW_THRESHOLD = "low_threshold"
    private const val KEY_DWELL_HIGH_MS = "dwell_high_ms"
    private const val KEY_DWELL_LOW_MS = "dwell_low_ms"
    private const val KEY_COOLDOWN_MS = "cooldown_ms"
    private const val KEY_MANUAL_HOLD_MS = "manual_hold_ms"
    private const val KEY_CONFIG_VERSION = "config_version"
    private const val SWITCH_TEST_INTERVAL_MS = 7000L
    private const val DEFAULT_AUTO_ENABLED = true
    private const val DEFAULT_HIGH_THRESHOLD = 100
    private const val DEFAULT_LOW_THRESHOLD = 88
    private const val DEFAULT_DWELL_HIGH_MS = 2000L
    private const val DEFAULT_DWELL_LOW_MS = 2500L
    private const val DEFAULT_COOLDOWN_MS = 2000L
    private const val DEFAULT_MANUAL_HOLD_MS = 15000L
    private const val CONFIG_VERSION = 2

    private var appContext: Context? = null
    private var engine = ControlEngine()
    private var pendingManualMode: DeviceMode? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var switchTestJob: Job? = null

    private val _currentMode = MutableStateFlow(DeviceMode.ECO)
    val currentMode: StateFlow<DeviceMode> = _currentMode

    private val _autoEnabled = MutableStateFlow(false)
    val autoEnabled: StateFlow<Boolean> = _autoEnabled

    private val _config = MutableStateFlow(ControlConfig())
    val config: StateFlow<ControlConfig> = _config

    private val _lastDecision = MutableStateFlow("Controller idle")
    val lastDecision: StateFlow<String> = _lastDecision

    private val _switchTestEnabled = MutableStateFlow(false)
    val switchTestEnabled: StateFlow<Boolean> = _switchTestEnabled

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val prefs = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        maybeMigrateDefaults(prefs)

        _autoEnabled.value = prefs.getBoolean(KEY_AUTO_ENABLED, DEFAULT_AUTO_ENABLED)
        _config.value = ControlConfig(
            highThreshold = prefs.getInt(KEY_HIGH_THRESHOLD, DEFAULT_HIGH_THRESHOLD),
            lowThreshold = prefs.getInt(KEY_LOW_THRESHOLD, DEFAULT_LOW_THRESHOLD),
            dwellHighMs = prefs.getLong(KEY_DWELL_HIGH_MS, DEFAULT_DWELL_HIGH_MS),
            dwellLowMs = prefs.getLong(KEY_DWELL_LOW_MS, DEFAULT_DWELL_LOW_MS),
            cooldownMs = prefs.getLong(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS),
            manualOverrideHoldMs = prefs.getLong(KEY_MANUAL_HOLD_MS, DEFAULT_MANUAL_HOLD_MS),
        )

        engine = ControlEngine(initialMode = _currentMode.value, config = _config.value)
        ResearchLogger.init(context.applicationContext)
        ResearchLogger.logEvent(
            event = "controller_init",
            details = "auto=${_autoEnabled.value}",
            fromMode = _currentMode.value,
            toMode = _currentMode.value
        )
    }

    fun setAutoEnabled(enabled: Boolean) {
        val context = appContext ?: return
        if (enabled) {
            stopSwitchTest(updateMessage = false)
        }
        _autoEnabled.value = enabled
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_ENABLED, enabled)
            .apply()
        _lastDecision.value = if (enabled) "Auto mode enabled" else "Auto mode paused"
        ResearchLogger.logEvent(
            event = "auto_toggle",
            details = "enabled=$enabled",
            fromMode = _currentMode.value,
            toMode = _currentMode.value
        )
    }

    fun updateConfig(newConfig: ControlConfig) {
        val context = appContext ?: return
        val normalized = normalizeConfig(newConfig)
        _config.value = normalized
        engine.updateConfig(normalized)

        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HIGH_THRESHOLD, normalized.highThreshold)
            .putInt(KEY_LOW_THRESHOLD, normalized.lowThreshold)
            .putLong(KEY_DWELL_HIGH_MS, normalized.dwellHighMs)
            .putLong(KEY_DWELL_LOW_MS, normalized.dwellLowMs)
            .putLong(KEY_COOLDOWN_MS, normalized.cooldownMs)
            .putLong(KEY_MANUAL_HOLD_MS, normalized.manualOverrideHoldMs)
            .apply()

        _lastDecision.value = "Controller config updated"
        ResearchLogger.logEvent(
            event = "config_update",
            details = normalized.toString(),
            fromMode = _currentMode.value,
            toMode = _currentMode.value
        )
    }

    fun emergencyStop() {
        stopSwitchTest(updateMessage = false)
        setAutoEnabled(false)
        pendingManualMode = null
        _lastDecision.value = "Emergency stop activated"
        ResearchLogger.logEvent(
            event = "emergency_stop",
            details = "true",
            fromMode = _currentMode.value,
            toMode = _currentMode.value
        )
    }

    fun startSwitchTest() {
        val context = appContext ?: return
        stopSwitchTest(updateMessage = false)
        setAutoEnabled(false)
        _switchTestEnabled.value = true
        _lastDecision.value = "7s switch test running. Open target app."
        ResearchLogger.logEvent(
            event = "switch_test_start",
            bpm = HrStore.hr.value.toIntOrNull(),
            fromMode = _currentMode.value,
            toMode = _currentMode.value,
            details = "interval_ms=$SWITCH_TEST_INTERVAL_MS"
        )

        switchTestJob = scope.launch {
            var nextMode =
                if (_currentMode.value == DeviceMode.ECO) DeviceMode.FITNESS else DeviceMode.ECO
            while (isActive && _switchTestEnabled.value) {
                val modeForThisTick = nextMode
                pendingManualMode = modeForThisTick
                attemptPendingManualSwitch(
                    context = context,
                    bpm = HrStore.hr.value.toIntOrNull(),
                    source = "interval_tick"
                )
                nextMode = if (modeForThisTick == DeviceMode.ECO) DeviceMode.FITNESS else DeviceMode.ECO
                delay(SWITCH_TEST_INTERVAL_MS)
            }
        }
    }

    fun stopSwitchTest(updateMessage: Boolean = true) {
        if (!_switchTestEnabled.value && switchTestJob == null) {
            return
        }
        switchTestJob?.cancel()
        switchTestJob = null
        _switchTestEnabled.value = false
        pendingManualMode = null
        if (updateMessage) {
            _lastDecision.value = "7s switch test stopped"
        }
        ResearchLogger.logEvent(
            event = "switch_test_stop",
            bpm = HrStore.hr.value.toIntOrNull(),
            fromMode = _currentMode.value,
            toMode = _currentMode.value,
            details = "stopped"
        )
    }

    fun forceMode(mode: DeviceMode) {
        val context = appContext ?: return
        if (_autoEnabled.value) {
            _lastDecision.value = "Manual force ignored while Auto is enabled"
            ResearchLogger.logEvent(
                event = "manual_force_ignored",
                bpm = HrStore.hr.value.toIntOrNull(),
                fromMode = _currentMode.value,
                toMode = mode,
                details = "disable_auto_for_manual_force"
            )
            return
        }
        pendingManualMode = mode
        _lastDecision.value = "Force ${mode.name} queued. Open target app."
        ResearchLogger.logEvent(
            event = "manual_force_queued",
            bpm = HrStore.hr.value.toIntOrNull(),
            fromMode = _currentMode.value,
            toMode = mode,
            details = "queued_from_ui"
        )
        attemptPendingManualSwitch(context, HrStore.hr.value.toIntOrNull(), source = "manual_button")
    }

    private fun attemptPendingManualSwitch(context: Context, bpm: Int?, source: String): Boolean {
        val requestedMode = pendingManualMode ?: return false
        if (!ModeAccessibilityService.isEnabled(context)) {
            _lastDecision.value = "Force pending: Accessibility not enabled"
            return false
        }

        if (!ModeAccessibilityService.isTargetAppForeground(context, ModeAccessibilityService.defaultTargetPackage)) {
            val currentPackage = ModeAccessibilityService.currentForegroundPackage()
            val packageHint = if (currentPackage.isNullOrBlank()) "unknown" else currentPackage
            _lastDecision.value =
                "Force pending: open ${ModeAccessibilityService.defaultTargetPackage} (now: $packageHint)"
            return false
        }

        val from = _currentMode.value
        val start = System.currentTimeMillis()

        val result = ModeAccessibilityService.performModeSwitch(
            context = context,
            mode = requestedMode,
            expectedPackage = ModeAccessibilityService.defaultTargetPackage
        )

        val latency = System.currentTimeMillis() - start
        if (result.success) {
            if (_autoEnabled.value) {
                // When auto is active, keep hysteresis in control immediately after manual taps.
                engine.onSwitchSuccess(requestedMode)
            } else {
                engine.onManualOverride(requestedMode)
            }
            _currentMode.value = requestedMode
            pendingManualMode = null
            _lastDecision.value = "Manual switch -> ${requestedMode.name}"
        } else {
            _lastDecision.value = "Force pending: ${result.message}"
        }

        ResearchLogger.logEvent(
            event = "manual_switch_attempt",
            bpm = bpm,
            fromMode = from,
            toMode = requestedMode,
            success = result.success,
            latencyMs = latency,
            details = "$source:${result.message}"
        )
        return result.success
    }

    @Synchronized
    fun onHeartRateSample(bpm: Int) {
        if (bpm <= 0) return
        val context = appContext ?: return
        if (_switchTestEnabled.value) {
            return
        }
        if (pendingManualMode != null) {
            if (_autoEnabled.value) {
                pendingManualMode = null
            } else {
                if (attemptPendingManualSwitch(context, bpm, source = "hr_tick")) {
                    return
                }
                // Keep manual intent prioritized until it is fulfilled or cancelled.
                return
            }
        }

        val decision = engine.onSample(bpm = bpm, autoEnabled = _autoEnabled.value)
        _lastDecision.value = decision.message

        if (decision.switchTarget == null) {
            ResearchLogger.logEvent(
                event = decision.reason.ifBlank { "controller_state" },
                bpm = bpm,
                fromMode = _currentMode.value,
                toMode = _currentMode.value,
                details = decision.message
            )
            return
        }

        if (!ModeAccessibilityService.isEnabled(context)) {
            _lastDecision.value = "Switch blocked: Accessibility disabled"
            ResearchLogger.logEvent(
                event = "switch_blocked",
                bpm = bpm,
                fromMode = _currentMode.value,
                toMode = decision.switchTarget,
                success = false,
                details = "accessibility_disabled"
            )
            return
        }

        if (!ModeAccessibilityService.isTargetAppForeground(context, ModeAccessibilityService.defaultTargetPackage)) {
            _lastDecision.value = "Switch blocked: target app not foreground"
            ResearchLogger.logEvent(
                event = "switch_blocked",
                bpm = bpm,
                fromMode = _currentMode.value,
                toMode = decision.switchTarget,
                success = false,
                details = "target_not_foreground"
            )
            return
        }

        val from = _currentMode.value
        val start = System.currentTimeMillis()
        val result = ModeAccessibilityService.performModeSwitch(
            context = context,
            mode = decision.switchTarget,
            expectedPackage = ModeAccessibilityService.defaultTargetPackage
        )
        val latency = System.currentTimeMillis() - start

        if (result.success) {
            engine.onSwitchSuccess(decision.switchTarget)
            _currentMode.value = decision.switchTarget
            _lastDecision.value = "Auto switched -> ${decision.switchTarget.name} (${bpm} BPM)"
        } else {
            _lastDecision.value = "Auto switch failed: ${result.message}"
        }

        ResearchLogger.logEvent(
            event = "auto_switch",
            bpm = bpm,
            fromMode = from,
            toMode = decision.switchTarget,
            success = result.success,
            latencyMs = latency,
            details = result.message
        )
    }

    private fun normalizeConfig(config: ControlConfig): ControlConfig {
        val high = config.highThreshold.coerceIn(60, 220)
        val low = config.lowThreshold.coerceIn(40, high - 1)
        return config.copy(
            highThreshold = high,
            lowThreshold = low,
            dwellHighMs = config.dwellHighMs.coerceAtLeast(1000),
            dwellLowMs = config.dwellLowMs.coerceAtLeast(1000),
            cooldownMs = config.cooldownMs.coerceAtLeast(1000),
            manualOverrideHoldMs = config.manualOverrideHoldMs.coerceAtLeast(1000),
        )
    }

    private fun maybeMigrateDefaults(prefs: android.content.SharedPreferences) {
        val storedVersion = prefs.getInt(KEY_CONFIG_VERSION, 0)
        if (storedVersion >= CONFIG_VERSION) return
        prefs.edit()
            .putBoolean(KEY_AUTO_ENABLED, DEFAULT_AUTO_ENABLED)
            .putInt(KEY_HIGH_THRESHOLD, DEFAULT_HIGH_THRESHOLD)
            .putInt(KEY_LOW_THRESHOLD, DEFAULT_LOW_THRESHOLD)
            .putLong(KEY_DWELL_HIGH_MS, DEFAULT_DWELL_HIGH_MS)
            .putLong(KEY_DWELL_LOW_MS, DEFAULT_DWELL_LOW_MS)
            .putLong(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS)
            .putLong(KEY_MANUAL_HOLD_MS, DEFAULT_MANUAL_HOLD_MS)
            .putInt(KEY_CONFIG_VERSION, CONFIG_VERSION)
            .apply()
    }
}
