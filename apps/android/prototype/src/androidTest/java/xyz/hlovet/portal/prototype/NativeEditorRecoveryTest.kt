package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 通过真实编辑组件和受控 HTTP 时序验证离线草稿恢复，不访问线上接口或用户偏好。 */
@RunWith(AndroidJUnit4::class)
class NativeEditorRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    private val releases = CopyOnWriteArrayList<CountDownLatch>()
    private val generation = mutableIntStateOf(0)
    private val selectedId = mutableStateOf<Int?>(71)
    private var oldLocale = "zh-CN"
    @Volatile private var offline = false
    @Volatile private var failSave = false
    @Volatile private var saveGate: CountDownLatch? = null
    @Volatile private var savedCallbacks = 0
    private val session = NativeSession(
        accessToken = "fixture-editor-token", refreshToken = "fixture-editor-refresh",
        userId = 871, username = "fixture-editor", nickname = "Fixture editor",
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
    )

    /** 使用精确本地夹具来源和独立偏好，所有写请求都限定在模拟服务器。 */
    @Before
    fun setUp() {
        // 来源保护防止测试令牌或生成正文进入真实服务器。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        val namespace = "editor-recovery-${UUID.randomUUID()}"
        context = object : ContextWrapper(compose.activity) {
            /** 将 name 对应的草稿偏好映射到本条用例命名空间，返回独立存储。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                // 其它组件偏好也隔离，避免更改已安装账号的设置。
                return super.getSharedPreferences("$namespace-$name", mode)
            }
        }
        prefs = context.getSharedPreferences("native-article-drafts", Context.MODE_PRIVATE)
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                /** 根据 request 路径返回文章、配置或受控保存结果，不依赖固定延迟。 */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // 配置读取不影响正文保存，离线状态只影响文章及写入接口。
                    val path = request.path.orEmpty().substringBefore('?')
                    if (path == "/site-settings/public") return json("{}")
                    if (path == "/roles") return json("{\"items\":[]}")
                    if (request.method == "POST" && path.endsWith("/autosave")) {
                        val input = JSONObject(request.body.readUtf8())
                        requests.add(path to input)
                        if (offline) return json("{\"message\":\"Fixture offline\"}", 503)
                        saveGate?.await(12, TimeUnit.SECONDS)
                        if (failSave) return json("{\"message\":\"Fixture save failed\"}", 503)
                        return json(article(input).toString())
                    }
                    if (offline) return json("{\"message\":\"Fixture offline\"}", 503)
                    if (request.method == "GET" && path == "/articles/mine/71") return json(article().toString())
                    return json("{\"message\":\"Unexpected fixture request\"}", 404)
                }
            }
            start(39871)
        }
    }

    /** 释放全部受控请求后卸载测试组合，恢复语言；不删除任何真实会话或草稿。 */
    @After
    fun tearDown() {
        // 先解锁 HTTP 线程，避免组合退出后服务器仍等待测试操作。
        releases.forEach { it.countDown() }
        if (::server.isInitialized) {
            try { host.dispose() }
            finally {
                server.shutdown()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 已有文章联网读取失败时仍提供本地恢复，恢复后保留正文和明确提示。 */
    @Test
    fun existingArticleCanRecoverWhenOnlineLoadFails() {
        // 本地草稿以当前账号和既有文章编号保存，不从 new 键猜测关联。
        offline = true
        writeNativeLocalDraft(context, draftKey(71), input("Offline title", "Offline body"))
        show(71)
        waitText("发现未提交的本地草稿")
        compose.onNodeWithText("恢复", useUnmergedTree = true).performClick()
        field("标题").assertTextContains("Offline title")
        field("正文").performScrollTo().assertTextContains("Offline body")
        assertEquals("Offline body", requireNotNull(readNativeLocalDraft(context, draftKey(71))).input.content)
    }

    /** 首次自动保存回包时新输入不能丢失，本地草稿必须跟随新分配的文章编号。 */
    @Test
    fun firstSaveMovesNewerInputToAssignedArticleId() {
        // 明确等到第一个保存已到达服务器，才继续输入并释放旧回包。
        val first = gate()
        show(null)
        sourceMode()
        fill("标题", "New draft")
        fill("正文", "First body")
        waitRequests(1)
        fill("正文", "Newer body")
        compose.waitUntil(10_000) { readNativeLocalDraft(context, draftKey(null))?.input?.content == "Newer body" }
        val second = gate()
        saveGate = second
        first.countDown()
        compose.waitUntil(10_000) { savedCallbacks >= 1 }
        field("正文").assertTextContains("Newer body")
        compose.waitUntil(10_000) { readNativeLocalDraft(context, draftKey(71))?.input?.content == "Newer body" }
        assertNull(readNativeLocalDraft(context, draftKey(null)))
        waitRequests(2)
        assertEquals("/articles/71/autosave", requests[1].first)
        assertEquals("Newer body", requests[1].second.optString("content"))
        second.countDown()
        compose.waitUntil(10_000) { savedCallbacks == 2 && readNativeLocalDraft(context, draftKey(71)) == null }
    }

    /** 已有文章保存期间继续修改，旧回包只能确认旧版本，不能覆盖或清除新内容。 */
    @Test
    fun editsDuringSaveRemainLocalAndAreSavedAgain() {
        // 受控请求建立真实保存中状态，检查最新本地副本及第二次请求正文。
        show(71)
        val first = gate()
        fill("正文", "Pending body")
        waitRequests(1)
        fill("正文", "Latest body")
        compose.waitUntil(10_000) { readNativeLocalDraft(context, draftKey(71))?.input?.content == "Latest body" }
        val second = gate()
        saveGate = second
        first.countDown()
        waitRequests(2)
        field("正文").assertTextContains("Latest body")
        assertEquals("Latest body", requests[1].second.optString("content"))
        assertEquals("Latest body", requireNotNull(readNativeLocalDraft(context, draftKey(71))).input.content)
        second.countDown()
        compose.waitUntil(10_000) { savedCallbacks == 2 && readNativeLocalDraft(context, draftKey(71)) == null }
    }

    /** 保存失败后最新输入留在本地，组件重建再遇离线时仍可恢复。 */
    @Test
    fun failedSaveSurvivesEditorRecreation() {
        // 失败不伪装保存成功，也不删除按来源账号文章隔离的副本。
        show(71)
        val first = gate()
        failSave = true
        fill("正文", "Failed newest body")
        waitRequests(1)
        first.countDown()
        waitText("Fixture save failed")
        assertEquals(0, savedCallbacks)
        assertEquals("Failed newest body", requireNotNull(readNativeLocalDraft(context, draftKey(71))).input.content)
        offline = true
        compose.runOnIdle { generation.intValue += 1 }
        waitText("发现未提交的本地草稿")
        compose.onNodeWithText("恢复", useUnmergedTree = true).performClick()
        field("正文").performScrollTo().assertTextContains("Failed newest body")
    }

    /** 丢弃离线副本只删除当前键，未读取原文时禁止提交空白覆盖既有文章。 */
    @Test
    fun discardOfflineRecoveryCannotOverwriteUnreadArticle() {
        // 保留另一文章副本，确认丢弃操作没有扩大清理范围。
        offline = true
        writeNativeLocalDraft(context, draftKey(71), input("Discard title", "Discard body"))
        writeNativeLocalDraft(context, draftKey(72), input("Other title", "Other body"))
        show(71)
        waitText("发现未提交的本地草稿")
        compose.onNodeWithText("丢弃", useUnmergedTree = true).performClick()
        assertNull(readNativeLocalDraft(context, draftKey(71)))
        assertNotNull(readNativeLocalDraft(context, draftKey(72)))
        sourceMode()
        fill("标题", "Do not overwrite")
        fill("正文", "Unread replacement")
        Thread.sleep(1800)
        assertEquals(0, requests.size)
        assertNotNull(readNativeLocalDraft(context, draftKey(71)))
    }

    /** 新文章恢复后按原格式继续编辑，成功保存才清除当前恢复副本。 */
    @Test
    fun newDraftRecoveryRetainsInputUntilSaveSucceeds() {
        // 阻塞保存回包，证明恢复动作本身不会清理未提交内容。
        writeNativeLocalDraft(context, draftKey(null), input("Recovered new", "Recovered body"))
        val pending = gate()
        show(null)
        waitText("发现未提交的本地草稿")
        compose.onNodeWithText("恢复", useUnmergedTree = true).performClick()
        field("标题").assertTextContains("Recovered new")
        field("正文").performScrollTo().assertTextContains("Recovered body")
        waitRequests(1)
        assertNotNull(readNativeLocalDraft(context, draftKey(null)))
        pending.countDown()
        compose.waitUntil(10_000) { savedCallbacks == 1 && readNativeLocalDraft(context, draftKey(null)) == null }
        assertNull(readNativeLocalDraft(context, draftKey(71)))
    }

    /** 断网写作列表提供有效副本入口，另一来源、另一账号与伪造编号都不能混入。 */
    @Test
    fun offlineHomeListsOnlyVerifiedCurrentAccountDrafts() {
        // 使用相同偏好文件放置不同身份与伪造条目，验证筛选依据不是标题。
        offline = true
        writeNativeLocalDraft(context, draftKey(71), input("Local existing", "Local body"), userId = session.userId, articleId = 71)
        writeNativeLocalDraft(context, draftKey(null), input("Local new", "New body"))
        writeNativeLocalDraft(context, nativeLocalDraftKey(872, 72), input("Other owner", "Other body"), userId = 872, articleId = 72)
        val otherOrigin = "https://other.example/api"
        writeNativeLocalDraft(context, nativeLocalDraftKey(session.userId, 73, otherOrigin), input("Other server", "Other body"), otherOrigin, session.userId, 73)
        prefs.edit().putString(draftKey(74), prefs.getString(draftKey(71), null)).commit()
        val entries = listNativeLocalDrafts(context, session.userId)
        assertEquals(setOf(null, 71), entries.map { it.articleId }.toSet())
        var openedId: Int? = null
        var newOpened = false
        host.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                HlovetTheme {
                    NativeWriteHomeScreen(PaddingValues(0.dp), session, onLogin = {}, onNewArticle = { newOpened = true }, onOpenDraft = { openedId = it })
                }
            }
        }
        // 加载中的服务器列表不遮挡本地入口，点击仍按真实文章编号打开。
        waitText("Local existing")
        compose.onNodeWithText("Local existing").performScrollTo().performClick()
        assertEquals(71, openedId)
        compose.onNodeWithText("Local new").performScrollTo().performClick()
        assertTrue(newOpened)
        assertTrue(compose.onAllNodes(hasText("Other owner")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("Other server")).fetchSemanticsNodes().isEmpty())
    }

    /** 移动光标不改变正文，不生成未提交副本，也不消耗远程自动保存请求。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun caretMovementDoesNotCreateAnUnsavedDraft() {
        // 超过实际防抖窗口仍未发出请求，证明仅选区变化未被当作写作修改。
        show(71)
        field("正文").performScrollTo().performTextInputSelection(TextRange(3))
        Thread.sleep(1800)
        assertEquals(0, requests.size)
        assertNull(readNativeLocalDraft(context, draftKey(71)))
    }

    /** 安装一次测试宿主，用 generation 控制组件重建，Context 始终指向独立偏好。 */
    private fun show(id: Int?) {
        // 所有文章保存回调只计数，不执行外部页面跳转。
        selectedId.value = id
        host.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                HlovetTheme {
                    key(generation.intValue) {
                        NativeWriteEditorScreen(session, selectedId.value, onBack = {}, onSaved = { savedCallbacks += 1 }, onPublished = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText("标题")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 新建页面切到源码，避免文本输入用例依赖 WebView 的异步编辑通知。 */
    private fun sourceMode() {
        // 源码切换读取实时正文后再挂载原生输入控件。
        compose.onNodeWithText("源码", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText("正文")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 返回 label 对应的可编辑语义节点，排除正文格式等同名展示文本。 */
    private fun field(label: String) =
        // 标签与输入动作一起匹配，排除普通展示文字。
        compose.onNode(hasSetTextAction() and hasText(label))

    /** 把 label 对应输入替换为 value，真实触发编辑回调和本地保存。 */
    private fun fill(label: String, value: String) {
        // 先滚入可视区域，再通过 Compose 编辑动作变更输入。
        field(label).performScrollTo().performTextReplacement(value)
    }

    /** 等待 text 进入语义树，用实际状态而不是固定休眠推进流程。 */
    private fun waitText(text: String) {
        // 错误反馈和恢复入口必须确实由当前组合提供。
        compose.waitUntil(12_000) { compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 等待 count 次真实保存请求，调用方随后才修改输入或释放回包。 */
    private fun waitRequests(count: Int) {
        // 服务器已读取请求体才计数，避免把预期自动保存误当完成。
        compose.waitUntil(12_000) { requests.size >= count }
    }

    /** 创建并登记一个请求闸门，返回供用例显式释放的 latch。 */
    private fun gate(): CountDownLatch {
        // 退出时统一释放，失败用例不会把服务线程永久锁住。
        return CountDownLatch(1).also { releases.add(it); saveGate = it }
    }

    /** 按当前测试账号与 id 生成来源绑定键，新文章传 null。 */
    private fun draftKey(id: Int?): String {
        // 与生产编辑页复用同一键算法，防止测试绕过编号迁移。
        return nativeLocalDraftKey(session.userId, id)
    }

    /** 生成 title、body 对应的合法 Markdown 草稿，其余字段使用当前业务默认值。 */
    private fun input(title: String, body: String): NativeDraftInput {
        // Markdown 可直接通过原生输入控件验证字符级恢复。
        return NativeDraftInput(title, "", body, "markdown", "随笔", "", "", "public", emptyList())
    }

    /** 将 input 转成编号 71 的保存回包；缺省 input 返回服务器旧正文。 */
    private fun article(input: JSONObject? = null): JSONObject {
        // 回包保留提交时快照，刻意不包含请求后继续输入的字符。
        return JSONObject().put("id", 71).put("title", input?.optString("title") ?: "Server title")
            .put("summary", input?.optString("summary") ?: "").put("content", input?.optString("content") ?: "Server body")
            .put("contentFormat", input?.optString("contentFormat") ?: "markdown").put("category", "随笔")
            .put("tags", "").put("titleColor", "").put("visibility", "public").put("status", "draft")
            .put("slug", "fixture-editor-71").put("updatedAt", "2026-10-14T01:00:00.000Z")
    }

    /** 返回 body 和 status 对应 JSON 响应，服务端错误仍遵循实际 message 协议。 */
    private fun json(body: String, status: Int = 200): MockResponse {
        // 显式内容类型使 API 按真实解析路径处理成功或失败回包。
        return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    }
}
