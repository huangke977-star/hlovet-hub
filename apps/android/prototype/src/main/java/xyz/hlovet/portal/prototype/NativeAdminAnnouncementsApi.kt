package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** 管理列表的公告快照，状态、投放范围和统计均由服务端提供。 */
internal data class NativeAdminAnnouncementSummary(
    val id: Int,
    val title: String,
    val summary: String,
    val audience: String,
    val status: String,
    val publishMode: String,
    val isPinned: Boolean,
    val pinOrder: Int,
    val pushEnabled: Boolean,
    val scheduledAt: String?,
    val publishedAt: String?,
    val expiresAt: String?,
    val recipientCount: Int,
    val viewCount: Int,
    val confirmedCount: Int,
    val roleCodes: List<String>,
    val createdBy: NativeCommunityUser?,
    val createdAt: String,
    val updatedAt: String,
)

/** 公告编辑详情及投递结果，不以列表摘要代替完整正文。 */
internal data class NativeAdminAnnouncementDetail(
    val summary: NativeAdminAnnouncementSummary,
    val content: String,
    val confirmedAt: String?,
    val deliveryStartedAt: String?,
    val deliveredAt: String?,
    val deliveryAttempts: Int,
    val deliveryError: String?,
)

/** 保存服务端过滤后的分页和总数，不在客户端估算写操作后的数量。 */
internal data class NativeAdminAnnouncementPage(
    val items: List<NativeAdminAnnouncementSummary>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

/** 提交草稿的完整字段；时间为 ISO UTC，角色列表仅对指定角色范围生效。 */
internal data class NativeAdminAnnouncementInput(
    val title: String,
    val summary: String,
    val content: String,
    val audience: String,
    val publishMode: String,
    val isPinned: Boolean,
    val pinOrder: Int,
    val pushEnabled: Boolean,
    val scheduledAt: String?,
    val expiresAt: String?,
    val roleCodes: List<String>,
)

/** 封装已认证的公告管理接口；不完整成功响应视为错误，权限由服务端最终判定。 */
internal object NativeAdminAnnouncementsApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    /** 按 accessToken、page/pageSize、search/status 查询服务端分页，返回真实列表而非本地筛选。 */
    fun list(
        accessToken: String,
        page: Int = 1,
        pageSize: Int = 12,
        search: String = "",
        status: String? = null,
    ): NativeAdminAnnouncementPage {
        // 分页边界先归一化，缺失响应字段不能伪装成空列表。
        val query = buildString {
            append("?page=").append(page.coerceAtLeast(1))
            append("&pageSize=").append(pageSize.coerceIn(1, 50))
            if (search.isNotBlank()) append("&search=").append(encode(search.trim()))
            if (!status.isNullOrBlank() && status != "all") append("&status=").append(encode(status))
        }
        return request("/announcements/admin$query", "GET", accessToken).toPage(page.coerceAtLeast(1), pageSize.coerceIn(1, 50))
    }

    /** 使用 accessToken 读取正整数 id 的完整公告，不存在或失去权限时抛出错误。 */
    fun get(accessToken: String, id: Int): NativeAdminAnnouncementDetail {
        // 先拒绝无效路径，防止错误身份被解析成 ID 为零的草稿。
        requireId(id)
        return request("/announcements/admin/$id", "GET", accessToken).toDetail()
    }

    /** 用 accessToken 保存 input 为新草稿，返回服务端分配的 ID 和标准化内容。 */
    fun create(accessToken: String, input: NativeAdminAnnouncementInput): NativeAdminAnnouncementDetail {
        // 成功必须包含有效详情，避免后续发布请求使用零 ID。
        return request("/announcements/admin", "POST", accessToken, input.toJson()).toDetail()
    }

    /** 用 accessToken 将 input 保存到 id 草稿；修改待发布公告会按服务端规则退回草稿。 */
    fun update(accessToken: String, id: Int, input: NativeAdminAnnouncementInput): NativeAdminAnnouncementDetail {
        // 更新只针对已存在的有效 ID，不在失败时降级为创建。
        requireId(id)
        return request("/announcements/admin/$id", "PATCH", accessToken, input.toJson()).toDetail()
    }

    /** 用 accessToken 发布 id，返回实际立即发布或定时发布状态，失败不回滚已保存草稿。 */
    fun publish(accessToken: String, id: Int): NativeAdminAnnouncementDetail {
        // 发布时间与有效角色仍由服务端按当前数据校验。
        requireId(id)
        return request("/announcements/admin/$id/publish", "POST", accessToken).toDetail()
    }

    /** 用 accessToken 归档已发布的 id，返回新的管理快照。 */
    fun archive(accessToken: String, id: Int): NativeAdminAnnouncementDetail {
        // 不在客户端提前更改可见状态，只有服务端确认后才刷新列表。
        requireId(id)
        return request("/announcements/admin/$id/archive", "POST", accessToken).toDetail()
    }

    /** 用 accessToken 删除 id；仅接受 success=true，普通管理员只能删除草稿。 */
    fun delete(accessToken: String, id: Int) {
        // 空 2xx 或未确认成功不能触发客户端移除行和成功提示。
        requireId(id)
        check(request("/announcements/admin/$id", "DELETE", accessToken).optBoolean("success")) {
            nativePhrase("服务器未确认公告删除成功。", "The server did not confirm deletion.")
        }
    }

    /** 校验 id 是否为正整数，非法输入不产生网络请求。 */
    private fun requireId(id: Int) {
        // 路径始终使用稳定服务端 ID，不允许空值的零编号。
        require(id > 0) { nativePhrase("公告编号无效。", "Invalid announcement ID.") }
    }

    /** 以 method 请求 path，accessToken 仅用于授权头，body 可空；错误保持可见且不打印凭据。 */
    private fun request(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        // 先验证授权，再设置连接；写入正文失败时同样释放连接。
        require(accessToken.isNotBlank()) { nativePhrase("请先登录。", "Sign in first.") }
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
            // 管理写请求按原接口提交 JSON，详情和列表响应不得为空。
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            // 对鉴权状态给出可理解提示，其它业务校验保留服务端消息。
            if (status == 401) throw IllegalStateException(nativePhrase("登录已失效，请重新登录。", "Your session expired. Sign in again."))
            if (status == 403) throw IllegalStateException(nativePhrase("当前账号无权执行此公告操作。", "This account cannot perform this announcement action."))
            if (status !in 200..299) {
                val fallback = nativePhrase("公告请求失败", "Announcement request failed")
                throw IllegalStateException(parseError(body).ifBlank { "$fallback（HTTP $status）" })
            }
            check(body.isNotBlank()) { nativePhrase("服务器未返回公告数据。", "The server returned no announcement data.") }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    /** 从 body 解析字符串或数组错误；非 JSON 返回空串，由 HTTP 状态兜底。 */
    private fun parseError(body: String): String = runCatching {
        // 仅消费约定的 message，不把 HTML 错误页面展示成业务详情。
        val value = JSONObject(body).opt("message")
        when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.joinToString("；")
            is String -> value
            else -> ""
        }
    }.getOrDefault("")

    /** 对 value 做 UTF-8 查询参数编码，空格使用百分号编码。 */
    private fun encode(value: String): String {
        // 不直接拼接用户搜索词，避免改变查询边界。
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }

    /** 将当前 input 转成服务端完整草稿字段；空时间明确使用 JSON null 清除。 */
    private fun NativeAdminAnnouncementInput.toJson() = JSONObject().apply {
        // 角色按稳定 code 去重；非角色范围不提交旧的角色草稿。
        put("title", title.trim())
        put("summary", summary.trim())
        put("content", content)
        put("audience", audience)
        put("publishMode", publishMode)
        put("isPinned", isPinned)
        put("pinOrder", pinOrder.coerceIn(0, 9999))
        put("pushEnabled", pushEnabled)
        put("scheduledAt", if (publishMode == "scheduled") scheduledAt ?: JSONObject.NULL else JSONObject.NULL)
        put("expiresAt", expiresAt ?: JSONObject.NULL)
        put("roleCodes", JSONArray(if (audience == "role_restricted") roleCodes.distinct().filter(String::isNotBlank) else emptyList<String>()))
    }

    /** 解析分页，fallbackPage/fallbackPageSize 仅补充旧服务端省略的分页字段，items 必须存在。 */
    private fun JSONObject.toPage(fallbackPage: Int, fallbackPageSize: Int): NativeAdminAnnouncementPage {
        // 缺失 items 是契约错误，不是没有公告。
        check(optJSONArray("items") != null) { nativePhrase("公告列表响应不完整。", "The announcement list response is incomplete.") }
        return NativeAdminAnnouncementPage(
            items = getJSONArray("items").toSummaries(),
            total = optInt("total"),
            page = optInt("page", fallbackPage).coerceAtLeast(1),
            pageSize = optInt("pageSize", fallbackPageSize).coerceIn(1, 50),
            totalPages = optInt("totalPages", 1).coerceAtLeast(1),
        )
    }

    /** 将当前 JSON 解析为编辑详情；有效公告 ID 由共享摘要解析校验。 */
    private fun JSONObject.toDetail(): NativeAdminAnnouncementDetail {
        // 列表摘要不能冒充完整详情，否则保存会静默清空正文。
        check(has("content") && !isNull("content")) { nativePhrase("公告详情响应不完整。", "The announcement details are incomplete.") }
        return NativeAdminAnnouncementDetail(
            summary = toSummary(),
            content = optString("content"),
            confirmedAt = optNullableString("confirmedAt"),
            deliveryStartedAt = optJSONObject("delivery").optNullableString("startedAt"),
            deliveredAt = optJSONObject("delivery").optNullableString("deliveredAt"),
            deliveryAttempts = optJSONObject("delivery")?.optInt("attempts") ?: 0,
            deliveryError = optJSONObject("delivery").optNullableString("error"),
        )
    }

    /** 将当前 JSON 解析为公告快照，缺失稳定 ID 时拒绝渲染或执行后续操作。 */
    private fun JSONObject.toSummary(): NativeAdminAnnouncementSummary {
        // ID 是页面和动作的唯一身份，不使用名称或数组位置代替。
        requireId(optInt("id"))
        val author = optJSONObject("createdBy")
        return NativeAdminAnnouncementSummary(
            id = optInt("id"),
            title = optString("title", nativePhrase("未命名公告", "Untitled announcement")),
            summary = optString("summary"),
            audience = optString("audience", "public"),
            status = optString("status", "draft"),
            publishMode = optString("publishMode", "immediate"),
            isPinned = optBoolean("isPinned"),
            pinOrder = optInt("pinOrder"),
            pushEnabled = optBoolean("pushEnabled", true),
            scheduledAt = optNullableString("scheduledAt"),
            publishedAt = optNullableString("publishedAt"),
            expiresAt = optNullableString("expiresAt"),
            recipientCount = optInt("recipientCount"),
            viewCount = optInt("viewCount"),
            confirmedCount = optInt("confirmedCount"),
            roleCodes = optJSONArray("roleCodes").toStrings(),
            createdBy = author?.let { NativeCommunityUser(it.optInt("id"), it.optString("username"), it.optString("nickname").ifBlank { it.optString("username") }) },
            createdAt = optString("createdAt"),
            updatedAt = optString("updatedAt"),
        )
    }

    /** 将当前公告数组逐项解析，非法元素拒绝整个响应而非静默减少总数。 */
    private fun JSONArray.toSummaries(): List<NativeAdminAnnouncementSummary> = buildList {
        // 服务端顺序保持不变，客户端不重新排序分页结果。
        for (index in 0 until this@toSummaries.length()) {
            add(this@toSummaries.getJSONObject(index).toSummary())
        }
    }

    /** 将可空数组转成角色 code；未提供字段按无角色处理。 */
    private fun JSONArray?.toStrings(): List<String> = buildList {
        // 角色展示兼容空数组，但不使用其名称判断权限。
        for (index in 0 until (this@toStrings?.length() ?: 0)) add(this@toStrings?.optString(index).orEmpty())
    }

    /** 从可空对象读取 key；空白和 JSON null 均返回 null。 */
    private fun JSONObject?.optNullableString(key: String): String? {
        // 时间与投递错误缺省不是字符串 "null"。
        return this?.optString(key).orEmpty().takeIf { it.isNotBlank() && it != "null" }
    }
}
