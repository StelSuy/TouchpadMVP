package com.stelsuy.touchpad

/** Все пользовательские настройки. Чистый Kotlin — без Android-классов, чтобы можно было тестировать. */
data class Settings(
    // --- Жесты ---
    val tapClick: Boolean = true,
    val tapDrag: Boolean = true,            // тап + сразу касание и движение = перетаскивание / выделение
    val longPressDrag: Boolean = true,      // удержание пальца = зажать левую кнопку
    val twoFingerTap: Boolean = true,       // тап двумя пальцами = правый клик
    val threeFingerTap: Boolean = true,     // тап тремя пальцами = средний клик
    val twoFingerScroll: Boolean = true,
    val naturalScroll: Boolean = true,
    val horizontalScroll: Boolean = true,
    val pinchZoom: Boolean = true,
    val threeFingerSwipe: Boolean = true,
    val swipeUp: String = "task_view",
    val swipeDown: String = "desktop",
    val swipeLeft: String = "desk_prev",
    val swipeRight: String = "desk_next",

    // --- Указатель ---
    val sensitivity: Float = 1.8f,
    val accel: Boolean = true,
    val accelLevel: Int = 4,                // 1..10
    val scrollSpeed: Float = 1.0f,
    val haptics: Boolean = true,

    // --- Интерфейс ---
    val showClickButtons: Boolean = true,
    val showQuickBar: Boolean = true,
    val keepScreenOn: Boolean = true,
    val volumeKeys: Boolean = false,
    val autoConnect: Boolean = false,

    // --- Соединение ---
    val password: String = ""
) {
    fun swipeAction(dir: Int): String = when (dir) {
        GestureEngine.SWIPE_UP -> swipeUp
        GestureEngine.SWIPE_DOWN -> swipeDown
        GestureEngine.SWIPE_LEFT -> swipeLeft
        else -> swipeRight
    }
}

/** Соответствие «действие из настроек» → комбинация клавиш для ПК. */
object SwipeActions {
    val hotkeys = mapOf(
        "task_view" to "WIN+TAB",
        "desktop" to "WIN+D",
        "alt_tab" to "ALT+TAB",
        "alt_shift_tab" to "ALT+SHIFT+TAB",
        "desk_next" to "CTRL+WIN+RIGHT",
        "desk_prev" to "CTRL+WIN+LEFT",
        "back" to "ALT+LEFT",
        "forward" to "ALT+RIGHT",
        "next_tab" to "CTRL+TAB",
        "prev_tab" to "CTRL+SHIFT+TAB",
        "maximize" to "WIN+UP",
        "minimize" to "WIN+DOWN",
        "close_window" to "ALT+F4",
        "screenshot" to "WIN+SHIFT+S",
        "lock" to "WIN+L",
        "play_pause" to "MEDIA_PLAY_PAUSE"
    )

    fun hotkeyFor(action: String): String? = hotkeys[action]
}
