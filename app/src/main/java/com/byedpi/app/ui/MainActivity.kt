package com.byedpi.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.byedpi.app.BuildConfig
import com.byedpi.app.R
import com.byedpi.app.core.DiagnosticsLog
import com.byedpi.app.core.EngineInfo
import com.byedpi.app.core.EngineStatus
import com.byedpi.app.core.EngineStatusHolder
import com.byedpi.app.core.Formatters
import com.byedpi.app.core.Phase
import com.byedpi.app.data.AppSettings
import com.byedpi.app.data.SettingsRepository
import com.byedpi.app.databinding.ActivityMainBinding
import com.byedpi.app.service.ByeDpiVpnService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: SettingsRepository

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startEngineService()
            } else {
                DiagnosticsLog.w("VPN permission was not granted")
                Toast.makeText(this, R.string.error_vpn_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.error_notification_permission, Toast.LENGTH_LONG).show()
            }
            requestVpnAndStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()
        repository = SettingsRepository(this)

        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        val engines = EngineInfo.readUpstream(this)
            .joinToString(" · ") { "${it.name} ${it.ref}" }
        binding.tvVersion.text = listOf("ByeDPI ${BuildConfig.VERSION_NAME}", engines)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EngineStatusHolder.status.collect { render(it) }
            }
        }
    }

    // ------------------------------------------------------------ connecting

    private fun onConnectClicked() {
        when (EngineStatusHolder.status.value.phase) {
            Phase.CONNECTED, Phase.CONNECTING -> startService(ByeDpiVpnService.stopIntent(this))
            Phase.DISCONNECTING -> Unit
            else -> beginConnect()
        }
    }

    private fun beginConnect() {
        val settings: AppSettings = repository.load()
        if (settings.validate().isNotEmpty()) {
            Toast.makeText(this, R.string.error_fix_settings, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnAndStart()
        }
    }

    private fun requestVpnAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startEngineService()
        }
    }

    private fun startEngineService() {
        ContextCompat.startForegroundService(this, ByeDpiVpnService.startIntent(this))
    }

    // ------------------------------------------------------------- rendering

    private fun render(status: EngineStatus) {
        val (colorRes, titleRes) = when (status.phase) {
            Phase.DISCONNECTED -> R.color.status_idle to R.string.status_disconnected
            Phase.CONNECTING -> R.color.status_connecting to R.string.status_connecting
            Phase.CONNECTED -> R.color.status_connected to R.string.status_connected
            Phase.DISCONNECTING -> R.color.status_connecting to R.string.status_disconnecting
            Phase.ERROR -> R.color.status_error to R.string.status_error
        }
        val detail = when (status.phase) {
            Phase.DISCONNECTED -> getString(R.string.status_detail_disconnected)
            Phase.CONNECTING -> getString(R.string.status_detail_connecting)
            Phase.CONNECTED -> getString(R.string.status_detail_connected)
            Phase.DISCONNECTING -> getString(R.string.status_detail_disconnecting)
            Phase.ERROR -> getString(R.string.status_detail_error, status.errorMessage.orEmpty())
        }
        binding.viewStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        binding.tvStatusTitle.setText(titleRes)
        binding.tvStatusDetail.text = detail
        binding.progressConnecting.visibility =
            if (status.phase == Phase.CONNECTING || status.phase == Phase.DISCONNECTING) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }

        renderButton(status.phase)

        val connected = status.phase == Phase.CONNECTED
        val none = getString(R.string.value_none)
        binding.tvEngineValue.text = if (status.proxyRunning) {
            getString(R.string.value_listening, "${AppSettings.LISTEN_ADDRESS}:${status.proxyPort}")
        } else {
            getString(R.string.value_stopped)
        }
        binding.tvTunnelValue.text = getString(
            if (status.tunnelRunning) R.string.value_running else R.string.value_stopped
        )
        binding.tvSentValue.text = if (connected) Formatters.bytes(status.txBytes) else none
        binding.tvReceivedValue.text = if (connected) Formatters.bytes(status.rxBytes) else none
        binding.tvUptimeValue.text = if (connected) Formatters.duration(status.uptimeMillis) else none

        val active = status.phase == Phase.CONNECTING || status.phase == Phase.CONNECTED
        val strategy = if (active) status.strategy else repository.load().strategyText()
        binding.tvStrategyValue.text = strategy.ifBlank { none }
    }

    private fun renderButton(phase: Phase) {
        val green = ContextCompat.getColor(this, R.color.green)
        val onGreen = ContextCompat.getColor(this, R.color.on_green)
        val button = binding.btnConnect
        when (phase) {
            Phase.CONNECTED -> {
                button.setText(R.string.action_disconnect)
                button.isEnabled = true
                button.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.red_dark))
                button.setTextColor(ContextCompat.getColor(this, R.color.on_red))
            }
            Phase.CONNECTING -> {
                button.setText(R.string.action_connecting)
                button.isEnabled = false
                button.backgroundTintList = ColorStateList.valueOf(green)
                button.setTextColor(onGreen)
            }
            Phase.DISCONNECTING -> {
                button.setText(R.string.action_disconnecting)
                button.isEnabled = false
                button.backgroundTintList = ColorStateList.valueOf(green)
                button.setTextColor(onGreen)
            }
            else -> {
                button.setText(R.string.action_connect)
                button.isEnabled = true
                button.backgroundTintList = ColorStateList.valueOf(green)
                button.setTextColor(onGreen)
            }
        }
    }
}
