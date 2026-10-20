package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 使用 client 请求 url 的 JSON；accessToken 为空时不发送账号凭据。
 * 请求构造及调度在后台执行，返回解析结果；取消协程同时取消实际 HTTP 调用。
 */
internal suspend fun nativePreviewJsonRequest(
    client: OkHttpClient,
    url: String,
    accessToken: String? = null,
): JSONObject = withContext(Dispatchers.IO) {
    // newCall/enqueue 也会触发客户端内部初始化，不能只把响应解析移到后台。
    suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (!accessToken.isNullOrBlank()) header("Authorization", "Bearer $accessToken")
        }.build()
        val call = client.newCall(request)
        // 页面退出或刷新超时后停止网络调用，不继续消耗连接。
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            /** 将 call 的网络错误 e 传回等待协程，不发布成功结果。 */
            override fun onFailure(call: Call, e: IOException) {
                // 取消导致的失败仍由协程取消状态处理。
                continuation.resumeWith(Result.failure(e))
            }

            /** 关闭 call 的 response 并解析 JSON，HTTP 失败只暴露状态码。 */
            override fun onResponse(call: Call, response: Response) {
                // 响应读取在 OkHttp 回调线程执行，所有结果均关闭响应体。
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        JSONObject(it.body?.string().orEmpty())
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }
}

internal data class RemoteArticle(
    val id: Int,
    val title: String,
    val slug: String,
    val summary: String,
    val content: String,
    val contentFormat: String = "markdown",
    val category: String,
    val tags: List<String>,
    val viewCount: Int,
    val likeCount: Int,
    val favoriteCount: Int,
    val commentCount: Int,
    val authorName: String,
    val authorUsername: String,
    val authorId: Int = 0,
    val liked: Boolean = false,
    val favorited: Boolean = false,
    val readLater: Boolean = false,
    val readingProgress: Int? = null,
    val lastReadAt: String? = null,
    val contentSegments: List<RemoteArticleSegment> = emptyList(),
    val resourceEnabled: Boolean = false,
    val resourceBlocks: List<RemoteResourceBlock> = emptyList(),
    val images: List<String> = emptyList(),
    val status: String = "published",
    val blockedReason: String? = null,
    val isPinned: Boolean = false,
    val pinOrder: Int = 0,
    val visibility: String = "public",
    val titleColor: String = "",
    val allowedRoleCodes: List<String> = emptyList(),
    val updatedAt: String = "",
)

internal data class RemoteArticleSegment(
    val type: String,
    val content: String? = null,
    val key: String? = null,
    val pointCost: Int? = null,
    val unlocked: Boolean = true,
)

internal data class RemoteResourceBlock(
    val key: String,
    val pointCost: Int,
    val unlocked: Boolean,
)

internal data class RemoteCollection(
    val id: Int,
    val name: String,
    val description: String,
    val articleCount: Int,
    val subscriberCount: Int,
    val ownerName: String,
    val articles: List<RemoteArticle>,
    val coverPath: String? = null,
    val visibility: String = "public",
    val subscribed: Boolean = false,
    val updatedAt: String = "",
)

internal data class RemoteTopic(
    val id: Int,
    val title: String,
    val description: String,
    val articleCount: Int,
    val subscriberCount: Int,
    val articles: List<RemoteArticle>,
    val slug: String = "",
    val coverPath: String? = null,
    val visibility: String = "public",
    val subscribed: Boolean = false,
    val updatedAt: String = "",
    val status: String = "active",
    val sortOrder: Int = 0,
    val roleCodes: List<String> = emptyList(),
)

internal data class PreviewData(
    val articles: List<RemoteArticle>,
    val topics: List<RemoteTopic>,
    val collections: List<RemoteCollection>,
    val appearance: PreviewAppearance,
    val backgroundUrl: String?,
)

internal data class PreviewAppearance(
    val themeId: String,
    val accent: String,
    val foreground: String,
    val muted: String,
    val surface: String,
    val cardAlpha: Int,
    val glassBlur: Int,
    val glassTint: String,
    val glassTintAlpha: Int,
)

/** 首页公开/可见内容及外观读取；地址和外观工具不触发网络客户端初始化。 */
internal object PreviewApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"
    // 仅真正请求时创建客户端，避免界面调用地址工具就初始化整个 OkHttp。
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    suspend fun load(accessToken: String? = null): PreviewData {
        val settings = request("/site-settings/public", accessToken)
        val theme = settings.optJSONObject("defaultTheme") ?: JSONObject()
        // The active uploaded background overrides the configured built-in
        // background on the web client as well. Keep this lookup optional so
        // an older server can still serve the configured default.
        val activeBackground = try {
            request("/backgrounds/active", accessToken)
                .optJSONObject("background")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val articlePath = if (accessToken.isNullOrBlank()) "/articles?page=1&pageSize=8&sort=recommended" else "/articles/visible?page=1&pageSize=8&sort=recommended"
        val topicPath = if (accessToken.isNullOrBlank()) "/discovery/topics?page=1&pageSize=8" else "/discovery/topics/visible?page=1&pageSize=8"
        val collectionPath = if (accessToken.isNullOrBlank()) "/discovery/collections?page=1&pageSize=8" else "/discovery/collections/visible?page=1&pageSize=8"
        val articles = request(articlePath, accessToken)
            .optJSONArray("items")
            .toRemoteArticles()
        val topics = request(topicPath, accessToken)
            .optJSONArray("items")
            .toRemoteTopics()
        val collections = request(collectionPath, accessToken)
            .optJSONArray("items")
            .toRemoteCollections()
        return PreviewData(
            articles = articles,
            topics = topics,
            collections = collections,
            appearance = theme.toPreviewAppearance(),
            backgroundUrl = activeBackground?.optString("url")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let(::apiAbsoluteUrl)
                ?: settings.optString("defaultBackgroundUrl")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let(::siteAbsoluteUrl),
        )
    }

    fun loadBitmap(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "image/*")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            BufferedInputStream(connection.inputStream).use(BitmapFactory::decodeStream)
        } finally {
            connection.disconnect()
        }
    }

    internal fun appearanceFromJson(theme: JSONObject): PreviewAppearance = theme.toPreviewAppearance()

    /** 请求相对 API 的 path；accessToken 用于可见内容，返回 JSON 并在后台首次创建客户端。 */
    private suspend fun request(path: String, accessToken: String? = null): JSONObject = withContext(Dispatchers.IO) {
        // lazy 求值发生在后台，不能在给挂起函数传参时提前回到主线程初始化。
        nativePreviewJsonRequest(client, baseUrl + path, accessToken)
    }

    internal fun apiAbsoluteUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return if (path.startsWith("/api/")) {
            baseUrl.substringBeforeLast("/api") + path
        } else {
            baseUrl + "/" + path.trimStart('/')
        }
    }

    internal fun siteAbsoluteUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val origin = BuildConfig.PORTAL_BASE_URL.trimEnd('/').ifBlank {
            baseUrl.substringBeforeLast("/api")
        }
        return origin + "/" + path.trimStart('/')
    }

    /** 将服务端主题 JSON 解析为原生外观；自定义方案保留身份，未知主题回退默认色板。 */
    private fun JSONObject.toPreviewAppearance(): PreviewAppearance {
        // 预置方案只读取其固定色板，自定义方案暂借默认值补齐未填写的颜色。
        val themeId = optString("themeId", "cloud-blue")
        val defaults = when (themeId) {
            "sakura-mist" -> PreviewAppearance(
                themeId = themeId,
                accent = "#db2777",
                foreground = "#2b2530",
                muted = "#665867",
                surface = "#ffffff",
                cardAlpha = 52,
                glassBlur = 22,
                glassTint = "#fff3f6",
                glassTintAlpha = 72,
            )
            "night-purple" -> PreviewAppearance(
                themeId = themeId,
                accent = "#6d5bd0",
                foreground = "#252534",
                muted = "#5d5d70",
                surface = "#ffffff",
                cardAlpha = 48,
                glassBlur = 18,
                glassTint = "#e8e5f2",
                glassTintAlpha = 70,
            )
            else -> PreviewAppearance(
                themeId = "cloud-blue",
                accent = "#0284c7",
                foreground = "#1f2937",
                muted = "#52616f",
                surface = "#ffffff",
                cardAlpha = 50,
                glassBlur = 18,
                glassTint = "#fff3f6",
                glassTintAlpha = 0,
            )
        }
        // 毛玻璃参数由所有方案共用，不随主题色板回退而丢失。
        val configuredGlass = defaults.copy(
            cardAlpha = optInt("cardAlpha", defaults.cardAlpha),
            glassBlur = optInt("glassBlur", defaults.glassBlur),
            glassTint = optString("glassTint", defaults.glassTint),
            glassTintAlpha = optInt("glassTintAlpha", defaults.glassTintAlpha),
        )
        if (themeId != "custom") return configuredGlass
        // 保留 custom 身份，避免重新登录或保存外观时把自定义方案写回预置主题。
        return configuredGlass.copy(
            themeId = themeId,
            accent = optString("customAccent", defaults.accent),
            foreground = optString("customForeground", defaults.foreground),
            muted = optString("customMuted", defaults.muted),
            surface = optString("customSurface", defaults.surface),
        )
    }

    private fun JSONArray?.toRemoteArticles(): List<RemoteArticle> = buildList {
        if (this@toRemoteArticles == null) return@buildList
        for (index in 0 until length()) add(getJSONObject(index).toRemoteArticle())
    }

    private fun JSONArray?.toRemoteTopics(): List<RemoteTopic> = buildList {
        if (this@toRemoteTopics == null) return@buildList
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            add(
                RemoteTopic(
                    id = item.optInt("id"),
                    title = item.optString("title", "未命名专题"),
                    description = item.optString("description"),
                    articleCount = item.optInt("articleCount", item.optJSONArray("articles")?.length() ?: 0),
                    subscriberCount = item.optInt("subscriberCount"),
                    articles = item.optJSONArray("articles").toRemoteArticles(),
                    slug = item.optString("slug"),
                    coverPath = item.optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
                    visibility = item.optString("visibility", "public"),
                    subscribed = item.optBoolean("subscribed"),
                    updatedAt = item.optString("updatedAt"),
                    status = item.optString("status", "active"),
                    sortOrder = item.optInt("sortOrder"),
                    roleCodes = item.optJSONArray("roleCodes").toStringList(),
                ),
            )
        }
    }

    private fun JSONArray?.toRemoteCollections(): List<RemoteCollection> = buildList {
        if (this@toRemoteCollections == null) return@buildList
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            val owner = item.optJSONObject("owner")
            add(
                RemoteCollection(
                    id = item.optInt("id"),
                    name = item.optString("name", "未命名合集"),
                    description = item.optString("description"),
                    articleCount = item.optInt("articleCount", item.optJSONArray("articles")?.length() ?: 0),
                    subscriberCount = item.optInt("subscriberCount"),
                    ownerName = owner?.displayName() ?: "站内作者",
                    articles = item.optJSONArray("articles").toRemoteArticles(),
                    coverPath = item.optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
                    visibility = item.optString("visibility", "public"),
                    subscribed = item.optBoolean("subscribed"),
                    updatedAt = item.optString("updatedAt"),
                ),
            )
        }
    }

    private fun JSONObject.toRemoteArticle(): RemoteArticle {
        val author = optJSONObject("author")
        val resource = optJSONObject("resource")
        return RemoteArticle(
            id = optInt("id"),
            title = optString("title", "未命名文章"),
            slug = optString("slug"),
            summary = optString("summary"),
            content = optString("content"),
            contentFormat = optString("contentFormat", "markdown"),
            category = optString("category", "未分类"),
            tags = optJSONArray("tags").toStringList(),
            viewCount = optInt("viewCount"),
            likeCount = optInt("likeCount"),
            favoriteCount = optInt("favoriteCount"),
            commentCount = optInt("commentCount"),
            authorName = author?.displayName() ?: "站内作者",
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
            images = optJSONArray("images").toStringList(),
            status = optString("status", "published"),
            blockedReason = optString("blockedReason").takeIf(String::isNotBlank),
            isPinned = optBoolean("isPinned"),
            titleColor = optString("titleColor"),
            updatedAt = optString("updatedAt"),
        )
    }

    private fun JSONObject.displayName(): String =
        optString("nickname").ifBlank { optString("username").ifBlank { "站内作者" } }

    private fun JSONArray?.toStringList(): List<String> = buildList {
        if (this@toStringList == null) return@buildList
        for (index in 0 until length()) add(optString(index))
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
}
