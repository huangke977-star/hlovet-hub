package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal data class NativeReputationLevel(
    val code: String,
    val name: String,
    val level: Int,
    val minExperience: Int,
)

internal data class NativeReputationRule(
    val reason: String,
    val label: String,
    val experience: Int,
    val points: Int,
    val dailyExperienceCap: Int?,
)

internal data class NativeReputationLedger(
    val id: Int,
    val reason: String,
    val description: String,
    val experienceDelta: Int,
    val pointDelta: Int,
    val experienceAfter: Int,
    val pointsAfter: Int,
    val pendingPointDelta: Int,
    val availableAt: String?,
    val settledAt: String?,
    val createdAt: String,
)

internal data class NativeReputationSummary(
    val experience: Int,
    val points: Int,
    val pendingPoints: Int,
    val level: NativeReputationLevel,
    val nextLevel: NativeReputationLevel?,
    val experienceToNext: Int,
    val progressPercent: Int,
    val rules: List<NativeReputationRule>,
    val recent: List<NativeReputationLedger>,
)

internal data class NativeReputationPage(
    val items: List<NativeReputationLedger>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal data class NativeResourceCatalogItem(
    val article: RemoteArticle,
    val minimumPointCost: Int,
    val blockCount: Int,
    val exchangeCount: Int,
)

internal data class NativeResourceCatalog(
    val items: List<NativeResourceCatalogItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal data class NativeResourceSummary(
    val purchasedBlocks: Int,
    val soldBlocks: Int,
    val pendingPoints: Int,
)

internal data class NativeResourcePerson(
    val id: Int,
    val nickname: String,
    val username: String,
)

internal data class NativeResourceDeliveryEvent(
    val id: Int,
    val type: String,
    val attempt: Int,
    val detail: String?,
    val createdAt: String,
)

internal data class NativeResourceDelivery(
    val id: Int,
    val articleId: Int,
    val articleTitle: String,
    val articleSlug: String,
    val blockKey: String,
    val pointCost: Int,
    val status: String,
    val attemptCount: Int,
    val lastError: String?,
    val downloadedAt: String?,
    val refundedAt: String?,
    val createdAt: String,
    val availableAt: String,
    val settledAt: String?,
    val buyer: NativeResourcePerson,
    val author: NativeResourcePerson,
    val events: List<NativeResourceDeliveryEvent>,
)

internal data class NativeResourceDeliveryPage(
    val items: List<NativeResourceDelivery>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal data class NativeAdminPointAdjustment(
    val id: Int,
    val user: NativeResourcePerson,
    val reason: String,
    val eventKey: String,
    val description: String,
    val pointDelta: Int,
    val pointsAfter: Int,
    val createdAt: String,
)

internal data class NativeAdminPointAdjustmentPage(
    val items: List<NativeAdminPointAdjustment>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal data class NativeResourceAggregate(
    val articleId: Int,
    val articleTitle: String,
    val articleSlug: String,
    val blockKey: String,
    val redemptionCount: Int,
    val grossPoints: Int,
    val pendingPoints: Int,
    val settledPoints: Int,
    val refundedPoints: Int,
)

internal data class NativeResourceEarnings(
    val total: Int,
    val pending: Int,
    val settled: Int,
    val refunded: Int,
    val aggregates: List<NativeResourceAggregate>,
    val items: List<NativeResourceDelivery>,
)

/** 复用站点资源与积分接口，解析目录、交付、作者收益和管理员账本结果。 */
internal object NativeResourceApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun listCatalog(
        accessToken: String?,
        query: String = "",
        sort: String = "latest",
        page: Int = 1,
        pageSize: Int = 12,
    ): NativeResourceCatalog {
        val path = if (accessToken.isNullOrBlank()) "/discovery/resources" else "/discovery/resources/visible"
        val encodedQuery = URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        val response = requestGet(
            "$path?q=$encodedQuery&sort=${sort.ifBlank { "latest" }}&page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}",
            accessToken,
        )
        return NativeResourceCatalog(
            items = response.optJSONArray("items").toCatalogItems(),
            total = response.optInt("total"),
            page = response.optInt("page", page),
            pageSize = response.optInt("pageSize", pageSize),
            totalPages = response.optInt("totalPages", 1),
        )
    }

    fun getSummary(accessToken: String): NativeResourceSummary {
        val response = requestGet("/discovery/resources/summary", accessToken)
        return NativeResourceSummary(
            purchasedBlocks = response.optInt("purchasedBlocks"),
            soldBlocks = response.optInt("soldBlocks"),
            pendingPoints = response.optInt("pendingPoints"),
        )
    }

    fun getReputation(accessToken: String): NativeReputationSummary {
        val response = requestGet("/reputation/me", accessToken)
        return NativeReputationSummary(
            experience = response.optInt("experience"),
            points = response.optInt("points"),
            pendingPoints = response.optInt("pendingPoints"),
            level = response.optJSONObject("level").toReputationLevel(),
            nextLevel = response.optJSONObject("nextLevel")?.toReputationLevel(),
            experienceToNext = response.optInt("experienceToNext"),
            progressPercent = response.optInt("progressPercent"),
            rules = response.optJSONArray("rules").toReputationRules(),
            recent = response.optJSONArray("recent").toReputationLedgers(),
        )
    }

    fun getReputationLedger(accessToken: String, page: Int = 1, pageSize: Int = 20): NativeReputationPage {
        val response = requestGet(
            "/reputation/ledger?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}",
            accessToken,
        )
        return NativeReputationPage(
            items = response.optJSONArray("items").toReputationLedgers(),
            total = response.optInt("total"),
            page = response.optInt("page", page),
            pageSize = response.optInt("pageSize", pageSize),
            totalPages = response.optInt("totalPages", 1),
        )
    }

    fun listDeliveries(accessToken: String, page: Int = 1, pageSize: Int = 20): NativeResourceDeliveryPage {
        val response = requestGet(
            "/resources/mine/deliveries?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}",
            accessToken,
        )
        return NativeResourceDeliveryPage(
            items = response.optJSONArray("items").toDeliveries(),
            total = response.optInt("total"),
            page = response.optInt("page", page),
            pageSize = response.optInt("pageSize", pageSize),
            totalPages = response.optInt("totalPages", 1),
        )
    }

    fun downloadDelivery(accessToken: String, id: Int) {
        requestMutation("/resources/deliveries/$id/download", "POST", accessToken)
    }

    fun retryDelivery(accessToken: String, id: Int) {
        requestMutation("/resources/deliveries/$id/retry", "POST", accessToken)
    }

    fun getCreatorEarnings(accessToken: String): NativeResourceEarnings {
        val response = requestGet("/resources/creator/earnings", accessToken)
        val summary = response.optJSONObject("summary") ?: JSONObject()
        return NativeResourceEarnings(
            total = summary.optInt("total"),
            pending = summary.optInt("pending"),
            settled = summary.optInt("settled"),
            refunded = summary.optInt("refunded"),
            aggregates = response.optJSONArray("aggregates").toAggregates(),
            items = response.optJSONArray("items").toDeliveries(),
        )
    }

    fun listAdminDeliveries(accessToken: String, page: Int = 1, pageSize: Int = 50): NativeResourceDeliveryPage {
        val response = requestGet(
            "/resources/admin/deliveries?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 100)}",
            accessToken,
        )
        return response.toDeliveryPage(page, pageSize)
    }

    fun listAdminPointAdjustments(accessToken: String, page: Int = 1, pageSize: Int = 50): NativeAdminPointAdjustmentPage {
        val response = requestGet(
            "/resources/admin/points/adjustments?page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 100)}",
            accessToken,
        )
        return NativeAdminPointAdjustmentPage(
            items = response.optJSONArray("items").toAdminPointAdjustments(),
            total = response.optInt("total"),
            page = response.optInt("page", page),
            pageSize = response.optInt("pageSize", pageSize),
            totalPages = response.optInt("totalPages", 1),
        )
    }

    fun markAdminDeliveryFailed(accessToken: String, id: Int, error: String) {
        requestMutation(
            "/resources/admin/deliveries/$id/fail",
            "POST",
            accessToken,
            JSONObject().put("error", error.trim().take(1000)),
        )
    }

    fun refundAdminDelivery(accessToken: String, id: Int) {
        requestMutation("/resources/admin/deliveries/$id/refund", "POST", accessToken)
    }

    /** 使用 accessToken 向 username 补发 points；eventKey 防重复，note 写入账本，返回是否新增入账。 */
    fun topUpAdminPoints(accessToken: String, username: String, points: Int, eventKey: String, note: String): Boolean {
        // 服务端按目标账号与事件键幂等记账，不能将 HTTP 成功一律解释为再次入账。
        return requestMutation(
            "/resources/admin/points/top-up",
            "POST",
            accessToken,
            JSONObject().apply {
                put("username", username.trim().removePrefix("@"))
                put("points", points)
                put("eventKey", eventKey.trim())
                if (note.isNotBlank()) put("note", note.trim())
            },
        ).getBoolean("applied")
    }

    /** 使用 accessToken 对 username 扣除 points；eventKey 防重复，note 写入账本，返回是否新增扣分。 */
    fun applyAdminViolationPenalty(accessToken: String, username: String, points: Int, eventKey: String, note: String): Boolean {
        // 保留服务器 applied=false 的业务含义，重复提交只提示已经处理而不声称再次扣分。
        return requestMutation(
            "/resources/admin/points/violation",
            "POST",
            accessToken,
            JSONObject().apply {
                put("username", username.trim().removePrefix("@"))
                put("points", points)
                put("eventKey", eventKey.trim())
                if (note.isNotBlank()) put("note", note.trim())
            },
        ).getBoolean("applied")
    }

    private fun requestGet(path: String, accessToken: String?): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return readResponse(connection, "资源数据加载失败")
    }

    private fun requestMutation(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            readResponse(connection, "资源操作失败")
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "$fallback（HTTP $status）"
                throw IllegalStateException(message)
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.toCatalogItems(): List<NativeResourceCatalogItem> = buildList {
        if (this@toCatalogItems == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val article = value.optJSONObject("article") ?: continue
            add(
                NativeResourceCatalogItem(
                    article = NativeArticleApi.parseArticle(article),
                    minimumPointCost = value.optInt("minimumPointCost"),
                    blockCount = value.optInt("blockCount"),
                    exchangeCount = value.optInt("exchangeCount"),
                ),
            )
        }
    }

    private fun JSONObject?.toReputationLevel(): NativeReputationLevel {
        val value = this ?: JSONObject()
        return NativeReputationLevel(
            code = value.optString("code"),
            name = value.optString("name", "新成员"),
            level = value.optInt("level"),
            minExperience = value.optInt("minExperience"),
        )
    }

    private fun JSONArray?.toReputationRules(): List<NativeReputationRule> = buildList {
        if (this@toReputationRules == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeReputationRule(
                    reason = value.optString("reason"),
                    label = value.optString("label"),
                    experience = value.optInt("experience"),
                    points = value.optInt("points"),
                    dailyExperienceCap = value.optInt("dailyExperienceCap").takeIf { !value.isNull("dailyExperienceCap") },
                ),
            )
        }
    }

    private fun JSONArray?.toReputationLedgers(): List<NativeReputationLedger> = buildList {
        if (this@toReputationLedgers == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeReputationLedger(
                    id = value.optInt("id"),
                    reason = value.optString("reason"),
                    description = value.optString("description"),
                    experienceDelta = value.optInt("experienceDelta"),
                    pointDelta = value.optInt("pointDelta"),
                    experienceAfter = value.optInt("experienceAfter"),
                    pointsAfter = value.optInt("pointsAfter"),
                    pendingPointDelta = value.optInt("pendingPointDelta"),
                    availableAt = value.optString("availableAt").takeIf { it.isNotBlank() && it != "null" },
                    settledAt = value.optString("settledAt").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = value.optString("createdAt"),
                ),
            )
        }
    }

    private fun JSONArray?.toDeliveries(): List<NativeResourceDelivery> = buildList {
        if (this@toDeliveries == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(value.toDelivery())
        }
    }

    private fun JSONObject.toDeliveryPage(fallbackPage: Int, fallbackPageSize: Int): NativeResourceDeliveryPage = NativeResourceDeliveryPage(
        items = optJSONArray("items").toDeliveries(),
        total = optInt("total"),
        page = optInt("page", fallbackPage),
        pageSize = optInt("pageSize", fallbackPageSize),
        totalPages = optInt("totalPages", 1),
    )

    private fun JSONArray?.toAdminPointAdjustments(): List<NativeAdminPointAdjustment> = buildList {
        if (this@toAdminPointAdjustments == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeAdminPointAdjustment(
                    id = value.optInt("id"),
                    user = value.optJSONObject("user").toPerson(),
                    reason = value.optString("reason"),
                    eventKey = value.optString("eventKey"),
                    description = value.optString("description"),
                    pointDelta = value.optInt("pointDelta"),
                    pointsAfter = value.optInt("pointsAfter"),
                    createdAt = value.optString("createdAt"),
                ),
            )
        }
    }

    private fun JSONObject.toDelivery(): NativeResourceDelivery {
        val article = optJSONObject("article") ?: JSONObject()
        return NativeResourceDelivery(
            id = optInt("id"),
            articleId = optInt("articleId"),
            articleTitle = article.optString("title", "未命名文章"),
            articleSlug = article.optString("slug"),
            blockKey = optString("blockKey"),
            pointCost = optInt("pointCost"),
            status = optString("status", "unlocked"),
            attemptCount = optInt("attemptCount"),
            lastError = optString("lastError").takeIf { it.isNotBlank() && it != "null" },
            downloadedAt = optString("downloadedAt").takeIf { it.isNotBlank() && it != "null" },
            refundedAt = optString("refundedAt").takeIf { it.isNotBlank() && it != "null" },
            createdAt = optString("createdAt"),
            availableAt = optString("availableAt"),
            settledAt = optString("settledAt").takeIf { it.isNotBlank() && it != "null" },
            buyer = optJSONObject("buyer").toPerson(),
            author = optJSONObject("author").toPerson(),
            events = optJSONArray("events").toDeliveryEvents(),
        )
    }

    private fun JSONObject?.toPerson(): NativeResourcePerson {
        val value = this ?: JSONObject()
        return NativeResourcePerson(
            id = value.optInt("id"),
            nickname = value.optString("nickname").ifBlank { value.optString("username", "站内用户") },
            username = value.optString("username"),
        )
    }

    private fun JSONArray?.toDeliveryEvents(): List<NativeResourceDeliveryEvent> = buildList {
        if (this@toDeliveryEvents == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeResourceDeliveryEvent(
                    id = value.optInt("id"),
                    type = value.optString("type"),
                    attempt = value.optInt("attempt"),
                    detail = value.optString("detail").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = value.optString("createdAt"),
                ),
            )
        }
    }

    private fun JSONArray?.toAggregates(): List<NativeResourceAggregate> = buildList {
        if (this@toAggregates == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val article = value.optJSONObject("article") ?: JSONObject()
            add(
                NativeResourceAggregate(
                    articleId = value.optInt("articleId"),
                    articleTitle = article.optString("title", "未命名文章"),
                    articleSlug = article.optString("slug"),
                    blockKey = value.optString("blockKey"),
                    redemptionCount = value.optInt("redemptionCount"),
                    grossPoints = value.optInt("grossPoints"),
                    pendingPoints = value.optInt("pendingPoints"),
                    settledPoints = value.optInt("settledPoints"),
                    refundedPoints = value.optInt("refundedPoints"),
                ),
            )
        }
    }
}
