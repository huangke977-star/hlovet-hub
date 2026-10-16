package xyz.hlovet.portal.prototype

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public distribution endpoints that can be opened from an email without a session. */
internal object NativeDistributionApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun unsubscribeEmail(token: String) {
        val normalized = token.trim()
        require(normalized.isNotBlank()) { "退订链接缺少 token。" }
        val encodedToken = Uri.encode(normalized)
        val connection = (URL("$baseUrl/distribution/email/unsubscribe/$encodedToken").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 18_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Length", "0")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    val raw = JSONObject(body).opt("message")
                    when (raw) {
                        is JSONArray -> raw.join("、")
                        is String -> raw
                        else -> ""
                    }
                }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "退订链接不可用（HTTP $status）。" })
            }
        } finally {
            connection.disconnect()
        }
    }
}
