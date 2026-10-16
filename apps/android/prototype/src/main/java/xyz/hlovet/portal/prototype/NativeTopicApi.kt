package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Native client for the administrator-only article topic management API. */
internal object NativeTopicApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun list(accessToken: String): List<RemoteTopic> =
        request("/discovery/admin/topics", "GET", accessToken)
            .optJSONArray("items")
            .toTopics()

    fun create(
        accessToken: String,
        title: String,
        slug: String,
        description: String,
        coverPath: String,
        visibility: String,
        status: String,
        sortOrder: Int,
        roleCodes: List<String>,
        articleIds: List<Int>,
    ): RemoteTopic = request(
        "/discovery/admin/topics",
        "POST",
        accessToken,
        JSONObject().apply {
            put("title", title.trim())
            put("slug", slug.trim())
            put("description", description.trim())
            put("coverPath", coverPath.trim())
            put("visibility", visibility)
            put("status", status)
            put("sortOrder", sortOrder.coerceAtLeast(0))
            put("roleCodes", JSONArray(roleCodes.distinct().filter(String::isNotBlank)))
            put("articleIds", JSONArray(articleIds.distinct()))
        },
    ).toTopic()

    fun update(
        accessToken: String,
        id: Int,
        title: String,
        slug: String,
        description: String,
        coverPath: String,
        visibility: String,
        status: String,
        sortOrder: Int,
        roleCodes: List<String>,
    ): RemoteTopic = request(
        "/discovery/admin/topics/$id",
        "PATCH",
        accessToken,
        JSONObject().apply {
            put("title", title.trim())
            put("slug", slug.trim())
            put("description", description.trim())
            put("coverPath", coverPath.trim())
            put("visibility", visibility)
            put("status", status)
            put("sortOrder", sortOrder.coerceAtLeast(0))
            put("roleCodes", JSONArray(roleCodes.distinct().filter(String::isNotBlank)))
        },
    ).toTopic()

    fun delete(accessToken: String, id: Int) {
        request("/discovery/admin/topics/$id", "DELETE", accessToken)
    }

    fun addArticle(accessToken: String, topicId: Int, articleId: Int): RemoteTopic =
        request("/discovery/admin/topics/$topicId/articles/$articleId", "POST", accessToken).toTopic()

    fun removeArticle(accessToken: String, topicId: Int, articleId: Int): RemoteTopic =
        request("/discovery/admin/topics/$topicId/articles/$articleId", "DELETE", accessToken).toTopic()

    fun reorderArticles(accessToken: String, topicId: Int, articleIds: List<Int>): RemoteTopic =
        request(
            "/discovery/admin/topics/$topicId/articles/order",
            "PATCH",
            accessToken,
            JSONObject().put("ids", JSONArray(articleIds)),
        ).toTopic()

    fun uploadCover(accessToken: String, topicId: Int, file: NativeUploadFile): RemoteTopic {
        val boundary = "----HlovetAndroidTopic${System.currentTimeMillis()}"
        val connection = (URL("$baseUrl/discovery/admin/topics/$topicId/cover").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                val safeName = file.name.replace("\"", "").replace("\r", "").replace("\n", "")
                output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Type: ${file.mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n".toByteArray(Charsets.UTF_8))
                output.write(file.bytes)
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            readResponse(connection, "专题封面上传失败（HTTP ${connection.responseCode}）").toTopic()
        } finally {
            connection.disconnect()
        }
    }

    private fun request(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readResponse(connection, "专题操作失败（HTTP ${connection.responseCode}）")
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: fallback
            throw IllegalStateException(message)
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun JSONArray?.toTopics(): List<RemoteTopic> = buildList {
        if (this@toTopics == null) return@buildList
        for (index in 0 until length()) optJSONObject(index)?.let { add(it.toTopic()) }
    }

    private fun JSONObject.toTopic(): RemoteTopic = RemoteTopic(
        id = optInt("id"),
        title = optString("title", "未命名专题"),
        slug = optString("slug"),
        description = optString("description"),
        coverPath = optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
        visibility = optString("visibility", "public"),
        status = optString("status", "active"),
        sortOrder = optInt("sortOrder"),
        roleCodes = optJSONArray("roleCodes").toStrings(),
        articleCount = optInt("articleCount", optJSONArray("articles")?.length() ?: 0),
        subscriberCount = optInt("subscriberCount"),
        subscribed = optBoolean("subscribed"),
        updatedAt = optString("updatedAt"),
        articles = optJSONArray("articles").toArticles(),
    )

    private fun JSONArray?.toArticles(): List<RemoteArticle> = buildList {
        if (this@toArticles == null) return@buildList
        for (index in 0 until length()) optJSONObject(index)?.let { add(it.toArticle()) }
    }

    private fun JSONObject.toArticle(): RemoteArticle {
        val author = optJSONObject("author")
        return RemoteArticle(
            id = optInt("id"),
            title = optString("title", "未命名文章"),
            slug = optString("slug"),
            summary = optString("summary"),
            content = optString("content"),
            contentFormat = optString("contentFormat", "markdown"),
            category = optString("category", "未分类"),
            tags = optJSONArray("tags").toStrings(),
            viewCount = optInt("viewCount"),
            likeCount = optInt("likeCount"),
            favoriteCount = optInt("favoriteCount"),
            commentCount = optInt("commentCount"),
            authorName = author?.optString("nickname").orEmpty().ifBlank { "站内作者" },
            authorUsername = author?.optString("username").orEmpty(),
            authorId = author?.optInt("id") ?: 0,
            status = optString("status", "published"),
            updatedAt = optString("updatedAt"),
        )
    }

    private fun JSONArray?.toStrings(): List<String> = buildList {
        if (this@toStrings == null) return@buildList
        for (index in 0 until length()) add(optString(index))
    }
}
