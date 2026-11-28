package io.github.dovecoteescapee.byedpi.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.dovecoteescapee.byedpi.databinding.ActivitySettingsM3Binding
import io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelActivity
import io.github.dovecoteescapee.byedpi.utility.AppearanceManager
import io.github.dovecoteescapee.byedpi.utility.applyAccentTheme
import io.github.dovecoteescapee.byedpi.utility.getPreferences

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsM3Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsM3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        io.github.dovecoteescapee.byedpi.utility.BottomNavHelper.setup(
            this,
            io.github.dovecoteescapee.byedpi.utility.BottomNavHelper.Tab.CORE
        )

        binding.btnGoAppearance.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        binding.cardAppearanceBanner.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        setupAccordion(binding.headerNetwork, binding.contentNetwork, binding.arrowNetwork)
        setupAccordion(binding.headerCmd, binding.contentCmd, binding.arrowCmd)
        setupAccordion(binding.headerSystem, binding.contentSystem, binding.arrowSystem)

        binding.btnSplitTunnel.setOnClickListener {
            startActivity(Intent(this, SplitTunnelActivity::class.java))
        }

        loadSettings()

        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetSettings.setOnClickListener {
            getPreferences().edit { clear() }
            loadSettings()
            Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show()
        }

        binding.btnCheckUpdates.setOnClickListener {
            Toast.makeText(this, "Проверка обновлений...", Toast.LENGTH_SHORT).show()
            io.github.dovecoteescapee.byedpi.utility.UpdateManager.checkForUpdates(this, manual = true)
        }
    }

    private fun setupAccordion(header: View, content: View, arrow: TextView) {
        header.setOnClickListener {
            val isOpening = content.visibility != View.VISIBLE
            content.visibility = if (isOpening) View.VISIBLE else View.GONE
            arrow.text = if (isOpening) "▲" else "▼"
        }
    }

    private fun loadSettings() {
        val prefs = getPreferences()
        val mode = prefs.getString("byedpi_mode", "vpn") ?: "vpn"
        if (mode == "proxy") {
            binding.chipModeProxy.isChecked = true
        } else {
            binding.chipModeVpn.isChecked = true
        }
        binding.etDnsIp.setText(prefs.getString("dns_ip", "1.1.1.1"))
        binding.etProxyIp.setText(prefs.getString("byedpi_proxy_ip", "127.0.0.1"))
        binding.etProxyPort.setText(prefs.getString("byedpi_proxy_port", "1080"))
        binding.switchIpv6.isChecked = prefs.getBoolean("ipv6_enable", false)
        binding.switchCmdEnable.isChecked = prefs.getBoolean("byedpi_enable_cmd_settings", false)
        binding.etCmdArgs.setText(prefs.getString("byedpi_cmd_args", ""))
        binding.switchAutostart.isChecked = prefs.getBoolean("autostart", false)
    }

    private fun saveSettings() {
        val mode = if (binding.chipModeProxy.isChecked) "proxy" else "vpn"
        getPreferences().edit {
            putString("byedpi_mode", mode)
            putString("dns_ip", binding.etDnsIp.text?.toString()?.trim().orEmpty().ifBlank { "1.1.1.1" })
            putString("byedpi_proxy_ip", binding.etProxyIp.text?.toString()?.trim().orEmpty().ifBlank { "127.0.0.1" })
            putString("byedpi_proxy_port", binding.etProxyPort.text?.toString()?.trim().orEmpty().ifBlank { "1080" })
            putBoolean("ipv6_enable", binding.switchIpv6.isChecked)
            putBoolean("byedpi_enable_cmd_settings", binding.switchCmdEnable.isChecked)
            putString("byedpi_cmd_args", binding.etCmdArgs.text?.toString()?.trim().orEmpty())
            putBoolean("autostart", binding.switchAutostart.isChecked)
        }
    }
}
