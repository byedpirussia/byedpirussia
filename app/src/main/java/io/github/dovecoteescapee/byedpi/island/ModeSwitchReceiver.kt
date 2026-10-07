package io.github.dovecoteescapee.byedpi.island

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ModeSwitchReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SWITCH_MODE = "io.github.dovecoteescapee.byedpi.ACTION_SWITCH_MODE"
        const val EXTRA_TARGET_MODE = "target_mode"

        fun createPendingIntent(context: Context, targetMode: String): PendingIntent {
            val intent = Intent(context, ModeSwitchReceiver::class.java).apply {
                action = ACTION_SWITCH_MODE
                putExtra(EXTRA_TARGET_MODE, targetMode)
            }
            val requestCode = targetMode.hashCode()
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_SWITCH_MODE) {
            val targetMode = intent.getStringExtra(EXTRA_TARGET_MODE) ?: return
            ServiceSwitchController.switchTo(context, targetMode)
        }
    }
}
