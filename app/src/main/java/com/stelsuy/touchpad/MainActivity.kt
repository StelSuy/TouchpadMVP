package com.stelsuy.touchpad

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val client = MouseClient()
    private val connected = AtomicBoolean(false)

    // Чувствительность курсора
    private val sensitivity = 1.8f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 15 (targetSdk 35) рисует edge-to-edge — отодвигаем UI от системных панелей
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (14 * resources.displayMetrics.density).toInt()
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }

        val status = findViewById<TextView>(R.id.status)
        val ip = findViewById<EditText>(R.id.ipInput)
        val port = findViewById<EditText>(R.id.portInput)
        val connect = findViewById<Button>(R.id.connectButton)
        val touchpad = findViewById<TouchpadView>(R.id.touchpad)
        val left = findViewById<Button>(R.id.leftButton)
        val right = findViewById<Button>(R.id.rightButton)

        client.onDisconnected = {
            runOnUiThread {
                if (connected.getAndSet(false)) {
                    status.text = "● Соединение потеряно"
                    connect.text = "ПОДКЛЮЧИТЬ"
                }
            }
        }

        connect.setOnClickListener {
            if (connected.get()) {
                client.close()
                connected.set(false)
                status.text = "● Не подключено"
                connect.text = "ПОДКЛЮЧИТЬ"
            } else {
                val host = ip.text.toString().trim()
                val p = port.text.toString().toIntOrNull() ?: 5000
                status.text = "● Подключение..."
                client.connect(host, p) { ok, message ->
                    runOnUiThread {
                        connected.set(ok)
                        status.text = message
                        connect.text = if (ok) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ"
                    }
                }
            }
        }

        touchpad.onMove = { dx, dy ->
            if (connected.get()) {
                // Locale.US обязателен: в русской локали получилось бы "4,20" вместо "4.20"
                client.send(
                    "MOVE",
                    String.format(Locale.US, "%.2f", dx * sensitivity),
                    String.format(Locale.US, "%.2f", dy * sensitivity)
                )
            }
        }
        touchpad.onTap = { if (connected.get()) client.send("CLICK", "LEFT") }
        left.setOnClickListener { if (connected.get()) client.send("CLICK", "LEFT") }
        right.setOnClickListener { if (connected.get()) client.send("CLICK", "RIGHT") }
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }
}
