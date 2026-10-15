package com.byedpi.app.data

import com.byedpi.app.R
import java.net.Inet6Address
import java.net.InetAddress

enum class DesyncMethod(val flag: String?) {
    NONE(null),
    SPLIT("--split"),
    DISORDER("--disorder"),
    FAKE("--fake"),
    OOB("--oob"),
    DISOOB("--disoob");

    companion object {
        /** Index order matches R.array.desync_methods. */
        fun fromIndex(index: Int): DesyncMethod = entries.getOrElse(index) { NONE }
    }
}

enum class SettingsField(val label: String) {
    PORT("port"),
    DNS("dns"),
    POSITION("position"),
    FAKE_TTL("fake TTL"),
    TLSREC("TLS record"),
    TIMEOUT("timeout"),
    CUSTOM_ARGS("custom arguments")
}

/** Splits a command line into tokens. Supports simple '...' and "..." quoting. */
fun tokenizeArgs(raw: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var inToken = false
    for (c in raw) {
        if (quote != null) {
            if (c == quote) quote = null else current.append(c)
        } else if (c == '"' || c == '\'') {
            quote = c
            inToken = true
        } else if (c.isWhitespace()) {
            if (inToken) {
                tokens.add(current.toString())
                current.setLength(0)
                inToken = false
            }
        } else {
            current.append(c)
            inToken = true
        }
    }
    if (inToken) tokens.add(current.toString())
    return tokens
}

data class AppSettings(
    val port: Int = DEFAULT_PORT,
    val dns: String = DEFAULT_DNS,
    val proxyUdp: Boolean = true,
    val tunnelIpv6: Boolean = false,
    val useCustomArgs: Boolean = false,
    val method: DesyncMethod = DesyncMethod.DISORDER,
    val position: String = "1",
    val fakeTtl: Int = 8,
    val tlsRecPosition: String = "",
    val autoMode: Boolean = false,
    val timeoutSeconds: String = "3",
    val customArgs: String = ""
) {

    /** Arguments controlling where the proxy listens. Always added by the app. */
    fun listenArgs(): List<String> = buildList {
        add("--ip")
        add(LISTEN_ADDRESS)
        add("--port")
        add(port.toString())
        if (!proxyUdp) add("--no-udp")
    }

    /** Arguments describing the bypass strategy. */
    fun strategyArgs(): List<String> {
        if (useCustomArgs) return tokenizeArgs(customArgs)

        val rest = buildList {
            val tls = tlsRecPosition.trim()
            if (tls.isNotEmpty()) {
                add("--tlsrec")
                add(tls)
            }
            val flag = method.flag
            if (flag != null) {
                add(flag)
                add(position.trim())
                if (method == DesyncMethod.FAKE) {
                    add("--ttl")
                    add(fakeTtl.toString())
                }
            }
        }
        if (!autoMode || rest.isEmpty()) return rest
        return listOf("--auto=torst", "--timeout", normalizedTimeout()) + rest
    }

    /** Full argument list passed to the engine (argv[0] is added natively). */
    fun engineArgs(): List<String> = listenArgs() + strategyArgs()

    fun strategyText(): String = strategyArgs().joinToString(" ")

    fun previewText(): String = (listOf("ciadpi") + engineArgs()).joinToString(" ")

    fun timeoutValue(): Double? =
        timeoutSeconds.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..60.0 }

    private fun normalizedTimeout(): String {
        val v = timeoutValue() ?: 3.0
        return if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
    }

    fun hasForbiddenCustomArgs(): Boolean = tokenizeArgs(customArgs).any { token ->
        if (token.startsWith("--")) {
            val name = token.substringBefore('=')
            name.length >= 3 && FORBIDDEN_LONG.any { it.startsWith(name) }
        } else if (token.startsWith("-") && token.length >= 2 && !token[1].isDigit()) {
            FORBIDDEN_SHORT.any { token.startsWith(it) }
        } else {
            false
        }
    }

    /** Returns field -> string resource id for every invalid field. Empty map means valid. */
    fun validate(): Map<SettingsField, Int> {
        val errors = linkedMapOf<SettingsField, Int>()

        if (port !in 1024..65535) errors[SettingsField.PORT] = R.string.error_port

        if (!isValidIp(dns)) {
            errors[SettingsField.DNS] = R.string.error_dns
        } else if (dns.contains(':') && !tunnelIpv6) {
            errors[SettingsField.DNS] = R.string.error_dns_ipv6
        }

        if (useCustomArgs) {
            if (hasForbiddenCustomArgs()) errors[SettingsField.CUSTOM_ARGS] = R.string.error_custom_invalid
        } else {
            if (method != DesyncMethod.NONE && !POSITION_REGEX.matches(position.trim())) {
                errors[SettingsField.POSITION] = R.string.error_position
            }
            if (method == DesyncMethod.FAKE && fakeTtl !in 1..255) {
                errors[SettingsField.FAKE_TTL] = R.string.error_ttl
            }
            val tls = tlsRecPosition.trim()
            if (tls.isNotEmpty() && !POSITION_REGEX.matches(tls)) {
                errors[SettingsField.TLSREC] = R.string.error_position
            }
            if (autoMode && timeoutValue() == null) {
                errors[SettingsField.TIMEOUT] = R.string.error_timeout
            }
        }
        return errors
    }

    companion object {
        const val LISTEN_ADDRESS = "127.0.0.1"
        const val DEFAULT_PORT = 1080
        const val DEFAULT_DNS = "8.8.8.8"

        private val POSITION_REGEX =
            Regex("^-?\\d{1,6}(:\\d{1,4}:\\d{1,4})?(\\+[shnem]{1,2})?$")
        private val IPV4_REGEX =
            Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")

        private val FORBIDDEN_LONG = listOf(
            "--ip", "--port", "--daemon", "--pidfile",
            "--transparent", "--cache-dump", "--help", "--version"
        )
        private val FORBIDDEN_SHORT = listOf("-i", "-p", "-D", "-w", "-E", "-y", "-h", "-v")

        fun isValidIp(value: String): Boolean {
            val s = value.trim()
            if (IPV4_REGEX.matches(s)) return true
            val looksLikeV6 = s.contains(':') &&
                s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
            if (!looksLikeV6) return false
            return try {
                InetAddress.getByName(s) is Inet6Address
            } catch (e: Exception) {
                false
            }
        }
    }
}
