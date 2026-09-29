package com.stelsuy.touchpad

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Распознавание жестов тачпада. Не зависит от Android: получает координаты пальцев
 * и вызывает [Output]. Благодаря этому логику можно проверять обычными тестами.
 *
 * Поддерживается:
 *  - 1 палец: движение курсора, тап = левый клик,
 *    тап + касание и движение = перетаскивание (выделение), долгое удержание = перетаскивание;
 *  - 2 пальца: тап = правый клик, скролл (вертикальный/горизонтальный), щипок = зум;
 *  - 3 пальца: тап = средний клик, свайп в 4 стороны = настраиваемые действия.
 */
class GestureEngine(
    var settings: Settings,
    private val density: Float,
    private val out: Output
) {
    interface Output {
        fun move(dx: Float, dy: Float)
        fun button(button: Int, down: Boolean)
        fun click(button: Int)
        fun scroll(dx: Float, dy: Float)
        fun zoom(steps: Int)
        fun swipe(direction: Int)
        fun haptic(strong: Boolean)
        /** Просит вызвать [tick] через delayMs миллисекунд (для долгого нажатия). */
        fun requestTick(delayMs: Long)
    }

    companion object {
        const val LEFT = 0
        const val RIGHT = 1
        const val MIDDLE = 2

        const val SWIPE_UP = 0
        const val SWIPE_DOWN = 1
        const val SWIPE_LEFT = 2
        const val SWIPE_RIGHT = 3

        const val TAP_MS = 250L
        const val MULTI_TAP_MS = 400L
        const val DOUBLE_TAP_MS = 300L
        const val LONG_PRESS_MS = 450L
    }

    private val slop = 8f * density
    private val multiSlop = 12f * density
    private val zoomStepPx = 28f * density
    private val swipeMinPx = 60f * density
    private val tapDragRadius = 48f * density

    private val MODE_NONE = 0
    private val MODE_SCROLL = 1
    private val MODE_ZOOM = 2

    // состояние жеста
    private var startTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var maxPointers = 0
    private var pointers = 0
    private var multiTouch = false
    private var multiMoved = false
    private var dragging = false
    private var tapDragCandidate = false
    private var longPressArmed = false
    private var mode = MODE_NONE
    private var totalX = 0f
    private var totalY = 0f
    private var totalPinch = 0f
    private var zoomAcc = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var lastDist = 0f

    // информация о последнем тапе (для tap-and-drag)
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // ------------------------------------------------------------------ события

    fun onDown(t: Long, x: Float, y: Float) {
        startTime = t
        startX = x; startY = y
        lastX = x; lastY = y
        moved = false
        multiTouch = false
        multiMoved = false
        maxPointers = 1
        pointers = 1
        mode = MODE_NONE
        totalX = 0f; totalY = 0f; totalPinch = 0f; zoomAcc = 0f
        dragging = false

        tapDragCandidate = settings.tapDrag && lastTapTime > 0 &&
            t - lastTapTime <= DOUBLE_TAP_MS &&
            hypot(x - lastTapX, y - lastTapY) <= tapDragRadius

        longPressArmed = settings.longPressDrag
        if (longPressArmed) out.requestTick(LONG_PRESS_MS)
    }

    /** Палец добавился или убрался. xs/ys содержат только оставшиеся активные пальцы. */
    fun onPointerChange(t: Long, n: Int, xs: FloatArray, ys: FloatArray) {
        val increased = n > pointers
        pointers = n
        maxPointers = max(maxPointers, n)
        if (n >= 2) multiTouch = true
        longPressArmed = false
        tapDragCandidate = false
        if (n <= 0) return

        if (increased) {
            totalX = 0f; totalY = 0f; totalPinch = 0f; zoomAcc = 0f
            mode = MODE_NONE
        }
        lastCx = centroidX(n, xs); lastCy = centroidY(n, ys)
        lastDist = if (n >= 2) dist(xs, ys) else 0f
        if (n == 1) { lastX = xs[0]; lastY = ys[0] }
    }

    fun onMove(t: Long, n: Int, xs: FloatArray, ys: FloatArray) {
        if (n <= 0) return
        if (n == 1 && !multiTouch) {
            oneFingerMove(xs[0], ys[0])
            return
        }
        if (n < 2) return   // остался один палец после многопальцевого жеста — курсор не двигаем

        val cx = centroidX(n, xs); val cy = centroidY(n, ys)
        val dcx = cx - lastCx; val dcy = cy - lastCy
        val d = dist(xs, ys)
        val dd = d - lastDist
        lastCx = cx; lastCy = cy; lastDist = d

        totalX += dcx; totalY += dcy; totalPinch += dd
        val net = hypot(totalX, totalY)
        if (net > multiSlop || abs(totalPinch) > multiSlop) multiMoved = true

        if (n == 2) twoFingerMove(dcx, dcy, dd, net)
    }

    fun onUp(t: Long) {
        longPressArmed = false
        val duration = t - startTime

        if (dragging) {
            out.button(LEFT, false)
            dragging = false
            lastTapTime = 0
            reset()
            return
        }

        when (maxPointers) {
            1 -> if (!moved && duration <= TAP_MS && settings.tapClick) {
                out.click(LEFT)
                out.haptic(false)
                lastTapTime = t; lastTapX = startX; lastTapY = startY
            } else {
                lastTapTime = 0
            }
            2 -> {
                lastTapTime = 0
                if (mode == MODE_NONE && !multiMoved && duration <= MULTI_TAP_MS && settings.twoFingerTap) {
                    out.click(RIGHT); out.haptic(true)
                }
            }
            3 -> {
                lastTapTime = 0
                if (!multiMoved && duration <= MULTI_TAP_MS) {
                    if (settings.threeFingerTap) { out.click(MIDDLE); out.haptic(true) }
                } else if (settings.threeFingerSwipe) {
                    detectSwipe()
                }
            }
            else -> lastTapTime = 0
        }
        reset()
    }

    fun onCancel() {
        longPressArmed = false
        if (dragging) out.button(LEFT, false)
        dragging = false
        lastTapTime = 0
        reset()
    }

    /** Вызывается по таймеру из [Output.requestTick]. */
    fun tick(t: Long) {
        if (longPressArmed && !moved && !multiTouch && !dragging && pointers == 1 &&
            t - startTime >= LONG_PRESS_MS
        ) {
            longPressArmed = false
            dragging = true
            out.button(LEFT, true)
            out.haptic(true)
        }
    }

    // ------------------------------------------------------------------ внутреннее

    private fun oneFingerMove(x: Float, y: Float) {
        var dx = x - lastX
        var dy = y - lastY
        if (!moved && hypot(x - startX, y - startY) > slop) {
            moved = true
            longPressArmed = false
            dx = x - startX          // отдаём накопленное смещение, чтобы не терять его
            dy = y - startY
            if (tapDragCandidate && !dragging) {
                dragging = true
                out.button(LEFT, true)
                out.haptic(false)
            }
        }
        lastX = x; lastY = y
        if (moved && (dx != 0f || dy != 0f)) out.move(dx, dy)
    }

    private fun twoFingerMove(dcx: Float, dcy: Float, dd: Float, net: Float) {
        when (mode) {
            MODE_NONE -> {
                if (settings.pinchZoom && abs(totalPinch) > multiSlop * 1.3f && abs(totalPinch) > net * 1.2f) {
                    mode = MODE_ZOOM
                    zoomAcc = totalPinch          // накопленное до захвата режима
                    emitZoom()
                } else if (settings.twoFingerScroll && net > slop * 1.2f) {
                    mode = MODE_SCROLL
                    out.scroll(totalX, totalY)    // накопленное до захвата режима
                }
            }
            MODE_SCROLL -> out.scroll(dcx, dcy)
            MODE_ZOOM -> {
                zoomAcc += dd
                emitZoom()
            }
        }
    }

    private fun emitZoom() {
        val steps = (zoomAcc / zoomStepPx).toInt()
        if (steps != 0) {
            out.zoom(steps)
            zoomAcc -= steps * zoomStepPx
        }
    }

    private fun detectSwipe() {
        if (max(abs(totalX), abs(totalY)) < swipeMinPx) return
        val dir = if (abs(totalX) > abs(totalY)) {
            if (totalX > 0) SWIPE_RIGHT else SWIPE_LEFT
        } else {
            if (totalY > 0) SWIPE_DOWN else SWIPE_UP
        }
        out.swipe(dir)
        out.haptic(true)
    }

    private fun reset() {
        pointers = 0
        maxPointers = 0
        multiTouch = false
        multiMoved = false
        moved = false
        mode = MODE_NONE
        tapDragCandidate = false
    }

    private fun centroidX(n: Int, xs: FloatArray): Float { var s = 0f; for (i in 0 until n) s += xs[i]; return s / n }
    private fun centroidY(n: Int, ys: FloatArray): Float { var s = 0f; for (i in 0 until n) s += ys[i]; return s / n }
    private fun dist(xs: FloatArray, ys: FloatArray) = hypot(xs[1] - xs[0], ys[1] - ys[0])
}
