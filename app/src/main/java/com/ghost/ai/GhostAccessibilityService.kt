package com.ghost.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ActionOutcome(val ok: Boolean, val detail: String)

/**
 * Eyes and hands of Ghost: reads the visible UI tree for Gemini and performs the
 * actions Gemini chooses (click, type, scroll, back/home, raw gestures).
 */
class GhostAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing to do: the screen is read on demand when a voice command arrives.
    }

    override fun onInterrupt() {
        // No ongoing feedback to interrupt.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ reading

    /** Compact text description of everything visible, sent to Gemini as context. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "SCREEN: unavailable (no active window readable)"
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes, 0)

        val sb = StringBuilder()
        sb.append("APP: ").append(root.packageName ?: "unknown").append('\n')
        sb.append("NODES:\n")

        var shown = 0
        val seen = HashSet<String>()
        for (node in nodes) {
            if (shown >= MAX_LINES) {
                sb.append("... (more elements not shown)\n")
                break
            }
            val label = labelOf(node)
            val interactive = node.isClickable || node.isEditable || node.isScrollable ||
                node.isCheckable || node.isLongClickable
            if (label.isEmpty() && !interactive) continue
            if (!node.isVisibleToUser) continue

            val flags = ArrayList<String>(4)
            if (node.isEditable) flags.add("EDITABLE")
            if (node.isClickable) flags.add("CLICKABLE")
            if (node.isScrollable) flags.add("SCROLLABLE")
            if (node.isCheckable) flags.add(if (node.isChecked) "CHECKED" else "UNCHECKED")
            if (node.isFocused) flags.add("FOCUSED")

            val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            val line = buildString {
                append("- ")
                if (label.isNotEmpty()) append('"').append(label.take(MAX_LABEL)).append('"') else append("(no text)")
                if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
                if (id.isNotEmpty() && label.isEmpty()) append(" id=").append(id)
            }
            if (!seen.add(line)) continue
            sb.append(line).append('\n')
            shown++
        }
        if (shown == 0) sb.append("(no readable elements; the app may block accessibility)\n")
        return sb.toString()
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        out.add(node)
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) return text.replace('\n', ' ')
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.isNotEmpty()) return desc.replace('\n', ' ')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()?.trim().orEmpty()
            if (hint.isNotEmpty()) return hint.replace('\n', ' ')
        }
        return ""
    }

    // ------------------------------------------------------------------ actions

    /** Clicks the best node matching [target] by text, description, hint or view id. */
    suspend fun clickByText(target: String): ActionOutcome {
        val query = target.trim()
        if (query.isEmpty()) return ActionOutcome(false, "No target given")
        val root = rootInActiveWindow ?: return ActionOutcome(false, "Screen is not readable")

        val node = findBestMatch(root, query)
            ?: return ActionOutcome(false, "Could not find \"$query\" on screen")

        // 1) Click the node itself or the nearest clickable ancestor.
        var clickable: AccessibilityNodeInfo? = node
        var hops = 0
        while (clickable != null && !clickable.isClickable && hops < 8) {
            clickable = clickable.parent
            hops++
        }
        if (clickable != null && clickable.isClickable && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return ActionOutcome(true, "Tapped \"$query\"")
        }

        // 2) Fall back to a real gesture at the centre of the node.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && tap(bounds.exactCenterX(), bounds.exactCenterY())) {
            return ActionOutcome(true, "Tapped \"$query\" by gesture")
        }
        return ActionOutcome(false, "\"$query\" was found but could not be tapped")
    }

    /** Types [text] into an editable field (matched by [target] if given, else focused/first). */
    suspend fun inputText(text: String, target: String): ActionOutcome {
        val root = rootInActiveWindow ?: return ActionOutcome(false, "Screen is not readable")

        var field: AccessibilityNodeInfo? = null

        // Focused input field wins unless the model asked for a specific one.
        if (target.isBlank()) {
            field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        }
        if (field == null && target.isNotBlank()) {
            val match = findBestMatch(root, target)
            field = when {
                match == null -> null
                match.isEditable -> match
                else -> findEditableNear(match)
            }
        }
        if (field == null) {
            val nodes = ArrayList<AccessibilityNodeInfo>()
            collect(root, nodes, 0)
            field = nodes.firstOrNull { it.isEditable && it.isVisibleToUser }
        }
        if (field == null) return ActionOutcome(false, "No text field found on screen")

        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(120)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            ActionOutcome(true, "Typed \"$text\"")
        } else {
            ActionOutcome(false, "The text field refused the text")
        }
    }

    /** Presses the keyboard action key (Enter / Search / Send) on the focused field. */
    fun pressEnter(): ActionOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val ok = focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                return ActionOutcome(ok, if (ok) "Pressed Enter" else "Enter was not accepted")
            }
            return ActionOutcome(false, "No focused field for Enter")
        }
        return ActionOutcome(false, "Enter key is not supported below Android 11; tap the search/send button instead")
    }

    /** Scrolls content. down = reveal content further down the page. */
    suspend fun scroll(direction: String): ActionOutcome {
        val dir = direction.lowercase().ifBlank { "down" }
        val root = rootInActiveWindow
        if (root != null) {
            val nodes = ArrayList<AccessibilityNodeInfo>()
            collect(root, nodes, 0)
            val scrollable = nodes
                .filter { it.isScrollable && it.isVisibleToUser }
                .maxByOrNull {
                    val r = Rect()
                    it.getBoundsInScreen(r)
                    r.width().toLong() * r.height()
                }
            if (scrollable != null) {
                val actionId = when (dir) {
                    "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                    "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                    else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                }
                if (scrollable.performAction(actionId)) {
                    return ActionOutcome(true, "Scrolled $dir")
                }
                val fallback = if (dir == "up" || dir == "left") {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                if (scrollable.performAction(fallback)) {
                    return ActionOutcome(true, "Scrolled $dir")
                }
            }
        }

        // No scrollable node (or it refused): swipe with a real gesture.
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val ok = when (dir) {
            "up" -> swipe(w / 2, h * 0.30f, w / 2, h * 0.70f)
            "left" -> swipe(w * 0.25f, h / 2, w * 0.75f, h / 2)
            "right" -> swipe(w * 0.75f, h / 2, w * 0.25f, h / 2)
            else -> swipe(w / 2, h * 0.70f, w / 2, h * 0.30f)
        }
        return ActionOutcome(ok, if (ok) "Scrolled $dir by swipe" else "Could not scroll")
    }

    fun goBack(): ActionOutcome {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        return ActionOutcome(ok, if (ok) "Went back" else "Back was not accepted")
    }

    fun goHome(): ActionOutcome {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        return ActionOutcome(ok, if (ok) "Went home" else "Home was not accepted")
    }

    // ----------------------------------------------------------------- gestures

    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 60L)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, durationMs)
    }

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val accepted = try {
                dispatchGesture(gesture, callback, null)
            } catch (e: Exception) {
                false
            }
            if (!accepted && cont.isActive) cont.resume(false)
        }

    // ------------------------------------------------------------------ matching

    private fun findBestMatch(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes, 0)

        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (node in nodes) {
            if (!node.isVisibleToUser) continue
            val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim()?.lowercase().orEmpty()
            } else ""
            val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()

            var score = 0
            for (candidate in listOf(text, desc, hint)) {
                if (candidate.isEmpty()) continue
                score = maxOf(
                    score,
                    when {
                        candidate == q -> 100
                        candidate.startsWith(q) -> 70
                        candidate.contains(q) -> 50
                        q.length >= 4 && q.contains(candidate) && candidate.length >= 3 -> 25
                        else -> 0
                    }
                )
            }
            if (score == 0 && id.isNotEmpty() && id == q.replace(' ', '_')) score = 40
            if (score == 0) continue

            // Prefer things the user can actually act on.
            if (node.isClickable || node.isEditable) score += 10
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    private fun findEditableNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var hops = 0
        while (current != null && hops < 4) {
            val nodes = ArrayList<AccessibilityNodeInfo>()
            collect(current, nodes, 0)
            nodes.firstOrNull { it.isEditable && it.isVisibleToUser }?.let { return it }
            current = current.parent
            hops++
        }
        return null
    }

    companion object {
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 40
        private const val MAX_LINES = 180
        private const val MAX_LABEL = 120

        @Volatile
        var instance: GhostAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
