package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName

/** 在真实 MainActivity 中使用设备内 HTTP 夹具验证背景同步和页面留存，不代表真实数据库或登录验收。 */
@RunWith(AndroidJUnit4::class)
class NativeSiteAppearanceDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @get:Rule(order = 2) val testName = TestName()
    /** 返回当前运行器，所有截图和主线程操作都属于本轮设备测试。 */
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    /** 返回被测应用上下文，不使用运行器自身的文件或会话目录。 */
    private val context get() = instrumentation.targetContext
    private lateinit var server: MockWebServer
    private var activity: ActivityScenario<MainActivity>? = null
    private var previousSession: NativeSession? = null
    private var previousLocale = "zh-CN"
    private var previousStoredLocale: String? = null
    private var initialized = false
    private val calls = CopyOnWriteArrayList<Pair<String, String>>()
    private val prefix = "/images/site-appearance-${UUID.randomUUID()}"
    private val originalUrl = "$prefix-original.png"
    private val changedUrl = "$prefix-changed.png"
    private val personal = PreviewAppearance("custom", "#168F53", "#17211B", "#42524A", "#FFFFFF", 50, 0, "#FFFFFF", 0)
    @Volatile private var active = false
    @Volatile private var deleted = false
    @Volatile private var settings = JSONObject()
    /** 对 request 注入有界等待或故障；null 表示继续正常夹具响应。 */
    @Volatile private var intercept: (RecordedRequest) -> MockResponse? = { null }
    private val releasePending = CountDownLatch(1)

    /** 仅使用精确隔离来源，保存原会话并准备内存配置；不覆盖正式或本地真实 API 的身份。 */
    @Before fun start() {
        // 新建虚构超级管理员只用于宿主页面分发，凭据不会发送到任何外部服务器。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:39871")
        previousSession = NativeSessionStore.load(context)
        previousLocale = NativeI18n.locale
        previousStoredLocale = context.getSharedPreferences("native-i18n", android.content.Context.MODE_PRIVATE).getString("locale", null)
        initialized = true
        val fixture = NativeSession(accessToken = "isolated-site-appearance", refreshToken = "", username = "site_appearance_fixture",
            nickname = "Appearance fixture", email = "fixture@example.invalid", appearance = personal,
            userId = 900026, isSuperAdmin = true, isAdministrator = true, locale = "zh-CN")
        check(NativeSessionStore.save(context, fixture))
        instrumentation.runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        settings = JSONObject().put("siteName", "Original fixture site").put("defaultBackgroundUrl", originalUrl)
            .put("defaultTheme", JSONObject().put("themeId", "cloud-blue"))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 只记录 request 的方法与路径；认证头和请求正文不写入证据。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 故障注入仍通过实际网络，不直接赋值宿主状态或调用页面回调。
                calls.add(request.method.orEmpty() to path(request))
                return intercept(request) ?: respond(request)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 39871)
    }

    /** 释放本轮 Activity/网络并恢复精确来源的原会话及语言，保留所有截图和缓存证据。 */
    @After fun stop() {
        // 先解除有界等待，再取消页面请求，防止夹具线程阻塞下一项测试。
        releasePending.countDown()
        if (initialized && activity != null) {
            // 取证失败也必须进入下方身份恢复，不能为了截图留下夹具账号。
            runCatching {
                capture("last-${testName.methodName}")
                // 结束前保留方法/路径及可见树，定位未提交或加载中状态；不保存请求头和正文。
                val directory = File(context.filesDir, "native-site-appearance-evidence").apply { mkdirs() }
                File(directory, "calls-${testName.methodName}.json").writeText(JSONArray(calls.map { JSONObject().put("method", it.first).put("path", it.second) }).toString(2))
                File(directory, "tree-${testName.methodName}.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            }
        }
        try { activity?.close() } finally {
            try { if (::server.isInitialized) server.shutdown() } finally {
                if (initialized) {
                    previousSession?.let { check(NativeSessionStore.save(context, it)) } ?: NativeSessionStore.clear(context)
                    // 宿主会保存账号语言，因此同时恢复持久值（包括原先未设置的状态）和运行时语言。
                    val edit = context.getSharedPreferences("native-i18n", android.content.Context.MODE_PRIVATE).edit()
                    if (previousStoredLocale == null) edit.remove("locale") else edit.putString("locale", previousStoredLocale)
                    check(edit.commit())
                    instrumentation.runOnMainSync { NativeI18n.applyLocale(previousLocale) }
                }
            }
        }
    }

    /** 启用/删除活动背景立即更新真实宿主，保持未保存输入、列表身份和个人主题。 */
    @Test fun backgroundChangesKeepTheEditorMountedAndPreservePersonalTheme() {
        // 当前首页背景为红色；未保存的名称不能因宿主背景刷新被服务端旧值覆盖。
        openSettings()
        field("站点名称").performTextReplacement("Unsaved appearance draft")
        val originalList = list().fetchSemanticsNode().id
        val contentReads = contentReadCount()
        val previousTop = useChangedBackground()
        waitCachedBackground(changedUrl)
        assertBackgroundColor(blue = true)
        assertEquals("背景切换和新增成功提示不能移动当前卡片", previousTop,
            compose.onNodeWithText("Changed fixture background").fetchSemanticsNode().boundsInRoot.top, 2f)
        assertEquals(originalList, list().fetchSemanticsNode().id)
        assertEquals(contentReads, contentReadCount())
        assertEquals(Color(0xFF168F53), compose.runOnIdle { HlovetUi.accent })
        field("站点名称").assertTextContains("Unsaved appearance draft")
        capture("active-background-editor-retained")
        // 删除使用真实确认弹窗；服务端仍配置原背景时应回退原背景，不重新加载内容。
        find(hasContentDescription("删除")).performNativeStableClick(compose)
        compose.onNode(hasText("删除") and hasClickAction() and hasAnyAncestor(isDialog())).performNativeStableClick(compose)
        waitCachedBackground(originalUrl)
        assertBackgroundColor(blue = false)
        assertEquals(originalList, list().fetchSemanticsNode().id)
        assertEquals(contentReads, contentReadCount())
        field("站点名称").assertTextContains("Unsaved appearance draft")
        capture("deleted-background-fallback")
    }

    /** 第二阶段重置失败仍同步已清除的活动背景，保留草稿和失败反馈。 */
    @Test fun partialResetSynchronizesTheConfirmedBackgroundWithoutLosingDraft() {
        // 第一步成功、第二步失败与真实两接口非事务边界一致。
        active = true
        intercept = { request -> if (path(request) == "/site-settings/reset") json(JSONObject().put("message", "Reset intentionally unavailable"), 503) else null }
        openSettings(initialChanged = true)
        field("站点名称").performTextReplacement("Keep after reset failure")
        val originalList = list().fetchSemanticsNode().id
        list().performScrollToIndex(0)
        compose.onNodeWithContentDescription("恢复默认").performNativeStableClick(compose)
        compose.onNode(hasText("恢复默认") and hasClickAction() and hasAnyAncestor(isDialog())).performNativeStableClick(compose)
        waitCachedBackground(originalUrl)
        assertBackgroundColor(blue = false)
        find(hasText("Reset intentionally unavailable")).assertIsDisplayed()
        field("站点名称").assertTextContains("Keep after reset failure")
        assertEquals(originalList, list().fetchSemanticsNode().id)
        assertEquals(1, calls.count { it.first == "POST" && it.second == "/site-settings/reset" })
        capture("partial-reset-background-synchronized")
    }

    /** 完整恢复且无默认背景时清除旧图片，仍停留同一设置页并保留个人配色。 */
    @Test fun completedResetWithNoConfiguredBackgroundClearsTheHostImage() {
        // 空背景是有效结果，不允许空值回退到刷新前的图片 URL。
        active = true
        openSettings(initialChanged = true)
        val originalList = list().fetchSemanticsNode().id
        list().performScrollToIndex(0)
        compose.onNodeWithContentDescription("恢复默认").performNativeStableClick(compose)
        compose.onNode(hasText("恢复默认") and hasClickAction() and hasAnyAncestor(isDialog())).performNativeStableClick(compose)
        compose.waitUntil(20_000) {
            NativeOfflineCache.loadPreview(context, "user:900026")?.let { it.backgroundUrl == null } == true
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-site-background").fetchSemanticsNodes().isEmpty() }
        field("站点名称").assertTextContains("Restored fixture site")
        assertEquals(originalList, list().fetchSemanticsNode().id)
        assertEquals(Color(0xFF168F53), compose.runOnIdle { HlovetUi.accent })
        capture("completed-reset-cleared-image")
    }

    /** 慢的旧读取不能覆盖随后已启用的新背景，外观同步不增加首页内容读取。 */
    @Test fun lateAppearanceReadCannotRestoreAnOlderBackground() {
        // 保存后公共设置读取暂挂；仍可编辑并启用背景，由更新请求替换旧刷新。
        openSettings()
        val pending = CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger()
        intercept = { request ->
            if (path(request) == "/site-settings/public" && count.incrementAndGet() == 1) {
                val older = JSONObject(settings.toString())
                pending.countDown()
                releasePending.await(15, TimeUnit.SECONDS)
                json(older)
            } else null
        }
        val contentReads = contentReadCount()
        find(hasTestTag("site-default-theme")).performClick()
        compose.onNode(hasText("夜紫灰蓝") and hasAnyAncestor(isPopup())).performClick()
        field("站点名称").performTextReplacement("Saved while appearance waits")
        // 编辑会异步展开真实 IME，先等待窗口可用高度稳定，再滚动到末尾保存入口。
        waitForKeyboardGeometry()
        val saveButton = find(hasText("保存全部设置") and hasClickAction())
        assertAboveKeyboard(saveButton)
        saveButton.performNativeStableClick(compose)
        // 让 Compose 测试时钟继续驱动重组和 LaunchedEffect，不能用阻塞锁等待尚未启动的刷新。
        compose.waitUntil(10_000) { pending.count == 0L }
        useChangedBackground()
        waitCachedBackground(changedUrl)
        releasePending.countDown()
        compose.waitForIdle()
        assertBackgroundColor(blue = true)
        assertEquals(Color(0xFF168F53), compose.runOnIdle { HlovetUi.accent })
        assertEquals("night-purple", NativeOfflineCache.loadPreview(context, "user:900026")?.appearance?.themeId)
        field("站点名称").assertTextContains("Saved while appearance waits")
        assertEquals(contentReads, contentReadCount())
        capture("late-response-kept-latest-background")
    }

    /** 活动背景读取失败保留已显示背景；用户刷新页面可重试而不必重复写入。 */
    @Test fun failedAppearanceReadKeepsThePreviousImageAndRefreshCanRetry() {
        // 背景写入成功与宿主刷新失败分开处理，读取失败不能用默认背景伪装成功。
        openSettings()
        intercept = { request -> if (path(request) == "/backgrounds/active") json(JSONObject().put("message", "Appearance read unavailable"), 503) else null }
        useChangedBackground()
        compose.waitUntil(10_000) { calls.count { it.second == "/backgrounds/active" } >= 3 }
        compose.waitForIdle()
        assertBackgroundColor(blue = false)
        // 解除故障后只点击刷新，不重复启用写入。
        intercept = { null }
        list().performScrollToIndex(0)
        compose.onNodeWithContentDescription("刷新").performNativeStableClick(compose)
        waitCachedBackground(changedUrl)
        assertBackgroundColor(blue = true)
        assertEquals(1, calls.count { it.first == "PATCH" && it.second.endsWith("/activate") })
        capture("appearance-read-explicit-refresh")
    }

    /** 用实际深链打开设置并等待正确的初始背景；initialChanged 指定夹具初始活动状态。 */
    private fun openSettings(initialChanged: Boolean = false) {
        // 生产 MainActivity 承担会话恢复、路由及背景读取，不直接挂载设置组件。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + "/admin/settings")))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("admin-site-settings-list").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        // 列表入口会先显示加载提示，必须等服务端配置形成可编辑字段，不能将入口出现当作读取完成。
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("站点名称") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        field("站点名称").assertTextContains("Original fixture site")
        waitCachedBackground(if (initialChanged) changedUrl else originalUrl)
        compose.waitUntil(10_000) { calls.count { it.second == "/backgrounds/active" } >= 2 }
        // 等首次账号首页三个内容接口均已读取，后续次数断言才只衡量背景刷新带来的额外请求。
        compose.waitUntil(10_000) { contentReadCount() >= 3 }
        assertBackgroundColor(initialChanged)
    }

    /** 物理点击启用夹具背景；返回点击前标题的屏幕纵坐标，供滚动锚点核对。 */
    private fun useChangedBackground(): Float {
        // 不调用业务方法；只有一条背景候选，避免用重复文字碰巧选中其它行。
        val button = find(hasText("使用") and hasClickAction())
        val previousTop = compose.onNodeWithText("Changed fixture background").fetchSemanticsNode().boundsInRoot.top
        button.performNativeStableClick(compose)
        compose.waitUntil(10_000) { calls.any { it.first == "PATCH" && it.second == "/backgrounds/2/activate" } }
        return previousTop
    }

    /** 返回实际设置列表节点，供滚动及组合身份核对。 */
    private fun list(): SemanticsNodeInteraction {
        // 稳定标签标识宿主内的业务列表，不依赖 translated 文案或树层级。
        return compose.onNodeWithTag("admin-site-settings-list")
    }

    /** 将 matcher 对应项滚入懒列表并返回唯一节点；字段/按钮可能初始尚未组合。 */
    private fun find(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // 外层列表负责纵向滚动，确保目标不是仅存在于不可见卡片中。
        list().performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    /** 返回 label 的可编辑字段，不匹配同名说明文案。 */
    private fun field(label: String): SemanticsNodeInteraction {
        // 只通过真实文本编辑事件修改草稿。
        return find(hasText(label) and hasSetTextAction())
    }

    /** 等待缓存中的有效背景与 path 一致，确认刷新经过宿主且已持久化。 */
    private fun waitCachedBackground(path: String) {
        // 夹具专用账号来源避免读取真实账号或公共站点缓存。
        compose.waitUntil(20_000) { NativeOfflineCache.loadPreview(context, "user:900026")?.backgroundUrl == BuildConfig.PORTAL_BASE_URL + path }
        compose.waitForIdle()
    }

    /** 用实际画面边缘像素验证 blue 对应背景已渲染，而非只检查请求或缓存状态。 */
    private fun assertBackgroundColor(blue: Boolean) {
        // 页面边缘不落在玻璃卡片内；白色遮罩会变浅，但主色通道仍应有明显差异。
        compose.waitUntil(15_000) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            try {
                val pixel = bitmap.getPixel(3, bitmap.height / 2)
                if (blue) android.graphics.Color.blue(pixel) - android.graphics.Color.red(pixel) > 80
                else android.graphics.Color.red(pixel) - android.graphics.Color.blue(pixel) > 80
            } finally { bitmap.recycle() }
        }
    }

    /** 返回首页内容读取次数；后台外观同步不得重新请求这些列表。 */
    private fun contentReadCount(): Int {
        // 统计目标业务路径，不把外观、用户摘要和其它后台读取混入分页验收。
        return calls.count { it.second == "/articles/visible" || it.second == "/discovery/topics/visible" || it.second == "/discovery/collections/visible" }
    }

    /** 等待真实 IME 的高度和宿主窗口稳定，避免按动画中间帧滚动后丢失懒列表节点。 */
    private fun waitForKeyboardGeometry() {
        // 固定延迟不能证明键盘已展开；连续稳定的窗口尺寸才允许继续测量保存按钮。
        var previous = 0 to 0
        var stableSince = 0L
        compose.waitUntil(10_000) {
            var current = 0 to 0
            requireNotNull(activity).onActivity { host ->
                val view = host.window.decorView
                current = view.height to (ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (current != previous || current.second == 0) stableSince = now
            previous = current
            current.second > 0 && now - stableSince >= 300
        }
    }

    /** 验证 button 完全位于真实软键盘上方，不能仅因 Compose 局部注入可点击就认定用户可操作。 */
    private fun assertAboveKeyboard(button: SemanticsNodeInteraction) {
        // 使用宿主窗口 Insets 和节点屏幕坐标，测试期间不主动隐藏键盘。
        compose.waitUntil(5_000) {
            var visibleBottom = 0
            var keyboardHeight = 0
            requireNotNull(activity).onActivity { current ->
                val view = current.window.decorView
                keyboardHeight = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                visibleBottom = view.height - keyboardHeight
            }
            val node = button.fetchSemanticsNode()
            val bottom = compose.runOnUiThread {
                val coordinates = node.layoutInfo.coordinates
                coordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero).y + coordinates.size.height
            }
            keyboardHeight > 0 && bottom <= visibleBottom
        }
        capture("save-button-above-keyboard")
    }

    /** 为 request 返回内存 API 响应；除页面明确写入外，其余后台接口使用无内容夹具。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 图片是当前测试唯一 URL 的实色 PNG，避免跨轮缓存或真实图像内容影响像素核对。
        val path = path(request)
        if (path == originalUrl || path == changedUrl) {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (path == changedUrl) android.graphics.Color.BLUE else android.graphics.Color.RED)
            val output = ByteArrayOutputStream()
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) } finally { bitmap.recycle() }
            return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(output.toByteArray()))
        }
        if (request.method == "GET") return when (path) {
            "/site-settings", "/site-settings/public" -> json(settings)
            "/site-settings/assets", "/roles" -> json(JSONArray())
            "/backgrounds" -> json(if (deleted) JSONArray() else JSONArray().put(background()))
            "/backgrounds/active" -> json(JSONObject().put("background", if (active) background() else JSONObject.NULL))
            "/discovery/onboarding" -> json(JSONObject().put("completed", true))
            else -> json(JSONObject().put("items", JSONArray()))
        }
        // 写入只改变此测试的内存，不访问数据库或外部系统。
        return when {
            path == "/backgrounds/2/activate" -> { active = true; json(background()) }
            path == "/backgrounds/2" && request.method == "DELETE" -> { active = false; deleted = true; json(JSONObject()) }
            path == "/backgrounds/active/clear" -> { active = false; json(JSONObject()) }
            path == "/site-settings/reset" && request.method == "POST" -> {
                // 默认设置与背景资源独立；这里保留上传条目但清空配置的背景引用。
                settings = JSONObject().put("siteName", "Restored fixture site").put("defaultBackgroundUrl", "")
                    .put("defaultTheme", JSONObject().put("themeId", "cloud-blue"))
                json(settings)
            }
            path == "/site-settings" && request.method == "PATCH" -> {
                val body = JSONObject(request.body.clone().readUtf8())
                settings = JSONObject(settings.toString()).put("siteName", body.getString("siteName"))
                    .put("defaultBackgroundUrl", body.getString("defaultBackgroundUrl"))
                    .put("defaultTheme", JSONObject().put("themeId", body.getString("defaultThemeId")))
                json(settings)
            }
            else -> json(JSONObject())
        }
    }

    /** 返回唯一夹具背景的公开实体，不暴露任何真实上传信息。 */
    private fun background(): JSONObject {
        // 稳定 ID 用于启用/删除，URL 按轮次唯一以隔离媒体缓存。
        return JSONObject().put("id", 2).put("originalName", "Changed fixture background").put("url", changedUrl)
            .put("isActive", active).put("sizeBytes", 100)
    }

    /** 返回 request 不含查询参数的业务路径。 */
    private fun path(request: RecordedRequest): String {
        // 诊断不保留查询信息、正文或凭据。
        return request.path.orEmpty().substringBefore('?')
    }

    /** 将 value 序列化为 status 的 UTF-8 JSON 响应。 */
    private fun json(value: Any, status: Int = 200): MockResponse {
        // 全部交互走产品实际网络解析，不直接向 Compose 注入响应对象。
        return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json; charset=utf-8").setBody(value.toString())
    }

    /** 保存 name 对应的宿主屏幕截图，不记录账号凭据。 */
    private fun capture(name: String) {
        // 留存本轮证据在应用私有目录，继续保留历史轮次文件。
        val directory = File(context.filesDir, "native-site-appearance-evidence").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
