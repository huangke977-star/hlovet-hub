package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 用设备内隔离服务验证用户管理异常、权限与分页，不向本地数据库或线上发送写请求。 */
@RunWith(AndroidJUnit4::class)
class NativeAdminUsersDeviceTest {
    @get:Rule val compose = createNativeComposeRule()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var locale = "zh-CN"
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private val users = CopyOnWriteArrayList<JSONObject>()
    @Volatile private var intercept: (RecordedRequest) -> MockResponse? = { null }
    private val admin = NativeSession("fixture-users-token", "fixture-refresh", 1, "root", "Root",
        PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
        isSuperAdmin = true, isAdministrator = true)

    /** 仅在精确隔离构建运行，准备三种管理层级和一个待注销用户。 */
    @Before fun start() {
        // 测试保护先于启动服务，避免请求误发到真实数据库。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        locale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        users.addAll(listOf(user(1, "root", superAdmin = true), user(2, "manager", administrator = true),
            user(3, "member", nickname = "独立昵称", totp = true), user(4, "pending", status = "deletion_pending")))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 记录 request 后交由当前故障夹具或正常处理器响应，不记录密码正文到报告。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 独立请求快照用于核对调用次数和授权边界。
                calls.add(request)
                return intercept(request) ?: respond(request)
            }
        }
        server.start(39871)
    }

    /** 先销毁组合再关闭服务并恢复语言，不修改设备保存的真实账号。 */
    @After fun stop() {
        // 未通过隔离构建保护时没有资源需要清理。
        if (!::server.isInitialized) return
        try { host.dispose() } finally {
            try { server.shutdown() } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) }
            }
        }
    }

    /** 普通用户不能请求管理接口，刷新也不可用。 */
    @Test fun nonAdministratorDoesNotReadUsers() {
        // 直接访问页面仍应在接口前拒绝，而非等待服务器 403。
        show(admin.copy(isSuperAdmin = false, isAdministrator = false), wait = false)
        waitText("当前账号没有访问用户管理的权限。")
        compose.onNodeWithContentDescription("刷新").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }

    /** 普通管理员只能操作普通用户，超级管理员和其他管理员保持只读。 */
    @Test fun administratorActionsRespectManagementHierarchy() {
        // 行内断言防止同名按钮来自另一用户，验证每一个管理动作。
        show(admin.copy(userId = 2, isSuperAdmin = false))
        row(1).assertExists(); assertRowActionAbsent(1, "停用账号")
        row(2).assertExists(); assertRowActionAbsent(2, "停用账号")
        rowAction(3, "停用账号").assertIsEnabled()
        rowAction(3, "重置昵称").assertIsEnabled()
        listOf("修改密码", "管理员身份", "解除双因素认证").forEach { assertRowActionAbsent(3, it) }
        row(4)
        compose.onNode(hasText("注销待处理") and hasAnyAncestor(hasTestTag("admin-user-4"))).assertIsDisplayed()
        assertTrue(calls.all { it.method == "GET" })
    }

    /** 超级管理员自身可修改密码，但不能被停用或重置安全信息。 */
    @Test fun superAdministratorSeesOnlyPermittedActions() {
        // 超级管理员自己的密码是明确特例，不放开其它超级管理员的操作。
        show()
        rowAction(1, "修改密码").assertExists()
        listOf("停用账号", "管理员身份", "重置昵称", "解除双因素认证").forEach { assertRowActionAbsent(1, it) }
        listOf("停用账号", "管理员身份", "修改密码", "重置昵称", "解除双因素认证").forEach { rowAction(3, it).assertExists() }
    }

    /** 密码客户端和服务器错误都显示在表单中，重复保存被阻止，成功后清空草稿。 */
    @Test fun passwordErrorsStayInDialogAndDuplicateSubmissionIsBlocked() {
        // 先验证无效输入不会发请求，再阻塞真实界面提交以检查互斥。
        show(); rowAction(3, "修改密码").performClick()
        dialogButton("保存").performClick(); dialogText("新密码至少需要 8 位。").assertIsDisplayed()
        fill("admin-users-password", "Fixture!password")
        fill("admin-users-password-confirmation", "different-password")
        dialogButton("保存").performClick(); dialogText("两次输入的密码不一致。").assertIsDisplayed()
        assertTrue(writes().isEmpty())
        fill("admin-users-password-confirmation", "Fixture!password")
        val attempts = AtomicInteger()
        val release = CountDownLatch(1)
        intercept = { r -> if (r.method == "PATCH" && path(r).endsWith("/password") && attempts.incrementAndGet() == 1) {
            release.await(30, TimeUnit.SECONDS); failure("Fixture password policy rejected", 400)
        } else null }
        try {
            dialogButton("保存").performClick()
            compose.waitUntil(10_000) { attempts.get() == 1 }
            dialogButton("保存中…").assertIsNotEnabled().performTouchInput { click() }
            assertEquals(1, attempts.get())
        } finally { release.countDown() }
        dialogText("Fixture password policy rejected").assertIsDisplayed()
        assertEquals("Fixture!password".length, compose.onNodeWithTag("admin-users-password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.length)
        capture("password-server-error")
        dialogButton("保存").performClick(); waitText("已更新 @member 的密码。")
        compose.onNodeWithTag("admin-users-password").assertDoesNotExist()
        assertEquals(2, attempts.get())
        assertTrue(JSONObject(writes().last().body.clone().readUtf8()).getString("password") == "Fixture!password")
        rowAction(3, "修改密码").performClick()
        assertEquals("", compose.onNodeWithTag("admin-users-password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    /** 失败确认框不关闭，重试只写一次，统计从服务器重新读取而非盲目增减。 */
    @Test fun statusFailureRetainsDialogAndReloadsAuthoritativeStatistics() {
        // 刻意令列表统计与本地推算不同，证明客户端使用服务端返回的计数。
        val attempts = AtomicInteger()
        val release = CountDownLatch(1)
        intercept = { r -> when {
            r.method == "PATCH" && path(r).endsWith("/status") && attempts.incrementAndGet() == 1 -> {
                release.await(30, TimeUnit.SECONDS); failure("Fixture status temporarily unavailable", 503)
            }
            r.method == "GET" && attempts.get() >= 2 -> json(page(r).put("activeCount", 0))
            else -> null
        } }
        show(); rowAction(3, "停用账号").performClick()
        try {
            dialogButton("确认").performClick(); compose.waitUntil(10_000) { attempts.get() == 1 }
            dialogButton("处理中…").assertIsNotEnabled().performTouchInput { click() }
            dialogButton("取消").assertIsNotEnabled()
            assertEquals(1, attempts.get())
        } finally { release.countDown() }
        dialogText("Fixture status temporarily unavailable").assertIsDisplayed()
        dialogButton("确认").performClick(); waitText("已停用 @member。")
        rowAction(3, "启用账号").assertIsDisplayed()
        metric("启用", "0")
        assertEquals(2, attempts.get())
        assertTrue(calls.count { it.method == "GET" } >= 2)
    }

    /** 昵称重置后重新查询当前搜索，目标不再命中时不残留旧卡片或错误计数。 */
    @Test fun nicknameResetReloadsTheCurrentSearchAndTotals() {
        // 昵称是唯一搜索命中字段，重置后本页应变为空态。
        show(); search("独立昵称"); rowAction(3, "重置昵称").performClick()
        dialogButton("确认").performClick(); waitText("没有找到匹配的用户。")
        metric("账号", "0"); metric("启用", "0")
        row(3, scroll = false).assertDoesNotExist()
    }

    /** 分页、每页条数和搜索都真正传入目标查询，变更后返回第一页。 */
    @Test fun pageSizeAndSearchResetTheServerQueryToPageOne() {
        // 扩充夹具覆盖多页，避免只测试本地页码文字变化。
        (5..27).forEach { users.add(user(it, "member$it")) }
        show(); listNode().performScrollToNode(hasText("下一页") and hasClickAction())
        compose.onNode(hasText("下一页") and hasClickAction()).performClick()
        waitQuery("page", "2")
        listNode().performScrollToNode(hasTestTag("admin-users-page-size"))
        compose.onNodeWithTag("admin-users-page-size").performClick()
        compose.onNode(hasText("10 条") and hasAnyAncestor(isPopup())).performClick()
        waitQuery("pageSize", "10"); assertEquals("1", calls.last { it.method == "GET" }.requestUrl!!.queryParameter("page"))
        search("member27"); row(27).assertExists()
        assertEquals("1", calls.last { it.method == "GET" }.requestUrl!!.queryParameter("page"))
        metric("账号", "1")
    }

    /** 权限降级销毁密码弹窗和草稿，再次授权也不能找回旧密码。 */
    @Test fun roleChangeDisposesDialogDraftsEvenWithTheSameToken() {
        // 相同令牌下切换权限，覆盖仅以 token 记忆状态的旧问题。
        val current = mutableStateOf(admin)
        host.setContent { HlovetTheme { NativeAdminUsersScreen(current.value, {}) } }
        waitText("@member"); rowAction(3, "修改密码").performClick(); fill("admin-users-password", "fixture-secret")
        compose.runOnIdle { current.value = admin.copy(isSuperAdmin = false, isAdministrator = false) }
        waitText("当前账号没有访问用户管理的权限。")
        compose.onNodeWithTag("admin-users-password").assertDoesNotExist()
        compose.runOnIdle { current.value = admin }
        waitText("@member"); rowAction(3, "修改密码").performClick()
        assertEquals("", compose.onNodeWithTag("admin-users-password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertTrue(writes().isEmpty())
    }

    /** 在同一页面连续切换管理权限、重开密码表单并重试状态操作，不能残留旧组合或忙碌状态。 */
    @Test fun repeatedPermissionAndDialogTransitionsRemainInteractive() {
        // 每轮都从真实页面打开表单并执行两阶段失败/重试，不重建 Activity 来掩盖生命周期问题。
        val current = mutableStateOf(admin)
        val attempts = AtomicInteger()
        intercept = { request ->
            if (request.method == "PATCH" && path(request).endsWith("/status") && attempts.incrementAndGet() % 2 == 1)
                failure("Fixture retry required", 503) else null
        }
        host.setContent { HlovetTheme { NativeAdminUsersScreen(current.value, {}) } }
        repeat(6) { round ->
            // 权限撤销必须销毁密码草稿；恢复权限后重新打开的是空表单。
            waitText("@member")
            rowAction(3, "修改密码").performClick()
            fill("admin-users-password", "fixture-unsubmitted")
            compose.runOnIdle { current.value = admin.copy(isSuperAdmin = false, isAdministrator = false) }
            waitText("当前账号没有访问用户管理的权限。")
            compose.onNodeWithTag("admin-users-password").assertDoesNotExist()
            compose.runOnIdle { current.value = admin }
            waitText("@member")
            rowAction(3, "修改密码").performClick()
            assertEquals("", compose.onNodeWithTag("admin-users-password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            dialogButton("取消").performClick()
            // 错误后两个按钮都要恢复；重试成功后重新查询服务器状态，下一轮执行相反操作。
            rowAction(3, if (round % 2 == 0) "停用账号" else "启用账号").performClick()
            dialogButton("确认").performClick()
            dialogText("Fixture retry required").assertIsDisplayed()
            dialogButton("确认").assertIsEnabled()
            dialogButton("取消").assertIsEnabled()
            dialogButton("确认").performClick()
            waitText(if (round % 2 == 0) "已停用 @member。" else "已启用 @member。")
        }
        // 六轮只允许各一次失败和一次成功，不发送密码请求，也没有重复状态写入。
        assertEquals(12, writes().size)
        assertTrue(writes().all { path(it).endsWith("/status") })
        capture("repeated-permission-and-dialog-transitions")
    }

    /** 账号切换后迟到的列表和写操作结果均不得污染新账号。 */
    @Test fun accountChangeDiscardsLateMutationAndClearsOldSuccess() {
        // 阻塞旧账号写响应，切换后再释放，检查旧成功提示不会回到新页面。
        val release = CountDownLatch(1)
        intercept = { r -> if (r.method == "PATCH") { release.await(30, TimeUnit.SECONDS); json(user(3, "member", administrator = true)) } else null }
        val current = mutableStateOf(admin)
        host.setContent { HlovetTheme { NativeAdminUsersScreen(current.value, {}) } }
        waitText("@member"); rowAction(3, "管理员身份").performClick(); dialogButton("确认").performClick()
        compose.waitUntil(10_000) { writes().size == 1 }
        try {
            compose.runOnIdle { current.value = admin.copy(userId = 99, accessToken = "fixture-reader", isSuperAdmin = false, isAdministrator = false) }
            waitText("当前账号没有访问用户管理的权限。")
        } finally { release.countDown() }
        compose.waitForIdle()
        compose.onNodeWithText("已授予 @member 管理员身份。").assertDoesNotExist()
        compose.onNodeWithTag("admin-user-3").assertDoesNotExist()
    }

    /** 空成功响应不能伪装成无用户，错误页可以直接重试。 */
    @Test fun emptySuccessIsReportedAndRetryLoadsUsers() {
        // 故障只出现一次，重试应恢复真实列表而不是保持空态。
        val reads = AtomicInteger()
        intercept = { r -> if (r.method == "GET" && reads.incrementAndGet() == 1) MockResponse().setResponseCode(200) else null }
        show(wait = false); waitText("用户管理返回为空，请重试。")
        compose.onNodeWithText("重试").performClick(); waitText("@member")
        assertEquals(2, reads.get())
    }

    /** 双因素解绑使用真实返回会话数并刷新服务器凭据状态。 */
    @Test fun totpResetUpdatesTheVisibleSecurityState() {
        // 解除动作完成后相同账号不再提供解除按钮。
        show(); rowAction(3, "解除双因素认证").performClick(); dialogButton("确认").performClick()
        waitText("已解除 @member 的双因素认证，并撤销 2 个会话。")
        assertRowActionAbsent(3, "解除双因素认证")
        assertEquals(1, writes().size)
        capture("totp-reset-confirmed")
    }

    /** 英文权限错误在弹窗内显示英文，避免页面语言混用。 */
    @Test fun englishPermissionFailureStaysInTheActiveDialog() {
        // 只切换本测试语言，失败不会关闭确认框。
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("en-US") }
        intercept = { r -> if (r.method == "PATCH") failure("Forbidden", 403) else null }
        show(); rowAction(3, "Disable account").performClick(); dialogButton("Confirm").performClick()
        dialogText("This account cannot perform this management action.").assertIsDisplayed()
        dialogButton("Confirm").assertIsEnabled()
        capture("english-permission-error")
    }

    /** 渲染 account 的页面；wait 决定是否等待成员行加载。 */
    private fun show(account: NativeSession = admin, wait: Boolean = true) {
        // 独立 Compose 宿主避免测试间残留旧页面。
        host.setContent { HlovetTheme { NativeAdminUsersScreen(account, {}) } }
        if (wait) waitText("@member")
    }

    /** 返回用户列表，供懒加载滚动定位使用。 */
    private fun listNode() = compose.onNodeWithTag("admin-users-list")

    /** 定位 id 行；scroll 为 false 时只读取已有语义，不创建屏幕外节点。 */
    private fun row(id: Int, scroll: Boolean = true): SemanticsNodeInteraction {
        // 先让懒列表创建目标行，再用稳定 ID 断言。
        if (scroll) listNode().performScrollToNode(hasTestTag("admin-user-$id"))
        val node = compose.onNodeWithTag("admin-user-$id")
        if (scroll) node.performScrollTo()
        return node
    }

    /** 返回 id 行内 description 操作，避免误点其它用户同名按钮。 */
    private fun rowAction(id: Int, description: String): SemanticsNodeInteraction {
        // 合并树保留按钮自身的可访问描述。
        row(id)
        return compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("admin-user-$id")))
    }

    /** 确认 id 行中没有 description 操作，覆盖只读权限边界。 */
    private fun assertRowActionAbsent(id: Int, description: String) {
        // 目标行先滚动可见，不能因未加载而误判按钮缺失。
        row(id)
        compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("admin-user-$id"))).assertDoesNotExist()
    }

    /** 等待 text 实际显示，不用固定延迟代替异步结果。 */
    private fun waitText(text: String) {
        // 未加载完成时持续推进 Compose 帧。
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 返回当前对话框中的 text，确保错误没有落在被遮挡的页面背后。 */
    private fun dialogText(text: String): SemanticsNodeInteraction {
        // 错误节点必须属于 Dialog 语义子树。
        try {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(text) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            // 在宿主销毁前保存首个失败现场，避免清理阶段异常遮盖真正的界面与请求状态。
            recordDialogWaitFailure(text, error)
            throw error
        }
        val node = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
        if (!node.isDisplayed()) node.performScrollTo()
        return node
    }

    /** 保存 expectedText 等待失败时的 error、屏幕和请求路径，不记录认证头或请求正文。 */
    private fun recordDialogWaitFailure(expectedText: String, error: Throwable) {
        // 证据写入测试私有目录，失败的证据收集不能取代或掩盖原异常。
        val name = "dialog-wait-${System.currentTimeMillis()}"
        runCatching { capture(name) }
        runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.filesDir, "native-admin-users-device-evidence").apply { mkdirs() }
            File(directory, "$name.txt").writeText(
                "Expected: $expectedText\nFailure: ${error.javaClass.name}: ${error.message}\n" +
                    "Requests:\n" + calls.joinToString("\n") { "${it.method} ${path(it)}" },
            )
        }
    }

    /** 返回对话框内可点击 text 按钮，不匹配背后的页面标题。 */
    private fun dialogButton(text: String): SemanticsNodeInteraction {
        // 等错误反馈与忙碌标签在同一帧更新后再定位，避免抓到旧的“处理中”按钮。
        val matcher = hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    /** 将 tag 输入框替换为 value，保持真实键盘编辑语义。 */
    private fun fill(tag: String, value: String) {
        // 密码输入不写测试报告或截图文本。
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }

    /** 搜索 value 并等待其成为服务器实际查询条件。 */
    private fun search(value: String) {
        // 列表可能已滚动到底部，先恢复搜索框可见性。
        listNode().performScrollToNode(hasTestTag("admin-users-search"))
        compose.onNodeWithTag("admin-users-search").performTextReplacement(value)
        waitQuery("search", value)
        compose.waitForIdle()
    }

    /** 等待列表请求携带 key=value，再等待界面加载完成。 */
    private fun waitQuery(key: String, value: String) {
        // 核对实际网络参数，不仅检查页码文案。
        compose.waitUntil(15_000) { calls.any { it.method == "GET" && it.requestUrl!!.queryParameter(key) == value } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("正在读取用户列表…").fetchSemanticsNodes().isEmpty() }
    }

    /** 核对 label 指标的 value，避免其它行数字造成误判。 */
    private fun metric(label: String, value: String) {
        // 指标在列表顶部，单独滚动并限定祖先。
        listNode().performScrollToNode(hasTestTag("admin-users-metric-$label"))
        compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("admin-users-metric-$label")), useUnmergedTree = true).assertIsDisplayed()
    }

    /** 返回所有写请求，供重复提交与无权限断言使用。 */
    private fun writes() = calls.filter { it.method == "PATCH" }

    /** 返回 request 的路径，不含令牌与查询值。 */
    private fun path(request: RecordedRequest) = request.requestUrl!!.encodedPath

    /** 构造 id 账号，其他参数描述昵称、安全与管理层级夹具。 */
    private fun user(id: Int, name: String, nickname: String = name, superAdmin: Boolean = false, administrator: Boolean = false, totp: Boolean = false, status: String = "active"): JSONObject {
        // 测试身份与邮箱不指向真实用户。
        return JSONObject().put("id", id).put("username", name).put("nickname", nickname).put("email", "$name@example.invalid")
            .put("status", status).put("isSuperAdmin", superAdmin).put("isAdministrator", administrator).put("totpEnabled", totp)
            .put("role", JSONObject().put("code", "member").put("name", "Member").put("level", 1))
    }

    /** 根据 request 的分页与搜索生成权威列表结果。 */
    private fun page(request: RecordedRequest): JSONObject {
        // 同服务端按用户名或昵称包含匹配，再统计与分页。
        val query = request.requestUrl!!
        val search = query.queryParameter("search").orEmpty()
        val filtered = users.filter { it.getString("username").contains(search) || it.getString("nickname").contains(search) }
        val size = query.queryParameter("pageSize")?.toInt() ?: 20
        val pages = maxOf(1, (filtered.size + size - 1) / size)
        val page = (query.queryParameter("page")?.toInt() ?: 1).coerceIn(1, pages)
        return JSONObject().put("items", JSONArray(filtered.drop((page - 1) * size).take(size)))
            .put("total", filtered.size).put("activeCount", filtered.count { it.getString("status") == "active" })
            .put("page", page).put("pageSize", size).put("totalPages", pages)
    }

    /** 对 request 执行隔离夹具的查询或更新，未知路径明确失败。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 只允许用户管理的精确路径，不模拟宽泛成功。
        if (request.method == "GET" && path(request) == "/users") return json(page(request))
        val id = Regex("/(?:users/)(\\d+)").find(path(request))?.groupValues?.get(1)?.toInt()
        val current = users.find { it.getInt("id") == id } ?: return failure("Unknown fixture user", 404)
        val updated = JSONObject(current.toString())
        val body = request.body.clone().readUtf8().takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
        when {
            path(request).endsWith("/administrator") -> updated.put("isAdministrator", body.getBoolean("isAdministrator"))
            path(request).endsWith("/status") -> updated.put("status", body.getString("status"))
            path(request).endsWith("/nickname/reset") -> updated.put("nickname", current.getString("username"))
            path(request).endsWith("/totp/reset") -> updated.put("totpEnabled", false)
            path(request).endsWith("/password") -> Unit
            else -> return failure("Unexpected fixture request", 404)
        }
        users[users.indexOf(current)] = updated
        return if (path(request).endsWith("/totp/reset")) json(JSONObject().put("revokedSessions", 2)) else json(updated)
    }

    /** 将 value 作为 JSON 响应返回，不包含认证头或请求体。 */
    private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())

    /** 返回 code 错误和可见 message，用于验证重试与表单反馈。 */
    private fun failure(message: String, code: Int) = json(JSONObject().put("message", message)).setResponseCode(code)

    /** 保存 name 整屏截图为证据；密码字段始终使用遮罩。 */
    private fun capture(name: String) {
        // 只写测试私有证据，不改变账号与业务数据。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "native-admin-users-device-evidence").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
