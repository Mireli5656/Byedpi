package com.byedpi.app.core

import android.os.SystemClock
import com.byedpi.app.data.AppSettings
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class CheckStage { PROXY, SOCKS, TLS }

sealed interface CheckResult {
    data class Success(val protocol: String, val millis: Long) : CheckResult
    data class Failure(val stage: CheckStage, val detail: String) : CheckResult
}

/** Opens a SOCKS5 CONNECT through the local proxy and completes a real TLS handshake. */
object ConnectivityCheck {

    private const val HTTPS_PORT = 443
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 8000

    private val HOST_REGEX =
        Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

    fun normalizeHost(input: String): String? {
        var s = input.trim().lowercase(Locale.ROOT)
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return s.takeIf { it.length <= 253 && HOST_REGEX.matches(it) }
    }

    fun run(host: String, proxyPort: Int): CheckResult {
        val started = SystemClock.elapsedRealtime()
        var stage = CheckStage.PROXY
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(AppSettings.LISTEN_ADDRESS, proxyPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            stage = CheckStage.SOCKS
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = readFully(input, 2)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                throw IOException("Unexpected SOCKS5 greeting")
            }

            val name = host.toByteArray(Charsets.US_ASCII)
            val request = ByteArray(7 + name.size)
            request[0] = 0x05
            request[1] = 0x01
            request[2] = 0x00
            request[3] = 0x03
            request[4] = name.size.toByte()
            System.arraycopy(name, 0, request, 5, name.size)
            request[5 + name.size] = (HTTPS_PORT shr 8).toByte()
            request[6 + name.size] = (HTTPS_PORT and 0xFF).toByte()
            output.write(request)
            output.flush()

            val reply = readFully(input, 4)
            val code = reply[1].toInt() and 0xFF
            if (code != 0) throw IOException("SOCKS5 reply code $code")
            val remaining = when (reply[3].toInt() and 0xFF) {
                0x01 -> 6
                0x04 -> 18
                0x03 -> (readFully(input, 1)[0].toInt() and 0xFF) + 2
                else -> throw IOException("Unexpected SOCKS5 address type")
            }
            readFully(input, remaining)

            stage = CheckStage.TLS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(socket, host, HTTPS_PORT, true) as SSLSocket
            ssl.soTimeout = READ_TIMEOUT_MS
            ssl.startHandshake()
            val protocol = ssl.session.protocol
            ssl.close()
            return CheckResult.Success(protocol, SystemClock.elapsedRealtime() - started)
        } catch (e: Exception) {
            return CheckResult.Failure(stage, "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(160))
        } finally {
            try {
                socket.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buffer, offset, count - offset)
            if (n < 0) throw EOFException("Connection closed")
            offset += n
        }
        return buffer
    }
}
