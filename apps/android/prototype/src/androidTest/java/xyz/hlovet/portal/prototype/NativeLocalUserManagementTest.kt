package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 在真实 MainActivity 和本地 API 中验收用户管理，保留新增账号与记录并恢复原读者。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalUserManagementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var reader: NativeSession? = null
    private var locale: String? = null
    private lateinit var root: NativeSession
    private var manager: NativeSession? = null
    private var target: NativeSession? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("origin", BuildConfig.API_BASE_URL).put("testDataRemoved", false)
        .put("superAdminInteractiveLoginTested", false).put("superAdminAuthorization", "short-lived-local-container-signed-jwt")

    /** 校验精确本地来源、现存读者与管理员授权，不放宽任何注册或二次验证配置。 */
    @Before fun prepare() {
        // 来源保护必须先于读取凭据和用户数据。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val saved = requireNotNull(NativeSessionStore.load(context))
        check(saved.userId in listOf(63, 64))
        reader = NativeAuthApi.refresh(saved.refreshToken)
        check(NativeSessionStore.save(context, requireNotNull(reader)))
        val file = File(context.filesDir, "native-local-users-admin-token.json")
        val authorization = try { JSONObject(file.readText()).getString("accessToken") } finally { file.delete() }
        val (status, current) = request("/auth/me", authorization)
        check(status == 200 && current.getInt("id") == 7 && current.getString("username") == "android_parity_admin" && current.getBoolean("isSuperAdmin"))
        root = NativeSession(authorization, "", 7, current.getString("username"), current.getString("nickname"),
            PreviewApi.appearanceFromJson(current.optJSONObject("appearance") ?: JSONObject()), isSuperAdmin = true, isAdministrator = current.optBoolean("isAdministrator"))
        locale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("readerId", saved.userId).put("superAdminId", root.userId)
    }

    /** 关闭页面后恢复读者加密会话，注销本轮临时会话但保留全部测试账号和业务记录。 */
    @After fun restore() {
        // 先停止页面刷新，避免旧 Activity 覆盖原读者凭据。
        if (reader == null) return
        try { if (locale != null) { capture("last-state"); writeReport() }; closePage() }
        finally {
            check(NativeSessionStore.save(context, requireNotNull(reader)))
            listOfNotNull(manager, target).forEach { runCatching { NativeAuthApi.logout(it.refreshToken) } }
            locale?.let { value -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(value) } }
        }
    }

    /** 正常注册辅助账号，经真实授权、UI 登录和管理操作核对角色、状态、密码及双因素。 */
    @Test fun realAdministratorAndSuperAdministratorActionsPersist() {
        // 只新增独立夹具，不重置既有管理员密码或更改原读者身份。
        val suffix = UUID.randomUUID().toString().take(8)
        val managerName = "native_manager_$suffix"
        val targetName = "native_managed_$suffix"
        val managerPassword = "Local!${UUID.randomUUID()}"
        val targetPassword = "Local!${UUID.randomUUID()}"
        val targetNickname = "管理验收$suffix"
        manager = registered(managerName, managerName, managerPassword)
        target = registered(targetName, targetNickname, targetPassword)
        NativeAdminUsersApi.setAdministrator(root.accessToken, requireNotNull(manager).userId, true)
        NativeAuthApi.logout(requireNotNull(manager).refreshToken)
        report.put("managerId", requireNotNull(manager).userId).put("managerUsername", managerName)
            .put("targetId", requireNotNull(target).userId).put("targetUsername", targetName).put("initialTargetNickname", targetNickname)
        record("independent-fixtures-registered-and-manager-granted-through-the-real-admin-api")

        // 普通管理员必须经过真实登录表单，而非把短期超级管理员令牌当成交互登录成功。
        closePage(); NativeSessionStore.clear(context)
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitTag("native-navigation-4"); compose.onNodeWithTag("native-navigation-4").performClick()
        waitTag("native-profile-login"); compose.onNodeWithTag("native-profile-login").performClick()
        waitTag("native-login-account"); compose.onNodeWithTag("native-login-account").performTextReplacement(managerName)
        compose.onNodeWithTag("native-login-password").performTextReplacement(managerPassword)
        compose.onNodeWithTag("native-login-submit").performScrollTo().performClick()
        compose.waitUntil(60_000) { NativeSessionStore.load(context)?.userId == manager?.userId }
        manager = requireNotNull(NativeSessionStore.load(context))
        assertTrue(requireNotNull(manager).isAdministrator); assertFalse(requireNotNull(manager).isSuperAdmin)
        report.put("ordinaryAdminInteractiveLoginTested", true)
        record("ordinary-administrator-signs-in-through-real-mainactivity-password-form")

        // 普通管理员可启停普通用户，列表状态与后端实际持久化必须同步。
        openAs(requireNotNull(manager)); search(targetName)
        assertActionAbsent(requireNotNull(target).userId, "修改密码")
        assertActionAbsent(requireNotNull(target).userId, "管理员身份")
        action(requireNotNull(target).userId, "停用账号").performClick(); confirm()
        waitText("已停用 @$targetName。")
        action(requireNotNull(target).userId, "启用账号").assertIsDisplayed()
        assertEquals("disabled", findTarget(requireNotNull(target).userId).status)
        record("ordinary-administrator-disables-member-and-row-matches-database-backed-api")
        action(requireNotNull(target).userId, "启用账号").performClick(); confirm()
        waitText("已启用 @$targetName。")
        action(requireNotNull(target).userId, "停用账号").assertIsDisplayed()
        assertEquals("active", findTarget(requireNotNull(target).userId).status)
        record("ordinary-administrator-enables-member-and-refreshes-row")

        // 昵称唯一命中搜索，重置后必须消失；直接接口也不能越权授予管理员或改密码。
        search(targetNickname); action(requireNotNull(target).userId, "重置昵称").performClick(); confirm()
        waitText("没有找到匹配的用户。")
        assertEquals(targetName, findTarget(requireNotNull(target).userId).nickname)
        assertEquals(403, request("/users/${target?.userId}/administrator", requireNotNull(manager).accessToken, "PATCH", JSONObject().put("isAdministrator", true)).first)
        assertEquals(403, request("/users/${target?.userId}/password", requireNotNull(manager).accessToken, "PATCH", JSONObject().put("password", "Rejected!${UUID.randomUUID()}")).first)
        record("nickname-reset-refreshes-filtered-results-and-server-rejects-manager-escalation")
        search(root.username); assertActionAbsent(root.userId, "停用账号"); assertActionAbsent(root.userId, "修改密码")
        search(managerName); assertActionAbsent(requireNotNull(manager).userId, "停用账号")
        capture("ordinary-admin-protected-self")
        record("ordinary-administrator-cannot-operate-super-administrator-or-self-administrator")

        // 超级管理员的密码表单先校验错误，再实际修改新建目标密码并使用新密码登录确认。
        openAs(root); search(targetName); action(requireNotNull(target).userId, "修改密码").performClick()
        dialogButton("保存").performClick(); dialogText("新密码至少需要 8 位。").assertIsDisplayed()
        val newPassword = "Changed!${UUID.randomUUID()}"
        compose.onNodeWithTag("admin-users-password").performScrollTo().performTextReplacement(newPassword)
        compose.onNodeWithTag("admin-users-password-confirmation").performScrollTo().performTextReplacement(newPassword)
        dialogButton("保存").performClick(); waitText("已更新 @$targetName 的密码。")
        target = authenticated(NativeAuthApi.login(targetName, newPassword))
        assertEquals(targetName, requireNotNull(target).username)
        record("super-administrator-password-dialog-validates-and-new-password-authenticates")

        // 授予与撤销管理员必须由真实页面触发，返回后再次读取实际角色。
        action(requireNotNull(target).userId, "管理员身份").performClick(); confirm()
        waitText("已授予 @$targetName 管理员身份。")
        assertTrue(findTarget(requireNotNull(target).userId).isAdministrator)
        action(requireNotNull(target).userId, "管理员身份").performClick(); confirm()
        waitText("已取消 @$targetName 管理员身份。")
        assertFalse(findTarget(requireNotNull(target).userId).isAdministrator)
        record("super-administrator-grants-and-removes-administrator-through-real-ui")

        // 本地专用加密密钥已配置；通过正常密码授权和 TOTP 确认创建可解绑的真实凭据。
        val access = requireNotNull(target).accessToken
        val grant = NativePrivacyApi.verifySensitivePassword(access, "totp_enrollment", newPassword)
        val enrollment = NativePrivacyApi.beginTotpEnrollment(access, grant)
        NativePrivacyApi.confirmTotp(access, nativeLocalTotp(enrollment.secret))
        assertTrue(findTarget(requireNotNull(target).userId).totpEnabled)
        closePage(); openAs(root); search(targetName)
        action(requireNotNull(target).userId, "解除双因素认证").performClick(); confirm()
        waitTextPrefix("已解除 @$targetName 的双因素认证，并撤销 ")
        assertActionAbsent(requireNotNull(target).userId, "解除双因素认证")
        assertFalse(findTarget(requireNotNull(target).userId).totpEnabled)
        assertEquals(401, request("/auth/me", access).first)
        capture("super-admin-totp-reset")
        record("real-totp-enrollment-and-admin-reset-refresh-row-and-revoke-target-session")

        // 最后撤销辅助管理员，验证服务端和安卓入口都立即失去用户管理权限。
        search(managerName); action(requireNotNull(manager).userId, "管理员身份").performClick(); confirm()
        waitText("已取消 @$managerName 管理员身份。")
        assertFalse(findTarget(requireNotNull(manager).userId).isAdministrator)
        assertEquals(403, request("/users", requireNotNull(manager).accessToken).first)
        record("revoked-real-administrator-is-denied-by-server-without-changing-the-reader")
    }

    /** 正常注册 username、nickname、password 的本地夹具并完成引导，返回会话。 */
    private fun registered(username: String, nickname: String, password: String): NativeSession {
        // API 注册不保存到设备会话存储，不覆盖原读者。
        val session = authenticated(NativeAuthApi.register(username, nickname, "$username@example.invalid", password, null, null))
        NativeDiscoveryApi.completeOnboarding(session.accessToken, emptyList(), emptyList())
        return session
    }

    /** 校验 result 是真正的登录成功，返回其会话而不绕过二次验证。 */
    private fun authenticated(result: NativeAuthResult): NativeSession {
        // 遇到验证挑战直接失败，不能修改策略或伪造挑战完成。
        assertTrue(result is NativeAuthResult.Authenticated)
        return (result as NativeAuthResult.Authenticated).session
    }

    /** 关闭旧页再以 session 打开真实管理路由，防止旧页面刷新覆盖测试会话。 */
    private fun openAs(session: NativeSession) {
        // 普通管理员使用关闭旧页后保存的轮换会话；超级管理员保持短期只读凭据传递。
        closePage()
        val fresh = if (session.userId == manager?.userId) requireNotNull(manager) else session
        check(NativeSessionStore.save(context, fresh))
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + "/admin/users")))
        waitTag("admin-users-search")
        compose.waitUntil(60_000) { compose.onAllNodesWithText("正在读取用户列表…").fetchSemanticsNodes().isEmpty() }
    }

    /** 关闭页面并保留轮换后的辅助管理员令牌，保证后续真实权限请求有效。 */
    private fun closePage() {
        // 原读者只在收尾恢复，此处不覆盖其内存中的有效会话。
        activity?.close(); activity = null
        NativeSessionStore.load(context)?.let { saved -> if (saved.userId == manager?.userId) manager = saved }
    }

    /** 在真实列表中搜索 value 并等待防抖请求结束。 */
    private fun search(value: String) {
        // 等实际加载结束且输入框仍为当前条件，不通过固定休眠推测网络已完成。
        listNode().performScrollToNode(hasTestTag("admin-users-search"))
        compose.onNodeWithTag("admin-users-search").performTextReplacement(value)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.waitUntil(60_000) { compose.onAllNodesWithText("正在读取用户列表…").fetchSemanticsNodes().isEmpty() }
    }

    /** 返回 id 行内 description 操作；等待查询结果后再滚动到稳定账号标识。 */
    private fun action(id: Int, description: String): SemanticsNodeInteraction {
        // 页面异步刷新期间目标卡片可能尚未创建，限定重试而不点击其它账号。
        compose.waitUntil(60_000) { runCatching { listNode().performScrollToNode(hasTestTag("admin-user-$id")); true }.getOrDefault(false) }
        compose.onNodeWithTag("admin-user-$id").performScrollTo()
        return compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("admin-user-$id")))
    }

    /** 确认 id 行不包含 description 动作，不将屏幕外未加载误判为无权限。 */
    private fun assertActionAbsent(id: Int, description: String) {
        // 正常显示目标行后再检查其子节点。
        compose.waitUntil(60_000) { runCatching { listNode().performScrollToNode(hasTestTag("admin-user-$id")); true }.getOrDefault(false) }
        compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("admin-user-$id"))).assertDoesNotExist()
    }

    /** 返回当前管理列表供懒加载滚动使用。 */
    private fun listNode() = compose.onNodeWithTag("admin-users-list")

    /** 点击当前弹窗的确认按钮，不触碰页面背后操作。 */
    private fun confirm() { dialogButton("确认").performClick() }

    /** 返回当前弹窗中 text 对应的按钮。 */
    private fun dialogButton(text: String): SemanticsNodeInteraction {
        // 先等待弹窗进入可操作状态，不点击异步加载期间的旧页面节点。
        val matcher = hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(60_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    /** 返回弹窗中的 text 错误，验证提示确实在活动表单内。 */
    private fun dialogText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog())).performScrollTo()

    /** 等待 tag 的真实页面节点出现。 */
    private fun waitTag(tag: String) { compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }

    /** 等待 text 的真实服务器操作反馈出现。 */
    private fun waitText(text: String) { compose.waitUntil(60_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }

    /** 等待以 prefix 开头的结果，撤销会话数由服务器决定。 */
    private fun waitTextPrefix(prefix: String) { compose.waitUntil(60_000) { compose.onAllNodes(hasText(prefix, substring = true)).fetchSemanticsNodes().isNotEmpty() } }

    /** 从真实用户列表读取 id 的最终状态，不直接查询或修改业务表。 */
    private fun findTarget(id: Int): NativeAdminUser {
        // 使用本轮明确账号名缩小查询范围，保留服务端权限校验。
        val username = if (id == manager?.userId) requireNotNull(manager).username else requireNotNull(target).username
        return NativeAdminUsersApi.list(root.accessToken, search = username).items.single { it.id == id }
    }

    /** 调用真实本地 path，method/body 为可选业务请求，返回状态与 JSON；绝不打印认证数据。 */
    private fun request(path: String, token: String, method: String = "GET", body: JSONObject? = null): Pair<Int, JSONObject> {
        // 来源固定为已保护的本地构建，拒绝绝对 URL 或协议相对路径。
        check(path.startsWith("/") && !path.startsWith("//"))
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to if (bytes.isBlank()) JSONObject() else JSONObject(bytes)
        } finally { connection.disconnect() }
    }

    /** 记录已经完成的 check，不保存密码、授权、TOTP 密钥或恢复码。 */
    private fun record(check: String) {
        // 每个检查点即时写入，失败时保留此前已完成的真实结果。
        checks.put(JSONObject().put("check", check).put("passed", true)); writeReport()
    }

    /** 写入可核对的账号标识和检查结果，不清理测试数据。 */
    private fun writeReport() { File(context.filesDir, "native-local-user-management-report.json").writeText(report.put("checks", checks).toString(2)) }

    /** 保存 name 对应的整屏证据，使用系统截图而非虚构渲染图。 */
    private fun capture(name: String) {
        // 密码输入采用遮罩，证据不包含传递用管理员令牌。
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(context.filesDir, "native-local-user-management-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
