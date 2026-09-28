package xyz.hlovet.portal.prototype

import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeSession(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val nickname: String,
    val appearance: PreviewAppearance,
)

internal sealed interface NativeAuthResult {
    data class Authenticated(val session: NativeSession) : NativeAuthResult
    data class DeviceVerification(val challengeToken: String, val emailHint: String) : NativeAuthResult
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

internal object NativeAuthApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"
    const val USER_AGENT = "HLOVET-Android-Preview"

    init {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun login(account: String, password: String): NativeAuthResult =
        parseLoginResponse(request("/auth/login", JSONObject().apply {
            put("account", account)
            put("password", password)
        }))

    fun verifyDevice(challengeToken: String, code: String): NativeAuthResult =
        parseLoginResponse(request("/auth/login/device-verification", JSONObject().apply {
            put("challengeToken", challengeToken)
            put("code", code)
        }))

    fun verifyTotp(challengeToken: String, code: String): NativeAuthResult =
        parseLoginResponse(request("/auth/login/totp-verification", JSONObject().apply {
            put("challengeToken", challengeToken)
            put("code", code)
        }))

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

    fun startGoogleLogin(): String {
        val path = "/auth/google/start?native=android&returnTo=%2Fdashboard"
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
            if (status !in 200..299) throw IllegalStateException("请求接口失败（HTTP $status）")
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
            username = user.optString("username", "user"),
            nickname = user.optString("nickname", user.optString("username", "user")),
            appearance = PreviewApi.appearanceFromJson(appearance),
        )
    }

    private fun request(path: String, body: JSONObject): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
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
                throw IllegalStateException(message)
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
