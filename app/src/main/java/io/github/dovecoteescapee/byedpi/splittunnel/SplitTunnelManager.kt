package io.github.dovecoteescapee.byedpi.splittunnel

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log

object SplitTunnelManager {
    private const val TAG = "SplitTunnelManager"
    private const val PREFS_NAME = "split_tunnel_prefs"
    private const val KEY_MODE = "split_tunnel_mode"
    private const val KEY_PACKAGES = "split_tunnel_packages"
    private const val KEY_EXCLUDE_RUSSIAN = "split_tunnel_exclude_russian"

    const val MODE_ALL = 0          // Весь трафик устройства
    const val MODE_WHITELIST = 1    // Только выбранные приложения (addAllowedApplication)
    const val MODE_BLACKLIST = 2    // Все приложения, кроме выбранных (addDisallowedApplication)

    // Префиксы и пакеты популярных российских сервисов, банков, госуслуг и маркетплейсов
    private val RUSSIAN_APP_PREFIXES = listOf(
        "ru.",
        "com.idamob.tinkoff",
        "com.yandex.",
        "com.vk.",
        "com.mail.",
        "com.sber.",
        "com.alfa.",
        "com.vtb.",
        "com.gazprombank.",
        "com.openbank.",
        "com.sovcombank.",
        "com.sovcomcard",
        "com.ubrr",
        "com.psbank.",
        "com.rosbank.",
        "com.mironline.",
        "com.cardmobile.",
        "com.bspb",
        "com.ftc.",
        "com.raiffeisen.",
        "com.ozon.",
        "com.wildberries.",
        "com.avito.",
        "com.hh.",
        "com.pochta.",
        "com.drom.",
        "com.auto.",
        "com.kinopoisk",
        "com.rutube.",
        "com.megafon.",
        "com.mts.",
        "com.beeline.",
        "com.tele2.",
        "com.rostelecom.",
        "com.domru."
    )

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

    fun isExcludeRussianAppsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_EXCLUDE_RUSSIAN, false)
    }

    fun setExcludeRussianAppsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EXCLUDE_RUSSIAN, enabled).apply()
    }

    fun isRussianApp(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return RUSSIAN_APP_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Возвращает список установленных российских приложений на устройстве.
     */
    fun getInstalledRussianPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                pm.getInstalledApplications(flags)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed applications", e)
            emptyList()
        }

        val ownPackage = context.applicationContext.packageName
        return installed
            .map { it.packageName }
            .filter { it != ownPackage && isRussianApp(it) }
            .toSet()
    }

    /**
     * Применяет правила раздельного туннелирования к VpnService.Builder
     */
    fun applySplitTunnel(builder: VpnService.Builder, context: Context) {
        val ownPackage = context.applicationContext.packageName

        // Разрешаем приложениям обходить VPN напрямую в физическую сеть (важно для обхода детектов)
        try {
            builder.allowBypass()
        } catch (e: Exception) {
            Log.w(TAG, "allowBypass not supported or failed", e)
        }

        // Всегда исключаем само приложение, чтобы локальные сокеты и туннели не замыкались сами на себя
        try {
            builder.addDisallowedApplication(ownPackage)
        } catch (_: Exception) {}

        val mode = getMode(context)
        val selectedPackages = getSelectedPackages(context)
        val excludeRussian = isExcludeRussianAppsEnabled(context)

        val russianPackages = if (excludeRussian) {
            getInstalledRussianPackages(context)
        } else {
            emptySet()
        }

        when (mode) {
            MODE_WHITELIST -> {
                if (selectedPackages.isNotEmpty()) {
                    for (pkg in selectedPackages) {
                        if (pkg != ownPackage) {
                            // Если включено авто-исключение росс. приложений, не добавляем их в whitelist
                            if (excludeRussian && russianPackages.contains(pkg)) {
                                continue
                            }
                            try {
                                builder.addAllowedApplication(pkg)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            MODE_BLACKLIST -> {
                // В черном списке исключаем выбранные + российские приложения
                val allDisallowed = selectedPackages.toMutableSet()
                if (excludeRussian) {
                    allDisallowed.addAll(russianPackages)
                }

                for (pkg in allDisallowed) {
                    if (pkg != ownPackage) {
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (_: Exception) {}
                    }
                }
            }
            MODE_ALL -> {
                // Если режим "весь трафик", но включено исключение росс. приложений:
                if (excludeRussian && russianPackages.isNotEmpty()) {
                    for (pkg in russianPackages) {
                        if (pkg != ownPackage) {
                            try {
                                builder.addDisallowedApplication(pkg)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }
}
