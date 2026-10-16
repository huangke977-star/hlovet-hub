package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class NativeModerationUser(
    val id: Int,
    val nickname: String,
    val username: String,
    val avatarUrl: String?,
    val roleName: String,
)

internal data class NativeReportArticle(
    val id: Int,
    val title: String,
    val slug: String,
    val author: NativeModerationUser,
)

internal data class NativeReportComment(
    val id: Int,
    val body: String,
    val status: String,
)

internal data class NativeReportGroup(
    val id: Int,
    val conversationId: Int,
    val name: String,
    val avatarUrl: String?,
)

internal data class NativeReportAttachment(
    val id: Int,
    val kind: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val thumbnailUrl: String?,
)

internal data class NativeReportMessage(
    val id: Int,
    val body: String,
    val type: String,
    val sender: NativeModerationUser,
    val attachments: List<NativeReportAttachment>,
    val createdAt: String,
)

internal data class NativeModerationReport(
    val key: String,
    val id: Int,
    val source: String,
    val sourceLabel: String,
    val status: String,
    val reason: String,
    val detail: String?,
    val resolution: String?,
    val reporter: NativeModerationUser,
    val targetUser: NativeModerationUser?,
    val article: NativeReportArticle?,
    val comment: NativeReportComment?,
    val group: NativeReportGroup?,
    val message: NativeReportMessage?,
    val createdAt: String,
    val handledAt: String?,
)

internal data class NativeModerationReportSummary(
    val total: Int,
    val pending: Int,
    val article: Int,
    val comment: Int,
    val groupMessage: Int,
)

internal data class NativeModerationSourceStats(val source: String, val total: Int, val pending: Int, val overdue: Int)

internal data class NativeModerationOverview(
    val total: Int,
    val pending: Int,
    val resolved: Int,
    val rejected: Int,
    val overdue: Int,
    val averageHandleMinutes: Int?,
    val sources: List<NativeModerationSourceStats>,
    val ruleHitsLast7Days: Int,
    val ruleHitsLast30Days: Int,
)

internal data class NativeModerationRuleHit(
    val id: Int,
    val ruleName: String,
    val ruleType: String,
    val actorName: String,
    val username: String,
    val source: String,
    val action: String,
    val contentPreview: String,
    val detail: String,
    val createdAt: String,
)

internal data class NativeModerationRule(
    val id: Int,
    val name: String,
    val type: String,
    val action: String,
    val sources: List<String>,
    val keywords: String,
    val threshold: Int,
    val windowSeconds: Int,
    val enabled: Boolean,
)

internal data class NativeModerationRuleHitPage(val items: List<NativeModerationRuleHit>, val page: Int, val totalPages: Int)

internal data class NativeModerationTemplate(
    val id: Int,
    val name: String,
    val status: String,
    val content: String,
    val enabled: Boolean,
)

internal data class NativeModerationSettings(
    val deadlineHours: Int,
    val reminderLeadHours: Int,
    val automaticRemindersEnabled: Boolean,
    val updatedAt: String,
)

internal data class NativeModerationRuleInput(
    val name: String,
    val type: String,
    val action: String,
    val sources: List<String>,
    val keywords: String,
    val threshold: Int,
    val windowSeconds: Int,
    val enabled: Boolean,
)

internal data class NativeModerationTemplateInput(val name: String, val status: String, val content: String, val enabled: Boolean)

internal data class NativeModerationReportPage(
    val items: List<NativeModerationReport>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

/** Client for the signed-in user's submitted moderation reports. */
internal object NativeReportsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun list(accessToken: String): List<NativeModerationReport> =
        request("/moderation/my-reports", "GET", accessToken).optJSONArray("items").toReports()

    fun listAdmin(
        accessToken: String,
        page: Int = 1,
        pageSize: Int = 20,
        status: String = "pending",
        source: String = "all",
    ): NativeModerationReportPage {
        val query = buildString {
            append("?status=").append(encode(status))
            append("&page=").append(page.coerceAtLeast(1))
            append("&pageSize=").append(pageSize.coerceIn(1, 50))
            if (source != "all") append("&type=").append(encode(source))
        }
        return request("/moderation/reports$query", "GET", accessToken).let { response ->
            NativeModerationReportPage(
                items = response.optJSONArray("items").toReports(),
                total = response.optInt("total"),
                page = response.optInt("page", page),
                pageSize = response.optInt("pageSize", pageSize),
                totalPages = response.optInt("totalPages", 1).coerceAtLeast(1),
            )
        }
    }

    fun moderateArticleReport(accessToken: String, id: Int, status: String, resolution: String, articleStatus: String? = null) {
        val body = JSONObject().put("status", status).put("resolution", resolution)
        if (!articleStatus.isNullOrBlank()) body.put("articleStatus", articleStatus)
        request("/articles/admin/article-reports/$id", "PATCH", accessToken, body)
    }

    fun moderateCommentReport(accessToken: String, id: Int, status: String, resolution: String, commentStatus: String? = null) {
        val body = JSONObject().put("status", status).put("resolution", resolution)
        if (!commentStatus.isNullOrBlank()) body.put("commentStatus", commentStatus)
        request("/articles/admin/comment-reports/$id", "PATCH", accessToken, body)
    }

    fun moderateGroupReport(accessToken: String, id: Int, status: String, resolution: String, deleteMessage: Boolean) {
        request("/social/group-reports/$id", "PATCH", accessToken, JSONObject().put("status", status).put("resolution", resolution).put("deleteMessage", deleteMessage))
    }

    fun bulkModerate(accessToken: String, source: String, ids: List<Int>, status: String, resolution: String): NativeModerationBatchResult {
        val response = request(
            "/moderation/reports/bulk",
            "POST",
            accessToken,
            JSONObject().put("source", source).put("reportIds", JSONArray(ids)).put("status", status).put("resolution", resolution),
        )
        return NativeModerationBatchResult(
            succeeded = response.optJSONArray("succeeded").toIntList(),
            failed = response.optJSONArray("failed").toFailureList(),
        )
    }

    fun getArticlePreview(accessToken: String, reportId: Int): RemoteArticle =
        NativeArticleApi.parseArticle(request("/articles/mine/reports/$reportId/preview", "GET", accessToken))

    fun summary(accessToken: String): NativeModerationReportSummary {
        val response = request("/moderation/reports/summary", "GET", accessToken)
        val bySource = response.optJSONObject("bySource") ?: JSONObject()
        return NativeModerationReportSummary(
            total = response.optInt("total"),
            pending = response.optInt("pending"),
            article = bySource.optInt("article"),
            comment = bySource.optInt("comment"),
            groupMessage = bySource.optInt("group_message"),
        )
    }

    fun overview(accessToken: String): NativeModerationOverview {
        val response = request("/moderation/overview", "GET", accessToken)
        val reports = response.optJSONObject("reports") ?: JSONObject()
        val hits = response.optJSONObject("ruleHits") ?: JSONObject()
        val sourceStats = reports.optJSONObject("bySource") ?: JSONObject()
        val sources = listOf("article", "comment", "group_message").map { source ->
            val item = sourceStats.optJSONObject(source) ?: JSONObject()
            NativeModerationSourceStats(source, item.optInt("total"), item.optInt("pending"), item.optInt("overdue"))
        }
        return NativeModerationOverview(
            total = reports.optInt("total"),
            pending = reports.optInt("pending"),
            resolved = reports.optInt("resolved"),
            rejected = reports.optInt("rejected"),
            overdue = reports.optInt("overdue"),
            averageHandleMinutes = if (reports.isNull("averageHandleMinutes")) null else reports.optInt("averageHandleMinutes"),
            sources = sources,
            ruleHitsLast7Days = hits.optInt("last7Days"),
            ruleHitsLast30Days = hits.optInt("last30Days"),
        )
    }

    fun ruleHits(accessToken: String, page: Int = 1): NativeModerationRuleHitPage {
        val response = request("/moderation/rule-hits?page=${page.coerceAtLeast(1)}&pageSize=20", "GET", accessToken)
        val items = buildList {
            val array = response.optJSONArray("items")
            for (index in 0 until (array?.length() ?: 0)) {
                val item = array?.optJSONObject(index) ?: continue
                val rule = item.optJSONObject("rule") ?: JSONObject()
                val actor = item.optJSONObject("actor") ?: JSONObject()
                add(NativeModerationRuleHit(item.optInt("id"), rule.optString("name"), rule.optString("type"), actor.optString("nickname"), actor.optString("username"), item.optString("source"), item.optString("action"), item.optString("contentPreview"), item.optString("detail"), item.optString("createdAt")))
            }
        }
        return NativeModerationRuleHitPage(items, response.optInt("page", page), response.optInt("totalPages", 1).coerceAtLeast(1))
    }

    fun rules(accessToken: String): List<NativeModerationRule> = request("/moderation/rules", "GET", accessToken).optJSONArray("items").let { array ->
        buildList {
            for (index in 0 until (array?.length() ?: 0)) {
                val item = array?.optJSONObject(index) ?: continue
                val sources = item.optJSONArray("sources")
                add(NativeModerationRule(item.optInt("id"), item.optString("name"), item.optString("type"), item.optString("action", "record"), buildList { for (sourceIndex in 0 until (sources?.length() ?: 0)) add(sources?.optString(sourceIndex).orEmpty()) }, item.optString("keywords"), item.optInt("threshold", 1), item.optInt("windowSeconds", 60), item.optBoolean("enabled", true)))
            }
        }
    }

    fun saveRule(accessToken: String, id: Int?, input: NativeModerationRuleInput): NativeModerationRule {
        val body = JSONObject()
            .put("name", input.name.trim())
            .put("action", input.action)
            .put("sources", JSONArray(input.sources))
            .put("keywords", input.keywords.trim())
            .put("threshold", input.threshold)
            .put("windowSeconds", input.windowSeconds)
            .put("enabled", input.enabled)
        val method: String
        val path: String
        if (id == null) {
            body.put("type", input.type)
            method = "POST"
            path = "/moderation/rules"
        } else {
            method = "PATCH"
            path = "/moderation/rules/$id"
        }
        return request(path, method, accessToken, body).toModerationRule()
    }

    fun deleteRule(accessToken: String, id: Int) { request("/moderation/rules/$id", "DELETE", accessToken) }

    fun templates(accessToken: String): List<NativeModerationTemplate> = request("/moderation/templates", "GET", accessToken).optJSONArray("items").let { array ->
        buildList { for (index in 0 until (array?.length() ?: 0)) array?.optJSONObject(index)?.let { add(it.toModerationTemplate()) } }
    }

    fun saveTemplate(accessToken: String, id: Int?, input: NativeModerationTemplateInput): NativeModerationTemplate {
        val body = JSONObject().put("name", input.name.trim()).put("status", input.status).put("content", input.content.trim()).put("enabled", input.enabled)
        return if (id == null) request("/moderation/templates", "POST", accessToken, body).toModerationTemplate()
        else request("/moderation/templates/$id", "PATCH", accessToken, body).toModerationTemplate()
    }

    fun deleteTemplate(accessToken: String, id: Int) { request("/moderation/templates/$id", "DELETE", accessToken) }

    fun settings(accessToken: String): NativeModerationSettings = request("/moderation/settings", "GET", accessToken).toModerationSettings()

    fun updateSettings(accessToken: String, input: NativeModerationSettings): NativeModerationSettings = request(
        "/moderation/settings",
        "PUT",
        accessToken,
        JSONObject().put("deadlineHours", input.deadlineHours).put("reminderLeadHours", input.reminderLeadHours).put("automaticRemindersEnabled", input.automaticRemindersEnabled),
    ).toModerationSettings()

    private fun request(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = if (method == "GET") 20_000 else 30_000
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(errorMessage(body, "举报记录加载失败（HTTP $status）"))
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(body: String, fallback: String): String = runCatching {
        val message = JSONObject(body).opt("message")
        when (message) {
            is JSONArray -> message.join("、")
            is String -> message
            else -> fallback
        }
    }.getOrDefault(fallback).ifBlank { fallback }

    private fun JSONArray?.toReports(): List<NativeModerationReport> = buildList {
        for (index in 0 until (this@toReports?.length() ?: 0)) {
            this@toReports?.optJSONObject(index)?.let { add(it.toReport()) }
        }
    }

    private fun JSONArray?.toIntList(): List<Int> = buildList {
        for (index in 0 until (this@toIntList?.length() ?: 0)) add(this@toIntList?.optInt(index) ?: 0)
    }

    private fun JSONArray?.toFailureList(): List<NativeModerationBatchFailure> = buildList {
        for (index in 0 until (this@toFailureList?.length() ?: 0)) {
            val value = this@toFailureList?.optJSONObject(index) ?: continue
            add(NativeModerationBatchFailure(value.optInt("id"), value.optString("message")))
        }
    }

    private fun JSONObject.toReport() = NativeModerationReport(
        key = optString("key", "${optString("source")}-${optInt("id")}"),
        id = optInt("id"),
        source = optString("source"),
        sourceLabel = optString("sourceLabel"),
        status = optString("status", "pending"),
        reason = optString("reason", "other"),
        detail = optStringOrNull("detail"),
        resolution = optStringOrNull("resolution"),
        reporter = optJSONObject("reporter").toUser(),
        targetUser = optJSONObject("targetUser")?.toUser(),
        article = optJSONObject("article")?.toArticle(),
        comment = optJSONObject("comment")?.toComment(),
        group = optJSONObject("group")?.toGroup(),
        message = optJSONObject("message")?.toMessage(),
        createdAt = optString("createdAt"),
        handledAt = optStringOrNull("handledAt"),
    )

    private fun JSONObject.toModerationRule() = NativeModerationRule(
        id = optInt("id"), name = optString("name"), type = optString("type"), action = optString("action", "record"),
        sources = buildList { val array = optJSONArray("sources"); for (index in 0 until (array?.length() ?: 0)) add(array?.optString(index).orEmpty()) },
        keywords = optString("keywords"), threshold = optInt("threshold", 1), windowSeconds = optInt("windowSeconds", 60), enabled = optBoolean("enabled", true),
    )

    private fun JSONObject.toModerationTemplate() = NativeModerationTemplate(optInt("id"), optString("name"), optString("status", "resolved"), optString("content"), optBoolean("enabled", true))

    private fun JSONObject.toModerationSettings() = NativeModerationSettings(optInt("deadlineHours", 24), optInt("reminderLeadHours", 4), optBoolean("automaticRemindersEnabled", true), optString("updatedAt"))

    private fun JSONObject?.toUser(): NativeModerationUser {
        val value = this ?: JSONObject()
        return NativeModerationUser(
            id = value.optInt("id"),
            nickname = value.optString("nickname").ifBlank { value.optString("username", "站内用户") },
            username = value.optString("username"),
            avatarUrl = value.optStringOrNull("avatarUrl"),
            roleName = value.optJSONObject("role")?.optString("name").orEmpty(),
        )
    }

    private fun JSONObject.toArticle() = NativeReportArticle(
        id = optInt("id"),
        title = optString("title", "文章内容"),
        slug = optString("slug"),
        author = optJSONObject("author").toUser(),
    )

    private fun JSONObject.toComment() = NativeReportComment(
        id = optInt("id"),
        body = optString("body"),
        status = optString("status"),
    )

    private fun JSONObject.toGroup() = NativeReportGroup(
        id = optInt("id"),
        conversationId = optInt("conversationId"),
        name = optString("name", "群聊"),
        avatarUrl = optStringOrNull("avatarUrl"),
    )

    private fun JSONObject.toMessage() = NativeReportMessage(
        id = optInt("id"),
        body = optString("body"),
        type = optString("type"),
        sender = optJSONObject("sender").toUser(),
        attachments = optJSONArray("attachments").toAttachments(),
        createdAt = optString("createdAt"),
    )

    private fun JSONArray?.toAttachments(): List<NativeReportAttachment> = buildList {
        for (index in 0 until (this@toAttachments?.length() ?: 0)) {
            this@toAttachments?.optJSONObject(index)?.let { add(it.toAttachment()) }
        }
    }

    private fun JSONObject.toAttachment() = NativeReportAttachment(
        id = optInt("id"),
        kind = optString("kind", "file"),
        originalName = optString("originalName", "附件"),
        mimeType = optString("mimeType"),
        sizeBytes = optLong("sizeBytes"),
        downloadUrl = optString("downloadUrl"),
        thumbnailUrl = optStringOrNull("thumbnailUrl"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

internal data class NativeModerationBatchFailure(val id: Int, val message: String)

internal data class NativeModerationBatchResult(
    val succeeded: List<Int>,
    val failed: List<NativeModerationBatchFailure>,
)
