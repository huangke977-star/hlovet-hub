package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class NativeCacheOverview(
    val redisVersion: String,
    val keyCount: Int,
    val usedMemoryBytes: Long,
    val maxMemoryBytes: Long,
    val connectedClients: Int,
    val hitRate: Double?,
    val expiredKeys: Long,
    val evictedKeys: Long,
)

internal data class NativeCacheKeySummary(
    val key: String,
    val type: String,
    val category: String,
    val ttlSeconds: Long,
    val memoryBytes: Long?,
    val canUpdateTtl: Boolean,
)

internal data class NativeCacheKeyPage(
    val cursor: String,
    val nextCursor: String,
    val done: Boolean,
    val keys: List<NativeCacheKeySummary>,
)

internal data class NativeCacheKeyDetail(
    val summary: NativeCacheKeySummary,
    val length: Int?,
    val format: String,
    val valueText: String,
    val truncated: Boolean,
)

internal data class NativeCacheDeleteResult(
    val deletedKeys: Int,
    val revokedSessions: Int,
    val clearedLoginFailures: Int,
) {
    operator fun plus(other: NativeCacheDeleteResult) = NativeCacheDeleteResult(
        deletedKeys + other.deletedKeys, revokedSessions + other.revokedSessions,
        clearedLoginFailures + other.clearedLoginFailures,
    )
}

internal class NativeCacheBatchException(
    val completedKeys: List<String>,
    val deletion: NativeCacheDeleteResult? = null,
    val updated: List<NativeCacheKeySummary> = emptyList(),
    cause: Exception,
) : IllegalStateException(nativePhrase("已完成 ${completedKeys.size} 项，其余未确认完成：${cause.message.orEmpty()}", "${completedKeys.size} items completed; the rest are not confirmed: ${cause.message.orEmpty()}"), cause)

internal object NativeAdminCacheApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun overview(accessToken: String): NativeCacheOverview {
        val value = request("/admin/cache/overview", accessToken)
        return NativeCacheOverview(
            redisVersion = value.optString("redisVersion", "unknown"),
            keyCount = value.optInt("keyCount"),
            usedMemoryBytes = value.optLong("usedMemoryBytes"),
            maxMemoryBytes = value.optLong("maxMemoryBytes"),
            connectedClients = value.optInt("connectedClients"),
            hitRate = if (value.isNull("hitRate")) null else value.optDouble("hitRate"),
            expiredKeys = value.optLong("expiredKeys"),
            evictedKeys = value.optLong("evictedKeys"),
        )
    }

    fun listKeys(accessToken: String, cursor: String, count: Int, search: String, type: String, category: String): NativeCacheKeyPage {
        val query = buildString {
            append("?cursor=").append(URLEncoder.encode(cursor, Charsets.UTF_8.name()))
            append("&count=").append(count.coerceIn(10, 100))
            if (search.isNotBlank()) append("&search=").append(URLEncoder.encode(search, Charsets.UTF_8.name()))
            if (type.isNotBlank()) append("&type=").append(URLEncoder.encode(type, Charsets.UTF_8.name()))
            if (category.isNotBlank()) append("&category=").append(URLEncoder.encode(category, Charsets.UTF_8.name()))
        }
        val value = request("/admin/cache/keys$query", accessToken)
        return NativeCacheKeyPage(
            cursor = value.optString("cursor", cursor),
            nextCursor = value.optString("nextCursor", "0"),
            done = value.optBoolean("done"),
            keys = value.optJSONArray("keys").toSummaries(),
        )
    }

    fun inspect(accessToken: String, key: String): NativeCacheKeyDetail {
        val value = request("/admin/cache/inspect", accessToken, "POST", JSONObject().put("key", key))
        val summary = value.toSummary()
        val raw = value.opt("value")
        val text = when (raw) {
            null, JSONObject.NULL -> ""
            is JSONObject -> raw.toString(2)
            is JSONArray -> raw.toString(2)
            else -> raw.toString()
        }
        return NativeCacheKeyDetail(summary, value.optIntOrNull("length"), value.optString("format"), text, value.optBoolean("truncated"))
    }

    suspend fun delete(accessToken: String, keys: List<String>): NativeCacheDeleteResult {
        require(keys.isNotEmpty())
        var result = NativeCacheDeleteResult(0, 0, 0)
        val completed = mutableListOf<String>()
        keys.distinct().chunked(20).forEach { batch ->
            currentCoroutineContext().ensureActive()
            try {
                val value = request("/admin/cache/delete", accessToken, "POST", JSONObject().put("keys", JSONArray(batch)))
                result = NativeCacheDeleteResult(result.deletedKeys + value.optInt("deletedKeys"), result.revokedSessions + value.optInt("revokedSessions"), result.clearedLoginFailures + value.optInt("clearedLoginFailures"))
                completed.addAll(batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                throw NativeCacheBatchException(completed.toList(), deletion = result, cause = exception)
            }
        }
        return result
    }

    fun updateTtl(accessToken: String, key: String, ttlSeconds: Int): NativeCacheKeySummary {
        return request("/admin/cache/ttl", accessToken, "PATCH", JSONObject().put("key", key).put("ttlSeconds", ttlSeconds)).toSummary()
    }

    suspend fun updateTtls(accessToken: String, keys: List<String>, ttlSeconds: Int): List<NativeCacheKeySummary> {
        require(keys.isNotEmpty())
        val updated = mutableListOf<NativeCacheKeySummary>()
        keys.distinct().chunked(20).forEach { batch ->
            currentCoroutineContext().ensureActive()
            try {
                updated.addAll(requestArray("/admin/cache/ttl/bulk", accessToken, "PATCH", JSONObject().put("keys", JSONArray(batch)).put("ttlSeconds", ttlSeconds)).toSummaries())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                throw NativeCacheBatchException(updated.map { it.key }, updated = updated.toList(), cause = exception)
            }
        }
        return updated
    }

    private fun request(path: String, accessToken: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
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
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { nativePhrase("缓存管理请求失败（HTTP $status）。", "Cache request failed (HTTP $status).") })
            }
            if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestArray(path: String, accessToken: String, method: String, body: JSONObject): JSONArray {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            doOutput = true
        }
        return try {
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { nativePhrase("缓存 TTL 批量更新失败（HTTP $status）。", "Bulk TTL update failed (HTTP $status).") })
            }
            JSONArray(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toSummary() = NativeCacheKeySummary(
        key = optString("key"),
        type = optString("type"),
        category = optString("category"),
        ttlSeconds = optLong("ttlSeconds"),
        memoryBytes = optLongOrNull("memoryBytes"),
        canUpdateTtl = optBoolean("canUpdateTtl"),
    )

    private fun JSONArray?.toSummaries(): List<NativeCacheKeySummary> = buildList {
        if (this@toSummaries == null) return@buildList
        for (index in 0 until length()) add(optJSONObject(index)?.toSummary() ?: continue)
    }

    private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else optLong(name)
    private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)
}
