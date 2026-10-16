package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeOperationsOverview(
    val generatedAt: String,
    val auditPolicy: NativeAuditPolicy,
    val alerts: List<NativeOperationalAlert>,
    val runs: List<NativeOperationalRun>,
    val dependencyItems: List<NativeDependencyItem>,
    val recoveryTargets: List<NativeRecoveryTarget>,
    val loadTestTargets: List<NativeLoadTestTarget>,
    val externalStorage: NativeExternalStorage,
)

internal data class NativeAuditPolicy(
    val cleanupEnabled: Boolean,
    val businessDays: Int,
    val securityDays: Int,
    val serverDays: Int,
    val lastCleanupAt: String?,
    val lastCleanupCount: Int,
)

internal data class NativeAuditCleanupResult(
    val deletedCount: Int,
    val policy: NativeAuditPolicy,
)

internal data class NativeOperationalAlert(
    val id: Int,
    val type: String,
    val severity: String,
    val status: String,
    val title: String,
    val message: String,
    val occurrenceCount: Int,
    val firstSeenAt: String,
    val lastSeenAt: String,
)

internal data class NativeOperationalRun(
    val id: Int,
    val kind: String,
    val status: String,
    val provider: String?,
    val summary: String,
    val detail: String,
    val metrics: String,
    val startedAt: String,
    val completedAt: String?,
    val createdAt: String,
)

internal data class NativeDependencyItem(
    val name: String,
    val current: String,
    val requested: String?,
    val category: String,
    val status: String,
    val note: String,
)

internal data class NativeRecoveryTarget(
    val name: String,
    val target: String,
    val current: String,
    val status: String,
    val measurement: String,
)

internal data class NativeLoadTestTarget(
    val path: String,
    val label: String,
    val description: String,
    val labelEn: String = label,
    val descriptionEn: String = description,
)

internal data class NativeExternalStorage(
    val ossConfigured: Boolean,
    val r2Configured: Boolean,
    val encryptionConfigured: Boolean,
    val message: String,
)

internal data class NativeQualityOverview(
    val generatedAt: String,
    val checks: List<NativeQualityCheck>,
    val latestRun: NativeOperationalRun?,
    val browserChecks: List<NativeBrowserCheck>,
)

internal data class NativeQualityCheck(
    val id: String,
    val label: String,
    val description: String,
    val status: String,
    val detail: String,
    val labelEn: String = label,
    val detailEn: String = detail,
)

internal data class NativeBrowserCheck(
    val id: String,
    val label: String,
    val command: String,
    val description: String,
    val labelEn: String = label,
)

internal class NativeAdminOperationsHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

internal object NativeAdminOperationsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun getOverview(accessToken: String): NativeOperationsOverview =
        request("/admin/system/operations/overview", accessToken).toOperationsOverview()

    fun getQualityOverview(accessToken: String): NativeQualityOverview =
        request("/admin/system/quality/overview", accessToken).toQualityOverview()

    fun listRuns(accessToken: String): List<NativeOperationalRun> =
        request("/admin/system/operations/runs?limit=20", accessToken).optJSONArray("items").toRuns()

    fun runAlertCheck(accessToken: String): NativeOperationalRun =
        request("/admin/system/operations/runs/alert-check", accessToken, "POST").toRun()

    fun runDependencyReview(accessToken: String): NativeOperationalRun =
        request("/admin/system/operations/runs/dependency-review", accessToken, "POST").toRun()

    fun runQualityCheck(accessToken: String): NativeOperationalRun =
        request("/admin/system/quality/release-check", accessToken, "POST").toRun()

    fun updateAuditPolicy(
        accessToken: String,
        cleanupEnabled: Boolean,
        businessDays: Int,
        securityDays: Int,
        serverDays: Int,
    ): NativeAuditPolicy = request(
        "/admin/system/operations/audit-policy",
        accessToken,
        "POST",
        JSONObject().apply {
            put("cleanupEnabled", cleanupEnabled)
            put("businessDays", businessDays.also { require(it in 7..3650) })
            put("securityDays", securityDays.also { require(it in 7..3650) })
            put("serverDays", serverDays.also { require(it in 7..3650) })
        },
    ).toAuditPolicy()

    fun cleanupAuditLogs(accessToken: String): NativeAuditCleanupResult {
        val response = request("/admin/system/operations/audit-cleanup", accessToken, "POST")
        return NativeAuditCleanupResult(
            deletedCount = response.optInt("deletedCount", 0),
            policy = response.optJSONObject("policy").toAuditPolicy(),
        )
    }

    fun startRecoveryDrill(accessToken: String, provider: String): NativeOperationalRun =
        request(
            "/admin/system/operations/recovery-drills",
            accessToken,
            "POST",
            JSONObject().put("provider", provider),
        ).toRun()

    fun startLoadTest(
        accessToken: String,
        paths: List<String>,
        concurrency: Int,
        durationSeconds: Int,
    ): NativeOperationalRun = request(
        "/admin/system/operations/load-tests",
        accessToken,
        "POST",
        JSONObject().apply {
            put("paths", JSONArray(paths.also { require(it.size in 1..3 && it.distinct().size == it.size) }))
            put("concurrency", concurrency.also { require(it in 1..50) })
            put("durationSeconds", durationSeconds.also { require(it in 1..300) })
        },
    ).toRun()

    fun acknowledgeAlert(accessToken: String, id: Int): NativeOperationalAlert =
        request("/admin/system/operations/alerts/$id/acknowledge", accessToken, "POST").toAlert()

    fun resolveAlert(accessToken: String, id: Int): NativeOperationalAlert =
        request("/admin/system/operations/alerts/$id/resolve", accessToken, "POST").toAlert()

    private fun request(path: String, accessToken: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = if (method == "POST") 120_000 else 30_000
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
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).messageText() }.getOrNull().orEmpty()
                throw NativeAdminOperationsHttpException(
                    status,
                    when (status) {
                        HttpURLConnection.HTTP_UNAUTHORIZED -> nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Sign in again.")
                        HttpURLConnection.HTTP_FORBIDDEN -> nativePhrase("当前账号没有运营与质量权限。", "This account cannot manage operations and quality.")
                        else -> message.ifBlank { nativePhrase("运营操作失败（HTTP $status）。", "Operation failed (HTTP $status).") }
                    },
                )
            }
            if (text.isBlank()) JSONObject() else when (val result = org.json.JSONTokener(text).nextValue()) {
                is JSONObject -> result
                is JSONArray -> JSONObject().put("items", result)
                else -> throw IllegalStateException(nativePhrase("运营接口响应格式不正确。", "Invalid operations response format."))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toOperationsOverview(): NativeOperationsOverview {
        val policy = optJSONObject("auditPolicy") ?: JSONObject()
        val dependencies = optJSONObject("dependencyAssessment") ?: JSONObject()
        val storage = optJSONObject("externalStorage") ?: JSONObject()
        return NativeOperationsOverview(
            generatedAt = optString("generatedAt"),
            auditPolicy = NativeAuditPolicy(
                cleanupEnabled = policy.optBoolean("cleanupEnabled"),
                businessDays = policy.optInt("businessDays", 180),
                securityDays = policy.optInt("securityDays", 365),
                serverDays = policy.optInt("serverDays", 90),
                lastCleanupAt = policy.optNullableString("lastCleanupAt"),
                lastCleanupCount = policy.optInt("lastCleanupCount", 0),
            ),
            alerts = optJSONArray("alerts").toAlerts(),
            runs = optJSONArray("runs").toRuns(),
            dependencyItems = dependencies.optJSONArray("items").toDependencyItems(),
            recoveryTargets = optJSONArray("recoveryTargets").toRecoveryTargets(),
            loadTestTargets = optJSONArray("loadTestTargets").toLoadTestTargets(),
            externalStorage = NativeExternalStorage(
                ossConfigured = storage.optBoolean("ossConfigured"),
                r2Configured = storage.optBoolean("r2Configured"),
                encryptionConfigured = storage.optBoolean("encryptionConfigured"),
                message = storage.optString("message"),
            ),
        )
    }

    private fun JSONObject.toQualityOverview(): NativeQualityOverview = NativeQualityOverview(
        generatedAt = optString("generatedAt"),
        checks = optJSONArray("checks").toQualityChecks(),
        latestRun = optJSONObject("latestRun")?.toRun(),
        browserChecks = optJSONArray("browserChecks").toBrowserChecks(),
    )

    private fun JSONObject?.toAuditPolicy(): NativeAuditPolicy {
        val value = this ?: JSONObject()
        return NativeAuditPolicy(
            cleanupEnabled = value.optBoolean("cleanupEnabled", true),
            businessDays = value.optInt("businessDays", 180),
            securityDays = value.optInt("securityDays", 365),
            serverDays = value.optInt("serverDays", 90),
            lastCleanupAt = value.optNullableString("lastCleanupAt"),
            lastCleanupCount = value.optInt("lastCleanupCount", 0),
        )
    }

    private fun JSONObject.toAlert(): NativeOperationalAlert = NativeOperationalAlert(
        id = optInt("id"),
        type = optString("type"),
        severity = optString("severity", "warning"),
        status = optString("status", "open"),
        title = optString("title", "运营告警"),
        message = optString("message"),
        occurrenceCount = optInt("occurrenceCount", 1),
        firstSeenAt = optString("firstSeenAt"),
        lastSeenAt = optString("lastSeenAt"),
    )

    private fun JSONObject.toRun(): NativeOperationalRun = NativeOperationalRun(
        id = optInt("id"),
        kind = optString("kind"),
        status = optString("status", "running"),
        provider = optNullableString("provider"),
        summary = optString("summary"),
        detail = jsonValueText(opt("detail")),
        metrics = jsonValueText(opt("metrics")),
        startedAt = optString("startedAt"),
        completedAt = optNullableString("completedAt"),
        createdAt = optString("createdAt"),
    )

    private fun JSONArray?.toAlerts(): List<NativeOperationalAlert> = buildList {
        for (index in 0 until (this@toAlerts?.length() ?: 0)) this@toAlerts?.optJSONObject(index)?.let { add(it.toAlert()) }
    }

    private fun JSONArray?.toRuns(): List<NativeOperationalRun> = buildList {
        for (index in 0 until (this@toRuns?.length() ?: 0)) this@toRuns?.optJSONObject(index)?.let { add(it.toRun()) }
    }

    private fun JSONArray?.toDependencyItems(): List<NativeDependencyItem> = buildList {
        for (index in 0 until (this@toDependencyItems?.length() ?: 0)) {
            val item = this@toDependencyItems?.optJSONObject(index) ?: continue
            add(NativeDependencyItem(item.optString("name"), item.optString("current"), item.optNullableString("requested"), item.optString("category"), item.optString("status"), item.optString("note")))
        }
    }

    private fun JSONArray?.toRecoveryTargets(): List<NativeRecoveryTarget> = buildList {
        for (index in 0 until (this@toRecoveryTargets?.length() ?: 0)) {
            val item = this@toRecoveryTargets?.optJSONObject(index) ?: continue
            add(NativeRecoveryTarget(item.optString("name"), item.optString("target"), item.optString("current"), item.optString("status"), item.optString("measurement")))
        }
    }

    private fun JSONArray?.toLoadTestTargets(): List<NativeLoadTestTarget> = buildList {
        for (index in 0 until (this@toLoadTestTargets?.length() ?: 0)) {
            val item = this@toLoadTestTargets?.optJSONObject(index) ?: continue
            add(NativeLoadTestTarget(item.optString("path"), item.optString("label"), item.optString("description"), item.optString("labelEn", item.optString("label")), item.optString("descriptionEn", item.optString("description"))))
        }
    }

    private fun JSONArray?.toQualityChecks(): List<NativeQualityCheck> = buildList {
        for (index in 0 until (this@toQualityChecks?.length() ?: 0)) {
            val item = this@toQualityChecks?.optJSONObject(index) ?: continue
            add(NativeQualityCheck(item.optString("id"), item.optString("label"), item.optString("description"), item.optString("status"), item.optString("detail"), item.optString("labelEn", item.optString("label")), item.optString("detailEn", item.optString("detail"))))
        }
    }

    private fun JSONArray?.toBrowserChecks(): List<NativeBrowserCheck> = buildList {
        for (index in 0 until (this@toBrowserChecks?.length() ?: 0)) {
            val item = this@toBrowserChecks?.optJSONObject(index) ?: continue
            add(NativeBrowserCheck(item.optString("id"), item.optString("label"), item.optString("command"), item.optString("description"), item.optString("labelEn", item.optString("label"))))
        }
    }

    private fun JSONObject?.optNullableString(key: String): String? =
        this?.optString(key).orEmpty().takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject?.messageText(): String {
        val value = this?.opt("message") ?: return ""
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.joinToString("；")
            else -> value.toString()
        }
    }

    private fun jsonValueText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }
}
