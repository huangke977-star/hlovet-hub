package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeArticleTaxonomy(
    val name: String,
    val color: String,
)

internal data class NativeWriterSettings(
    val categories: List<NativeArticleTaxonomy>,
    val tags: List<NativeArticleTaxonomy>,
    val defaultVisibility: String,
    val imageMaxSizeMb: Int,
)

/** 描述文章详情页可用的评论与举报能力，读取失败时由调用方按开启处理。 */
internal data class NativeInteractionSettings(
    val commentsEnabled: Boolean,
    val reportsEnabled: Boolean,
)

/** 读取公开站点配置，为原生详情页和写作页提供不含管理权限的展示策略。 */
internal object NativeSiteSettingsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    /** 读取文章详情页的互动开关；返回接口未提供字段时保持默认开启。 */
    fun loadInteractionSettings(): NativeInteractionSettings {
        // 互动开关与写作设置共用公开接口，但单独解析以避免详情页依赖写作配置结构。
        val connection = (URL("$baseUrl/site-settings/public").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            // 非成功响应交给调用方降级为开启，正文不因可选策略接口失败而中断。
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("文章互动设置加载失败（HTTP $status）")
            }
            val response = JSONObject(body)
            NativeInteractionSettings(
                commentsEnabled = response.optBoolean("commentsEnabled", true),
                reportsEnabled = response.optBoolean("reportsEnabled", true),
            )
        } finally {
            // 公开设置请求使用完毕立即释放连接，避免详情页重复进出时积累短连接。
            connection.disconnect()
        }
    }

    /** 读取写作页需要的分类、标签和默认编辑配置。 */
    fun loadWriterSettings(): NativeWriterSettings {
        val connection = (URL("$baseUrl/site-settings/public").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: "文章配置加载失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            val response = JSONObject(body)
            val taxonomies = response.optJSONObject("taxonomies") ?: JSONObject()
            NativeWriterSettings(
                categories = taxonomies.optJSONArray("categories").toTaxonomies(),
                tags = taxonomies.optJSONArray("tags").toTaxonomies(),
                defaultVisibility = response.optString("defaultArticleVisibility", "public"),
                imageMaxSizeMb = response.optInt("articleImageMaxSizeMb", 10).coerceAtLeast(1),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.toTaxonomies(): List<NativeArticleTaxonomy> = buildList {
        if (this@toTaxonomies == null) return@buildList
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            if (!item.optBoolean("enabled", true)) continue
            val name = item.optString("name").trim()
            if (name.isNotBlank()) add(NativeArticleTaxonomy(name, item.optString("color")))
        }
    }
}
