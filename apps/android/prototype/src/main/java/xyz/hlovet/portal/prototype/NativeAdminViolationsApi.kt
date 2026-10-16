package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeViolationUser(
    val id: Int,
    val nickname: String,
    val username: String,
    val avatarUrl: String?,
)

internal data class NativeViolationRestriction(
    val id: Int,
    val reason: String,
    val startsAt: String,
    val endsAt: String?,
    val liftedAt: String?,
)

internal data class NativeViolationAuthor(
    val user: NativeViolationUser,
    val totalReceived: Int,
    val recentReceived: Int,
    val totalSubmitted: Int,
    val recentSubmitted: Int,
    val restriction: NativeViolationRestriction?,
)

internal data class NativeViolationReport(
    val id: Int,
    val publicationNumber: Int,
    val articleId: Int,
    val articleTitle: String,
    val articleSlug: String,
    val reporter: NativeViolationUser?,
    val articleAuthor: NativeViolationUser?,
    val reason: String,
    val detail: String?,
    val status: String,
    val resolution: String?,
    val createdAt: String,
    val handledAt: String?,
)

internal data class NativeViolationAuthorDetail(
    val summary: NativeViolationAuthor,
    val received: List<NativeViolationReport>,
    val submitted: List<NativeViolationReport>,
)

internal object NativeAdminViolationsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun list(accessToken: String): List<NativeViolationAuthor> =
        request("/articles/admin/violations", "GET", accessToken).optJSONArray("items").toAuthors()

    fun get(accessToken: String, userId: Int): NativeViolationAuthorDetail {
        val value = request("/articles/admin/violations/$userId", "GET", accessToken)
        val summary = value.toAuthor()
        return NativeViolationAuthorDetail(
            summary = summary,
            received = value.optJSONArray("received").toReports(),
            submitted = value.optJSONArray("submitted").toReports(),
        )
    }

    fun updateRestriction(
        accessToken: String,
        userId: Int,
        active: Boolean,
        permanent: Boolean = false,
        endsAt: String? = null,
        reason: String = "",
    ): JSONObject = request(
        "/articles/admin/violations/$userId/restriction",
        "PATCH",
        accessToken,
        JSONObject().apply {
            put("active", active)
            put("permanent", permanent)
            if (!endsAt.isNullOrBlank()) put("endsAt", endsAt)
            put("reason", reason)
        },
    )

    private fun request(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = if (method == "GET") 20_000 else 30_000
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
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    val raw = JSONObject(responseBody).opt("message")
                    when (raw) {
                        is JSONArray -> raw.join("、")
                        is String -> raw
                        else -> ""
                    }
                }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "违规作者请求失败（HTTP $status）" })
            }
            if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.toAuthors(): List<NativeViolationAuthor> = buildList {
        for (index in 0 until (this@toAuthors?.length() ?: 0)) {
            this@toAuthors?.optJSONObject(index)?.let { add(it.toAuthor()) }
        }
    }

    private fun JSONArray?.toReports(): List<NativeViolationReport> = buildList {
        for (index in 0 until (this@toReports?.length() ?: 0)) {
            this@toReports?.optJSONObject(index)?.let { add(it.toReport()) }
        }
    }

    private fun JSONObject.toAuthor(): NativeViolationAuthor = NativeViolationAuthor(
        user = optJSONObject("user")?.toUser() ?: NativeViolationUser(0, "未知用户", "unknown", null),
        totalReceived = optInt("totalReceived"),
        recentReceived = optInt("recentReceived"),
        totalSubmitted = optInt("totalSubmitted"),
        recentSubmitted = optInt("recentSubmitted"),
        restriction = optJSONObject("restriction")?.toRestriction(),
    )

    private fun JSONObject.toReport(): NativeViolationReport {
        val article = optJSONObject("article") ?: JSONObject()
        return NativeViolationReport(
            id = optInt("id"),
            publicationNumber = optInt("publicationNumber"),
            articleId = article.optInt("id"),
            articleTitle = article.optString("title", "未命名文章"),
            articleSlug = article.optString("slug"),
            reporter = optJSONObject("reporter")?.toUser(),
            articleAuthor = article.optJSONObject("author")?.toUser(),
            reason = optString("reason"),
            detail = optString("detail").takeIf(String::isNotBlank),
            status = optString("status", "pending"),
            resolution = optString("resolution").takeIf(String::isNotBlank),
            createdAt = optString("createdAt"),
            handledAt = optString("handledAt").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun JSONObject.toRestriction(): NativeViolationRestriction = NativeViolationRestriction(
        id = optInt("id"),
        reason = optString("reason"),
        startsAt = optString("startsAt"),
        endsAt = optString("endsAt").takeIf { it.isNotBlank() && it != "null" },
        liftedAt = optString("liftedAt").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun JSONObject.toUser(): NativeViolationUser = NativeViolationUser(
        id = optInt("id"),
        nickname = optString("nickname").ifBlank { optString("username", "站内用户") },
        username = optString("username", "unknown"),
        avatarUrl = optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
    )
}
