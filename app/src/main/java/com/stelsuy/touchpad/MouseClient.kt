package com.stelsuy.touchpad

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors

class MouseClient {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null

    /** Вызывается (из фонового потока), если соединение оборвалось при отправке. */
    var onDisconnected: (() -> Unit)? = null

    /**
     * Подключение + рукопожатие: HELLO|пароль → OK|имя_пк  или  AUTH_FAIL.
     * onResult вызывается из фонового потока.
     */
    fun connect(host: String, port: Int, password: String, onResult: (Boolean, String) -> Unit) {
        executor.execute {
            try {
                closeInternal()
                val s = Socket()
                s.tcpNoDelay = true      // без буферизации Нейгла — иначе курсор лагает
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), 2500)
                s.soTimeout = 3000

                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                w.write("HELLO|" + URLEncoder.encode(password, "UTF-8") + "\n")
                w.flush()

                val reply = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()
                if (reply == null || !reply.startsWith("OK")) {
                    s.close()
                    onResult(false, if (reply == "AUTH_FAIL") "● Неверный пароль" else "● Сервер не ответил")
                    return@execute
                }
                s.soTimeout = 0
                socket = s
                writer = w
                val name = reply.substringAfter('|', "")
                onResult(true, "● Подключено: " + name.ifEmpty { "$host:$port" })
            } catch (e: Exception) {
                closeInternal()
                onResult(false, "● Ошибка: ${e.message ?: "не удалось подключиться"}")
            }
        }
    }

    fun send(type: String, vararg values: Any) {
        if (writer == null) return
        val line = buildString {
            append(type)
            values.forEach { append('|').append(it) }
            append('\n')
        }
        executor.execute {
            val w = writer ?: return@execute
            try {
                w.write(line)
                w.flush()
            } catch (_: Exception) {
                closeInternal()
                onDisconnected?.invoke()
            }
        }
    }

    fun close() { executor.execute { closeInternal() } }

    private fun closeInternal() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
    }

    companion object {
        /**
         * Ищет сервер в локальной сети UDP-broadcast'ом. onResult(ip, имя) — из фонового потока,
         * (null, null) если ничего не нашли.
         */
        fun discover(port: Int, onResult: (String?, String?) -> Unit) {
            Thread {
                var found: String? = null
                var name: String? = null
                try {
                    DatagramSocket().use { ds ->
                        ds.broadcast = true
                        ds.soTimeout = 1500
                        val msg = "TOUCHPAD_DISCOVER".toByteArray()
                        ds.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), port))
                        val buf = ByteArray(256)
                        val p = DatagramPacket(buf, buf.size)
                        ds.receive(p)
                        val text = String(p.data, 0, p.length, Charsets.UTF_8)
                        if (text.startsWith("TOUCHPAD_HERE")) {
                            found = p.address.hostAddress
                            name = text.substringAfter('|', "")
                        }
                    }
                } catch (_: Exception) {
                }
                onResult(found, name)
            }.start()
        }
    }
}
