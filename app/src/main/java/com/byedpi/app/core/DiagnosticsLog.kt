package com.byedpi.app.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory ring buffer of app events. Never record visited hosts, IPs or packet contents here.
 */
object DiagnosticsLog {

    private const val MAX_ENTRIES = 300
    private const val MAX_LINE = 300

    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val _version = MutableStateFlow(0L)

    /** Increments on every change so UIs can re-render. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun i(message: String) = add("I", message)
    fun w(message: String) = add("W", message)
    fun e(message: String) = add("E", message)

    private fun add(level: String, message: String) {
        val clean = message.replace('\n', ' ').replace('\r', ' ').take(MAX_LINE)
        val line = "${LocalTime.now().format(formatter)} $level $clean"
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        _version.update { it + 1 }
    }

    fun asText(): String = synchronized(lock) { entries.joinToString("\n") }

    fun clear() {
        synchronized(lock) { entries.clear() }
        _version.update { it + 1 }
    }
}
