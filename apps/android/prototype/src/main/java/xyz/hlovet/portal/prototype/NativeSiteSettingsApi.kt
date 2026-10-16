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

internal object NativeSiteSettingsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

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
