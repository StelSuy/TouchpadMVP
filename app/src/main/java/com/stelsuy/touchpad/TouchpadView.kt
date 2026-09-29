package com.stelsuy.touchpad

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    var onMove: ((dx: Float, dy: Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    init { isClickable = true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.x; val y = event.y
                val dx = x - lastX; val dy = y - lastY
                if (abs(x - downX) > 8 || abs(y - downY) > 8) moved = true
                if (event.pointerCount == 1 && (dx != 0f || dy != 0f)) onMove?.invoke(dx, dy)
                lastX = x; lastY = y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTap?.invoke()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
