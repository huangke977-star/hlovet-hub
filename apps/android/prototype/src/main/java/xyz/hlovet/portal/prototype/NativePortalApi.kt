package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativePortalEntry(
    val id: Int,
    val categoryId: Int,
    val title: String,
    val description: String,
    val url: String?,
    val iconPath: String?,
    val openInNewTab: Boolean,
    val visibility: String,
    val sortOrder: Int,
    val status: String = "active",
    val isFeatured: Boolean,
    val featuredSortOrder: Int = 0,
    val allowedRoleCodes: List<String> = emptyList(),
    val categoryName: String,
    val categoryKind: String,
)

internal data class NativePortalCategory(
    val id: Int,
    val kind: String,
    val name: String,
    val slug: String,
    val description: String,
    val sortOrder: Int = 0,
    val status: String = "active",
    val entries: List<NativePortalEntry>,
)

internal data class NativePortalContent(val categories: List<NativePortalCategory>)

internal data class NativePortalPreferences(
    val homeEntryIds: List<Int>,
    val toolEntryIds: List<Int>,
)

internal data class NativePortalHomeSummary(
    val articleViews: Int,
    val commentCount: Int,
    val subscriberCount: Int,
    val likeCount: Int,
    val favoriteCount: Int,
    val friendCount: Int,
    val groupCount: Int,
)

/** Client for the user-visible portal, common tools and personal dashboard data. */
internal object NativePortalApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun listContent(accessToken: String?, kinds: List<String> = emptyList()): NativePortalContent {
        val path = if (accessToken.isNullOrBlank()) "/portal/public" else "/portal/me"
        val query = kinds.filter(String::isNotBlank).distinct().joinToString(",")
            .takeIf(String::isNotBlank)
            ?.let { "?kinds=$it" }
            .orEmpty()
        return requestGet("$path$query", accessToken).toContent()
    }

    fun listAdminContent(accessToken: String): NativePortalContent =
        requestGet("/portal/admin", accessToken).toContent()

    fun createCategory(
        accessToken: String,
        kind: String,
        name: String,
        description: String,
        sortOrder: Int,
        status: String,
    ): NativePortalCategory = requestMutation(
        "/portal/admin/categories",
        "POST",
        accessToken,
        JSONObject().apply {
            put("kind", kind)
            put("name", name)
            put("description", description)
            put("sortOrder", sortOrder)
            put("status", status)
        },
        "门户分类创建失败",
    ).toCategory()

    fun updateCategory(
        accessToken: String,
        id: Int,
        kind: String,
        name: String,
        description: String,
        sortOrder: Int,
        status: String,
    ): NativePortalCategory = requestMutation(
        "/portal/admin/categories/$id",
        "PATCH",
        accessToken,
        JSONObject().apply {
            put("kind", kind)
            put("name", name)
            put("description", description)
            put("sortOrder", sortOrder)
            put("status", status)
        },
        "门户分类保存失败",
    ).toCategory()

    fun deleteCategory(accessToken: String, id: Int) {
        requestMutation("/portal/admin/categories/$id", "DELETE", accessToken, null, "门户分类删除失败")
    }

    fun createEntry(
        accessToken: String,
        categoryId: Int,
        title: String,
        description: String,
        url: String?,
        iconPath: String?,
        openInNewTab: Boolean,
        visibility: String,
        sortOrder: Int,
        status: String,
        isFeatured: Boolean,
        featuredSortOrder: Int,
        roleCodes: List<String>,
    ): NativePortalEntry = requestMutation(
        "/portal/admin/entries",
        "POST",
        accessToken,
        entryBody(categoryId, title, description, url, iconPath, openInNewTab, visibility, sortOrder, status, isFeatured, featuredSortOrder, roleCodes),
        "门户条目创建失败",
    ).toEntry()

    fun updateEntry(
        accessToken: String,
        id: Int,
        categoryId: Int,
        title: String,
        description: String,
        url: String?,
        iconPath: String?,
        openInNewTab: Boolean,
        visibility: String,
        sortOrder: Int,
        status: String,
        isFeatured: Boolean,
        featuredSortOrder: Int,
        roleCodes: List<String>,
    ): NativePortalEntry = requestMutation(
        "/portal/admin/entries/$id",
        "PATCH",
        accessToken,
        entryBody(categoryId, title, description, url, iconPath, openInNewTab, visibility, sortOrder, status, isFeatured, featuredSortOrder, roleCodes),
        "门户条目保存失败",
    ).toEntry()

    fun deleteEntry(accessToken: String, id: Int) {
        requestMutation("/portal/admin/entries/$id", "DELETE", accessToken, null, "门户条目删除失败")
    }

    fun getPreferences(accessToken: String): NativePortalPreferences =
        requestGet("/portal/me/preferences", accessToken).toPreferences()

    fun updatePreferences(
        accessToken: String,
        homeEntryIds: List<Int>,
        toolEntryIds: List<Int>,
    ): NativePortalPreferences = requestMutation(
        "/portal/me/preferences",
        "PATCH",
        accessToken,
        JSONObject().apply {
            put("homeEntryIds", JSONArray(homeEntryIds.distinct().take(12)))
            put("toolEntryIds", JSONArray(toolEntryIds.distinct().take(30)))
        },
    ).toPreferences()

    fun getHomeSummary(accessToken: String): NativePortalHomeSummary {
        val value = requestGet("/portal/me/home-summary", accessToken)
        return NativePortalHomeSummary(
            articleViews = value.optInt("articleViews"),
            commentCount = value.optInt("commentCount"),
            subscriberCount = value.optInt("subscriberCount"),
            likeCount = value.optInt("likeCount"),
            favoriteCount = value.optInt("favoriteCount"),
            friendCount = value.optInt("friendCount"),
            groupCount = value.optInt("groupCount"),
        )
    }

    private fun JSONObject.toContent(): NativePortalContent = NativePortalContent(
        categories = optJSONArray("categories").toCategories(),
    )

    private fun JSONObject.toCategory(): NativePortalCategory =
        NativePortalCategory(
            id = optInt("id"),
            kind = optString("kind", "navigation"),
            name = optString("name", "未分类"),
            slug = optString("slug"),
            description = optString("description"),
            sortOrder = optInt("sortOrder"),
            status = optString("status", "active"),
            entries = optJSONArray("entries").toEntries(
                optInt("id"),
                optString("name", "未分类"),
                optString("kind", "navigation"),
            ),
        )

    private fun JSONObject.toEntry(): NativePortalEntry {
        val categoryId = optInt("categoryId")
        val categoryName = optString("categoryName", "未分类")
        val categoryKind = optString("categoryKind", "navigation")
        return NativePortalEntry(
            id = optInt("id"),
            categoryId = categoryId,
            title = optString("title", "未命名入口"),
            description = optString("description"),
            url = optString("url").takeIf { it.isNotBlank() && it != "null" },
            iconPath = optString("iconPath").takeIf { it.isNotBlank() && it != "null" },
            openInNewTab = optBoolean("openInNewTab", true),
            visibility = optString("visibility", "public"),
            sortOrder = optInt("sortOrder"),
            status = optString("status", "active"),
            isFeatured = optBoolean("isFeatured"),
            featuredSortOrder = optInt("featuredSortOrder"),
            allowedRoleCodes = optJSONArray("allowedRoles").roleCodes(),
            categoryName = categoryName,
            categoryKind = categoryKind,
        )
    }

    private fun JSONObject.toPreferences(): NativePortalPreferences = NativePortalPreferences(
        homeEntryIds = optJSONArray("homeEntryIds").toIntList(),
        toolEntryIds = optJSONArray("toolEntryIds").toIntList(),
    )

    private fun JSONArray?.toIntList(): List<Int> = buildList {
        for (index in 0 until (this@toIntList?.length() ?: 0)) {
            val value = this@toIntList?.optInt(index, 0) ?: 0
            if (value > 0) add(value)
        }
    }

    private fun JSONArray?.toCategories(): List<NativePortalCategory> = buildList {
        for (index in 0 until (this@toCategories?.length() ?: 0)) {
            val value = this@toCategories?.optJSONObject(index) ?: continue
            val kind = value.optString("kind", "navigation")
            val categoryId = value.optInt("id")
            val categoryName = value.optString("name", "未分类")
            add(
                NativePortalCategory(
                    id = categoryId,
                    kind = kind,
                    name = categoryName,
                    slug = value.optString("slug"),
                    description = value.optString("description"),
                    sortOrder = value.optInt("sortOrder"),
                    status = value.optString("status", "active"),
                    entries = value.optJSONArray("entries").toEntries(categoryId, categoryName, kind),
                ),
            )
        }
    }

    private fun JSONArray?.toEntries(
        categoryId: Int,
        categoryName: String,
        categoryKind: String,
    ): List<NativePortalEntry> = buildList {
        for (index in 0 until (this@toEntries?.length() ?: 0)) {
            val value = this@toEntries?.optJSONObject(index) ?: continue
            add(
                NativePortalEntry(
                    id = value.optInt("id"),
                    categoryId = value.optInt("categoryId", categoryId),
                    title = value.optString("title", "未命名入口"),
                    description = value.optString("description"),
                    url = value.optString("url").takeIf { it.isNotBlank() && it != "null" },
                    iconPath = value.optString("iconPath").takeIf { it.isNotBlank() && it != "null" },
                    openInNewTab = value.optBoolean("openInNewTab"),
                    visibility = value.optString("visibility", "public"),
                    sortOrder = value.optInt("sortOrder"),
                    status = value.optString("status", "active"),
                    isFeatured = value.optBoolean("isFeatured"),
                    featuredSortOrder = value.optInt("featuredSortOrder"),
                    allowedRoleCodes = value.optJSONArray("allowedRoles").roleCodes(),
                    categoryName = categoryName,
                    categoryKind = categoryKind,
                ),
            )
        }
    }

    private fun requestGet(path: String, accessToken: String?): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return readResponse(connection, "门户内容加载失败")
    }

    private fun requestMutation(
        path: String,
        method: String,
        accessToken: String,
        body: JSONObject?,
        fallback: String,
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 18_000
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
        }
        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            readResponse(connection, fallback)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestMutation(
        path: String,
        method: String,
        accessToken: String,
        body: JSONObject,
    ): JSONObject = requestMutation(path, method, accessToken, body, "门户偏好保存失败")

    private fun entryBody(
        categoryId: Int,
        title: String,
        description: String,
        url: String?,
        iconPath: String?,
        openInNewTab: Boolean,
        visibility: String,
        sortOrder: Int,
        status: String,
        isFeatured: Boolean,
        featuredSortOrder: Int,
        roleCodes: List<String>,
    ): JSONObject = JSONObject().apply {
        put("categoryId", categoryId)
        put("title", title)
        put("description", description)
        put("url", url ?: JSONObject.NULL)
        put("iconPath", iconPath ?: JSONObject.NULL)
        put("openInNewTab", openInNewTab)
        put("visibility", visibility)
        put("sortOrder", sortOrder)
        put("status", status)
        put("isFeatured", isFeatured)
        put("featuredSortOrder", featuredSortOrder)
        put("roleCodes", JSONArray(roleCodes))
    }

    private fun JSONArray?.roleCodes(): List<String> = buildList {
        for (index in 0 until (this@roleCodes?.length() ?: 0)) {
            val value = this@roleCodes?.optJSONObject(index) ?: continue
            value.optString("code").trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun readResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        return try {
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
                throw IllegalStateException(message.ifBlank { "$fallback（HTTP $status）" })
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
