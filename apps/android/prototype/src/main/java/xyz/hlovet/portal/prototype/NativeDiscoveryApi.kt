package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal data class NativeSubscriptionResult(
    val subscribed: Boolean,
    val subscriberCount: Int,
)

internal data class NativeDiscoverySearchResult(
    val articles: List<RemoteArticle>,
    val topics: List<RemoteTopic>,
    val collections: List<RemoteCollection>,
)

internal data class NativeDiscoveryPage(
    val articles: List<RemoteArticle>,
    val topics: List<RemoteTopic>,
    val collections: List<RemoteCollection>,
    val totalPages: Int,
)

internal data class NativeRecommendationAuthor(
    val id: Int,
    val nickname: String,
    val username: String,
    val avatarUrl: String?,
)

internal data class NativeRecommendationTopic(
    val id: Int,
    val title: String,
    val slug: String,
    val description: String,
    val coverPath: String?,
    val articleCount: Int,
    val subscriberCount: Int,
    val subscribed: Boolean,
    val updatedAt: String,
    val reasons: List<String>,
)

internal data class NativeRecommendationCollection(
    val id: Int,
    val name: String,
    val description: String,
    val articleCount: Int,
    val subscriberCount: Int,
    val subscribed: Boolean,
    val owner: NativeRecommendationAuthor,
    val updatedAt: String,
    val reasons: List<String>,
)

internal data class NativeRecommendationGroup(
    val id: Int,
    val conversationId: Int,
    val name: String,
    val avatarUrl: String?,
    val announcement: String,
    val memberCount: Int,
    val joinMode: String,
    val isMember: Boolean,
    val updatedAt: String,
    val reasons: List<String>,
)

internal data class NativeRecommendationAuthorItem(
    val author: NativeRecommendationAuthor,
    val topCategory: String,
    val articleCount: Int,
    val engagementCount: Int,
    val subscribed: Boolean,
    val reasons: List<String>,
)

internal data class NativeDiscoveryRecommendations(
    val topics: List<NativeRecommendationTopic>,
    val collections: List<NativeRecommendationCollection>,
    val groups: List<NativeRecommendationGroup>,
    val authors: List<NativeRecommendationAuthorItem>,
    val batch: Int,
    val hasMore: Boolean,
)

internal fun normalizeNativeRecommendationTargetType(value: String?): String = when (value?.trim()?.lowercase()) {
    "topic", "collection", "author", "group", "article" -> value.trim().lowercase()
    else -> "article"
}

internal fun nativeRecommendationReasonText(reasons: List<String>): String = reasons
    .mapNotNull {
        when (it) {
            "based_on_reading" -> "基于你的阅读"
            "based_on_subscription" -> "与你的订阅相关"
            "based_on_interaction" -> "与你互动过的内容相关"
            "recently_active" -> "近期活跃"
            "popular" -> "站内热门"
            else -> null
        }
    }
    .distinct()
    .take(2)
    .joinToString(" · ")

internal fun nativeRecommendationReasonDisplayText(reasons: List<String>): String = reasons
    .mapNotNull {
        when (it) {
            "based_on_reading" -> nativePhrase("基于你的阅读", "Based on your reading")
            "based_on_subscription" -> nativePhrase("与你的订阅相关", "Related to your subscriptions")
            "based_on_interaction" -> nativePhrase("与你互动过的内容相关", "Based on your interactions")
            "recently_active" -> nativePhrase("近期活跃", "Recently active")
            "popular" -> nativePhrase("站内热门", "Popular on HLOVET")
            else -> null
        }
    }
    .distinct()
    .take(2)
    .joinToString(" · ")

internal data class NativeCollectionPage(
    val items: List<RemoteCollection>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal data class NativeSubscriptionFeedItem(
    val article: RemoteArticle,
    val readAt: String?,
)

internal data class NativeSubscriptionFeed(
    val items: List<NativeSubscriptionFeedItem>,
    val total: Int,
    val unread: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal data class NativeAuthorSubscriptionSetting(
    val authorId: Int,
    val nickname: String,
    val username: String,
    val notifyNewArticles: Boolean,
    val frequency: String,
)

internal data class NativeSubscriptionSettings(
    val items: List<NativeAuthorSubscriptionSetting>,
    val digestEnabled: Boolean,
)

internal data class NativeSubscriptionEmailDelivery(
    val dayKey: String,
    val status: String,
    val itemCount: Int,
)

internal data class NativeSubscriptionEmailSettings(
    val available: Boolean,
    val enabled: Boolean,
    val unsubscribedAt: String?,
    val deliveries: List<NativeSubscriptionEmailDelivery>,
)

internal data class NativeTopicSubscriptionSetting(
    val id: Int,
    val title: String,
    val slug: String,
    val articleCount: Int,
    val subscriberCount: Int,
    val frequency: String,
)

internal data class NativeCollectionSubscriptionSetting(
    val id: Int,
    val name: String,
    val ownerName: String,
    val articleCount: Int,
)

internal data class NativeTagSubscriptionSetting(
    val tag: String,
    val frequency: String,
)

internal data class NativeContentSubscriptionSettings(
    val topics: List<NativeTopicSubscriptionSetting>,
    val collections: List<NativeCollectionSubscriptionSetting>,
    val tags: List<NativeTagSubscriptionSetting>,
)

internal data class NativeOnboardingTopic(
    val id: Int,
    val title: String,
    val description: String,
    val articleCount: Int,
    val subscriberCount: Int,
    val coverPath: String?,
    val subscribed: Boolean,
)

internal data class NativeOnboardingAuthor(
    val id: Int,
    val nickname: String,
    val username: String,
    val avatarUrl: String?,
    val topCategory: String,
    val subscribed: Boolean,
)

internal data class NativeOnboardingState(
    val completed: Boolean,
    val topics: List<NativeOnboardingTopic>,
    val authors: List<NativeOnboardingAuthor>,
)

internal object NativeDiscoveryApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun getTopic(accessToken: String?, slug: String): RemoteTopic {
        val encodedSlug = URLEncoder.encode(slug, Charsets.UTF_8.name()).replace("+", "%20")
        val path = if (accessToken.isNullOrBlank()) {
            "/discovery/topics/public/$encodedSlug"
        } else {
            "/discovery/topics/visible/$encodedSlug"
        }
        return requestGet(path, accessToken).toTopic()
    }

    fun getCollection(accessToken: String?, id: Int): RemoteCollection {
        val path = if (accessToken.isNullOrBlank()) {
            "/discovery/collections/public/$id"
        } else {
            "/discovery/collections/visible/$id"
        }
        return requestGet(path, accessToken).toCollection()
    }

    internal fun parseCollection(value: JSONObject): RemoteCollection = value.toCollection()

    fun listMyCollections(accessToken: String): List<RemoteCollection> =
        requestGet("/discovery/collections/mine", accessToken)
            .optJSONArray("items")
            .toCollections()

    fun listVisibleCollections(
        accessToken: String,
        query: String = "",
        page: Int = 1,
        pageSize: Int = 12,
    ): NativeCollectionPage {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)
        val encodedQuery = URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        val response = requestGet(
            "/discovery/collections/visible?page=$safePage&pageSize=$safeSize&q=$encodedQuery",
            accessToken,
        )
        return NativeCollectionPage(
            items = response.optJSONArray("items").toCollections(),
            total = response.optInt("total"),
            page = response.optInt("page", safePage),
            pageSize = response.optInt("pageSize", safeSize),
            totalPages = response.optInt("totalPages", 1).coerceAtLeast(1),
        )
    }

    fun search(accessToken: String?, query: String): NativeDiscoverySearchResult {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        val authenticated = !accessToken.isNullOrBlank()
        val articlesPath = if (authenticated) "/articles/visible" else "/articles"
        val topicsPath = if (authenticated) "/discovery/topics/visible" else "/discovery/topics"
        val collectionsPath = if (authenticated) "/discovery/collections/visible" else "/discovery/collections"
        return NativeDiscoverySearchResult(
            articles = requestGet("$articlesPath?page=1&pageSize=20&sort=latest&search=$encoded", accessToken)
                .optJSONArray("items").toArticles(),
            topics = requestGet("$topicsPath?page=1&pageSize=20&q=$encoded", accessToken)
                .optJSONArray("items").toTopics(),
            collections = requestGet("$collectionsPath?page=1&pageSize=20&q=$encoded", accessToken)
                .optJSONArray("items").toCollections(),
        )
    }

    fun listDiscoveryPage(
        accessToken: String?,
        page: Int,
        pageSize: Int = 8,
        sort: String = "latest",
    ): NativeDiscoveryPage {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)
        val authenticated = !accessToken.isNullOrBlank()
        val articlePath = if (authenticated) "/articles/visible" else "/articles"
        val topicPath = if (authenticated) "/discovery/topics/visible" else "/discovery/topics"
        val collectionPath = if (authenticated) "/discovery/collections/visible" else "/discovery/collections"
        val articleResponse = requestGet("$articlePath?page=$safePage&pageSize=$safeSize&sort=$sort", accessToken)
        val topicResponse = requestGet("$topicPath?page=$safePage&pageSize=$safeSize", accessToken)
        val collectionResponse = requestGet("$collectionPath?page=$safePage&pageSize=$safeSize", accessToken)
        return NativeDiscoveryPage(
            articles = articleResponse.optJSONArray("items").toArticles(),
            topics = topicResponse.optJSONArray("items").toTopics(),
            collections = collectionResponse.optJSONArray("items").toCollections(),
            totalPages = maxOf(
                articleResponse.optInt("totalPages", 1),
                topicResponse.optInt("totalPages", 1),
                collectionResponse.optInt("totalPages", 1),
            ),
        )
    }

    fun listRecommendations(accessToken: String, batch: Int = 0): NativeDiscoveryRecommendations {
        val response = requestGet("/discovery/recommendations?batch=${batch.coerceAtLeast(0)}", accessToken)
        return parseRecommendations(response, batch)
    }

    internal fun parseRecommendations(response: JSONObject, fallbackBatch: Int = 0): NativeDiscoveryRecommendations {
        return NativeDiscoveryRecommendations(
            topics = response.optJSONArray("topics").toRecommendationTopics(),
            collections = response.optJSONArray("collections").toRecommendationCollections(),
            groups = response.optJSONArray("groups").toRecommendationGroups(),
            authors = response.optJSONArray("authors").toRecommendationAuthors(),
            batch = response.optInt("batch", fallbackBatch.coerceAtLeast(0)),
            hasMore = response.optBoolean("hasMore"),
        )
    }

    fun recordRecommendationFeedback(accessToken: String, targetType: String, targetId: Int) {
        requestMutation(
            "/discovery/recommendations/feedback",
            "POST",
            accessToken,
            JSONObject().put("targetType", normalizeNativeRecommendationTargetType(targetType)).put("targetId", targetId),
        )
    }

    fun listSubscriptionFeed(accessToken: String, page: Int = 1, pageSize: Int = 12, sort: String = "latest"): NativeSubscriptionFeed {
        val response = requestGet(
            "/discovery/feed?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}&sort=${sort.ifBlank { "latest" }}",
            accessToken,
        )
        return NativeSubscriptionFeed(
            items = response.optJSONArray("items").toSubscriptionFeedItems(),
            total = response.optInt("total"),
            unread = response.optInt("unread"),
            page = response.optInt("page", page),
            pageSize = response.optInt("pageSize", pageSize),
            totalPages = response.optInt("totalPages", 1),
        )
    }

    fun markSubscriptionRead(accessToken: String, articleId: Int): String =
        requestMutation("/discovery/feed/$articleId/read", "POST", accessToken)
            .optString("readAt")

    fun markAllSubscriptionsRead(accessToken: String): String =
        requestMutation("/discovery/feed/read-all", "POST", accessToken)
            .optString("readAt")

    fun getSubscriptionSettings(accessToken: String): NativeSubscriptionSettings {
        val response = requestGet("/discovery/subscriptions/settings", accessToken)
        return NativeSubscriptionSettings(
            items = response.optJSONArray("items").toAuthorSubscriptionSettings(),
            digestEnabled = response.optBoolean("digestEnabled", true),
        )
    }

    fun getSubscriptionEmailSettings(accessToken: String): NativeSubscriptionEmailSettings =
        requestGet("/distribution/email", accessToken).toSubscriptionEmailSettings()

    fun updateSubscriptionEmailSettings(accessToken: String, enabled: Boolean): NativeSubscriptionEmailSettings =
        requestMutation(
            "/distribution/email",
            "PATCH",
            accessToken,
            JSONObject().put("enabled", enabled),
        ).toSubscriptionEmailSettings()

    fun getContentSubscriptionSettings(accessToken: String): NativeContentSubscriptionSettings {
        val response = requestGet("/discovery/subscriptions/content", accessToken)
        return NativeContentSubscriptionSettings(
            topics = response.optJSONArray("topics").toTopicSubscriptionSettings(),
            collections = response.optJSONArray("collections").toCollectionSubscriptionSettings(),
            tags = response.optJSONArray("tags").toTagSubscriptionSettings(),
        )
    }

    fun getOnboarding(accessToken: String): NativeOnboardingState {
        val response = requestGet("/discovery/onboarding", accessToken)
        return NativeOnboardingState(
            completed = response.optBoolean("completed"),
            topics = response.optJSONArray("topics").toOnboardingTopics(),
            authors = response.optJSONArray("authors").toOnboardingAuthors(),
        )
    }

    fun completeOnboarding(accessToken: String, topicIds: List<Int>, authorIds: List<Int>) {
        requestMutation(
            "/discovery/onboarding",
            "POST",
            accessToken,
            JSONObject().apply {
                put("topicIds", JSONArray(topicIds.distinct().take(3)))
                put("authorIds", JSONArray(authorIds.distinct().take(6)))
            },
        )
    }

    fun updateAuthorSubscription(accessToken: String, authorId: Int, notifyNewArticles: Boolean? = null, frequency: String? = null) {
        requestMutation(
            "/discovery/subscriptions/$authorId/settings",
            "PATCH",
            accessToken,
            JSONObject().apply {
                notifyNewArticles?.let { put("notifyNewArticles", it) }
                frequency?.let { put("frequency", it) }
            },
        )
    }

    fun unsubscribeAuthor(accessToken: String, authorId: Int) {
        requestMutation("/social/subscriptions/$authorId", "DELETE", accessToken)
    }

    fun updateTopicSubscriptionFrequency(accessToken: String, topicId: Int, frequency: String) {
        requestMutation(
            "/discovery/topics/$topicId/subscription",
            "PATCH",
            accessToken,
            JSONObject().put("frequency", frequency),
        )
    }

    fun unsubscribeTopic(accessToken: String, topicId: Int) {
        requestMutation("/discovery/topics/$topicId/subscribe", "DELETE", accessToken)
    }

    fun updateTagSubscriptionFrequency(accessToken: String, tag: String, frequency: String) {
        val encodedTag = URLEncoder.encode(tag, Charsets.UTF_8.name()).replace("+", "%20")
        requestMutation(
            "/discovery/tags/subscriptions/$encodedTag",
            "PATCH",
            accessToken,
            JSONObject().put("frequency", frequency),
        )
    }

    fun unsubscribeTag(accessToken: String, tag: String) {
        val encodedTag = URLEncoder.encode(tag, Charsets.UTF_8.name()).replace("+", "%20")
        requestMutation("/discovery/tags/subscribe/$encodedTag", "DELETE", accessToken)
    }

    fun unsubscribeCollection(accessToken: String, collectionId: Int) {
        requestMutation("/discovery/collections/$collectionId/subscribe", "DELETE", accessToken)
    }

    fun updateSubscriptionDigest(accessToken: String, enabled: Boolean) {
        requestMutation(
            "/social/notification-channels/subscription/settings",
            "PATCH",
            accessToken,
            JSONObject().put("digestEnabled", enabled),
        )
    }

    fun setTopicSubscription(accessToken: String, id: Int, subscribed: Boolean): NativeSubscriptionResult =
        requestMutation("/discovery/topics/$id/subscribe", if (subscribed) "POST" else "DELETE", accessToken)
            .toSubscriptionResult(subscribed)

    fun setCollectionSubscription(accessToken: String, id: Int, subscribed: Boolean): NativeSubscriptionResult =
        requestMutation("/discovery/collections/$id/subscribe", if (subscribed) "POST" else "DELETE", accessToken)
            .toSubscriptionResult(subscribed)

    fun createCollection(
        accessToken: String,
        name: String,
        description: String,
        visibility: String,
        articleIds: List<Int>,
        coverPath: String? = null,
    ): RemoteCollection = requestMutation(
        "/discovery/collections",
        "POST",
        accessToken,
        JSONObject().apply {
            put("name", name.trim())
            put("description", description.trim())
            put("visibility", visibility)
            put("articleIds", JSONArray(articleIds))
            coverPath?.let { put("coverPath", it.trim()) }
        },
    ).toCollection()

    fun updateCollection(
        accessToken: String,
        id: Int,
        name: String,
        description: String,
        visibility: String,
        coverPath: String? = null,
    ): RemoteCollection = requestMutation(
        "/discovery/collections/$id",
        "PATCH",
        accessToken,
        JSONObject().apply {
            put("name", name.trim())
            put("description", description.trim())
            put("visibility", visibility)
            coverPath?.let { put("coverPath", it.trim()) }
        },
    ).toCollection()

    fun uploadCollectionCover(accessToken: String, collectionId: Int, file: NativeUploadFile): RemoteCollection {
        val boundary = "----HlovetAndroidCollection${System.currentTimeMillis()}"
        val connection = (URL("$baseUrl/discovery/collections/$collectionId/cover").openConnection() as HttpURLConnection).apply {
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
                val safeName = file.name.replace("\"", "").replace("\r", "").replace("\n", "")
                output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Type: ${file.mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n".toByteArray(Charsets.UTF_8))
                output.write(file.bytes)
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            readResponse(connection, "合集封面上传失败（HTTP ${connection.responseCode}）").toCollection()
        } finally {
            connection.disconnect()
        }
    }

    fun deleteCollection(accessToken: String, id: Int) {
        requestMutation("/discovery/collections/$id", "DELETE", accessToken)
    }

    fun addCollectionArticle(accessToken: String, id: Int, articleId: Int): RemoteCollection =
        requestMutation("/discovery/collections/$id/articles/$articleId", "POST", accessToken).toCollection()

    fun removeCollectionArticle(accessToken: String, id: Int, articleId: Int): RemoteCollection =
        requestMutation("/discovery/collections/$id/articles/$articleId", "DELETE", accessToken).toCollection()

    fun reorderCollectionArticles(accessToken: String, id: Int, articleIds: List<Int>): RemoteCollection =
        requestMutation(
            "/discovery/collections/$id/articles/order",
            "PATCH",
            accessToken,
            JSONObject().apply { put("ids", JSONArray(articleIds)) },
        ).toCollection()

    private fun requestGet(path: String, accessToken: String?): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(errorMessage(body, "专题或合集加载失败（HTTP $status）"))
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestMutation(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
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
            if (status !in 200..299) throw IllegalStateException(errorMessage(body, "订阅操作失败（HTTP $status）"))
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw IllegalStateException(errorMessage(body, fallback))
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    private fun JSONObject.toTopic(): RemoteTopic {
        return RemoteTopic(
            id = optInt("id"),
            title = optString("title", "未命名专题"),
            slug = optString("slug"),
            description = optString("description"),
            coverPath = optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
            visibility = optString("visibility", "public"),
            articleCount = optInt("articleCount", optJSONArray("articles")?.length() ?: 0),
            subscriberCount = optInt("subscriberCount"),
            subscribed = optBoolean("subscribed"),
            updatedAt = optString("updatedAt"),
            articles = optJSONArray("articles").toArticles(),
            status = optString("status", "active"),
            sortOrder = optInt("sortOrder"),
            roleCodes = optJSONArray("roleCodes").toStrings(),
        )
    }

    private fun JSONObject.toCollection(): RemoteCollection {
        val owner = optJSONObject("owner")
        return RemoteCollection(
            id = optInt("id"),
            name = optString("name", "未命名合集"),
            description = optString("description"),
            coverPath = optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
            visibility = optString("visibility", "public"),
            articleCount = optInt("articleCount", optJSONArray("articles")?.length() ?: 0),
            subscriberCount = optInt("subscriberCount"),
            subscribed = optBoolean("subscribed"),
            updatedAt = optString("updatedAt"),
            ownerName = owner?.displayName() ?: "站内作者",
            articles = optJSONArray("articles").toArticles(),
        )
    }

    private fun JSONArray?.toArticles(): List<RemoteArticle> = buildList {
        if (this@toArticles == null) return@buildList
        for (index in 0 until length()) add(getJSONObject(index).toArticle())
    }

    private fun JSONArray?.toSubscriptionFeedItems(): List<NativeSubscriptionFeedItem> = buildList {
        if (this@toSubscriptionFeedItems == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val article = value.optJSONObject("article") ?: continue
            add(NativeSubscriptionFeedItem(article.toArticle(), value.optString("readAt").takeIf { it.isNotBlank() && it != "null" }))
        }
    }

    private fun JSONArray?.toAuthorSubscriptionSettings(): List<NativeAuthorSubscriptionSetting> = buildList {
        if (this@toAuthorSubscriptionSettings == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val author = value.optJSONObject("author") ?: continue
            add(
                NativeAuthorSubscriptionSetting(
                    authorId = author.optInt("id"),
                    nickname = author.displayName(),
                    username = author.optString("username"),
                    notifyNewArticles = value.optBoolean("notifyNewArticles", true),
                    frequency = value.optString("frequency", "instant"),
                ),
            )
        }
    }

    private fun JSONArray?.toTopicSubscriptionSettings(): List<NativeTopicSubscriptionSetting> = buildList {
        if (this@toTopicSubscriptionSettings == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeTopicSubscriptionSetting(
                    id = value.optInt("id"),
                    title = value.optString("title", "未命名专题"),
                    slug = value.optString("slug"),
                    articleCount = value.optInt("articleCount"),
                    subscriberCount = value.optInt("subscriberCount"),
                    frequency = value.optString("frequency", "instant"),
                ),
            )
        }
    }

    private fun JSONArray?.toCollectionSubscriptionSettings(): List<NativeCollectionSubscriptionSetting> = buildList {
        if (this@toCollectionSubscriptionSettings == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val owner = value.optJSONObject("owner")
            add(
                NativeCollectionSubscriptionSetting(
                    id = value.optInt("id"),
                    name = value.optString("name", "未命名合集"),
                    ownerName = owner?.displayName() ?: "站内作者",
                    articleCount = value.optInt("articleCount"),
                ),
            )
        }
    }

    private fun JSONArray?.toTagSubscriptionSettings(): List<NativeTagSubscriptionSetting> = buildList {
        if (this@toTagSubscriptionSettings == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(NativeTagSubscriptionSetting(value.optString("tag"), value.optString("frequency", "instant")))
        }
    }

    private fun JSONArray?.toOnboardingTopics(): List<NativeOnboardingTopic> = buildList {
        if (this@toOnboardingTopics == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeOnboardingTopic(
                    id = value.optInt("id"),
                    title = value.optString("title", "未命名专题"),
                    description = value.optString("description"),
                    articleCount = value.optInt("articleCount"),
                    subscriberCount = value.optInt("subscriberCount"),
                    coverPath = value.optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
                    subscribed = value.optBoolean("subscribed"),
                ),
            )
        }
    }

    private fun JSONArray?.toOnboardingAuthors(): List<NativeOnboardingAuthor> = buildList {
        if (this@toOnboardingAuthors == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeOnboardingAuthor(
                    id = value.optInt("id"),
                    nickname = value.optString("nickname").ifBlank { value.optString("username", "站内作者") },
                    username = value.optString("username"),
                    avatarUrl = value.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
                    topCategory = value.optString("topCategory", "未分类"),
                    subscribed = value.optBoolean("subscribed"),
                ),
            )
        }
    }

    private fun JSONArray?.toTopics(): List<RemoteTopic> = buildList {
        if (this@toTopics == null) return@buildList
        for (index in 0 until length()) add(getJSONObject(index).toTopic())
    }

    private fun JSONArray?.toCollections(): List<RemoteCollection> = buildList {
        if (this@toCollections == null) return@buildList
        for (index in 0 until length()) add(getJSONObject(index).toCollection())
    }

    private fun JSONArray?.toRecommendationTopics(): List<NativeRecommendationTopic> = buildList {
        if (this@toRecommendationTopics == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeRecommendationTopic(
                    id = value.optInt("id"),
                    title = value.optString("title", "未命名专题"),
                    slug = value.optString("slug"),
                    description = value.optString("description"),
                    coverPath = value.optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
                    articleCount = value.optInt("articleCount"),
                    subscriberCount = value.optInt("subscriberCount"),
                    subscribed = value.optBoolean("subscribed"),
                    updatedAt = value.optString("updatedAt"),
                    reasons = value.optJSONArray("reasons").toStrings(),
                ),
            )
        }
    }

    private fun JSONArray?.toRecommendationCollections(): List<NativeRecommendationCollection> = buildList {
        if (this@toRecommendationCollections == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeRecommendationCollection(
                    id = value.optInt("id"),
                    name = value.optString("name", "未命名合集"),
                    description = value.optString("description"),
                    articleCount = value.optInt("articleCount"),
                    subscriberCount = value.optInt("subscriberCount"),
                    subscribed = value.optBoolean("subscribed"),
                    owner = value.optJSONObject("owner").toRecommendationAuthor(),
                    updatedAt = value.optString("updatedAt"),
                    reasons = value.optJSONArray("reasons").toStrings(),
                ),
            )
        }
    }

    private fun JSONArray?.toRecommendationGroups(): List<NativeRecommendationGroup> = buildList {
        if (this@toRecommendationGroups == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeRecommendationGroup(
                    id = value.optInt("id"),
                    conversationId = value.optInt("conversationId"),
                    name = value.optString("name", "未命名群聊"),
                    avatarUrl = value.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
                    announcement = value.optString("announcement"),
                    memberCount = value.optInt("memberCount"),
                    joinMode = value.optString("joinMode", "approval"),
                    isMember = value.optBoolean("isMember"),
                    updatedAt = value.optString("updatedAt"),
                    reasons = value.optJSONArray("reasons").toStrings(),
                ),
            )
        }
    }

    private fun JSONArray?.toRecommendationAuthors(): List<NativeRecommendationAuthorItem> = buildList {
        if (this@toRecommendationAuthors == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeRecommendationAuthorItem(
                    author = value.toRecommendationAuthor(),
                    topCategory = value.optString("topCategory", "未分类"),
                    articleCount = value.optInt("articleCount"),
                    engagementCount = value.optInt("engagementCount"),
                    subscribed = value.optBoolean("subscribed"),
                    reasons = value.optJSONArray("reasons").toStrings(),
                ),
            )
        }
    }

    private fun JSONObject?.toRecommendationAuthor(): NativeRecommendationAuthor {
        val value = this ?: JSONObject()
        return NativeRecommendationAuthor(
            id = value.optInt("id"),
            nickname = value.optString("nickname").ifBlank { value.optString("username", "站内作者") },
            username = value.optString("username"),
            avatarUrl = value.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun JSONObject.toArticle(): RemoteArticle {
        val author = optJSONObject("author")
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
            authorName = author?.displayName() ?: optString("authorName", "站内作者"),
            authorUsername = author?.optString("username") ?: optString("authorUsername"),
            authorId = author?.optInt("id") ?: optInt("authorId"),
            liked = optBoolean("liked"),
            favorited = optBoolean("favorited"),
            readLater = optBoolean("readLater"),
            images = optJSONArray("images").toStrings(),
        )
    }

    private fun JSONObject.toSubscriptionResult(fallback: Boolean): NativeSubscriptionResult =
        NativeSubscriptionResult(
            subscribed = optBoolean("subscribed", fallback),
            subscriberCount = optInt("subscriberCount"),
        )

    private fun JSONObject.toSubscriptionEmailSettings(): NativeSubscriptionEmailSettings =
        NativeSubscriptionEmailSettings(
            available = optBoolean("available"),
            enabled = optBoolean("enabled"),
            unsubscribedAt = optString("unsubscribedAt").takeIf { it.isNotBlank() && it != "null" },
            deliveries = optJSONArray("deliveries").toSubscriptionEmailDeliveries(),
        )

    private fun JSONArray?.toSubscriptionEmailDeliveries(): List<NativeSubscriptionEmailDelivery> = buildList {
        if (this@toSubscriptionEmailDeliveries == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeSubscriptionEmailDelivery(
                    dayKey = value.optString("dayKey"),
                    status = value.optString("status"),
                    itemCount = value.optInt("itemCount"),
                ),
            )
        }
    }

    private fun JSONObject.displayName(): String =
        optString("nickname").ifBlank { optString("username").ifBlank { "站内作者" } }

    private fun JSONArray?.toStrings(): List<String> = buildList {
        if (this@toStrings == null) return@buildList
        for (index in 0 until length()) add(optString(index))
    }

    private fun errorMessage(body: String, fallback: String): String =
        runCatching { JSONObject(body).optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallback
}
