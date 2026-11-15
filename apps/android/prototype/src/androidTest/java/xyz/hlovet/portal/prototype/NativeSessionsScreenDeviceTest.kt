package xyz.hlovet.portal.prototype

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用设备内回环夹具验证登录设备列表和撤销交互，不访问生产接口或真实账号。 */
@RunWith(AndroidJUnit4::class)
class NativeSessionsScreenDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RequestSummary>()
    private var oldLocale = "zh-CN"
    private val otherSessionPresent = AtomicBoolean(true)
    private val currentRevoked = AtomicBoolean(false)
    private val failOtherRevocation = AtomicBoolean(false)
    private val notices = CopyOnWriteArrayList<String>()
    private val currentRevocationCallbacks = AtomicInteger(0)

    /** 仅保留测试断言需要的请求元数据，避免把授权头和请求体留在测试内存中。 */
    private data class RequestSummary(val method: String?, val path: String?)
    private val session = NativeSession(
        accessToken = "fixture-sessions-token",
        refreshToken = "fixture-sessions-refresh",
        userId = 73,
        username = "fixture-sessions-user",
        nickname = "设备测试用户",
        appearance = PreviewAppearance(
            themeId = "sky-blue",
            accent = "#0284C7",
            foreground = "#1F2937",
            muted = "#52616F",
            surface = "#FFFFFF",
            cardAlpha = 50,
            glassBlur = 18,
            glassTint = "#FFFFFF",
            glassTintAlpha = 46,
        ),
    )

    /** 仅在构建明确指向本机回环 API 时启动夹具，阻止测试误请求线上服务。 */
    @Before
    fun startFixture() {
        oldLocale = NativeI18n.locale
        // 任何非夹具构建都跳过，确保测试凭据只会发往当前设备的 MockWebServer。
        assumeTrue("Requires the isolated device-local API build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        requests.clear()
        notices.clear()
        otherSessionPresent.set(true)
        currentRevoked.set(false)
        failOtherRevocation.set(false)
        currentRevocationCallbacks.set(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            // 只实现本页面调用的会话接口，未声明请求返回 404 以暴露意外网络行为。
            dispatcher = object : Dispatcher() {
                /** 记录请求路径并返回内存中的会话夹具，不持久化授权头或测试凭据。 */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += RequestSummary(request.method, request.requestUrl?.encodedPath)
                    return respond(request)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 39871)
        }
    }

    /** 先卸载页面再停止回环服务，避免异步请求在夹具关闭后更新组合状态。 */
    @After
    fun stopFixture() {
        try {
            // 只有服务器已启动后才挂载页面，跳过或启动失败时无需卸载 Compose 内容。
            if (::server.isInitialized) host.dispose()
        } finally {
            try {
                // 即使服务器启动失败，也必须恢复测试前的全局语言状态。
                if (::server.isInitialized) server.shutdown()
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 成功撤销其它设备后重新读取列表，当前会话仍保留且显示成功提示。 */
    @Test
    fun revokeOtherDevicesRefreshesListAndKeepsCurrentSession() {
        // 首次加载必须展示当前设备和另一台设备，并启用批量退出入口。
        mountScreen()
        waitForText("Windows 设备")
        compose.onNodeWithText("Android 设备").assertIsDisplayed()

        // 通过确认弹窗提交撤销，夹具随后从下一次列表响应中移除远端会话。
        compose.onNodeWithText("退出其他设备").performClick()
        compose.onNodeWithText("确定退出其他设备吗？当前设备会保持登录。").assertIsDisplayed()
        compose.onNodeWithText("确认").performClick()
        waitUntil { requests.count { it.path == "/auth/sessions" } >= 2 && !otherSessionPresent.get() }

        // 回读列表与通知证明成功路径确实刷新 UI，没有误退出当前设备。
        compose.onAllNodesWithText("Windows 设备").assertCountEquals(0)
        compose.onNodeWithText("Android 设备").assertIsDisplayed()
        waitUntil { notices.any { it.contains("已退出 1 个设备会话") } }
        assertTrue(requests.any { it.method == "POST" && it.path == "/auth/sessions/revoke-others" })
    }

    /** 撤销请求失败时保留原有列表并把服务端错误交给页面通知回调。 */
    @Test
    fun failedRevocationKeepsSessionsAndReportsError() {
        // 故意让批量撤销返回服务端错误，验证失败分支不会清空已有设备快照。
        failOtherRevocation.set(true)
        mountScreen()
        waitForText("Windows 设备")
        compose.onNodeWithText("退出其他设备").performClick()
        compose.onNodeWithText("确认").performClick()

        // 失败后无需重新加载，原有两条设备记录应仍可见且回调收到错误提示。
        waitUntil { notices.isNotEmpty() }
        compose.onNodeWithText("Windows 设备").assertIsDisplayed()
        compose.onNodeWithText("Android 设备").assertIsDisplayed()
        assertTrue(requests.any { it.method == "POST" && it.path == "/auth/sessions/revoke-others" })
    }

    /** 全部设备撤销返回当前会话标记后，页面必须调用主机的本地登出回调。 */
    @Test
    fun revokeAllDevicesInvokesCurrentSessionCleanup() {
        // 全部退出会结束当前设备登录，确认弹窗必须明确告知这一副作用。
        mountScreen()
        waitForText("Windows 设备")
        compose.onNodeWithText("退出全部设备").performClick()
        compose.onNodeWithText("确定退出全部设备吗？当前设备也会退出登录。").assertIsDisplayed()
        compose.onNodeWithText("确认").performClick()

        // 服务端确认当前会话已撤销时应交由 MainActivity 清除本地令牌和推送注册。
        waitUntil { currentRevocationCallbacks.get() == 1 }
        assertTrue(currentRevoked.get())
        assertTrue(requests.any { it.method == "POST" && it.path == "/auth/sessions/revoke-all" })
        assertEquals(1, currentRevocationCallbacks.get())
    }

    /** 用假会话宿主挂载真实 Compose 页面，不经过生产 Activity 或用户认证流程。 */
    private fun mountScreen() {
        // 所有外部副作用通过测试回调捕获，网络只由本类的 loopback fixture 提供。
        host.setContent {
            HlovetTheme {
                NativeSessionsScreen(
                    session = session,
                    onBack = {},
                    onCurrentSessionRevoked = {
                        currentRevoked.set(true)
                        currentRevocationCallbacks.incrementAndGet()
                    },
                    onNotice = notices::add,
                )
            }
        }
    }

    /** 根据请求方法和路径返回会话列表、撤销结果或明确的未实现响应。 */
    private fun respond(request: RecordedRequest): MockResponse = when {
        request.method == "POST" && request.path == "/auth/sessions" -> {
            // 当前设备始终保留，只有批量撤销成功后才从快照中移除远端会话。
            val sessions = JSONArray().put(sessionJson("current-session", "Android 14", true))
            if (otherSessionPresent.get()) sessions.put(sessionJson("other-session", "Windows NT 10.0", false))
            json(JSONObject().put("sessions", sessions))
        }
        request.method == "POST" && request.path == "/auth/sessions/revoke-others" -> {
            // 可切换错误响应以覆盖撤销失败时的页面状态保持。
            if (failOtherRevocation.get()) {
                json(JSONObject().put("message", "夹具撤销失败"), status = 503)
            } else {
                otherSessionPresent.set(false)
                json(JSONObject().put("revokedSessions", 1))
            }
        }
        request.method == "POST" && request.path == "/auth/sessions/revoke-all" -> {
            // 返回当前标识以触发与生产宿主相同的本地登出清理分支。
            currentRevoked.set(true)
            otherSessionPresent.set(false)
            json(JSONObject().put("revokedSessions", 2))
        }
        else -> json(JSONObject().put("message", "未声明的测试请求"), status = 404)
    }

    /** 构造服务端会话数据，覆盖当前设备标识和 user-agent 设备类型识别。 */
    private fun sessionJson(id: String, userAgent: String, current: Boolean): JSONObject = JSONObject()
        .put("id", id)
        .put("issuedAt", "2026-11-15T08:30:00.000Z")
        .put("expiresAt", "2026-12-15T08:30:00.000Z")
        .put("ip", "192.0.2.10")
        .put("userAgent", userAgent)
        .put("current", current)

    /** 设置 JSON 响应头与状态码，避免夹具解析依赖服务器默认内容类型。 */
    private fun json(body: JSONObject, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body.toString())

    /** 等待网络回包驱动的 Compose 状态稳定，超时由测试规则报告失败条件。 */
    private fun waitForText(text: String) {
        // 页面初始请求在 IO 调度器运行，因此不能立即断言异步会话行。
        waitUntil { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    /** 在有限时间内等待指定的设备状态，避免固定 sleep 掩盖请求或重组失败。 */
    private fun waitUntil(condition: () -> Boolean) {
        // 网络和 Compose 都是异步过程，使用规则的轮询等待并保留明确的超时失败。
        compose.waitUntil(15_000, condition)
    }
}
