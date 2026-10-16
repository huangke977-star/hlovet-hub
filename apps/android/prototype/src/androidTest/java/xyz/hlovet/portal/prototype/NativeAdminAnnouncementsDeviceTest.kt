package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

/** 在设备内隔离公告服务中核对权限、草稿两阶段提交和错误恢复，不写真实数据库。 */
@RunWith(AndroidJUnit4::class)
class NativeAdminAnnouncementsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private val announcements = CopyOnWriteArrayList<JSONObject>()
    /** 按请求返回临时故障，null 表示继续使用正常夹具；不改变真实服务或账号。 */
    @Volatile private var intercept: (RecordedRequest) -> MockResponse? = { null }
    private val nextId = AtomicInteger(20)
    private val admin = NativeSession("fixture-announcement-root", "fixture-refresh", 1, "root", "Root",
        PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
        isSuperAdmin = true, isAdministrator = true)

    /** 仅允许精确隔离地址构建，准备不同状态公告和角色，不触碰保存的登录态。 */
    @Before fun start() {
        // 地址保护先于启动服务，误用本地/正式包只跳过，不误发写请求。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        announcements.addAll(listOf(announcement(1, "草稿甲"), announcement(2, "发布乙", "published"),
            announcement(3, "归档丙", "archived"), announcement(4, "定时丁", "scheduled")))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 记录 request 的调用边界，再由当前故障夹具或正常服务响应。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 认证头和正文只存在测试内存，不写入证据报告。
                calls.add(request)
                return intercept(request) ?: respond(request)
            }
        }
        server.start(39871)
    }

    /** 先销毁组合，关闭服务并恢复原语言；不修改设备真实会话或业务数据。 */
    @After fun stop() {
        // 未通过地址保护时不访问未初始化服务。
        if (!::server.isInitialized) return
        try { host.dispose() } finally {
            try { server.shutdown() } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 未登录和普通用户均不能读取公告管理、角色或发起任何写操作。 */
    @Test fun ordinaryUserDoesNotMakeAdministrativeRequests() {
        // 有 token 但没有管理权限，也不能先向后台请求再依赖 403。
        val account = mutableStateOf<NativeSession?>(admin.copy(isSuperAdmin = false, isAdministrator = false))
        host.setContent { HlovetTheme { NativeAdminAnnouncementsScreen(account.value, {}) } }
        waitText("当前账号没有公告管理权限。")
        compose.onNodeWithContentDescription("新建公告").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
        compose.runOnIdle { account.value = null }
        waitText("登录后查看公告管理。")
        assertTrue(calls.isEmpty())
    }

    /** 普通管理员仅删除草稿，超级管理员可删除已发布、归档和定时公告。 */
    @Test fun deletionActionsMatchServerAndPcManagementHierarchy() {
        // 同一页面切换权限必须重建，不能延用上一身份的按钮。
        val account = mutableStateOf(admin.copy(isSuperAdmin = false))
        host.setContent { HlovetTheme { NativeAdminAnnouncementsScreen(account.value, {}) } }
        waitText("共 4 条公告")
        rowAction(1, "删除").assertExists()
        listOf(2, 3, 4).forEach { assertActionAbsent(it, "删除") }
        rowAction(2, "归档").assertExists()
        compose.runOnIdle { account.value = admin }
        waitText("共 4 条公告")
        listOf(1, 2, 3, 4).forEach { rowAction(it, "删除").assertExists() }
        assertTrue(writes().isEmpty())
    }

    /** 删除失败留在当前确认框，重试成功后读取实际总数并移除已删除行。 */
    @Test fun failedDeleteRemainsInDialogAndCanRetry() {
        // 第一次服务错误不能显示为成功，也不能关闭用户确认上下文。
        val failures = AtomicInteger(1)
        intercept = { request -> if (request.method == "DELETE" && failures.getAndDecrement() > 0) failure("测试删除暂不可用", 503) else null }
        show(); rowAction(2, "删除").performClick(); dialogButton("删除").performClick()
        dialogText("测试删除暂不可用").assertIsDisplayed()
        assertEquals(1, writes().size)
        assertTrue(announcements.any { it.getInt("id") == 2 })
        capture("delete-error-in-dialog")
        dialogButton("删除").performClick()
        waitText("公告已删除。")
        waitText("共 3 条公告")
        assertFalse(announcements.any { it.getInt("id") == 2 })
        assertEquals(2, writes().size)
    }

    /** 发布请求阻塞期间按钮与取消均禁用，重复点击不会发送第二次写请求。 */
    @Test fun pendingMutationDisablesCancellationAndDuplicateSubmission() {
        // 用有界闩锁停在真实网络等待阶段，检查界面互斥而非仅调用方法。
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        intercept = { request ->
            if (path(request) == "/announcements/admin/1/publish") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            null
        }
        try {
            show(); rowAction(1, "发布").performClick(); dialogButton("发布").performClick()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            dialogButton("发布").assertIsNotEnabled().performTouchInput { click() }
            dialogButton("取消").assertIsNotEnabled()
            assertEquals(1, writes().size)
        } finally { release.countDown() }
        waitText("公告已发布或安排定时发布。")
        rowAction(1, "归档").assertExists()
        assertEquals(1, writes().size)
    }

    /** 草稿保存错误显示在编辑弹窗并保留正文，成功重试只创建一次有效草稿。 */
    @Test fun draftSaveErrorPreservesEditableContentAndRetry() {
        // 第一次失败不持久化，重试使用相同内容且错误反馈位于可见弹窗内。
        val failures = AtomicInteger(1)
        intercept = { request -> if (request.method == "POST" && path(request) == "/announcements/admin" && failures.getAndDecrement() > 0) failure("测试草稿保存失败", 500) else null }
        show(); compose.onNodeWithContentDescription("新建公告").performClick()
        fill("admin-announcement-title", "保留草稿标题")
        fill("admin-announcement-body", "保留正文\n第二段内容")
        compose.onNodeWithTag("admin-announcement-save").performClick()
        dialogText("测试草稿保存失败").assertIsDisplayed()
        compose.onNodeWithTag("admin-announcement-body").assertTextContains("保留正文\n第二段内容")
        capture("draft-save-error")
        compose.onNodeWithTag("admin-announcement-save").performClick()
        dialogText("公告草稿已保存。").assertIsDisplayed()
        assertEquals(1, announcements.count { it.getString("title") == "保留草稿标题" })
        assertEquals("保留正文\n第二段内容", announcements.last().getString("content"))
    }

    /** 保存成功、发布失败后保留草稿 ID，重新发布先更新原草稿而非再次创建。 */
    @Test fun publishFailureRetainsSavedIdentityAndRetryDoesNotDuplicateDraft() {
        // 两阶段接口边界按真实顺序核对：创建、发布失败、更新、发布成功。
        val failures = AtomicInteger(1)
        intercept = { request -> if (path(request).endsWith("/publish") && failures.getAndDecrement() > 0) failure("测试发布失败", 503) else null }
        show(); compose.onNodeWithContentDescription("新建公告").performClick()
        fill("admin-announcement-title", "发布重试草稿")
        fill("admin-announcement-body", "发布正文")
        compose.onNodeWithTag("announcement-publish").performClick()
        dialogText("测试发布失败").assertIsDisplayed()
        val savedId = announcements.last().getInt("id")
        assertEquals("draft", announcements.last().getString("status"))
        compose.onNodeWithTag("announcement-publish").performClick()
        waitText("公告已发布。")
        assertEquals(listOf("POST /announcements/admin", "POST /announcements/admin/$savedId/publish",
            "PATCH /announcements/admin/$savedId", "POST /announcements/admin/$savedId/publish"), writes().map { "${it.method} ${path(it)}" })
        assertEquals(1, announcements.count { it.getString("title") == "发布重试草稿" })
        assertEquals("published", announcements.last().getString("status"))
    }

    /** 页面打开的表单遇到账号切换或权限撤销必须清空，不继承旧草稿和管理请求。 */
    @Test fun accountAndPermissionChangesDisposeSensitiveEditorState() {
        // 换成另一个有效管理员时草稿也必须消失，不能只依赖 token 的 remember。
        val account = mutableStateOf(admin)
        host.setContent { HlovetTheme { NativeAdminAnnouncementsScreen(account.value, {}) } }
        waitText("共 4 条公告")
        compose.onNodeWithContentDescription("新建公告").performClick()
        fill("admin-announcement-title", "旧账号敏感草稿")
        compose.runOnIdle { account.value = admin.copy(userId = 9, accessToken = "fixture-announcement-other") }
        waitText("共 4 条公告")
        compose.onNodeWithTag("admin-announcement-title").assertDoesNotExist()
        compose.onNodeWithContentDescription("新建公告").performClick()
        assertEquals("", compose.onNodeWithTag("admin-announcement-title").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val count = calls.size
        compose.runOnIdle { account.value = account.value.copy(isAdministrator = false, isSuperAdmin = false) }
        waitText("当前账号没有公告管理权限。")
        compose.onNodeWithTag("admin-announcement-title").assertDoesNotExist()
        compose.waitForIdle()
        assertEquals(count, calls.size)
        assertTrue(writes().isEmpty())
    }

    /** 旧账号慢查询返回后不能覆盖新账号的数据，也不能重新打开编辑器。 */
    @Test fun lateResultFromPreviousAccountCannotPopulateNewPage() {
        // 只阻塞旧令牌的列表响应，新身份使用独立列表和组合。
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        intercept = { request ->
            if (path(request) == "/announcements/admin" && request.getHeader("Authorization") == "Bearer ${admin.accessToken}") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                json(JSONObject().put("items", JSONArray().put(announcement(80, "旧账号结果"))).put("total", 1))
            } else null
        }
        val account = mutableStateOf(admin)
        try {
            host.setContent { HlovetTheme { NativeAdminAnnouncementsScreen(account.value, {}) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.runOnIdle { account.value = admin.copy(userId = 9, accessToken = "fixture-announcement-new") }
            waitText("共 4 条公告")
        } finally { release.countDown() }
        compose.waitForIdle()
        compose.onNodeWithText("旧账号结果").assertDoesNotExist()
        assertTrue(writes().isEmpty())
    }

    /** 搜索及状态筛选都交给服务端，非第一页变更条件后回到第一页。 */
    @Test fun searchStatusAndPaginationUseAuthoritativeQueries() {
        // 大于单页的夹具验证页码，不以当前屏幕的局部行数冒充总数。
        (5..18).forEach { announcements.add(announcement(it, "额外公告$it")) }
        show(); listNode().performScrollToNode(hasText("下一页") and hasClickAction())
        compose.onNodeWithText("下一页").performClick()
        waitQuery("page", "2")
        listNode().performScrollToNode(hasTestTag("admin-announcements-search"))
        compose.onNodeWithTag("admin-announcements-search").performTextReplacement("甲")
        waitQuery("search", "甲")
        waitText("共 1 条公告")
        assertEquals("1", calls.last { path(it) == "/announcements/admin" }.requestUrl!!.queryParameter("page"))
        val beforeClear = calls.size
        compose.onNodeWithTag("admin-announcements-search").performTextReplacement("")
        waitQuery("page", "1", after = beforeClear)
        compose.onNode(hasText("已发布") and hasClickAction()).performClick()
        waitQuery("status", "published")
        waitText("共 1 条公告")
    }

    /** 无效编号、空响应和缺失必要字段不能被解析为成功或空列表。 */
    @Test fun invalidIdsAndIncompleteSuccessResponsesAreRejected() {
        // API 契约边界独立检查，不需要挂载页面或真实账号。
        assertTrue(runCatching { NativeAdminAnnouncementsApi.get(admin.accessToken, 0) }.isFailure)
        assertTrue(runCatching { NativeAdminAnnouncementsApi.delete(admin.accessToken, -1) }.isFailure)
        assertTrue(calls.isEmpty())
        intercept = { MockResponse().setResponseCode(200) }
        assertEquals("服务器未返回公告数据。", runCatching { NativeAdminAnnouncementsApi.list(admin.accessToken) }.exceptionOrNull()?.message)
        intercept = { json(JSONObject()) }
        assertEquals("公告列表响应不完整。", runCatching { NativeAdminAnnouncementsApi.list(admin.accessToken) }.exceptionOrNull()?.message)
        assertEquals("服务器未确认公告删除成功。", runCatching { NativeAdminAnnouncementsApi.delete(admin.accessToken, 1) }.exceptionOrNull()?.message)
        intercept = { json(JSONObject().put("id", 1)) }
        assertEquals("公告详情响应不完整。", runCatching { NativeAdminAnnouncementsApi.get(admin.accessToken, 1) }.exceptionOrNull()?.message)
    }

    /** 定时、角色、置顶及推送配置完整序列化，切回公开立即发布时清除无关旧字段。 */
    @Test fun scheduledAndRoleInputsRoundTripWithoutLeakingInactiveOptions() {
        // 对比实际网络 JSON，不仅检查表单选中状态。
        val input = NativeAdminAnnouncementInput(" 配置公告 ", " 摘要 ", "正文", "role_restricted", "scheduled",
            true, 3, false, "2030-01-02T03:04:00Z", "2030-01-03T03:04:00Z", listOf("member", "member", ""))
        val created = NativeAdminAnnouncementsApi.create(admin.accessToken, input)
        assertEquals(listOf("member"), created.summary.roleCodes)
        assertEquals("2030-01-02T03:04:00Z", created.summary.scheduledAt)
        assertTrue(created.summary.isPinned)
        assertFalse(created.summary.pushEnabled)
        assertEquals(3, created.summary.pinOrder)
        val scheduled = NativeAdminAnnouncementsApi.publish(admin.accessToken, created.summary.id)
        assertEquals("scheduled", scheduled.summary.status)
        NativeAdminAnnouncementsApi.update(admin.accessToken, created.summary.id, input.copy(audience = "public", publishMode = "immediate"))
        val body = JSONObject(writes().last().body.clone().readUtf8())
        assertEquals("配置公告", body.getString("title"))
        assertEquals("摘要", body.getString("summary"))
        assertTrue(body.isNull("scheduledAt"))
        assertEquals(0, body.getJSONArray("roleCodes").length())
    }

    /** 归档成功刷新可见状态，普通管理员归档后不再展示删除按钮。 */
    @Test fun archivingReloadsServerStateWithoutGrantingDeletion() {
        // 普通管理员权限在状态变化后仍需维持，不因本地成功反馈放宽。
        show(admin.copy(isSuperAdmin = false))
        rowAction(2, "归档").performClick()
        dialogButton("归档").performClick()
        waitText("公告已归档。")
        assertActionAbsent(2, "归档")
        assertActionAbsent(2, "删除")
        assertEquals("archived", announcements.first { it.getInt("id") == 2 }.getString("status"))
        assertEquals(1, writes().size)
    }

    /** 归档公告可重新编辑发布，定时公告只允许编辑而不重复触发发布操作。 */
    @Test fun archivedEditingAndScheduledActionsMatchPcLifecycle() {
        // 状态变化与保存 ID 均由实际界面调用精确接口完成。
        show(admin.copy(isSuperAdmin = false))
        rowAction(4, "编辑").assertExists()
        assertActionAbsent(4, "发布")
        rowAction(3, "发布").assertIsEnabled()
        rowAction(3, "编辑").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("admin-announcement-title").fetchSemanticsNodes().isNotEmpty() }
        fill("admin-announcement-title", "归档重新编辑")
        compose.onNodeWithTag("admin-announcement-save").performClick()
        dialogText("公告草稿已保存。").assertIsDisplayed()
        compose.onNode(hasContentDescription("关闭") and hasAnyAncestor(isDialog())).performClick()
        rowAction(3, "发布").performClick()
        dialogButton("发布").performClick()
        waitText("公告已发布或安排定时发布。")
        assertEquals(listOf("PATCH /announcements/admin/3", "POST /announcements/admin/3/publish"), writes().map { "${it.method} ${path(it)}" })
        assertEquals("published", announcements.first { it.getInt("id") == 3 }.getString("status"))
    }

    /** 英文模式的 403 留在操作弹窗，401 列表错误不冒充没有公告。 */
    @Test fun englishPermissionAndExpiredSessionFeedbackRemainVisible() {
        // 改语言只影响当前测试，结束后恢复用户原设置。
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("en") }
        intercept = { request -> if (request.method == "DELETE") failure("Forbidden", 403) else null }
        show(); rowAction(1, "Delete").performClick(); dialogButton("Delete").performClick()
        dialogText("This account cannot perform this announcement action.").assertIsDisplayed()
        capture("english-permission-error")
        dialogButton("Cancel").performClick()
        intercept = { request -> if (path(request) == "/announcements/admin") failure("Unauthorized", 401) else null }
        listNode().performScrollToIndex(0)
        compose.onNodeWithContentDescription("Refresh").performClick()
        waitText("Your session expired. Sign in again.")
        compose.onNodeWithText("No matching announcements.").assertDoesNotExist()
    }

    /** 挂载 account 的实际页面，等待列表完成，不安装 MainActivity 或修改其保存会话。 */
    private fun show(account: NativeSession = admin) {
        // 独立宿主控制组合生命周期，使测试间不残留对话框。
        host.setContent { HlovetTheme { NativeAdminAnnouncementsScreen(account, {}) } }
        waitText(if (NativeI18n.locale.startsWith("en")) "4 announcements" else "共 ${announcements.size} 条公告")
    }

    /** 返回稳定标记的列表，用于先创建懒加载行再定位其按钮。 */
    private fun listNode(): SemanticsNodeInteraction {
        // 不直接滚动横向操作按钮，避免与纵向列表发生定位循环。
        return compose.onNodeWithTag("admin-announcements-list")
    }

    /** 返回 id 公告行内 description 操作，先滚动目标行至可见。 */
    private fun rowAction(id: Int, description: String): SemanticsNodeInteraction {
        // 稳定 ID 限定祖先，避免点中另一行的同名操作。
        listNode().performScrollToNode(hasTestTag("admin-announcement-$id"))
        compose.onNodeWithTag("admin-announcement-$id").performScrollTo()
        return compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("admin-announcement-$id")))
    }

    /** 确认 id 行内没有 description 操作，不能因懒加载未创建而误判不存在。 */
    private fun assertActionAbsent(id: Int, description: String) {
        // 先创建行语义，再核对该行的权限按钮。
        listNode().performScrollToNode(hasTestTag("admin-announcement-$id"))
        compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("admin-announcement-$id"))).assertDoesNotExist()
    }

    /** 等待 text 出现在实际语义树，持续推进 Compose 帧而不是固定睡眠。 */
    private fun waitText(text: String) {
        // 网络异步完成后文字才是用户可核对状态。
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 返回弹窗中的 text 反馈，必要时在弹窗正文内滚动，不接受页面背后的错误。 */
    private fun dialogText(text: String): SemanticsNodeInteraction {
        // 错误必须存在于活动对话框语义子树。
        val matcher = hasText(text) and hasAnyAncestor(isDialog())
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNode(matcher)
        if (!node.isDisplayed()) node.performScrollTo()
        return node
    }

    /** 返回弹窗内可点击 text 按钮，等待忙碌状态的语义同步完成。 */
    private fun dialogButton(text: String): SemanticsNodeInteraction {
        // 同名发布按钮只接受当前 Dialog 的操作栏。
        val matcher = hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    /** 将 tag 标识的输入框替换为 value，不直接改页面内部状态。 */
    private fun fill(tag: String, value: String) {
        // 真实编辑语义同时覆盖滚动到较长表单内的字段。
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }

    /** 等待 after 之后的列表请求带 key=value，核对实际服务端查询而不只看页码。 */
    private fun waitQuery(key: String, value: String, after: Int = 0) {
        // 等待加载结束，后续点击不能落到上一帧正在更换的列表。
        compose.waitUntil(15_000) { calls.drop(after).any { path(it) == "/announcements/admin" && it.requestUrl!!.queryParameter(key) == value } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("正在读取公告…").fetchSemanticsNodes().isEmpty() }
    }

    /** 返回全部非 GET 请求，以检查写顺序和重复提交。 */
    private fun writes(): List<RecordedRequest> {
        // 仅在内存核对请求，不输出认证信息。
        return calls.filter { it.method != "GET" }
    }

    /** 返回 request 的路径，去掉查询参数和授权内容。 */
    private fun path(request: RecordedRequest): String {
        // 精确路径用于限定夹具支持范围。
        return request.requestUrl!!.encodedPath
    }

    /** 构造 id、title、status 描述的独立公告，含完整详情字段。 */
    private fun announcement(id: Int, title: String, status: String = "draft"): JSONObject {
        // 虚构公告不包含真实用户内容或服务器配置。
        return JSONObject().put("id", id).put("title", title).put("summary", "摘要$title").put("content", "正文$title")
            .put("status", status).put("audience", "public").put("publishMode", if (status == "scheduled") "scheduled" else "immediate")
            .put("pushEnabled", false).put("isPinned", false).put("pinOrder", 0).put("roleCodes", JSONArray())
            .put("createdAt", "2026-10-16T00:00:00Z").put("updatedAt", "2026-10-16T00:00:00Z")
    }

    /** 按 request 的搜索、状态和分页构造列表，数量与筛选后的数据一致。 */
    private fun page(request: RecordedRequest): JSONObject {
        // 夹具先过滤再分页，和实际服务端口径相同。
        val query = request.requestUrl!!
        val search = query.queryParameter("search").orEmpty()
        val status = query.queryParameter("status")
        val filtered = announcements.filter { it.getString("title").contains(search) && (status == null || it.getString("status") == status) }
        val size = query.queryParameter("pageSize")?.toInt() ?: 12
        val pages = maxOf(1, (filtered.size + size - 1) / size)
        val current = (query.queryParameter("page")?.toInt() ?: 1).coerceIn(1, pages)
        return JSONObject().put("items", JSONArray(filtered.drop((current - 1) * size).take(size))).put("total", filtered.size)
            .put("page", current).put("pageSize", size).put("totalPages", pages)
    }

    /** 对 request 支持精确公告接口；未知路径明确失败，不使用宽泛空成功。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 角色接口保持真实数组形状，只有授权页面才会请求。
        val path = path(request)
        if (path == "/roles" && request.method == "GET") return MockResponse().setHeader("Content-Type", "application/json").setBody("[{\"code\":\"member\",\"name\":\"Member\",\"level\":1}]")
        if (path == "/announcements/admin" && request.method == "GET") return json(page(request))
        val body = request.body.clone().readUtf8().takeIf { it.isNotBlank() }?.let(::JSONObject)
        if (path == "/announcements/admin" && request.method == "POST") {
            // 新建只分配一次稳定 ID，发布另走独立请求。
            val created = announcement(nextId.incrementAndGet(), body!!.getString("title"))
            body.keys().forEach { created.put(it, body.get(it)) }
            announcements.add(created)
            return json(created)
        }
        val id = Regex("^/announcements/admin/(\\d+)(?:/(publish|archive))?$").matchEntire(path)?.groupValues?.get(1)?.toInt()
        val current = announcements.firstOrNull { it.getInt("id") == id } ?: return failure("Unknown fixture announcement", 404)
        if (request.method == "GET") return json(current)
        if (request.method == "DELETE") {
            // 删除仅修改测试内存，响应契约与 API 一致。
            announcements.remove(current)
            return json(JSONObject().put("success", true))
        }
        val updated = JSONObject(current.toString())
        when {
            path.endsWith("/publish") -> updated.put("status", if (updated.getString("publishMode") == "scheduled") "scheduled" else "published")
            path.endsWith("/archive") -> updated.put("status", "archived")
            request.method == "PATCH" && body != null -> {
                // 保存已定时的公告同样退回草稿，下一次发布重新安排。
                body.keys().forEach { updated.put(it, body.get(it)) }
                updated.put("status", "draft")
            }
            else -> return failure("Unexpected fixture mutation", 404)
        }
        announcements[announcements.indexOf(current)] = updated
        return json(updated)
    }

    /** 将 value 返回为 JSON 响应，不打印请求数据。 */
    private fun json(value: JSONObject): MockResponse {
        // 所有成功夹具保持真实接口对象结构。
        return MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    }

    /** 返回 code 与 message 错误，供当前界面验证失败反馈及重试。 */
    private fun failure(message: String, code: Int): MockResponse {
        // 服务端错误不改变夹具持久化状态。
        return json(JSONObject().put("message", message)).setResponseCode(code)
    }

    /** 保存 name 对应的真实整屏截图，证据只写设备测试私有目录。 */
    private fun capture(name: String) {
        // 截图用来核对弹窗遮挡和反馈位置，不替代业务断言。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "native-admin-announcements-device-evidence").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
