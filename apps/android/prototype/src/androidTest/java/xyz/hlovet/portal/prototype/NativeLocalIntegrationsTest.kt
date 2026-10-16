package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 在本地真实 MainActivity 与数据库中验证令牌生命周期，保留撤销记录但不输出完整凭据。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalIntegrationsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var session: NativeSession
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("testDataRemoved", false).put("productionTested", false).put("apiBaseUrl", BuildConfig.API_BASE_URL)
    private val marker = "native_readonly_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    private val peers = mutableListOf<NativeSession>()
    private var rawToken: String? = null
    private var tokenId: Int? = null
    private var evidenceName = "native-local-integrations"

    /** 沿用当前已授权的本地普通用户，独立生成夹具，不依赖历史账号或预置数据。 */
    @Before fun requireLocalReader() {
        // 正式来源不能执行测试写入，安全策略不满足时明确失败而非修改站点配置。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val policy = NativeAuthApi.registrationPolicy()
        check(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled)
        val stored = requireNotNull(NativeSessionStore.load(context))
        session = NativeAuthApi.refresh(stored.refreshToken)
        check(!session.isSuperAdmin && !session.isAdministrator) { "Permission fixtures require a normal reader" }
        check(NativeSessionStore.save(context, session))
        oldLocale = NativeI18n.locale
        instrumentation.runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("userId", session.userId).put("username", session.username).put("marker", marker)
    }

    /** 保存无凭据报告并关闭页面；异常中断时也撤销本次创建的令牌，记录仍保留。 */
    @After fun finish() {
        // 不遗留本测试用过的有效访问凭据，不清理数据库记录。
        tokenId?.let { id -> runCatching {
            if (NativeIntegrationsApi.listTokens(session.accessToken).any { it.id == id && it.revokedAt == null }) {
                NativeIntegrationsApi.revokeToken(session.accessToken, id)
                report.put("revokedInFinalizer", true)
            }
        } }
        rawToken = null
        if (oldLocale != null) saveReport()
        activity?.close()
        // 独立辅助账号保留在数据库中，但测试创建的临时登录会话不继续有效。
        peers.forEach { peer -> runCatching { NativeAuthApi.logout(peer.refreshToken) } }
        oldLocale?.let { locale -> instrumentation.runOnMainSync { NativeI18n.applyLocale(locale) } }
    }

    /** 真实按钮创建、权限查询、最近使用、列表不回显明文、真实按钮撤销和后续拒绝。 */
    @Test fun realTokenCreationScopeVisibilityAndRevocation() {
        // 使用真实站点分类和角色，为当前读者创建七类独立文章，避免匹配历史样例。
        prepareArticles()
        // 默认只选择文章权限，使用真实创建按钮而非测试专用界面。
        val name = "${marker}_articles"
        val stored = createInPage(name)
        check(stored.scopes == listOf("read_articles"))
        record("real-create-button-persisted-read-articles-only-token")
        screenshot("created-closed")

        // 权限过滤与搜索必须同时生效：四类可见，另外三类不得泄漏元数据。
        val response = http("/integrations/v1/articles?search=$marker", requireNotNull(rawToken))
        assertEquals(200, response.first)
        val articles = JSONObject(response.second).getJSONArray("items")
        val allowed = setOf("public", "authenticated", "own_private", "allowed_role")
        val actual = (0 until articles.length()).map { articles.getJSONObject(it).getString("title").removePrefix("${marker}_") }.toSet()
        assertEquals(allowed, actual)
        report.put("visibleFixtureTitles", JSONArray(actual.sorted()))
        // /articles 固定为游客公开列表，必须用 /visible 对照当前登录用户的阅读权限。
        val normal = http("/articles/visible?search=$marker", session.accessToken)
        assertEquals(200, normal.first)
        val normalItems = JSONObject(normal.second).getJSONArray("items")
        val normalIds = (0 until normalItems.length()).map { normalItems.getJSONObject(it).getInt("id") }.toSet()
        val tokenIds = (0 until articles.length()).map { articles.getJSONObject(it).getInt("id") }.toSet()
        assertEquals(normalIds, tokenIds)
        record("real-api-matches-normal-article-permissions-for-seven-retained-fixtures")

        // 只授予文章权限的凭据不得读取通知、资料或调用常规登录接口。
        assertEquals(401, http("/integrations/v1/notifications", requireNotNull(rawToken)).first)
        assertEquals(401, http("/integrations/v1/users/${session.username}", requireNotNull(rawToken)).first)
        assertEquals(401, http("/articles/mine", requireNotNull(rawToken)).first)
        assertEquals(401, http("/integrations/tokens", requireNotNull(rawToken)).first)
        record("real-token-rejects-missing-scopes-and-normal-session-endpoints")

        // 最近使用由真实服务端写入；列表只有前缀和元数据，不应再包含完整凭据。
        val listing = http("/integrations/tokens", session.accessToken)
        assertEquals(200, listing.first)
        check(!listing.second.contains(requireNotNull(rawToken))) { "List exposed a complete token" }
        check(NativeIntegrationsApi.listTokens(session.accessToken).single { it.id == stored.id }.lastUsedAt != null)
        record("real-last-used-persisted-and-list-never-returned-raw-token")

        // 按令牌行选择撤销，既检查页面状态也回读数据库并再次实际访问。
        compose.onNodeWithTag("integration-token-${stored.id}").performScrollTo()
        compose.onNode(hasContentDescription("撤销令牌") and hasAnyAncestor(hasTestTag("integration-token-${stored.id}"))).performClick()
        clickDialog("撤销"); waitText("只读令牌已撤销。")
        check(NativeIntegrationsApi.listTokens(session.accessToken).single { it.id == stored.id }.revokedAt != null)
        assertEquals(401, http("/integrations/v1/articles?search=$marker", requireNotNull(rawToken)).first)
        compose.onNode(hasContentDescription("撤销令牌") and hasAnyAncestor(hasTestTag("integration-token-${stored.id}"))).assertIsNotEnabled()
        record("real-revoke-button-persists-revocation-and-subsequent-access-is-401")
        screenshot("revoked")
    }

    /** 通过真实权限复选框创建多权限令牌，读取公开资料和本人通知但不能变成登录凭据。 */
    @Test fun realProfileAndNotificationsScopesStayReadOnlyAndOwnerFiltered() {
        // 单独报告多权限路径，不能覆盖上一条文章权限流程的验收证据。
        evidenceName = "native-local-integrations-all-scopes"
        val notificationIds = prepareNotifications()
        val name = "${marker}_all_scopes"
        val stored = createInPage(name, listOf("公开资料只读", "通知只读"))
        assertEquals(setOf("read_articles", "read_profile", "read_notifications"), stored.scopes.toSet())
        record("real-scope-checkboxes-persisted-all-three-read-only-scopes")

        // 公开资料不能携带用户邮箱、密码或登录凭据。
        val profile = http("/integrations/v1/users/${session.username}", requireNotNull(rawToken))
        assertEquals(200, profile.first)
        val user = JSONObject(profile.second)
        assertEquals(session.userId, user.getInt("id"))
        check(listOf("email", "password", "passwordHash", "accessToken", "refreshToken").none { user.has(it) })
        record("real-public-profile-scope-excludes-private-fields")

        // 两条真实好友申请通知归属不同用户；按稳定 ID 检查，避免依赖通知文案或同名标题。
        val notifications = http("/integrations/v1/notifications", requireNotNull(rawToken))
        assertEquals(200, notifications.first)
        val items = JSONObject(notifications.second).getJSONArray("items")
        val actualIds = (0 until items.length()).map { items.getJSONObject(it).getInt("id") }.toSet()
        check(notificationIds.first in actualIds)
        check(notificationIds.second !in actualIds)
        report.put("notificationIds", JSONArray(actualIds.sorted()))
        record("real-notifications-scope-includes-own-and-excludes-foreign-fixture")
        assertEquals(401, http("/integrations/tokens", requireNotNull(rawToken)).first)
        record("all-scopes-token-still-cannot-manage-tokens")

        // 仍用实际行按钮撤销，多权限不能使凭据在撤销后继续读通知或资料。
        compose.onNodeWithTag("integration-token-${stored.id}").performScrollTo()
        compose.onNode(hasContentDescription("撤销令牌") and hasAnyAncestor(hasTestTag("integration-token-${stored.id}"))).performClick()
        clickDialog("撤销"); waitText("只读令牌已撤销。")
        assertEquals(401, http("/integrations/v1/notifications", requireNotNull(rawToken)).first)
        assertEquals(401, http("/integrations/v1/users/${session.username}", requireNotNull(rawToken)).first)
        record("real-all-scopes-token-revoked-and-read-access-rejected")
        screenshot("revoked")
    }

    /** 注册 suffix 标记的辅助用户并返回真实会话；账号和业务记录保留，密码不落盘。 */
    private fun registerPeer(suffix: String): NativeSession {
        // 注册使用当前开放的本地策略，不伪造 JWT、不改既有账号密码或角色。
        val username = "readonly_${UUID.randomUUID().toString().replace("-", "").take(12)}_$suffix"
        val peer = (NativeAuthApi.register(username, "Readonly $suffix", "$username@example.com", "Local!${UUID.randomUUID()}", null, null)
            as NativeAuthResult.Authenticated).session
        peers.add(peer)
        NativeDiscoveryApi.completeOnboarding(peer.accessToken, emptyList(), emptyList())
        report.put("peerAccounts", JSONArray(peers.map { JSONObject().put("id", it.userId).put("username", it.username) }))
        saveReport()
        return peer
    }

    /** 经真实文章 API 准备七种权限/状态样例，报告记录 ID 与规则，不泄漏凭据。 */
    private fun prepareArticles() {
        // 分类来自当前站点配置；授权/非授权角色来自服务端角色清单，不能写死角色编码。
        val category = NativeSiteSettingsApi.loadWriterSettings().categories.first().name
        val role = NativeProfileApi.loadPublic(session.accessToken, session.username).roleCode
        val excludedRole = NativeRoleApi.listRoles().first { it.code != role }.code
        val peer = registerPeer("author")
        val fixtures = JSONArray()
        // 七类样例中只有前四类可见；未发布状态不能因公开范围而进入只读列表。
        for (label in listOf("public", "authenticated", "own_private", "allowed_role", "foreign_private", "denied_role", "draft")) {
            val author = if (label == "own_private" || label == "draft") session else peer
            val visibility = when (label) {
                "own_private", "foreign_private" -> "private"
                "allowed_role", "denied_role" -> "role_restricted"
                "authenticated" -> "authenticated"
                else -> "public"
            }
            val roles = when (label) {
                "allowed_role" -> listOf(role)
                "denied_role" -> listOf(excludedRole)
                else -> emptyList()
            }
            val draft = NativeArticleApi.createArticle(author.accessToken, NativeDraftInput(
                "${marker}_$label", "Retained native read-only permission fixture", "<p>$marker $label</p>",
                "html", category, "", visibility = visibility, roleCodes = roles,
            ))
            val article = if (label == "draft") draft else NativeArticleApi.publish(author.accessToken, draft.id)
            fixtures.put(JSONObject().put("id", article.id).put("title", article.title).put("authorId", author.userId)
                .put("visibility", visibility).put("roleCodes", JSONArray(roles)).put("status", article.status))
            report.put("articleFixtures", fixtures)
            saveReport()
        }
        record("seven-independent-article-fixtures-created-through-real-business-api")
    }

    /** 真实好友申请分别通知当前用户与另一用户，返回本人通知 ID 和他人通知 ID。 */
    private fun prepareNotifications(): Pair<Int, Int> {
        // 使用新注册的两个辅助账号，不依赖旧通知、不修改当前账号隐私或通知策略。
        val sender = registerPeer("sender")
        val other = registerPeer("recipient")
        val ownRequest = NativeFriendsApi.requestFriend(sender.accessToken, session.userId, "${marker}_own")
        val otherRequest = NativeFriendsApi.requestFriend(sender.accessToken, other.userId, "${marker}_foreign")
        val ownId = NativeSocialApi.listNotifications(session.accessToken, limit = 50).items.single { it.friendshipId == ownRequest.id }.id
        val otherId = NativeSocialApi.listNotifications(other.accessToken, limit = 50).items.single { it.friendshipId == otherRequest.id }.id
        report.put("ownNotificationId", ownId).put("foreignNotificationId", otherId)
            .put("ownFriendshipId", ownRequest.id).put("foreignFriendshipId", otherRequest.id)
        record("owner-and-foreign-notifications-created-through-real-friend-request-api")
        return ownId to otherId
    }

    /** 在真实页面填写 name 和 extraScopes 对应的权限选项，返回无明文的服务端元数据。 */
    private fun createInPage(name: String, extraScopes: List<String> = emptyList()): NativeReadOnlyToken {
        // 深链走生产 MainActivity 路由，沿用已授权的真实本地账号。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/profile/integrations")))
        waitText("只读 API 令牌")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("新增令牌").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        compose.onNodeWithContentDescription("新增令牌").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("名称") and hasAnyAncestor(isDialog())).performTextReplacement(name)
        extraScopes.forEach { label -> compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo().performClick() }
        clickDialog("创建"); waitText("令牌已创建")

        // 先登记撤销目标，后续语义读取失败时 finalizer 也能撤销，不遗留有效凭据。
        val stored = NativeIntegrationsApi.listTokens(session.accessToken).single { it.name == name }
        tokenId = stored.id
        report.put("tokenId", stored.id).put("tokenName", name).put("scopes", JSONArray(stored.scopes))
        // 一次性凭据只留在内存，不拍摄该弹窗、不写入文件、不使用共享剪贴板。
        val values = compose.onAllNodes(hasAnyAncestor(isDialog()) and hasText("lvt_", substring = true)).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
        rawToken = values.firstOrNull { it.startsWith("lvt_") && it.length > 30 }
        check(!rawToken.isNullOrBlank()) { "No complete one-time token found" }
        clickDialog("我已保存")
        return stored
    }

    /** 读取 path，使用 bearer 鉴权并只返回状态及正文，不记录请求头。 */
    private fun http(path: String, bearer: String): Pair<Int, String> {
        // 明文凭据只在当前调用栈传递，不写日志或断言错误消息。
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000; connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer $bearer")
        return try {
            val status = connection.responseCode
            status to (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally { connection.disconnect() }
    }

    /** 点击弹窗内 text 对应按钮，排除同名标题。 */
    private fun clickDialog(text: String) {
        // 操作真实语义按钮，不直接调用页面回调。
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    }

    /** 等待真实页面出现 text。 */
    private fun waitText(text: String) {
        // 仅等待已渲染状态，不用固定时长假定请求成功。
        compose.waitUntil(45_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 记录已完成的 check，失败或未执行的检查不会被计为通过。 */
    private fun record(check: String) {
        // 报告只包含凭据编号、权限与测试结论。
        checks.put(JSONObject().put("check", check).put("passed", true)); saveReport()
    }

    /** 持久化本轮无敏感信息的报告。 */
    private fun saveReport() {
        // 明文令牌不能成为 JSON 字段。
        report.put("checks", checks)
        File(context.filesDir, "$evidenceName-report.json").writeText(report.toString(2))
    }

    /** 保存 name 对应的页面截图，只在一次性令牌弹窗关闭后调用。 */
    private fun screenshot(name: String) {
        // 凭据展示期间不得截图，避免证据目录泄漏真实访问密钥。
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "$evidenceName-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
