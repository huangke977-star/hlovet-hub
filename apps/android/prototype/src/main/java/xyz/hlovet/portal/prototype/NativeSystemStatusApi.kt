package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeSystemStatus(
    val generatedAt: String,
    val application: NativeSystemApplication,
    val database: NativeSystemDatabase,
    val redis: NativeSystemRedis,
    val storage: NativeSystemStorage,
    val backups: NativeSystemBackups,
    val monitoring: NativeSystemMonitoring,
    val reliability: NativeSystemReliability,
    val containerRuntime: NativeSystemContainerRuntime,
)

internal data class NativeSystemApplication(
    val service: String,
    val nodeVersion: String,
    val environment: String,
    val uptimeSeconds: Long,
    val rssBytes: Long,
    val heapUsedBytes: Long,
    val heapTotalBytes: Long,
)

internal data class NativeSystemDatabase(
    val connected: Boolean,
    val latencyMs: Double?,
    val version: String?,
    val sizeBytes: Long?,
    val migrationCount: Int?,
    val latestMigration: String?,
    val error: String?,
)

internal data class NativeSystemRedis(
    val connected: Boolean,
    val latencyMs: Double?,
    val version: String?,
    val keyCount: Long?,
    val usedMemoryBytes: Long?,
    val maxMemoryBytes: Long?,
    val connectedClients: Int?,
    val error: String?,
)

internal data class NativeSystemStorage(
    val totalBytes: Long,
    val totalFiles: Int,
    val items: List<NativeSystemStorageItem>,
)

internal data class NativeSystemStorageItem(
    val key: String,
    val label: String,
    val available: Boolean,
    val sizeBytes: Long,
    val fileCount: Int,
)

internal data class NativeSystemBackups(
    val available: Boolean,
    val totalBytes: Long,
    val fileCount: Int,
    val latest: NativeSystemBackup?,
    val items: List<NativeSystemBackup>,
)

internal fun NativeSystemBackups.withCreatedBackup(backup: NativeSystemBackup): NativeSystemBackups {
    val merged = (items.filterNot { it.name == backup.name } + backup).sortedByDescending { it.updatedAt }
    return copy(available = true, items = merged, latest = merged.firstOrNull(), fileCount = merged.size,
        totalBytes = merged.sumOf { it.sizeBytes + (it.mediaSnapshotSizeBytes ?: 0L) })
}

internal data class NativeSystemBackup(
    val name: String,
    val sizeBytes: Long,
    val mediaSnapshotAvailable: Boolean,
    val mediaSnapshotSizeBytes: Long?,
    val verificationStatus: String,
    val updatedAt: String,
)

internal data class NativeSystemMonitoring(
    val retentionMinutes: Int,
    val slowRequestThresholdMs: Double,
    val slowRequests: List<NativeSystemHttpEvent>,
    val recentErrors: List<NativeSystemHttpEvent>,
    val recentClientErrors: List<NativeSystemClientError>,
    val memoryTrend: List<NativeSystemMemoryPoint>,
    val diskTrend: List<NativeSystemDiskPoint>,
)

internal data class NativeSystemHttpEvent(
    val occurredAt: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val durationMs: Double,
    val message: String?,
)

internal data class NativeSystemClientError(
    val occurredAt: String,
    val source: String,
    val message: String,
    val path: String,
)

internal data class NativeSystemMemoryPoint(
    val recordedAt: String,
    val rssBytes: Long,
    val heapUsedBytes: Long,
)

internal data class NativeSystemDiskPoint(
    val recordedAt: String,
    val usedBytes: Long,
    val capacityBytes: Long,
    val usedPercent: Double,
)

internal data class NativeSystemReliability(
    val backedUpFiles: Int,
    val totalFiles: Int,
    val uncoveredFiles: Int,
    val coveragePercentage: Double?,
    val lastSuccessfulBackupAt: String?,
    val lastSuccessfulBackupSource: String?,
    val anomalyWindowHours: Int,
    val anomalyTotal: Int,
    val backupFailures: Int,
    val diskPressure: Int,
    val missingFiles: Int,
    val orphanFiles: Int,
    val metadataMismatches: Int,
    val recentApiErrors: Int,
    val latestScanAt: String?,
    val diskUsedPercent: Double?,
    val warningThresholdPercent: Double,
)

internal data class NativeSystemContainerRuntime(
    val connected: Boolean,
    val message: String,
)

internal class NativeSystemStatusHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

internal object NativeSystemStatusApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun getStatus(accessToken: String): NativeSystemStatus =
        request("/admin/system/status", accessToken).toSystemStatus()

    fun createBackup(accessToken: String): NativeSystemBackup =
        request("/admin/system/backups", accessToken, "POST").toBackup()

    private fun request(path: String, accessToken: String, method: String = "GET"): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = if (method == "POST") 120_000 else 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val serverMessage = runCatching { JSONObject(body).messageText() }.getOrNull().orEmpty()
                val message = when (status) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Please sign in again.")
                    HttpURLConnection.HTTP_FORBIDDEN -> nativePhrase("当前账号没有系统概况权限。", "This account cannot access system status.")
                    else -> serverMessage.ifBlank { nativePhrase("系统概况请求失败（HTTP $status）。", "System status request failed (HTTP $status).") }
                }
                throw NativeSystemStatusHttpException(status, message)
            }
            if (body.isBlank()) throw IllegalStateException(nativePhrase("系统概况返回为空。", "The system status response is empty."))
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun JSONObject.toSystemStatus(): NativeSystemStatus {
        val application = optJSONObject("application") ?: JSONObject()
        val database = optJSONObject("database") ?: JSONObject()
        val redis = optJSONObject("redis") ?: JSONObject()
        val storage = optJSONObject("storage") ?: JSONObject()
        val backups = optJSONObject("backups") ?: JSONObject()
        val monitoring = optJSONObject("monitoring") ?: JSONObject()
        val reliability = optJSONObject("reliability") ?: JSONObject()
        val memory = application.optJSONObject("memory") ?: JSONObject()
        val anomalies = reliability.optJSONObject("anomalies") ?: JSONObject()
        val coverage = reliability.optJSONObject("backupCoverage") ?: JSONObject()
        val reliabilityStorage = reliability.optJSONObject("storage") ?: JSONObject()
        val container = optJSONObject("containerRuntime") ?: JSONObject()

        return NativeSystemStatus(
            generatedAt = optString("generatedAt"),
            application = NativeSystemApplication(
                service = application.optString("service", "hlovet-api"),
                nodeVersion = application.optString("nodeVersion", "-"),
                environment = application.optString("environment", "-"),
                uptimeSeconds = application.optLong("uptimeSeconds", 0L),
                rssBytes = memory.optLong("rssBytes", 0L),
                heapUsedBytes = memory.optLong("heapUsedBytes", 0L),
                heapTotalBytes = memory.optLong("heapTotalBytes", 0L),
            ),
            database = NativeSystemDatabase(
                connected = database.optBoolean("connected"),
                latencyMs = database.optNullableDouble("latencyMs"),
                version = database.optNullableString("version"),
                sizeBytes = database.optNullableLong("sizeBytes"),
                migrationCount = database.optNullableInt("migrationCount"),
                latestMigration = database.optJSONObject("latestMigration")?.let {
                    listOf(it.optString("name"), it.optNullableString("finishedAt").orEmpty())
                        .filter(String::isNotBlank)
                        .joinToString(" · ")
                },
                error = database.optNullableString("error"),
            ),
            redis = NativeSystemRedis(
                connected = redis.optBoolean("connected"),
                latencyMs = redis.optNullableDouble("latencyMs"),
                version = redis.optNullableString("version"),
                keyCount = redis.optNullableLong("keyCount"),
                usedMemoryBytes = redis.optNullableLong("usedMemoryBytes"),
                maxMemoryBytes = redis.optNullableLong("maxMemoryBytes"),
                connectedClients = redis.optNullableInt("connectedClients"),
                error = redis.optNullableString("error"),
            ),
            storage = NativeSystemStorage(
                totalBytes = storage.optLong("totalBytes", 0L),
                totalFiles = storage.optInt("totalFiles", 0),
                items = storage.optJSONArray("items").toStorageItems(),
            ),
            backups = NativeSystemBackups(
                available = backups.optBoolean("available"),
                totalBytes = backups.optLong("totalBytes", 0L),
                fileCount = backups.optInt("fileCount", 0),
                latest = backups.optJSONObject("latest")?.toBackup(),
                items = backups.optJSONArray("items").toBackups(),
            ),
            monitoring = NativeSystemMonitoring(
                retentionMinutes = monitoring.optInt("retentionMinutes", 60),
                slowRequestThresholdMs = monitoring.optDouble("slowRequestThresholdMs", 0.0),
                slowRequests = monitoring.optJSONArray("slowRequests").toHttpEvents(),
                recentErrors = monitoring.optJSONArray("recentErrors").toHttpEvents(),
                recentClientErrors = monitoring.optJSONArray("recentClientErrors").toClientErrors(),
                memoryTrend = monitoring.optJSONArray("memoryTrend").toMemoryPoints(),
                diskTrend = monitoring.optJSONArray("diskTrend").toDiskPoints(),
            ),
            reliability = NativeSystemReliability(
                backedUpFiles = coverage.optInt("backedUpFiles", 0),
                totalFiles = coverage.optInt("totalFiles", 0),
                uncoveredFiles = coverage.optInt("uncoveredFiles", 0),
                coveragePercentage = coverage.optNullableDouble("percentage"),
                lastSuccessfulBackupAt = reliability.optNullableString("lastSuccessfulBackupAt"),
                lastSuccessfulBackupSource = reliability.optNullableString("lastSuccessfulBackupSource"),
                anomalyWindowHours = reliability.optInt("anomalyWindowHours", 24),
                anomalyTotal = anomalies.optInt("total", 0),
                backupFailures = anomalies.optInt("backupFailures", 0),
                diskPressure = anomalies.optInt("diskPressure", 0),
                missingFiles = anomalies.optInt("missingFiles", 0),
                orphanFiles = anomalies.optInt("orphanFiles", 0),
                metadataMismatches = anomalies.optInt("metadataMismatches", 0),
                recentApiErrors = anomalies.optInt("recentApiErrors", 0),
                latestScanAt = reliabilityStorage.optNullableString("latestScanAt"),
                diskUsedPercent = reliabilityStorage.optNullableDouble("diskUsedPercent"),
                warningThresholdPercent = reliabilityStorage.optDouble("warningThresholdPercent", 85.0),
            ),
            containerRuntime = NativeSystemContainerRuntime(
                connected = container.optBoolean("connected"),
                message = container.optString("message", nativePhrase("容器运行状态不可用。", "Container runtime is unavailable.")),
            ),
        )
    }

    private fun JSONObject?.optLong(key: String, fallback: Long): Long =
        this?.optLong(key, fallback) ?: fallback

    private fun JSONObject?.optInt(key: String, fallback: Int): Int =
        this?.optInt(key, fallback) ?: fallback

    private fun JSONObject?.optNullableString(key: String): String? =
        this?.optString(key).orEmpty().takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject?.optNullableLong(key: String): Long? =
        if (this == null || !has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject?.optNullableInt(key: String): Int? =
        if (this == null || !has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject?.optNullableDouble(key: String): Double? =
        if (this == null || !has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

    private fun JSONObject?.messageText(): String {
        val value = this?.opt("message") ?: return ""
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.joinToString("；")
            else -> value.toString()
        }
    }

    private fun JSONArray?.toStorageItems(): List<NativeSystemStorageItem> = buildList {
        for (index in 0 until (this@toStorageItems?.length() ?: 0)) {
            val item = this@toStorageItems?.optJSONObject(index) ?: continue
            add(
                NativeSystemStorageItem(
                    key = item.optString("key"),
                    label = item.optString("label", nativePhrase("存储目录", "Storage directory")),
                    available = item.optBoolean("available"),
                    sizeBytes = item.optLong("sizeBytes", 0L),
                    fileCount = item.optInt("fileCount", 0),
                ),
            )
        }
    }

    private fun JSONArray?.toBackups(): List<NativeSystemBackup> = buildList {
        for (index in 0 until (this@toBackups?.length() ?: 0)) {
            this@toBackups?.optJSONObject(index)?.toBackup()?.let(::add)
        }
    }

    private fun JSONObject.toBackup(): NativeSystemBackup = NativeSystemBackup(
        name = optString("name", nativePhrase("未命名备份", "Unnamed backup")),
        sizeBytes = optLong("sizeBytes", 0L),
        mediaSnapshotAvailable = optBoolean("mediaSnapshotAvailable"),
        mediaSnapshotSizeBytes = optNullableLong("mediaSnapshotSizeBytes"),
        verificationStatus = optJSONObject("verification")?.optString("status", "not_verified") ?: "not_verified",
        updatedAt = optString("updatedAt"),
    )

    private fun JSONArray?.toHttpEvents(): List<NativeSystemHttpEvent> = buildList {
        for (index in 0 until (this@toHttpEvents?.length() ?: 0)) {
            val item = this@toHttpEvents?.optJSONObject(index) ?: continue
            add(
                NativeSystemHttpEvent(
                    occurredAt = item.optString("occurredAt"),
                    method = item.optString("method", "-"),
                    path = item.optString("path", "-"),
                    statusCode = item.optInt("statusCode", 0),
                    durationMs = item.optDouble("durationMs", 0.0),
                    message = item.optNullableString("message"),
                ),
            )
        }
    }

    private fun JSONArray?.toClientErrors(): List<NativeSystemClientError> = buildList {
        for (index in 0 until (this@toClientErrors?.length() ?: 0)) {
            val item = this@toClientErrors?.optJSONObject(index) ?: continue
            add(
                NativeSystemClientError(
                    occurredAt = item.optString("occurredAt"),
                    source = item.optString("source", "client"),
                    message = item.optString("message", nativePhrase("前端错误", "Client error")),
                    path = item.optString("path", "-"),
                ),
            )
        }
    }

    private fun JSONArray?.toMemoryPoints(): List<NativeSystemMemoryPoint> = buildList {
        for (index in 0 until (this@toMemoryPoints?.length() ?: 0)) {
            val item = this@toMemoryPoints?.optJSONObject(index) ?: continue
            add(NativeSystemMemoryPoint(item.optString("recordedAt"), item.optLong("rssBytes"), item.optLong("heapUsedBytes")))
        }
    }

    private fun JSONArray?.toDiskPoints(): List<NativeSystemDiskPoint> = buildList {
        for (index in 0 until (this@toDiskPoints?.length() ?: 0)) {
            val item = this@toDiskPoints?.optJSONObject(index) ?: continue
            add(
                NativeSystemDiskPoint(
                    recordedAt = item.optString("recordedAt"),
                    usedBytes = item.optLong("usedBytes"),
                    capacityBytes = item.optLong("capacityBytes"),
                    usedPercent = item.optDouble("usedPercent", 0.0),
                ),
            )
        }
    }
}
