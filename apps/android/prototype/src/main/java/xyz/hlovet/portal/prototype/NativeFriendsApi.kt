package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class NativeSocialUser(
    val id: Int,
    val nickname: String,
    val username: String,
    val avatarUrl: String?,
    val profileBio: String,
    val roleName: String,
)

internal data class NativeFriendship(
    val id: Int,
    val status: String,
    val direction: String,
    val note: String?,
    val user: NativeSocialUser,
    val createdAt: String,
    val updatedAt: String,
)

internal data class NativeFriendshipLists(
    val friends: List<NativeFriendship>,
    val incoming: List<NativeFriendship>,
    val outgoing: List<NativeFriendship>,
    val blocked: List<NativeFriendship>,
)

internal data class NativeStrangerMessageRequest(
    val id: Int,
    val user: NativeSocialUser,
    val body: String,
    val status: String,
    val direction: String,
    val conversationId: Int?,
    val respondedAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

internal data class NativeStrangerRequestLists(
    val incoming: List<NativeStrangerMessageRequest>,
    val outgoing: List<NativeStrangerMessageRequest>,
)

internal data class NativeSocialSearchUser(
    val user: NativeSocialUser,
    val relationshipId: Int?,
    val relationshipStatus: String?,
    val relationshipDirection: String?,
    val relationshipNote: String?,
    val canRequest: Boolean,
)

/** 负责好友关系、陌生人请求和用户/提及候选接口，不在客户端扩大服务端授权范围。 */
internal object NativeFriendsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun listFriendships(accessToken: String): NativeFriendshipLists {
        val response = requestJson("/social/friends", "GET", accessToken)
        return NativeFriendshipLists(
            friends = response.optJSONArray("friends").toFriendships(),
            incoming = response.optJSONArray("incoming").toFriendships(),
            outgoing = response.optJSONArray("outgoing").toFriendships(),
            blocked = response.optJSONArray("blocked").toFriendships(),
        )
    }

    fun listStrangerRequests(accessToken: String): NativeStrangerRequestLists {
        val response = requestJson("/social/stranger-message-requests", "GET", accessToken)
        return NativeStrangerRequestLists(
            incoming = response.optJSONArray("incoming").toStrangerRequests(),
            outgoing = response.optJSONArray("outgoing").toStrangerRequests(),
        )
    }

    /** 按 query 搜索用户；mention 允许空关键词，conversationId 将聊天候选限定到有权访问的会话成员。 */
    fun searchUsers(accessToken: String, query: String, limit: Int = 12, mention: Boolean = false, conversationId: Int? = null): List<NativeSocialSearchUser> {
        // 普通好友搜索保留两字符门槛，提及搜索显式携带标志及可选会话范围，不改变评论可搜索规则。
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        val mentionQuery = if (mention) "&mention=true" + (conversationId?.let { "&conversationId=$it" } ?: "") else ""
        return requestJson("/social/users/search?q=$encoded&limit=${limit.coerceIn(1, 20)}$mentionQuery", "GET", accessToken)
            .optJSONArray("items")
            .toSearchUsers()
    }

    fun requestFriend(accessToken: String, userId: Int, note: String?): NativeFriendship {
        val body = JSONObject().apply { put("note", note?.trim().orEmpty()) }
        return requestJson("/social/friends/$userId/request", "POST", accessToken, body).toFriendship()
    }

    fun respondFriendRequest(accessToken: String, friendshipId: Int, status: String): NativeFriendship {
        val body = JSONObject().put("status", status)
        return requestJson("/social/friendships/$friendshipId/respond", "PATCH", accessToken, body).toFriendship()
    }

    fun removeFriendship(accessToken: String, friendshipId: Int) {
        requestJson("/social/friendships/$friendshipId", "DELETE", accessToken)
    }

    fun blockFriendship(accessToken: String, friendshipId: Int) {
        requestJson("/social/friendships/$friendshipId/block", "POST", accessToken)
    }

    fun unblockFriendship(accessToken: String, friendshipId: Int) {
        requestJson("/social/friendships/$friendshipId/block", "DELETE", accessToken)
    }

    fun createStrangerRequest(accessToken: String, userId: Int, body: String): NativeStrangerMessageRequest {
        require(body.trim().isNotBlank()) { "请先输入消息内容。" }
        return requestJson(
            "/social/stranger-message-requests/$userId",
            "POST",
            accessToken,
            JSONObject().put("body", body.trim()),
        ).toStrangerRequest()
    }

    fun respondStrangerRequest(accessToken: String, requestId: Int, status: String): NativeStrangerMessageRequest {
        val response = requestJson(
            "/social/stranger-message-requests/$requestId/respond",
            "PATCH",
            accessToken,
            JSONObject().put("status", status),
        )
        return response.optJSONObject("request")?.toStrangerRequest()
            ?: throw IllegalStateException("消息请求响应格式无效。")
    }

    fun getOrCreateConversation(accessToken: String, userId: Int): NativeConversationPreview {
        return requestJson("/social/conversations/with/$userId", "POST", accessToken).toConversationPreview()
    }

    fun setAuthorSubscription(accessToken: String, userId: Int, subscribed: Boolean): NativeSubscriptionResult {
        val response = requestJson(
            "/social/subscriptions/$userId",
            if (subscribed) "POST" else "DELETE",
            accessToken,
        )
        return NativeSubscriptionResult(
            subscribed = response.optBoolean("subscribed", subscribed),
            subscriberCount = response.optInt("subscriberCount"),
        )
    }

    fun blockUser(accessToken: String, userId: Int) {
        requestJson("/social/users/$userId/block", "POST", accessToken)
    }

    private fun requestJson(
        path: String,
        method: String,
        accessToken: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            body?.let { payload -> connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "好友和消息请求操作失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.toFriendships(): List<NativeFriendship> = buildList {
        if (this@toFriendships == null) return@buildList
        for (index in 0 until length()) optJSONObject(index)?.toFriendship()?.let(::add)
    }

    private fun JSONArray?.toStrangerRequests(): List<NativeStrangerMessageRequest> = buildList {
        if (this@toStrangerRequests == null) return@buildList
        for (index in 0 until length()) optJSONObject(index)?.toStrangerRequest()?.let(::add)
    }

    private fun JSONArray?.toSearchUsers(): List<NativeSocialSearchUser> = buildList {
        if (this@toSearchUsers == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val relationship = value.optJSONObject("relationship")
            add(
                NativeSocialSearchUser(
                    user = value.toSocialUser(),
                    relationshipId = relationship?.optInt("id")?.takeIf { it > 0 },
                    relationshipStatus = relationship?.optString("status")?.takeIf { it.isNotBlank() },
                    relationshipDirection = relationship?.optString("direction")?.takeIf { it.isNotBlank() },
                    relationshipNote = relationship?.optString("note")?.takeIf { it.isNotBlank() && it != "null" },
                    canRequest = value.optBoolean("canRequest"),
                ),
            )
        }
    }

    private fun JSONObject.toFriendship(): NativeFriendship = NativeFriendship(
        id = optInt("id"),
        status = optString("status", "pending"),
        direction = optString("direction", "outgoing"),
        note = optString("note").takeIf { it.isNotBlank() && it != "null" },
        user = optJSONObject("user")?.toSocialUser() ?: throw IllegalStateException("好友数据格式无效。"),
        createdAt = optString("createdAt"),
        updatedAt = optString("updatedAt"),
    )

    private fun JSONObject.toStrangerRequest(): NativeStrangerMessageRequest = NativeStrangerMessageRequest(
        id = optInt("id"),
        user = optJSONObject("user")?.toSocialUser() ?: throw IllegalStateException("消息请求数据格式无效。"),
        body = optString("body"),
        status = optString("status", "pending"),
        direction = optString("direction", "incoming"),
        conversationId = optInt("conversationId").takeIf { it > 0 },
        respondedAt = optString("respondedAt").takeIf { it.isNotBlank() && it != "null" },
        createdAt = optString("createdAt"),
        updatedAt = optString("updatedAt"),
    )

    private fun JSONObject.toSocialUser(): NativeSocialUser {
        val username = optString("username")
        return NativeSocialUser(
            id = optInt("id"),
            nickname = optString("nickname").ifBlank { username.ifBlank { "站内用户" } },
            username = username,
            avatarUrl = optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
            profileBio = optString("profileBio"),
            roleName = optJSONObject("role")?.optString("name").orEmpty(),
        )
    }

    private fun JSONObject.toConversationPreview(): NativeConversationPreview {
        val group = optJSONObject("group")
        val user = optJSONObject("user")
        val title = group?.optString("name")?.takeIf { it.isNotBlank() }
            ?: user?.optString("nickname")?.takeIf { it.isNotBlank() }
            ?: user?.optString("username")?.takeIf { it.isNotBlank() }
            ?: "聊天会话"
        val lastMessage = optJSONObject("lastMessage")
        val message = lastMessage?.optString("body")?.takeIf { it.isNotBlank() }
            ?: if (lastMessage?.optString("type") == "attachment") "附件消息" else "暂无消息"
        return NativeConversationPreview(
            id = optInt("id"),
            title = title,
            initials = title.takeLast(2),
            message = message,
            time = formatTime(lastMessage?.optString("createdAt") ?: optString("updatedAt")),
            unreadCount = optInt("unreadCount"),
            kind = optString("kind", "direct"),
            groupId = group?.optInt("id")?.takeIf { it > 0 },
            muted = optBoolean("muted"),
        )
    }

    private fun formatTime(value: String): String = when {
        value.isBlank() -> ""
        value.length >= 16 -> value.substring(11, 16)
        else -> value
    }
}
