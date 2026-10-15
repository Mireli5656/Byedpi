package com.byedpi.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.byedpi.app.ByeDpiApp
import com.byedpi.app.R
import com.byedpi.app.core.ByeDpiNative
import com.byedpi.app.core.DiagnosticsLog
import com.byedpi.app.core.EngineStatus
import com.byedpi.app.core.EngineStatusHolder
import com.byedpi.app.core.Phase
import com.byedpi.app.core.ProxyEngine
import com.byedpi.app.core.ProxyProbe
import com.byedpi.app.core.TProxyService
import com.byedpi.app.core.TunnelConfig
import com.byedpi.app.data.AppSettings
import com.byedpi.app.data.SettingsRepository
import com.byedpi.app.ui.MainActivity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the whole connection: native proxy -> VPN interface -> tun2socks tunnel.
 * The status is published as CONNECTED only after every stage has been verified.
 */
class ByeDpiVpnService : VpnService() {

    private class ConnectionFailure(message: String) : Exception(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shutdownMutex = Mutex()
    private val resourceLock = Any()

    @Volatile private var session: Job? = null
    @Volatile private var proxyEngine: ProxyEngine? = null
    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var tunnelStarted = false
    @Volatile private var finished = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            else -> if (finished) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        val settings = SettingsRepository(applicationContext).load()
        // Must be called promptly after startForegroundService(), even if we then ignore the request.
        showForeground(settings.port)

        val phase = EngineStatusHolder.status.value.phase
        if (!finished || phase == Phase.DISCONNECTING) {
            DiagnosticsLog.w("Start request ignored: a session is already active")
            return
        }
        finished = false
        EngineStatusHolder.update {
            EngineStatus(phase = Phase.CONNECTING, strategy = settings.strategyText())
        }
        DiagnosticsLog.i("Connecting")
        session = scope.launch { runSession(settings) }
    }

    private fun handleStop() {
        scope.launch {
            session?.cancelAndJoin()
            shutdown(error = null)
        }
    }

    override fun onRevoke() {
        DiagnosticsLog.w("VPN permission was revoked (another VPN app or the system)")
        handleStop()
    }

    override fun onDestroy() {
        session?.cancel()
        if (!finished) {
            finished = true
            releaseResources()
            EngineStatusHolder.update { EngineStatus(phase = Phase.DISCONNECTED) }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- session

    private suspend fun runSession(settings: AppSettings) {
        try {
            connect(settings)
            monitor()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectionFailure) {
            DiagnosticsLog.e("Connection failed: ${e.message}")
            shutdown(error = e.message)
        } catch (e: Throwable) {
            DiagnosticsLog.e("Unexpected failure: ${e.javaClass.simpleName}")
            shutdown(error = getString(R.string.error_unexpected, e.javaClass.simpleName))
        }
    }

    private suspend fun connect(settings: AppSettings) {
        val problems = settings.validate()
        if (problems.isNotEmpty()) {
            val names = problems.keys.joinToString(", ") { it.label }
            throw ConnectionFailure(getString(R.string.error_invalid_settings, names))
        }
        if (!ByeDpiNative.isLoaded || !TProxyService.isLoaded) {
            throw ConnectionFailure(getString(R.string.error_native_unavailable))
        }

        // 1. Native ByeDPI proxy
        DiagnosticsLog.i("Starting ByeDPI proxy on ${AppSettings.LISTEN_ADDRESS}:${settings.port}")
        val engine = ProxyEngine()
        proxyEngine = engine
        engine.start(settings.engineArgs())
        waitForProxy(engine, settings.port)
        DiagnosticsLog.i("Proxy answered a SOCKS5 handshake")
        EngineStatusHolder.update { it.copy(proxyRunning = true, proxyPort = settings.port) }

        // 2. VPN interface
        val pfd = buildTun(settings) ?: throw ConnectionFailure(getString(R.string.error_vpn_establish))
        tunFd = pfd
        DiagnosticsLog.i("VPN interface established")

        // 3. Tunnel (tun2socks -> proxy)
        val started = withContext(Dispatchers.IO) {
            val config = File(cacheDir, "tunnel.yml")
            config.writeText(TunnelConfig.yaml(settings))
            TProxyService.start(config.absolutePath, pfd.fd)
        }
        if (!started) throw ConnectionFailure(getString(R.string.error_tunnel_failed))
        tunnelStarted = true
        waitForTunnel()
        DiagnosticsLog.i("Tunnel is running")

        EngineStatusHolder.update { it.copy(phase = Phase.CONNECTED, tunnelRunning = true) }
        DiagnosticsLog.i("Connected")
    }

    private suspend fun monitor() {
        val engine = proxyEngine ?: throw ConnectionFailure(getString(R.string.error_proxy_stopped))
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            if (!engine.isRunning) throw ConnectionFailure(getString(R.string.error_proxy_stopped))
            if (!TProxyService.isRunning()) throw ConnectionFailure(getString(R.string.error_tunnel_failed))
            // Order documented by hev-socks5-tunnel: tx packets, tx bytes, rx packets, rx bytes.
            val stats = TProxyService.stats()
            val uptime = SystemClock.elapsedRealtime() - startedAt
            EngineStatusHolder.update {
                it.copy(
                    uptimeMillis = uptime,
                    txPackets = stats.getOrElse(0) { 0L },
                    txBytes = stats.getOrElse(1) { 0L },
                    rxPackets = stats.getOrElse(2) { 0L },
                    rxBytes = stats.getOrElse(3) { 0L }
                )
            }
        }
    }

    private suspend fun waitForProxy(engine: ProxyEngine, port: Int) {
        val deadline = SystemClock.elapsedRealtime() + PROXY_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            engine.exitCode?.let {
                throw ConnectionFailure(getString(R.string.error_proxy_start_failed, it))
            }
            val answered = withContext(Dispatchers.IO) { ProxyProbe.handshake(port) }
            if (answered) {
                // Make sure the answer came from our engine, not from another process on the port.
                delay(200)
                val code = engine.exitCode
                if (code != null || !engine.isRunning) {
                    throw ConnectionFailure(getString(R.string.error_proxy_start_failed, code ?: -1))
                }
                return
            }
            delay(150)
        }
        engine.exitCode?.let {
            throw ConnectionFailure(getString(R.string.error_proxy_start_failed, it))
        }
        throw ConnectionFailure(getString(R.string.error_proxy_not_listening, port))
    }

    private suspend fun waitForTunnel() {
        val deadline = SystemClock.elapsedRealtime() + TUNNEL_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (TProxyService.isRunning()) return
            delay(100)
        }
        throw ConnectionFailure(getString(R.string.error_tunnel_failed))
    }

    private fun buildTun(settings: AppSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(TunnelConfig.MTU)
            .addAddress(TunnelConfig.IPV4_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(settings.dns.trim())
        if (settings.tunnelIpv6) {
            builder.addAddress(TunnelConfig.IPV6_ADDRESS, 128)
            builder.addRoute("::", 0)
        }
        try {
            // Keeps the proxy's own outgoing sockets out of the tunnel (prevents a loop).
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            DiagnosticsLog.w("Could not exclude the app from the VPN")
        }
        return builder.establish()
    }

    // --------------------------------------------------------------- shutdown

    private suspend fun shutdown(error: String?) {
        shutdownMutex.withLock {
            if (finished) {
                stopSelf()
                return
            }
            finished = true
            EngineStatusHolder.update { it.copy(phase = Phase.DISCONNECTING) }
            withContext(Dispatchers.IO) { releaseResources() }
            EngineStatusHolder.update {
                EngineStatus(
                    phase = if (error == null) Phase.DISCONNECTED else Phase.ERROR,
                    errorMessage = error
                )
            }
            DiagnosticsLog.i(if (error == null) "Disconnected" else "Stopped after an error")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseResources() {
        synchronized(resourceLock) {
            if (tunnelStarted) {
                try {
                    TProxyService.stop()
                } catch (t: Throwable) {
                    DiagnosticsLog.e("Tunnel stop failed: ${t.javaClass.simpleName}")
                }
                tunnelStarted = false
            }
            tunFd?.let {
                try {
                    it.close()
                } catch (e: IOException) {
                    // ignore
                }
            }
            tunFd = null
            proxyEngine?.stop()
            proxyEngine = null
        }
    }

    // ----------------------------------------------------------- notification

    private fun showForeground(port: Int) {
        val notification = buildNotification(port)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val stop = PendingIntent.getService(this, 1, stopIntent(this), flags)
        return NotificationCompat.Builder(this, ByeDpiApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, "${AppSettings.LISTEN_ADDRESS}:$port"))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_action_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.byedpi.app.action.START"
        const val ACTION_STOP = "com.byedpi.app.action.STOP"

        private const val NOTIFICATION_ID = 1
        private const val PROXY_READY_TIMEOUT_MS = 6000L
        private const val TUNNEL_READY_TIMEOUT_MS = 3000L

        fun startIntent(context: Context): Intent =
            Intent(context, ByeDpiVpnService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, ByeDpiVpnService::class.java).setAction(ACTION_STOP)
    }
}
