package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal data class NativeDeletedUserSummary(
    val id: Int,
    val originalUsername: String,
    val originalNickname: String,
    val originalEmail: String,
    val deletedAt: String?,
    val articleCount: Int,
    val commentCount: Int,
)

internal data class NativeDeletedUserPage(
    val items: List<NativeDeletedUserSummary>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal data class NativeDeletedUserArticle(
    val id: Int,
    val title: String,
    val status: String,
    val createdAt: String,
    val publishedAt: String?,
)

internal data class NativeDeletedUserComment(
    val id: Int,
    val articleTitle: String,
    val body: String,
    val status: String,
    val createdAt: String,
)

internal data class NativeDeletedUserContent(
    val id: Int,
    val originalUsername: String,
    val originalNickname: String,
    val originalEmail: String,
    val deletedAt: String?,
    val articles: List<NativeDeletedUserArticle>,
    val comments: List<NativeDeletedUserComment>,
)

internal object NativeAdminDeletedUsersApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun list(accessToken: String, page: Int = 1, pageSize: Int = 20, search: String = ""): NativeDeletedUserPage {
        val encoded = URLEncoder.encode(search.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        val value = request("/account-privacy/admin/deleted-users?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}&search=$encoded", accessToken)
        return NativeDeletedUserPage(
            items = value.optJSONArray("items").toSummaries(),
            total = value.optInt("total"),
            page = value.optInt("page", page),
            pageSize = value.optInt("pageSize", pageSize),
            totalPages = value.optInt("totalPages", 1),
        )
    }

    fun getContent(accessToken: String, id: Int): NativeDeletedUserContent {
        val value = request("/account-privacy/admin/deleted-users/$id/content", accessToken)
        val user = value.optJSONObject("user") ?: JSONObject()
        return NativeDeletedUserContent(
            id = user.optInt("id", id),
            originalUsername = user.optString("originalUsername"),
            originalNickname = user.optString("originalNickname"),
            originalEmail = user.optString("originalEmail"),
            deletedAt = user.optString("deletedAt").takeIf { it.isNotBlank() && it != "null" },
            articles = value.optJSONArray("articles").toArticles(),
            comments = value.optJSONArray("comments").toComments(),
        )
    }

    private fun JSONArray?.toSummaries(): List<NativeDeletedUserSummary> = buildList {
        if (this@toSummaries == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeDeletedUserSummary(
                    id = value.optInt("id"),
                    originalUsername = value.optString("originalUsername"),
                    originalNickname = value.optString("originalNickname"),
                    originalEmail = value.optString("originalEmail"),
                    deletedAt = value.optString("deletedAt").takeIf { it.isNotBlank() && it != "null" },
                    articleCount = value.optInt("articleCount"),
                    commentCount = value.optInt("commentCount"),
                ),
            )
        }
    }

    private fun JSONArray?.toArticles(): List<NativeDeletedUserArticle> = buildList {
        if (this@toArticles == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(NativeDeletedUserArticle(value.optInt("id"), value.optString("title"), value.optString("status"), value.optString("createdAt"), value.optString("publishedAt").takeIf { it.isNotBlank() && it != "null" }))
        }
    }

    private fun JSONArray?.toComments(): List<NativeDeletedUserComment> = buildList {
        if (this@toComments == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(NativeDeletedUserComment(value.optInt("id"), value.optJSONObject("article")?.optString("title").orEmpty(), value.optString("body"), value.optString("status"), value.optString("createdAt")))
        }
    }

    private fun request(path: String, accessToken: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { "已注销账号读取失败（HTTP $status）。" })
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
