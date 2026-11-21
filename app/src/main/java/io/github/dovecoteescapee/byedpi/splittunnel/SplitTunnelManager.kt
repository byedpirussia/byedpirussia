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
        "su.",
        "by.",
        "kz.",
        "com.idamob.",
        "com.tcsbank.",
        "com.tbank.",
        "com.tinkoff.",
        "com.sber.",
        "com.sberbank.",
        "com.alfa.",
        "com.alfabank.",
        "com.vtb.",
        "com.vtb24.",
        "com.gazprom.",
        "com.gazprombank.",
        "com.openbank.",
        "com.sovcom.",
        "com.sovcombank.",
        "com.sovcomcard",
        "com.ubrr",
        "com.psbank.",
        "com.rosbank.",
        "com.raiffeisen.",
        "com.rshb.",
        "com.bspb",
        "com.ftc.",
        "com.cardmobile.",
        "com.mironline.",
        "com.mirpay.",
        "com.sbp.",
        "com.sbpay.",
        "com.yandex.",
        "com.vk.",
        "com.vkontakte.",
        "com.mail.",
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
        "com.domru.",
        "com.dublgis.",
        "com.twogis.",
        "com.akbars.",
        "com.mkb.",
        "com.rnkb.",
        "com.genbank.",
        "com.avangard.",
        "com.homecredit.",
        "com.otpbank."
    )

    private val RUSSIAN_APP_KEYWORDS = listOf(
        "sber", "sberbank", "tinkoff", "tbank", "t-bank", "tcsbank", "idamob",
        "vtb", "vtb24", "alfa", "alfabank", "gazprom", "gazprombank",
        "raiffeisen", "sovcom", "sovcombank", "halva", "rosbank",
        "openbank", "psbank", "promsvyaz", "rshb", "pochtabank",
        "letobank", "bspb", "ubrr", "akbars", "mkb", "rnkb", "genbank",
        "absolutbank", "avangard", "homecredit", "otpbank",
        "ozon", "wildberries", "wbwb", "avito", "yandex", "kinopoisk", "rutube",
        "megafon", "beeline", "tele2", "rostelecom", "domru",
        "gosuslugi", "mirpay", "mironline", "sbpay", "sbp", "dublgis", "2gis",
        "drom", "auto.ru", "pochta", "nalog", "mos.ru", "emias", "rzd",
        "korona", "rustore", "vkontakte", "mail.ru", "dzen"
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
        return getPrefs(context).getBoolean(KEY_EXCLUDE_RUSSIAN, true)
    }

    fun setExcludeRussianAppsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EXCLUDE_RUSSIAN, enabled).apply()
    }

    fun isRussianApp(packageName: String): Boolean {
        val lower = packageName.lowercase()
        if (RUSSIAN_APP_PREFIXES.any { lower.startsWith(it) }) return true
        return RUSSIAN_APP_KEYWORDS.any { lower.contains(it) }
    }

    // Популярные российские сервисы, банки, государственные порталы и маркетплейсы
    // (Не все .ru сайты, а именно ключевые российские порталы и сервисы)
    val EXCLUDED_RUSSIAN_DOMAINS = listOf(
        "gosuslugi.ru",
        "sberbank.ru",
        "sber.ru",
        "tinkoff.ru",
        "tbank.ru",
        "vtb.ru",
        "alfabank.ru",
        "raiffeisen.ru",
        "gazprombank.ru",
        "rshb.ru",
        "open.ru",
        "psbank.ru",
        "sovcombank.ru",
        "ozon.ru",
        "wildberries.ru",
        "avito.ru",
        "yandex.ru",
        "ya.ru",
        "mail.ru",
        "vk.com",
        "dzen.ru",
        "kinopoisk.ru",
        "rutube.ru",
        "mos.ru",
        "nalog.gov.ru",
        "pfr.gov.ru",
        "cbr.ru",
        "hh.ru",
        "pochta.ru",
        "megafon.ru",
        "mts.ru",
        "beeline.ru",
        "t2.ru",
        "tele2.ru",
        "rt.ru",
        "domru.ru"
    )

    fun isExcludedRussianDomain(domain: String): Boolean {
        val lower = domain.lowercase().trim()
        return EXCLUDED_RUSSIAN_DOMAINS.any { lower == it || lower.endsWith(".$it") }
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
                    var addedAny = false
                    for (pkg in selectedPackages) {
                        if (pkg != ownPackage) {
                            // Если включено авто-исключение росс. приложений, не добавляем их в whitelist
                            if (excludeRussian && russianPackages.contains(pkg)) {
                                continue
                            }
                            try {
                                builder.addAllowedApplication(pkg)
                                addedAny = true
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to addAllowedApplication: $pkg", e)
                            }
                        }
                    }
                    // Если ни одно приложение не смогло добавиться в whitelist, не оставляем VPN пустым
                    if (!addedAny) {
                        Log.w(TAG, "Whitelist was empty after filtering. Disallowing own package only.")
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
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to addDisallowedApplication: $pkg", e)
                        }
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
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to addDisallowedApplication: $pkg", e)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Устанавливает реальную физическую сеть (Wi-Fi или сотовую) в качестве underlying network для VPN.
     * Это гарантирует, что исключенные (disallowed) приложения подключаются напрямую через физическую сеть,
     * а не через виртуальный интерфейс tun0, благодаря чему российские банки и сервисы не видят VPN.
     */
    fun setUnderlyingPhysicalNetworks(vpnService: VpnService) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val currentNet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm?.activeNetwork?.takeIf { net ->
                        val c = cm.getNetworkCapabilities(net)
                        c != null && !c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                    }
                } else null ?: cm?.allNetworks?.firstOrNull { net ->
                    val c = cm.getNetworkCapabilities(net)
                    c != null && !c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                }
                vpnService.setUnderlyingNetworks(if (currentNet != null) arrayOf(currentNet) else null)
                Log.d(TAG, "Underlying physical network set: $currentNet")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set underlying physical networks", e)
            }
        }
    }
}
