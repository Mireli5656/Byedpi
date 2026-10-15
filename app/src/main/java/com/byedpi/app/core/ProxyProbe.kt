package com.byedpi.app.core

import com.byedpi.app.data.AppSettings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object ProxyProbe {

    /** True only if something on the port answers a SOCKS5 "no auth" greeting correctly. */
    fun handshake(port: Int, timeoutMs: Int = 500): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(AppSettings.LISTEN_ADDRESS, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val input = socket.getInputStream()
                val reply = ByteArray(2)
                var read = 0
                while (read < reply.size) {
                    val n = input.read(reply, read, reply.size - read)
                    if (n < 0) return false
                    read += n
                }
                reply[0] == 0x05.toByte() && reply[1] == 0x00.toByte()
            }
        } catch (e: IOException) {
            false
        }
    }
}
