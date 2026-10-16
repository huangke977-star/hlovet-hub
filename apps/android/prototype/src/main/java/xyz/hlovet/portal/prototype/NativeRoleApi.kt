package xyz.hlovet.portal.prototype

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeRole(
    val code: String,
    val name: String,
    val level: Int,
)

internal object NativeRoleApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun listRoles(): List<NativeRole> {
        val connection = (URL("$baseUrl/roles").openConnection() as HttpURLConnection).apply {
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
                throw IllegalStateException("角色列表加载失败（HTTP $status）")
            }
            JSONArray(body).toRoles()
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray.toRoles(): List<NativeRole> = buildList {
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            val code = value.optString("code").trim()
            val name = value.optString("name").trim()
            if (code.isNotBlank() && name.isNotBlank()) {
                add(NativeRole(code = code, name = name, level = value.optInt("level")))
            }
        }
    }
}
