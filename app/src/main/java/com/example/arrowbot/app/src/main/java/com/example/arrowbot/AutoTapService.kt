package com.example.arrowbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

/**
 * Loop: screenshot -> game or ad? -> detect arrows -> solve -> tap in order.
 * During ads it only waits and never taps.
 */
class AutoTapService : AccessibilityService() {

    companion object {
        var instance: AutoTapService? = null
        var statusListener: ((String) -> Unit)? = null
        var logListener: ((String) -> Unit)? = null
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopBot() }
    override fun onDestroy() { stopBot(); instance = null; super.onDestroy() }

    private fun status(s: String) { statusListener?.invoke(s) }
    private fun say(m: String) { logListener?.invoke(m) }

    fun startBot() {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastState = ""
            while (isActive) {
                val shot = screenshot()
                if (shot == null) { delay(1000); continue }

                if (!isGameVisible(shot)) {
                    if (lastState != "ad") say("Ad or loading screen: waiting.")
                    lastState = "ad"; status("ad")
                    delay(1000)
                    continue
                }
                if (lastState != "game") say("Game screen detected.")
                lastState = "game"; status("game")

                val arrows = ArrowDetector.detect(shot)
                if (arrows.isEmpty()) {
                    say("Arrow detector not built yet: nothing to tap.")
                    delay(3000)
                    continue
                }
                val order = Solver.solve(arrows)
                if (order == null) { say("No solution found, retrying."); delay(1500); continue }

                status("solving")
                for (id in order) {
                    val check = screenshot() ?: break
                    if (!isGameVisible(check)) { say("Ad popped up: pausing."); break }
                    val a = arrows.first { it.id == id }
                    tap(a.centerX, a.centerY)
                    delay(350)
                }
                delay(1500)
            }
        }
    }

    fun stopBot() { job?.cancel(); status("stopped") }

    /** Game screen = the three red hearts under "Level N" are visible. */
    private fun isGameVisible(bmp: Bitmap): Boolean {
        val y = (bmp.height * 0.09).toInt().coerceIn(0, bmp.height - 1)
        var redHits = 0
        for (fx in listOf(0.43f, 0.50f, 0.57f)) {
            val x = (bmp.width * fx).toInt().coerceIn(0, bmp.width - 1)
            val p = bmp.getPixel(x, y)
            if (Color.red(p) > 200 && Color.green(p) < 90 && Color.blue(p) < 110) redHits++
        }
        return redHits >= 2
    }

    private suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    cont.resume(copy) {}
                }
                override fun onFailure(errorCode: Int) { cont.resume(null) {} }
            })
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}

data class DetectedArrow(
    val id: Int,
    val cells: List<Pair<Int, Int>>,   // tail -> head
    val direction: Char,               // U D L R
    val centerX: Float,
    val centerY: Float,
    val rows: Int = 0,
    val cols: Int = 0
)

object ArrowDetector {
    /** TODO next step: threshold dark strokes, split into connected components,
     *  find arrowhead end + direction, map pixels to grid cells. */
    fun detect(bmp: Bitmap): List<DetectedArrow> = emptyList()
}

object Solver {
    private val dirs = mapOf('U' to (-1 to 0), 'D' to (1 to 0), 'L' to (0 to -1), 'R' to (0 to 1))

    /** Port of solver.py: remove any arrow whose head ray is clear. */
    fun solve(arrows: List<DetectedArrow>): List<Int>? {
        val rows = arrows.maxOf { it.rows }
        val cols = arrows.maxOf { it.cols }
        val remaining = arrows.associateBy { it.id }.toMutableMap()
        val order = mutableListOf<Int>()
        while (remaining.isNotEmpty()) {
            val occ = HashMap<Pair<Int, Int>, Int>()
            for (a in remaining.values) for (c in a.cells) occ[c] = a.id
            var moved = false
            for ((id, a) in remaining) {
                val (dr, dc) = dirs.getValue(a.direction)
                var (r, c) = a.cells.last()
                var blocked = false
                r += dr; c += dc
                while (r in 0 until rows && c in 0 until cols) {
                    val o = occ[r to c]
                    if (o != null && o != id) { blocked = true; break }
                    r += dr; c += dc
                }
                if (!blocked) { order.add(id); remaining.remove(id); moved = true; break }
            }
            if (!moved) return null
        }
        return order
    }
}
