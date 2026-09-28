package xyz.hlovet.portal.prototype

import org.json.JSONObject
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
}

internal object NativeAuthApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

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

    private fun parseLoginResponse(response: JSONObject): NativeAuthResult {
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
