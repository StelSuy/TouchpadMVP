package com.stelsuy.touchpad

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class MouseClient {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null

    /** Вызывается (из фонового потока), если соединение оборвалось при отправке. */
    var onDisconnected: (() -> Unit)? = null

    fun connect(host: String, port: Int, onResult: (Boolean, String) -> Unit) {
        executor.execute {
            try {
                closeInternal()
                val s = Socket()
                s.tcpNoDelay = true      // без буферизации Нейгла — иначе курсор лагает
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), 2500)
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                onResult(true, "● Подключено: $host:$port")
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

    fun isConnected(): Boolean = socket?.let { it.isConnected && !it.isClosed } == true

    fun close() { executor.execute { closeInternal() } }

    private fun closeInternal() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
    }
}
