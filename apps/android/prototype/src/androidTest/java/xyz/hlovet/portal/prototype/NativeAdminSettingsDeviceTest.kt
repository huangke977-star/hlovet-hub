package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 在设备内隔离 HTTP 服务验证站点配置交互与账号边界，不修改真实配置或保存会话。 */
@RunWith(AndroidJUnit4::class)
class NativeAdminSettingsDeviceTest {
    @get:Rule val compose = createNativeComposeRule()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private val taxonomies = CopyOnWriteArrayList<JSONObject>()
    private val nextId = AtomicInteger(20)
    @Volatile private var saved = JSONObject()
    /** 为当前 request 注入一次故障或等待；null 表示继续使用正常夹具。 */
    @Volatile private var intercept: (RecordedRequest) -> MockResponse? = { null }
    private val admin = NativeSession("fixture-settings-root", "fixture-refresh", 1, "root", "Root",
        PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
        isSuperAdmin = true, isAdministrator = true)

    /** 先限定隔离构建地址，再准备虚构设置、资源和角色，防止误向正式服务提交。 */
    @Before fun start() {
        // 跳过不匹配来源的包；不启动服务、不改设备登录态。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        saved = JSONObject().put("siteName", "Fixture site").put("browserTitle", "Fixture title")
            .put("logoPath", "/logo.svg").put("pwaIconPath", "/pwa-logo.png").put("defaultRoleCode", "reader")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 记录 request 的内存副本，再按当前场景响应，不输出认证头或请求正文。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 请求边界用于核对写入数量；现场证据只记录虚构页面截图。
                calls.add(request)
                return intercept(request) ?: respond(request)
            }
        }
        server.start(39871)
    }

    /** 先卸载组合，再关闭服务并恢复语言；保留所有真实账号和测试证据。 */
    @After fun stop() {
        // 未通过来源保护的场景没有服务需要关闭。
        if (!::server.isInitialized) return
        try { host.dispose() } finally {
            try { server.shutdown() } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 普通管理员及未登录状态均不能读取站点设置、资源、背景或角色。 */
    @Test fun onlySuperAdministratorsCanLoadSettings() {
        // 同一宿主切换身份，不能只隐藏配置却继续读取管理数据。
        val account = mutableStateOf<NativeSession?>(admin.copy(isSuperAdmin = false))
        host.setContent { HlovetTheme { NativeAdminSettingsScreen(account.value, {}) } }
        waitText("站点设置仅超级管理员可访问。")
        compose.onNodeWithContentDescription("刷新").assertIsNotEnabled()
        compose.onNodeWithContentDescription("恢复默认").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
        compose.runOnIdle { account.value = null }
        waitText("登录后查看站点设置。")
        compose.onNodeWithContentDescription("刷新").assertIsNotEnabled()
        compose.onNodeWithContentDescription("恢复默认").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }

    /** 内置 Logo、PWA 图标和服务端角色均可选择，提交使用资源路径及角色代码而非显示名称。 */
    @Test fun builtinAssetsAndServerRolesPersistWithTheirStableValues() {
        // 通过实际菜单选择两个内置资源，不能把可选项只写进静态清单。
        show()
        compose.onNodeWithTag("site-asset-select-logo").performScrollTo().performClick()
        compose.onNodeWithText("页签图标").performClick()
        compose.onNodeWithTag("site-asset-select-pwa_icon").performScrollTo().performClick()
        compose.onNodeWithText("512 图标").performClick()
        find(hasTestTag("site-default-role")).performClick()
        compose.onNodeWithText("Writer role").performClick()
        find(hasText("保存全部设置")).performNativeStableClick(compose)
        compose.waitUntil(10_000) { calls.any { it.method == "PATCH" && path(it) == "/site-settings" } }
        // 核对写入值和返回结果；这些菜单选择在保存之前不能偷偷提交。
        val writes = calls.filter { it.method != "GET" }
        assertEquals(1, writes.size)
        val body = JSONObject(writes.single().body.clone().readUtf8())
        assertEquals("/tab-icon.svg", body.getString("logoPath"))
        assertEquals("/icon-512.png", body.getString("pwaIconPath"))
        assertEquals("writer", body.getString("defaultRoleCode"))
        compose.onNodeWithTag("admin-site-settings-list").performScrollToIndex(0)
        waitText("站点设置已保存。")
    }

    /** 多个长文件名不再挤在同一横行，展开列表沿用共享玻璃背景并可选择后部资源。 */
    @Test fun longAssetListsRemainBoundedAndSelectable() {
        // 十二条虚构资源覆盖超出首屏的候选，最后一条使用已带 API 前缀的路径。
        show()
        compose.onNodeWithTag("site-asset-select-logo").performScrollTo().performClick()
        // 同名资源也显示在页面列表，必须限定弹出候选而非误点背景行。
        val lastOption = compose.onNode(hasText(assetName(12)) and hasAnyAncestor(isPopup()) and hasClickAction())
        lastOption.performScrollTo().assertIsDisplayed()
        capture("long-asset-menu")
        lastOption.performClick()
        compose.onNodeWithTag("site-asset-select-logo").assertTextContains(assetName(12))
        val bounds = compose.onNodeWithTag("site-asset-select-logo").fetchSemanticsNode().boundsInRoot
        val root = compose.onNode(isRoot()).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right)
        // 截图供实际排版检查；不以节点存在代替候选可点和宽度断言。
        capture("long-asset-selection")
        find(hasText("保存全部设置")).performNativeStableClick(compose)
        compose.waitUntil(10_000) { calls.any { it.method == "PATCH" } }
        val body = JSONObject(calls.single { it.method == "PATCH" }.body.clone().readUtf8())
        assertEquals("/api/site-settings/assets/12/file", body.getString("logoPath"))
    }

    /** 名称、颜色和排序先在弹窗中校验，非法输入不能发出分类创建请求。 */
    @Test fun taxonomyValidationStaysInTheDialogWithoutWriting() {
        // 三种错误依次修正，错误提示必须在当前 Dialog 中而非页面背后。
        show(); find(hasText("新增分类")).performClick()
        saveDialog(); dialogText("名称不能为空。").assertIsDisplayed()
        fill("名称", " Valid category "); fill("颜色（#RRGGBB）", "invalid")
        saveDialog(); dialogText("颜色必须为 #RRGGBB 格式。").assertIsDisplayed()
        fill("颜色（#RRGGBB）", "#123abc"); fill("排序", "10000")
        saveDialog(); dialogText("排序必须在 0 到 9999 之间。").assertIsDisplayed()
        assertFalse(calls.any { it.method == "POST" })
        // 合法边界一次提交，服务器得到修剪后的名称与保留的颜色值。
        fill("排序", "9999"); saveDialog(); waitDialogClosed()
        val body = JSONObject(calls.single { it.method == "POST" }.body.clone().readUtf8())
        assertEquals("Valid category", body.getString("name"))
        assertEquals("#123ABC", body.getString("color"))
        assertEquals(9999, body.getInt("sortOrder"))
    }

    /** 分类和标签的保存失败均保留输入，只有用户明确重试后才发送第二次写请求。 */
    @Test fun categoryAndTagFailuresRetainInputForExplicitRetry() {
        // 每种类型只让第一次创建失败，不修改真实服务或采用自动重试。
        val attempts = AtomicInteger()
        intercept = { request -> if (request.method == "POST" && attempts.incrementAndGet() % 2 == 1) failure("Taxonomy temporarily unavailable") else null }
        show()
        listOf("分类" to "category", "标签" to "tag").forEach { (label, kind) ->
            find(hasText("新增$label")).performClick()
            fill("名称", "Retained $kind"); saveDialog()
            dialogText("Taxonomy temporarily unavailable").assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasText("名称") and hasAnyAncestor(isDialog())).assertTextContains("Retained $kind")
            saveDialog(); waitDialogClosed()
            find(hasText("Retained $kind")).assertIsDisplayed()
        }
        // 每类一次失败、一次成功，共四次请求且仅产生两条记录。
        assertEquals(4, calls.count { it.method == "POST" })
        assertEquals(setOf("category", "tag"), taxonomies.map { it.getString("kind") }.toSet())
    }

    /** 保存未返回时禁止关闭或重复提交，结果成功后才能移除弹窗。 */
    @Test fun pendingTaxonomySubmissionDisablesCancellationAndDuplicates() {
        // 闩锁确保服务真的处于等待中，而不是依赖很短的请求时机。
        val release = CountDownLatch(1)
        intercept = { request -> if (request.method == "POST") { release.await(8, TimeUnit.SECONDS); null } else null }
        try {
            show(); find(hasText("新增分类")).performClick(); fill("名称", "Pending category"); saveDialog()
            compose.waitUntil(5_000) { calls.any { it.method == "POST" } }
            compose.onNode(hasText("保存中…") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
            compose.onNode(hasText("取消") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
            assertEquals(1, calls.count { it.method == "POST" })
        } finally {
            // 即使断言失败也释放网络，不让测试清理阶段被夹具阻塞。
            release.countDown()
        }
        waitDialogClosed()
    }

    /** 相同令牌权限降级时关闭管理员草稿，重新授权也不能恢复旧输入。 */
    @Test fun roleChangesDisposeTaxonomyDraftsEvenWithTheSameToken() {
        // 使用同一令牌隔离权限变化，不能仅依赖 token 变化清除状态。
        val account = mutableStateOf<NativeSession?>(admin)
        host.setContent { HlovetTheme { NativeAdminSettingsScreen(account.value, {}) } }
        waitLoaded(); find(hasText("新增分类")).performClick(); fill("名称", "Discard this draft")
        val before = calls.size
        compose.runOnIdle { account.value = admin.copy(isSuperAdmin = false) }
        waitText("站点设置仅超级管理员可访问。")
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(before, calls.size)
        compose.runOnIdle { account.value = admin }
        waitLoaded(); find(hasText("新增分类")).performClick()
        compose.onNodeWithText("Discard this draft").assertDoesNotExist()
        assertFalse(calls.any { it.method != "GET" })
    }

    /** 旧身份的迟到配置响应不能填充新身份页面，取消不显示为业务错误。 */
    @Test fun lateSettingsFromPreviousAccountAreDiscarded() {
        // 只拦截旧令牌的设置请求，新身份仍使用正常 HTTP 加载。
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        intercept = { request ->
            if (path(request) == "/site-settings" && request.getHeader("Authorization") == "Bearer ${admin.accessToken}") {
                release.await(8, TimeUnit.SECONDS)
                returned.countDown()
                json(settings().put("siteName", "Old account setting"))
            } else null
        }
        val account = mutableStateOf<NativeSession?>(admin)
        try {
            host.setContent { HlovetTheme { NativeAdminSettingsScreen(account.value, {}) } }
            compose.waitUntil(5_000) { calls.any { path(it) == "/site-settings" } }
            compose.runOnIdle { account.value = admin.copy(userId = 2, accessToken = "fixture-settings-new") }
            waitLoaded()
        } finally { release.countDown() }
        // 等旧请求确实返回后检查当前页面，不把“响应还没来”当作隔离成功。
        assertTrue(returned.await(3, TimeUnit.SECONDS))
        compose.waitForIdle()
        compose.onNodeWithText("Old account setting").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("站点名称")).assertTextContains("Fixture site")
    }

    /** 角色读取失败时禁用选择而非退回手填代码，刷新成功后重新提供服务端候选。 */
    @Test fun failedRoleLoadingCanRecoverWithoutFreeFormCodes() {
        // 设置资源本身仍正常，角色故障不能让其它配置消失。
        intercept = { request -> if (path(request) == "/roles") failure("Role list unavailable") else null }
        show(); find(hasTestTag("site-default-role")).assertIsNotEnabled()
        find(hasText("角色列表读取失败，请刷新重试。")).assertIsDisplayed()
        intercept = { null }
        compose.onNodeWithTag("admin-site-settings-list").performScrollToIndex(0)
        compose.onNodeWithContentDescription("刷新").performClick()
        compose.waitUntil(10_000) { calls.count { path(it) == "/roles" } == 2 }
        find(hasTestTag("site-default-role")).assertIsEnabled().performClick()
        compose.onNodeWithText("Writer role").assertIsDisplayed()
    }

    /** 不完整的成功响应不能关闭表单或显示保存成功，用户可以保留输入后刷新确认。 */
    @Test fun invalidTaxonomySuccessIsNotTreatedAsSaved() {
        // HTTP 200 不等于契约成功，必须有有效资源 ID 和匹配的种类。
        intercept = { request -> if (request.method == "POST") json(JSONObject().put("kind", "tag")) else null }
        show(); find(hasText("新增分类")).performClick(); fill("名称", "Unconfirmed category"); saveDialog()
        dialogText("服务器未返回有效的分类或标签，请刷新后确认。").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("名称") and hasAnyAncestor(isDialog())).assertTextContains("Unconfirmed category")
        assertEquals(1, calls.count { it.method == "POST" })
    }

    /** 挂载 account 的真实设置页面，再等待名称输入框完成读取。 */
    private fun show(account: NativeSession = admin) {
        // 夹具仅替代 HTTP，仍运行实际 Compose 页面和请求解析。
        host.setContent { HlovetTheme { NativeAdminSettingsScreen(account, {}) } }
        waitLoaded()
    }

    /** 等待首屏身份字段，避免对仍在加载的页面进行菜单或表单操作。 */
    private fun waitLoaded() {
        // 名称输入框只能在配置加载后出现。
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText("站点名称")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 为 matcher 滚动到所属懒加载项和具体节点，返回可真实触摸的控件。 */
    private fun find(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // 先使懒加载项进入组合，再滚动到较高卡片内的子控件。
        compose.onNodeWithTag("admin-site-settings-list").performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo().assertIsDisplayed()
    }

    /** 将当前弹窗中 label 字段替换为 value，不直接修改业务状态。 */
    private fun fill(label: String, value: String) {
        // 编辑语义保留输入框自己的事件处理和校验逻辑。
        compose.onNode(hasSetTextAction() and hasText(label) and hasAnyAncestor(isDialog())).performScrollTo().performTextReplacement(value)
    }

    /** 对当前弹窗保存按钮执行一次位置稳定后的真实点击。 */
    private fun saveDialog() {
        // 不自动重试，提交数量由每个业务场景独立断言。
        compose.onNode(hasText("保存") and hasClickAction() and hasAnyAncestor(isDialog())).performNativeStableClick(compose)
    }

    /** 等待当前表单因服务端成功关闭，失败反馈不能通过手动卸载界面掩盖。 */
    private fun waitDialogClosed() {
        // 当前测试只打开一个表单，不能把另一个弹窗当成完成状态。
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    /** 等待并返回当前弹窗内的 text 反馈。 */
    private fun dialogText(text: String): SemanticsNodeInteraction {
        // 限定 Dialog 祖先，防止页面背景中的同名错误使断言误通过。
        val matcher = hasText(text) and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher).performScrollTo()
    }

    /** 等待页面出现 text，用于登录、权限和保存反馈。 */
    private fun waitText(text: String) {
        // 不推进未来时间或用固定延迟替代业务条件。
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 返回 request 的路由路径；查询参数不参与本夹具的资源身份。 */
    private fun path(request: RecordedRequest): String {
        // 不返回请求认证头或正文，诊断只需方法和路由。
        return request.requestUrl!!.encodedPath
    }

    /** 构造第 index 个虚构资源的长名称，用于验证截断与列表滚动。 */
    private fun assetName(index: Int): String {
        // 序号作为测试资源稳定标识，不使用真实用户文件名。
        return "Uploaded logo $index with a deliberately long filename.png"
    }

    /** 构造当前设置响应，按分类种类分组并附加共享主题与通知结构。 */
    private fun settings(): JSONObject {
        // 每次返回独立对象，避免响应序列化与另一个请求共享可变 JSON。
        return JSONObject(saved.toString()).put("defaultTheme", JSONObject()).put("notifications", JSONObject())
            .put("taxonomies", JSONObject().put("categories", JSONArray(taxonomies.filter { it.optString("kind") == "category" }))
                .put("tags", JSONArray(taxonomies.filter { it.optString("kind") == "tag" })))
    }

    /** 为 request 返回明确的 HTTP 契约，未声明路由直接拒绝。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 只读路由使用内存数据，资源名和角色名都不来自真实账号。
        val path = path(request)
        if (request.method == "GET") return when (path) {
            "/site-settings" -> json(settings())
            "/site-settings/assets" -> json(JSONArray((1..12).map { index -> JSONObject().put("id", index).put("kind", "logo").put("originalName", assetName(index)).put("url", if (index == 12) "/api/site-settings/assets/$index/file" else "/site-settings/assets/$index/file") }))
            "/backgrounds" -> json(JSONArray())
            "/roles" -> json(JSONArray("""[{"code":"reader","name":"Reader role","level":1},{"code":"writer","name":"Writer role","level":2}]"""))
            else -> failure("Unexpected fixture GET $path")
        }
        // 写操作只落入当前测试的内存；返回完整可回读实体，不伪造成功提示。
        val body = request.body.clone().readUtf8().takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
        return when {
            path == "/site-settings" && request.method == "PATCH" -> {
                saved = body
                json(settings())
            }
            path == "/site-settings/taxonomies" && request.method == "POST" -> {
                val created = body.put("id", nextId.getAndIncrement())
                taxonomies.add(created)
                json(created)
            }
            else -> failure("Unexpected fixture write $path")
        }
    }

    /** 将 value 序列化为 UTF-8 JSON 成功响应。 */
    private fun json(value: Any): MockResponse {
        // 服务端响应走实际网络解析，不将数据直接注入 Compose。
        return MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(value.toString())
    }

    /** 为 message 构造可在界面显示的受控失败响应。 */
    private fun failure(message: String): MockResponse {
        // 503 用于网络错误恢复，不模拟权限成功。
        return json(JSONObject().put("message", message)).setResponseCode(503)
    }

    /** 保存 name 对应的虚构页面截图，文件仅留在应用私有测试目录。 */
    private fun capture(name: String) {
        // 不导出请求、认证头或设备保存的真实会话。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "native-admin-settings-evidence").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
