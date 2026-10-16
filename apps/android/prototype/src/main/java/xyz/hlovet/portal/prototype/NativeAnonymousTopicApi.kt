package xyz.hlovet.portal.prototype

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.UUID
import java.io.BufferedOutputStream

internal data class NativeAnonymousTopicHighlight(
    val id: Int,
    val sequence: Int,
    val body: String,
    val nickname: String?,
    val count: Int,
)

internal data class NativeAnonymousTopicSummary(
    val id: Int,
    val title: String,
    val status: String,
    val isHidden: Boolean,
    val messageCount: Int,
    val messageLikeCount: Int,
    val favoriteCount: Int,
    val favorited: Boolean,
    val topLikedMessage: NativeAnonymousTopicHighlight?,
    val topDislikedMessage: NativeAnonymousTopicHighlight?,
    val createdAt: String,
    val updatedAt: String,
)

internal data class NativeAnonymousTopicMessage(
    val id: Int,
    val sequence: Int,
    val body: String,
    val nickname: String?,
    val isHidden: Boolean,
    val likeCount: Int,
    val dislikeCount: Int,
    val attachments: List<NativeArticleAttachment>,
    val createdAt: String,
)

internal data class NativeAnonymousTopicDetail(
    val summary: NativeAnonymousTopicSummary,
    val messages: List<NativeAnonymousTopicMessage>,
    val hasMore: Boolean,
)

internal data class NativeAnonymousTopicPage(
    val items: List<NativeAnonymousTopicSummary>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal data class NativeAnonymousIdentity(
    val identityToken: String,
    val nickname: String,
    val isCreator: Boolean,
)

internal object NativeAnonymousTopicApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"
    private const val PREFS = "native-anonymous-topics"
    private const val VISITOR_KEY = "visitor-key"
    private const val IDENTITY_PREFIX = "identity:"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun readIdentity(context: Context, topicId: Int): NativeAnonymousIdentity? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(IDENTITY_PREFIX + topicId, null) ?: return@runCatching null
        JSONObject(raw).toIdentity()
    }.getOrNull()

    fun saveIdentity(context: Context, topicId: Int, identity: NativeAnonymousIdentity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(IDENTITY_PREFIX + topicId, JSONObject().apply {
                put("identityToken", identity.identityToken)
                put("nickname", identity.nickname)
                put("isCreator", identity.isCreator)
            }.toString())
            .apply()
    }

    fun visitorKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(VISITOR_KEY, null)
        if (!current.isNullOrBlank() && current.length >= 16) return current
        return UUID.randomUUID().toString().also { prefs.edit().putString(VISITOR_KEY, it).apply() }
    }

    fun list(
        context: Context,
        page: Int = 1,
        pageSize: Int = 12,
        query: String = "",
        sort: String = "time",
    ): NativeAnonymousTopicPage = requestGet(
        path = "/anonymous-topics${listQuery(page, pageSize, query, sort)}",
        context = context,
    ).toPage(page, pageSize)

    fun listAdmin(
        accessToken: String,
        page: Int = 1,
        pageSize: Int = 20,
        query: String = "",
        sort: String = "time",
        visibility: String = "visible",
    ): NativeAnonymousTopicPage = requestGet(
        path = "/anonymous-topics/admin${listQuery(page, pageSize, query, sort)}&visibility=${encode(visibility)}",
        accessToken = accessToken,
    ).toPage(page, pageSize)

    fun get(context: Context, id: Int, beforeSequence: Int? = null): NativeAnonymousTopicDetail = requestGet(
        path = "/anonymous-topics/$id${detailQuery(beforeSequence)}",
        context = context,
    ).toDetail()

    fun getAdmin(accessToken: String, id: Int, beforeSequence: Int? = null): NativeAnonymousTopicDetail = requestGet(
        path = "/anonymous-topics/admin/$id${detailQuery(beforeSequence)}",
        accessToken = accessToken,
    ).toDetail()

    fun create(context: Context, title: String, nickname: String, password: String): NativeAnonymousTopicSummaryAndIdentity = requestMutation(
        path = "/anonymous-topics",
        method = "POST",
        context = context,
        body = JSONObject().apply {
            put("title", title.trim())
            put("nickname", nickname.trim())
            put("password", password)
            put("visitorKey", visitorKey(context))
        },
    ).toSummaryAndIdentity()

    fun claimIdentity(context: Context, topicId: Int, password: String, nickname: String?, create: Boolean): NativeAnonymousIdentity = requestMutation(
        path = "/anonymous-topics/$topicId/identity",
        method = "POST",
        context = context,
        body = JSONObject().apply {
            put("password", password)
            if (!nickname.isNullOrBlank()) put("nickname", nickname.trim())
            put("create", create)
            put("visitorKey", visitorKey(context))
        },
    ).toIdentity()

    fun favorite(context: Context, topicId: Int): JSONObject = requestMutation(
        path = "/anonymous-topics/$topicId/favorite",
        method = "POST",
        context = context,
        body = JSONObject().put("visitorKey", visitorKey(context)),
    )

    fun react(context: Context, messageId: Int, value: String): NativeAnonymousTopicMessage = requestMutation(
        path = "/anonymous-topics/messages/$messageId/reaction",
        method = "POST",
        context = context,
        body = JSONObject().put("value", value).put("visitorKey", visitorKey(context)),
    ).toMessage()

    fun sendMessage(
        context: Context,
        topicId: Int,
        body: String,
        identityToken: String?,
        files: List<NativeUploadFile> = emptyList(),
    ): NativeAnonymousTopicMessage {
        if (files.isEmpty()) {
            return requestMutation(
                path = "/anonymous-topics/$topicId/messages",
                method = "POST",
                context = context,
                body = JSONObject().apply {
                    put("body", body)
                    if (!identityToken.isNullOrBlank()) put("identityToken", identityToken)
                    put("visitorKey", visitorKey(context))
                },
            ).toMessage()
        }
        val boundary = "----HlovetAndroidAnonymous${System.currentTimeMillis()}"
        val connection = (URL(baseUrl + "/anonymous-topics/$topicId/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                writeField(output, boundary, "body", body)
                writeField(output, boundary, "identityToken", identityToken.orEmpty())
                writeField(output, boundary, "visitorKey", visitorKey(context))
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
            readResponse(connection, "匿名发言失败").toMessage()
        } finally {
            connection.disconnect()
        }
    }

    fun updateTopic(accessToken: String, id: Int, status: String? = null, isHidden: Boolean? = null): NativeAnonymousTopicSummary = requestMutation(
        path = "/anonymous-topics/admin/$id",
        method = "PATCH",
        accessToken = accessToken,
        body = JSONObject().apply {
            status?.let { put("status", it) }
            isHidden?.let { put("isHidden", it) }
        },
    ).toSummary()

    fun updateTopicAsCreator(context: Context, id: Int, status: String, identityToken: String): NativeAnonymousTopicSummary = requestMutation(
        path = "/anonymous-topics/$id/status",
        method = "PATCH",
        context = context,
        body = JSONObject().put("status", status).put("identityToken", identityToken),
    ).toSummary()

    fun updateMessage(accessToken: String, id: Int, isHidden: Boolean): NativeAnonymousTopicMessage = requestMutation(
        path = "/anonymous-topics/admin/messages/$id",
        method = "PATCH",
        accessToken = accessToken,
        body = JSONObject().put("isHidden", isHidden),
    ).toMessage()

    private fun listQuery(page: Int, pageSize: Int, query: String, sort: String): String = buildString {
        append("?page=").append(page.coerceAtLeast(1))
        append("&pageSize=").append(pageSize.coerceIn(1, 50))
        append("&sort=").append(encode(sort))
        if (query.trim().isNotEmpty()) append("&q=").append(encode(query.trim()))
    }

    private fun detailQuery(beforeSequence: Int?): String = if (beforeSequence == null) "?limit=40" else "?limit=40&beforeSequence=$beforeSequence"

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun requestGet(path: String, context: Context? = null, accessToken: String? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            context?.let { setRequestProperty("X-Anonymous-Visitor-Key", visitorKey(it)) }
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return readResponse(connection, "匿名话题加载失败")
    }

    private fun requestMutation(path: String, method: String, context: Context? = null, accessToken: String? = null, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 30_000
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            context?.let { setRequestProperty("X-Anonymous-Visitor-Key", visitorKey(it)) }
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readResponse(connection, "匿名话题操作失败")
    }

    private fun writeField(output: BufferedOutputStream, boundary: String, name: String, value: String) {
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun readResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(errorMessage(body, "$fallback（HTTP $status）"))
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(body: String, fallback: String): String = runCatching {
        when (val message = JSONObject(body).opt("message")) {
            is JSONArray -> message.join("、")
            is String -> message
            else -> fallback
        }
    }.getOrDefault(fallback).ifBlank { fallback }

    private fun JSONObject.toPage(defaultPage: Int, defaultPageSize: Int) = NativeAnonymousTopicPage(
        items = optJSONArray("items").toTopicSummaries(),
        total = optInt("total"),
        page = optInt("page", defaultPage),
        pageSize = optInt("pageSize", defaultPageSize),
        totalPages = optInt("totalPages", 1),
    )

    private fun JSONObject.toDetail(): NativeAnonymousTopicDetail = NativeAnonymousTopicDetail(
        summary = toSummary(),
        messages = optJSONArray("messages").toTopicMessages(),
        hasMore = optBoolean("hasMore"),
    )

    private fun JSONObject.toSummaryAndIdentity() = NativeAnonymousTopicSummaryAndIdentity(
        summary = toSummary(),
        identity = toIdentity(),
    )

    private fun JSONObject.toIdentity() = NativeAnonymousIdentity(
        identityToken = optString("identityToken"),
        nickname = optString("nickname"),
        isCreator = optBoolean("isCreator"),
    )

    private fun JSONObject.toSummary() = NativeAnonymousTopicSummary(
        id = optInt("id"),
        title = optString("title", "未命名话题"),
        status = optString("status", "active"),
        isHidden = optBoolean("isHidden"),
        messageCount = optInt("messageCount"),
        messageLikeCount = optInt("messageLikeCount"),
        favoriteCount = optInt("favoriteCount"),
        favorited = optBoolean("favorited"),
        topLikedMessage = optJSONObject("topLikedMessage")?.toHighlight(),
        topDislikedMessage = optJSONObject("topDislikedMessage")?.toHighlight(),
        createdAt = optString("createdAt"),
        updatedAt = optString("updatedAt"),
    )

    private fun JSONObject.toHighlight() = NativeAnonymousTopicHighlight(
        id = optInt("id"),
        sequence = optInt("sequence"),
        body = optString("body"),
        nickname = optString("nickname").takeIf(String::isNotBlank),
        count = optInt("count"),
    )

    private fun JSONObject.toMessage() = NativeAnonymousTopicMessage(
        id = optInt("id"),
        sequence = optInt("sequence"),
        body = optString("body"),
        nickname = optString("nickname").takeIf(String::isNotBlank),
        isHidden = optBoolean("isHidden"),
        likeCount = optInt("likeCount"),
        dislikeCount = optInt("dislikeCount"),
        attachments = optJSONArray("attachments").toArticleAttachments(),
        createdAt = optString("createdAt"),
    )

    private fun JSONArray?.toTopicSummaries(): List<NativeAnonymousTopicSummary> = buildList {
        for (index in 0 until (this@toTopicSummaries?.length() ?: 0)) this@toTopicSummaries?.optJSONObject(index)?.let { add(it.toSummary()) }
    }

    private fun JSONArray?.toTopicMessages(): List<NativeAnonymousTopicMessage> = buildList {
        for (index in 0 until (this@toTopicMessages?.length() ?: 0)) this@toTopicMessages?.optJSONObject(index)?.let { add(it.toMessage()) }
    }

    private fun JSONArray?.toArticleAttachments(): List<NativeArticleAttachment> = buildList {
        for (index in 0 until (this@toArticleAttachments?.length() ?: 0)) {
            this@toArticleAttachments?.optJSONObject(index)?.let { value ->
                add(
                    NativeArticleAttachment(
                        id = value.optInt("id"),
                        kind = value.optString("kind"),
                        originalName = value.optString("originalName", "附件"),
                        mimeType = value.optString("mimeType"),
                        sizeBytes = value.optLong("sizeBytes"),
                        downloadUrl = value.optString("downloadUrl"),
                        thumbnailUrl = value.optString("thumbnailUrl").takeIf(String::isNotBlank),
                    ),
                )
            }
        }
    }
}

internal data class NativeAnonymousTopicSummaryAndIdentity(
    val summary: NativeAnonymousTopicSummary,
    val identity: NativeAnonymousIdentity,
)
