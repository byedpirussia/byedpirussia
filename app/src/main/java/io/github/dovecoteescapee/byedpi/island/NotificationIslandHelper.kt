package io.github.dovecoteescapee.byedpi.island

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.utility.isDynamicIslandEnabled
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object NotificationIslandHelper {

    private val sequence = AtomicLong(System.currentTimeMillis() / 1000)

    fun applyHyperOsFocus(
        builder: NotificationCompat.Builder,
        context: Context,
        currentMode: String
    ) {
        if (!context.isDynamicIslandEnabled()) {
            return
        }

        val modeName = when (currentMode) {
            ServiceSwitchController.MODE_BYEDPI -> "ByeDPI"
            ServiceSwitchController.MODE_WARP -> "WARP"
            ServiceSwitchController.MODE_VLESS -> "VLESS"
            ServiceSwitchController.MODE_OPENFLUX -> "OpenFLUX"
            else -> "VPN"
        }

        // 1. Метаданные HyperOS / MIUI Focus Notification (Фокусное уведомление / капсула в статус-баре)
        val extras = Bundle().apply {
            putBoolean("miui.focusNotice", true)
            putBoolean("miui.focusNotification", true)
            putString("miui.focusNotificationType", "custom")
            putBoolean("show_in_status_bar", true)
            putBoolean("miui.showActionInStatusBar", true)
            putInt("miui.focusNotificationLevel", 1)
            putString("miui.focus.ticker", "ByeDPI Russia: $modeName")

            // Создаем и передаем Bundle с иконками для динамического острова
            try {
                val icon = Icon.createWithResource(context, R.drawable.ic_notification)
                val pics = Bundle().apply {
                    putParcelable("miui.focus.pic_app_icon", icon)
                    putParcelable("miui.focus.pic_app_icon_dark", icon)
                    putParcelable("miui.focus.pic_small", icon)
                    putParcelable("miui.focus.pic_small_dark", icon)
                    putParcelable("miui.focus.pic_ticker", icon)
                    putParcelable("miui.focus.pic_ticker_dark", icon)
                }
                putBundle("miui.focus.pics", pics)
            } catch (e: Exception) {
                // Игнорируем ошибки загрузки иконки
            }

            // Формируем JSON-параметры для рендеринга Dynamic Island на Xiaomi HyperOS
            try {
                putString("miui.focus.param", buildHyperIslandJson(modeName))
            } catch (e: Exception) {
                // Игнорируем ошибки сериализации JSON
            }
        }

        builder.addExtras(extras)
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        builder.setOngoing(true)

        // 2. Добавляем Action-кнопки переключения режимов прямо в уведомление / остров
        when (currentMode) {
            ServiceSwitchController.MODE_BYEDPI -> {
                builder.addAction(
                    R.drawable.ic_bolt_24,
                    "⚡ WARP",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_WARP)
                )
                builder.addAction(
                    R.drawable.ic_shield_check_24,
                    "🌐 VLESS",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_VLESS)
                )
                builder.addAction(
                    R.drawable.ic_bolt_24,
                    "🌊 OpenFLUX",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_OPENFLUX)
                )
                builder.addAction(
                    R.drawable.ic_power_24,
                    "⏹️ Стоп",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_STOP)
                )
            }
            ServiceSwitchController.MODE_WARP -> {
                builder.addAction(
                    R.drawable.ic_shield_check_24,
                    "🛡️ ByeDPI",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_BYEDPI)
                )
                builder.addAction(
                    R.drawable.ic_bolt_24,
                    "🌐 VLESS",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_VLESS)
                )
                builder.addAction(
                    R.drawable.ic_bolt_24,
                    "🌊 OpenFLUX",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_OPENFLUX)
                )
                builder.addAction(
                    R.drawable.ic_power_24,
                    "⏹️ Стоп",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_STOP)
                )
            }
            ServiceSwitchController.MODE_VLESS -> {
                builder.addAction(
                    R.drawable.ic_shield_check_24,
                    "🛡️ ByeDPI",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_BYEDPI)
                )
                builder.addAction(
                    R.drawable.ic_bolt_24,
                    "⚡ WARP",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_WARP)
                )
                builder.addAction(
                    R.drawable.ic_bolt_24,
                    "🌊 OpenFLUX",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_OPENFLUX)
                )
                builder.addAction(
                    R.drawable.ic_power_24,
                    "⏹️ Стоп",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_STOP)
                )
            }
            ServiceSwitchController.MODE_OPENFLUX -> {
                builder.addAction(
                    R.drawable.ic_shield_check_24,
                    "🛡️ ByeDPI",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_BYEDPI)
                )
                builder.addAction(
                    R.drawable.ic_bolt_24,
                    "⚡ WARP",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_WARP)
                )
                builder.addAction(
                    R.drawable.ic_shield_check_24,
                    "🌐 VLESS",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_VLESS)
                )
                builder.addAction(
                    R.drawable.ic_power_24,
                    "⏹️ Стоп",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_STOP)
                )
            }
            else -> {
                builder.addAction(
                    R.drawable.ic_power_24,
                    "⏹️ Стоп",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_STOP)
                )
            }
        }
    }

    private fun buildHyperIslandJson(modeName: String): String {
        val now = System.currentTimeMillis()
        val business = "byedpi_service"

        val params = JSONObject().apply {
            put("business", business)
            put("protocol", 1)
            put("orderId", "byedpi_status")
            put("islandFirstFloat", true)
            put("enableFloat", true)
            put("updatable", true)
            put("outEffectSrc", "")
            put("reopen", "reopen")
            put("sequence", sequence.incrementAndGet())
            put("aodTitle", "ByeDPI Russia: $modeName")

            put("baseInfo", JSONObject().apply {
                put("type", 2)
                put("title", "ByeDPI Russia")
                put("content", "Режим: $modeName • 🟢 Защита активна")
                put("subTitle", "")
                put("extraTitle", "")
                put("specialTitle", "")
                put("subContent", "")
                put("picFunction", "")
                put("showDivider", true)
                put("showContentDivider", false)
                put("colorTitle", "#FFFFFF")
                put("colorTitleDark", "#FFFFFF")
                put("colorContent", "#2ECC71")
                put("colorContentDark", "#2ECC71")
            })

            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "miui.focus.pic_app_icon")
            })

            put("hintInfo", JSONObject().apply {
                put("type", 2)
                put("content", "Активный режим")
                put("title", modeName)
                put("timerInfo", JSONObject().apply {
                    put("timerType", 0)
                    put("timerWhen", 0L)
                    put("timerTotal", 0L)
                    put("timerSystemCurrent", now)
                })
                put("subContent", "Статус соединения")
                put("subTitle", "🟢 Подключено")
                put("colorContent", "#AAAAAA")
                put("colorContentDark", "#AAAAAA")
                put("colorTitle", "#FFFFFF")
                put("colorTitleDark", "#FFFFFF")
                put("colorSubContent", "#AAAAAA")
                put("colorSubContentDark", "#AAAAAA")
                put("colorSubTitle", "#2ECC71")
                put("colorSubTitleDark", "#2ECC71")
            })

            put("param_island", JSONObject().apply {
                put("islandProperty", 1)
                put("islandTimeout", 7 * 24 * 3600)
                put("bigIslandArea", JSONObject().apply {
                    put("templateNo", 2)
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("textInfo", JSONObject().apply {
                            put("title", modeName)
                            put("content", "В сети")
                            put("showHighlightColor", false)
                            put("narrowFont", false)
                        })
                    })
                    put("textInfo", JSONObject().apply {
                        put("frontTitle", "")
                        put("title", "🟢 ByeDPI Russia")
                        put("content", "Нажмите для переключения")
                        put("showHighlightColor", false)
                        put("narrowFont", false)
                    })
                })
                put("smallIslandArea", JSONObject().apply {
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_small")
                        put("picDark", "miui.focus.pic_small_dark")
                    })
                })
            })
        }

        return JSONObject().put("param_v2", params).toString()
    }
}
