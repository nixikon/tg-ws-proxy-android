package com.nixikon.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The status-bar notification replaces the desktop tray icon: it carries the
 * same quick actions (open in Telegram, copy link, restart, stop) and shows the
 * current listen address.
 *
 * A second, separate channel announces a new release when the app is not on
 * screen — for instance when the proxy was started from the Quick Settings tile
 * and there is no activity to show a dialog on.
 */
object Notifications {

    /**
     * Channel id of the running-proxy notification.
     *
     * Renamed to `_v2` in 1.11.0-a7 on purpose: an existing channel's importance
     * cannot be changed after it has been created, and the old one was created
     * as IMPORTANCE_LOW ("Silent"). Several vendor ROMs — MIUI and friends — use
     * exactly that to decide that a notification deserves no status-bar icon, so
     * the "T" next to the clock never appeared there. The new channel is
     * IMPORTANCE_DEFAULT with the sound and vibration switched off: it counts as
     * a normal notification, but still does not beep.
     */
    const val CHANNEL_ID = "proxy_status_v2"

    /** Pre-1.11.0-a7 channel, deleted on first run of the new build. */
    const val LEGACY_CHANNEL_ID = "proxy_status"

    const val NOTIF_ID = 1001

    const val CHANNEL_UPDATE_ID = "proxy_updates"
    const val NOTIF_ID_UPDATE = 1002

    fun createChannel(ctx: Context) {
        createStatusChannel(ctx)
        createUpdateChannel(ctx)
    }

    private fun createStatusChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return

        // Drop the old silent channel, so the settings screen does not show two
        // entries for the same thing.
        try {
            if (mgr.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
                mgr.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            }
        } catch (_: Exception) {
            // Nothing to clean up.
        }

        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = ctx.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            // Quiet, but not "silent": the sound is removed rather than the
            // importance lowered, because the importance is what the ROMs look
            // at when deciding whether to draw the status-bar icon.
            setSound(null, null)
            enableVibration(false)
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Importance of the proxy channel, or [NotificationManager.IMPORTANCE_DEFAULT]
     * when there is nothing to report (pre-O, or the channel is not created yet).
     */
    fun statusChannelImportance(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return NotificationManager.IMPORTANCE_DEFAULT
        }
        val mgr = ctx.getSystemService(NotificationManager::class.java)
            ?: return NotificationManager.IMPORTANCE_DEFAULT
        return mgr.getNotificationChannel(CHANNEL_ID)?.importance
            ?: NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun createUpdateChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_UPDATE_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_UPDATE_ID,
            ctx.getString(R.string.notif_update_channel_name),
            // DEFAULT, not LOW: a new release is worth an audible heads-up.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = ctx.getString(R.string.notif_update_channel_desc)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun flags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun activityIntent(ctx: Context, action: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(ctx, action?.hashCode() ?: 0, intent, flags())
    }

    private fun serviceIntent(ctx: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, ProxyService::class.java).setAction(action)
        return PendingIntent.getService(ctx, requestCode, intent, flags())
    }

    fun build(ctx: Context, text: String, running: Boolean): Notification {
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            // DEFAULT, matching the channel: a lower priority is what let some
            // ROMs drop the status-bar icon.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(activityIntent(ctx, null))

        if (running) {
            // Only the two actions the user asked for. Stopping is available from
            // the Quick Settings tile and from the app itself.
            builder.addAction(
                R.drawable.ic_stat_proxy,
                ctx.getString(R.string.notif_action_open_tg),
                activityIntent(ctx, MainActivity.ACTION_OPEN_TG),
            )
            builder.addAction(
                R.drawable.ic_stat_proxy,
                ctx.getString(R.string.notif_action_restart),
                serviceIntent(ctx, ProxyService.ACTION_RESTART, 11),
            )
        }

        return builder.build()
    }

    /**
     * Announces [version] with a single *Download* action. Shown only when no
     * activity of ours is on screen, because otherwise the in-app dialog is the
     * better place for it.
     */
    fun showUpdate(ctx: Context, version: String, currentVersion: String, notes: String) {
        val text = ctx.getString(R.string.notif_update_text, currentVersion)
        val builder = NotificationCompat.Builder(ctx, CHANNEL_UPDATE_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(ctx.getString(R.string.notif_update_title, version))
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (notes.isEmpty()) text else "$text\n\n$notes"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(ctx, MainActivity.ACTION_SHOW_UPDATE))
            .addAction(
                R.drawable.ic_stat_proxy,
                ctx.getString(R.string.notif_action_download),
                activityIntent(ctx, MainActivity.ACTION_DOWNLOAD_UPDATE),
            )

        try {
            NotificationManagerCompat.from(ctx)
                .notify(NOTIF_ID_UPDATE, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted: the tile/app still works, the user
            // just learns about the release next time the app is opened.
        }
    }

    /** Drops the release notification, e.g. once the update is under way. */
    fun cancelUpdate(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID_UPDATE)
        } catch (_: SecurityException) {
            // Nothing to cancel without the permission.
        }
    }
}
