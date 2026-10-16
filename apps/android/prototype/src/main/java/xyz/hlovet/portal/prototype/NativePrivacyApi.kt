package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeBlockedUser(
    val friendshipId: Int,
    val userId: Int,
    val username: String,
    val nickname: String,
)

internal data class NativePrivacyOverview(
    val email: String,
    val deletionPending: Boolean,
    val deletionScheduledAt: String?,
    val totpEnabled: Boolean,
    val blocked: List<NativeBlockedUser>,
)

internal data class NativePrivacyExportJob(
    val id: Int,
    val status: String,
    val error: String?,
    val expiresAt: String,
    val completedAt: String?,
)

internal data class NativePrivacyAuditRecord(
    val id: Int,
    val action: String,
    val createdAt: String,
)

internal data class NativeSecurityEvent(
    val id: Int,
    val type: String,
    val riskLevel: String,
    val summary: String,
    val createdAt: String,
)

internal data class NativeTrustedDevice(
    val id: Int,
    val label: String,
    val ip: String,
    val current: Boolean,
    val lastSeenAt: String,
)

internal data class NativeSecurityOverview(
    val mailServiceEnabled: Boolean,
    val emailVerifiedAt: String?,
    val loginAlertsEnabled: Boolean,
    val emailAlertsEnabled: Boolean,
    val newDeviceAlertsEnabled: Boolean,
    val events: List<NativeSecurityEvent>,
    val trustedDevices: List<NativeTrustedDevice>,
)

internal data class NativeSecurityPreferences(
    val loginAlertsEnabled: Boolean,
    val emailAlertsEnabled: Boolean,
    val newDeviceAlertsEnabled: Boolean,
)

internal data class NativePasskeySummary(
    val id: Int,
    val name: String,
    val createdAt: String,
    val lastUsedAt: String?,
)

internal data class NativeSensitiveEmailChallenge(
    val challengeToken: String,
    val retryAfterSeconds: Int,
)

internal data class NativeTotpEnrollment(
    val secret: String,
    val otpAuthUri: String,
)

internal object NativePrivacyApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun getOverview(accessToken: String): NativePrivacyOverview {
        val value = requestJson("/account-privacy/me", "GET", accessToken)
        val deletion = value.optJSONObject("deletion") ?: JSONObject()
        val totp = value.optJSONObject("totp") ?: JSONObject()
        return NativePrivacyOverview(
            email = value.optString("email"),
            deletionPending = deletion.optBoolean("pending"),
            deletionScheduledAt = deletion.optString("scheduledAt").takeIf(String::isNotBlank),
            totpEnabled = totp.optBoolean("enabled"),
            blocked = value.optJSONArray("blocked").toBlockedUsers(),
        )
    }

    fun listAudit(accessToken: String, limit: Int = 30): List<NativePrivacyAuditRecord> =
        JSONArray(requestRaw("/account-privacy/me/audit?limit=${limit.coerceIn(1, 100)}", "GET", accessToken))
            .toAuditRecords()

    fun requestExport(accessToken: String): NativePrivacyExportJob =
        requestJson("/account-privacy/me/exports", "POST", accessToken, JSONObject()).toExportJob()

    fun getExport(accessToken: String, id: Int): NativePrivacyExportJob =
        requestJson("/account-privacy/me/exports/$id", "GET", accessToken).toExportJob()

    fun downloadExport(accessToken: String, id: Int): String =
        requestRaw("/account-privacy/me/exports/$id/download", "GET", accessToken)

    fun unblock(accessToken: String, friendshipId: Int) {
        requestJson("/social/friendships/$friendshipId/block", "DELETE", accessToken)
    }

    fun getSecurity(accessToken: String): NativeSecurityOverview {
        val value = requestJson("/auth/me/security", "GET", accessToken)
        val preferences = value.optJSONObject("preferences") ?: JSONObject()
        return NativeSecurityOverview(
            mailServiceEnabled = value.optBoolean("mailServiceEnabled", true),
            emailVerifiedAt = value.optString("emailVerifiedAt").takeIf(String::isNotBlank),
            loginAlertsEnabled = preferences.optBoolean("loginAlertsEnabled"),
            emailAlertsEnabled = preferences.optBoolean("emailAlertsEnabled"),
            newDeviceAlertsEnabled = preferences.optBoolean("newDeviceAlertsEnabled"),
            events = value.optJSONArray("events").toSecurityEvents(),
            trustedDevices = value.optJSONArray("trustedDevices").toTrustedDevices(),
        )
    }

    fun requestEmailVerification(accessToken: String): Int =
        requestJson("/auth/me/email-verification/send", "POST", accessToken, JSONObject())
            .optInt("retryAfterSeconds", 60)
            .coerceAtLeast(1)

    fun confirmEmailVerification(accessToken: String, code: String): String? =
        requestJson(
            "/auth/me/email-verification/confirm",
            "POST",
            accessToken,
            JSONObject().put("code", code),
        ).optString("emailVerifiedAt")
            .takeIf { it.isNotBlank() && it != "null" }

    fun updateSecurityPreferences(
        accessToken: String,
        loginAlertsEnabled: Boolean,
        emailAlertsEnabled: Boolean,
        newDeviceAlertsEnabled: Boolean,
    ): NativeSecurityPreferences {
        val value = requestJson(
            "/auth/me/security/preferences",
            "PATCH",
            accessToken,
            JSONObject().apply {
                put("loginAlertsEnabled", loginAlertsEnabled)
                put("emailAlertsEnabled", emailAlertsEnabled)
                put("newDeviceAlertsEnabled", newDeviceAlertsEnabled)
            },
        )
        val preferences = value.optJSONObject("preferences") ?: value
        return NativeSecurityPreferences(
            loginAlertsEnabled = preferences.optBoolean("loginAlertsEnabled"),
            emailAlertsEnabled = preferences.optBoolean("emailAlertsEnabled"),
            newDeviceAlertsEnabled = preferences.optBoolean("newDeviceAlertsEnabled"),
        )
    }

    fun cancelTrustedDevice(accessToken: String, deviceId: Int): Boolean =
        requestJson(
            "/auth/me/security/trusted-devices/$deviceId",
            "DELETE",
            accessToken,
        ).optBoolean("current")

    fun listPasskeys(accessToken: String): List<NativePasskeySummary> =
        JSONArray(requestRaw("/auth/me/passkeys", "GET", accessToken)).toPasskeys()

    fun renamePasskey(accessToken: String, id: Int, name: String) {
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "请输入通行密钥名称。" }
        requestJson("/auth/me/passkeys/$id", "PATCH", accessToken, JSONObject().put("name", trimmedName))
    }

    fun passkeyRegistrationOptions(accessToken: String, verificationToken: String): NativePasskeyCreationOptions {
        val value = requestJson(
            "/auth/me/passkeys/registration/options",
            "POST",
            accessToken,
            JSONObject().apply { put("verificationToken", verificationToken) },
        )
        return NativePasskeyCreationOptions(
            challengeToken = value.optString("challengeToken").ifBlank { throw IllegalStateException("通行密钥注册挑战值缺失") },
            requestJson = value.optJSONObject("options")?.toString() ?: throw IllegalStateException("通行密钥注册参数缺失"),
        )
    }

    fun verifyPasskeyRegistration(
        accessToken: String,
        challengeToken: String,
        responseJson: String,
        name: String?,
    ) {
        requestJson(
            "/auth/me/passkeys/registration/verify",
            "POST",
            accessToken,
            JSONObject().apply {
                put("challengeToken", challengeToken)
                put("response", JSONObject(responseJson))
                if (!name.isNullOrBlank()) put("name", name.trim())
            },
        )
    }

    fun deletePasskeyWithPassword(accessToken: String, id: Int, currentPassword: String) {
        requestJson(
            "/auth/me/passkeys/$id/delete/password",
            "POST",
            accessToken,
            JSONObject().apply { put("currentPassword", currentPassword) },
        )
    }

    fun deletePasskeyWithTotp(accessToken: String, id: Int, code: String) {
        requestJson(
            "/auth/me/passkeys/$id/delete/totp",
            "POST",
            accessToken,
            JSONObject().apply { put("code", code) },
        )
    }

    fun requestPasskeyDeletionEmail(accessToken: String, id: Int): NativeSensitiveEmailChallenge {
        val value = requestJson("/auth/me/passkeys/$id/delete/email", "POST", accessToken, JSONObject())
        return NativeSensitiveEmailChallenge(
            challengeToken = value.optString("challengeToken").ifBlank { throw IllegalStateException("邮箱验证码挑战值缺失") },
            retryAfterSeconds = value.optInt("retryAfterSeconds", 60),
        )
    }

    fun deletePasskeyWithEmail(accessToken: String, id: Int, challengeToken: String, code: String) {
        requestJson(
            "/auth/me/passkeys/$id/delete/email/verify",
            "POST",
            accessToken,
            JSONObject().apply {
                put("challengeToken", challengeToken)
                put("code", code)
            },
        )
    }

    fun passkeyDeletionOptions(accessToken: String, id: Int): NativePasskeyOptions {
        val value = requestJson("/auth/me/passkeys/$id/delete/passkey/options", "POST", accessToken, JSONObject())
        return NativePasskeyOptions(
            challengeToken = value.optString("challengeToken").ifBlank { throw IllegalStateException("通行密钥验证挑战值缺失") },
            requestJson = value.optJSONObject("options")?.toString() ?: throw IllegalStateException("通行密钥验证参数缺失"),
        )
    }

    fun verifyPasskeyDeletion(accessToken: String, id: Int, challengeToken: String, responseJson: String) {
        requestJson(
            "/auth/me/passkeys/$id/delete/passkey/verify",
            "POST",
            accessToken,
            JSONObject().apply {
                put("challengeToken", challengeToken)
                put("response", JSONObject(responseJson))
            },
        )
    }

    fun requestSensitiveEmail(
        accessToken: String,
        action: String,
    ): NativeSensitiveEmailChallenge {
        val value = requestJson(
            "/account-privacy/me/security-verification/$action/email",
            "POST",
            accessToken,
            JSONObject(),
        )
        return NativeSensitiveEmailChallenge(
            challengeToken = value.optString("challengeToken").ifBlank { throw IllegalStateException("邮箱验证码挑战值缺失") },
            retryAfterSeconds = value.optInt("retryAfterSeconds", 60),
        )
    }

    fun verifySensitiveEmail(
        accessToken: String,
        action: String,
        challengeToken: String,
        code: String,
    ): String = verifySensitiveResponse(
        requestJson(
            "/account-privacy/me/security-verification/$action/email/verify",
            "POST",
            accessToken,
            JSONObject().apply {
                put("challengeToken", challengeToken)
                put("code", code)
            },
        ),
    )

    fun verifySensitivePassword(accessToken: String, action: String, currentPassword: String): String =
        verifySensitiveResponse(
            requestJson(
                "/account-privacy/me/security-verification/$action/password",
                "POST",
                accessToken,
                JSONObject().apply { put("currentPassword", currentPassword) },
            ),
        )

    fun verifySensitiveTotp(accessToken: String, action: String, code: String): String =
        verifySensitiveResponse(
            requestJson(
                "/account-privacy/me/security-verification/$action/totp",
                "POST",
                accessToken,
                JSONObject().apply { put("code", code) },
            ),
        )

    fun sensitivePasskeyOptions(accessToken: String, action: String): NativePasskeyOptions {
        val value = requestJson(
            "/auth/me/security-verification/$action/passkey/options",
            "POST",
            accessToken,
            JSONObject(),
        )
        return NativePasskeyOptions(
            challengeToken = value.optString("challengeToken").ifBlank { throw IllegalStateException("通行密钥挑战值缺失") },
            requestJson = value.optJSONObject("options")?.toString() ?: throw IllegalStateException("通行密钥参数缺失"),
        )
    }

    fun verifySensitivePasskey(
        accessToken: String,
        action: String,
        challengeToken: String,
        responseJson: String,
    ): String = verifySensitiveResponse(
        requestJson(
            "/auth/me/security-verification/$action/passkey/verify",
            "POST",
            accessToken,
            JSONObject().apply {
                put("challengeToken", challengeToken)
                put("response", JSONObject(responseJson))
            },
        ),
    )

    fun changePassword(accessToken: String, verificationToken: String, newPassword: String): Int =
        requestJson(
            "/account-privacy/me/password",
            "PATCH",
            accessToken,
            JSONObject().apply {
                put("verificationToken", verificationToken)
                put("newPassword", newPassword)
            },
        ).optInt("revokedSessions")

    fun changeEmail(accessToken: String, verificationToken: String, email: String): String =
        requestJson(
            "/account-privacy/me/email",
            "PATCH",
            accessToken,
            JSONObject().apply {
                put("verificationToken", verificationToken)
                put("email", email)
            },
        ).optString("email").ifBlank { email }

    fun requestDeletion(accessToken: String, verificationToken: String) {
        requestJson(
            "/account-privacy/me/deletion",
            "POST",
            accessToken,
            JSONObject().apply { put("verificationToken", verificationToken) },
        )
    }

    fun cancelDeletion(accessToken: String) {
        requestJson("/account-privacy/me/deletion/cancel", "PATCH", accessToken, JSONObject())
    }

    fun beginTotpEnrollment(accessToken: String, verificationToken: String): NativeTotpEnrollment {
        val value = requestJson(
            "/account-privacy/me/totp/enroll",
            "POST",
            accessToken,
            JSONObject().apply { put("verificationToken", verificationToken) },
        )
        return NativeTotpEnrollment(
            secret = value.optString("secret").ifBlank { throw IllegalStateException("双因素密钥缺失") },
            otpAuthUri = value.optString("otpAuthUri"),
        )
    }

    fun confirmTotp(accessToken: String, code: String): List<String> =
        requestJson(
            "/account-privacy/me/totp/confirm",
            "POST",
            accessToken,
            JSONObject().apply { put("code", code) },
        ).optJSONArray("recoveryCodes").toStringList()

    fun disableTotpWithPassword(accessToken: String, currentPassword: String) {
        requestJson(
            "/account-privacy/me/totp/disable/password",
            "PATCH",
            accessToken,
            JSONObject().apply { put("currentPassword", currentPassword) },
        )
    }

    fun disableTotpWithTotp(accessToken: String, code: String) {
        requestJson(
            "/account-privacy/me/totp/disable",
            "PATCH",
            accessToken,
            JSONObject().apply { put("code", code) },
        )
    }

    fun requestTotpDisableEmail(accessToken: String): NativeSensitiveEmailChallenge {
        val value = requestJson("/account-privacy/me/totp/disable/email", "POST", accessToken, JSONObject())
        return NativeSensitiveEmailChallenge(
            challengeToken = value.optString("challengeToken"),
            retryAfterSeconds = value.optInt("retryAfterSeconds", 60),
        )
    }

    fun disableTotpWithEmail(accessToken: String, code: String) {
        requestJson(
            "/account-privacy/me/totp/disable/email/confirm",
            "POST",
            accessToken,
            JSONObject().apply { put("code", code) },
        )
    }

    fun totpDisablePasskeyOptions(accessToken: String): NativePasskeyOptions {
        val value = requestJson("/auth/me/totp/disable/passkey/options", "POST", accessToken, JSONObject())
        return NativePasskeyOptions(
            challengeToken = value.optString("challengeToken").ifBlank { throw IllegalStateException("通行密钥验证挑战值缺失") },
            requestJson = value.optJSONObject("options")?.toString() ?: throw IllegalStateException("通行密钥验证参数缺失"),
        )
    }

    fun verifyTotpDisablePasskey(accessToken: String, challengeToken: String, responseJson: String) {
        requestJson(
            "/auth/me/totp/disable/passkey/verify",
            "POST",
            accessToken,
            JSONObject().apply {
                put("challengeToken", challengeToken)
                put("response", JSONObject(responseJson))
            },
        )
    }

    private fun verifySensitiveResponse(value: JSONObject): String =
        value.optString("verificationToken").ifBlank { throw IllegalStateException("安全验证令牌缺失") }

    private fun requestJson(
        path: String,
        method: String,
        accessToken: String,
        body: JSONObject? = null,
    ): JSONObject {
        val raw = requestRaw(path, method, accessToken, body)
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun requestRaw(
        path: String,
        method: String,
        accessToken: String,
        body: JSONObject? = null,
    ): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 30_000
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
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: "隐私与数据操作失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            responseBody
        } finally {
            connection.disconnect()
        }
    }
}

private fun JSONArray?.toBlockedUsers(): List<NativeBlockedUser> = buildList {
    if (this@toBlockedUsers == null) return@buildList
    for (index in 0 until length()) {
        val value = getJSONObject(index)
        val user = value.optJSONObject("user") ?: JSONObject()
        add(
            NativeBlockedUser(
                friendshipId = value.optInt("friendshipId"),
                userId = user.optInt("id"),
                username = user.optString("username"),
                nickname = user.optString("nickname").ifBlank { user.optString("username") },
            ),
        )
    }
}

private fun JSONArray?.toAuditRecords(): List<NativePrivacyAuditRecord> = buildList {
    if (this@toAuditRecords == null) return@buildList
    for (index in 0 until length()) {
        val value = getJSONObject(index)
        add(NativePrivacyAuditRecord(value.optInt("id"), value.optString("action"), value.optString("createdAt")))
    }
}

private fun JSONArray?.toSecurityEvents(): List<NativeSecurityEvent> = buildList {
    if (this@toSecurityEvents == null) return@buildList
    for (index in 0 until length()) {
        val value = getJSONObject(index)
        add(
            NativeSecurityEvent(
                id = value.optInt("id"),
                type = value.optString("type"),
                riskLevel = value.optString("riskLevel", "low"),
                summary = value.optString("summary"),
                createdAt = value.optString("createdAt"),
            ),
        )
    }
}

private fun JSONArray?.toTrustedDevices(): List<NativeTrustedDevice> = buildList {
    if (this@toTrustedDevices == null) return@buildList
    for (index in 0 until length()) {
        val value = getJSONObject(index)
        add(
            NativeTrustedDevice(
                id = value.optInt("id"),
                label = value.optString("label").ifBlank { "已信任设备" },
                ip = value.optString("lastIp").ifBlank { value.optString("firstIp") },
                current = value.optBoolean("current"),
                lastSeenAt = value.optString("lastSeenAt"),
            ),
        )
    }
}

private fun JSONArray?.toPasskeys(): List<NativePasskeySummary> = buildList {
    if (this@toPasskeys == null) return@buildList
    for (index in 0 until length()) {
        val value = getJSONObject(index)
        add(
            NativePasskeySummary(
                id = value.optInt("id"),
                name = value.optString("name").ifBlank { "未命名通行密钥" },
                createdAt = value.optString("createdAt"),
                lastUsedAt = value.optString("lastUsedAt").takeIf(String::isNotBlank),
            ),
        )
    }
}

private fun JSONArray?.toStringList(): List<String> = buildList {
    if (this@toStringList == null) return@buildList
    for (index in 0 until length()) {
        val value = optString(index).trim()
        if (value.isNotBlank()) add(value)
    }
}

private fun JSONObject.toExportJob(): NativePrivacyExportJob = NativePrivacyExportJob(
    id = optInt("id"),
    status = optString("status", "queued"),
    error = optString("error").takeIf(String::isNotBlank),
    expiresAt = optString("expiresAt"),
    completedAt = optString("completedAt").takeIf(String::isNotBlank),
)
