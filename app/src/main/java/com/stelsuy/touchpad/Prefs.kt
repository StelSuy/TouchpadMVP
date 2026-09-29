package com.stelsuy.touchpad

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object Prefs {
    const val LAST_IP = "last_ip"
    const val LAST_PORT = "last_port"

    fun of(ctx: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun load(ctx: Context): Settings {
        val p = of(ctx)
        val d = Settings()
        return Settings(
            tapClick = p.getBoolean("tap_click", d.tapClick),
            tapDrag = p.getBoolean("tap_drag", d.tapDrag),
            longPressDrag = p.getBoolean("long_press_drag", d.longPressDrag),
            twoFingerTap = p.getBoolean("two_finger_tap", d.twoFingerTap),
            threeFingerTap = p.getBoolean("three_finger_tap", d.threeFingerTap),
            twoFingerScroll = p.getBoolean("two_finger_scroll", d.twoFingerScroll),
            naturalScroll = p.getBoolean("natural_scroll", d.naturalScroll),
            horizontalScroll = p.getBoolean("horizontal_scroll", d.horizontalScroll),
            pinchZoom = p.getBoolean("pinch_zoom", d.pinchZoom),
            threeFingerSwipe = p.getBoolean("three_finger_swipe", d.threeFingerSwipe),
            swipeUp = p.getString("swipe_up", d.swipeUp) ?: d.swipeUp,
            swipeDown = p.getString("swipe_down", d.swipeDown) ?: d.swipeDown,
            swipeLeft = p.getString("swipe_left", d.swipeLeft) ?: d.swipeLeft,
            swipeRight = p.getString("swipe_right", d.swipeRight) ?: d.swipeRight,

            sensitivity = p.getInt("sensitivity", 18) / 10f,
            accel = p.getBoolean("accel", d.accel),
            accelLevel = p.getInt("accel_level", d.accelLevel),
            scrollSpeed = p.getInt("scroll_speed", 10) / 10f,
            haptics = p.getBoolean("haptics", d.haptics),

            showClickButtons = p.getBoolean("show_click_buttons", d.showClickButtons),
            showQuickBar = p.getBoolean("show_quick_bar", d.showQuickBar),
            keepScreenOn = p.getBoolean("keep_screen_on", d.keepScreenOn),
            volumeKeys = p.getBoolean("volume_keys", d.volumeKeys),
            autoConnect = p.getBoolean("auto_connect", d.autoConnect),

            password = p.getString("password", "") ?: ""
        )
    }
}
