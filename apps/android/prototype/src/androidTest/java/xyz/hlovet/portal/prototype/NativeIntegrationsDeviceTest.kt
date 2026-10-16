package xyz.hlovet.portal.prototype

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 在真实 Compose 弹窗配合隔离 HTTP 服务验证集成表单，不冒充数据库或外部服务验收。 */
@RunWith(AndroidJUnit4::class)
class NativeIntegrationsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val calls = CopyOnWriteArrayList<Pair<String, String>>()
    private var oldLocale = "zh-CN"
    @Volatile private var override: (RecordedRequest) -> MockResponse? = { null }
    private val session = NativeSession("fixture-token", "fixture-refresh", 34, "fixture-reader", "Fixture reader",
        PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46))
    private val account = mutableStateOf<NativeSession?>(session)
    private val fixtureToken = JSONObject("""{"id":71,"name":"Fixture token","tokenPrefix":"lvt_fixture","scopes":["read_articles"],"createdAt":"2026-10-15T00:00:00Z"}""")
    private val fixtureChannel = JSONObject("""{"id":81,"kind":"webhook","endpoint":"https://example.com/fixture","enabled":false,"verified":false,"failureCount":0,"preferences":{},"createdAt":"2026-10-15T00:00:00Z"}""")

    /** 只在专用预览 origin 启动隔离服务，禁止向真实 API 发送替身凭据。 */
    @Before fun start() {
        // 切换中文便于定位实际控件，退出时还原原有语言。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 为 request 记录路线而非凭据，返回本测试明确声明的响应。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 不把 Authorization 或完整令牌写入测试日志。
                calls.add(request.method.orEmpty() to request.path.orEmpty())
                return override(request) ?: response(request)
            }
        }
        server.start(39871)
    }

    /** 先销毁页面再停止服务，保留应用原有会话和本地数据。 */
    @After fun stop() {
        // 跳过未初始化的服务，避免环境不匹配时掩盖假设跳过结果。
        if (!::server.isInitialized) return
        try { host.dispose() } finally {
            server.shutdown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
        }
    }

    /** 通道读取失败不应隐藏令牌，刷新后可恢复错误区域。 */
    @Test fun channelFailureKeepsTokensAndRefreshRecovers() {
        // 首次仅破坏通道接口，验证两类读取不会连带失败。
        val attempts = AtomicInteger()
        override = { if (it.path == "/integrations/external-channels" && attempts.incrementAndGet() == 1) failure("Channel read failed") else null }
        show()
        waitText("Fixture token")
        compose.onNodeWithText("Channel read failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("刷新").performScrollTo().performClick()
        waitText("https://example.com/fixture")
        compose.onNodeWithText("Channel read failed").assertDoesNotExist()
    }

    /** 令牌读取失败仍应提供通道操作，不能误报空令牌列表。 */
    @Test fun tokenFailureKeepsChannels() {
        // 错误与暂无数据必须区分，避免用户误以为原有凭据丢失。
        override = { if (it.path == "/integrations/tokens") failure("Token read failed") else null }
        show()
        waitText("Token read failed")
        compose.onNodeWithText("https://example.com/fixture").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("暂无只读令牌。").assertDoesNotExist()
    }

    /** 过期和无效日期在弹窗内提示，合法日期允许重试创建。 */
    @Test fun tokenExpiryValidationAndCreateRetryStayInDialog() {
        // 第一次合法提交模拟服务器失败，草稿名称不能被清空。
        val attempts = AtomicInteger()
        override = { if (it.method == "POST" && it.path == "/integrations/tokens" && attempts.incrementAndGet() == 1) failure("Create failed") else null }
        show(); openToken()
        fill("名称", "Retained draft")
        fill("过期时间（可选）", "not-a-date")
        clickDialog("创建")
        dialogText("请输入晚于当前时间的有效日期，例如 2026-12-31T23:59:59Z。").assertIsDisplayed()
        assertEquals(0, count("POST", "/integrations/tokens"))
        fill("过期时间（可选）", "2000-01-01T00:00:00Z")
        clickDialog("创建")
        assertEquals(0, count("POST", "/integrations/tokens"))
        fill("过期时间（可选）", "")
        clickDialog("创建"); waitText("Create failed")
        dialogText("Create failed").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Retained draft")).assertExists()
        clickDialog("创建"); waitText("令牌已创建")
        clickDialog("我已保存")
        waitText("Retained draft")
        assertEquals(2, count("POST", "/integrations/tokens"))
    }

    /** 创建成功不依赖另一次列表读取；关闭成功弹窗后不能再次查看明文。 */
    @Test fun createdTokenIsOneTimeAndDoesNotDependOnReadback() {
        // 创建后主动让读取失败，现有确认响应仍须保留。
        show(); openToken(); fill("名称", "One-time token")
        clickDialog("创建"); waitText("令牌已创建")
        assertEquals(1, count("GET", "/integrations/tokens"))
        clickDialog("我已保存"); waitText("One-time token")
        compose.onNodeWithText("lvt_one_time_fixture_only").assertDoesNotExist()
        override = { if (it.path == "/integrations/tokens") failure("Reload unavailable") else null }
        compose.onNodeWithContentDescription("刷新").performScrollTo().performClick()
        waitText("Reload unavailable")
        compose.onNodeWithText("One-time token").assertExists()
    }

    /** 撤销失败在确认弹窗显示，重试成功立即禁用该行。 */
    @Test fun revokeFailureRetainsDialogAndSuccessDisablesAction() {
        // 服务端拒绝时不提前将凭据显示为已撤销。
        val attempts = AtomicInteger()
        override = { if (it.method == "DELETE" && it.path == "/integrations/tokens/71" && attempts.incrementAndGet() == 1) failure("Revoke failed") else null }
        show(); waitText("Fixture token")
        compose.onNodeWithContentDescription("撤销令牌").performScrollTo().performClick()
        clickDialog("撤销"); waitText("Revoke failed")
        dialogText("Revoke failed").assertIsDisplayed()
        clickDialog("撤销"); waitText("只读令牌已撤销。")
        compose.onNodeWithContentDescription("撤销令牌").assertIsNotEnabled()
        assertEquals(2, count("DELETE", "/integrations/tokens/71"))
    }

    /** 通道 HTTPS 校验和投递失败在原弹窗反馈且保留草稿。 */
    @Test fun channelValidationAndSendFailureAreVisible() {
        // 不向任何外部地址投递；隔离服务只模拟创建失败响应。
        override = { if (it.method == "POST" && it.path == "/integrations/external-channels") failure("Delivery failed") else null }
        show(); waitText("Fixture token")
        compose.onNodeWithContentDescription("添加通道").performScrollTo().performClick()
        fill("HTTPS URL", "http://example.com/test")
        clickDialog("发送验证")
        dialogText("请输入有效的 HTTPS 通知地址。").assertIsDisplayed()
        assertEquals(0, count("POST", "/integrations/external-channels"))
        fill("HTTPS URL", "https://example.com/test")
        clickDialog("发送验证"); waitText("Delivery failed")
        dialogText("Delivery failed").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("https://example.com/test")).assertExists()
    }

    /** 新通道输入满六位自动验证，失败清空并能重新输入。 */
    @Test fun sixDigitChannelVerificationRetriesAndUpdatesRow() {
        // 用实际六格输入组件验证自动提交，而非直接调用页面函数。
        val attempts = AtomicInteger()
        override = { if (it.path == "/integrations/external-channels/81/verify" && attempts.incrementAndGet() == 1) failure("Code rejected") else null }
        show(); waitText("Fixture token")
        compose.onNode(hasText("验证") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("123456")
        waitText("Code rejected"); dialogText("Code rejected").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("654321")
        waitText("外部通知通道已验证并启用。")
        assertEquals(2, count("POST", "/integrations/external-channels/81/verify"))
        compose.onNodeWithText("已验证 · 已启用").performScrollTo().assertIsDisplayed()
    }

    /** 删除失败保留目标，成功不依赖列表回读便立即移除。 */
    @Test fun channelDeleteFailureCanRetry() {
        // 保留确认弹窗直到服务端删除成功。
        val attempts = AtomicInteger()
        override = { if (it.method == "DELETE" && it.path == "/integrations/external-channels/81" && attempts.incrementAndGet() == 1) failure("Delete failed") else null }
        show(); waitText("Fixture token")
        compose.onNodeWithContentDescription("删除通道").performScrollTo().performClick()
        clickDialog("删除"); waitText("Delete failed"); dialogText("Delete failed").assertIsDisplayed()
        clickDialog("删除"); waitText("外部通知通道已删除。")
        compose.onNodeWithText("https://example.com/fixture").assertDoesNotExist()
    }

    /** 会话切换销毁旧草稿与成功弹窗，旧请求回包不能向新账号泄漏一次性令牌。 */
    @Test fun accountSwitchDiscardsDialogAndLateCreatedToken() {
        // 将创建响应延迟到换账号之后，核对页面不显示旧账号的新凭据。
        override = { if (it.method == "POST" && it.path == "/integrations/tokens") created(it).setBodyDelay(1, TimeUnit.SECONDS) else null }
        show(); openToken(); fill("名称", "Old account secret"); clickDialog("创建")
        compose.waitUntil(5_000) { count("POST", "/integrations/tokens") == 1 }
        compose.runOnIdle { account.value = session.copy(userId = 35, accessToken = "another-fixture-token") }
        waitText("Fixture token")
        Thread.sleep(1300)
        compose.onNodeWithText("令牌已创建").assertDoesNotExist()
        compose.onNodeWithText("Old account secret").assertDoesNotExist()
        openToken()
        compose.onNode(hasSetTextAction() and hasText("名称")).assertTextContains("名称")
        compose.onNodeWithText("Old account secret").assertDoesNotExist()
    }

    /** 未登录不读取任何私有数据，也不显示可以操作的集成表单。 */
    @Test fun signedOutNeverReadsIntegrations() {
        // 退出状态不复用旧账号列表或按钮。
        account.value = null; show(); waitText("请先登录后管理外部集成。")
        compose.onNodeWithContentDescription("新增令牌").assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }

    /** 日期规范化保留未来时刻，空值不强制过期，无效日期明确拒绝。 */
    @Test fun expiryNormalizationRejectsInvalidAndPastValues() {
        // 使用固定时刻避免测试依赖机器当前年月。
        val now = Instant.parse("2026-10-15T00:00:00Z")
        assertNull(normalizeIntegrationExpiry(" ", now))
        assertEquals("2027-01-01T00:00:00Z", normalizeIntegrationExpiry("2027-01-01T00:00:00Z", now))
        assertTrue(runCatching { normalizeIntegrationExpiry("2026-01-01T00:00:00Z", now) }.isFailure)
        assertTrue(runCatching { normalizeIntegrationExpiry("bad-date", now) }.isFailure)
    }

    /** 将当前可变会话挂到真实页面，供账号切换场景重组。 */
    private fun show() {
        // 沿用共享主题与弹窗组件，不构造测试专用界面。
        host.setContent { HlovetTheme { NativeIntegrationsScreen(account.value, onBack = {}) } }
    }

    /** 打开令牌表单前等待实际列表读完。 */
    private fun openToken() {
        // 新建入口加载时禁用，避免测试与异步请求竞态。
        waitText("Fixture token")
        compose.onNodeWithContentDescription("新增令牌").performScrollTo().performClick()
    }

    /** 在当前弹窗中填写 label 所指的字段为 value。 */
    private fun fill(label: String, value: String) {
        // 只选弹窗内可编辑字段，防止误操作页面上的同名文案。
        compose.onNode(hasSetTextAction() and hasText(label) and hasAnyAncestor(isDialog())).performScrollTo().performTextReplacement(value)
    }

    /** 点击弹窗中 text 对应的操作。 */
    private fun clickDialog(text: String) {
        // 用点击语义区分同名标题和按钮。
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    }

    /** 返回 text 对应的弹窗内节点，以确认错误没有被窗口遮挡。 */
    private fun dialogText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    /** 等待 text 真正在 Compose 语义树出现。 */
    private fun waitText(text: String) {
        // 页面内容含 LazyColumn，先等存在再由各场景滚动检查可见。
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 返回 method/path 对应的请求次数，不记录请求凭据。 */
    private fun count(method: String, path: String) = calls.count { it == method to path }

    /** 将 value 包装为 status 的 JSON HTTP 响应。 */
    private fun json(value: Any, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(value.toString())

    /** 为 message 创建可在真实弹窗展示的失败响应。 */
    private fun failure(message: String) = json(JSONObject().put("message", message), 503)

    /** 使用 request 的草稿字段构造一次性令牌响应，禁止用于真实 API。 */
    private fun created(request: RecordedRequest): MockResponse {
        // 明文为无效替身，不能使用任何真实凭据。
        val body = JSONObject(request.body.clone().readUtf8())
        return json(JSONObject(fixtureToken.toString()).put("id", 72).put("name", body.getString("name")).put("token", "lvt_one_time_fixture_only"), 201)
    }

    /** 为 request 返回有限集成契约，未声明的接口直接拒绝。 */
    private fun response(request: RecordedRequest): MockResponse {
        // GET 与写入分别匹配，避免无条件成功掩盖路由错误。
        return when (request.method to request.path) {
            "GET" to "/auth/providers" -> json("""{"google":{"enabled":false}}""")
            "GET" to "/integrations/tokens" -> json(JSONArray().put(fixtureToken))
            "GET" to "/integrations/external-channels" -> json(JSONArray().put(fixtureChannel))
            "POST" to "/integrations/tokens" -> created(request)
            "DELETE" to "/integrations/tokens/71", "DELETE" to "/integrations/external-channels/81" -> json("{}")
            "POST" to "/integrations/external-channels/81/verify" -> json(JSONObject(fixtureChannel.toString()).put("verified", true).put("enabled", true))
            else -> json(JSONObject().put("message", "Unexpected integration fixture route"), 404)
        }
    }
}
