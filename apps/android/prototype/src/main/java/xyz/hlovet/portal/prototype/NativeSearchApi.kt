package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal data class NativeSearchUser(
    val id: Int,
    val username: String,
    val nickname: String,
    val bio: String,
    val roleName: String,
)

internal data class NativeSearchArticle(
    val id: Int,
    val title: String,
    val slug: String,
    val category: String,
    val tags: List<String>,
    val publishedAt: String?,
    val viewCount: Int,
    val commentCount: Int,
    val authorName: String,
)

internal data class NativeSearchTopic(
    val id: Int,
    val title: String,
    val slug: String,
    val description: String,
    val articleCount: Int,
    val subscriberCount: Int,
)

internal data class NativeSearchCollection(
    val id: Int,
    val name: String,
    val description: String,
    val articleCount: Int,
    val subscriberCount: Int,
    val ownerName: String,
)

internal data class NativeSearchAnnouncement(
    val id: Int,
    val title: String,
    val summary: String,
    val pinned: Boolean,
)

internal data class NativeSearchEntry(
    val id: Int,
    val title: String,
    val description: String,
    val url: String?,
    val iconPath: String?,
    val openInNewTab: Boolean,
    val categoryName: String,
)

internal data class NativeSearchChatGroup(
    val id: Int,
    val conversationId: Int,
    val name: String,
    val announcement: String,
    val memberCount: Int,
    val joinMode: String,
    val isMember: Boolean,
)

internal data class NativeSearchGroup<T>(
    val items: List<T>,
    val total: Int,
    val page: Int = 1,
    val pageSize: Int = 20,
    val totalPages: Int,
)

internal data class NativeSearchFilters(
    val articleCategories: List<Pair<String, String>>,
    val navigationCategories: List<Pair<String, String>>,
    val toolCategories: List<Pair<String, String>>,
)

internal data class NativeSearchResult(
    val query: String,
    val sort: String,
    val articles: NativeSearchGroup<NativeSearchArticle>,
    val users: NativeSearchGroup<NativeSearchUser>,
    val navigation: NativeSearchGroup<NativeSearchEntry>,
    val tools: NativeSearchGroup<NativeSearchEntry>,
    val topics: NativeSearchGroup<NativeSearchTopic>,
    val collections: NativeSearchGroup<NativeSearchCollection>,
    val groups: NativeSearchGroup<NativeSearchChatGroup>,
    val announcements: NativeSearchGroup<NativeSearchAnnouncement>,
    val filters: NativeSearchFilters,
)

internal data class NativeSearchHistory(
    val id: Int,
    val keyword: String,
    val searchCount: Int,
)

internal object NativeSearchApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun search(
        accessToken: String?,
        query: String,
        scope: String = "all",
        sort: String = "relevance",
        category: String? = null,
        page: Int = 1,
    ): NativeSearchResult {
        val params = buildString {
            append("q=").append(encode(query.trim()))
            append("&page=").append(page.coerceAtLeast(1))
            append("&pageSize=20&scope=").append(encode(scope))
            append("&sort=").append(encode(sort))
            if (!category.isNullOrBlank()) append("&category=").append(encode(category))
        }
        val path = if (accessToken.isNullOrBlank()) "/search/public?$params" else "/search/visible?$params"
        return requestGet(path, accessToken).toResult()
    }

    fun history(accessToken: String): List<NativeSearchHistory> =
        requestGet("/search/history", accessToken).optJSONArray("items").toHistory()

    fun hot(limit: Int = 10): List<NativeSearchHistory> =
        requestGet("/search/hot?limit=${limit.coerceIn(1, 20)}", null)
            .optJSONArray("items")
            .toHistory()

    fun record(accessToken: String, keyword: String) {
        requestMutation("/search/history", "POST", accessToken, JSONObject().put("keyword", keyword.trim()))
    }

    fun clearHistory(accessToken: String) {
        requestMutation("/search/history", "DELETE", accessToken)
    }

    fun deleteHistory(accessToken: String, id: Int) {
        require(id > 0) { "搜索记录编号无效。" }
        requestMutation("/search/history/$id", "DELETE", accessToken)
    }

    private fun requestGet(path: String, accessToken: String?): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return readResponse(connection, "搜索失败")
    }

    private fun requestMutation(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 18_000
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readResponse(connection, "搜索历史操作失败")
    }

    private fun readResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { "$fallback（HTTP $status）" })
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toResult(): NativeSearchResult = NativeSearchResult(
        query = optString("query"),
        sort = optString("sort", "relevance"),
        articles = optJSONObject("articles").toArticlesGroup(),
        users = optJSONObject("users").toUsersGroup(),
        navigation = optJSONObject("navigation").toEntriesGroup(),
        tools = optJSONObject("tools").toEntriesGroup(),
        topics = optJSONObject("topics").toTopicsGroup(),
        collections = optJSONObject("collections").toCollectionsGroup(),
        groups = optJSONObject("groups").toGroupsGroup(),
        announcements = optJSONObject("announcements").toAnnouncementsGroup(),
        filters = NativeSearchFilters(
            articleCategories = optJSONObject("filters")?.optJSONArray("articleCategories").toCategoryPairs(),
            navigationCategories = optJSONObject("filters")?.optJSONArray("navigationCategories").toCategoryPairs(),
            toolCategories = optJSONObject("filters")?.optJSONArray("toolCategories").toCategoryPairs(),
        ),
    )

    private fun JSONObject?.toArticlesGroup(): NativeSearchGroup<NativeSearchArticle> = NativeSearchGroup(
        items = this?.optJSONArray("items").toArticles(), total = this?.optInt("total") ?: 0,
        page = this?.optInt("page", 1) ?: 1, pageSize = this?.optInt("pageSize", 20) ?: 20,
        totalPages = this?.optInt("totalPages", 1) ?: 1,
    )

    private fun JSONObject?.toUsersGroup(): NativeSearchGroup<NativeSearchUser> = NativeSearchGroup(
        items = this?.optJSONArray("items").toUsers(), total = this?.optInt("total") ?: 0,
        page = this?.optInt("page", 1) ?: 1, pageSize = this?.optInt("pageSize", 20) ?: 20,
        totalPages = this?.optInt("totalPages", 1) ?: 1,
    )

    private fun JSONObject?.toEntriesGroup(): NativeSearchGroup<NativeSearchEntry> = NativeSearchGroup(
        items = this?.optJSONArray("items").toEntries(), total = this?.optInt("total") ?: 0,
        page = this?.optInt("page", 1) ?: 1, pageSize = this?.optInt("pageSize", 20) ?: 20,
        totalPages = this?.optInt("totalPages", 1) ?: 1,
    )

    private fun JSONObject?.toTopicsGroup(): NativeSearchGroup<NativeSearchTopic> = NativeSearchGroup(
        items = this?.optJSONArray("items").toTopics(), total = this?.optInt("total") ?: 0,
        page = this?.optInt("page", 1) ?: 1, pageSize = this?.optInt("pageSize", 20) ?: 20,
        totalPages = this?.optInt("totalPages", 1) ?: 1,
    )

    private fun JSONObject?.toCollectionsGroup(): NativeSearchGroup<NativeSearchCollection> = NativeSearchGroup(
        items = this?.optJSONArray("items").toCollections(), total = this?.optInt("total") ?: 0,
        page = this?.optInt("page", 1) ?: 1, pageSize = this?.optInt("pageSize", 20) ?: 20,
        totalPages = this?.optInt("totalPages", 1) ?: 1,
    )

    private fun JSONObject?.toGroupsGroup(): NativeSearchGroup<NativeSearchChatGroup> = NativeSearchGroup(
        items = this?.optJSONArray("items").toGroups(), total = this?.optInt("total") ?: 0,
        page = this?.optInt("page", 1) ?: 1, pageSize = this?.optInt("pageSize", 20) ?: 20,
        totalPages = this?.optInt("totalPages", 1) ?: 1,
    )

    private fun JSONObject?.toAnnouncementsGroup(): NativeSearchGroup<NativeSearchAnnouncement> = NativeSearchGroup(
        items = this?.optJSONArray("items").toAnnouncements(), total = this?.optInt("total") ?: 0,
        page = this?.optInt("page", 1) ?: 1, pageSize = this?.optInt("pageSize", 20) ?: 20,
        totalPages = this?.optInt("totalPages", 1) ?: 1,
    )

    private fun JSONArray?.toArticles(): List<NativeSearchArticle> = buildList {
        for (index in 0 until (this@toArticles?.length() ?: 0)) {
            val value = this@toArticles?.optJSONObject(index) ?: continue
            val author = value.optJSONObject("author")
            add(NativeSearchArticle(
                id = value.optInt("id"), title = value.optString("title"), slug = value.optString("slug"),
                category = value.optString("category"), tags = value.optJSONArray("tags").toStrings(),
                publishedAt = value.optString("publishedAt").takeIf { it.isNotBlank() && it != "null" },
                viewCount = value.optInt("viewCount"), commentCount = value.optInt("commentCount"),
                authorName = author?.optString("nickname").orEmpty().ifBlank { author?.optString("username").orEmpty() },
            ))
        }
    }

    private fun JSONArray?.toUsers(): List<NativeSearchUser> = buildList {
        for (index in 0 until (this@toUsers?.length() ?: 0)) {
            val value = this@toUsers?.optJSONObject(index) ?: continue
            add(NativeSearchUser(
                id = value.optInt("id"), username = value.optString("username"),
                nickname = value.optString("nickname").ifBlank { value.optString("username") },
                bio = value.optString("profileBio"), roleName = value.optJSONObject("role")?.optString("name").orEmpty(),
            ))
        }
    }

    private fun JSONArray?.toTopics(): List<NativeSearchTopic> = buildList {
        for (index in 0 until (this@toTopics?.length() ?: 0)) {
            val value = this@toTopics?.optJSONObject(index) ?: continue
            add(NativeSearchTopic(
                id = value.optInt("id"), title = value.optString("title"), slug = value.optString("slug"),
                description = value.optString("description"), articleCount = value.optInt("articleCount"),
                subscriberCount = value.optInt("subscriberCount"),
            ))
        }
    }

    private fun JSONArray?.toCollections(): List<NativeSearchCollection> = buildList {
        for (index in 0 until (this@toCollections?.length() ?: 0)) {
            val value = this@toCollections?.optJSONObject(index) ?: continue
            add(NativeSearchCollection(
                id = value.optInt("id"), name = value.optString("name"), description = value.optString("description"),
                articleCount = value.optInt("articleCount"), subscriberCount = value.optInt("subscriberCount"),
                ownerName = value.optJSONObject("owner")?.optString("nickname").orEmpty(),
            ))
        }
    }

    private fun JSONArray?.toAnnouncements(): List<NativeSearchAnnouncement> = buildList {
        for (index in 0 until (this@toAnnouncements?.length() ?: 0)) {
            val value = this@toAnnouncements?.optJSONObject(index) ?: continue
            add(NativeSearchAnnouncement(value.optInt("id"), value.optString("title"), value.optString("summary"), value.optBoolean("isPinned")))
        }
    }

    private fun JSONArray?.toEntries(): List<NativeSearchEntry> = buildList {
        for (index in 0 until (this@toEntries?.length() ?: 0)) {
            val value = this@toEntries?.optJSONObject(index) ?: continue
            val category = value.optJSONObject("category")
            add(
                NativeSearchEntry(
                    id = value.optInt("id"),
                    title = value.optString("title"),
                    description = value.optString("description"),
                    url = value.optString("url").takeIf { it.isNotBlank() && it != "null" },
                    iconPath = value.optString("iconPath").takeIf { it.isNotBlank() && it != "null" },
                    openInNewTab = value.optBoolean("openInNewTab"),
                    categoryName = category?.optString("name").orEmpty(),
                ),
            )
        }
    }

    private fun JSONArray?.toGroups(): List<NativeSearchChatGroup> = buildList {
        for (index in 0 until (this@toGroups?.length() ?: 0)) {
            val value = this@toGroups?.optJSONObject(index) ?: continue
            add(
                NativeSearchChatGroup(
                    id = value.optInt("id"),
                    conversationId = value.optInt("conversationId"),
                    name = value.optString("name", "群聊"),
                    announcement = value.optString("announcement"),
                    memberCount = value.optInt("memberCount"),
                    joinMode = value.optString("joinMode", "approval"),
                    isMember = value.optBoolean("isMember"),
                ),
            )
        }
    }

    private fun JSONArray?.toHistory(): List<NativeSearchHistory> = buildList {
        for (index in 0 until (this@toHistory?.length() ?: 0)) {
            val value = this@toHistory?.optJSONObject(index) ?: continue
            add(NativeSearchHistory(value.optInt("id"), value.optString("keyword"), value.optInt("searchCount")))
        }
    }

    private fun JSONArray?.toCategoryPairs(): List<Pair<String, String>> = buildList {
        for (index in 0 until (this@toCategoryPairs?.length() ?: 0)) {
            val value = this@toCategoryPairs?.optJSONObject(index) ?: continue
            add(value.optString("name") to value.optString("value"))
        }
    }

    private fun JSONArray?.toStrings(): List<String> = buildList {
        for (index in 0 until (this@toStrings?.length() ?: 0)) add(this@toStrings?.optString(index).orEmpty())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
