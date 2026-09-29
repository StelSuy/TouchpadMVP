package com.stelsuy.touchpad

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** Тонкая обёртка над [GestureEngine]: переводит MotionEvent в вызовы движка. */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Куда уходят распознанные жесты. */
    var output: GestureEngine.Output? = null

    var settings: Settings = Settings()
        set(value) {
            field = value
            engine.settings = value
        }

    private val tickRunnable: Runnable = Runnable { engine.tick(SystemClock.uptimeMillis()) }

    private val proxy: GestureEngine.Output = object : GestureEngine.Output {
        override fun move(dx: Float, dy: Float) { output?.move(dx, dy) }
        override fun button(button: Int, down: Boolean) { output?.button(button, down) }
        override fun click(button: Int) { output?.click(button) }
        override fun scroll(dx: Float, dy: Float) { output?.scroll(dx, dy) }
        override fun zoom(steps: Int) { output?.zoom(steps) }
        override fun swipe(direction: Int) { output?.swipe(direction) }
        override fun haptic(strong: Boolean) {
            if (!settings.haptics) return
            performHapticFeedback(
                if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
            )
        }
        override fun requestTick(delayMs: Long) {
            removeCallbacks(tickRunnable)
            postDelayed(tickRunnable, delayMs)
        }
    }

    private val engine: GestureEngine = GestureEngine(settings, resources.displayMetrics.density, proxy)

    private val xs = FloatArray(MAX_POINTERS)
    private val ys = FloatArray(MAX_POINTERS)

    init { isClickable = true }

    /** Копирует координаты активных пальцев, пропуская [skipIndex]. Возвращает их число. */
    private fun fill(event: MotionEvent, skipIndex: Int = -1): Int {
        var k = 0
        for (i in 0 until minOf(event.pointerCount, MAX_POINTERS)) {
            if (i == skipIndex) continue
            xs[k] = event.getX(i); ys[k] = event.getY(i); k++
        }
        return k
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val t = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                engine.onDown(t, event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> engine.onPointerChange(t, fill(event), xs, ys)
            MotionEvent.ACTION_POINTER_UP -> engine.onPointerChange(t, fill(event, event.actionIndex), xs, ys)
            MotionEvent.ACTION_MOVE -> engine.onMove(t, fill(event), xs, ys)
            MotionEvent.ACTION_UP -> engine.onUp(t)
            MotionEvent.ACTION_CANCEL -> engine.onCancel()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        removeCallbacks(tickRunnable)
        engine.onCancel()   // если ушли с экрана посреди перетаскивания — отпускаем кнопку
        super.onDetachedFromWindow()
    }

    private companion object { const val MAX_POINTERS = 10 }
}
