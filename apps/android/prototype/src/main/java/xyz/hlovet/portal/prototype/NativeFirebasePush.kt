package xyz.hlovet.portal.prototype

import android.content.Context
import android.provider.Settings
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 判断 Firebase 配置是否完整；空值或只填写部分参数时必须保持可选能力关闭。 */
internal fun isNativeFirebaseConfigured(
    apiKey: String,
    projectId: String,
    applicationId: String,
    senderId: String,
): Boolean {
    // 只有四个公开 Firebase 标识都存在时，应用才允许初始化 SDK 并申请设备令牌。
    return apiKey.isNotBlank() && projectId.isNotBlank() && applicationId.isNotBlank() && senderId.isNotBlank()
}

/** Optional FCM bridge. Empty Gradle Firebase properties intentionally disable it. */
internal object NativeFirebasePush {
    private const val PREFS = "native-firebase-push"
    private const val TOKEN_KEY = "token"
    private const val INSTALLATION_KEY = "installation-id"

    /** 返回当前构建是否提供完整 Firebase 参数；未配置时不触碰 Firebase SDK。 */
    fun isConfigured(): Boolean {
        // 将 BuildConfig 的构建注入值交给纯函数判断，避免局部缺项被误当成已启用。
        return isNativeFirebaseConfigured(
            apiKey = BuildConfig.FIREBASE_API_KEY,
            projectId = BuildConfig.FIREBASE_PROJECT_ID,
            applicationId = BuildConfig.FIREBASE_APPLICATION_ID,
            senderId = BuildConfig.FIREBASE_SENDER_ID,
        )
    }

    fun initialize(context: Context): Boolean {
        if (!isConfigured()) return false
        return runCatching {
            FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            true
        }.getOrDefault(false)
    }

    fun cachedToken(context: Context): String? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(TOKEN_KEY, null)
        ?.takeIf(String::isNotBlank)

    fun saveToken(context: Context, token: String) {
        if (token.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(TOKEN_KEY, token)
            .apply()
    }

    fun installationId(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.getString(INSTALLATION_KEY, null)?.takeIf(String::isNotBlank)?.let { return it }
        val generated = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf(String::isNotBlank)
        }.getOrNull() ?: UUID.randomUUID().toString()
        preferences.edit().putString(INSTALLATION_KEY, generated).apply()
        return generated
    }

    suspend fun currentToken(context: Context): String? = withContext(Dispatchers.IO) {
        cachedToken(context) ?: runCatching {
            if (!initialize(context)) return@runCatching null
            Tasks.await(FirebaseMessaging.getInstance().token, 10, TimeUnit.SECONDS)
                .also { saveToken(context, it) }
        }.getOrNull()
    }

    suspend fun sync(context: Context, accessToken: String): Boolean {
        val token = currentToken(context) ?: return false
        return runCatching {
            NativeSocialApi.registerNativePushToken(
                accessToken = accessToken,
                token = token,
                appId = BuildConfig.APPLICATION_ID,
                deviceId = installationId(context),
            )
            true
        }.getOrDefault(false)
    }

    suspend fun unregister(context: Context, accessToken: String): Boolean {
        val token = cachedToken(context) ?: return false
        return runCatching {
            NativeSocialApi.unregisterNativePushToken(accessToken, token)
            true
        }.getOrDefault(false)
    }
}

class NativeFirebaseMessagingService : com.google.firebase.messaging.FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        NativeFirebasePush.saveToken(this, token)
        val session = NativeSessionStore.load(this) ?: return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { NativeFirebasePush.sync(this@NativeFirebaseMessagingService, session.accessToken) }
        }
    }

    override fun onMessageReceived(message: com.google.firebase.messaging.RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "HLOVET"
        val body = data["body"] ?: message.notification?.body ?: "你有一条新的站内提醒。"
        val notificationId = data["notificationId"]?.toIntOrNull()
        val session = NativeSessionStore.load(this) ?: return
        val owner = NativeOfflineOwner.forSession(session)
        if (notificationId != null) NativeLocalNotifications.rememberSeen(this, owner, notificationId)
        NativeLocalNotifications.showRemote(
            context = this,
            notificationId = notificationId,
            messageId = data["messageId"]?.toIntOrNull(),
            title = title,
            body = body,
            type = data["type"],
            actionUrl = data["url"],
        )
    }
}
