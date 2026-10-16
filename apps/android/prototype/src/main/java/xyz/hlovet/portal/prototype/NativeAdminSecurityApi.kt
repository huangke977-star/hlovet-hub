package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal data class NativeSecurityAdminConfig(
    val smtpEnabled: Boolean,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecure: Boolean,
    val smtpUsername: String,
    val smtpPasswordConfigured: Boolean,
    val smtpFromName: String,
    val smtpFromEmail: String,
    val registrationEmailVerificationEnabled: Boolean,
    val passwordRecoveryEnabled: Boolean,
    val untrustedDeviceEmailVerificationEnabled: Boolean,
    val turnstileSiteKey: String,
    val turnstileSecretConfigured: Boolean,
    val turnstileRegistrationEnabled: Boolean,
    val turnstileLoginEnabled: Boolean,
    val turnstileRecoveryEnabled: Boolean,
    val loginFailureTurnstileThreshold: Int,
    val encryptionConfigured: Boolean,
    val updatedAt: String,
)

internal data class NativeSecurityAdminUpdate(
    val smtpEnabled: Boolean,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecure: Boolean,
    val smtpUsername: String,
    val smtpFromName: String,
    val smtpFromEmail: String,
    val registrationEmailVerificationEnabled: Boolean,
    val passwordRecoveryEnabled: Boolean,
    val untrustedDeviceEmailVerificationEnabled: Boolean,
    val turnstileSiteKey: String,
    val turnstileRegistrationEnabled: Boolean,
    val turnstileLoginEnabled: Boolean,
    val turnstileRecoveryEnabled: Boolean,
    val loginFailureTurnstileThreshold: Int,
    val smtpPassword: String = "",
    val turnstileSecret: String = "",
)

internal data class NativeSecurityAdminRecord(
    val id: String,
    val type: String,
    val status: String,
    val email: String,
    val recipient: String,
    val subject: String,
    val purpose: String,
    val summary: String,
    val riskLevel: String,
    val ip: String,
    val deviceLabel: String,
    val attempts: Int,
    val lastError: String,
    val createdAt: String,
    val updatedAt: String,
    val expiresAt: String,
    val sentAt: String,
    val verifiedAt: String,
    val consumedAt: String,
    val userNickname: String,
    val userUsername: String,
)

internal data class NativeSecurityAdminRecordPage(
    val items: List<NativeSecurityAdminRecord>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

internal fun nativeSecurityDraftError(config: NativeSecurityAdminConfig, port: String, threshold: String, smtpPassword: String, turnstileSecret: String): String? {
    if (!port.matches(Regex("[0-9]+")) || port.toIntOrNull()?.let { it in 1..65535 } != true)
        return nativePhrase("SMTP 端口必须是 1 到 65535 之间的整数。", "SMTP port must be an integer from 1 to 65535.")
    if (!threshold.matches(Regex("[0-9]+")) || threshold.toIntOrNull()?.let { it in 1..5 } != true)
        return nativePhrase("登录失败触发次数必须是 1 到 5 之间的整数。", "Failed sign-in threshold must be an integer from 1 to 5.")
    if (config.smtpHost.length > 255 || config.smtpUsername.length > 255 || config.smtpFromName.length > 120 || config.smtpFromEmail.length > 191 || config.turnstileSiteKey.length > 255 || smtpPassword.length > 500 || turnstileSecret.length > 500)
        return nativePhrase("配置内容超过允许长度，请缩短后重试。", "A setting exceeds its allowed length. Shorten it and try again.")
    if ((smtpPassword.isNotBlank() || turnstileSecret.isNotBlank()) && !config.encryptionConfigured)
        return nativePhrase("服务器尚未配置凭据加密，不能保存新的密钥。", "Credential encryption is not configured. New secrets cannot be saved.")
    if (config.smtpEnabled && (config.smtpHost.isBlank() || config.smtpUsername.isBlank() || config.smtpFromEmail.isBlank() || (!config.smtpPasswordConfigured && smtpPassword.isBlank())))
        return nativePhrase("启用邮件前请配置 SMTP 主机、账号、密码和发件邮箱。", "Configure the SMTP host, username, password, and sender email before enabling email.")
    if ((config.turnstileRegistrationEnabled || config.turnstileLoginEnabled || config.turnstileRecoveryEnabled) && (config.turnstileSiteKey.isBlank() || (!config.turnstileSecretConfigured && turnstileSecret.isBlank())))
        return nativePhrase("启用 Turnstile 前请配置 Site Key 和 Secret Key。", "Configure the Turnstile site key and secret key before enabling it.")
    return null
}

internal object NativeAdminSecurityApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun getConfig(accessToken: String): NativeSecurityAdminConfig {
        return request("/security-admin/config", accessToken).toConfig()
    }

    fun updateConfig(accessToken: String, update: NativeSecurityAdminUpdate): NativeSecurityAdminConfig {
        return request("/security-admin/config", accessToken, "PATCH", update.toJson()).toConfig()
    }

    internal fun NativeSecurityAdminUpdate.toJson(): JSONObject {
        val update = this
        return JSONObject().apply {
            put("smtpEnabled", update.smtpEnabled)
            put("smtpHost", update.smtpHost)
            put("smtpPort", update.smtpPort)
            put("smtpSecure", update.smtpSecure)
            put("smtpUsername", update.smtpUsername)
            put("smtpFromName", update.smtpFromName)
            if (update.smtpFromEmail.isNotBlank()) put("smtpFromEmail", update.smtpFromEmail.trim())
            put("registrationEmailVerificationEnabled", update.registrationEmailVerificationEnabled)
            put("passwordRecoveryEnabled", update.passwordRecoveryEnabled)
            put("untrustedDeviceEmailVerificationEnabled", update.untrustedDeviceEmailVerificationEnabled)
            put("turnstileSiteKey", update.turnstileSiteKey)
            put("turnstileRegistrationEnabled", update.turnstileRegistrationEnabled)
            put("turnstileLoginEnabled", update.turnstileLoginEnabled)
            put("turnstileRecoveryEnabled", update.turnstileRecoveryEnabled)
            put("loginFailureTurnstileThreshold", update.loginFailureTurnstileThreshold)
            if (update.smtpPassword.isNotBlank()) put("smtpPassword", update.smtpPassword)
            if (update.turnstileSecret.isNotBlank()) put("turnstileSecret", update.turnstileSecret)
        }
    }

    fun testSmtp(accessToken: String): String {
        return request("/security-admin/smtp/test", accessToken, "POST", JSONObject()).textOrEmpty("message").ifBlank { nativePhrase("SMTP 连接测试已完成。", "SMTP connection test completed.") }
    }

    fun listOverview(
        accessToken: String,
        tab: String,
        page: Int,
        pageSize: Int,
        search: String,
        status: String,
    ): NativeSecurityAdminRecordPage {
        val query = buildString {
            append("?tab=").append(URLEncoder.encode(tab, Charsets.UTF_8.name()))
            append("&page=").append(page.coerceAtLeast(1))
            append("&pageSize=").append(pageSize.coerceIn(1, 100))
            if (search.isNotBlank()) append("&search=").append(URLEncoder.encode(search, Charsets.UTF_8.name()))
            if (status.isNotBlank()) append("&status=").append(URLEncoder.encode(status, Charsets.UTF_8.name()))
        }
        return request("/security-admin/overview$query", accessToken).toRecordPage(page, pageSize)
    }

    internal fun JSONObject.toRecordPage(page: Int = 1, pageSize: Int = 10): NativeSecurityAdminRecordPage {
        val value = this
        return NativeSecurityAdminRecordPage(
            items = value.optJSONArray("items").toRecords(),
            total = value.optInt("total"),
            page = value.optInt("page", page),
            pageSize = value.optInt("pageSize", pageSize),
            totalPages = value.optInt("totalPages", 1),
        )
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
                val message = runCatching {
                    val value = JSONObject(responseBody).opt("message")
                    when (value) {
                        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.joinToString("\n")
                        is String -> value
                        else -> ""
                    }
                }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { nativePhrase("安全管理请求失败（HTTP $status）。", "Security request failed (HTTP $status).") })
            }
            if (responseBody.isBlank()) throw IllegalStateException(nativePhrase("安全管理返回为空，请重试。", "The security response is empty. Please retry."))
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    internal fun JSONObject.toConfig(): NativeSecurityAdminConfig = NativeSecurityAdminConfig(
        smtpEnabled = optBoolean("smtpEnabled"),
        smtpHost = textOrEmpty("smtpHost"),
        smtpPort = optInt("smtpPort", 465),
        smtpSecure = optBoolean("smtpSecure"),
        smtpUsername = textOrEmpty("smtpUsername"),
        smtpPasswordConfigured = optBoolean("smtpPasswordConfigured"),
        smtpFromName = textOrEmpty("smtpFromName"),
        smtpFromEmail = textOrEmpty("smtpFromEmail"),
        registrationEmailVerificationEnabled = optBoolean("registrationEmailVerificationEnabled"),
        passwordRecoveryEnabled = optBoolean("passwordRecoveryEnabled"),
        untrustedDeviceEmailVerificationEnabled = optBoolean("untrustedDeviceEmailVerificationEnabled"),
        turnstileSiteKey = textOrEmpty("turnstileSiteKey"),
        turnstileSecretConfigured = optBoolean("turnstileSecretConfigured"),
        turnstileRegistrationEnabled = optBoolean("turnstileRegistrationEnabled"),
        turnstileLoginEnabled = optBoolean("turnstileLoginEnabled"),
        turnstileRecoveryEnabled = optBoolean("turnstileRecoveryEnabled"),
        loginFailureTurnstileThreshold = optInt("loginFailureTurnstileThreshold", 3),
        encryptionConfigured = optBoolean("encryptionConfigured"),
        updatedAt = textOrEmpty("updatedAt"),
    )

    private fun JSONArray?.toRecords(): List<NativeSecurityAdminRecord> = buildList {
        if (this@toRecords == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val user = value.optJSONObject("user")
            add(
                NativeSecurityAdminRecord(
                    id = value.optString("id", index.toString()),
                    type = value.textOrEmpty("type"),
                    status = value.textOrEmpty("status"),
                    email = value.textOrEmpty("email"),
                    recipient = value.textOrEmpty("recipient"),
                    subject = value.textOrEmpty("subject"),
                    purpose = value.textOrEmpty("purpose"),
                    summary = value.textOrEmpty("summary"),
                    riskLevel = value.textOrEmpty("riskLevel"),
                    ip = value.textOrEmpty("ip"),
                    deviceLabel = value.textOrEmpty("deviceLabel"),
                    attempts = value.optInt("attempts"),
                    lastError = value.textOrEmpty("lastError"),
                    createdAt = value.textOrEmpty("createdAt"),
                    updatedAt = value.textOrEmpty("updatedAt"),
                    expiresAt = value.textOrEmpty("expiresAt"),
                    sentAt = value.textOrEmpty("sentAt"),
                    verifiedAt = value.textOrEmpty("verifiedAt"),
                    consumedAt = value.textOrEmpty("consumedAt"),
                    userNickname = user?.textOrEmpty("nickname").orEmpty(),
                    userUsername = user?.textOrEmpty("username").orEmpty(),
                ),
            )
        }
    }

    private fun JSONObject.textOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
}
