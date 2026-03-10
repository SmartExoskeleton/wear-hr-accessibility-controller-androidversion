// Created by ravishan_n on 2026-03-10
package com.example.heartratemonitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

class ModeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceRef = WeakReference(this)
        Log.i(tag, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        logForegroundFromEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serviceRef = null
        super.onDestroy()
    }

    companion object {
        private const val tag = "HR_ACCESS"
        private var serviceRef: WeakReference<ModeAccessibilityService>? = null
        private const val maxAttempts = 3
        private const val debugNodeProbe = true
        private var lastForegroundPackageLogged: String? = null
        private var lastForegroundLogAtMs: Long = 0L
        private const val foregroundLogThrottleMs = 1500L

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

        private fun logForegroundFromEvent(event: AccessibilityEvent?) {
            val service = serviceRef?.get() ?: return
            val eventPackage = event?.packageName?.toString()?.takeIf { it.isNotBlank() }
            val rootPackage = service.rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }
            val packageName = eventPackage ?: rootPackage ?: return

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
    }
}
