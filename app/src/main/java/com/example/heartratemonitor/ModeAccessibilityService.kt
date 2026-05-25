// Created by ravishan_n on 2026-03-10
package com.example.heartratemonitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ModeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        HrAutomationController.init(applicationContext)
        serviceRef = WeakReference(this)
        _serviceBound.value = true
        _foregroundPackage.value = rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }
        Log.i(tag, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        updateForegroundPackage(event)
        logForegroundFromEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        _serviceBound.value = false
        _foregroundPackage.value = null
        serviceRef = null
        super.onDestroy()
    }

    companion object {
        private const val tag = "HR_ACCESS"
        private var serviceRef: WeakReference<ModeAccessibilityService>? = null
        private const val maxAttempts = 3
        private const val debugNodeProbe = true
        private const val resistanceGestureDurationMs = 900L
        private var lastForegroundPackageLogged: String? = null
        private var lastForegroundLogAtMs: Long = 0L
        private const val foregroundLogThrottleMs = 1500L
        private val _serviceBound = MutableStateFlow(false)
        val serviceBound: StateFlow<Boolean> = _serviceBound
        private val _foregroundPackage = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> = _foregroundPackage

        // Replace this with exact package in your closed test environment if needed.
        const val defaultTargetPackage = "com.hypershell"

        data class SwitchResult(val success: Boolean, val message: String)

        fun isEnabled(context: Context): Boolean {
            val className = ModeAccessibilityService::class.java.name
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val managerEnabled = manager
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo
                    serviceInfo?.packageName == context.packageName && serviceInfo.name == className
                } == true
            if (managerEnabled) return true

            val cn = ComponentName(context, ModeAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val full = cn.flattenToString()
            val short = cn.flattenToShortString()
            return enabled.contains(full, ignoreCase = true) || enabled.contains(short, ignoreCase = true)
        }

        fun isTargetAppForeground(context: Context, expectedPackage: String): Boolean {
            val service = serviceRef?.get() ?: return false
            val root = service.rootInActiveWindow ?: return false
            val pkg = root.packageName?.toString() ?: return false
            return matchesPackage(pkg, expectedPackage)
        }

        fun currentForegroundPackage(): String? {
            val service = serviceRef?.get() ?: return null
            val root = service.rootInActiveWindow ?: return null
            return root.packageName?.toString()
        }

        fun matchesExpectedPackage(actual: String?, expected: String): Boolean {
            val packageName = actual?.takeIf { it.isNotBlank() } ?: return false
            return matchesPackage(packageName, expected)
        }

        fun performModeSwitch(
            context: Context,
            mode: DeviceMode,
            expectedPackage: String,
        ): SwitchResult {
            if (!isEnabled(context)) {
                return SwitchResult(false, "Accessibility service disabled")
            }
            val service = serviceRef?.get() ?: return SwitchResult(false, "Accessibility service not bound")

            repeat(maxAttempts) { attempt ->
                val root = service.rootInActiveWindow ?: return@repeat
                val packageName = root.packageName?.toString().orEmpty()
                if (!matchesPackage(packageName, expectedPackage)) {
                    if (debugNodeProbe) {
                        Log.i(
                            tag,
                            "Switch probe mode=${mode.name} attempt=${attempt + 1} wrong package=$packageName expected=$expectedPackage"
                        )
                    }
                    return SwitchResult(false, "Wrong screen/package: $packageName")
                }

                if (debugNodeProbe) {
                    logNodeAvailability(root, mode, attempt + 1, packageName)
                }
                val target = findTargetNode(root, mode)
                if (target == null) {
                    if (attempt == maxAttempts - 1) {
                        return SwitchResult(false, "Target button not found for ${mode.name}")
                    }
                    return@repeat
                }

                if (debugNodeProbe) {
                    logNodeClickability(target, mode, attempt + 1)
                }
                val clicked = clickNode(target)
                if (clicked) {
                    if (debugNodeProbe) {
                        Log.i(tag, "Switch probe mode=${mode.name} attempt=${attempt + 1} click=true")
                    }
                    return SwitchResult(true, "Clicked ${mode.name} (attempt ${attempt + 1})")
                }
                if (debugNodeProbe) {
                    Log.i(tag, "Switch probe mode=${mode.name} attempt=${attempt + 1} click=false")
                }
            }
            return SwitchResult(false, "Node found but click action failed")
        }

        fun performResistanceDrag(
            context: Context,
            targetPercent: Int,
            expectedPackage: String,
            fromPercent: Int,
        ): SwitchResult {
            if (!isEnabled(context)) {
                return SwitchResult(false, "Accessibility service disabled")
            }
            val service = serviceRef?.get() ?: return SwitchResult(false, "Accessibility service not bound")
            val root = service.rootInActiveWindow ?: return SwitchResult(false, "No active window")
            val packageName = root.packageName?.toString().orEmpty()
            if (!matchesPackage(packageName, expectedPackage)) {
                return SwitchResult(false, "Wrong screen/package: $packageName")
            }

            val rootBounds = Rect().also(root::getBoundsInScreen)
            val anchorBounds = findResistanceAnchorBounds(root, rootBounds)
            val bar = calculateResistanceBarBounds(rootBounds, anchorBounds)
                ?: return SwitchResult(false, "Invalid resistance bar geometry")

            val clampedFrom = fromPercent.coerceIn(0, 100)
            val clampedTarget = targetPercent.coerceIn(0, 100)
            val startX = bar.left + (bar.width() * clampedFrom / 100f)
            val targetX = bar.left + (bar.width() * clampedTarget / 100f)
            val direction = if (targetX >= startX) 1f else -1f
            val overshootX = (targetX + direction * bar.width() * 0.04f)
                .coerceIn(bar.left.toFloat(), bar.right.toFloat())
            val y = bar.centerY().toFloat()
            val path = Path().apply {
                moveTo(startX, y)
                lineTo(startX + direction * bar.width() * 0.03f, y)
                lineTo((startX + targetX) / 2f, y)
                lineTo(overshootX, y)
                lineTo(targetX, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        resistanceGestureDurationMs
                    )
                )
                .build()

            val dispatched = service.dispatchGesture(gesture, null, null)
            if (debugNodeProbe) {
                Log.i(
                    tag,
                    "Resistance drag from=$clampedFrom target=$clampedTarget " +
                        "bar=$bar anchor=$anchorBounds dispatched=$dispatched"
                )
            }
            return if (dispatched) {
                SwitchResult(true, "Resistance drag -> $clampedTarget%")
            } else {
                SwitchResult(false, "Resistance gesture dispatch failed")
            }
        }

        private fun findTargetNode(root: AccessibilityNodeInfo, mode: DeviceMode): AccessibilityNodeInfo? {
            val idHints = when (mode) {
                DeviceMode.ECO -> listOf("eco", "mode_eco", "btn_eco")
                DeviceMode.FITNESS -> listOf("fitness", "mode_fitness", "btn_fitness")
            }

            // 1) Prefer view-id matching when available.
            val idMatch = findByViewIdHints(root, idHints)
            if (idMatch != null) return idMatch

            // 2) Fallback to text matching.
            val labels = when (mode) {
                DeviceMode.ECO -> listOf("eco", "eco mode")
                DeviceMode.FITNESS -> listOf("fitness", "fitness mode")
            }
            return findBySemanticHints(root, labels)
        }

        private fun findByViewIdHints(root: AccessibilityNodeInfo, idHints: List<String>): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val idName = node.viewIdResourceName.orEmpty().lowercase()
                if (idHints.any { idName.contains(it) } && node.isVisibleToUser) {
                    if (node.isClickable || node.parent?.isClickable == true) {
                        return node
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }
            }
            return null
        }

        private fun findResistanceAnchorBounds(root: AccessibilityNodeInfo, rootBounds: Rect): Rect {
            val rootWidth = rootBounds.width().coerceAtLeast(1)
            val rootHeight = rootBounds.height().coerceAtLeast(1)
            val targetX = rootBounds.left + rootWidth * 0.869f
            val targetY = rootBounds.top + rootHeight * 0.781f
            val minY = rootBounds.top + rootHeight * 0.735f
            val maxY = rootBounds.top + rootHeight * 0.835f

            val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val bounds = Rect().also(node::getBoundsInScreen)
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                val actionable = node.isClickable || node.parent?.isClickable == true
                val plausibleSize = bounds.width() in 20..180 && bounds.height() in 20..180
                val inRightControlArea =
                    centerX >= rootBounds.left + rootWidth * 0.70f &&
                        centerY >= minY &&
                        centerY <= maxY
                if (node.isVisibleToUser && actionable && plausibleSize && inRightControlArea) {
                    candidates.add(node to bounds)
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }
            }

            val matched = candidates.minByOrNull { (_, bounds) ->
                val dx = kotlin.math.abs(bounds.centerX() - targetX)
                val dy = kotlin.math.abs(bounds.centerY() - targetY)
                dy + dx * 0.25f
            }?.second

            if (matched != null) return matched

            // Fallback based on Scanner output: lower right button beside the resistance bar.
            val left = rootBounds.left + (rootWidth * 0.805f).toInt()
            val top = rootBounds.top + (rootHeight * 0.735f).toInt()
            val right = rootBounds.left + (rootWidth * 0.931f).toInt()
            val bottom = rootBounds.top + (rootHeight * 0.782f).toInt()
            return Rect(left, top, right, bottom)
        }

        private fun calculateResistanceBarBounds(rootBounds: Rect, anchorBounds: Rect): Rect? {
            val rootWidth = rootBounds.width()
            if (rootWidth <= 0 || anchorBounds.isEmpty) return null
            val gap = (rootWidth * 0.035f).toInt().coerceAtLeast(8)
            val left = rootBounds.left + (rootWidth * 0.08f).toInt()
            val right = anchorBounds.left - gap
            val height = (anchorBounds.height() * 0.55f).toInt().coerceAtLeast(20)
            val centerY = anchorBounds.centerY()
            if (right <= left) return null
            return Rect(left, centerY - height / 2, right, centerY + height / 2)
        }

        private fun clickNode(node: AccessibilityNodeInfo): Boolean {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                parent = parent.parent
            }
            return false
        }

        private fun findBySemanticHints(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
            val normalizedLabels = labels.map { it.lowercase() }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser) {
                    val text = node.text?.toString().orEmpty().lowercase()
                    val desc = node.contentDescription?.toString().orEmpty().lowercase()
                    val combined = "$text $desc"
                    if (normalizedLabels.any { combined.contains(it) }) {
                        candidates.add(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }
            }

            // Prefer candidates that look like actionable chips/tabs/buttons.
            val actionable = candidates.firstOrNull {
                val className = it.className?.toString().orEmpty().lowercase()
                val looksLikeChip = className.contains("chip") || className.contains("tab") || className.contains("button")
                looksLikeChip && (it.isClickable || it.parent?.isClickable == true)
            }
            if (actionable != null) return actionable

            return candidates.firstOrNull { it.isClickable || it.parent?.isClickable == true }
        }

        private fun logNodeAvailability(
            root: AccessibilityNodeInfo,
            mode: DeviceMode,
            attempt: Int,
            packageName: String,
        ) {
            val idHints = when (mode) {
                DeviceMode.ECO -> listOf("eco", "mode_eco", "btn_eco")
                DeviceMode.FITNESS -> listOf("fitness", "mode_fitness", "btn_fitness")
            }
            val labelHints = when (mode) {
                DeviceMode.ECO -> listOf("Eco", "ECO")
                DeviceMode.FITNESS -> listOf("Fitness", "FITNESS")
            }

            var idMatchCount = 0
            var idClickableCount = 0
            var visibleCount = 0
            var totalCount = 0
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                totalCount += 1
                if (node.isVisibleToUser) visibleCount += 1

                val idName = node.viewIdResourceName.orEmpty().lowercase()
                if (idHints.any { idName.contains(it) }) {
                    idMatchCount += 1
                    if (node.isClickable || node.parent?.isClickable == true) {
                        idClickableCount += 1
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }
            }

            var textMatchCount = 0
            var textClickableCount = 0
            for (label in labelHints) {
                val nodes = root.findAccessibilityNodeInfosByText(label)
                textMatchCount += nodes.size
                textClickableCount += nodes.count { it.isVisibleToUser && (it.isClickable || it.parent?.isClickable == true) }
            }

            Log.i(
                tag,
                "Switch probe mode=${mode.name} attempt=$attempt pkg=$packageName " +
                    "nodes=$totalCount visible=$visibleCount " +
                    "idMatches=$idMatchCount idClickable=$idClickableCount " +
                    "textMatches=$textMatchCount textClickable=$textClickableCount"
            )
        }

        private fun logNodeClickability(
            node: AccessibilityNodeInfo,
            mode: DeviceMode,
            attempt: Int,
        ) {
            val idName = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val nodeClickable = node.isClickable
            val parentClickable = node.parent?.isClickable == true
            Log.i(
                tag,
                "Switch probe mode=${mode.name} attempt=$attempt " +
                    "targetId=$idName targetText=$text targetDesc=$desc " +
                    "nodeClickable=$nodeClickable parentClickable=$parentClickable"
            )
        }

        private fun matchesPackage(actual: String, expected: String): Boolean {
            if (expected.isBlank()) return true
            return actual == expected || actual.startsWith("$expected.") || actual.contains(expected)
        }

        private fun updateForegroundPackage(event: AccessibilityEvent?) {
            val service = serviceRef?.get() ?: return
            resolveForegroundPackage(service, event)?.let { _foregroundPackage.value = it }
        }

        private fun logForegroundFromEvent(event: AccessibilityEvent?) {
            val service = serviceRef?.get() ?: return
            val packageName = resolveForegroundPackage(service, event) ?: return

            val now = System.currentTimeMillis()
            val changed = packageName != lastForegroundPackageLogged
            val throttled = now - lastForegroundLogAtMs < foregroundLogThrottleMs
            if (!changed && throttled) return

            lastForegroundPackageLogged = packageName
            lastForegroundLogAtMs = now
            val className = event?.className?.toString().orEmpty()
            val eventType = event?.eventType ?: -1
            Log.i(tag, "Foreground app pkg=$packageName class=$className eventType=$eventType")
        }

        private fun resolveForegroundPackage(
            service: ModeAccessibilityService,
            event: AccessibilityEvent?,
        ): String? {
            val rootPackage = service.rootInActiveWindow
                ?.packageName
                ?.toString()
                ?.takeIf { it.isNotBlank() }
            if (rootPackage != null) {
                return rootPackage
            }

            val eventPackage = event?.packageName?.toString()?.takeIf { it.isNotBlank() }
            return when (event?.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> eventPackage
                else -> _foregroundPackage.value ?: eventPackage
            }
        }
    }
}
