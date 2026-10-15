package com.byedpi.app.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.byedpi.app.R
import com.byedpi.app.core.DiagnosticsLog
import com.byedpi.app.data.AppSettings
import com.byedpi.app.data.DesyncMethod
import com.byedpi.app.data.SettingsField
import com.byedpi.app.data.SettingsRepository
import com.byedpi.app.databinding.ActivitySettingsBinding
import com.google.android.material.textfield.TextInputLayout

private fun TextView.str(): String = text?.toString().orEmpty()

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: SettingsRepository
    private lateinit var methodLabels: Array<String>

    private var selectedMethod = DesyncMethod.DISORDER
    private var populating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()
        repository = SettingsRepository(this)

        methodLabels = resources.getStringArray(R.array.desync_methods)
        binding.ddMethod.setSimpleItems(R.array.desync_methods)
        binding.ddMethod.setOnItemClickListener { _, _, position, _ ->
            selectedMethod = DesyncMethod.fromIndex(position)
            onFormChanged()
        }

        listOf(
            binding.etPort, binding.etDns, binding.etPosition, binding.etFakeTtl,
            binding.etTlsrec, binding.etTimeout, binding.etCustomArgs
        ).forEach { it.doAfterTextChanged { onFormChanged() } }

        binding.switchUdp.setOnCheckedChangeListener { _, checked ->
            if (!checked && !populating) {
                Toast.makeText(this, R.string.settings_udp_warning, Toast.LENGTH_LONG).show()
            }
            onFormChanged()
        }
        binding.switchIpv6.setOnCheckedChangeListener { _, _ -> onFormChanged() }
        binding.switchAuto.setOnCheckedChangeListener { _, _ -> onFormChanged() }
        binding.switchCustom.setOnCheckedChangeListener { _, checked ->
            if (checked && !populating && binding.etCustomArgs.str().isBlank()) {
                binding.etCustomArgs.setText(readForm().copy(useCustomArgs = false).strategyText())
            }
            onFormChanged()
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnReset.setOnClickListener {
            repository.reset()
            clearErrors()
            populate(AppSettings())
            Toast.makeText(this, R.string.toast_defaults_restored, Toast.LENGTH_SHORT).show()
        }

        populate(repository.load())
    }

    private fun populate(s: AppSettings) {
        populating = true
        selectedMethod = s.method
        binding.etPort.setText(s.port.toString())
        binding.etDns.setText(s.dns)
        binding.switchUdp.isChecked = s.proxyUdp
        binding.switchIpv6.isChecked = s.tunnelIpv6
        binding.switchCustom.isChecked = s.useCustomArgs
        binding.ddMethod.setText(methodLabels[s.method.ordinal], false)
        binding.etPosition.setText(s.position)
        binding.etFakeTtl.setText(s.fakeTtl.toString())
        binding.etTlsrec.setText(s.tlsRecPosition)
        binding.switchAuto.isChecked = s.autoMode
        binding.etTimeout.setText(s.timeoutSeconds)
        binding.etCustomArgs.setText(s.customArgs)
        populating = false
        updateVisibility()
        updatePreview()
    }

    private fun readForm(): AppSettings = AppSettings(
        port = binding.etPort.str().trim().toIntOrNull() ?: 0,
        dns = binding.etDns.str().trim(),
        proxyUdp = binding.switchUdp.isChecked,
        tunnelIpv6 = binding.switchIpv6.isChecked,
        useCustomArgs = binding.switchCustom.isChecked,
        method = selectedMethod,
        position = binding.etPosition.str().trim(),
        fakeTtl = binding.etFakeTtl.str().trim().toIntOrNull() ?: 0,
        tlsRecPosition = binding.etTlsrec.str().trim(),
        autoMode = binding.switchAuto.isChecked,
        timeoutSeconds = binding.etTimeout.str().trim(),
        customArgs = binding.etCustomArgs.str()
    )

    private fun onFormChanged() {
        if (populating) return
        updateVisibility()
        updatePreview()
    }

    private fun updateVisibility() {
        val custom = binding.switchCustom.isChecked
        binding.groupCustom.isVisible = custom
        binding.groupStructured.isVisible = !custom
        binding.tilFakeTtl.isVisible = selectedMethod == DesyncMethod.FAKE
        binding.tilTimeout.isVisible = binding.switchAuto.isChecked
    }

    private fun updatePreview() {
        binding.tvArgsPreview.text = readForm().previewText()
    }

    private fun save() {
        clearErrors()
        val settings = readForm()
        val errors = settings.validate()
        if (errors.isNotEmpty()) {
            var first: TextInputLayout? = null
            for ((field, messageRes) in errors) {
                val layout = fieldLayout(field)
                layout.error = getString(messageRes)
                if (first == null) first = layout
            }
            first?.editText?.requestFocus()
            return
        }
        repository.save(settings)
        DiagnosticsLog.i("Settings saved")
        Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun clearErrors() {
        SettingsField.entries.forEach { fieldLayout(it).error = null }
    }

    private fun fieldLayout(field: SettingsField): TextInputLayout = when (field) {
        SettingsField.PORT -> binding.tilPort
        SettingsField.DNS -> binding.tilDns
        SettingsField.POSITION -> binding.tilPosition
        SettingsField.FAKE_TTL -> binding.tilFakeTtl
        SettingsField.TLSREC -> binding.tilTlsrec
        SettingsField.TIMEOUT -> binding.tilTimeout
        SettingsField.CUSTOM_ARGS -> binding.tilCustomArgs
    }
}
