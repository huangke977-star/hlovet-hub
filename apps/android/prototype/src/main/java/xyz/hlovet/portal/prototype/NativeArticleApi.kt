package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.BufferedOutputStream

internal data class NativeArticleList(
    val items: List<RemoteArticle>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

internal data class NativeDraftArticle(
    val id: Int,
    val title: String,
    val summary: String,
    val content: String,
    val contentFormat: String,
    val category: String,
    val tags: List<String>,
    val titleColor: String,
    val visibility: String,
    val roleCodes: List<String>,
    val status: String,
    val slug: String,
    val updatedAt: String,
    val publishAt: String? = null,
    val unpublishAt: String? = null,
    val scheduleError: String? = null,
    val attachments: List<NativeArticleAttachment> = emptyList(),
)

internal data class NativeDraftList(
    val items: List<NativeDraftArticle>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

internal fun appendNativeDraftPage(current: NativeDraftList?, next: NativeDraftList): NativeDraftList {
    if (current == null || next.page <= 1) return next
    return current.copy(
        items = (current.items + next.items).distinctBy { it.id },
        total = next.total,
        page = next.page,
        totalPages = next.totalPages,
    )
}

internal data class NativeArticleMineSummary(
    val total: Int,
    val draft: Int,
    val published: Int,
    val unpublished: Int,
    val blocked: Int,
    val deleted: Int,
)

internal data class NativeArticleCenterSummary(
    val discover: Int,
    val subscriptions: Int,
    val mine: Int,
    val favorites: Int,
    val liked: Int,
    val readLater: Int,
    val history: Int,
    val manage: Int,
)

internal data class NativeArticleDashboard(
    val views: Int,
    val likes: Int,
    val comments: Int,
    val favorites: Int,
    val resourceExchanges: Int,
    val pendingPoints: Int,
    val settledPoints: Int,
    val recentResourceIncome: List<NativeResourceIncome>,
)

internal data class NativeResourceIncome(
    val id: Int,
    val articleTitle: String,
    val articleSlug: String,
    val pointCost: Int,
    val createdAt: String,
    val availableAt: String,
    val settledAt: String?,
    val buyerName: String?,
    val deliveryStatus: String?,
)

internal data class NativeArticleScheduleItem(
    val id: Int,
    val title: String,
    val slug: String,
    val status: String,
    val publishAt: String?,
    val unpublishAt: String?,
    val error: String?,
    val updatedAt: String,
)

internal data class NativeDraftInput(
    val title: String,
    val summary: String,
    val content: String,
    val contentFormat: String,
    val category: String,
    val tags: String,
    val titleColor: String = "",
    val visibility: String = "public",
    val roleCodes: List<String> = emptyList(),
)

internal const val NATIVE_DEFAULT_ARTICLE_CONTENT_FORMAT = "html"

internal fun nativeNewArticleContentFormat(templateFormat: String?): String {
    return templateFormat
        ?.trim()
        ?.lowercase()
        ?.takeIf { it == "html" || it == "markdown" }
        ?: NATIVE_DEFAULT_ARTICLE_CONTENT_FORMAT
}

internal fun nativeStartsInVisualEditor(contentFormat: String?): Boolean =
    nativeNewArticleContentFormat(contentFormat) == "html"

internal fun normalizeNativeTitleColor(value: String): String? {
    val normalized = value.trim()
    if (normalized.isBlank()) return ""
    val withPrefix = if (normalized.startsWith('#')) normalized else "#$normalized"
    return withPrefix.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.uppercase()
}

internal data class NativePublishCheck(
    val valid: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
)

internal data class NativeArticleVersionSummary(
    val id: Int,
    val versionNumber: Int,
    val source: String,
    val changedFields: List<String>,
    val createdAt: String,
)

internal data class NativeArticleVersion(
    val summary: NativeArticleVersionSummary,
    val title: String,
    val articleSummary: String,
    val content: String,
    val contentFormat: String,
    val category: String,
    val tags: List<String>,
    val titleColor: String,
    val visibility: String,
    val status: String,
    val roleCodes: List<String>,
)

internal data class NativeUploadFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

internal data class NativeArticleAttachment(
    val id: Int,
    val kind: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val thumbnailUrl: String?,
)

internal data class NativeArticleComment(
    val id: Int,
    val articleId: Int,
    val parentId: Int?,
    val body: String,
    val status: String,
    val likeCount: Int,
    val liked: Boolean,
    val reported: Boolean,
    val quote: NativeCommentQuote?,
    val authorName: String,
    val authorUsername: String,
    val authorInitials: String,
    val createdAt: String,
    val attachments: List<NativeArticleAttachment> = emptyList(),
    val pendingReportCount: Int = 0,
    val reportCount: Int = 0,
)

internal data class NativeCommentQuote(
    val id: Int,
    val body: String,
    val authorName: String,
    val available: Boolean,
)

internal data class NativeArticleComments(
    val items: List<NativeArticleComment>,
    val hasMore: Boolean,
    val nextCursor: Int?,
    val totalThreads: Int,
)

internal data class NativeArticleAppeal(
    val id: Int,
    val articleId: Int,
    val articleTitle: String,
    val articleSlug: String,
    val articleStatus: String,
    val authorName: String,
    val authorUsername: String,
    val reason: String,
    val status: String,
    val resolution: String?,
    val createdAt: String,
    val reviewedAt: String?,
)

internal val NATIVE_ARTICLE_REPORT_REASONS = listOf(
    "spam",
    "harassment",
    "illegal",
    "privacy",
    "misinformation",
    "other",
)

internal fun normalizeNativeArticleReportDetail(value: String): String? =
    value.trim().take(500).takeIf(String::isNotBlank)

internal object NativeArticleApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    internal fun parseArticle(value: JSONObject): RemoteArticle = value.toRemoteArticle()

    /** 将 value 解析为编辑草稿，返回包含服务端计划与设备本地时间的完整编辑模型。 */
    internal fun parseDraftArticle(value: JSONObject): NativeDraftArticle {
        // 测试和真实读取共用解析入口，避免计划字段存在而编辑器回显为空。
        return value.toDraftArticle()
    }

    fun redeemResource(accessToken: String, articleId: Int, blockKey: String): RemoteArticle {
        return requestMutation(
            "/articles/$articleId/redeem",
            "POST",
            accessToken,
            JSONObject().put("blockKey", blockKey),
        ).toRemoteArticle()
    }

    fun listMine(
        accessToken: String,
        page: Int = 1,
        pageSize: Int = 20,
        status: String? = null,
        search: String? = null,
    ): NativeArticleList {
        return listByPath("/articles/mine", accessToken, page, pageSize, status, search)
    }

    fun mineSummary(accessToken: String): NativeArticleMineSummary {
        val response = requestGet("/articles/mine/summary", accessToken)
        return NativeArticleMineSummary(
            total = response.optInt("total"),
            draft = response.optInt("draft"),
            published = response.optInt("published"),
            unpublished = response.optInt("unpublished"),
            blocked = response.optInt("blocked"),
            deleted = response.optInt("deleted"),
        )
    }

    fun publicCenterSummary(): NativeArticleCenterSummary =
        parseNativeArticleCenterSummary(requestGet("/articles/center/summary", null))

    fun visibleCenterSummary(accessToken: String): NativeArticleCenterSummary =
        parseNativeArticleCenterSummary(requestGet("/articles/visible/center/summary", accessToken))

    fun mineDashboard(accessToken: String): NativeArticleDashboard {
        val response = requestGet("/articles/mine/dashboard", accessToken)
        return NativeArticleDashboard(
            views = response.optInt("views"),
            likes = response.optInt("likes"),
            comments = response.optInt("comments"),
            favorites = response.optInt("favorites"),
            resourceExchanges = response.optInt("resourceExchanges"),
            pendingPoints = response.optInt("pendingPoints"),
            settledPoints = response.optInt("settledPoints"),
            recentResourceIncome = response.optJSONArray("recentResourceIncome").toResourceIncome(),
        )
    }

    fun listMySchedules(accessToken: String): List<NativeArticleScheduleItem> {
        return requestGet("/articles/mine/schedules", accessToken)
            .optJSONArray("items")
            .toScheduleItems()
    }

    fun scheduleArticle(
        accessToken: String,
        id: Int,
        publishAt: String?,
        unpublishAt: String?,
    ): RemoteArticle {
        val body = JSONObject().apply {
            put("publishAt", publishAt ?: JSONObject.NULL)
            put("unpublishAt", unpublishAt ?: JSONObject.NULL)
        }
        return requestMutation("/articles/$id/schedule", "PATCH", accessToken, body).toRemoteArticle()
    }

    fun cancelArticleSchedule(accessToken: String, id: Int): RemoteArticle {
        return scheduleArticle(accessToken, id, publishAt = null, unpublishAt = null)
    }

    fun listMyDrafts(
        accessToken: String,
        page: Int = 1,
        pageSize: Int = 12,
        search: String? = null,
    ): NativeDraftList {
        val query = buildString {
            append("/articles/mine?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}&sort=latest&status=draft")
            if (!search.isNullOrBlank()) append("&search=${URLEncoder.encode(search.trim(), Charsets.UTF_8.name())}")
        }
        val response = requestGet(query, accessToken)
        return NativeDraftList(
            items = response.optJSONArray("items").toDraftArticles(),
            total = response.optInt("total"),
            page = response.optInt("page", page),
            totalPages = response.optInt("totalPages", 1),
        )
    }

    fun getMyArticle(accessToken: String, id: Int): NativeDraftArticle {
        return requestGet("/articles/mine/$id", accessToken).toDraftArticle()
    }

    fun getAdminArticle(accessToken: String, id: Int): RemoteArticle {
        return requestGet("/articles/admin/$id", accessToken).toRemoteArticle()
    }

    fun createArticleAppeal(accessToken: String, articleId: Int, reason: String): NativeArticleAppeal {
        return requestMutation(
            "/articles/$articleId/appeals",
            "POST",
            accessToken,
            JSONObject().put("reason", reason.trim()),
        ).toArticleAppeal()
    }

    fun listAdminArticles(
        accessToken: String,
        page: Int = 1,
        pageSize: Int = 12,
        search: String? = null,
        status: String? = null,
    ): NativeArticleList {
        return listByPath("/articles/admin", accessToken, page, pageSize, status, search)
    }

    fun listAdminComments(accessToken: String, articleId: Int): List<NativeArticleComment> {
        return requestGet("/articles/admin/comments?articleId=$articleId", accessToken)
            .optJSONArray("items")
            .toComments()
    }

    fun moderateAdminArticle(
        accessToken: String,
        articleId: Int,
        status: String,
        visibility: String,
        roleCodes: List<String>,
        isPinned: Boolean,
        pinOrder: Int,
        titleColor: String,
        blockedReason: String,
    ): RemoteArticle {
        val body = JSONObject().apply {
            put("status", status)
            put("visibility", visibility)
            put("roleCodes", JSONArray(roleCodes))
            put("isPinned", isPinned)
            put("pinOrder", pinOrder.coerceAtLeast(0))
            put("titleColor", titleColor)
            put("blockedReason", blockedReason)
        }
        return requestMutation("/articles/admin/$articleId", "PATCH", accessToken, body).toRemoteArticle()
    }

    fun moderateAdminComment(accessToken: String, commentId: Int, status: String) {
        requestMutation("/articles/admin/comments/$commentId", "PATCH", accessToken, JSONObject().put("status", status))
    }

    fun listAdminAppeals(accessToken: String, status: String? = null): List<NativeArticleAppeal> {
        val query = status?.takeIf(String::isNotBlank)?.let { "?status=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()
        return requestGet("/articles/admin/appeals$query", accessToken).optJSONArray("items").toArticleAppeals()
    }

    fun moderateAdminAppeal(accessToken: String, appealId: Int, status: String, resolution: String) {
        requestMutation(
            "/articles/admin/appeals/$appealId",
            "PATCH",
            accessToken,
            JSONObject().put("status", status).put("resolution", resolution.trim()),
        )
    }

    internal fun parseAppeal(value: JSONObject): NativeArticleAppeal = value.toArticleAppeal()

    fun createAutosave(accessToken: String, input: NativeDraftInput): NativeDraftArticle {
        return requestMutation("/articles/autosave", "POST", accessToken, input.toJson()).toDraftArticle()
    }

    fun autosave(accessToken: String, id: Int, input: NativeDraftInput): NativeDraftArticle {
        return requestMutation("/articles/$id/autosave", "POST", accessToken, input.toJson()).toDraftArticle()
    }

    fun createArticle(accessToken: String, input: NativeDraftInput): NativeDraftArticle {
        return requestMutation("/articles", "POST", accessToken, input.toJson()).toDraftArticle()
    }

    fun updateArticle(accessToken: String, id: Int, input: NativeDraftInput, status: String = "draft"): NativeDraftArticle {
        return requestMutation(
            "/articles/$id",
            "PATCH",
            accessToken,
            input.toJson().put("status", status),
        ).toDraftArticle()
    }

    fun publishCheck(accessToken: String, id: Int): NativePublishCheck {
        val response = requestGet("/articles/$id/publish-check", accessToken)
        return NativePublishCheck(
            valid = response.optBoolean("valid"),
            errors = response.optJSONArray("errors").toStrings(),
            warnings = response.optJSONArray("warnings").toStrings(),
        )
    }

    fun publish(accessToken: String, id: Int): NativeDraftArticle {
        return requestMutation("/articles/$id/publish", "POST", accessToken).toDraftArticle()
    }

    fun unpublish(accessToken: String, id: Int): RemoteArticle {
        return requestMutation("/articles/$id/unpublish", "POST", accessToken).toRemoteArticle()
    }

    fun deleteArticle(accessToken: String, id: Int) {
        requestMutation("/articles/$id", "DELETE", accessToken)
    }

    fun restoreArticle(accessToken: String, id: Int): RemoteArticle {
        return requestMutation("/articles/$id/restore", "POST", accessToken).toRemoteArticle()
    }

    fun permanentlyDeleteArticle(accessToken: String, id: Int) {
        requestMutation("/articles/$id/permanent", "DELETE", accessToken)
    }

    fun listVersions(accessToken: String, id: Int): List<NativeArticleVersionSummary> {
        val response = requestGet("/articles/$id/versions", accessToken)
        return response.optJSONArray("items").toVersionSummaries()
    }

    fun getVersion(accessToken: String, articleId: Int, versionId: Int): NativeArticleVersion {
        return requestGet("/articles/$articleId/versions/$versionId", accessToken).toArticleVersion()
    }

    fun restoreVersion(accessToken: String, articleId: Int, versionId: Int): NativeDraftArticle {
        return requestMutation(
            "/articles/$articleId/versions/$versionId/restore",
            "POST",
            accessToken,
        ).toDraftArticle()
    }

    fun uploadAttachments(
        accessToken: String,
        articleId: Int,
        files: List<NativeUploadFile>,
    ): List<NativeArticleAttachment> {
        require(files.isNotEmpty()) { "请选择需要上传的文件。" }
        val boundary = "----HlovetAndroid${System.currentTimeMillis()}"
        val connection = (URL("$baseUrl/articles/$articleId/attachments").openConnection() as HttpURLConnection).apply {
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
                    ?: "附件上传失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            JSONObject(body).optJSONArray("attachments").toAttachments()
        } finally {
            connection.disconnect()
        }
    }

    fun removeAttachment(accessToken: String, articleId: Int, attachmentId: Int) {
        requestMutation(
            "/articles/$articleId/attachments/$attachmentId",
            "DELETE",
            accessToken,
        )
    }

    fun listFavorites(accessToken: String, page: Int = 1, pageSize: Int = 20, search: String? = null): NativeArticleList {
        return listByPath("/articles/favorites", accessToken, page, pageSize, search = search)
    }

    fun listLiked(accessToken: String, page: Int = 1, pageSize: Int = 20, search: String? = null): NativeArticleList {
        return listByPath("/articles/liked", accessToken, page, pageSize, search = search)
    }

    fun listReadLater(accessToken: String, page: Int = 1, pageSize: Int = 20, search: String? = null): NativeArticleList {
        return listByPath("/articles/read-later", accessToken, page, pageSize, search = search)
    }

    fun listReadingHistory(accessToken: String, page: Int = 1, pageSize: Int = 20, search: String? = null): NativeArticleList {
        return listByPath("/articles/history", accessToken, page, pageSize, search = search)
    }

    fun updateReadingProgress(accessToken: String, articleId: Int, progress: Int): NativeReadingProgress {
        val response = requestMutation(
            "/articles/$articleId/reading-progress",
            "PATCH",
            accessToken,
            JSONObject().put("progress", progress.coerceIn(1, 100)),
        )
        return NativeReadingProgress(
            progress = response.optInt("progress", progress.coerceIn(1, 100)),
            lastReadAt = response.optString("lastReadAt"),
        )
    }

    fun removeReadingHistory(accessToken: String, articleId: Int) {
        requestMutation("/articles/history/$articleId", "DELETE", accessToken)
    }

    fun clearReadingHistory(accessToken: String) {
        requestMutation("/articles/history", "DELETE", accessToken)
    }

    fun listSubscriptions(accessToken: String, page: Int = 1, pageSize: Int = 20, search: String? = null): NativeArticleList {
        return listByPath("/articles/subscriptions", accessToken, page, pageSize, search = search)
    }

    /** Loads only the articles visible on a public user's profile. */
    fun listByAuthor(
        accessToken: String?,
        username: String,
        page: Int = 1,
        pageSize: Int = 12,
    ): NativeArticleList {
        val path = if (accessToken.isNullOrBlank()) "/articles" else "/articles/visible"
        val encodedUsername = URLEncoder.encode(username, Charsets.UTF_8.name()).replace("+", "%20")
        val response = requestGet(
            "$path?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}&sort=latest&authorUsername=$encodedUsername",
            accessToken,
        )
        return NativeArticleList(
            items = response.optJSONArray("items").toArticles(),
            total = response.optInt("total"),
            page = response.optInt("page", page),
            totalPages = response.optInt("totalPages", 1).coerceAtLeast(1),
        )
    }

    /** Loads the public or currently visible recommendation stream used by the native home page. */
    fun listHome(accessToken: String?, page: Int = 1, pageSize: Int = 8): NativeArticleList {
        val path = if (accessToken.isNullOrBlank()) "/articles" else "/articles/visible"
        val response = requestGet(
            "$path?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}&sort=recommended",
            accessToken,
        )
        return NativeArticleList(
            items = response.optJSONArray("items").toArticles(),
            total = response.optInt("total"),
            page = response.optInt("page", page),
            totalPages = response.optInt("totalPages", 1),
        )
    }

    private fun listByPath(
        path: String,
        accessToken: String,
        page: Int,
        pageSize: Int,
        status: String? = null,
        search: String? = null,
    ): NativeArticleList {
        val query = buildString {
            append("$path?page=$page&pageSize=$pageSize&sort=latest")
            if (!status.isNullOrBlank()) append("&status=${URLEncoder.encode(status, Charsets.UTF_8.name())}")
            if (!search.isNullOrBlank()) append("&search=${URLEncoder.encode(search, Charsets.UTF_8.name())}")
        }
        val response = requestGet(query, accessToken)
        return NativeArticleList(
            items = response.optJSONArray("items").toArticles(),
            total = response.optInt("total"),
            page = response.optInt("page", page),
            totalPages = response.optInt("totalPages", 1),
        )
    }

    fun getArticle(accessToken: String?, slug: String): RemoteArticle {
        val encodedSlug = URLEncoder.encode(slug, Charsets.UTF_8.name()).replace("+", "%20")
        val path = if (accessToken.isNullOrBlank()) {
            "/articles/$encodedSlug"
        } else {
            "/articles/visible/$encodedSlug"
        }
        return requestGet(path, accessToken).toRemoteArticle()
    }

    fun listComments(
        accessToken: String?,
        slug: String,
        cursor: Int? = null,
        pageSize: Int = 20,
        focusId: Int? = null,
    ): NativeArticleComments {
        val encodedSlug = URLEncoder.encode(slug, Charsets.UTF_8.name()).replace("+", "%20")
        val query = buildString {
            append("?pageSize=")
            append(pageSize.coerceIn(1, 50))
            if (cursor != null && cursor > 0) {
                append("&cursor=")
                append(cursor)
            }
            if (focusId != null && focusId > 0) {
                append("&focusId=")
                append(focusId)
            }
        }
        val path = if (accessToken.isNullOrBlank()) {
            "/articles/$encodedSlug/comments$query"
        } else {
            "/articles/visible/$encodedSlug/comments$query"
        }
        val response = requestGet(path, accessToken)
        return NativeArticleComments(
            items = response.optJSONArray("items").toComments(),
            hasMore = response.optBoolean("hasMore"),
            nextCursor = response.optIntOrNull("nextCursor"),
            totalThreads = response.optInt("totalThreads"),
        )
    }

    fun toggleLike(accessToken: String, articleId: Int, liked: Boolean): RemoteArticleAction {
        return requestMutation("/articles/$articleId/like", if (liked) "POST" else "DELETE", accessToken).toArticleAction(liked = liked)
    }

    fun toggleFavorite(accessToken: String, articleId: Int, favorited: Boolean): RemoteArticleAction {
        return requestMutation("/articles/$articleId/favorite", if (favorited) "POST" else "DELETE", accessToken).toArticleAction(favorited = favorited)
    }

    fun toggleReadLater(accessToken: String, articleId: Int, readLater: Boolean): Boolean {
        val response = requestMutation("/articles/$articleId/read-later", if (readLater) "POST" else "DELETE", accessToken)
        return response.optBoolean("readLater", readLater)
    }

    fun createComment(
        accessToken: String,
        articleId: Int,
        body: String,
        parentId: Int? = null,
        quotedCommentId: Int? = null,
        files: List<NativeUploadFile> = emptyList(),
    ): NativeArticleComment {
        if (files.isNotEmpty()) {
            return requestMultipartComment(
                articleId = articleId,
                accessToken = accessToken,
                body = body,
                parentId = parentId,
                quotedCommentId = quotedCommentId,
                files = files,
            )
        }
        val response = requestMutation(
            "/articles/$articleId/comments",
            "POST",
            accessToken,
            JSONObject().apply {
                put("body", body)
                parentId?.let { put("parentId", it) }
                quotedCommentId?.let { put("quotedCommentId", it) }
            },
        )
        return response.toComment()
    }

    private fun requestMultipartComment(
        articleId: Int,
        accessToken: String,
        body: String,
        parentId: Int?,
        quotedCommentId: Int?,
        files: List<NativeUploadFile>,
    ): NativeArticleComment {
        val boundary = "----HlovetAndroidComment${System.currentTimeMillis()}"
        val connection = (URL("$baseUrl/articles/$articleId/comments").openConnection() as HttpURLConnection).apply {
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
                fun writeField(name: String, value: String?) {
                    if (value == null) return
                    output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                }

                writeField("body", body)
                parentId?.let { writeField("parentId", it.toString()) }
                quotedCommentId?.let { writeField("quotedCommentId", it.toString()) }
                files.forEach { file ->
                    val safeName = file.name.replace("\"", "").replace("\r", "").replace("\n", "")
                    output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    output.write(
                        "Content-Disposition: form-data; name=\"files\"; filename=\"$safeName\"\r\n".toByteArray(Charsets.UTF_8),
                    )
                    output.write("Content-Type: ${file.mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(file.bytes)
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                }
                output.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "评论发布失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            JSONObject(responseBody).toComment()
        } finally {
            connection.disconnect()
        }
    }

    fun deleteComment(accessToken: String, commentId: Int) {
        requestMutation("/articles/comments/$commentId", "DELETE", accessToken)
    }

    fun reportComment(accessToken: String, commentId: Int, reason: String, detail: String?) {
        requestMutation(
            "/articles/comments/$commentId/report",
            "POST",
            accessToken,
            JSONObject().apply {
                put("reason", reason)
                detail?.trim()?.takeIf { it.isNotBlank() }?.let { put("detail", it) }
            },
        )
    }

    fun reportArticle(accessToken: String, articleId: Int, reason: String, detail: String?) {
        requestMutation(
            "/articles/$articleId/report",
            "POST",
            accessToken,
            JSONObject().apply {
                put("reason", reason)
                normalizeNativeArticleReportDetail(detail.orEmpty())?.let { put("detail", it) }
            },
        )
    }

    fun toggleCommentLike(accessToken: String, commentId: Int, liked: Boolean): NativeCommentAction {
        val response = requestMutation("/articles/comments/$commentId/like", if (liked) "POST" else "DELETE", accessToken)
        return NativeCommentAction(
            liked = response.optBoolean("liked", liked),
            likeCount = response.optInt("likeCount"),
        )
    }

    private fun requestGet(path: String, accessToken: String?): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "文章加载失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseNativeArticleCenterSummary(response: JSONObject): NativeArticleCenterSummary = NativeArticleCenterSummary(
        discover = response.optInt("discover"),
        subscriptions = response.optInt("subscriptions"),
        mine = response.optInt("mine"),
        favorites = response.optInt("favorites"),
        liked = response.optInt("liked"),
        readLater = response.optInt("readLater"),
        history = response.optInt("history"),
        manage = response.optInt("manage"),
    )

    private fun requestMutation(
        path: String,
        method: String,
        accessToken: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
            doOutput = method != "DELETE" || body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "文章操作失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.toArticles(): List<RemoteArticle> = buildList {
        if (this@toArticles == null) return@buildList
        for (index in 0 until length()) {
            val value = getJSONObject(index)
            add(RemoteArticle(
                id = value.optInt("id"),
                title = value.optString("title"),
                slug = value.optString("slug"),
                summary = value.optString("summary"),
                content = value.optString("content"),
                contentFormat = value.optString("contentFormat", "markdown"),
                category = value.optString("category"),
                tags = value.optJSONArray("tags").toStrings(),
                viewCount = value.optInt("viewCount"),
                likeCount = value.optInt("likeCount"),
                favoriteCount = value.optInt("favoriteCount"),
                commentCount = value.optInt("commentCount"),
                authorName = value.optJSONObject("author")?.optString("nickname")
                    ?: value.optString("authorName"),
                authorUsername = value.optJSONObject("author")?.optString("username")
                    ?: value.optString("authorUsername"),
                authorId = value.optJSONObject("author")?.optInt("id") ?: value.optInt("authorId"),
                liked = value.optBoolean("liked"),
                favorited = value.optBoolean("favorited"),
                readLater = value.optBoolean("readLater"),
                readingProgress = value.optInt("readingProgress").takeIf { value.has("readingProgress") && !value.isNull("readingProgress") },
                lastReadAt = value.optString("lastReadAt").takeIf { it.isNotBlank() && it != "null" },
                contentSegments = value.optJSONArray("contentSegments").toRemoteArticleSegments(),
                resourceEnabled = value.optJSONObject("resource")?.optBoolean("enabled") == true,
                resourceBlocks = value.optJSONObject("resource")?.optJSONArray("blocks").toRemoteResourceBlocks(),
                images = value.optJSONArray("images").toStrings(),
                status = value.optString("status", "published"),
                blockedReason = value.optString("blockedReason").takeIf(String::isNotBlank),
                isPinned = value.optBoolean("isPinned"),
                updatedAt = formatTime(value.optString("updatedAt")),
            ))
        }
    }

    private fun JSONArray?.toDraftArticles(): List<NativeDraftArticle> = buildList {
        if (this@toDraftArticles == null) return@buildList
        for (index in 0 until length()) add(getJSONObject(index).toDraftArticle())
    }

    private fun JSONArray?.toResourceIncome(): List<NativeResourceIncome> = buildList {
        if (this@toResourceIncome == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val article = value.optJSONObject("article")
            val buyer = value.optJSONObject("buyer")
            add(
                NativeResourceIncome(
                    id = value.optInt("id"),
                    articleTitle = article?.optString("title").orEmpty().ifBlank { "未命名文章" },
                    articleSlug = article?.optString("slug").orEmpty(),
                    pointCost = value.optInt("pointCost"),
                    createdAt = formatTime(value.optString("createdAt")),
                    availableAt = formatTime(value.optString("availableAt")),
                    settledAt = value.optString("settledAt").takeIf { it.isNotBlank() && it != "null" }?.let(::formatTime),
                    buyerName = buyer?.optString("nickname").orEmpty()
                        .ifBlank { buyer?.optString("username").orEmpty() }
                        .takeIf(String::isNotBlank),
                    deliveryStatus = value.optString("deliveryStatus").takeIf(String::isNotBlank),
                ),
            )
        }
    }

    /** 解析本人计划列表；接口列表的顶层时间转换为设备本地展示值，缺少列表返回空集合。 */
    private fun JSONArray?.toScheduleItems(): List<NativeArticleScheduleItem> = buildList {
        // 计划列表与文章详情结构不同，保留当前列表顺序和编号，不从名称推测身份。
        if (this@toScheduleItems == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeArticleScheduleItem(
                    id = value.optInt("id"),
                    title = value.optString("title"),
                    slug = value.optString("slug"),
                    status = value.optString("status", "draft"),
                    publishAt = nativeLocalDateTimeInput(value.optString("publishAt")),
                    unpublishAt = nativeLocalDateTimeInput(value.optString("unpublishAt")),
                    error = value.optString("error").takeIf(String::isNotBlank),
                    updatedAt = formatTime(value.optString("updatedAt")),
                ),
            )
        }
    }

    private fun JSONArray?.toVersionSummaries(): List<NativeArticleVersionSummary> = buildList {
        if (this@toVersionSummaries == null) return@buildList
        for (index in 0 until length()) add(getJSONObject(index).toVersionSummary())
    }

    private fun JSONArray?.toAttachments(): List<NativeArticleAttachment> = buildList {
        if (this@toAttachments == null) return@buildList
        for (index in 0 until length()) {
            val value = getJSONObject(index)
            add(NativeArticleAttachment(
                id = value.optInt("id"),
                kind = value.optString("kind", "file"),
                originalName = value.optString("originalName", "附件"),
                mimeType = value.optString("mimeType"),
                sizeBytes = value.optLong("sizeBytes"),
                downloadUrl = value.optString("downloadUrl"),
                thumbnailUrl = value.optString("thumbnailUrl").takeIf(String::isNotBlank),
            ))
        }
    }

    private fun JSONObject.toVersionSummary(): NativeArticleVersionSummary = NativeArticleVersionSummary(
        id = optInt("id"),
        versionNumber = optInt("versionNumber"),
        source = optString("source"),
        changedFields = optJSONArray("changedFields").toStrings(),
        createdAt = formatTime(optString("createdAt")),
    )

    private fun JSONObject.toArticleVersion(): NativeArticleVersion = NativeArticleVersion(
        summary = toVersionSummary(),
        title = optString("title"),
        articleSummary = optString("summary"),
        content = optString("content"),
        contentFormat = optString("contentFormat", "markdown"),
        category = optString("category"),
        tags = optTags("tags"),
        titleColor = optString("titleColor"),
        visibility = optString("visibility", "public"),
        status = optString("status", "draft"),
        roleCodes = optJSONArray("roleCodes").toStrings(),
    )

    /** 从当前文章 JSON 解析编辑模型，计划取嵌套 schedule，UTC 在编辑边界转为设备本地时间。 */
    private fun JSONObject.toDraftArticle(): NativeDraftArticle {
        // 角色以服务端稳定代码为准；没有授权角色时保持空列表，不扩大可见范围。
        val roles = optJSONArray("allowedRoles")
        val roleCodes = buildList {
            if (roles == null) return@buildList
            for (index in 0 until roles.length()) {
                val value = roles.optJSONObject(index) ?: continue
                val nested = value.optJSONObject("role")
                val code = nested?.optString("code").orEmpty().ifBlank { value.optString("code") }
                if (code.isNotBlank()) add(code)
            }
        }
        // 服务端详情只有 schedule 对象，不能从同名顶层字段读取或直接截断 UTC 时间。
        val schedule = optJSONObject("schedule")
        return NativeDraftArticle(
            id = optInt("id"),
            title = optString("title"),
            summary = optString("summary"),
            content = optString("content"),
            contentFormat = optString("contentFormat", "markdown"),
            category = optString("category"),
            tags = optTags("tags"),
            titleColor = optString("titleColor"),
            visibility = optString("visibility", "public"),
            roleCodes = roleCodes,
            status = optString("status", "draft"),
            slug = optString("slug"),
            updatedAt = formatTime(optString("updatedAt")),
            publishAt = nativeLocalDateTimeInput(schedule?.optString("publishAt")),
            unpublishAt = nativeLocalDateTimeInput(schedule?.optString("unpublishAt")),
            scheduleError = schedule?.optString("error")?.takeIf { it.isNotBlank() && it != "null" },
            attachments = optJSONArray("attachments").toAttachments(),
        )
    }

    private fun NativeDraftInput.toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("summary", summary)
        put("content", content)
        put("contentFormat", contentFormat)
        put("category", category)
        put("tags", tags)
        put("titleColor", titleColor)
        put("visibility", visibility)
        put("roleCodes", JSONArray(roleCodes))
    }

    private fun JSONArray?.toComments(): List<NativeArticleComment> = buildList {
        if (this@toComments == null) return@buildList
        for (index in 0 until length()) add(getJSONObject(index).toComment())
    }

    private fun JSONArray?.toArticleAppeals(): List<NativeArticleAppeal> = buildList {
        if (this@toArticleAppeals == null) return@buildList
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(it.toArticleAppeal()) }
        }
    }

    private fun JSONObject.toArticleAppeal(): NativeArticleAppeal {
        val article = optJSONObject("article")
        val author = optJSONObject("author")
        return NativeArticleAppeal(
            id = optInt("id"),
            articleId = article?.optInt("id") ?: optInt("articleId"),
            articleTitle = article?.optString("title").orEmpty().ifBlank { "未命名文章" },
            articleSlug = article?.optString("slug").orEmpty(),
            articleStatus = article?.optString("status").orEmpty(),
            authorName = author?.optString("nickname").orEmpty()
                .ifBlank { author?.optString("username").orEmpty() }
                .ifBlank { "作者" },
            authorUsername = author?.optString("username").orEmpty(),
            reason = optString("reason"),
            status = optString("status", "pending"),
            resolution = optString("resolution").takeIf(String::isNotBlank),
            createdAt = formatTime(optString("createdAt")),
            reviewedAt = optString("reviewedAt").takeIf { it.isNotBlank() && it != "null" }?.let(::formatTime),
        )
    }

    private fun JSONObject.toComment(): NativeArticleComment {
        val author = optJSONObject("author")
        val name = author?.optString("nickname").orEmpty()
            .ifBlank { author?.optString("username").orEmpty() }
            .ifBlank { "站内用户" }
        return NativeArticleComment(
            id = optInt("id"),
            articleId = optInt("articleId"),
            parentId = optIntOrNull("parentId"),
            body = optString("body"),
            status = optString("status", "active"),
            likeCount = optInt("likeCount"),
            liked = optBoolean("liked"),
            reported = optBoolean("reported"),
            quote = optJSONObject("quote")?.let { quote ->
                NativeCommentQuote(
                    id = quote.optInt("id"),
                    body = quote.optString("body"),
                    authorName = quote.optString("authorName").ifBlank { "原评论" },
                    available = quote.optBoolean("available", true),
                )
            },
            authorName = name,
            authorUsername = author?.optString("username").orEmpty(),
            authorInitials = name.takeLast(2),
            createdAt = formatTime(optString("createdAt")),
            attachments = optJSONArray("attachments").toAttachments(),
            pendingReportCount = optInt("pendingReportCount"),
            reportCount = optJSONArray("reports")?.length() ?: 0,
        )
    }

    private fun JSONObject.toRemoteArticle(): RemoteArticle {
        val author = optJSONObject("author")
        val authorName = author?.optString("nickname").orEmpty()
            .ifBlank { author?.optString("username").orEmpty() }
            .ifBlank { "站内作者" }
        val resource = optJSONObject("resource")
        return RemoteArticle(
            id = optInt("id"),
            title = optString("title", "未命名文章"),
            slug = optString("slug"),
            summary = optString("summary"),
            content = optString("content"),
            contentFormat = optString("contentFormat", "markdown"),
            category = optString("category", "未分类"),
            tags = optJSONArray("tags").toStrings(),
            viewCount = optInt("viewCount"),
            likeCount = optInt("likeCount"),
            favoriteCount = optInt("favoriteCount"),
            commentCount = optInt("commentCount"),
            authorName = authorName,
            authorUsername = author?.optString("username").orEmpty(),
            authorId = author?.optInt("id") ?: 0,
            liked = optBoolean("liked"),
            favorited = optBoolean("favorited"),
            readLater = optBoolean("readLater"),
            readingProgress = optInt("readingProgress").takeIf { has("readingProgress") && !isNull("readingProgress") },
            lastReadAt = optString("lastReadAt").takeIf { it.isNotBlank() && it != "null" },
            contentSegments = optJSONArray("contentSegments").toRemoteArticleSegments(),
            resourceEnabled = resource?.optBoolean("enabled") == true,
            resourceBlocks = resource?.optJSONArray("blocks").toRemoteResourceBlocks(),
            images = optJSONArray("images").toStrings(),
            status = optString("status", "published"),
            blockedReason = optString("blockedReason").takeIf(String::isNotBlank),
            isPinned = optBoolean("isPinned"),
            pinOrder = optInt("pinOrder"),
            visibility = optString("visibility", "public"),
            titleColor = optString("titleColor"),
            allowedRoleCodes = optJSONArray("allowedRoles").toRoleCodes(),
            updatedAt = formatTime(optString("updatedAt")),
        )
    }

    private fun JSONObject.toArticleAction(
        liked: Boolean? = null,
        favorited: Boolean? = null,
    ): RemoteArticleAction = RemoteArticleAction(
        liked = optBoolean("liked", liked ?: false),
        favorited = optBoolean("favorited", favorited ?: false),
        likeCount = optInt("likeCount"),
        favoriteCount = optInt("favoriteCount"),
    )

    private fun JSONArray?.toStrings(): List<String> = buildList {
        if (this@toStrings == null) return@buildList
        for (index in 0 until length()) add(optString(index))
    }

    private fun JSONArray?.toRoleCodes(): List<String> = buildList {
        if (this@toRoleCodes == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val nested = value.optJSONObject("role")
            val code = nested?.optString("code").orEmpty().ifBlank { value.optString("code") }
            if (code.isNotBlank()) add(code)
        }
    }

    private fun JSONArray?.toRemoteResourceBlocks(): List<RemoteResourceBlock> = buildList {
        if (this@toRemoteResourceBlocks == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                RemoteResourceBlock(
                    key = value.optString("key"),
                    pointCost = value.optInt("pointCost"),
                    unlocked = value.optBoolean("unlocked"),
                ),
            )
        }
    }

    private fun JSONArray?.toRemoteArticleSegments(): List<RemoteArticleSegment> = buildList {
        if (this@toRemoteArticleSegments == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                RemoteArticleSegment(
                    type = value.optString("type", "markdown"),
                    content = value.optString("content").takeIf { it.isNotBlank() && it != "null" },
                    key = value.optString("key").takeIf { it.isNotBlank() && it != "null" },
                    pointCost = value.optInt("pointCost").takeIf { !value.isNull("pointCost") },
                    unlocked = value.optBoolean("unlocked", true),
                ),
            )
        }
    }

    private fun JSONObject.optTags(name: String): List<String> {
        val value = opt(name)
        return when (value) {
            is JSONArray -> value.toStrings()
            is String -> value.split(",", "，", "、").map(String::trim).filter(String::isNotBlank)
            else -> emptyList()
        }
    }

    private fun formatTime(value: String): String = when {
        value.isBlank() -> ""
        value.length >= 16 -> value.substring(0, 10) + " " + value.substring(11, 16)
        else -> value
    }
}

internal data class RemoteArticleAction(
    val liked: Boolean,
    val favorited: Boolean,
    val likeCount: Int,
    val favoriteCount: Int,
)

internal data class NativeReadingProgress(
    val progress: Int,
    val lastReadAt: String,
)

internal data class NativeCommentAction(
    val liked: Boolean,
    val likeCount: Int,
)

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null
