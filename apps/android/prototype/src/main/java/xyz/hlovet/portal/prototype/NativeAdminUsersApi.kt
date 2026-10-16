package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 用户管理列表的账号快照；管理身份、成长等级与双因素状态分别保存。 */
internal data class NativeAdminUser(
    val id: Int,
    val username: String,
    val nickname: String,
    val email: String,
    val status: String,
    val isSuperAdmin: Boolean,
    val isAdministrator: Boolean,
    val avatarUrl: String?,
    val profileBio: String,
    val createdAt: String,
    val roleCode: String,
    val roleName: String,
    val roleLevel: Int,
    val totpEnabled: Boolean,
)

/** 服务端按当前搜索范围计算的分页与启用统计，不由客户端累加。 */
internal data class NativeAdminUserPage(
    val items: List<NativeAdminUser>,
    val total: Int,
    val activeCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

/** 解除双因素认证的结果，仅展示实际撤销会话数，不包含安全凭据。 */
internal data class NativeAdminTotpResetResult(
    val revokedSessions: Int,
)

/** 对接用户管理接口；调用方负责调度 IO，权限和持久化规则始终由服务端执行。 */
internal object NativeAdminUsersApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        // 仅使用构建时明确配置的接口地址，不将测试请求回退到线上。
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    /** 使用 accessToken 按 page、pageSize 和 search 查询，返回服务端校正后的分页。 */
    fun list(
        accessToken: String,
        page: Int = 1,
        pageSize: Int = 20,
        search: String = "",
    ): NativeAdminUserPage {
        // 搜索编码和分页范围与网页保持一致，不将搜索内容拼成额外查询参数。
        val query = buildString {
            append("?page=").append(page.coerceAtLeast(1))
            append("&pageSize=").append(pageSize.coerceIn(10, 100))
            if (search.isNotBlank()) append("&search=").append(encode(search.trim().take(64)))
        }
        return request("/users$query", "GET", accessToken).toPage(page, pageSize)
    }

    /** 使用 accessToken 将 userId 的管理员身份设为 enabled，返回实际保存的账号。 */
    fun setAdministrator(accessToken: String, userId: Int, enabled: Boolean): NativeAdminUser =
        request("/users/$userId/administrator", "PATCH", accessToken, JSONObject().put("isAdministrator", enabled)).toUser()

    /** 使用 accessToken 将 userId 设为 status，幂等请求仍返回实际账号状态。 */
    fun setStatus(accessToken: String, userId: Int, status: String): NativeAdminUser =
        request("/users/$userId/status", "PATCH", accessToken, JSONObject().put("status", status)).toUser()

    /** 使用 accessToken 将 userId 昵称恢复为用户名，返回更新后的搜索身份。 */
    fun resetNickname(accessToken: String, userId: Int): NativeAdminUser =
        request("/users/$userId/nickname/reset", "PATCH", accessToken).toUser()

    /** 使用 accessToken 更新 userId 的 password；密码仅出现在请求体，不记录日志。 */
    fun updatePassword(accessToken: String, userId: Int, password: String): NativeAdminUser =
        request("/users/$userId/password", "PATCH", accessToken, JSONObject().put("password", password)).toUser()

    /** 使用 accessToken 解除 userId 双因素认证，返回服务端实际撤销会话数。 */
    fun resetTotp(accessToken: String, userId: Int): NativeAdminTotpResetResult {
        // 复用隐私管理接口，不自行删除凭据或推算会话数。
        val response = request("/account-privacy/admin/users/$userId/totp/reset", "PATCH", accessToken)
        return NativeAdminTotpResetResult(revokedSessions = response.optInt("revokedSessions", 0))
    }

    /** 向 path 发送 method 和可选 body；使用 accessToken 授权，异常不包含令牌或密码。 */
    private fun request(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        // 连接生命周期局限于单次请求，超时和错误流均显式处理。
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
                // 优先展示业务校验结果，身份失效与权限拒绝使用当前语言的固定提示。
                val message = parseError(responseBody)
                throw IllegalStateException(
                    when (status) {
                        HttpURLConnection.HTTP_UNAUTHORIZED -> nativePhrase("登录状态已失效，请重新登录。", "Your session expired. Sign in again.")
                        HttpURLConnection.HTTP_FORBIDDEN -> nativePhrase("当前账号没有执行该管理操作的权限。", "This account cannot perform this management action.")
                        else -> message.ifBlank { nativePhrase("用户管理请求失败（HTTP $status）。", "User management request failed (HTTP $status).") }
                    },
                )
            }
            // 空响应不能冒充空列表或操作成功，否则用户无法判断真实保存结果。
            check(responseBody.isNotBlank()) { nativePhrase("用户管理返回为空，请重试。", "User management returned an empty response. Retry.") }
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    /** 将列表响应转换为分页；fallbackPage 和 fallbackPageSize 仅补齐可选分页字段。 */
    private fun JSONObject.toPage(fallbackPage: Int, fallbackPageSize: Int): NativeAdminUserPage {
        // items 缺失意味着协议异常，不能将它静默解释为没有用户。
        check(optJSONArray("items") != null) { nativePhrase("用户列表格式异常，请重试。", "Invalid user list response. Retry.") }
        return NativeAdminUserPage(
        items = optJSONArray("items").toUsers(),
        total = optInt("total"),
        activeCount = optInt("activeCount"),
        page = optInt("page", fallbackPage),
        pageSize = optInt("pageSize", fallbackPageSize),
        totalPages = optInt("totalPages", 1).coerceAtLeast(1),
        )
    }

    /** 转换用户数组；空数组返回空列表，不创建缺少账号标识的占位用户。 */
    private fun JSONArray?.toUsers(): List<NativeAdminUser> = buildList {
        // 每行沿用服务端 ID，数组序号只用于读取而不充当身份。
        for (index in 0 until (this@toUsers?.length() ?: 0)) {
            this@toUsers?.optJSONObject(index)?.let { add(it.toUser()) }
        }
    }

    /** 解析账号及可选成长等级；totpFallback 仅用于未返回安全状态的更新响应。 */
    private fun JSONObject.toUser(totpFallback: Boolean = false): NativeAdminUser {
        // 写接口缺少有效账号 ID 时必须显示失败，不能错误关闭确认框。
        check(optInt("id") > 0) { nativePhrase("用户信息格式异常，请刷新后重试。", "Invalid user response. Refresh and retry.") }
        val role = optJSONObject("role") ?: JSONObject()
        return NativeAdminUser(
            id = optInt("id"),
            username = optString("username", "user"),
            nickname = optString("nickname").ifBlank { optString("username", "user") },
            email = optString("email"),
            status = optString("status", "active"),
            isSuperAdmin = optBoolean("isSuperAdmin"),
            isAdministrator = optBoolean("isAdministrator"),
            avatarUrl = optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
            profileBio = optString("profileBio"),
            createdAt = optString("createdAt"),
            roleCode = role.optString("code"),
            roleName = role.optString("name"),
            roleLevel = role.optInt("level"),
            totpEnabled = if (has("totpEnabled")) optBoolean("totpEnabled") else totpFallback,
        )
    }

    /** 提取 body 的单条或数组错误文案；非 JSON 响应返回空串，交由 HTTP 提示兜底。 */
    private fun parseError(body: String): String = runCatching {
        // 保留多条字段校验信息，不将完整响应或敏感请求参数写入反馈。
        val value = JSONObject(body).opt("message")
        when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.joinToString("；")
            is String -> value
            else -> ""
        }
    }.getOrDefault("")

    /** 将 value 编码为单个查询参数，空格使用百分号编码。 */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
