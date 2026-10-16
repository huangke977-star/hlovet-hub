package xyz.hlovet.portal.prototype

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeInstallSettings(
    val siteName: String,
    val enabled: Boolean,
)

internal object NativeInstallApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun loadSettings(): NativeInstallSettings {
        val response = request("/site-settings/public")
        return NativeInstallSettings(
            siteName = response.optString("siteName", "HLOVET").ifBlank { "HLOVET" },
            enabled = response.optBoolean("installPageEnabled", true),
        )
    }

    fun latestRelease(): NativeAndroidRelease? = NativeAdminAndroidApi.latest()

    fun webInstallUrl(): String {
        val origin = baseUrl.substringBeforeLast("/api").trimEnd('/').ifBlank { baseUrl }
        return "$origin/install"
    }

    private fun request(path: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { "安装配置读取失败（HTTP $status）。" })
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
