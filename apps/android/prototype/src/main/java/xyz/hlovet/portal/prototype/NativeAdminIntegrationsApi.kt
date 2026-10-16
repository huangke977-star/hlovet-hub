package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI

internal data class NativeAdminGoogleOAuthConfig(
    val managed: Boolean,
    val enabled: Boolean,
    val clientId: String,
    val clientSecretConfigured: Boolean,
    val redirectUri: String,
    val source: String,
    val encryptionConfigured: Boolean,
    val updatedAt: String,
)

internal data class NativeAdminGoogleOAuthDraft(val enabled: Boolean, val clientId: String, val redirectUri: String)

internal class NativeIntegrationHttpException(val status: Int, message: String) : IllegalStateException(message)

internal fun NativeAdminGoogleOAuthConfig.toDraft() = NativeAdminGoogleOAuthDraft(enabled, clientId, redirectUri)

internal fun nativeGoogleOAuthDraftError(config: NativeAdminGoogleOAuthConfig, draft: NativeAdminGoogleOAuthDraft, secret: String): String? {
    if (!config.encryptionConfigured) return nativePhrase("服务器尚未配置密钥加密，不能保存 Google 配置。", "Secret encryption is not configured. Google settings cannot be saved.")
    if (draft.clientId.length > 255 || secret.length > 500 || draft.redirectUri.length > 512)
        return nativePhrase("Google 配置超过允许长度，请缩短后重试。", "A Google setting exceeds its allowed length. Shorten it and retry.")
    if (draft.enabled && (draft.clientId.isBlank() || draft.redirectUri.isBlank() || (!config.clientSecretConfigured && secret.isBlank())))
        return nativePhrase("启用 Google 登录前请配置 Client ID、Client Secret 和回调地址。", "Configure the client ID, client secret, and redirect URI before enabling Google sign-in.")
    if (draft.enabled && !nativeIntegrationHttpsUrl(draft.redirectUri.trim(), allowLocalhost = true))
        return nativePhrase("Google 回调地址必须是有效的 HTTPS 地址（本地开发可使用 localhost）。", "Use a valid HTTPS redirect URI; HTTP localhost is allowed for development.")
    return null
}

internal val nativeIntegrationEvents = listOf("integration.test", "user.registered", "article.published", "article.commented", "notification.created", "resource.redeemed")

internal fun nativeWebhookDraftError(name: String, url: String, secret: String, events: List<String>): String? = when {
    name.isBlank() || name.trim().length > 120 -> nativePhrase("Webhook 名称需要 1 到 120 个字符。", "A webhook name must contain 1 to 120 characters.")
    url.trim().length > 512 || !nativeIntegrationHttpsUrl(url.trim()) -> nativePhrase("Webhook 地址必须是有效的 HTTPS 地址，且不超过 512 个字符。", "Use a valid HTTPS webhook URL of no more than 512 characters.")
    secret.length !in 16..255 -> nativePhrase("签名 Secret 需要 16 到 255 个字符。", "The signing secret must contain 16 to 255 characters.")
    events.isEmpty() || events.any { it !in nativeIntegrationEvents } -> nativePhrase("至少选择一个支持的 Webhook 事件。", "Select at least one supported webhook event.")
    else -> null
}

private fun nativeIntegrationHttpsUrl(value: String, allowLocalhost: Boolean = false): Boolean = runCatching {
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase()
    !uri.host.isNullOrBlank() && (scheme == "https" || (allowLocalhost && scheme == "http" && uri.host.equals("localhost", ignoreCase = true))) &&
        uri.port in -1..65535 && uri.port != 0
}.getOrDefault(false)

internal data class NativeAdminWebhook(
    val id: Int,
    val name: String,
    val url: String,
    val events: List<String>,
    val enabled: Boolean,
    val deliveryCount: Int,
    val lastDeliveredAt: String,
    val lastError: String,
    val createdAt: String,
    val updatedAt: String,
)

internal data class NativeAdminWebhookDelivery(
    val id: Int,
    val endpointId: Int,
    val eventId: String,
    val eventType: String,
    val status: String,
    val attempts: Int,
    val lastError: String,
    val createdAt: String,
    val nextAttemptAt: String,
    val deliveredAt: String,
)

internal object NativeAdminIntegrationsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun getGoogleOAuth(accessToken: String): NativeAdminGoogleOAuthConfig = requestObject(
        "/integrations/admin/google-oauth",
        accessToken,
    ).toGoogleOAuth()

    fun updateGoogleOAuth(
        accessToken: String,
        enabled: Boolean,
        clientId: String,
        clientSecret: String,
        redirectUri: String,
    ): NativeAdminGoogleOAuthConfig {
        val body = JSONObject().apply {
            put("enabled", enabled)
            put("clientId", clientId)
            put("redirectUri", redirectUri)
            if (clientSecret.isNotBlank()) put("clientSecret", clientSecret)
        }
        return requestObject("/integrations/admin/google-oauth", accessToken, "PATCH", body).toGoogleOAuth()
    }

    fun listWebhooks(accessToken: String): List<NativeAdminWebhook> = requestJson(
        "/integrations/admin/webhooks",
        accessToken,
    ).asArray("items").toWebhooks()

    fun createWebhook(
        accessToken: String,
        name: String,
        url: String,
        secret: String,
        events: List<String>,
    ): NativeAdminWebhook = requestObject(
        "/integrations/admin/webhooks",
        accessToken,
        "POST",
        JSONObject().apply {
            put("name", name)
            put("url", url)
            put("secret", secret)
            put("events", JSONArray(events))
        },
    ).toWebhook()

    fun updateWebhook(accessToken: String, id: Int, enabled: Boolean): NativeAdminWebhook = requestObject(
        "/integrations/admin/webhooks/$id",
        accessToken,
        "PATCH",
        JSONObject().put("enabled", enabled),
    ).toWebhook()

    fun deleteWebhook(accessToken: String, id: Int) {
        requestJson("/integrations/admin/webhooks/$id", accessToken, "DELETE", allowEmptySuccess = true)
    }

    fun testWebhook(accessToken: String): String = requestJson(
        "/integrations/admin/webhooks/test",
        accessToken,
        "POST",
        JSONObject(),
        allowEmptySuccess = true,
    ).asObject().textOrEmpty("message").ifBlank { nativePhrase("测试事件已提交，请查看投递记录。", "Test event submitted. Check the delivery records.") }

    fun listDeliveries(accessToken: String): List<NativeAdminWebhookDelivery> = requestJson(
        "/integrations/admin/webhook-deliveries",
        accessToken,
    ).asArray("items").toDeliveries()

    fun replayDelivery(accessToken: String, id: Int): String = requestJson(
        "/integrations/admin/webhook-deliveries/$id/replay",
        accessToken,
        "POST",
        JSONObject(),
    ).asObject().textOrEmpty("message").ifBlank { nativePhrase("重放请求已完成，请查看投递状态。", "Replay request completed. Check the delivery status.") }

    private fun requestObject(path: String, accessToken: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        requestJson(path, accessToken, method, body).asObject()

    private fun requestJson(path: String, accessToken: String, method: String = "GET", body: JSONObject? = null, allowEmptySuccess: Boolean = false): Any {
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
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            if (status !in 200..299) {
                val message = runCatching {
                    when (val value = JSONObject(response).opt("message")) {
                        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }.filter(String::isNotBlank).joinToString("\n")
                        is String -> value
                        else -> ""
                    }
                }.getOrNull().orEmpty()
                throw NativeIntegrationHttpException(status, message.ifBlank { nativePhrase("外部集成请求失败（HTTP $status）。", "Integration request failed (HTTP $status).") })
            }
            when {
                response.isBlank() -> if (allowEmptySuccess) JSONObject() else throw IllegalStateException(nativePhrase("外部集成返回为空，请重试。", "The integration response is empty. Please retry."))
                response.startsWith("[") -> JSONArray(response)
                else -> JSONObject(response)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun Any.asObject(): JSONObject = when (this) {
        is JSONObject -> this
        else -> throw IllegalStateException(nativePhrase("外部集成返回格式不正确，请重试。", "The integration response has an invalid format. Please retry."))
    }

    private fun Any.asArray(key: String): JSONArray = when (this) {
        is JSONArray -> this
        is JSONObject -> optJSONArray(key) ?: optJSONArray("data") ?: throw IllegalStateException(nativePhrase("集成列表返回格式不正确，请重试。", "The integration list has an invalid format. Please retry."))
        else -> throw IllegalStateException(nativePhrase("集成列表返回格式不正确，请重试。", "The integration list has an invalid format. Please retry."))
    }

    internal fun JSONObject.toGoogleOAuth(): NativeAdminGoogleOAuthConfig {
        check(opt("enabled") is Boolean && opt("encryptionConfigured") is Boolean) { nativePhrase("Google 配置返回格式不正确，请重试。", "The Google settings response has an invalid format. Please retry.") }
        return NativeAdminGoogleOAuthConfig(
        managed = optBoolean("managed"),
        enabled = optBoolean("enabled"),
        clientId = textOrEmpty("clientId"),
        clientSecretConfigured = optBoolean("clientSecretConfigured"),
        redirectUri = textOrEmpty("redirectUri"),
        source = textOrEmpty("source"),
        encryptionConfigured = optBoolean("encryptionConfigured"),
        updatedAt = textOrEmpty("updatedAt"),
    )
    }

    internal fun JSONObject.toWebhook(): NativeAdminWebhook {
        check(optInt("id") > 0) { nativePhrase("Webhook 返回格式不正确，请重试。", "The webhook response has an invalid format. Please retry.") }
        return NativeAdminWebhook(
        id = optInt("id"),
        name = textOrEmpty("name"),
        url = textOrEmpty("url"),
        events = optJSONArray("events").toStringList(),
        enabled = optBoolean("enabled"),
        deliveryCount = optInt("deliveryCount"),
        lastDeliveredAt = textOrEmpty("lastDeliveredAt"),
        lastError = textOrEmpty("lastError"),
        createdAt = textOrEmpty("createdAt"),
        updatedAt = textOrEmpty("updatedAt"),
    )
    }

    private fun JSONArray?.toStringList(): List<String> = buildList {
        val array = this@toStringList ?: return@buildList
        for (index in 0 until array.length()) {
            (array.opt(index) as? String)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun JSONArray.toWebhooks(): List<NativeAdminWebhook> = buildList {
        for (index in 0 until length()) optJSONObject(index)?.let { add(it.toWebhook()) }
    }

    internal fun JSONArray.toDeliveries(): List<NativeAdminWebhookDelivery> = buildList {
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeAdminWebhookDelivery(
                    id = value.optInt("id"),
                    endpointId = value.optInt("endpointId"),
                    eventId = value.textOrEmpty("eventId"),
                    eventType = value.textOrEmpty("eventType"),
                    status = value.textOrEmpty("status"),
                    attempts = value.optInt("attempts"),
                    lastError = value.textOrEmpty("lastError"),
                    createdAt = value.textOrEmpty("createdAt"),
                    nextAttemptAt = value.textOrEmpty("nextAttemptAt"),
                    deliveredAt = value.textOrEmpty("deliveredAt"),
                ),
            )
        }
    }

    private fun JSONObject.textOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
}
