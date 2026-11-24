package io.github.yhzion.velo.updates

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

internal enum class NoticeAction { IgnoreStaleCheck, Keep, Cancel, Post }

internal fun noticeAction(generation: Long, current: Long?, candidate: Long?, announced: Long, lastNotice: Long): NoticeAction = when {
    generation != current -> NoticeAction.IgnoreStaleCheck
    candidate == null -> NoticeAction.Cancel
    candidate > announced -> NoticeAction.Post
    candidate == lastNotice -> NoticeAction.Keep
    else -> NoticeAction.Cancel
}

internal object UpdateNotice {
    private const val CHANNEL = "app_updates"
    private const val ID = 0x555044

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    fun post(context: Context, release: AppRelease): Boolean {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val channel = manager.getNotificationChannel(CHANNEL) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false

        // The feed has verified the canonical, tag-specific GitHub URL. Never open an asset or /latest.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.notesUrl)).addCategory(Intent.CATEGORY_BROWSABLE)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val label = context.applicationInfo.loadLabel(context.packageManager)
        val note = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("$label ${release.tag.removePrefix("v")} is available")
            .setContentText("View release notes on GitHub")
            .setContentIntent(open).setAutoCancel(true).build()
        return try {
            manager.notify(ID, note)
            true
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and the notify call.
            false
        }
    }
}
