package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeReadOnlyToken(
    val id: Int,
    val name: String,
    val tokenPrefix: String,
    val scopes: List<String>,
    val expiresAt: String?,
    val lastUsedAt: String?,
    val revokedAt: String?,
    val createdAt: String,
)

internal data class NativeCreatedReadOnlyToken(
    val id: Int,
    val name: String,
    val token: String,
    val tokenPrefix: String,
    val scopes: List<String>,
    val expiresAt: String?,
    val createdAt: String,
)

internal data class NativeExternalChannel(
    val id: Int,
    val kind: String,
    val endpoint: String,
    val preferences: Map<String, Boolean>,
    val enabled: Boolean,
    val verified: Boolean,
    val failureCount: Int,
    val lastError: String?,
    val lastDeliveredAt: String?,
    val createdAt: String,
)

internal data class NativeExternalChannelCreation(
    val channel: NativeExternalChannel,
    val verificationRequired: Boolean,
    val verificationExpiresAt: String?,
)

/** Ordinary-user API for read-only tokens and external notification channels. */
internal object NativeIntegrationsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun listTokens(accessToken: String): List<NativeReadOnlyToken> =
        JSONArray(requestRaw("/integrations/tokens", "GET", accessToken)).toTokens()

    fun createToken(
        accessToken: String,
        name: String,
        scopes: List<String>,
        expiresAt: String?,
    ): NativeCreatedReadOnlyToken {
        val body = JSONObject().apply {
            put("name", name.trim())
            put("scopes", JSONArray(scopes.distinct()))
            if (!expiresAt.isNullOrBlank()) put("expiresAt", expiresAt.trim())
        }
        return requestJson("/integrations/tokens", "POST", accessToken, body).toCreatedToken()
    }

    fun revokeToken(accessToken: String, id: Int) {
        requestJson("/integrations/tokens/$id", "DELETE", accessToken)
    }

    fun listChannels(accessToken: String): List<NativeExternalChannel> =
        JSONArray(requestRaw("/integrations/external-channels", "GET", accessToken)).toChannels()

    fun createChannel(accessToken: String, endpoint: String, secret: String?): NativeExternalChannelCreation {
        val body = JSONObject().apply {
            put("kind", "webhook")
            put("endpoint", endpoint.trim())
            if (!secret.isNullOrBlank()) put("secret", secret.trim())
        }
        val value = requestJson("/integrations/external-channels", "POST", accessToken, body)
        return NativeExternalChannelCreation(
            channel = value.toChannel(),
            verificationRequired = value.optBoolean("verificationRequired"),
            verificationExpiresAt = value.optStringOrNull("verificationExpiresAt"),
        )
    }

    fun verifyChannel(accessToken: String, id: Int, code: String): NativeExternalChannel =
        requestJson(
            "/integrations/external-channels/$id/verify",
            "POST",
            accessToken,
            JSONObject().put("code", code),
        ).toChannel()

    fun updateChannel(accessToken: String, id: Int, enabled: Boolean): NativeExternalChannel =
        requestJson(
            "/integrations/external-channels/$id",
            "PATCH",
            accessToken,
            JSONObject().put("enabled", enabled),
        ).toChannel()

    fun deleteChannel(accessToken: String, id: Int) {
        requestJson("/integrations/external-channels/$id", "DELETE", accessToken)
    }

    fun bindGoogleIdentity(accessToken: String, pendingToken: String, verificationToken: String) {
        requestJson(
            "/auth/google/bind",
            "POST",
            accessToken,
            JSONObject().apply {
                put("pendingToken", pendingToken)
                put("verificationToken", verificationToken)
            },
        )
    }

    private fun requestJson(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        val raw = requestRaw(path, method, accessToken, body)
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun requestRaw(path: String, method: String, accessToken: String, body: JSONObject? = null): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
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
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(errorMessage(response, "集成操作失败（HTTP $status）"))
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(body: String, fallback: String): String = runCatching {
        val message = JSONObject(body).opt("message")
        when (message) {
            is JSONArray -> message.join("、")
            is String -> message
            else -> fallback
        }
    }.getOrDefault(fallback).ifBlank { fallback }

    private fun JSONArray.toTokens(): List<NativeReadOnlyToken> = buildList {
        for (index in 0 until length()) optJSONObject(index)?.let { add(it.toToken()) }
    }

    private fun JSONArray.toChannels(): List<NativeExternalChannel> = buildList {
        for (index in 0 until length()) optJSONObject(index)?.let { add(it.toChannel()) }
    }

    private fun JSONObject.toToken() = NativeReadOnlyToken(
        id = optInt("id"),
        name = optString("name", "未命名令牌"),
        tokenPrefix = optString("tokenPrefix"),
        scopes = optJSONArray("scopes").toStrings(),
        expiresAt = optStringOrNull("expiresAt"),
        lastUsedAt = optStringOrNull("lastUsedAt"),
        revokedAt = optStringOrNull("revokedAt"),
        createdAt = optString("createdAt"),
    )

    private fun JSONObject.toCreatedToken() = NativeCreatedReadOnlyToken(
        id = optInt("id"),
        name = optString("name", "未命名令牌"),
        token = optString("token"),
        tokenPrefix = optString("tokenPrefix"),
        scopes = optJSONArray("scopes").toStrings(),
        expiresAt = optStringOrNull("expiresAt"),
        createdAt = optString("createdAt"),
    )

    private fun JSONObject.toChannel() = NativeExternalChannel(
        id = optInt("id"),
        kind = optString("kind", "webhook"),
        endpoint = optString("endpoint"),
        preferences = optJSONObject("preferences").toBooleanMap(),
        enabled = optBoolean("enabled"),
        verified = optBoolean("verified"),
        failureCount = optInt("failureCount"),
        lastError = optStringOrNull("lastError"),
        lastDeliveredAt = optStringOrNull("lastDeliveredAt"),
        createdAt = optString("createdAt"),
    )

    private fun JSONObject?.toBooleanMap(): Map<String, Boolean> = buildMap {
        val value = this@toBooleanMap ?: return@buildMap
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, value.optBoolean(key))
        }
    }

    private fun JSONArray?.toStrings(): List<String> = buildList {
        for (index in 0 until (this@toStrings?.length() ?: 0)) {
            this@toStrings?.optString(index)?.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
}
