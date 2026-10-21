package io.github.dovecoteescapee.byedpi.utility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.STOP_ACTION

fun registerNotificationChannel(context: Context, id: String, @StringRes name: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            id,
            context.getString(name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setShowBadge(false)

        manager.createNotificationChannel(channel)
    }
}

fun createConnectionNotification(
    context: Context,
    channelId: String,
    @StringRes title: Int,
    @StringRes content: Int,
    service: Class<*>,
    isPaused: Boolean = false,
): Notification {
    val stopIntent = Intent(context, service).apply {
        action = STOP_ACTION
    }
    val stopPendingIntent = PendingIntent.getService(
        context,
        101,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val pauseAction = if (isPaused) {
        val resumeIntent = Intent(context, service).apply {
            action = io.github.dovecoteescapee.byedpi.data.RESUME_ACTION
        }
        val resumePendingIntent = PendingIntent.getService(
            context,
            102,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Action.Builder(
            R.drawable.ic_play_arrow_24,
            context.getString(R.string.action_resume),
            resumePendingIntent
        ).build()
    } else {
        val pauseIntent = Intent(context, service).apply {
            action = io.github.dovecoteescapee.byedpi.data.PAUSE_ACTION
        }
        val pausePendingIntent = PendingIntent.getService(
            context,
            102,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Action.Builder(
            R.drawable.ic_pause_24,
            context.getString(R.string.action_pause),
            pausePendingIntent
        ).build()
    }

    val stopAction = NotificationCompat.Action.Builder(
        R.drawable.ic_stop_24,
        context.getString(R.string.action_stop),
        stopPendingIntent
    ).build()

    val contentText = if (isPaused) {
        "${context.getString(content)} (${context.getString(R.string.notification_paused)})"
    } else {
        context.getString(content)
    }

    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setContentTitle(context.getString(title))
        .setContentText(contentText)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(pauseAction)
        .addAction(stopAction)
        .setOngoing(true)

    io.github.dovecoteescapee.byedpi.island.NotificationIslandHelper.applyHyperOsFocus(
        builder,
        context,
        io.github.dovecoteescapee.byedpi.island.ServiceSwitchController.MODE_BYEDPI
    )

    return builder.build()
}
