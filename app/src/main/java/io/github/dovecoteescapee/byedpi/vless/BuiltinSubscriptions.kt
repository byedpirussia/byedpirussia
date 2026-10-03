package io.github.dovecoteescapee.byedpi.vless

data class BuiltinSubscription(
    val id: String,
    val title: String,
    val badge: String,
    val description: String,
    val url: String
)

object BuiltinSubscriptions {
    val LIST = listOf(
        BuiltinSubscription(
            id = "etonelya_wl",
            title = "✌️ EtoNeYa Whitelist",
            badge = "~500-1500 серверов",
            description = "EtoNeYa Whitelist UTF-8 для белых списков мобильных операторов",
            url = "https://ru-wbl.gitverse.site/wl/EtoNeYa/EtoNeYa_wl.txt"
        ),
        BuiltinSubscription(
            id = "rostun_gen",
            title = "🏳️‍ РосТуннель (Отобранная Б/С)",
            badge = "~20 серверов",
            description = "Отобранная Б/С в UTF-8 от РосТуннель",
            url = "https://ru-wbl.gitverse.site/wl/RosTun/utf-8_gen.txt"
        ),
        BuiltinSubscription(
            id = "rostun_wl",
            title = "🏳️‍ РосТуннель (Белый список)",
            badge = "~1500 серверов",
            description = "Большой белый список в UTF-8 VLESS от РосТуннель",
            url = "https://ru-wbl.gitverse.site/wl/RosTun/utf-8_wl.txt"
        ),
        BuiltinSubscription(
            id = "zieng2_nm",
            title = "😺 Zieng2 ~ NowMeow",
            badge = "30 серверов",
            description = "Отобранные Б/С для Zieng2 от проекта NowMeow",
            url = "https://ru-wbl.gitverse.site/wl/Zieng2/Zieng2_NowMeow.txt"
        ),
        BuiltinSubscription(
            id = "zieng2_lite",
            title = "😺 Zieng2 (Vless Lite)",
            badge = "~1000 серверов",
            description = "Протестированная база во время Б/С, множество подсетей Vless",
            url = "https://ru-wbl.gitverse.site/wl/Zieng2/Zieng2_vless_lite.txt"
        ),
        BuiltinSubscription(
            id = "limevpn",
            title = "🍈 LimeVPN Free",
            badge = "Free Б/С",
            description = "Бесплатная подписка LimeVPN для обхода блокировок",
            url = "https://ru-wbl.gitverse.site/wl/LimeVPN/LimeVPN_Free.txt"
        ),
        BuiltinSubscription(
            id = "kvru_vpn",
            title = "🛡️ KvRuVPN",
            badge = "Б/С",
            description = "Конфиги KvRuVPN из коллекции KWN-конфигов",
            url = "https://ru-wbl.gitverse.site/wl/KvRuVPN/KvRuVPN.txt"
        )
    )

    fun findByUrl(url: String): BuiltinSubscription? {
        val trimmed = url.trim()
        return LIST.firstOrNull { it.url.equals(trimmed, ignoreCase = true) }
    }

    fun getDisplayName(url: String, fallbackIndex: Int = -1): String {
        findByUrl(url)?.let { return it.title }
        // If not a builtin, extract human-friendly name from URL if possible
        try {
            val uri = android.net.Uri.parse(url)
            val lastSegment = uri.lastPathSegment?.removeSuffix(".txt")
            if (!lastSegment.isNullOrBlank() && lastSegment.length > 2) {
                return lastSegment
            }
            val host = uri.host
            if (!host.isNullOrBlank()) {
                return host
            }
        } catch (_: Exception) {}
        return if (fallbackIndex >= 0) "Подписка ${fallbackIndex + 1}" else "Подписка"
    }
}
