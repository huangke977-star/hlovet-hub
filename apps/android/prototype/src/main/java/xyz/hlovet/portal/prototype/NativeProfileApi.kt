package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class NativeProfileSummary(
    val id: Int,
    val nickname: String,
    val username: String,
    val createdAt: String?,
    val bio: String?,
    val publicArticleCount: Int?,
    val receivedLikeCount: Int?,
    val publicViewCount: Int?,
    val subscriberCount: Int?,
    val followingCount: Int?,
)

internal data class NativeProfileVisibility(
    val bio: Boolean = true,
    val joinedAt: Boolean = true,
    val stats: Boolean = true,
    val followingCount: Boolean = true,
    val pinnedContent: Boolean = true,
)

internal data class NativeProfileRelationship(
    val id: Int,
    val status: String,
    val direction: String,
    val note: String?,
)

internal data class NativePublicProfile(
    val id: Int,
    val nickname: String,
    val username: String,
    val bio: String?,
    val avatarUrl: String?,
    val roleCode: String,
    val roleName: String,
    val roleLevel: Int,
    val isSuperAdmin: Boolean,
    val isAdministrator: Boolean,
    val createdAt: String?,
    val isSelf: Boolean,
    val subscribed: Boolean,
    val subscriberCount: Int?,
    val followingCount: Int?,
    val publicArticleCount: Int?,
    val receivedLikeCount: Int?,
    val publicViewCount: Int?,
    val visibleFields: NativeProfileVisibility,
    val relationship: NativeProfileRelationship?,
    val canRequestFriend: Boolean,
    val messageAccess: String,
)

internal data class NativeAccountProfile(
    val username: String,
    val nickname: String,
    val email: String,
    val profileBio: String,
    val avatarUrl: String?,
    val locale: String,
    val appearance: PreviewAppearance,
)

internal data class NativeProfileSettings(
    val profileAccess: String = "public",
    val searchable: Boolean = true,
    val friendRequestPolicy: String = "everyone",
    val directMessagePolicy: String = "request",
    val groupInvitationPolicy: String = "everyone",
    val showBio: Boolean = true,
    val showJoinedAt: Boolean = true,
    val showStats: Boolean = true,
    val showFollowingCount: Boolean = true,
    val showPinnedContent: Boolean = true,
    val pinnedArticleId: Int? = null,
    val pinnedCollectionId: Int? = null,
)

internal data class NativeProfileShowcase(
    val settings: NativeProfileSettings,
    val visitCount: Int?,
    val pinnedArticle: RemoteArticle?,
    val pinnedCollection: RemoteCollection?,
    val collections: List<RemoteCollection>,
)

internal object NativeProfileApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun loadSummary(accessToken: String, username: String): NativeProfileSummary {
        return load(accessToken, username)
    }

    fun loadPublic(accessToken: String?, username: String): NativePublicProfile =
        parsePublicProfile(requestGet(profilePath("profiles", accessToken, username), accessToken))

    fun loadShowcase(accessToken: String?, username: String): NativeProfileShowcase {
        val endpoint = if (accessToken.isNullOrBlank()) "public" else "visible"
        return parseShowcase(requestGet("/discovery/profiles/$endpoint/${encode(username)}", accessToken))
    }

    fun loadAccount(accessToken: String): NativeAccountProfile =
        parseAccount(requestJson("/auth/me", "GET", accessToken))

    fun updateProfile(
        accessToken: String,
        nickname: String,
        email: String,
        profileBio: String,
    ): NativeAccountProfile = parseAccount(
        requestJson(
            path = "/auth/me/profile",
            method = "PATCH",
            accessToken = accessToken,
            body = JSONObject().apply {
                put("nickname", nickname)
                // Email changes are intentionally handled by the privacy flow.
                put("email", email)
                put("profileBio", profileBio)
            },
        ),
    )

    fun updateUsername(accessToken: String, username: String): NativeAccountProfile = parseAccount(
        requestJson(
            path = "/auth/me/username",
            method = "PATCH",
            accessToken = accessToken,
            body = JSONObject().put("username", username),
        ),
    )

    fun updateLocale(accessToken: String, locale: String): NativeAccountProfile = parseAccount(
        requestJson(
            path = "/auth/me/locale",
            method = "PATCH",
            accessToken = accessToken,
            body = JSONObject().put("locale", if (locale == "en-US") "en-US" else "zh-CN"),
        ),
    )

    fun updateAppearance(accessToken: String, appearance: PreviewAppearance): NativeAccountProfile = parseAccount(
        requestJson(
            path = "/auth/me/appearance",
            method = "PATCH",
            accessToken = accessToken,
            body = JSONObject().apply {
                put("themeId", appearance.themeId)
                put("customAccent", appearance.accent)
                put("customSurface", appearance.surface)
                put("customForeground", appearance.foreground)
                put("customMuted", appearance.muted)
                put("cardAlpha", appearance.cardAlpha)
                put("glassBlur", appearance.glassBlur)
                put("glassTint", appearance.glassTint)
                put("glassTintAlpha", appearance.glassTintAlpha)
            },
        ),
    )

    fun uploadAvatar(accessToken: String, file: NativeUploadFile): NativeAccountProfile {
        val boundary = "----HlovetAndroid${System.currentTimeMillis()}"
        val connection = (URL(baseUrl + "/auth/me/avatar").openConnection() as HttpURLConnection).apply {
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
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(errorMessage(body, status, "头像上传失败"))
            parseAccount(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    fun loadSettings(accessToken: String): NativeProfileSettings =
        parseSettings(requestJson("/discovery/profile/settings", "GET", accessToken))

    fun updateSettings(accessToken: String, patch: JSONObject): NativeProfileSettings =
        parseSettings(requestJson("/discovery/profile/settings", "PATCH", accessToken, patch))

    private fun load(accessToken: String?, username: String): NativeProfileSummary {
        val response = requestGet(profilePath("profiles", accessToken, username), accessToken)
        return NativeProfileSummary(
            id = response.optInt("id"),
            nickname = response.optString("nickname", username),
            username = response.optString("username", username),
            createdAt = response.optString("createdAt").takeIf { it.isNotBlank() && it != "null" },
            bio = response.optString("profileBio").takeIf { it.isNotBlank() },
            publicArticleCount = response.optionalInt("publicArticleCount"),
            receivedLikeCount = response.optionalInt("receivedLikeCount"),
            publicViewCount = response.optionalInt("publicViewCount"),
            subscriberCount = response.optionalInt("subscriberCount"),
            followingCount = response.optionalInt("followingCount"),
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun profilePath(prefix: String, accessToken: String?, username: String): String {
        val endpoint = if (accessToken.isNullOrBlank()) "public" else "visible"
        return "/$prefix/$endpoint/${encode(username)}"
    }

    private fun parsePublicProfile(response: JSONObject): NativePublicProfile {
        val role = response.optJSONObject("role")
        val visible = response.optJSONObject("visibleFields")
        val relationship = response.optJSONObject("relationship")
        return NativePublicProfile(
            id = response.optInt("id"),
            nickname = response.optString("nickname", response.optString("username", "站内用户")),
            username = response.optString("username"),
            bio = response.optString("profileBio").takeIf { it.isNotBlank() && it != "null" },
            avatarUrl = response.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
            roleCode = role?.optString("code").orEmpty(),
            roleName = role?.optString("name").orEmpty(),
            roleLevel = role?.optInt("level") ?: 0,
            isSuperAdmin = response.optBoolean("isSuperAdmin"),
            isAdministrator = response.optBoolean("isAdministrator"),
            createdAt = response.optString("createdAt").takeIf { it.isNotBlank() && it != "null" },
            isSelf = response.optBoolean("isSelf"),
            subscribed = response.optBoolean("subscribed"),
            subscriberCount = response.optionalInt("subscriberCount"),
            followingCount = response.optionalInt("followingCount"),
            publicArticleCount = response.optionalInt("publicArticleCount"),
            receivedLikeCount = response.optionalInt("receivedLikeCount"),
            publicViewCount = response.optionalInt("publicViewCount"),
            visibleFields = NativeProfileVisibility(
                bio = visible?.optBoolean("bio", true) ?: true,
                joinedAt = visible?.optBoolean("joinedAt", true) ?: true,
                stats = visible?.optBoolean("stats", true) ?: true,
                followingCount = visible?.optBoolean("followingCount", true) ?: true,
                pinnedContent = visible?.optBoolean("pinnedContent", true) ?: true,
            ),
            relationship = relationship?.let {
                NativeProfileRelationship(
                    id = it.optInt("id"),
                    status = it.optString("status", "pending"),
                    direction = it.optString("direction", "outgoing"),
                    note = it.optString("note").takeIf { note -> note.isNotBlank() && note != "null" },
                )
            },
            canRequestFriend = response.optBoolean("canRequestFriend"),
            messageAccess = response.optString("messageAccess", "none"),
        )
    }

    private fun parseShowcase(response: JSONObject): NativeProfileShowcase {
        val settings = response.optJSONObject("settings") ?: JSONObject()
        return NativeProfileShowcase(
            settings = parseSettings(settings),
            visitCount = response.optionalInt("visitCount"),
            pinnedArticle = response.optJSONObject("pinnedArticle")?.let(NativeArticleApi::parseArticle),
            pinnedCollection = response.optJSONObject("pinnedCollection")?.let(NativeDiscoveryApi::parseCollection),
            collections = response.optJSONArray("collections").toProfileCollections(),
        )
    }

    private fun parseAccount(response: JSONObject): NativeAccountProfile {
        val user = response.optJSONObject("user") ?: response
        return NativeAccountProfile(
            username = user.optString("username", "user"),
            nickname = user.optString("nickname", user.optString("username", "user")),
            email = user.optString("email"),
            profileBio = user.optString("profileBio"),
            avatarUrl = user.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
            locale = user.optString("locale", "zh-CN"),
            appearance = PreviewApi.appearanceFromJson(user.optJSONObject("appearance") ?: JSONObject()),
        )
    }

    private fun parseSettings(response: JSONObject): NativeProfileSettings = NativeProfileSettings(
        profileAccess = response.optString("profileAccess", "public"),
        searchable = response.optBoolean("searchable", true),
        friendRequestPolicy = response.optString("friendRequestPolicy", "everyone"),
        directMessagePolicy = response.optString("directMessagePolicy", "request"),
        groupInvitationPolicy = response.optString("groupInvitationPolicy", "everyone"),
        showBio = response.optBoolean("showBio", true),
        showJoinedAt = response.optBoolean("showJoinedAt", true),
        showStats = response.optBoolean("showStats", true),
        showFollowingCount = response.optBoolean("showFollowingCount", true),
        showPinnedContent = response.optBoolean("showPinnedContent", true),
        pinnedArticleId = response.optIntOrNull("pinnedArticleId"),
        pinnedCollectionId = response.optIntOrNull("pinnedCollectionId"),
    )

    private fun requestJson(
        path: String,
        method: String,
        accessToken: String,
        body: JSONObject? = null,
    ): JSONObject {
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
            if (body != null) connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(errorMessage(responseBody, status, "个人资料请求失败"))
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestGet(path: String, accessToken: String?): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "个人资料加载失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONArray?.toProfileCollections(): List<RemoteCollection> = buildList {
    if (this@toProfileCollections == null) return@buildList
    for (index in 0 until length()) {
        optJSONObject(index)?.let { add(NativeDiscoveryApi.parseCollection(it)) }
    }
}

private fun errorMessage(body: String, status: Int, fallback: String): String =
    runCatching {
        val value = JSONObject(body).opt("message")
        when (value) {
            is org.json.JSONArray -> (0 until value.length()).joinToString("；") { value.optString(it) }
            else -> value?.toString().orEmpty()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "$fallback（HTTP $status）"

private fun JSONObject.optionalInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null
