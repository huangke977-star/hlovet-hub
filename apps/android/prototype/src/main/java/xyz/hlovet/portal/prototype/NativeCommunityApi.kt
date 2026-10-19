package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal data class NativeAnnouncementSummary(
    val id: Int,
    val title: String,
    val summary: String,
    val isPinned: Boolean,
    val viewCount: Int,
    val unread: Boolean,
    val publishedAt: String,
    val updatedAt: String,
)

internal data class NativeAnnouncementDetail(
    val summary: NativeAnnouncementSummary,
    val content: String,
    val confirmedAt: String?,
)

internal data class NativeAnnouncementPage(
    val items: List<NativeAnnouncementSummary>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

internal data class NativeCommunityUser(
    val id: Int,
    val username: String,
    val nickname: String,
)

internal data class NativeFeedbackSummary(
    val id: Int,
    val category: String,
    val title: String,
    val status: String,
    val user: NativeCommunityUser,
    val replyCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

internal data class NativeFeedbackReply(
    val id: Int,
    val content: String,
    val author: NativeCommunityUser,
    val createdAt: String,
)

internal data class NativeFeedbackDetail(
    val summary: NativeFeedbackSummary,
    val content: String,
    val reviewedAt: String?,
    val reviewedBy: NativeCommunityUser?,
    val replies: List<NativeFeedbackReply>,
)

internal data class NativeFeedbackPage(
    val items: List<NativeFeedbackSummary>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

internal data class NativeSuggestionSummary(
    val id: Int,
    val title: String,
    val status: String,
    val user: NativeCommunityUser = NativeCommunityUser(0, "", ""),
    val reviewedBy: NativeCommunityUser? = null,
    val replyCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

internal data class NativeSuggestionReply(
    val id: Int,
    val content: String,
    val author: NativeCommunityUser,
    val createdAt: String,
)

internal data class NativeSuggestionDetail(
    val summary: NativeSuggestionSummary,
    val content: String,
    val reviewedAt: String?,
    val replies: List<NativeSuggestionReply>,
)

internal data class NativeSuggestionPage(
    val items: List<NativeSuggestionSummary>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

/** 保留社区接口的 HTTP 状态，供页面区分不可访问内容与可重试网络故障，message 用于既有错误展示。 */
internal class NativeCommunityHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

/** 封装普通用户的公告、反馈和建议接口，权限继续由服务端校验。 */
internal object NativeCommunityApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun listAnnouncements(accessToken: String?, page: Int = 1, pageSize: Int = 12): NativeAnnouncementPage {
        val path = if (accessToken.isNullOrBlank()) "/announcements/public" else "/announcements/visible"
        val response = requestGet("$path?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}", accessToken)
        return NativeAnnouncementPage(
            items = response.optJSONArray("items").toAnnouncementSummaries(),
            total = response.optInt("total"),
            page = response.optInt("page", page),
            totalPages = response.optInt("totalPages", 1),
        )
    }

    fun getAnnouncement(accessToken: String?, id: Int): NativeAnnouncementDetail {
        val path = if (accessToken.isNullOrBlank()) "/announcements/public/$id" else "/announcements/visible/$id"
        val response = requestGet(path, accessToken)
        return NativeAnnouncementDetail(
            summary = response.toAnnouncementSummary(),
            content = response.optString("content"),
            confirmedAt = response.optStringOrNull("confirmedAt"),
        )
    }

    fun confirmAnnouncement(accessToken: String, id: Int): String =
        requestMutation("/announcements/visible/$id/confirm", "POST", accessToken).optString("confirmedAt")

    fun listMyFeedback(accessToken: String, page: Int = 1, pageSize: Int = 12, query: String = ""): NativeFeedbackPage {
        return requestGet("/feedback/mine${pageQuery(page, pageSize, query)}", accessToken).toFeedbackPage(page)
    }

    fun listFeedbackInbox(
        accessToken: String,
        page: Int = 1,
        pageSize: Int = 12,
        query: String = "",
        status: String? = null,
    ): NativeFeedbackPage {
        val suffix = buildString {
            append(pageQuery(page, pageSize, query))
            if (!status.isNullOrBlank() && status != "all") {
                append("&status=")
                append(URLEncoder.encode(status, Charsets.UTF_8.name()).replace("+", "%20"))
            }
        }
        return requestGet("/feedback/inbox$suffix", accessToken).toFeedbackPage(page)
    }

    fun getFeedback(accessToken: String, id: Int): NativeFeedbackDetail =
        requestGet("/feedback/$id", accessToken).toFeedbackDetail()

    fun createFeedback(accessToken: String, category: String, title: String, content: String): NativeFeedbackDetail =
        requestMutation(
            "/feedback",
            "POST",
            accessToken,
            JSONObject().apply {
                put("category", category)
                put("title", title.trim())
                put("content", content.trim())
            },
        ).toFeedbackDetail()

    fun replyFeedback(accessToken: String, id: Int, content: String): NativeFeedbackDetail =
        requestMutation("/feedback/$id/replies", "POST", accessToken, JSONObject().put("content", content.trim())).toFeedbackDetail()

    fun updateFeedbackStatus(accessToken: String, id: Int, status: String): NativeFeedbackDetail =
        requestMutation("/feedback/$id/status", "PATCH", accessToken, JSONObject().put("status", status)).toFeedbackDetail()

    fun listSuggestions(accessToken: String?, page: Int = 1, pageSize: Int = 12, query: String = ""): NativeSuggestionPage {
        // The public suggestions page is intentionally the same for guests and signed-in users.
        // Authentication is only needed for creating a suggestion.
        return requestGet("/suggestions${pageQuery(page, pageSize, query)}", null).toSuggestionPage(page)
    }

    fun listMySuggestions(accessToken: String, page: Int = 1, pageSize: Int = 12, query: String = ""): NativeSuggestionPage =
        requestGet("/suggestions/mine${pageQuery(page, pageSize, query)}", accessToken).toSuggestionPage(page)

    fun listSuggestionInbox(accessToken: String, page: Int = 1, pageSize: Int = 12, query: String = ""): NativeSuggestionPage =
        requestGet("/suggestions/inbox${pageQuery(page, pageSize, query)}", accessToken).toSuggestionPage(page)

    fun getSuggestion(id: Int): NativeSuggestionDetail = requestGet("/suggestions/$id", null).toSuggestionDetail()

    fun createSuggestion(accessToken: String, title: String, content: String): NativeSuggestionDetail =
        requestMutation(
            "/suggestions",
            "POST",
            accessToken,
            JSONObject().apply {
                put("title", title.trim())
                put("content", content.trim())
            },
        ).toSuggestionDetail()

    fun updateSuggestionStatus(accessToken: String, id: Int, status: String): NativeSuggestionDetail =
        requestMutation("/suggestions/$id/status", "PATCH", accessToken, JSONObject().put("status", status)).toSuggestionDetail()

    fun replyToSuggestion(accessToken: String, id: Int, content: String): NativeSuggestionDetail =
        requestMutation("/suggestions/$id/replies", "POST", accessToken, JSONObject().put("content", content.trim())).toSuggestionDetail()

    private fun pageQuery(page: Int, pageSize: Int, query: String): String {
        val builder = StringBuilder("?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}")
        if (query.trim().isNotEmpty()) {
            builder.append("&q=")
            builder.append(URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20"))
        }
        return builder.toString()
    }

    private fun requestGet(path: String, accessToken: String?): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return readResponse(connection, "内容加载失败")
    }

    private fun requestMutation(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
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
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readResponse(connection, "操作失败")
    }

    /** 解析 connection 的 JSON 响应；失败保留 HTTP 状态及 fallback 对应业务错误，始终关闭连接。 */
    private fun readResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        return try {
            // 错误仍保留服务端文案，但页面不能靠匹配文字判断是否已删除或无权限。
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw NativeCommunityHttpException(status, errorMessage(body, "$fallback（HTTP $status）"))
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            // 包括错误和空响应在内，每次连接都必须释放，不影响用户之后主动重试。
            connection.disconnect()
        }
    }

    private fun errorMessage(body: String, fallback: String): String {
        return runCatching {
            val message = JSONObject(body).opt("message")
            when (message) {
                is JSONArray -> message.join("、")
                is String -> message
                else -> fallback
            }
        }.getOrDefault(fallback).ifBlank { fallback }
    }

    private fun JSONObject.toAnnouncementSummary() = NativeAnnouncementSummary(
        id = optInt("id"),
        title = optString("title", "未命名公告"),
        summary = optString("summary"),
        isPinned = optBoolean("isPinned"),
        viewCount = optInt("viewCount"),
        unread = optBoolean("unread"),
        publishedAt = optString("publishedAt"),
        updatedAt = optString("updatedAt"),
    )

    private fun JSONArray?.toAnnouncementSummaries(): List<NativeAnnouncementSummary> = buildList {
        for (index in 0 until (this@toAnnouncementSummaries?.length() ?: 0)) {
            this@toAnnouncementSummaries?.optJSONObject(index)?.let { add(it.toAnnouncementSummary()) }
        }
    }

    private fun JSONObject.toFeedbackPage(fallbackPage: Int) = NativeFeedbackPage(
        items = optJSONArray("items").toFeedbackSummaries(),
        total = optInt("total"),
        page = optInt("page", fallbackPage),
        totalPages = optInt("totalPages", 1),
    )

    private fun JSONObject.toFeedbackSummary() = NativeFeedbackSummary(
        id = optInt("id"),
        category = optString("category", "other"),
        title = optString("title", "未命名反馈"),
        status = optString("status", "pending"),
        user = optJSONObject("user").toCommunityUser(),
        replyCount = optInt("replyCount"),
        createdAt = optString("createdAt"),
        updatedAt = optString("updatedAt"),
    )

    private fun JSONArray?.toFeedbackSummaries(): List<NativeFeedbackSummary> = buildList {
        for (index in 0 until (this@toFeedbackSummaries?.length() ?: 0)) {
            this@toFeedbackSummaries?.optJSONObject(index)?.let { add(it.toFeedbackSummary()) }
        }
    }

    private fun JSONObject.toFeedbackDetail() = NativeFeedbackDetail(
        summary = toFeedbackSummary(),
        content = optString("content"),
        reviewedAt = optStringOrNull("reviewedAt"),
        reviewedBy = optJSONObject("reviewedBy")?.toCommunityUser(),
        replies = optJSONArray("replies").toFeedbackReplies(),
    )

    private fun JSONArray?.toFeedbackReplies(): List<NativeFeedbackReply> = buildList {
        for (index in 0 until (this@toFeedbackReplies?.length() ?: 0)) {
            this@toFeedbackReplies?.optJSONObject(index)?.let {
                add(NativeFeedbackReply(it.optInt("id"), it.optString("content"), it.optJSONObject("author").toCommunityUser(), it.optString("createdAt")))
            }
        }
    }

    private fun JSONObject.toSuggestionPage(fallbackPage: Int) = NativeSuggestionPage(
        items = optJSONArray("items").toSuggestionSummaries(),
        total = optInt("total"),
        page = optInt("page", fallbackPage),
        totalPages = optInt("totalPages", 1),
    )

    private fun JSONObject.toSuggestionSummary() = NativeSuggestionSummary(
        id = optInt("id"),
        title = optString("title", "未命名建议"),
        status = optString("status", "pending"),
        user = optJSONObject("user").toCommunityUser(),
        reviewedBy = optJSONObject("reviewedBy")?.toCommunityUser(),
        replyCount = optInt("replyCount"),
        createdAt = optString("createdAt"),
        updatedAt = optString("updatedAt"),
    )

    private fun JSONArray?.toSuggestionSummaries(): List<NativeSuggestionSummary> = buildList {
        for (index in 0 until (this@toSuggestionSummaries?.length() ?: 0)) {
            this@toSuggestionSummaries?.optJSONObject(index)?.let { add(it.toSuggestionSummary()) }
        }
    }

    private fun JSONObject.toSuggestionDetail() = NativeSuggestionDetail(
        summary = toSuggestionSummary(),
        content = optString("content"),
        reviewedAt = optStringOrNull("reviewedAt"),
        replies = optJSONArray("replies").toSuggestionReplies(),
    )

    private fun JSONArray?.toSuggestionReplies(): List<NativeSuggestionReply> = buildList {
        for (index in 0 until (this@toSuggestionReplies?.length() ?: 0)) {
            this@toSuggestionReplies?.optJSONObject(index)?.let {
                add(NativeSuggestionReply(it.optInt("id"), it.optString("content"), it.optJSONObject("author").toCommunityUser(), it.optString("createdAt")))
            }
        }
    }

    private fun JSONObject?.toCommunityUser(): NativeCommunityUser {
        val value = this ?: JSONObject()
        return NativeCommunityUser(value.optInt("id"), value.optString("username"), value.optString("nickname").ifBlank { value.optString("username") })
    }

    private fun JSONObject.optStringOrNull(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
}
