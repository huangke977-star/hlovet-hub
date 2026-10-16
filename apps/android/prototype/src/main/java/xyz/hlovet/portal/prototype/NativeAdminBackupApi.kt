package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class NativeDatabaseBackup(
    val name: String,
    val sizeBytes: Long,
    val mediaSnapshotAvailable: Boolean,
    val mediaSnapshotSizeBytes: Long?,
    val verificationStatus: String,
    val updatedAt: String,
    val verificationError: String? = null,
    val warning: String? = null,
)

internal data class NativeBackupRestorePreflight(
    val backup: NativeDatabaseBackup,
    val canRestore: Boolean,
    val warnings: List<String>,
)

internal data class NativeBackupProvider(
    val enabled: Boolean,
    val region: String = "",
    val endpoint: String = "",
    val accountId: String = "",
    val bucket: String = "",
    val prefix: String = "",
    val hasAccessKeyId: Boolean = false,
    val hasSecretAccessKey: Boolean = false,
)

internal data class NativeBackupConfiguration(
    val automaticEnabled: Boolean,
    val scheduleTime: String,
    val timezone: String,
    val localRetentionDays: Int,
    val remoteRetentionDays: Int,
    val encryptionConfigured: Boolean,
    val nextRunAt: String?,
    val lastAutomaticBackupDate: String?,
    val lastMediaBackupDate: String?,
    val lastSuccessAt: String?,
    val lastFailureAt: String?,
    val lastFailureMessage: String?,
    val lastBackupName: String?,
    val oss: NativeBackupProvider,
    val r2: NativeBackupProvider,
)

internal data class NativeBackupConfigurationForm(
    val automaticEnabled: Boolean,
    val scheduleTime: String,
    val localRetentionDays: String,
    val remoteRetentionDays: String,
    val ossEnabled: Boolean,
    val ossRegion: String,
    val ossEndpoint: String,
    val ossBucket: String,
    val ossPrefix: String,
    val ossAccessKeyId: String,
    val ossAccessKeySecret: String,
    val clearOssCredentials: Boolean,
    val r2Enabled: Boolean,
    val r2AccountId: String,
    val r2Bucket: String,
    val r2Prefix: String,
    val r2AccessKeyId: String,
    val r2SecretAccessKey: String,
    val clearR2Credentials: Boolean,
)

internal data class NativeMediaBackupJob(
    val id: Int,
    val status: String,
    val trigger: String,
    val providers: List<String>,
    val totalFiles: Int,
    val processedFiles: Int,
    val uploadedFiles: Int,
    val reusedFiles: Int,
    val skippedFiles: Int,
    val failedFiles: Int,
    val totalBytes: Long,
    val uploadedBytes: Long,
    val error: String?,
    val startedAt: String?,
    val completedAt: String?,
    val createdAt: String,
)

internal data class NativeMediaBackupManifest(
    val storedName: String,
    val category: String,
    val provider: String,
    val status: String,
    val sizeBytes: Long,
    val objectKey: String?,
    val error: String?,
)

internal data class NativeMediaBackupLog(
    val level: String,
    val event: String,
    val message: String,
    val provider: String?,
    val createdAt: String,
)

internal data class NativeMediaBackupJobDetail(
    val job: NativeMediaBackupJob,
    val manifests: List<NativeMediaBackupManifest>,
    val logs: List<NativeMediaBackupLog>,
)

internal data class NativeMediaBackupJobPage(
    val items: List<NativeMediaBackupJob>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val pageCount: Int,
)

internal data class NativeMediaBackupFile(
    val id: Int,
    val category: String,
    val storedName: String,
    val sourceType: String,
    val sourceId: String?,
    val sourceLabel: String,
    val sizeBytes: Long,
    val contentHash: String?,
    val fileUpdatedAt: String?,
    val lastSeenAt: String,
    val lastBackedUpAt: String?,
)

internal data class NativeMediaBackupFilePage(
    val items: List<NativeMediaBackupFile>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val pageCount: Int,
)

internal data class NativeDownloadedBackup(val bytes: ByteArray, val contentType: String)

internal data class NativeConfigurationImportPreview(
    val bundle: JSONObject,
    val generatedAt: String,
    val sections: List<String>,
)

internal fun previewNativeConfigurationImport(bundle: JSONObject, locale: String? = NativeI18n.locale): NativeConfigurationImportPreview {
    require(bundle.optString("format") == "hlovet-configuration" && bundle.opt("version") == 1) {
        "配置包格式或版本不受支持，请选择当前 HLOVET 导出的 JSON 配置包。"
    }
    val data = bundle.optJSONObject("data") ?: throw IllegalArgumentException("配置包缺少配置数据。")
    val singletonLabels = linkedMapOf(
        "siteSetting" to "站点设置",
        "securityConfiguration" to "安全设置（邮箱、Google 登录和验证码策略）",
        "aiConfiguration" to "AI 主模型与上下文设置",
        "backupConfiguration" to "备份策略与远端凭据",
        "storageConfiguration" to "存储策略",
        "moderationSetting" to "违规处理策略",
        "auditRetentionPolicy" to "审计留存策略",
    )
    val sections = buildList {
        singletonLabels.forEach { (key, label) ->
            if ((data.optJSONObject(key)?.length() ?: 0) > 0) add(label)
        }
        val capabilities = data.optJSONArray("aiCapabilities")
        val capabilityCount = (0 until (capabilities?.length() ?: 0)).count { index ->
            (capabilities?.optJSONObject(index)?.opt("capability") as? String)?.trim()?.isNotEmpty() == true
        }
        if (capabilityCount > 0) add(nativePhraseForLocale(locale, "AI 能力配置 $capabilityCount 项", "AI capability settings: $capabilityCount"))
        val taxonomies = data.optJSONArray("articleTaxonomies")
        val taxonomyCount = (0 until (taxonomies?.length() ?: 0)).count { index ->
            val item = taxonomies?.optJSONObject(index)
            (item?.opt("kind") as? String)?.trim()?.isNotEmpty() == true && (item.opt("name") as? String)?.trim()?.isNotEmpty() == true
        }
        if (taxonomyCount > 0) add(nativePhraseForLocale(locale, "文章分类和标签 $taxonomyCount 项（按名称合并）", "Article categories and tags: $taxonomyCount (merged by name)"))
    }
    require(sections.isNotEmpty()) { "配置包中没有可导入的配置。" }
    return NativeConfigurationImportPreview(bundle, bundle.optString("generatedAt"), sections)
}

internal fun nativeBackupRetentionDays(value: String, remote: Boolean, locale: String? = NativeI18n.locale): Int {
    val days = value.toIntOrNull()
    val maxDays = if (remote) 3650 else 365
    require(days != null && days in 1..maxDays) {
        nativePhraseForLocale(
            locale,
            "${if (remote) "远端" else "本地"}保留天数应为 1 到 $maxDays 的整数。",
            "${if (remote) "Remote" else "Local"} retention must be an integer from 1 to $maxDays.",
        )
    }
    return days
}

internal class NativeAdminBackupHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

internal object NativeAdminBackupApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"
    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun getConfiguration(token: String): NativeBackupConfiguration = request("/admin/system/backups/configuration", token).toConfiguration()

    fun updateConfiguration(token: String, form: NativeBackupConfigurationForm): NativeBackupConfiguration = request(
        "/admin/system/backups/configuration", token, "POST", JSONObject().apply {
            put("automaticEnabled", form.automaticEnabled)
            put("scheduleTime", form.scheduleTime.trim())
            put("localRetentionDays", nativeBackupRetentionDays(form.localRetentionDays, remote = false))
            put("remoteRetentionDays", nativeBackupRetentionDays(form.remoteRetentionDays, remote = true))
            put("ossEnabled", form.ossEnabled)
            put("ossRegion", form.ossRegion.trim())
            put("ossEndpoint", form.ossEndpoint.trim())
            put("ossBucket", form.ossBucket.trim())
            put("ossPrefix", form.ossPrefix.trim())
            if (form.ossAccessKeyId.isNotBlank()) put("ossAccessKeyId", form.ossAccessKeyId.trim())
            if (form.ossAccessKeySecret.isNotBlank()) put("ossAccessKeySecret", form.ossAccessKeySecret)
            put("clearOssCredentials", form.clearOssCredentials)
            put("r2Enabled", form.r2Enabled)
            put("r2AccountId", form.r2AccountId.trim())
            put("r2Bucket", form.r2Bucket.trim())
            put("r2Prefix", form.r2Prefix.trim())
            if (form.r2AccessKeyId.isNotBlank()) put("r2AccessKeyId", form.r2AccessKeyId.trim())
            if (form.r2SecretAccessKey.isNotBlank()) put("r2SecretAccessKey", form.r2SecretAccessKey)
            put("clearR2Credentials", form.clearR2Credentials)
        },
    ).toConfiguration()

    fun testProvider(token: String, provider: String) {
        request("/admin/system/backups/providers/test", token, "POST", JSONObject().put("provider", provider))
    }

    fun createDatabaseBackup(token: String): NativeDatabaseBackup = request("/admin/system/backups", token, "POST").toBackup()

    fun listDatabaseBackups(token: String): List<NativeDatabaseBackup> = request("/admin/system/status", token).optJSONObject("backups")?.optJSONArray("items").toBackups()

    fun downloadDatabaseBackup(token: String, name: String): NativeDownloadedBackup = download("/admin/system/backups/${encode(name)}/download", token)

    fun deleteDatabaseBackup(token: String, name: String) {
        request("/admin/system/backups/${encode(name)}", token, "DELETE")
    }

    fun deleteDatabaseBackups(token: String, names: List<String>) {
        request("/admin/system/backups/batch-delete", token, "POST", JSONObject().put("names", JSONArray(names)))
    }

    fun preflightRestore(token: String, name: String): NativeBackupRestorePreflight = request("/admin/system/backups/${encode(name)}/preflight", token).toPreflight()

    fun verifyDatabaseBackup(token: String, name: String): NativeDatabaseBackup = request("/admin/system/backups/${encode(name)}/verify", token, "POST").toBackup()

    fun restoreDatabaseBackup(token: String, name: String): JSONObject = request(
        "/admin/system/backups/${encode(name)}/restore", token, "POST", JSONObject().put("confirmation", name),
    )

    fun startMediaBackup(token: String): NativeMediaBackupJob = request("/admin/system/media-backups/jobs", token, "POST").toMediaJob()

    fun listMediaBackupJobs(token: String, pageSize: Int = 8): NativeMediaBackupJobPage = request(
        "/admin/system/media-backups/jobs?page=1&pageSize=${pageSize.coerceIn(1, 50)}", token,
    ).toMediaJobPage()

    fun getMediaBackupJob(token: String, id: Int): NativeMediaBackupJobDetail {
        val json = request("/admin/system/media-backups/jobs/$id", token)
        return NativeMediaBackupJobDetail(json.toMediaJob(), json.optJSONArray("manifests").toManifests(), json.optJSONArray("logs").toLogs())
    }

    fun listMediaBackupFiles(
        token: String,
        page: Int = 1,
        pageSize: Int = 20,
        category: String? = null,
        query: String? = null,
    ): NativeMediaBackupFilePage {
        val params = buildList {
            add("page=${page.coerceAtLeast(1)}")
            add("pageSize=${pageSize.coerceIn(1, 100)}")
            category?.trim()?.takeIf(String::isNotBlank)?.let { add("category=${encode(it)}") }
            query?.trim()?.takeIf(String::isNotBlank)?.let { add("q=${encode(it)}") }
        }
        return parseNativeMediaBackupFilePage(request("/admin/system/media-backups/files?${params.joinToString("&")}", token))
    }

    fun restoreMediaBackupFile(token: String, id: Int, confirmation: String, provider: String? = null): JSONObject = request(
        "/admin/system/media-backups/files/$id/restore", token, "POST", JSONObject().apply {
            put("confirmation", confirmation)
            provider?.trim()?.takeIf(String::isNotBlank)?.let { put("provider", it) }
        },
    )

    fun exportConfigurationBundle(token: String): JSONObject = request("/admin/system/configuration-bundle", token)

    fun importConfigurationBundle(token: String, bundle: JSONObject): JSONObject = request("/admin/system/configuration-bundle/import", token, "POST", bundle)

    private fun request(path: String, token: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val connection = open(path, token, method).apply {
            doOutput = body != null
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readJson(connection, nativePhraseForLocale(NativeI18n.locale, "备份管理请求失败", "Backup management request failed"))
        } finally { connection.disconnect() }
    }

    private fun download(path: String, token: String): NativeDownloadedBackup {
        val connection = open(path, token, "GET")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) throw NativeAdminBackupHttpException(status, errorText(bytes, nativePhraseForLocale(NativeI18n.locale, "备份下载失败", "Backup download failed")))
            NativeDownloadedBackup(bytes, connection.contentType?.substringBefore(';').orEmpty().ifBlank { "application/octet-stream" })
        } finally { connection.disconnect() }
    }

    private fun open(path: String, token: String, method: String): HttpURLConnection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = if (method == "POST") 180_000 else 45_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
    }

    private fun readJson(connection: HttpURLConnection, fallback: String): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw NativeAdminBackupHttpException(status, errorText(body.toByteArray(), "$fallback (HTTP $status)."))
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    private fun errorText(bytes: ByteArray, fallback: String): String = runCatching { JSONObject(String(bytes)).messageText() }.getOrNull().orEmpty().ifBlank { fallback }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JSONObject.toConfiguration(): NativeBackupConfiguration {
        val oss = optJSONObject("oss") ?: JSONObject()
        val r2 = optJSONObject("r2") ?: JSONObject()
        return NativeBackupConfiguration(
            automaticEnabled = optBoolean("automaticEnabled"),
            scheduleTime = optString("scheduleTime", "02:00"),
            timezone = optString("timezone", "UTC"),
            localRetentionDays = optInt("localRetentionDays", 7),
            remoteRetentionDays = optInt("remoteRetentionDays", 30),
            encryptionConfigured = optBoolean("encryptionConfigured"),
            nextRunAt = optNullableString("nextRunAt"),
            lastAutomaticBackupDate = optNullableString("lastAutomaticBackupDate"),
            lastMediaBackupDate = optNullableString("lastMediaBackupDate"),
            lastSuccessAt = optNullableString("lastSuccessAt"),
            lastFailureAt = optNullableString("lastFailureAt"),
            lastFailureMessage = optNullableString("lastFailureMessage"),
            lastBackupName = optNullableString("lastBackupName"),
            oss = oss.toProvider(),
            r2 = r2.toProvider(),
        )
    }

    private fun JSONObject.toProvider(): NativeBackupProvider = NativeBackupProvider(
        enabled = optBoolean("enabled"),
        region = optString("region"),
        endpoint = optString("endpoint"),
        accountId = optString("accountId"),
        bucket = optString("bucket"),
        prefix = optString("prefix"),
        hasAccessKeyId = optBoolean("hasAccessKeyId"),
        hasSecretAccessKey = optBoolean("hasSecretAccessKey"),
    )

    private fun JSONObject.toBackup(): NativeDatabaseBackup = NativeDatabaseBackup(
        name = optString("name", "未命名备份"),
        sizeBytes = optLong("sizeBytes"),
        mediaSnapshotAvailable = optBoolean("mediaSnapshotAvailable"),
        mediaSnapshotSizeBytes = optNullableLong("mediaSnapshotSizeBytes"),
        verificationStatus = optJSONObject("verification")?.optString("status", "not_verified") ?: "not_verified",
        updatedAt = optString("updatedAt"),
        verificationError = optJSONObject("verification").optNullableString("error"),
        warning = optNullableString("warning"),
    )

    private fun JSONObject.toPreflight(): NativeBackupRestorePreflight = NativeBackupRestorePreflight(
        backup = optJSONObject("backup")?.toBackup() ?: toBackup(),
        canRestore = optBoolean("canRestore"),
        warnings = optJSONArray("warnings").toStrings(),
    )

    private fun JSONObject.toMediaJob(): NativeMediaBackupJob = NativeMediaBackupJob(
        id = optInt("id"), status = optString("status", "pending"), trigger = optString("trigger", "manual"),
        providers = optJSONArray("providers").toStrings(), totalFiles = optInt("totalFiles"), processedFiles = optInt("processedFiles"),
        uploadedFiles = optInt("uploadedFiles"), reusedFiles = optInt("reusedFiles"), skippedFiles = optInt("skippedFiles"), failedFiles = optInt("failedFiles"),
        totalBytes = optLong("totalBytes"), uploadedBytes = optLong("uploadedBytes"), error = optNullableString("error"),
        startedAt = optNullableString("startedAt"), completedAt = optNullableString("completedAt"), createdAt = optString("createdAt"),
    )

    private fun JSONObject.toMediaJobPage(): NativeMediaBackupJobPage = NativeMediaBackupJobPage(
        items = optJSONArray("items").toMediaJobs(), total = optInt("total"), page = optInt("page", 1), pageSize = optInt("pageSize", 8), pageCount = optInt("pageCount", 1),
    )

    private fun JSONArray?.toBackups(): List<NativeDatabaseBackup> = buildList { for (index in 0 until (this@toBackups?.length() ?: 0)) this@toBackups?.optJSONObject(index)?.toBackup()?.let(::add) }
    private fun JSONArray?.toMediaJobs(): List<NativeMediaBackupJob> = buildList { for (index in 0 until (this@toMediaJobs?.length() ?: 0)) this@toMediaJobs?.optJSONObject(index)?.toMediaJob()?.let(::add) }
    private fun JSONArray?.toStrings(): List<String> = buildList { for (index in 0 until (this@toStrings?.length() ?: 0)) this@toStrings?.optString(index)?.takeIf(String::isNotBlank)?.let(::add) }
    private fun JSONArray?.toManifests(): List<NativeMediaBackupManifest> = buildList { for (index in 0 until (this@toManifests?.length() ?: 0)) { val item = this@toManifests?.optJSONObject(index) ?: continue; add(NativeMediaBackupManifest(item.optString("storedName"), item.optString("category"), item.optString("provider"), item.optString("status"), item.optLong("sizeBytes"), item.optNullableString("objectKey"), item.optNullableString("error"))) } }
    private fun JSONArray?.toLogs(): List<NativeMediaBackupLog> = buildList { for (index in 0 until (this@toLogs?.length() ?: 0)) { val item = this@toLogs?.optJSONObject(index) ?: continue; add(NativeMediaBackupLog(item.optString("level"), item.optString("event"), item.optString("message"), item.optNullableString("provider"), item.optString("createdAt"))) } }
    private fun JSONObject?.optNullableString(key: String): String? = this?.optString(key).orEmpty().takeIf { it.isNotBlank() && it != "null" }
    private fun JSONObject?.optNullableLong(key: String): Long? = if (this == null || !has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject?.messageText(): String = this?.opt("message")?.let { if (it is JSONArray) it.toStrings().joinToString("；") else it.toString() }.orEmpty()
}

internal fun parseNativeMediaBackupFile(json: JSONObject): NativeMediaBackupFile = NativeMediaBackupFile(
    id = json.optInt("id"),
    category = json.optString("category"),
    storedName = json.optString("storedName"),
    sourceType = json.optString("sourceType"),
    sourceId = json.optString("sourceId").takeIf { it.isNotBlank() && it != "null" },
    sourceLabel = json.optString("sourceLabel"),
    sizeBytes = json.optLong("sizeBytes"),
    contentHash = json.optString("contentHash").takeIf { it.isNotBlank() && it != "null" },
    fileUpdatedAt = json.optString("fileUpdatedAt").takeIf { it.isNotBlank() && it != "null" },
    lastSeenAt = json.optString("lastSeenAt"),
    lastBackedUpAt = json.optString("lastBackedUpAt").takeIf { it.isNotBlank() && it != "null" },
)

internal fun parseNativeMediaBackupFilePage(json: JSONObject): NativeMediaBackupFilePage = NativeMediaBackupFilePage(
    items = buildList {
        val array = json.optJSONArray("items")
        for (index in 0 until (array?.length() ?: 0)) {
            array?.optJSONObject(index)?.let(::parseNativeMediaBackupFile)?.let(::add)
        }
    },
    total = json.optInt("total"),
    page = json.optInt("page", 1),
    pageSize = json.optInt("pageSize", 20),
    pageCount = json.optInt("pageCount"),
)
