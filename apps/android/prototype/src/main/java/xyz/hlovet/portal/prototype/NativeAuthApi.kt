package xyz.hlovet.portal.prototype

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: Int = 0,
    val username: String,
    val nickname: String,
    val appearance: PreviewAppearance,
    val email: String = "",
    val profileBio: String = "",
    val avatarUrl: String? = null,
    val locale: String = "zh-CN",
    val isSuperAdmin: Boolean = false,
    val isAdministrator: Boolean = false,
)

internal data class NativeAuthSession(
    val id: String,
    val issuedAt: String,
    val expiresAt: String,
    val ip: String,
    val userAgent: String,
    val current: Boolean,
)

internal sealed interface NativeAuthResult {
    data class Authenticated(val session: NativeSession) : NativeAuthResult
    data class DeviceVerification(
        val challengeToken: String,
        val emailHint: String,
        val retryAfterSeconds: Int,
    ) : NativeAuthResult
    data class TotpVerification(val challengeToken: String) : NativeAuthResult
    data class GoogleLinkRequired(
        val pendingToken: String,
        val email: String,
        val methods: GoogleLinkMethods,
    ) : NativeAuthResult
}

internal data class GoogleLinkMethods(
    val passkey: Boolean,
    val email: Boolean,
    val totp: Boolean,
    val password: Boolean,
)

internal data class NativePasskeyOptions(
    val challengeToken: String,
    val requestJson: String,
)

internal data class NativePasskeyCreationOptions(
    val challengeToken: String,
    val requestJson: String,
)

internal data class NativeRegistrationPolicy(
    val registrationOpen: Boolean,
    val emailVerificationEnabled: Boolean,
    val turnstileEnabled: Boolean,
    val loginTurnstileEnabled: Boolean,
    val loginTurnstileFailureThreshold: Int,
    val passwordRecoveryEnabled: Boolean,
    val recoveryTurnstileEnabled: Boolean,
    val turnstileSiteKey: String,
)

internal data class NativeRegistrationCodeResult(
    val retryAfterSeconds: Int,
)

internal data class NativeDeviceVerificationResend(
    val challengeToken: String,
    val emailHint: String,
    val retryAfterSeconds: Int,
)

internal fun parseNativeDeviceVerificationResend(
    response: JSONObject,
    fallbackChallengeToken: String,
): NativeDeviceVerificationResend = NativeDeviceVerificationResend(
    challengeToken = response.optString("challengeToken").ifBlank { fallbackChallengeToken },
    emailHint = response.optString("emailHint"),
    retryAfterSeconds = response.optInt("retryAfterSeconds", 60).coerceAtLeast(1),
)

internal class NativeAuthHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

internal object NativeAuthApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"
    const val USER_AGENT = "HLOVET-Android-Preview"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun login(account: String, password: String, turnstileToken: String? = null): NativeAuthResult =
        parseLoginResponse(request("/auth/login", JSONObject().apply {
            put("account", account)
            put("password", password)
            if (!turnstileToken.isNullOrBlank()) put("turnstileToken", turnstileToken)
        }))

    fun refresh(refreshToken: String): NativeSession =
        parseSession(request("/auth/refresh", JSONObject().apply { put("refreshToken", refreshToken) }))

    fun logout(refreshToken: String) {
        request("/auth/logout", JSONObject().apply { put("refreshToken", refreshToken) })
    }

    fun listSessions(accessToken: String): List<NativeAuthSession> {
        val response = authorizedRequest("/auth/sessions", "POST", accessToken)
        val items = response.optJSONArray("sessions") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(
                    NativeAuthSession(
                        id = item.optString("id"),
                        issuedAt = item.optString("issuedAt"),
                        expiresAt = item.optString("expiresAt"),
                        ip = item.optString("ip", "unknown"),
                        userAgent = item.optString("userAgent", "unknown"),
                        current = item.optBoolean("current"),
                    ),
                )
            }
        }
    }

    fun revokeOtherSessions(accessToken: String): Int =
        authorizedRequest("/auth/sessions/revoke-others", "POST", accessToken).optInt("revokedSessions")

    fun revokeAllSessions(accessToken: String): Int =
        authorizedRequest("/auth/sessions/revoke-all", "POST", accessToken).optInt("revokedSessions")

    fun revokeSession(accessToken: String, sessionId: String): Boolean {
        val normalizedId = sessionId.trim()
        require(normalizedId.isNotBlank()) { "登录设备标识缺失。" }
        val encodedId = Uri.encode(normalizedId)
        return authorizedRequest("/auth/sessions/$encodedId", "DELETE", accessToken).optBoolean("current")
    }

    fun verifyDevice(challengeToken: String, code: String): NativeAuthResult =
        parseLoginResponse(request("/auth/login/device-verification", JSONObject().apply {
            put("challengeToken", challengeToken)
            put("code", code)
        }))

    fun resendDeviceVerification(challengeToken: String): NativeDeviceVerificationResend {
        val response = request("/auth/login/device-verification/resend", JSONObject().apply {
            put("challengeToken", challengeToken)
        })
        return parseNativeDeviceVerificationResend(response, challengeToken)
    }

    fun verifyTotp(challengeToken: String, code: String): NativeAuthResult =
        parseLoginResponse(request("/auth/login/totp-verification", JSONObject().apply {
            put("challengeToken", challengeToken)
            put("code", code)
        }))

    fun registrationPolicy(): NativeRegistrationPolicy {
        val siteSettings = requestGet("/site-settings/public")
        val securityPolicy = requestGet("/auth/security-policy")
        val turnstile = securityPolicy.optJSONObject("turnstile") ?: JSONObject()
        return NativeRegistrationPolicy(
            registrationOpen = siteSettings.optBoolean("registrationOpen", true),
            emailVerificationEnabled = securityPolicy.optBoolean("registrationEmailVerificationEnabled"),
            turnstileEnabled = turnstile.optBoolean("registrationEnabled"),
            loginTurnstileEnabled = turnstile.optBoolean("loginEnabled"),
            loginTurnstileFailureThreshold = turnstile.optInt("loginFailureThreshold", 5).coerceAtLeast(1),
            passwordRecoveryEnabled = securityPolicy.optBoolean("passwordRecoveryEnabled"),
            recoveryTurnstileEnabled = turnstile.optBoolean("recoveryEnabled"),
            turnstileSiteKey = turnstile.optString("siteKey"),
        )
    }

    fun requestPasswordRecovery(email: String, turnstileToken: String?) {
        request("/auth/password-recovery/request", JSONObject().apply {
            put("email", email)
            put("client", "android")
            if (!turnstileToken.isNullOrBlank()) put("turnstileToken", turnstileToken)
        })
    }

    fun resetPassword(token: String, newPassword: String, turnstileToken: String?): Int {
        val response = request("/auth/password-recovery/reset", JSONObject().apply {
            put("token", token)
            put("newPassword", newPassword)
            if (!turnstileToken.isNullOrBlank()) put("turnstileToken", turnstileToken)
        })
        return response.optInt("revokedSessions")
    }

    fun requestRegistrationCode(email: String, turnstileToken: String?): NativeRegistrationCodeResult {
        val response = request("/auth/registration-code", JSONObject().apply {
            put("email", email)
            if (!turnstileToken.isNullOrBlank()) put("turnstileToken", turnstileToken)
        })
        return NativeRegistrationCodeResult(response.optInt("retryAfterSeconds", 60))
    }

    fun register(
        username: String,
        nickname: String,
        email: String,
        password: String,
        verificationCode: String?,
        turnstileToken: String?,
    ): NativeAuthResult = NativeAuthResult.Authenticated(parseSession(request("/auth/register", JSONObject().apply {
        put("username", username)
        put("nickname", nickname)
        put("email", email)
        put("password", password)
        if (!verificationCode.isNullOrBlank()) put("verificationCode", verificationCode)
        if (!turnstileToken.isNullOrBlank()) put("turnstileToken", turnstileToken)
    })))

    fun passkeyLoginOptions(): NativePasskeyOptions {
        val response = request("/auth/passkeys/login/options", JSONObject())
        return NativePasskeyOptions(
            challengeToken = response.optString("challengeToken").ifBlank { throw IllegalStateException("通行密钥挑战值缺失") },
            requestJson = response.optJSONObject("options")?.toString() ?: throw IllegalStateException("通行密钥参数缺失"),
        )
    }

    fun verifyPasskeyLogin(challengeToken: String, responseJson: String): NativeAuthResult =
        parseLoginResponse(request("/auth/passkeys/login/verify", JSONObject().apply {
            put("challengeToken", challengeToken)
            put("response", JSONObject(responseJson))
        }))

    fun externalAuthProviders(): Boolean =
        requestGet("/auth/providers").optJSONObject("google")?.optBoolean("enabled") == true

    fun startGoogleLogin(returnTo: String = "/dashboard", linkOnly: Boolean = false): String {
        val purpose = if (linkOnly) "&purpose=link" else ""
        val path = "/auth/google/start?native=android&returnTo=${Uri.encode(returnTo)}$purpose"
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "text/html")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 300..399) {
                throw IllegalStateException("Google 登录暂不可用（HTTP ${connection.responseCode}）")
            }
            connection.getHeaderField("Location")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Google 授权地址缺失")
        } finally {
            connection.disconnect()
        }
    }

    fun consumeOAuthResult(token: String): NativeAuthResult =
        parseLoginResponse(request("/auth/oauth/result", JSONObject().apply { put("token", token) }))

    fun googleLinkPasskeyOptions(pendingToken: String): NativePasskeyOptions {
        val response = request("/auth/google/link/passkey/options", JSONObject().apply { put("pendingToken", pendingToken) })
        return NativePasskeyOptions(
            challengeToken = response.optString("challengeToken").ifBlank { throw IllegalStateException("Google 关联挑战值缺失") },
            requestJson = response.optJSONObject("options")?.toString() ?: throw IllegalStateException("Google 关联参数缺失"),
        )
    }

    fun verifyGoogleLinkPasskey(pendingToken: String, challengeToken: String, responseJson: String): NativeAuthResult =
        parseLoginResponse(request("/auth/google/link/passkey/verify", JSONObject().apply {
            put("pendingToken", pendingToken)
            put("challengeToken", challengeToken)
            put("response", JSONObject(responseJson))
        }))

    fun requestGoogleLinkEmail(pendingToken: String): GoogleLinkEmailChallenge {
        val response = request("/auth/google/link/email", JSONObject().apply { put("pendingToken", pendingToken) })
        return GoogleLinkEmailChallenge(
            challengeToken = response.optString("challengeToken").ifBlank { throw IllegalStateException("邮箱验证码挑战值缺失") },
            emailHint = response.optString("emailHint"),
        )
    }

    fun verifyGoogleLinkEmail(pendingToken: String, challengeToken: String, code: String): NativeAuthResult =
        parseLoginResponse(request("/auth/google/link/email/verify", JSONObject().apply {
            put("pendingToken", pendingToken)
            put("challengeToken", challengeToken)
            put("code", code)
        }))

    fun verifyGoogleLinkTotp(pendingToken: String, code: String): NativeAuthResult =
        parseLoginResponse(request("/auth/google/link/totp", JSONObject().apply {
            put("pendingToken", pendingToken)
            put("code", code)
        }))

    fun verifyGoogleLinkPassword(pendingToken: String, password: String): NativeAuthResult =
        parseLoginResponse(request("/auth/google/link/password", JSONObject().apply {
            put("pendingToken", pendingToken)
            put("currentPassword", password)
        }))

    private fun parseLoginResponse(response: JSONObject): NativeAuthResult {
        if (response.optBoolean("oauthLinkRequired")) {
            val methods = response.optJSONObject("methods") ?: JSONObject()
            return NativeAuthResult.GoogleLinkRequired(
                pendingToken = response.optString("pendingToken").ifBlank { throw IllegalStateException("Google 关联令牌缺失") },
                email = response.optString("email"),
                methods = GoogleLinkMethods(
                    passkey = methods.optBoolean("passkey"),
                    email = methods.optBoolean("email"),
                    totp = methods.optBoolean("totp"),
                    password = methods.optBoolean("password"),
                ),
            )
        }
        if (response.optBoolean("deviceVerificationRequired")) {
            return NativeAuthResult.DeviceVerification(
                challengeToken = response.optString("challengeToken"),
                emailHint = response.optString("emailHint"),
                retryAfterSeconds = response.optInt("retryAfterSeconds", 60).coerceAtLeast(1),
            )
        }
        if (response.optBoolean("totpVerificationRequired")) {
            return NativeAuthResult.TotpVerification(response.optString("challengeToken"))
        }
        return NativeAuthResult.Authenticated(parseSession(response))
    }

    private fun requestGet(path: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw NativeAuthHttpException(status, "请求接口失败（HTTP $status）")
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSession(response: JSONObject): NativeSession {
        val user = response.optJSONObject("user") ?: throw IllegalStateException("登录响应缺少用户信息")
        val appearance = user.optJSONObject("appearance") ?: JSONObject()
        return NativeSession(
            accessToken = response.optString("accessToken").ifBlank { throw IllegalStateException("登录响应缺少访问令牌") },
            refreshToken = response.optString("refreshToken"),
            userId = user.optInt("id", 0),
            username = user.optString("username", "user"),
            nickname = user.optString("nickname", user.optString("username", "user")),
            appearance = PreviewApi.appearanceFromJson(appearance),
            email = user.optString("email"),
            profileBio = user.optString("profileBio"),
            avatarUrl = user.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
            locale = user.optString("locale", "zh-CN"),
            isSuperAdmin = user.optBoolean("isSuperAdmin"),
            isAdministrator = user.optBoolean("isAdministrator"),
        )
    }

    /** 发送已认证的会话操作请求；关闭连接复用以避免本地代理半关闭响应造成 EOF。 */
    private fun authorizedRequest(path: String, method: String, accessToken: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 12_000
            doOutput = method == "POST"
            setRequestProperty("Accept", "application/json")
            // Android 模拟器经由 ADB reverse 的本地代理偶发复用半关闭连接，认证操作必须读取完整响应。
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Authorization", "Bearer $accessToken")
            if (doOutput) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            if (connection.doOutput) {
                connection.outputStream.bufferedWriter().use { it.write("{}") }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "会话操作失败（HTTP $status）"
                throw NativeAuthHttpException(status, message)
            }
            if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    /** 发送认证相关 JSON 请求；请求结束后立即断开连接，避免下一个认证步骤继承损坏连接。 */
    private fun request(path: String, body: JSONObject): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            // 登录/注册/登出都经过本地 ADB reverse，关闭 keep-alive 只影响认证请求的连接复用。
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "登录失败（HTTP $status）"
                throw NativeAuthHttpException(status, message)
            }
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }
}

internal data class GoogleLinkEmailChallenge(
    val challengeToken: String,
    val emailHint: String,
)
