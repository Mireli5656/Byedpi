package com.byedpi.app.data

import android.content.Context

class SettingsRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        val method = try {
            DesyncMethod.valueOf(prefs.getString(K_METHOD, d.method.name) ?: d.method.name)
        } catch (e: IllegalArgumentException) {
            d.method
        }
        return AppSettings(
            port = prefs.getInt(K_PORT, d.port),
            dns = prefs.getString(K_DNS, d.dns) ?: d.dns,
            proxyUdp = prefs.getBoolean(K_UDP, d.proxyUdp),
            tunnelIpv6 = prefs.getBoolean(K_IPV6, d.tunnelIpv6),
            useCustomArgs = prefs.getBoolean(K_CUSTOM, d.useCustomArgs),
            method = method,
            position = prefs.getString(K_POSITION, d.position) ?: d.position,
            fakeTtl = prefs.getInt(K_TTL, d.fakeTtl),
            tlsRecPosition = prefs.getString(K_TLSREC, d.tlsRecPosition) ?: d.tlsRecPosition,
            autoMode = prefs.getBoolean(K_AUTO, d.autoMode),
            timeoutSeconds = prefs.getString(K_TIMEOUT, d.timeoutSeconds) ?: d.timeoutSeconds,
            customArgs = prefs.getString(K_CUSTOM_ARGS, d.customArgs) ?: d.customArgs
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt(K_PORT, s.port)
            .putString(K_DNS, s.dns.trim())
            .putBoolean(K_UDP, s.proxyUdp)
            .putBoolean(K_IPV6, s.tunnelIpv6)
            .putBoolean(K_CUSTOM, s.useCustomArgs)
            .putString(K_METHOD, s.method.name)
            .putString(K_POSITION, s.position.trim())
            .putInt(K_TTL, s.fakeTtl)
            .putString(K_TLSREC, s.tlsRecPosition.trim())
            .putBoolean(K_AUTO, s.autoMode)
            .putString(K_TIMEOUT, s.timeoutSeconds.trim())
            .putString(K_CUSTOM_ARGS, s.customArgs)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "byedpi_settings"
        const val K_PORT = "port"
        const val K_DNS = "dns"
        const val K_UDP = "proxy_udp"
        const val K_IPV6 = "tunnel_ipv6"
        const val K_CUSTOM = "use_custom_args"
        const val K_METHOD = "method"
        const val K_POSITION = "position"
        const val K_TTL = "fake_ttl"
        const val K_TLSREC = "tlsrec"
        const val K_AUTO = "auto_mode"
        const val K_TIMEOUT = "timeout"
        const val K_CUSTOM_ARGS = "custom_args"
    }
}
