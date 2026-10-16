package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeAdminSiteTheme(
    val themeId: String,
    val accent: String,
    val surface: String,
    val foreground: String,
    val muted: String,
    val cardAlpha: Int,
    val glassBlur: Int,
    val glassTint: String,
    val glassTintAlpha: Int,
)

internal data class NativeAdminNotificationSettings(
    val notifyArticleLiked: Boolean,
    val notifyArticleFavorited: Boolean,
    val notifyArticleCommented: Boolean,
    val notifyCommentReplied: Boolean,
    val notifyAuthorSubscribed: Boolean,
    val notifySubscriptionPublished: Boolean,
    val notifyFriendRequest: Boolean,
    val notifyCommentReport: Boolean,
    val notifySystem: Boolean,
    val articleLiked: String,
    val articleFavorited: String,
    val articleCommented: String,
    val commentReplied: String,
    val authorSubscribed: String,
    val subscriptionPublished: String,
    val friendRequest: String,
    val commentReportHandled: String,
    val commentAuthorModerated: String,
    val articleLikedEn: String,
    val articleFavoritedEn: String,
    val articleCommentedEn: String,
    val commentRepliedEn: String,
    val authorSubscribedEn: String,
    val subscriptionPublishedEn: String,
    val friendRequestEn: String,
    val commentReportHandledEn: String,
    val commentAuthorModeratedEn: String,
)

internal data class NativeAdminTaxonomy(
    val id: Int,
    val kind: String,
    val name: String,
    val color: String,
    val sortOrder: Int,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

internal data class NativeAdminSiteAsset(
    val id: Int,
    val kind: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val url: String,
    val uploadedBy: String,
    val createdAt: String,
    val updatedAt: String,
)

internal data class NativeAdminBackground(
    val id: Int,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isActive: Boolean,
    val url: String,
    val uploadedBy: String,
    val createdAt: String,
    val updatedAt: String,
)

internal data class NativeAdminSiteSettings(
    val siteName: String,
    val browserTitle: String,
    val logoPath: String,
    val pwaIconPath: String,
    val defaultBackgroundUrl: String,
    val defaultTheme: NativeAdminSiteTheme,
    val registrationOpen: Boolean,
    val defaultRoleCode: String,
    val installPageEnabled: Boolean,
    val apkHistoryEnabled: Boolean,
    val apkAutoCleanupEnabled: Boolean,
    val apkRetentionCount: Int,
    val defaultArticleVisibility: String,
    val articleImageMaxSizeMb: Int,
    val commentsEnabled: Boolean,
    val reportsEnabled: Boolean,
    val notifications: NativeAdminNotificationSettings,
    val categories: List<NativeAdminTaxonomy>,
    val tags: List<NativeAdminTaxonomy>,
    val updatedAt: String,
)

internal const val NATIVE_BACKGROUND_MAX_BYTES = 30L * 1024L * 1024L
internal const val NATIVE_BACKGROUND_MAX_FILES = 5

internal fun nativeBackgroundUploadAllowed(sizeBytes: Long): Boolean =
    sizeBytes in 1L..NATIVE_BACKGROUND_MAX_BYTES

internal fun requireNativeBackgroundUploadCount(count: Int) {
    require(count in 1..NATIVE_BACKGROUND_MAX_FILES) {
        nativePhrase("请选择 1-$NATIVE_BACKGROUND_MAX_FILES 张背景图。", "Choose 1-$NATIVE_BACKGROUND_MAX_FILES background images.")
    }
}

internal fun <T> readNativeBackgroundUploads(
    selected: List<T>,
    readFile: (T, Long) -> NativeUploadFile,
): List<NativeUploadFile> {
    requireNativeBackgroundUploadCount(selected.size)
    return selected.map { readFile(it, NATIVE_BACKGROUND_MAX_BYTES) }
}

internal object NativeAdminSettingsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"
    private const val MAX_SITE_ASSET_BYTES = 5L * 1024L * 1024L
    // Keep the native client aligned with the server and PC settings page.
    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun get(accessToken: String): NativeAdminSiteSettings = requestObject("/site-settings", accessToken).toSettings()

    fun update(accessToken: String, settings: NativeAdminSiteSettings): NativeAdminSiteSettings =
        requestObject("/site-settings", accessToken, "PATCH", settings.toUpdateJson()).toSettings()

    fun reset(accessToken: String): NativeAdminSiteSettings = requestObject("/site-settings/reset", accessToken, "POST", JSONObject()).toSettings()

    fun listAssets(accessToken: String): List<NativeAdminSiteAsset> = requestJson("/site-settings/assets", accessToken).toArray().toAssets()

    fun uploadAsset(accessToken: String, kind: String, file: NativeUploadFile): NativeAdminSiteAsset {
        if (file.bytes.size.toLong() > MAX_SITE_ASSET_BYTES) throw IllegalStateException("站点资源不能超过 5 MB。")
        return uploadMultipart(
            path = "/site-settings/assets",
            accessToken = accessToken,
            fields = mapOf("kind" to kind),
            files = listOf(UploadPart("file", file)),
        ).toObject().toAsset()
    }

    fun deleteAsset(accessToken: String, id: Int) {
        requestJson("/site-settings/assets/$id", accessToken, "DELETE")
    }

    fun listBackgrounds(accessToken: String): List<NativeAdminBackground> = requestJson("/backgrounds", accessToken).toArray().toBackgrounds()

    fun uploadBackgrounds(accessToken: String, files: List<NativeUploadFile>): List<NativeAdminBackground> {
        requireNativeBackgroundUploadCount(files.size)
        if (files.any { !nativeBackgroundUploadAllowed(it.bytes.size.toLong()) }) throw IllegalStateException("单张背景图不能超过 30 MB。")
        return uploadMultipart(
            path = "/backgrounds",
            accessToken = accessToken,
            fields = emptyMap(),
            files = files.map { UploadPart("files", it) },
        ).toArray().toBackgrounds()
    }

    fun activateBackground(accessToken: String, id: Int): NativeAdminBackground = requestObject("/backgrounds/$id/activate", accessToken, "PATCH", JSONObject()).toBackground()

    fun deleteBackground(accessToken: String, id: Int) {
        requestJson("/backgrounds/$id", accessToken, "DELETE")
    }

    fun clearActiveBackground(accessToken: String) {
        requestJson("/backgrounds/active/clear", accessToken, "PATCH", JSONObject())
    }

    fun createTaxonomy(accessToken: String, kind: String, name: String, color: String, sortOrder: Int, enabled: Boolean): NativeAdminTaxonomy =
        requestObject("/site-settings/taxonomies", accessToken, "POST", taxonomyJson(kind, name, color, sortOrder, enabled)).toTaxonomy()

    fun updateTaxonomy(accessToken: String, item: NativeAdminTaxonomy): NativeAdminTaxonomy =
        requestObject("/site-settings/taxonomies/${item.id}", accessToken, "PATCH", taxonomyJson(item.kind, item.name, item.color, item.sortOrder, item.enabled)).toTaxonomy()

    fun deleteTaxonomy(accessToken: String, id: Int) {
        requestJson("/site-settings/taxonomies/$id", accessToken, "DELETE")
    }

    fun resolveUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val origin = baseUrl.substringBeforeLast("/api").trimEnd('/')
        return if (path.startsWith("/api/")) origin + path else baseUrl + "/" + path.trimStart('/')
    }

    private fun taxonomyJson(kind: String, name: String, color: String, sortOrder: Int, enabled: Boolean) = JSONObject().apply {
        put("kind", kind)
        put("name", name)
        put("color", color)
        put("sortOrder", sortOrder)
        put("enabled", enabled)
    }

    private fun NativeAdminSiteSettings.toUpdateJson() = JSONObject().apply {
        put("siteName", siteName)
        put("browserTitle", browserTitle)
        put("logoPath", logoPath)
        put("pwaIconPath", pwaIconPath)
        put("defaultBackgroundUrl", defaultBackgroundUrl)
        put("defaultThemeId", defaultTheme.themeId)
        put("defaultAccent", defaultTheme.accent)
        put("defaultSurface", defaultTheme.surface)
        put("defaultForeground", defaultTheme.foreground)
        put("defaultMuted", defaultTheme.muted)
        put("defaultCardAlpha", defaultTheme.cardAlpha)
        put("defaultGlassBlur", defaultTheme.glassBlur)
        put("defaultGlassTint", defaultTheme.glassTint)
        put("defaultGlassTintAlpha", defaultTheme.glassTintAlpha)
        put("registrationOpen", registrationOpen)
        put("defaultRoleCode", defaultRoleCode)
        put("installPageEnabled", installPageEnabled)
        put("apkHistoryEnabled", apkHistoryEnabled)
        put("apkAutoCleanupEnabled", apkAutoCleanupEnabled)
        put("apkRetentionCount", apkRetentionCount)
        put("defaultArticleVisibility", defaultArticleVisibility)
        put("articleImageMaxSizeMb", articleImageMaxSizeMb)
        put("commentsEnabled", commentsEnabled)
        put("reportsEnabled", reportsEnabled)
        put("notifyArticleLiked", notifications.notifyArticleLiked)
        put("notifyArticleFavorited", notifications.notifyArticleFavorited)
        put("notifyArticleCommented", notifications.notifyArticleCommented)
        put("notifyCommentReplied", notifications.notifyCommentReplied)
        put("notifyAuthorSubscribed", notifications.notifyAuthorSubscribed)
        put("notifySubscriptionPublished", notifications.notifySubscriptionPublished)
        put("notifyFriendRequest", notifications.notifyFriendRequest)
        put("notifyCommentReport", notifications.notifyCommentReport)
        put("notifySystem", notifications.notifySystem)
        put("templateArticleLiked", notifications.articleLiked)
        put("templateArticleFavorited", notifications.articleFavorited)
        put("templateArticleCommented", notifications.articleCommented)
        put("templateCommentReplied", notifications.commentReplied)
        put("templateAuthorSubscribed", notifications.authorSubscribed)
        put("templateSubscriptionPublished", notifications.subscriptionPublished)
        put("templateFriendRequest", notifications.friendRequest)
        put("templateCommentReportHandled", notifications.commentReportHandled)
        put("templateCommentAuthorModerated", notifications.commentAuthorModerated)
        put("templateArticleLikedEn", notifications.articleLikedEn)
        put("templateArticleFavoritedEn", notifications.articleFavoritedEn)
        put("templateArticleCommentedEn", notifications.articleCommentedEn)
        put("templateCommentRepliedEn", notifications.commentRepliedEn)
        put("templateAuthorSubscribedEn", notifications.authorSubscribedEn)
        put("templateSubscriptionPublishedEn", notifications.subscriptionPublishedEn)
        put("templateFriendRequestEn", notifications.friendRequestEn)
        put("templateCommentReportHandledEn", notifications.commentReportHandledEn)
        put("templateCommentAuthorModeratedEn", notifications.commentAuthorModeratedEn)
    }

    private fun requestObject(path: String, accessToken: String, method: String = "GET", body: JSONObject? = null): JSONObject = requestJson(path, accessToken, method, body).toObject()

    private fun requestJson(path: String, accessToken: String, method: String = "GET", body: JSONObject? = null): Any {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            parseResponse(connection, "站点设置请求失败")
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadMultipart(path: String, accessToken: String, fields: Map<String, String>, files: List<UploadPart>): Any {
        val boundary = "----HlovetSiteSettings${System.currentTimeMillis()}"
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                fields.forEach { (name, value) ->
                    output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                files.forEach { part ->
                    val safeName = part.file.name.replace("\"", "").replace("\r", "").replace("\n", "")
                    output.write("--$boundary\r\nContent-Disposition: form-data; name=\"${part.name}\"; filename=\"$safeName\"\r\nContent-Type: ${part.file.mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n".toByteArray())
                    output.write(part.file.bytes)
                    output.write("\r\n".toByteArray())
                }
                output.write("--$boundary--\r\n".toByteArray())
            }
            parseResponse(connection, "站点资源上传失败")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(connection: HttpURLConnection, fallback: String): Any {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            throw IllegalStateException(message.ifBlank { "$fallback（HTTP $status）。" })
        }
        return when {
            body.isBlank() -> JSONObject()
            body.startsWith("[") -> JSONArray(body)
            else -> JSONObject(body)
        }
    }

    private data class UploadPart(val name: String, val file: NativeUploadFile)

    private fun Any.toObject(): JSONObject = this as? JSONObject ?: JSONObject()
    private fun Any.toArray(): JSONArray = this as? JSONArray ?: JSONObject().optJSONArray("items") ?: JSONArray()

    private fun JSONObject.toSettings(): NativeAdminSiteSettings {
        val theme = optJSONObject("defaultTheme") ?: JSONObject()
        val notifications = optJSONObject("notifications") ?: JSONObject()
        val taxonomies = optJSONObject("taxonomies") ?: JSONObject()
        return NativeAdminSiteSettings(
            siteName = optString("siteName"),
            browserTitle = optString("browserTitle"),
            logoPath = optString("logoPath"),
            pwaIconPath = optString("pwaIconPath"),
            defaultBackgroundUrl = optString("defaultBackgroundUrl"),
            defaultTheme = NativeAdminSiteTheme(
                themeId = theme.optString("themeId", "cloud-blue"),
                accent = theme.optString("customAccent", "#0284C7"),
                surface = theme.optString("customSurface", "#FFFFFF"),
                foreground = theme.optString("customForeground", "#1F2937"),
                muted = theme.optString("customMuted", "#52616F"),
                cardAlpha = theme.optInt("cardAlpha", 50),
                glassBlur = theme.optInt("glassBlur", 18),
                glassTint = theme.optString("glassTint", "#FFF3F6"),
                glassTintAlpha = theme.optInt("glassTintAlpha", 0),
            ),
            registrationOpen = optBoolean("registrationOpen"),
            defaultRoleCode = optString("defaultRoleCode"),
            installPageEnabled = optBoolean("installPageEnabled"),
            apkHistoryEnabled = optBoolean("apkHistoryEnabled"),
            apkAutoCleanupEnabled = optBoolean("apkAutoCleanupEnabled"),
            apkRetentionCount = optInt("apkRetentionCount", 3),
            defaultArticleVisibility = optString("defaultArticleVisibility", "public"),
            articleImageMaxSizeMb = optInt("articleImageMaxSizeMb", 10),
            commentsEnabled = optBoolean("commentsEnabled", true),
            reportsEnabled = optBoolean("reportsEnabled", true),
            notifications = notifications.toNotifications(),
            categories = taxonomies.optJSONArray("categories").toTaxonomies(),
            tags = taxonomies.optJSONArray("tags").toTaxonomies(),
            updatedAt = optString("updatedAt"),
        )
    }

    private fun JSONObject.toNotifications() = NativeAdminNotificationSettings(
        notifyArticleLiked = optBoolean("notifyArticleLiked"),
        notifyArticleFavorited = optBoolean("notifyArticleFavorited"),
        notifyArticleCommented = optBoolean("notifyArticleCommented"),
        notifyCommentReplied = optBoolean("notifyCommentReplied"),
        notifyAuthorSubscribed = optBoolean("notifyAuthorSubscribed"),
        notifySubscriptionPublished = optBoolean("notifySubscriptionPublished"),
        notifyFriendRequest = optBoolean("notifyFriendRequest"),
        notifyCommentReport = optBoolean("notifyCommentReport"),
        notifySystem = optBoolean("notifySystem", true),
        articleLiked = optJSONObject("templates")?.optString("articleLiked").orEmpty(),
        articleFavorited = optJSONObject("templates")?.optString("articleFavorited").orEmpty(),
        articleCommented = optJSONObject("templates")?.optString("articleCommented").orEmpty(),
        commentReplied = optJSONObject("templates")?.optString("commentReplied").orEmpty(),
        authorSubscribed = optJSONObject("templates")?.optString("authorSubscribed").orEmpty(),
        subscriptionPublished = optJSONObject("templates")?.optString("subscriptionPublished").orEmpty(),
        friendRequest = optJSONObject("templates")?.optString("friendRequest").orEmpty(),
        commentReportHandled = optJSONObject("templates")?.optString("commentReportHandled").orEmpty(),
        commentAuthorModerated = optJSONObject("templates")?.optString("commentAuthorModerated").orEmpty(),
        articleLikedEn = optJSONObject("templatesEn")?.optString("articleLiked").orEmpty(),
        articleFavoritedEn = optJSONObject("templatesEn")?.optString("articleFavorited").orEmpty(),
        articleCommentedEn = optJSONObject("templatesEn")?.optString("articleCommented").orEmpty(),
        commentRepliedEn = optJSONObject("templatesEn")?.optString("commentReplied").orEmpty(),
        authorSubscribedEn = optJSONObject("templatesEn")?.optString("authorSubscribed").orEmpty(),
        subscriptionPublishedEn = optJSONObject("templatesEn")?.optString("subscriptionPublished").orEmpty(),
        friendRequestEn = optJSONObject("templatesEn")?.optString("friendRequest").orEmpty(),
        commentReportHandledEn = optJSONObject("templatesEn")?.optString("commentReportHandled").orEmpty(),
        commentAuthorModeratedEn = optJSONObject("templatesEn")?.optString("commentAuthorModerated").orEmpty(),
    )

    private fun JSONArray?.toTaxonomies(): List<NativeAdminTaxonomy> = buildList {
        val array = this@toTaxonomies ?: return@buildList
        for (index in 0 until array.length()) array.optJSONObject(index)?.let { add(it.toTaxonomy()) }
    }

    private fun JSONObject.toTaxonomy() = NativeAdminTaxonomy(
        id = optInt("id"), kind = optString("kind"), name = optString("name"), color = optString("color", "#0284C7"),
        sortOrder = optInt("sortOrder"), enabled = optBoolean("enabled", true), createdAt = optString("createdAt"), updatedAt = optString("updatedAt"),
    )

    private fun JSONArray.toAssets(): List<NativeAdminSiteAsset> = buildList { for (index in 0 until length()) optJSONObject(index)?.let { add(it.toAsset()) } }
    private fun JSONObject.toAsset() = NativeAdminSiteAsset(
        id = optInt("id"), kind = optString("kind"), originalName = optString("originalName"), mimeType = optString("mimeType"), sizeBytes = optLong("sizeBytes"), url = optString("url"), uploadedBy = optJSONObject("uploadedBy")?.optString("username").orEmpty(), createdAt = optString("createdAt"), updatedAt = optString("updatedAt"),
    )

    private fun JSONArray.toBackgrounds(): List<NativeAdminBackground> = buildList { for (index in 0 until length()) optJSONObject(index)?.let { add(it.toBackground()) } }
    private fun JSONObject.toBackground() = NativeAdminBackground(
        id = optInt("id"), originalName = optString("originalName"), mimeType = optString("mimeType"), sizeBytes = optLong("sizeBytes"), isActive = optBoolean("isActive"), url = optString("url"), uploadedBy = optJSONObject("uploadedBy")?.optString("username").orEmpty(), createdAt = optString("createdAt"), updatedAt = optString("updatedAt"),
    )
}
