package xyz.hlovet.portal.prototype

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.abs

internal data class NativeNotificationIntent(
    val notificationId: Int?,
    val messageId: Int?,
    val title: String?,
    val body: String?,
    val type: String?,
    val actionUrl: String?,
)

/**
 * A small foreground notification fallback for the native client.
 *
 * The web client uses Web Push/VAPID, while the optional Android bridge uses
 * FCM separately. This class remains the foreground fallback: it keeps a
 * per-account seen set and posts notifications discovered by the authenticated
 * poller, without treating an Android token as a browser subscription.
 */
internal object NativeLocalNotifications {
    const val ACTION_OPEN_NOTIFICATION = "xyz.hlovet.portal.prototype.OPEN_NOTIFICATION"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    private const val CHANNEL_ID = "hlovet-native-updates"
    private const val CHANNEL_NAME = "站内提醒"
    private const val PREFS = "native-local-notifications"
    private const val SEEN_PREFIX = "seen:"
    private const val INITIALIZED_PREFIX = "initialized:"
    private const val MAX_SEEN_IDS = 240

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "HLOVET 的聊天、评论和系统提醒"
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun isAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Seeds existing unread rows on first use, then returns only rows that are
     * both unread and new for this account. This prevents a fresh install from
     * replaying an old notification backlog.
     */
    fun takeNewUnread(context: Context, owner: String, notifications: List<NativeNotificationPreview>): List<NativeNotificationPreview> {
        val normalizedOwner = owner.trim().ifBlank { "anonymous" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seenKey = SEEN_PREFIX + normalizedOwner
        val initializedKey = INITIALIZED_PREFIX + normalizedOwner
        val currentIds = notifications.map { it.id }.filter { it > 0 }
        val seen = prefs.getStringSet(seenKey, emptySet()).orEmpty().toMutableSet()
        if (!prefs.getBoolean(initializedKey, false)) {
            seen += currentIds.map(Int::toString)
            saveSeen(prefs, seenKey, seen)
            prefs.edit().putBoolean(initializedKey, true).apply()
            return emptyList()
        }
        val fresh = notifications.filter { it.unread && it.id > 0 && it.id.toString() !in seen }
        seen += currentIds.map(Int::toString)
        saveSeen(prefs, seenKey, seen)
        return fresh
    }

    /** Records a notification delivered by FCM so the foreground poller will not duplicate it. */
    fun rememberSeen(context: Context, owner: String, notificationId: Int) {
        if (notificationId <= 0) return
        val normalizedOwner = owner.trim().ifBlank { "anonymous" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = SEEN_PREFIX + normalizedOwner
        val seen = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        seen += notificationId.toString()
        saveSeen(prefs, key, seen)
    }

    fun show(context: Context, notification: NativeNotificationPreview): Boolean {
        return showRemote(
            context = context,
            notificationId = notification.id,
            messageId = notification.messageId,
            title = notification.title,
            body = notification.body,
            type = notification.type,
            actionUrl = notification.actionUrl,
        )
    }

    fun showRemote(
        context: Context,
        notificationId: Int?,
        messageId: Int?,
        title: String,
        body: String,
        type: String?,
        actionUrl: String?,
    ): Boolean {
        if ((notificationId ?: messageId ?: 0) <= 0 && actionUrl.isNullOrBlank()) return false
        if (!isAllowed(context)) return false
        ensureChannel(context)
        val systemNotificationId = notificationId ?: messageId ?: stableNotificationId(type, actionUrl, body)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_NOTIFICATION
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, notificationId ?: 0)
            putExtra(EXTRA_NOTIFICATION_MESSAGE_ID, messageId ?: 0)
            putExtra(EXTRA_NOTIFICATION_TITLE, title)
            putExtra(EXTRA_NOTIFICATION_BODY, body)
            putExtra(EXTRA_NOTIFICATION_TYPE, type)
            putExtra(EXTRA_NOTIFICATION_URL, actionUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            systemNotificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val posted = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "HLOVET" })
            .setContentText(body.ifBlank { "你有一条新的站内提醒。" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(systemNotificationId, posted)
        return true
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun intentFrom(intent: Intent?): NativeNotificationIntent? {
        if (intent?.action != ACTION_OPEN_NOTIFICATION) return null
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0).takeIf { it > 0 }
        val messageId = intent.getIntExtra(EXTRA_NOTIFICATION_MESSAGE_ID, 0).takeIf { it > 0 }
        val actionUrl = intent.getStringExtra(EXTRA_NOTIFICATION_URL)?.takeIf { it.isNotBlank() }
        if (notificationId == null && messageId == null && actionUrl.isNullOrBlank()) return null
        return NativeNotificationIntent(
            notificationId = notificationId,
            messageId = messageId,
            title = intent.getStringExtra(EXTRA_NOTIFICATION_TITLE),
            body = intent.getStringExtra(EXTRA_NOTIFICATION_BODY),
            type = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE),
            actionUrl = actionUrl,
        )
    }

    fun notificationIdFrom(intent: Intent?): Int? = intentFrom(intent)?.notificationId

    private fun saveSeen(prefs: android.content.SharedPreferences, key: String, values: Set<String>) {
        val trimmed = values
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .takeLast(MAX_SEEN_IDS)
            .map(Int::toString)
            .toSet()
        prefs.edit().putStringSet(key, trimmed).apply()
    }

    private fun stableNotificationId(type: String?, actionUrl: String?, body: String): Int {
        val value = "${type.orEmpty()}|${actionUrl.orEmpty()}|$body".hashCode()
        return abs(if (value == Int.MIN_VALUE) 1 else value).coerceAtLeast(1)
    }

    private const val EXTRA_NOTIFICATION_MESSAGE_ID = "notification_message_id"
    private const val EXTRA_NOTIFICATION_TITLE = "notification_title"
    private const val EXTRA_NOTIFICATION_BODY = "notification_body"
    private const val EXTRA_NOTIFICATION_TYPE = "notification_type"
    private const val EXTRA_NOTIFICATION_URL = "notification_url"
}
