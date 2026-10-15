package com.byedpi.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

data class EngineStatus(
    val phase: Phase = Phase.DISCONNECTED,
    val proxyRunning: Boolean = false,
    val proxyPort: Int = 0,
    val tunnelRunning: Boolean = false,
    val strategy: String = "",
    val errorMessage: String? = null,
    val uptimeMillis: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val txPackets: Long = 0L,
    val rxPackets: Long = 0L
)

/** Single source of truth for the connection state; written only by the VPN service. */
object EngineStatusHolder {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun update(transform: (EngineStatus) -> EngineStatus) {
        _status.update(transform)
    }
}
