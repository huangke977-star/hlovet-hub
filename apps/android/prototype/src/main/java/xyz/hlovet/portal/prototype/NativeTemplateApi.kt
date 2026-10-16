package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeArticleTemplate(
    val id: Int,
    val name: String,
    val title: String,
    val summary: String,
    val content: String,
    val contentFormat: String,
    val category: String,
    val tags: List<String>,
    val titleColor: String,
    val visibility: String,
    val roleCodes: List<String>,
    val updatedAt: String,
)

internal object NativeTemplateApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun list(accessToken: String): List<NativeArticleTemplate> =
        requestGet("/articles/mine/templates", accessToken).optJSONArray("items").toTemplates()

    fun create(accessToken: String, input: NativeArticleTemplateInput): NativeArticleTemplate =
        requestMutation("/articles/mine/templates", "POST", accessToken, input.toJson()).toTemplate()

    fun update(accessToken: String, id: Int, input: NativeArticleTemplateInput): NativeArticleTemplate =
        requestMutation("/articles/mine/templates/$id", "PATCH", accessToken, input.toJson()).toTemplate()

    fun delete(accessToken: String, id: Int) {
        requestMutation("/articles/mine/templates/$id", "DELETE", accessToken)
    }

    private fun requestGet(path: String, accessToken: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return readResponse(connection, "文章模板读取失败")
    }

    private fun requestMutation(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
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
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readResponse(connection, "文章模板操作失败")
    }

    private fun readResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { "$fallback（HTTP $status）" })
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun NativeArticleTemplateInput.toJson() = JSONObject().apply {
        put("name", name.trim())
        put("title", title.trim())
        put("summary", summary.trim())
        put("content", content)
        put("contentFormat", contentFormat)
        put("category", category.trim())
        put("tags", tags.trim())
        put("titleColor", titleColor.trim())
        put("visibility", visibility)
        put("roleCodes", JSONArray(roleCodes))
    }

    private fun JSONObject.toTemplate() = NativeArticleTemplate(
        id = optInt("id"),
        name = optString("name", "未命名模板"),
        title = optString("title"),
        summary = optString("summary"),
        content = optString("content"),
        contentFormat = optString("contentFormat", "markdown"),
        category = optString("category"),
        tags = optJSONArray("tags").toStrings(),
        titleColor = optString("titleColor"),
        visibility = optString("visibility", "public"),
        roleCodes = optJSONArray("roleCodes").toStrings(),
        updatedAt = optString("updatedAt"),
    )

    private fun JSONArray?.toTemplates(): List<NativeArticleTemplate> = buildList {
        for (index in 0 until (this@toTemplates?.length() ?: 0)) {
            this@toTemplates?.optJSONObject(index)?.let { add(it.toTemplate()) }
        }
    }

    private fun JSONArray?.toStrings(): List<String> = buildList {
        for (index in 0 until (this@toStrings?.length() ?: 0)) add(this@toStrings?.optString(index).orEmpty())
    }
}

internal data class NativeArticleTemplateInput(
    val name: String,
    val title: String,
    val summary: String,
    val content: String,
    val contentFormat: String,
    val category: String,
    val tags: String,
    val titleColor: String,
    val visibility: String,
    val roleCodes: List<String>,
)
