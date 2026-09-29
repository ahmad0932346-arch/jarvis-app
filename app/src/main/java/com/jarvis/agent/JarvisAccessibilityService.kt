package com.jarvis.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** The "hands and eyes": reads the UI tree and performs taps, typing, scrolling. */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
    }

    class Snapshot(val text: String, val interactive: Int, val pkg: String)

    @Volatile private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- screen size ----------
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val b = wm.maximumWindowMetrics.bounds
            return Pair(b.width(), b.height())
        }
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    // ---------- reading the screen ----------
    @Synchronized
    fun snapshot(): Snapshot {
        val root = rootInActiveWindow ?: return Snapshot("(screen not readable)", 0, "")
        val (sw, sh) = screenSize()
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        var interactive = 0

        fun walk(n: AccessibilityNodeInfo) {
            if (list.size >= 120) return
            if (!n.isVisibleToUser) return
            val label = sequenceOf(n.text, n.contentDescription, n.hintText)
                .map { it?.toString()?.trim() }
                .firstOrNull { !it.isNullOrEmpty() }
            val isInteractive = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable
            if (label != null || isInteractive) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val cx = (r.centerX() * 1000 / sw).coerceIn(0, 1000)
                val cy = (r.centerY() * 1000 / sh).coerceIn(0, 1000)
                sb.append('[').append(list.size).append("] ")
                sb.append(n.className?.toString()?.substringAfterLast('.') ?: "View")
                if (label != null) sb.append(" \"").append(label.take(80).replace("\n", " ")).append('"')
                if (n.isClickable) sb.append(" click")
                if (n.isEditable) sb.append(" edit")
                if (n.isScrollable) sb.append(" scroll")
                if (n.isChecked) sb.append(" checked")
                sb.append(" @").append(cx).append(',').append(cy).append('\n')
                list.add(n)
                if (isInteractive) interactive++
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
            }
        }
        walk(root)
        nodes = list
        val text = if (sb.isEmpty()) "(no readable elements)" else sb.toString()
        return Snapshot(text, interactive, root.packageName?.toString() ?: "")
    }

    // ---------- actions ----------
    suspend fun click(index: Int, long: Boolean): String {
        val n = nodes.getOrNull(index) ?: return "No element with index $index (the screen may have changed)"
        var t: AccessibilityNodeInfo? = n
        var depth = 0
        while (t != null && depth < 6) {
            val ok = if (long) t.isLongClickable else t.isClickable
            if (ok) {
                val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                if (t.performAction(action)) return "Clicked [$index]"
                break
            }
            t = t.parent
            depth++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        val x = r.centerX().toFloat()
        val y = r.centerY().toFloat()
        val ok = gesture(x, y, x, y, if (long) 700 else 60)
        return if (ok) "Tapped [$index] via gesture" else "Click on [$index] failed"
    }

    fun typeText(index: Int?, text: String): String {
        var target: AccessibilityNodeInfo? = index?.let { nodes.getOrNull(it) }
        if (target == null) target = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) target = nodes.firstOrNull { it.isEditable }
        if (target == null) return "No input field found. Click a text field first."
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed text" else "Typing failed"
    }

    fun pressKey(key: String): String = when (key.lowercase()) {
        "back" -> global(GLOBAL_ACTION_BACK)
        "home" -> global(GLOBAL_ACTION_HOME)
        "recents" -> global(GLOBAL_ACTION_RECENTS)
        "notifications" -> global(GLOBAL_ACTION_NOTIFICATIONS)
        "enter" -> enter()
        else -> "Unknown key '$key'. Use back, home, recents, notifications or enter."
    }

    private fun global(action: Int): String = if (performGlobalAction(action)) "OK" else "Failed"

    private fun enter(): String {
        if (Build.VERSION.SDK_INT >= 30) {
            val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return "Enter pressed"
            }
        }
        return "Enter is not available here; tap the search/send button instead."
    }

    suspend fun scroll(direction: String): String {
        val (w, h) = screenSize()
        val cx = w / 2f
        val cy = h / 2f
        // "down" = reveal content further down => finger swipes up
        val p: List<Float> = when (direction.lowercase()) {
            "down" -> listOf(cx, h * 0.72f, cx, h * 0.30f)
            "up" -> listOf(cx, h * 0.30f, cx, h * 0.72f)
            "right" -> listOf(w * 0.80f, cy, w * 0.20f, cy)
            "left" -> listOf(w * 0.20f, cy, w * 0.80f, cy)
            else -> return "Unknown direction '$direction'"
        }
        return if (gesture(p[0], p[1], p[2], p[3], 350)) "Scrolled $direction" else "Scroll failed"
    }

    suspend fun tapNorm(x: Float, y: Float): String {
        val (w, h) = screenSize()
        val px = x / 1000f * w
        val py = y / 1000f * h
        return if (gesture(px, py, px, py, 60)) "Tapped" else "Tap failed"
    }

    suspend fun swipeNorm(x1: Float, y1: Float, x2: Float, y2: Float): String {
        val (w, h) = screenSize()
        val ok = gesture(x1 / 1000f * w, y1 / 1000f * h, x2 / 1000f * w, y2 / 1000f * h, 350)
        return if (ok) "Swiped" else "Swipe failed"
    }

    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x1, y1)
                if (x1 != x2 || y1 != y2) lineTo(x2, y2)
            }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceAtLeast(1)))
                .build()
            val dispatched = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    // ---------- screenshot (Android 11+) ----------
    suspend fun screenshot(): String? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < 30) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val hb = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                    if (soft == null) {
                        if (cont.isActive) cont.resume(null)
                        return
                    }
                    val nw = 720
                    val nh = (soft.height * nw.toFloat() / soft.width).toInt()
                    val scaled = Bitmap.createScaledBitmap(soft, nw, nh, true)
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    if (cont.isActive) cont.resume(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }
}
