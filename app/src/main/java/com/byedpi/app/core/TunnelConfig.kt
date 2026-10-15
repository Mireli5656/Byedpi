package com.byedpi.app.core

import com.byedpi.app.data.AppSettings

/** Builds the hev-socks5-tunnel YAML config. Only keys documented in its README are used. */
object TunnelConfig {

    const val MTU = 1500
    const val IPV4_ADDRESS = "198.18.0.1"
    const val IPV6_ADDRESS = "fc00::1"

    fun yaml(settings: AppSettings): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: $MTU")
        appendLine("  ipv4: $IPV4_ADDRESS")
        if (settings.tunnelIpv6) appendLine("  ipv6: '$IPV6_ADDRESS'")
        appendLine("socks5:")
        appendLine("  port: ${settings.port}")
        appendLine("  address: ${AppSettings.LISTEN_ADDRESS}")
        appendLine("  udp: 'udp'")
        appendLine("misc:")
        appendLine("  task-stack-size: 81920")
        appendLine("  connect-timeout: 5000")
        appendLine("  log-level: warn")
    }
}
