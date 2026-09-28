package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

internal data class RemoteArticle(
    val id: Int,
    val title: String,
    val slug: String,
    val summary: String,
    val content: String,
    val category: String,
    val tags: List<String>,
    val viewCount: Int,
    val likeCount: Int,
    val favoriteCount: Int,
    val commentCount: Int,
    val authorName: String,
    val authorUsername: String,
)

internal data class RemoteCollection(
    val id: Int,
    val name: String,
    val description: String,
    val articleCount: Int,
    val subscriberCount: Int,
    val ownerName: String,
    val articles: List<RemoteArticle>,
)

internal data class RemoteTopic(
    val id: Int,
    val title: String,
    val description: String,
    val articleCount: Int,
    val subscriberCount: Int,
    val articles: List<RemoteArticle>,
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

internal object PreviewApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun load(): PreviewData {
        val settings = request("/site-settings/public")
        val theme = settings.optJSONObject("defaultTheme") ?: JSONObject()
        val articles = request("/articles?page=1&pageSize=8&sort=latest")
            .optJSONArray("items")
            .toRemoteArticles()
        val topics = request("/discovery/topics?page=1&pageSize=8")
            .optJSONArray("items")
            .toRemoteTopics()
        val collections = request("/discovery/collections?page=1&pageSize=8")
            .optJSONArray("items")
            .toRemoteCollections()
        return PreviewData(
            articles = articles,
            topics = topics,
            collections = collections,
            appearance = theme.toPreviewAppearance(),
            backgroundUrl = settings.optString("defaultBackgroundUrl").takeIf { it.isNotBlank() }?.let(::absoluteUrl),
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

    private fun request(path: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("请求接口失败（HTTP $status）")
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun absoluteUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return if (path.startsWith("/api/")) {
            baseUrl.substringBeforeLast("/api") + path
        } else {
            baseUrl + "/" + path.trimStart('/')
        }
    }

    private fun JSONObject.toPreviewAppearance(): PreviewAppearance {
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
        if (themeId != "custom") return defaults
        return defaults.copy(
            accent = optString("customAccent", defaults.accent),
            foreground = optString("customForeground", defaults.foreground),
            muted = optString("customMuted", defaults.muted),
            surface = optString("customSurface", defaults.surface),
            cardAlpha = optInt("cardAlpha", defaults.cardAlpha),
            glassBlur = optInt("glassBlur", defaults.glassBlur),
            glassTint = optString("glassTint", defaults.glassTint),
            glassTintAlpha = optInt("glassTintAlpha", defaults.glassTintAlpha),
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
                ),
            )
        }
    }

    private fun JSONObject.toRemoteArticle(): RemoteArticle {
        val author = optJSONObject("author")
        return RemoteArticle(
            id = optInt("id"),
            title = optString("title", "未命名文章"),
            slug = optString("slug"),
            summary = optString("summary"),
            content = optString("content"),
            category = optString("category", "未分类"),
            tags = optJSONArray("tags").toStringList(),
            viewCount = optInt("viewCount"),
            likeCount = optInt("likeCount"),
            favoriteCount = optInt("favoriteCount"),
            commentCount = optInt("commentCount"),
            authorName = author?.displayName() ?: "站内作者",
            authorUsername = author?.optString("username").orEmpty(),
        )
    }

    private fun JSONObject.displayName(): String =
        optString("nickname").ifBlank { optString("username").ifBlank { "站内作者" } }

    private fun JSONArray?.toStringList(): List<String> = buildList {
        if (this@toStringList == null) return@buildList
        for (index in 0 until length()) add(optString(index))
    }
}
