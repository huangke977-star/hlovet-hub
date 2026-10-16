package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class NativeAdminAnalytics(
    val range: Int,
    val generatedAt: String,
    val latestAggregateAt: String?,
    val summary: Map<String, Long>,
    val notificationReadRate: Double,
    val notificationOpenRate: Double,
    val onboardingCompleted: Long,
    val onboardingCompletionRate: Double,
    val trend: List<NativeAnalyticsTrendPoint>,
    val rankings: Map<String, List<NativeAnalyticsRankingItem>>,
    val definitions: List<NativeAnalyticsDefinition>,
)

internal data class NativeAnalyticsTrendPoint(
    val date: String,
    val metrics: Map<String, Long>,
)

internal data class NativeAnalyticsRankingItem(
    val key: String,
    val label: String,
    val secondary: String,
    val score: Double,
    val slug: String?,
)

internal data class NativeAnalyticsDefinition(
    val key: String,
    val label: String,
    val definition: String,
)

internal data class NativeAnalyticsRebuildResult(
    val days: Int,
    val completedAt: String,
)

internal data class NativeAuditActor(
    val id: Int?,
    val username: String,
    val nickname: String,
)

internal data class NativeAuditLog(
    val id: Int,
    val actor: NativeAuditActor,
    val action: String,
    val scope: String,
    val method: String,
    val path: String,
    val targetType: String?,
    val targetId: String?,
    val summary: String,
    val metadata: String,
    val ip: String,
    val userAgent: String,
    val statusCode: Int,
    val durationMs: Int,
    val createdAt: String,
)

internal data class NativeAuditLogPage(
    val items: List<NativeAuditLog>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal class NativeAdminDataHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

internal object NativeAdminDataApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun getAnalytics(accessToken: String, range: Int): NativeAdminAnalytics =
        requestJson("/analytics/admin?range=${range.coerceIn(7, 90)}", accessToken).toAnalytics()

    fun rebuildAnalytics(accessToken: String, range: Int): NativeAnalyticsRebuildResult {
        val response = requestJson(
            "/analytics/admin/rebuild",
            accessToken,
            method = "POST",
            body = JSONObject().put("range", range),
            readTimeoutMs = 120_000,
        )
        return NativeAnalyticsRebuildResult(
            days = response.optInt("days", range),
            completedAt = response.optString("completedAt"),
        )
    }

    fun listAuditLogs(
        accessToken: String,
        page: Int,
        pageSize: Int = 20,
        search: String = "",
        scope: String = "",
        result: String = "",
    ): NativeAuditLogPage {
        val query = queryString(
            "page" to page.coerceAtLeast(1).toString(),
            "pageSize" to pageSize.coerceIn(1, 100).toString(),
            "search" to search.trim().take(100),
            "scope" to scope.takeIf { it in setOf("business", "security", "server") }.orEmpty(),
            "result" to result.takeIf { it in setOf("success", "failed") }.orEmpty(),
        )
        return requestJson("/admin/audit?$query", accessToken).toAuditPage()
    }

    fun exportAuditLogs(
        accessToken: String,
        search: String = "",
        scope: String = "",
        result: String = "",
    ): ByteArray {
        val query = queryString(
            "search" to search.trim().take(100),
            "scope" to scope.takeIf { it in setOf("business", "security", "server") }.orEmpty(),
            "result" to result.takeIf { it in setOf("success", "failed") }.orEmpty(),
        )
        return requestBytes("/admin/audit/export?$query", accessToken)
    }

    private fun requestJson(
        path: String,
        accessToken: String,
        method: String = "GET",
        body: JSONObject? = null,
        readTimeoutMs: Int = 30_000,
    ): JSONObject {
        val bytes = request(path, accessToken, method, body, readTimeoutMs)
        val text = bytes.toString(Charsets.UTF_8)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun requestBytes(path: String, accessToken: String): ByteArray =
        request(path, accessToken, method = "GET", body = null, readTimeoutMs = 60_000)

    private fun request(
        path: String,
        accessToken: String,
        method: String,
        body: JSONObject?,
        readTimeoutMs: Int,
    ): ByteArray {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = readTimeoutMs
            doOutput = body != null
            setRequestProperty("Accept", "application/json, text/csv")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) {
                val message = errorMessage(bytes.toString(Charsets.UTF_8))
                throw NativeAdminDataHttpException(
                    status,
                    when (status) {
                        HttpURLConnection.HTTP_UNAUTHORIZED -> "登录状态已失效，请重新登录。"
                        HttpURLConnection.HTTP_FORBIDDEN -> "当前账号没有访问该管理功能的权限。"
                        else -> message.ifBlank { "管理数据请求失败（HTTP $status）。" }
                    },
                )
            }
            bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toAnalytics(): NativeAdminAnalytics {
        val conversion = optJSONObject("notificationConversion") ?: JSONObject()
        val onboarding = optJSONObject("onboardingConversion") ?: JSONObject()
        val rankingObject = optJSONObject("rankings") ?: JSONObject()
        return NativeAdminAnalytics(
            range = optInt("range", 30),
            generatedAt = optString("generatedAt"),
            latestAggregateAt = optNullableString("latestAggregateAt"),
            summary = (optJSONObject("summary") ?: JSONObject()).toLongMap(),
            notificationReadRate = conversion.optDouble("readRate", 0.0),
            notificationOpenRate = conversion.optDouble("openRate", 0.0),
            onboardingCompleted = onboarding.optLong("completed", 0L),
            onboardingCompletionRate = onboarding.optDouble("completionRate", 0.0),
            trend = optJSONArray("trend").toTrendPoints(),
            rankings = mapOf(
                "authors" to rankingObject.optJSONArray("authors").toRankingItems(),
                "articles" to rankingObject.optJSONArray("articles").toRankingItems(),
                "searches" to rankingObject.optJSONArray("searches").toRankingItems(),
                "subscriptionGrowth" to rankingObject.optJSONArray("subscriptionGrowth").toRankingItems(),
                "anonymousTopics" to rankingObject.optJSONArray("anonymousTopics").toRankingItems(),
            ),
            definitions = optJSONArray("definitions").toDefinitions(),
        )
    }

    private fun JSONObject.toAuditPage(): NativeAuditLogPage = NativeAuditLogPage(
        items = optJSONArray("items").toAuditLogs(),
        total = optInt("total"),
        page = optInt("page", 1),
        pageSize = optInt("pageSize", 20),
        totalPages = optInt("totalPages", 1).coerceAtLeast(1),
    )

    private fun JSONArray?.toTrendPoints(): List<NativeAnalyticsTrendPoint> = buildList {
        for (index in 0 until (this@toTrendPoints?.length() ?: 0)) {
            val item = this@toTrendPoints?.optJSONObject(index) ?: continue
            add(NativeAnalyticsTrendPoint(item.optString("date"), item.toLongMap(exclude = setOf("date"))))
        }
    }

    private fun JSONArray?.toRankingItems(): List<NativeAnalyticsRankingItem> = buildList {
        for (index in 0 until (this@toRankingItems?.length() ?: 0)) {
            val item = this@toRankingItems?.optJSONObject(index) ?: continue
            add(
                NativeAnalyticsRankingItem(
                    key = item.optString("key"),
                    label = item.optString("label"),
                    secondary = item.optString("secondary"),
                    score = item.optDouble("score", 0.0),
                    slug = item.optJSONObject("metadata")?.optNullableString("slug"),
                ),
            )
        }
    }

    private fun JSONArray?.toDefinitions(): List<NativeAnalyticsDefinition> = buildList {
        for (index in 0 until (this@toDefinitions?.length() ?: 0)) {
            val item = this@toDefinitions?.optJSONObject(index) ?: continue
            add(NativeAnalyticsDefinition(item.optString("key"), item.optString("label"), item.optString("definition")))
        }
    }

    private fun JSONArray?.toAuditLogs(): List<NativeAuditLog> = buildList {
        for (index in 0 until (this@toAuditLogs?.length() ?: 0)) {
            val item = this@toAuditLogs?.optJSONObject(index) ?: continue
            val actor = item.optJSONObject("actor") ?: JSONObject()
            val actorId = actor.optInt("id", -1).takeIf { it >= 0 }
            add(
                NativeAuditLog(
                    id = item.optInt("id"),
                    actor = NativeAuditActor(actorId, actor.optString("username"), actor.optString("nickname")),
                    action = item.optString("action"),
                    scope = item.optString("scope", "business"),
                    method = item.optString("method"),
                    path = item.optString("path"),
                    targetType = item.optNullableString("targetType"),
                    targetId = item.optNullableString("targetId"),
                    summary = item.optString("summary"),
                    metadata = jsonValueText(item.opt("metadata")),
                    ip = item.optString("ip"),
                    userAgent = item.optString("userAgent"),
                    statusCode = item.optInt("statusCode"),
                    durationMs = item.optInt("durationMs"),
                    createdAt = item.optString("createdAt"),
                ),
            )
        }
    }

    private fun JSONObject.toLongMap(exclude: Set<String> = emptySet()): Map<String, Long> = buildMap {
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in exclude) put(key, optLong(key, 0L))
        }
    }

    private fun queryString(vararg pairs: Pair<String, String>): String = pairs
        .filter { it.second.isNotBlank() }
        .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun errorMessage(body: String): String = runCatching {
        when (val message = JSONObject(body).opt("message")) {
            is JSONArray -> (0 until message.length()).mapNotNull { message.optString(it).takeIf(String::isNotBlank) }.joinToString("；")
            else -> message?.toString().orEmpty()
        }
    }.getOrDefault("")

    private fun JSONObject?.optNullableString(key: String): String? =
        this?.optString(key).orEmpty().takeIf { it.isNotBlank() && it != "null" }

    private fun jsonValueText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        else -> value.toString()
    }
}
