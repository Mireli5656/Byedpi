package com.byedpi.app.core

import java.util.Locale

object Formatters {

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var v = value.toDouble()
        var i = -1
        do {
            v /= 1024.0
            i++
        } while (v >= 1024.0 && i < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun duration(millis: Long): String {
        val total = millis / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
