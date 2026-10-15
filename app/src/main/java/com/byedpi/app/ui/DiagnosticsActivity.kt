package com.byedpi.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.byedpi.app.BuildConfig
import com.byedpi.app.R
import com.byedpi.app.core.ByeDpiNative
import com.byedpi.app.core.CheckResult
import com.byedpi.app.core.CheckStage
import com.byedpi.app.core.ConnectivityCheck
import com.byedpi.app.core.DiagnosticsLog
import com.byedpi.app.core.EngineInfo
import com.byedpi.app.core.EngineStatus
import com.byedpi.app.core.EngineStatusHolder
import com.byedpi.app.core.Formatters
import com.byedpi.app.core.TProxyService
import com.byedpi.app.databinding.ActivityDiagnosticsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRunCheck.setOnClickListener { runCheck() }
        binding.btnCopyLog.setOnClickListener { copyLog() }
        binding.btnClearLog.setOnClickListener {
            DiagnosticsLog.clear()
            Toast.makeText(this, R.string.toast_log_cleared, Toast.LENGTH_SHORT).show()
        }

        binding.tvEngineInfo.text = buildEngineInfo()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    EngineStatusHolder.status.collect { binding.tvStateInfo.text = buildStateInfo(it) }
                }
                launch {
                    DiagnosticsLog.version.collect {
                        binding.tvLog.text = DiagnosticsLog.asText().ifEmpty { getString(R.string.log_empty) }
                    }
                }
            }
        }
    }

    private fun buildEngineInfo(): String {
        val lines = mutableListOf("ByeDPI app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        val upstream = EngineInfo.readUpstream(this)
        if (upstream.isEmpty()) {
            lines.add("upstream versions: unknown")
        } else {
            upstream.forEach { lines.add("${it.name}: ${it.ref} (${it.commit})") }
        }
        lines.add("native engine: ${if (ByeDpiNative.isLoaded) "loaded" else "NOT loaded"}")
        lines.add("native tunnel: ${if (TProxyService.isLoaded) "loaded" else "NOT loaded"}")
        lines.add("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        lines.add("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        return lines.joinToString("\n")
    }

    private fun buildStateInfo(s: EngineStatus): String {
        val lines = mutableListOf<String>()
        lines.add("phase: ${s.phase.name}")
        lines.add("proxy: ${if (s.proxyRunning) "running on port ${s.proxyPort}" else "stopped"}")
        lines.add("tunnel: ${if (s.tunnelRunning) "running" else "stopped"}")
        if (s.strategy.isNotBlank()) lines.add("strategy: ${s.strategy}")
        lines.add("sent: ${Formatters.bytes(s.txBytes)} (${s.txPackets} packets)")
        lines.add("received: ${Formatters.bytes(s.rxBytes)} (${s.rxPackets} packets)")
        lines.add("uptime: ${Formatters.duration(s.uptimeMillis)}")
        s.errorMessage?.let { lines.add("last error: $it") }
        return lines.joinToString("\n")
    }

    private fun runCheck() {
        binding.tilTestHost.error = null
        val host = ConnectivityCheck.normalizeHost(binding.etTestHost.text?.toString().orEmpty())
        if (host == null) {
            binding.tilTestHost.error = getString(R.string.check_invalid_host)
            return
        }
        val status = EngineStatusHolder.status.value
        if (!status.proxyRunning || status.proxyPort == 0) {
            binding.tvCheckResult.text = getString(R.string.check_no_proxy)
            return
        }
        val port = status.proxyPort
        setCheckRunning(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ConnectivityCheck.run(host, port) }
            setCheckRunning(false)
            renderCheck(result)
        }
    }

    private fun setCheckRunning(running: Boolean) {
        binding.btnRunCheck.isEnabled = !running
        binding.progressCheck.visibility = if (running) View.VISIBLE else View.GONE
        if (running) binding.tvCheckResult.text = getString(R.string.check_running)
    }

    private fun renderCheck(result: CheckResult) {
        when (result) {
            is CheckResult.Success -> {
                binding.tvCheckResult.text =
                    getString(R.string.check_result_ok, result.protocol, result.millis)
                DiagnosticsLog.i("Check: TLS handshake OK (${result.protocol}, ${result.millis} ms)")
            }
            is CheckResult.Failure -> {
                val stageRes = when (result.stage) {
                    CheckStage.PROXY -> R.string.check_stage_proxy
                    CheckStage.SOCKS -> R.string.check_stage_socks
                    CheckStage.TLS -> R.string.check_stage_tls
                }
                binding.tvCheckResult.text =
                    getString(R.string.check_result_failed, getString(stageRes), result.detail)
                DiagnosticsLog.w("Check failed at ${result.stage.name}")
            }
        }
    }

    private fun copyLog() {
        val text = DiagnosticsLog.asText()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ByeDPI log", text))
        Toast.makeText(this, R.string.toast_log_copied, Toast.LENGTH_SHORT).show()
    }
                  }
