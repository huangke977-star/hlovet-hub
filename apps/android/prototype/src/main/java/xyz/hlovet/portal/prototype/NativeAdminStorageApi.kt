package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class NativeStorageConfiguration(
    val automaticScanEnabled: Boolean,
    val scanTime: String,
    val timezone: String,
    val trashRetentionDays: Int,
    val warningThresholdPercent: Int,
    val nextRunAt: String?,
    val lastScheduledScanDate: String?,
    val lastScanAt: String?,
    val lastWarningAt: String?,
)

internal data class NativeStorageCategorySummary(
    val key: String,
    val label: String,
    val available: Boolean,
    val sizeBytes: Long,
    val fileCount: Int,
    val referencedCount: Int,
    val healthyCount: Int,
    val missingCount: Int,
    val orphanCount: Int,
    val mismatchCount: Int,
    val trashCount: Int,
    val trashBytes: Long,
)

internal data class NativeStorageScanSummary(
    val capacityBytes: Long?,
    val usedBytes: Long?,
    val availableBytes: Long?,
    val usedPercent: Double?,
    val totalBytes: Long,
    val totalFiles: Int,
    val referencedCount: Int,
    val healthyCount: Int,
    val missingCount: Int,
    val orphanCount: Int,
    val mismatchCount: Int,
    val protectedTemporaryCount: Int,
    val trashCount: Int,
    val trashBytes: Long,
    val categories: List<NativeStorageCategorySummary>,
)

internal data class NativeStorageScan(
    val id: Int,
    val status: String,
    val trigger: String,
    val summary: NativeStorageScanSummary?,
    val error: String?,
    val startedAt: String,
    val completedAt: String?,
)

internal data class NativeStorageOverview(
    val configuration: NativeStorageConfiguration,
    val latestScan: NativeStorageScan?,
    val openMissing: Int,
    val openOrphan: Int,
    val openMismatch: Int,
    val trashCount: Int,
    val trashSizeBytes: Long,
    val expiredTrashCount: Int,
)

internal data class NativeStorageIssue(
    val id: Int,
    val scanId: Int,
    val kind: String,
    val category: String,
    val categoryLabel: String,
    val storedName: String,
    val mimeType: String?,
    val expectedSizeBytes: Long?,
    val actualSizeBytes: Long?,
    val sourceType: String,
    val sourceId: String?,
    val sourceLabel: String,
    val sourceUrl: String?,
    val uploadedBy: String?,
    val fileUpdatedAt: String?,
    val previewable: Boolean,
    val canTrash: Boolean,
    val createdAt: String,
)

internal data class NativeStorageIssuePage(
    val items: List<NativeStorageIssue>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val pageCount: Int,
    val scan: NativeStorageScan?,
)

internal data class NativeStorageRepair(
    val id: Int,
    val issueId: Int?,
    val category: String,
    val storedName: String,
    val action: String,
    val status: String,
    val provider: String?,
    val note: String?,
    val error: String?,
    val startedAt: String,
    val completedAt: String?,
)

internal data class NativeStorageTrashItem(
    val id: Int,
    val category: String,
    val categoryLabel: String,
    val originalStoredName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val trashedById: Int?,
    val deletedAt: String,
    val purgeAfter: String,
)

internal data class NativeStorageTrashPage(
    val items: List<NativeStorageTrashItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val pageCount: Int,
)

internal data class NativeStorageFile(
    val bytes: ByteArray,
    val mimeType: String,
)

internal class NativeAdminStorageHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

internal object NativeAdminStorageApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"
    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun overview(accessToken: String): NativeStorageOverview = request("/admin/system/storage", accessToken).toOverview()

    fun startScan(accessToken: String): NativeStorageScan = request("/admin/system/storage/scans", accessToken, "POST").toScan()

    fun scan(accessToken: String, id: Int): NativeStorageScan = request("/admin/system/storage/scans/$id", accessToken).toScan()

    fun issues(accessToken: String, page: Int, pageSize: Int, kind: String, category: String, query: String): NativeStorageIssuePage {
        val params = buildList {
            add("page=${page.coerceAtLeast(1)}")
            add("pageSize=${pageSize.coerceIn(1, 50)}")
            if (kind.isNotBlank()) add("kind=${encode(kind)}")
            if (category.isNotBlank()) add("category=${encode(category)}")
            if (query.isNotBlank()) add("q=${encode(query.trim())}")
        }.joinToString("&")
        return request("/admin/system/storage/issues?$params", accessToken).toIssuePage()
    }

    fun issueFile(accessToken: String, id: Int): NativeStorageFile {
        val connection = open("/admin/system/storage/issues/$id/file", accessToken, "GET")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) throw NativeAdminStorageHttpException(status, errorText(bytes, "文件读取失败（HTTP $status）。"))
            NativeStorageFile(bytes, connection.contentType?.substringBefore(';').orEmpty().ifBlank { "application/octet-stream" })
        } finally { connection.disconnect() }
    }

    fun trashIssue(accessToken: String, id: Int): NativeStorageTrashItem = request("/admin/system/storage/issues/$id/trash", accessToken, "POST").toTrashItem()

    fun restoreRemote(accessToken: String, id: Int): NativeStorageRepair = request("/admin/system/storage/issues/$id/restore-remote", accessToken, "POST", JSONObject()).toRepair()

    fun reupload(accessToken: String, id: Int, file: NativeUploadFile): NativeStorageRepair {
        val boundary = "----HlovetAndroidStorage${System.currentTimeMillis()}"
        val connection = open("/admin/system/storage/issues/$id/reupload", accessToken, "POST").apply {
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                val safeName = file.name.replace("\"", "").replace("\r", "").replace("\n", "")
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray())
                output.write("Content-Type: ${file.mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n".toByteArray())
                output.write(file.bytes)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            readJson(connection, "替换文件上传失败").toRepair()
        } finally { connection.disconnect() }
    }

    fun confirmUnrecoverable(accessToken: String, id: Int, note: String): NativeStorageRepair = request(
        "/admin/system/storage/issues/$id/confirm-unrecoverable", accessToken, "POST", JSONObject().put("note", note),
    ).toRepair()

    fun repairs(accessToken: String, id: Int): List<NativeStorageRepair> = requestArray("/admin/system/storage/issues/$id/repairs", accessToken).toRepairs()

    fun trash(accessToken: String, page: Int, pageSize: Int, category: String): NativeStorageTrashPage {
        val query = "page=${page.coerceAtLeast(1)}&pageSize=${pageSize.coerceIn(1, 50)}" + if (category.isBlank()) "" else "&category=${encode(category)}"
        return request("/admin/system/storage/trash?$query", accessToken).toTrashPage()
    }

    fun restoreTrash(accessToken: String, id: Int) { request("/admin/system/storage/trash/$id/restore", accessToken, "POST") }

    fun deleteTrash(accessToken: String, id: Int) { request("/admin/system/storage/trash/$id", accessToken, "DELETE") }

    fun updateConfiguration(accessToken: String, configuration: NativeStorageConfiguration): NativeStorageConfiguration = request(
        "/admin/system/storage/configuration", accessToken, "POST", JSONObject().apply {
            put("automaticScanEnabled", configuration.automaticScanEnabled)
            put("scanTime", configuration.scanTime)
            put("trashRetentionDays", configuration.trashRetentionDays.coerceIn(1, 90))
            put("warningThresholdPercent", configuration.warningThresholdPercent.coerceIn(50, 95))
        },
    ).toConfiguration()

    private fun request(path: String, accessToken: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val connection = open(path, accessToken, method).apply {
            doOutput = body != null
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            readJson(connection, "存储管理请求失败").also { connection.disconnect() }
        } catch (exception: Throwable) {
            connection.disconnect()
            throw exception
        }
    }

    private fun open(path: String, accessToken: String, method: String): HttpURLConnection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = if (method == "POST") 120_000 else 30_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
    }

    private fun readJson(connection: HttpURLConnection, fallback: String): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(body).messageText() }.getOrNull().orEmpty()
            throw NativeAdminStorageHttpException(status, message.ifBlank { "$fallback（HTTP $status）。" })
        }
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    private fun requestArray(path: String, accessToken: String): JSONArray {
        val connection = open(path, accessToken, "GET")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).messageText() }.getOrNull().orEmpty()
                throw NativeAdminStorageHttpException(status, message.ifBlank { "修复记录读取失败（HTTP $status）。" })
            }
            if (body.isBlank()) JSONArray() else JSONArray(body)
        } finally { connection.disconnect() }
    }

    private fun errorText(bytes: ByteArray, fallback: String): String = runCatching { JSONObject(String(bytes)).messageText() }.getOrNull().orEmpty().ifBlank { fallback }
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JSONObject.toOverview(): NativeStorageOverview {
        val open = optJSONObject("openIssues") ?: JSONObject()
        val trash = optJSONObject("trash") ?: JSONObject()
        return NativeStorageOverview(
            configuration = optJSONObject("configuration").toConfiguration(),
            latestScan = optJSONObject("latestScan")?.toScan(),
            openMissing = open.optInt("missing"),
            openOrphan = open.optInt("orphan"),
            openMismatch = open.optInt("metadataMismatch"),
            trashCount = trash.optInt("count"),
            trashSizeBytes = trash.optLong("sizeBytes"),
            expiredTrashCount = trash.optInt("expiredCount"),
        )
    }

    private fun JSONObject?.toConfiguration(): NativeStorageConfiguration {
        val value = this ?: JSONObject()
        return NativeStorageConfiguration(
            automaticScanEnabled = value.optBoolean("automaticScanEnabled"),
            scanTime = value.optString("scanTime", "03:00"),
            timezone = value.optString("timezone", "UTC"),
            trashRetentionDays = value.optInt("trashRetentionDays", 30),
            warningThresholdPercent = value.optInt("warningThresholdPercent", 75),
            nextRunAt = value.optNullableString("nextRunAt"),
            lastScheduledScanDate = value.optNullableString("lastScheduledScanDate"),
            lastScanAt = value.optNullableString("lastScanAt"),
            lastWarningAt = value.optNullableString("lastWarningAt"),
        )
    }

    private fun JSONObject.toScan(): NativeStorageScan = NativeStorageScan(
        id = optInt("id"), status = optString("status", "failed"), trigger = optString("trigger"),
        summary = optJSONObject("summary")?.toSummary(), error = optNullableString("error"),
        startedAt = optString("startedAt"), completedAt = optNullableString("completedAt"),
    )

    private fun JSONObject.toSummary(): NativeStorageScanSummary = NativeStorageScanSummary(
        capacityBytes = optNullableLong("capacityBytes"), usedBytes = optNullableLong("usedBytes"), availableBytes = optNullableLong("availableBytes"), usedPercent = optNullableDouble("usedPercent"),
        totalBytes = optLong("totalBytes"), totalFiles = optInt("totalFiles"), referencedCount = optInt("referencedCount"), healthyCount = optInt("healthyCount"), missingCount = optInt("missingCount"), orphanCount = optInt("orphanCount"), mismatchCount = optInt("mismatchCount"), protectedTemporaryCount = optInt("protectedTemporaryCount"), trashCount = optInt("trashCount"), trashBytes = optLong("trashBytes"), categories = optJSONArray("categories").toCategories(),
    )

    private fun JSONArray?.toCategories(): List<NativeStorageCategorySummary> = buildList {
        for (index in 0 until (this@toCategories?.length() ?: 0)) {
            val item = this@toCategories?.optJSONObject(index) ?: continue
            add(NativeStorageCategorySummary(item.optString("key"), item.optString("label"), item.optBoolean("available"), item.optLong("sizeBytes"), item.optInt("fileCount"), item.optInt("referencedCount"), item.optInt("healthyCount"), item.optInt("missingCount"), item.optInt("orphanCount"), item.optInt("mismatchCount"), item.optInt("trashCount"), item.optLong("trashBytes")))
        }
    }

    private fun JSONObject.toIssue(): NativeStorageIssue = NativeStorageIssue(
        id = optInt("id"), scanId = optInt("scanId"), kind = optString("kind"), category = optString("category"), categoryLabel = optString("categoryLabel"), storedName = optString("storedName"), mimeType = optNullableString("mimeType"), expectedSizeBytes = optNullableLong("expectedSizeBytes"), actualSizeBytes = optNullableLong("actualSizeBytes"), sourceType = optString("sourceType"), sourceId = optNullableString("sourceId"), sourceLabel = optString("sourceLabel"), sourceUrl = optNullableString("sourceUrl"), uploadedBy = optNullableString("uploadedBy"), fileUpdatedAt = optNullableString("fileUpdatedAt"), previewable = optBoolean("previewable"), canTrash = optBoolean("canTrash"), createdAt = optString("createdAt"),
    )

    private fun JSONObject.toIssuePage(): NativeStorageIssuePage = NativeStorageIssuePage(optJSONArray("items").toIssues(), optInt("total"), optInt("page", 1), optInt("pageSize", 20), optInt("pageCount"), optJSONObject("scan")?.toScan())
    private fun JSONArray?.toIssues(): List<NativeStorageIssue> = buildList { for (index in 0 until (this@toIssues?.length() ?: 0)) this@toIssues?.optJSONObject(index)?.let { add(it.toIssue()) } }
    private fun JSONObject.toRepair(): NativeStorageRepair = NativeStorageRepair(optInt("id"), optNullableInt("issueId"), optString("category"), optString("storedName"), optString("action"), optString("status"), optNullableString("provider"), optNullableString("note"), optNullableString("error"), optString("startedAt"), optNullableString("completedAt"))
    private fun JSONArray?.toRepairs(): List<NativeStorageRepair> = buildList { for (index in 0 until (this@toRepairs?.length() ?: 0)) this@toRepairs?.optJSONObject(index)?.let { add(it.toRepair()) } }
    private fun JSONObject.toTrashItem(): NativeStorageTrashItem = NativeStorageTrashItem(optInt("id"), optString("category"), optString("categoryLabel"), optString("originalStoredName"), optNullableString("mimeType"), optLong("sizeBytes"), optNullableInt("trashedById"), optString("deletedAt"), optString("purgeAfter"))
    private fun JSONObject.toTrashPage(): NativeStorageTrashPage = NativeStorageTrashPage(optJSONArray("items").toTrashItems(), optInt("total"), optInt("page", 1), optInt("pageSize", 20), optInt("pageCount"))
    private fun JSONArray?.toTrashItems(): List<NativeStorageTrashItem> = buildList { for (index in 0 until (this@toTrashItems?.length() ?: 0)) this@toTrashItems?.optJSONObject(index)?.let { add(it.toTrashItem()) } }
    private fun JSONObject?.optNullableString(key: String): String? = this?.optString(key).orEmpty().takeIf { it.isNotBlank() && it != "null" }
    private fun JSONObject?.optNullableLong(key: String): Long? = this?.takeIf { it.has(key) && !it.isNull(key) }?.optLong(key)
    private fun JSONObject?.optNullableInt(key: String): Int? = this?.takeIf { it.has(key) && !it.isNull(key) }?.optInt(key)
    private fun JSONObject?.optNullableDouble(key: String): Double? = this?.takeIf { it.has(key) && !it.isNull(key) }?.optDouble(key)
    private fun JSONObject?.messageText(): String {
        val value = this?.opt("message") ?: return ""
        return if (value is JSONArray) (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.joinToString("；") else value.toString()
    }
}
