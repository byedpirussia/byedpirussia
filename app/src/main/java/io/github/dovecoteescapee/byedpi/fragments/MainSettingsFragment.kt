package io.github.dovecoteescapee.byedpi.fragments

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.*
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.utility.*

class MainSettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private val TAG: String = MainSettingsFragment::class.java.simpleName

        fun setTheme(name: String) =
            themeByName(name)?.let {
                AppCompatDelegate.setDefaultNightMode(it)
            } ?: throw IllegalStateException("Invalid value for app_theme: $name")

        private fun themeByName(name: String): Int? = when (name) {
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> {
                Log.w(TAG, "Invalid value for app_theme: $name")
                null
            }
        }
    }

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updatePreferences()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_settings, rootKey)

        setEditTextPreferenceListener("dns_ip") {
            it.isBlank() || checkNotLocalIp(it)
        }

        findPreferenceNotNull<DropDownPreference>("app_theme")
            .setOnPreferenceChangeListener { _, newValue ->
                setTheme(newValue as String)
                true
            }

        findPreferenceNotNull<DropDownPreference>("accent_color")
            .setOnPreferenceChangeListener { _, _ ->
                activity?.recreate()
                true
            }

        findPreferenceNotNull<DropDownPreference>("app_ui_mode")
            .setOnPreferenceChangeListener { _, _ ->
                activity?.recreate()
                true
            }

        findPreferenceNotNull<DropDownPreference>("app_language")
            .setOnPreferenceChangeListener { _, newValue ->
                context?.let { ctx ->
                    ctx.setLanguageSelected(true)
                    ctx.setAppLanguage(newValue as String)
                }
                activity?.recreate()
                true
            }

        val switchCommandLineSettings = findPreferenceNotNull<SwitchPreference>(
            "byedpi_enable_cmd_settings"
        )
        val uiSettings = findPreferenceNotNull<Preference>("byedpi_ui_settings")
        val cmdSettings = findPreferenceNotNull<Preference>("byedpi_cmd_settings")

        val setByeDpiSettingsMode = { enable: Boolean ->
            uiSettings.isEnabled = !enable
            cmdSettings.isEnabled = enable
        }

        setByeDpiSettingsMode(switchCommandLineSettings.isChecked)

        switchCommandLineSettings.setOnPreferenceChangeListener { _, newValue ->
            setByeDpiSettingsMode(newValue as Boolean)
            true
        }

        findPreferenceNotNull<Preference>("version").summary = BuildConfig.VERSION_NAME

        findPreference<Preference>("telegram_channel")?.setOnPreferenceClickListener {
            context?.let { ctx ->
                val (byedpiStatus, byedpiMode) = io.github.dovecoteescapee.byedpi.services.appStatus
                val isByeDpiVpn = byedpiMode == Mode.VPN && byedpiStatus == io.github.dovecoteescapee.byedpi.data.AppStatus.Running
                val isWarpVpn = io.github.dovecoteescapee.byedpi.warp.WarpVpnService.isRunning.value
                val isVlessVpn = io.github.dovecoteescapee.byedpi.vless.VlessVpnService.isRunning.value
                val isVpnActive = isByeDpiVpn || isWarpVpn || isVlessVpn

                if (isVpnActive) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Byedpirussia"))
                    ctx.startActivity(intent)
                } else {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.tg_vpn_warning_title)
                        .setMessage(R.string.tg_vpn_warning_msg)
                        .setPositiveButton(R.string.tg_vpn_warning_btn_warp) { dialog, _ ->
                            dialog.dismiss()
                            io.github.dovecoteescapee.byedpi.warp.WarpVpnService.start(ctx)
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Byedpirussia"))
                            ctx.startActivity(intent)
                        }
                        .setNegativeButton(R.string.tg_vpn_warning_btn_anyway) { dialog, _ ->
                            dialog.dismiss()
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Byedpirussia"))
                            ctx.startActivity(intent)
                        }
                        .show()
                }
            }
            true
        }

        findPreference<Preference>("rerun_initial_setup")?.setOnPreferenceClickListener {
            context?.let { ctx ->
                ctx.setInitialSetupDone(false)
                android.widget.Toast.makeText(
                    ctx,
                    "Первоначальная настройка будет запущена при открытии главного экрана",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            true
        }

        updatePreferences()
    }

    override fun onResume() {
        super.onResume()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun updatePreferences() {
        val mode = findPreferenceNotNull<ListPreference>("byedpi_mode")
            .value.let { Mode.fromString(it) }
        val dns = findPreferenceNotNull<EditTextPreference>("dns_ip")
        val ipv6 = findPreferenceNotNull<SwitchPreference>("ipv6_enable")

        when (mode) {
            Mode.VPN -> {
                dns.isVisible = true
                ipv6.isVisible = true
            }

            Mode.Proxy -> {
                dns.isVisible = false
                ipv6.isVisible = false
            }
        }
    }
}
