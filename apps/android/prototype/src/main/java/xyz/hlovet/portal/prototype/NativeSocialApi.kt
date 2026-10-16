package xyz.hlovet.portal.prototype

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 保留社交接口 HTTP 状态，供定位入口区分内容不可见与网络或服务异常。 */
internal class NativeSocialHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

internal data class NativeConversationPreview(
    val id: Int,
    val title: String,
    val initials: String,
    val message: String,
    val time: String,
    val unreadCount: Int,
    val kind: String,
    val groupId: Int? = null,
    val muted: Boolean = false,
)

internal enum class NativeNotificationActionType {
    FriendRequest,
    StrangerMessageRequest,
    GroupInvitation,
    GroupJoinRequest,
    GroupReport,
    None,
}

internal fun nativeNotificationActionType(contextKind: String?, actionable: Boolean): NativeNotificationActionType {
    if (!actionable) return NativeNotificationActionType.None
    return when (contextKind?.trim()) {
        "friend_request" -> NativeNotificationActionType.FriendRequest
        "stranger_message_request" -> NativeNotificationActionType.StrangerMessageRequest
        "group_invitation" -> NativeNotificationActionType.GroupInvitation
        "group_join_request" -> NativeNotificationActionType.GroupJoinRequest
        "group_report" -> NativeNotificationActionType.GroupReport
        else -> NativeNotificationActionType.None
    }
}

internal data class NativeNotificationPreview(
    val id: Int,
    val title: String,
    val body: String,
    val groupName: String? = null,
    val requestNote: String? = null,
    val requestBody: String? = null,
    val commentBody: String? = null,
    val contextKind: String? = null,
    val contextStatus: String? = null,
    val reportMessage: NativeChatMessage? = null,
    val createdAt: String = "",
    val unread: Boolean,
    val channel: String = "system",
    val type: String = "",
    val actionUrl: String? = null,
    val friendshipId: Int? = null,
    val strangerRequest: Boolean = false,
    val messageDeleted: Boolean = false,
    val commentDeleted: Boolean = false,
    val conversationId: Int? = null,
    val messageId: Int? = null,
    val articleSlug: String? = null,
    val commentId: Int? = null,
    val actionType: NativeNotificationActionType = NativeNotificationActionType.None,
    val groupId: Int? = null,
    val invitationId: Int? = null,
    val joinRequestId: Int? = null,
    val requestId: Int? = null,
    val reportId: Int? = null,
)

internal data class NativeInbox(
    val unreadMessages: Int,
    val pendingFriendRequests: Int,
    val pendingStrangerRequests: Int,
    val unreadNotifications: Int,
    val conversations: List<NativeConversationPreview>,
    val notifications: List<NativeNotificationPreview>,
    val notificationsHasMore: Boolean = false,
    val hiddenNotificationChannels: List<String> = emptyList(),
    val notificationChannelStates: List<NativeNotificationChannelState> = emptyList(),
)

internal data class NativeNotificationPage(
    val items: List<NativeNotificationPreview>,
    val hasMore: Boolean,
    val hiddenChannels: List<String> = emptyList(),
    val channelStates: List<NativeNotificationChannelState> = emptyList(),
)

/** Lightweight counts used by the app shell; the full inbox is intentionally not fetched for every badge refresh. */
internal data class NativeInboxSummary(
    val unreadMessages: Int,
    val pendingFriendRequests: Int,
    val pendingStrangerRequests: Int,
    val unreadNotifications: Int,
) {
    val totalUnread: Int
        get() = unreadMessages + pendingFriendRequests + pendingStrangerRequests + unreadNotifications
}

internal fun NativeInbox.toSummary(): NativeInboxSummary = NativeInboxSummary(
    unreadMessages = unreadMessages,
    pendingFriendRequests = pendingFriendRequests,
    pendingStrangerRequests = pendingStrangerRequests,
    unreadNotifications = unreadNotifications,
)

internal data class NativeNotificationChannelState(
    val channel: String,
    val hiddenThroughNotificationId: Int,
    val pushEnabled: Boolean,
    val digestEnabled: Boolean,
)

internal data class NativeChatAttachmentPreview(
    val id: Int,
    val conversationId: Int,
    val kind: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val thumbnailUrl: String?,
    val createdAt: String,
)

internal data class NativeChatQuote(
    val id: Int,
    val body: String,
    val senderName: String,
    val available: Boolean,
)

/** 保存聊天和群举报共用的消息内容；可选头像仅作展示，不影响身份和附件授权。 */
internal data class NativeChatMessage(
    val id: Int,
    val conversationId: Int,
    val body: String,
    val type: String,
    val senderName: String,
    val senderUsername: String,
    val senderInitials: String,
    val quote: NativeChatQuote?,
    val isPinned: Boolean,
    val createdAt: String,
    val attachments: List<NativeChatAttachmentPreview>,
    val senderAvatarUrl: String? = null,
)

internal data class NativeConversationMessages(
    val items: List<NativeChatMessage>,
    val hasMore: Boolean,
)

internal data class NativeMessageSearchResult(
    val items: List<NativeChatMessage>,
    val hasMore: Boolean,
)

internal data class NativeCallSession(
    val id: Int,
    val conversationId: Int,
    val type: String,
    val status: String,
    val callerId: Int,
    val calleeId: Int,
    val userId: Int,
    val userName: String,
    val userUsername: String,
    val acceptedAt: String?,
)

internal data class NativeIceServer(
    val urls: List<String>,
    val username: String?,
    val credential: String?,
)

/** 负责原生端消息、通知和推送接口读取，统一解析服务端权限范围内的上下文。 */
internal object NativeSocialApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun loadInbox(accessToken: String): NativeInbox {
        val summary = requestGet("/social/summary", accessToken)
        val conversations = requestGet("/social/conversations", accessToken)
            .optJSONArray("items")
            .toConversations()
        val notificationResponse = requestGet("/social/notifications?limit=20", accessToken)
        val notifications = notificationResponse.optJSONArray("items").toNotifications()
        return NativeInbox(
            unreadMessages = summary.optInt("unreadMessages"),
            pendingFriendRequests = summary.optInt("pendingFriendRequests"),
            pendingStrangerRequests = summary.optInt("pendingStrangerRequests"),
            unreadNotifications = summary.optInt("unreadNotifications"),
            conversations = conversations,
            notifications = notifications,
            notificationsHasMore = notificationResponse.optBoolean("hasMore"),
            hiddenNotificationChannels = notificationResponse.optJSONArray("hiddenChannels").toStrings(),
            notificationChannelStates = notificationResponse.optJSONArray("channelStates").toChannelStates(),
        )
    }

    fun listNotifications(
        accessToken: String,
        beforeId: Int? = null,
        channel: String? = null,
        limit: Int = 20,
    ): NativeNotificationPage {
        val query = buildString {
            append("?limit=")
            append(limit.coerceIn(1, 50))
            if (beforeId != null && beforeId > 0) {
                append("&beforeId=")
                append(beforeId)
            }
            if (!channel.isNullOrBlank()) {
                append("&channel=")
                append(java.net.URLEncoder.encode(channel, Charsets.UTF_8.name()))
            }
        }
        val response = requestGet("/social/notifications$query", accessToken)
        return NativeNotificationPage(
            items = response.optJSONArray("items").toNotifications(),
            hasMore = response.optBoolean("hasMore"),
            hiddenChannels = response.optJSONArray("hiddenChannels").toStrings(),
            channelStates = response.optJSONArray("channelStates").toChannelStates(),
        )
    }

    fun loadInboxSummary(accessToken: String): NativeInboxSummary {
        val summary = requestGet("/social/summary", accessToken)
        return NativeInboxSummary(
            unreadMessages = summary.optInt("unreadMessages"),
            pendingFriendRequests = summary.optInt("pendingFriendRequests"),
            pendingStrangerRequests = summary.optInt("pendingStrangerRequests"),
            unreadNotifications = summary.optInt("unreadNotifications"),
        )
    }

    fun registerNativePushToken(
        accessToken: String,
        token: String,
        appId: String,
        deviceId: String,
    ) {
        requestJsonMutation(
            "/push/native-subscriptions",
            "POST",
            accessToken,
            JSONObject().apply {
                put("token", token)
                put("platform", "android")
                put("appId", appId)
                put("deviceId", deviceId)
            },
        )
    }

    fun unregisterNativePushToken(accessToken: String, token: String) {
        requestJsonMutation(
            "/push/native-subscriptions",
            "DELETE",
            accessToken,
            JSONObject().put("token", token).put("platform", "android"),
        )
    }

    fun updateNotificationChannelSettings(
        accessToken: String,
        channel: String,
        pushEnabled: Boolean? = null,
        digestEnabled: Boolean? = null,
    ): NativeNotificationChannelState {
        require(pushEnabled != null || digestEnabled != null) { "至少需要更新一项通知设置。" }
        val body = JSONObject().apply {
            pushEnabled?.let { put("pushEnabled", it) }
            digestEnabled?.let { put("digestEnabled", it) }
        }
        return requestJsonMutation(
            "/social/notification-channels/${java.net.URLEncoder.encode(channel, Charsets.UTF_8.name())}/settings",
            "PATCH",
            accessToken,
            body,
        ).toChannelState()
    }

    fun hideNotificationChannel(accessToken: String, channel: String) {
        requestMutation(
            "/social/notification-channels/${java.net.URLEncoder.encode(channel, Charsets.UTF_8.name())}",
            "DELETE",
            accessToken,
        )
    }

    fun listMessages(
        accessToken: String,
        conversationId: Int,
        beforeId: Int? = null,
        limit: Int = 30,
    ): NativeConversationMessages {
        val query = buildString {
            append("?limit=")
            append(limit.coerceIn(1, 50))
            if (beforeId != null && beforeId > 0) {
                append("&beforeId=")
                append(beforeId)
            }
        }
        val response = requestGet("/social/conversations/$conversationId/messages$query", accessToken)
        return NativeConversationMessages(
            items = response.optJSONArray("items").toChatMessages(),
            hasMore = response.optBoolean("hasMore"),
        )
    }

    fun listConversations(accessToken: String): List<NativeConversationPreview> =
        requestGet("/social/conversations", accessToken).optJSONArray("items").toConversations()

    fun markConversationRead(accessToken: String, conversationId: Int) {
        requestPost("/social/conversations/$conversationId/read", accessToken)
    }

    /** 按 notificationId 标记当前账号通知已读，并返回实时上下文，避免旧缓存误判删除状态。 */
    fun markNotificationRead(accessToken: String, notificationId: Int): NativeNotificationPreview {
        // 已读接口同时重新计算关联消息和评论状态，不能丢弃响应后继续使用旧通知。
        val response = requestMutation("/social/notifications/$notificationId/read", "PATCH", accessToken)
        return parseNativeNotification(response)
    }

    fun markAllNotificationsRead(accessToken: String) {
        requestMutation("/social/notifications/read-all", "POST", accessToken)
    }

    fun markSelectedNotificationsRead(accessToken: String, notificationIds: Collection<Int>): Int {
        val ids = notificationIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return 0
        return requestJsonMutation(
            "/social/notifications/read-selected",
            "POST",
            accessToken,
            JSONObject().put("notificationIds", JSONArray(ids)),
        ).optInt("count")
    }

    fun deleteNotification(accessToken: String, notificationId: Int) {
        requestMutation("/social/notifications/$notificationId", "DELETE", accessToken)
    }

    fun deleteSelectedNotifications(accessToken: String, notificationIds: Collection<Int>): Int {
        val ids = notificationIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return 0
        return requestJsonMutation(
            "/social/notifications/delete-selected",
            "POST",
            accessToken,
            JSONObject().put("notificationIds", JSONArray(ids)),
        ).optInt("count")
    }

    fun clearNotifications(accessToken: String, channel: String) {
        val encoded = java.net.URLEncoder.encode(channel, Charsets.UTF_8.name())
        requestMutation("/social/notifications?channel=$encoded", "DELETE", accessToken)
    }

    fun updateConversationSettings(
        accessToken: String,
        conversationId: Int,
        muted: Boolean,
    ): NativeConversationPreview = requestJsonMutation(
        "/social/conversations/$conversationId/settings",
        "PATCH",
        accessToken,
        JSONObject().put("muted", muted),
    ).toConversationPreview()

    fun getMessageContext(accessToken: String, conversationId: Int, messageId: Int): NativeChatMessage =
        requestGet("/social/conversations/$conversationId/messages/$messageId/context", accessToken)
            .optJSONObject("message")
            ?.let(::parseChatMessage)
            ?: throw IllegalStateException("消息不存在或当前不可见。")

    fun listPinnedMessages(accessToken: String, conversationId: Int): List<NativeChatMessage> =
        requestGet("/social/conversations/$conversationId/pinned", accessToken)
            .optJSONArray("items")
            .toChatMessages()

    fun setMessagePin(
        accessToken: String,
        conversationId: Int,
        messageId: Int,
        isPinned: Boolean,
    ): NativeChatMessage = requestJsonMutation(
        "/social/conversations/$conversationId/messages/$messageId/pin",
        "PATCH",
        accessToken,
        JSONObject().put("isPinned", isPinned),
    ).let(::parseChatMessage)

    fun reportGroupMessage(
        accessToken: String,
        groupId: Int,
        messageId: Int,
        reason: String,
        detail: String,
    ) {
        requestJsonMutation(
            "/social/groups/$groupId/messages/$messageId/reports",
            "POST",
            accessToken,
            JSONObject().put("reason", reason).apply {
                detail.trim().takeIf(String::isNotBlank)?.let { put("detail", it) }
            },
        )
    }

    fun getCallIceServers(accessToken: String): List<NativeIceServer> = buildList {
        val response = requestGet("/social/calls/ice-servers", accessToken)
        val servers = response.optJSONArray("iceServers") ?: return@buildList
        for (index in 0 until servers.length()) {
            val value = servers.optJSONObject(index) ?: continue
            val urls = when (val raw = value.opt("urls")) {
                is JSONArray -> buildList { for (urlIndex in 0 until raw.length()) add(raw.optString(urlIndex)) }
                else -> listOf(value.optString("urls"))
            }.filter(String::isNotBlank)
            if (urls.isNotEmpty()) {
                add(
                    NativeIceServer(
                        urls = urls,
                        username = value.optString("username").takeIf(String::isNotBlank),
                        credential = value.optString("credential").takeIf(String::isNotBlank),
                    ),
                )
            }
        }
    }

    fun searchMessages(accessToken: String, conversationId: Int, query: String, beforeId: Int? = null, limit: Int = 20): NativeMessageSearchResult {
        val encoded = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        val before = beforeId?.takeIf { it > 0 }?.let { "&beforeId=$it" }.orEmpty()
        val response = requestGet("/social/messages/search?q=$encoded&conversationId=$conversationId&limit=${limit.coerceIn(1, 50)}$before", accessToken)
        return NativeMessageSearchResult(
            items = response.optJSONArray("items").toChatMessages(),
            hasMore = response.optBoolean("hasMore"),
        )
    }

    fun uploadChatAttachments(
        accessToken: String,
        conversationId: Int,
        files: List<NativeUploadFile>,
    ): List<NativeChatAttachmentPreview> {
        require(files.isNotEmpty()) { "请选择需要上传的附件。" }
        val boundary = "----HlovetAndroidChat${System.currentTimeMillis()}"
        val connection = (URL("$baseUrl/social/conversations/$conversationId/attachments").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                files.forEach { file ->
                    val safeName = file.name.replace("\"", "").replace("\r", "").replace("\n", "")
                    output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    output.write("Content-Disposition: form-data; name=\"files\"; filename=\"$safeName\"\r\n".toByteArray(Charsets.UTF_8))
                    output.write("Content-Type: ${file.mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(file.bytes)
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                }
                output.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: "聊天附件上传失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            val attachments = runCatching { JSONArray(body) }.getOrNull()
                ?: JSONObject(body).optJSONArray("attachments")
            attachments.toChatAttachments()
        } finally {
            connection.disconnect()
        }
    }

    /** 使用 accessToken 读取 path，失败时保留 HTTP 状态，连接始终释放。 */
    private fun requestGet(path: String, accessToken: String): JSONObject {
        // 所有社交读取共用当前服务器来源和认证请求头，不能把网络失败当作消息删除。
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            // 先读取状态再解析响应，明确区分 403/404 与鉴权、限流及服务故障。
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "消息加载失败（HTTP $status）"
                throw NativeSocialHttpException(status, message)
            }
            JSONObject(body)
        } finally {
            // 无论解析还是请求失败，都及时释放此连接。
            connection.disconnect()
        }
    }

    private fun requestPost(path: String, accessToken: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "会话已读状态更新失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestMutation(path: String, method: String, accessToken: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
            doOutput = method == "POST"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (doOutput) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            if (connection.doOutput) connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "通知操作失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestJsonMutation(
        path: String,
        method: String,
        accessToken: String,
        body: JSONObject,
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "通知设置更新失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.toConversations(): List<NativeConversationPreview> = buildList {
        if (this@toConversations == null) return@buildList
        for (index in 0 until length()) {
            val value = getJSONObject(index)
            val group = value.optJSONObject("group")
            val user = value.optJSONObject("user")
            val title = group?.optString("name")?.takeIf { it.isNotBlank() }
                ?: user?.optString("nickname")?.takeIf { it.isNotBlank() }
                ?: user?.optString("username")?.takeIf { it.isNotBlank() }
                ?: "未命名会话"
            val initials = (group?.optString("name") ?: user?.optString("nickname") ?: title).takeLast(2)
            val lastMessage = value.optJSONObject("lastMessage")
            val body = lastMessage?.optString("body")?.takeIf { it.isNotBlank() }
                ?: when (lastMessage?.optString("type")) {
                    "attachment", "mixed" -> "附件消息"
                    else -> "暂无消息"
                }
            add(
                NativeConversationPreview(
                    id = value.optInt("id"),
                    title = title,
                    initials = initials,
                    message = body,
                    time = formatTime(lastMessage?.optString("createdAt") ?: value.optString("updatedAt")),
                    unreadCount = value.optInt("unreadCount"),
                    kind = value.optString("kind"),
                    groupId = group?.optInt("id")?.takeIf { it > 0 },
                    muted = value.optBoolean("muted"),
                ),
            )
        }
    }

    private fun JSONObject.toConversationPreview(): NativeConversationPreview {
        val group = optJSONObject("group")
        val user = optJSONObject("user")
        val title = group?.optString("name")?.takeIf { it.isNotBlank() }
            ?: user?.optString("nickname")?.takeIf { it.isNotBlank() }
            ?: user?.optString("username")?.takeIf { it.isNotBlank() }
            ?: "聊天会话"
        val lastMessage = optJSONObject("lastMessage")
        val body = lastMessage?.optString("body")?.takeIf { it.isNotBlank() }
            ?: when (lastMessage?.optString("type")) {
                "attachment", "mixed" -> "附件消息"
                else -> "暂无消息"
            }
        return NativeConversationPreview(
            id = optInt("id"),
            title = title,
            initials = title.takeLast(2),
            message = body,
            time = formatTime(lastMessage?.optString("createdAt") ?: optString("updatedAt")),
            unreadCount = optInt("unreadCount"),
            kind = optString("kind", "direct"),
            groupId = group?.optInt("id")?.takeIf { it > 0 },
            muted = optBoolean("muted"),
        )
    }

    private fun JSONArray?.toNotifications(): List<NativeNotificationPreview> = buildList {
        if (this@toNotifications == null) return@buildList
        for (index in 0 until length()) {
            add(parseNativeNotification(getJSONObject(index)))
        }
    }

    internal fun parseNativeNotification(value: JSONObject): NativeNotificationPreview {
            val context = value.optJSONObject("context")
            val actionUrl = value.optString("actionUrl").takeIf { it.isNotBlank() && it != "null" }
            val actionUri = actionUrl?.let(Uri::parse)
            val actionConversationId = actionUri?.getQueryParameter("conversation")?.toIntOrNull()
            val contextConversationId = context?.optInt("conversationId")?.takeIf { it > 0 }
            val conversationId = contextConversationId ?: actionConversationId
            val messageId = value.optInt("messageId").takeIf { it > 0 }
                ?: actionUri?.getQueryParameter("messageId")?.toIntOrNull()?.takeIf { it > 0 }
            val contextArticle = context?.optJSONObject("article")
            val pathSegments = actionUri?.pathSegments.orEmpty()
            val articlePathIndex = pathSegments.indexOfLast { it == "articles" }
            val articlePathCandidate = if (articlePathIndex >= 0) {
                pathSegments.getOrNull(articlePathIndex + 1)
            } else {
                null
            }
            val nonArticleRoutes = setOf("mine", "favorites", "read-later", "subscriptions", "edit")
            val articleSlug = contextArticle?.optString("slug")?.takeIf { it.isNotBlank() }
                ?: articlePathCandidate?.takeIf { it.isNotBlank() && it !in nonArticleRoutes }
            val commentId = context?.optInt("commentId")?.takeIf { it > 0 }
                ?: actionUri?.getQueryParameter("commentId")?.toIntOrNull()?.takeIf { it > 0 }
            val nestedComment = context?.optJSONObject("comment")
            val commentStatus = context?.optString("commentStatus").orEmpty()
                .ifBlank { nestedComment?.optString("status").orEmpty() }
                .ifBlank { value.optString("commentStatus") }
                .ifBlank { actionUri?.getQueryParameter("commentStatus").orEmpty() }
            val contextKind = context?.optString("kind").orEmpty()
            val actionable = context?.optBoolean("actionable") == true || value.optBoolean("actionable")
            val group = context?.optJSONObject("group")
            val nonBlank = { raw: String? -> raw?.takeIf { it.isNotBlank() && it != "null" } }
            return NativeNotificationPreview(
                id = value.optInt("id"),
                title = value.optString("title", "系统通知"),
                body = value.optString("body").ifBlank { value.optString("bodyEn") },
                groupName = nonBlank(group?.optString("name")),
                requestNote = nonBlank(context?.optString("requestNote")),
                requestBody = nonBlank(context?.optString("requestBody")),
                commentBody = nonBlank(context?.optString("commentBody")),
                contextKind = nonBlank(context?.optString("kind")),
                contextStatus = nonBlank(context?.optString("status")),
                reportMessage = context?.optJSONObject("message")?.let(::parseChatMessage),
                createdAt = value.optString("createdAt"),
                unread = value.isNull("readAt"),
                channel = value.optString("channel", "system"),
                type = value.optString("type"),
                actionUrl = actionUrl,
                friendshipId = value.optInt("friendshipId").takeIf { it > 0 }
                    ?: actionUri?.getQueryParameter("friendshipId")?.toIntOrNull()?.takeIf { it > 0 },
                strangerRequest = actionUri?.getQueryParameter("strangerRequests") == "1" || context?.optString("kind") == "stranger_message_request",
                messageDeleted = value.optBoolean("messageDeleted") || context?.optBoolean("messageDeleted") == true || actionUri?.getQueryParameter("messageDeleted") == "1",
                commentDeleted = commentStatus.equals("deleted", ignoreCase = true) || commentStatus.equals("blocked", ignoreCase = true),
                conversationId = conversationId,
                messageId = messageId,
                articleSlug = articleSlug,
                commentId = commentId,
                actionType = nativeNotificationActionType(contextKind, actionable),
                groupId = context?.optInt("groupId")?.takeIf { it > 0 }
                    ?: actionUri?.getQueryParameter("groupId")?.toIntOrNull()?.takeIf { it > 0 },
                invitationId = context?.optInt("invitationId")?.takeIf { it > 0 }
                    ?: actionUri?.getQueryParameter("invitationId")?.toIntOrNull()?.takeIf { it > 0 },
                joinRequestId = context?.optInt("joinRequestId")?.takeIf { it > 0 }
                    ?: actionUri?.getQueryParameter("joinRequestId")?.toIntOrNull()?.takeIf { it > 0 },
                requestId = context?.optInt("requestId")?.takeIf { it > 0 }
                    ?: actionUri?.getQueryParameter("requestId")?.toIntOrNull()?.takeIf { it > 0 },
                reportId = context?.optInt("reportId")?.takeIf { it > 0 }
                    ?: actionUri?.getQueryParameter("reportId")?.toIntOrNull()?.takeIf { it > 0 },
            )
    }

    private fun JSONArray?.toStrings(): List<String> = buildList {
        if (this@toStrings == null) return@buildList
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun JSONArray?.toChannelStates(): List<NativeNotificationChannelState> = buildList {
        if (this@toChannelStates == null) return@buildList
        for (index in 0 until length()) {
            optJSONObject(index)?.toChannelState()?.let(::add)
        }
    }

    private fun JSONObject.toChannelState(): NativeNotificationChannelState = NativeNotificationChannelState(
        channel = optString("channel", "system"),
        hiddenThroughNotificationId = optInt("hiddenThroughNotificationId"),
        pushEnabled = optBoolean("pushEnabled", true),
        digestEnabled = optBoolean("digestEnabled", true),
    )

    private fun JSONArray?.toChatMessages(): List<NativeChatMessage> = buildList {
        if (this@toChatMessages == null) return@buildList
        for (index in 0 until length()) add(parseChatMessage(getJSONObject(index)))
    }

    private fun JSONArray?.toChatAttachments(): List<NativeChatAttachmentPreview> = buildList {
        if (this@toChatAttachments == null) return@buildList
        for (index in 0 until length()) {
            val value = getJSONObject(index)
            add(
                NativeChatAttachmentPreview(
                    id = value.optInt("id"),
                    conversationId = value.optInt("conversationId"),
                    kind = value.optString("kind", "file"),
                    name = value.optString("originalName", "附件"),
                    mimeType = value.optString("mimeType", "application/octet-stream"),
                    sizeBytes = value.optLong("sizeBytes"),
                    downloadUrl = value.optString("downloadUrl"),
                    thumbnailUrl = value.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = value.optString("createdAt"),
                ),
            )
        }
    }

    /** 将服务端 value 转为消息；群昵称优先展示，空头像保持 null，附件沿用原认证地址。 */
    internal fun parseChatMessage(value: JSONObject): NativeChatMessage {
        // 展示名称允许使用群昵称，账号标识仍单独保留服务端 username。
        val sender = value.optJSONObject("sender")
        val quote = value.optJSONObject("quote")
        val senderName = value.optString("senderDisplayName").ifBlank {
            sender?.optString("nickname").orEmpty()
        }.ifBlank {
            sender?.optString("username").orEmpty()
        }.ifBlank { "站内用户" }
        // 时间格式和附件顺序保持既有聊天语义，仅补齐举报展示需要的发送者头像。
        return NativeChatMessage(
            id = value.optInt("id"),
            conversationId = value.optInt("conversationId"),
            body = value.optString("body"),
            type = value.optString("type", "text"),
            senderName = senderName,
            senderUsername = sender?.optString("username").orEmpty(),
            senderInitials = senderName.takeLast(2),
            quote = quote?.let {
                NativeChatQuote(
                    id = it.optInt("id"),
                    body = it.optString("body"),
                    senderName = it.optString("senderDisplayName").ifBlank { "站内用户" },
                    available = it.optBoolean("available", true),
                )
            },
            isPinned = value.optBoolean("isPinned"),
            createdAt = formatTime(value.optString("createdAt")),
            attachments = value.optJSONArray("attachments").toChatAttachments(),
            senderAvatarUrl = sender?.optString("avatarUrl")?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    internal fun parseCallSession(value: JSONObject): NativeCallSession {
        val user = value.optJSONObject("user")
        val userName = user?.optString("nickname").orEmpty().ifBlank {
            user?.optString("username").orEmpty()
        }.ifBlank { "聊天用户" }
        return NativeCallSession(
            id = value.optInt("id"),
            conversationId = value.optInt("conversationId"),
            type = value.optString("type", "voice"),
            status = value.optString("status", "ringing"),
            callerId = value.optInt("callerId"),
            calleeId = value.optInt("calleeId"),
            userId = user?.optInt("id") ?: 0,
            userName = userName,
            userUsername = user?.optString("username").orEmpty(),
            acceptedAt = value.optString("acceptedAt").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun formatTime(value: String): String = when {
        value.isBlank() -> ""
        value.length >= 16 -> value.substring(11, 16)
        else -> value
    }
}
