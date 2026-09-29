package com.stelsuy.touchpad

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val client = MouseClient()
    private val connected = AtomicBoolean(false)
    private var connecting = false
    private var userDisconnected = false
    private var settings = Settings()

    private lateinit var root: View
    private lateinit var status: TextView
    private lateinit var ip: EditText
    private lateinit var port: EditText
    private lateinit var connectButton: Button
    private lateinit var touchpad: TouchpadView
    private lateinit var quickScroll: HorizontalScrollView
    private lateinit var clickRow: LinearLayout
    private lateinit var kbInput: EditText

    /** Жесты → команды протокола. */
    private val output = object : GestureEngine.Output {
        override fun move(dx: Float, dy: Float) {
            if (!connected.get()) return
            val density = resources.displayMetrics.density
            var mult = settings.sensitivity
            if (settings.accel) {
                val speed = hypot(dx, dy) / density                       // dp за одно событие
                mult *= 1f + settings.accelLevel * 0.15f * min(speed / 6f, 3f)
            }
            client.send("MOVE", fmt(dx * mult), fmt(dy * mult))
        }

        override fun button(button: Int, down: Boolean) {
            if (connected.get()) client.send(if (down) "DOWN" else "UP", buttonName(button))
        }

        override fun click(button: Int) {
            if (connected.get()) client.send("CLICK", buttonName(button))
        }

        override fun scroll(dx: Float, dy: Float) {
            if (!connected.get()) return
            // 1 «щелчок» колеса на ~30dp движения пальцев при скорости 1.0
            val k = settings.scrollSpeed / (30f * resources.displayMetrics.density)
            val v = (if (settings.naturalScroll) dy else -dy) * k
            val h = if (!settings.horizontalScroll) 0f else (if (settings.naturalScroll) -dx else dx) * k
            client.send("SCROLL", fmt(h), fmt(v))
        }

        override fun zoom(steps: Int) {
            if (connected.get()) client.send("ZOOM", steps)
        }

        override fun swipe(direction: Int) {
            if (!connected.get()) return
            SwipeActions.hotkeyFor(settings.swipeAction(direction))?.let { client.send("HOTKEY", it) }
        }

        override fun haptic(strong: Boolean) {}          // вибро делает сам TouchpadView
        override fun requestTick(delayMs: Long) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        status = findViewById(R.id.status)
        ip = findViewById(R.id.ipInput)
        port = findViewById(R.id.portInput)
        connectButton = findViewById(R.id.connectButton)
        touchpad = findViewById(R.id.touchpad)
        quickScroll = findViewById(R.id.quickScroll)
        clickRow = findViewById(R.id.clickRow)
        kbInput = findViewById(R.id.kbInput)

        // Android 15 рисует edge-to-edge — отодвигаем UI от системных панелей и клавиатуры
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val pad = (14 * resources.displayMetrics.density).toInt()
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }

        val prefs = Prefs.of(this)
        ip.setText(prefs.getString(Prefs.LAST_IP, "192.168.1.100"))
        port.setText(prefs.getInt(Prefs.LAST_PORT, 5000).toString())

        client.onDisconnected = {
            runOnUiThread {
                if (connected.getAndSet(false)) {
                    status.text = "● Соединение потеряно"
                    connectButton.text = "ПОДКЛЮЧИТЬ"
                    if (settings.autoConnect && !userDisconnected) root.postDelayed({ startConnect() }, 1000)
                }
            }
        }

        connectButton.setOnClickListener {
            if (connected.get()) {
                userDisconnected = true
                client.close()
                connected.set(false)
                status.text = "● Не подключено"
                connectButton.text = "ПОДКЛЮЧИТЬ"
            } else {
                userDisconnected = false
                startConnect()
            }
        }

        findViewById<Button>(R.id.discoverButton).setOnClickListener { discover() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.helpButton).setOnClickListener { showHelp() }

        touchpad.output = output

        findViewById<Button>(R.id.leftButton).setOnClickListener { output.click(GestureEngine.LEFT) }
        findViewById<Button>(R.id.middleButton).setOnClickListener { output.click(GestureEngine.MIDDLE) }
        findViewById<Button>(R.id.rightButton).setOnClickListener { output.click(GestureEngine.RIGHT) }

        setupQuickBar()
        setupKeyboardInput()
    }

    override fun onStart() {
        super.onStart()
        applySettings()
        if (settings.autoConnect && !userDisconnected && !connected.get()) startConnect()
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ настройки

    private fun applySettings() {
        settings = Prefs.load(this)
        touchpad.settings = settings
        quickScroll.visibility = if (settings.showQuickBar) View.VISIBLE else View.GONE
        clickRow.visibility = if (settings.showClickButtons) View.VISIBLE else View.GONE
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ подключение

    private fun startConnect() {
        if (connecting || connected.get()) return
        val host = ip.text.toString().trim()
        if (host.isEmpty()) {
            status.text = "● Введите IP компьютера"
            return
        }
        val p = port.text.toString().toIntOrNull() ?: 5000
        Prefs.of(this).edit().putString(Prefs.LAST_IP, host).putInt(Prefs.LAST_PORT, p).apply()

        connecting = true
        status.text = "● Подключение..."
        client.connect(host, p, settings.password) { ok, message ->
            runOnUiThread {
                connecting = false
                connected.set(ok)
                status.text = message
                connectButton.text = if (ok) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ"
            }
        }
    }

    private fun discover() {
        val p = port.text.toString().toIntOrNull() ?: 5000
        status.text = "● Поиск ПК в сети..."
        MouseClient.discover(p) { foundIp, name ->
            runOnUiThread {
                if (foundIp != null) {
                    ip.setText(foundIp)
                    status.text = "● Найден ПК: ${name?.ifEmpty { foundIp } ?: foundIp}"
                    if (!connected.get()) { userDisconnected = false; startConnect() }
                } else {
                    status.text = "● ПК не найден. Запущен ли сервер? Введи IP вручную."
                }
            }
        }
    }

    // ------------------------------------------------------------------ быстрые клавиши

    private fun setupQuickBar() {
        val bar = findViewById<LinearLayout>(R.id.quickBar)
        val density = resources.displayMetrics.density

        fun add(label: String, action: () -> Unit) {
            val b = Button(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                setBackgroundResource(R.drawable.button_bg)
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                setOnClickListener {
                    action()
                    if (settings.haptics) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (44 * density).toInt())
            lp.marginEnd = (6 * density).toInt()
            bar.addView(b, lp)
        }
        fun key(label: String, combo: String) = add(label) { if (connected.get()) client.send("HOTKEY", combo) }

        add("Клавиатура") { toggleKeyboard() }
        key("Esc", "ESC"); key("Tab", "TAB"); key("⌫", "BACKSPACE"); key("Enter", "ENTER"); key("Del", "DELETE")
        key("←", "LEFT"); key("↑", "UP"); key("↓", "DOWN"); key("→", "RIGHT")
        key("Копировать", "CTRL+C"); key("Вставить", "CTRL+V"); key("Вырезать", "CTRL+X")
        key("Выделить всё", "CTRL+A"); key("Отменить", "CTRL+Z"); key("Повторить", "CTRL+Y")
        key("Alt+Tab", "ALT+TAB"); key("Win", "WIN"); key("Рабочий стол", "WIN+D")
        key("F5", "F5"); key("F11", "F11")
        key("Громкость −", "VOL_DOWN"); key("Громкость +", "VOL_UP"); key("Mute", "VOL_MUTE")
        key("⏮", "MEDIA_PREV"); key("⏯", "MEDIA_PLAY_PAUSE"); key("⏭", "MEDIA_NEXT")
        key("Скриншот", "WIN+SHIFT+S"); key("Блокировка", "WIN+L")
    }

    // ------------------------------------------------------------------ ввод текста

    private fun setupKeyboardInput() {
        kbInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD     // без подсказок/автозамены — символы уходят сразу
        kbInput.imeOptions = EditorInfo.IME_ACTION_SEND or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN

        kbInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) return
                val text = s.toString()
                s.clear()                 // поле всегда пустое → Backspace приходит как клавиша
                sendText(text)
            }
        })
        kbInput.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    if (event.action == KeyEvent.ACTION_DOWN && connected.get()) client.send("HOTKEY", "BACKSPACE")
                    true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && connected.get()) client.send("HOTKEY", "ENTER")
                    true
                }
                else -> false
            }
        }
        kbInput.setOnEditorActionListener { _, _, _ ->
            if (connected.get()) client.send("HOTKEY", "ENTER")
            true
        }
    }

    private fun sendText(text: String) {
        if (!connected.get()) return
        text.split("\n").forEachIndexed { i, part ->
            if (i > 0) client.send("HOTKEY", "ENTER")
            if (part.isNotEmpty()) client.send("TEXT", URLEncoder.encode(part, "UTF-8"))
        }
    }

    private fun toggleKeyboard() {
        val controller = WindowCompat.getInsetsController(window, kbInput)
        val visible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) {
            controller.hide(WindowInsetsCompat.Type.ime())
        } else {
            kbInput.requestFocus()
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }

    // ------------------------------------------------------------------ прочее

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (settings.volumeKeys && connected.get()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { client.send("HOTKEY", "VOL_UP"); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { client.send("HOTKEY", "VOL_DOWN"); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (settings.volumeKeys && connected.get() &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Жесты")
            .setMessage(
                "1 палец\n" +
                "• двигать — курсор\n" +
                "• тап — левый клик\n" +
                "• тап и сразу коснуться и вести — перетаскивание / выделение\n" +
                "• удержать палец — зажать левую кнопку (выделение), отпустить — отпустить\n\n" +
                "2 пальца\n" +
                "• тап — правый клик\n" +
                "• вести — прокрутка (вверх/вниз/в стороны)\n" +
                "• развести / свести — масштаб (Ctrl + колесо)\n\n" +
                "3 пальца\n" +
                "• тап — средний клик\n" +
                "• свайп вверх / вниз / влево / вправо — действия, которые задаются в настройках " +
                "(по умолчанию: обзор окон, рабочий стол, переключение рабочих столов)\n\n" +
                "Каждый жест можно отключить в настройках."
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun buttonName(button: Int) = when (button) {
        GestureEngine.RIGHT -> "RIGHT"
        GestureEngine.MIDDLE -> "MIDDLE"
        else -> "LEFT"
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)   // Locale.US: точка, а не запятая
}
