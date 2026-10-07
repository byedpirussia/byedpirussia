package io.github.dovecoteescapee.byedpi.island

import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R

object NotificationIslandHelper {

    fun applyHyperOsFocus(builder: NotificationCompat.Builder, context: Context, currentMode: String) {
        // 1. Метаданные HyperOS / MIUI Focus Notification (Фокусное уведомление / капсула в статус-баре)
        val extras = Bundle().apply {
            putBoolean("miui.focusNotice", true)
            putBoolean("miui.focusNotification", true)
            putString("miui.focusNotificationType", "custom")
            putBoolean("show_in_status_bar", true)
            putBoolean("miui.showActionInStatusBar", true)
            putInt("miui.focusNotificationLevel", 1)
        }
        builder.addExtras(extras)
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE)

        // 2. Добавляем Action-кнопки переключения режимов прямо в уведомление/остров
        when (currentMode) {
            ServiceSwitchController.MODE_BYEDPI -> {
                builder.addAction(
                    R.drawable.ic_shield_check_24,
                    "⚡ WARP",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_WARP)
                )
                builder.addAction(
                    R.drawable.ic_bolt_24,
                    "🌐 VLESS",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_VLESS)
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
                    R.drawable.ic_shield_check_24,
                    "⚡ WARP",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_WARP)
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
                    R.drawable.ic_shield_check_24,
                    "⚡ WARP",
                    ModeSwitchReceiver.createPendingIntent(context, ServiceSwitchController.MODE_WARP)
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
}
