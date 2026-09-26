package io.github.dovecoteescapee.byedpi.splittunnel

import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build

object SplitTunnelManager {
    private const val PREFS_NAME = "split_tunnel_prefs"
    private const val KEY_MODE = "split_tunnel_mode"
    private const val KEY_PACKAGES = "split_tunnel_packages"

    const val MODE_ALL = 0          // Весь трафик устройства
    const val MODE_WHITELIST = 1    // Только выбранные приложения (addAllowedApplication)
    const val MODE_BLACKLIST = 2    // Все приложения, кроме выбранных (addDisallowedApplication)

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getMode(context: Context): Int {
        return getPrefs(context).getInt(KEY_MODE, MODE_ALL)
    }

    fun setMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_MODE, mode).apply()
    }

    fun getSelectedPackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setSelectedPackages(context: Context, packages: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_PACKAGES, packages).apply()
    }

    /**
     * Применяет правила раздельного туннелирования к VpnService.Builder
     */
    fun applySplitTunnel(builder: VpnService.Builder, context: Context) {
        val ownPackage = context.applicationContext.packageName

        // Всегда исключаем само приложение, чтобы локальные сокеты и туннели не замыкались сами на себя
        try {
            builder.addDisallowedApplication(ownPackage)
        } catch (_: Exception) {}

        val mode = getMode(context)
        val selectedPackages = getSelectedPackages(context)

        when (mode) {
            MODE_WHITELIST -> {
                if (selectedPackages.isNotEmpty()) {
                    for (pkg in selectedPackages) {
                        if (pkg != ownPackage) {
                            try {
                                builder.addAllowedApplication(pkg)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            MODE_BLACKLIST -> {
                for (pkg in selectedPackages) {
                    if (pkg != ownPackage) {
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (_: Exception) {}
                    }
                }
            }
            MODE_ALL -> {
                // Никаких дополнительных ограничений, туннелируется всё устройство
            }
        }
    }
}
