package xyz.hlovet.portal.prototype

import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** 在设备内回环夹具验证推荐反馈的筛选、恢复和批量清理，不访问生产接口。 */
@RunWith(AndroidJUnit4::class)
class NativeRecommendationFeedbackDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private var oldLocale = "zh-CN"
    private val session = NativeSession(
        accessToken = "fixture-token",
        refreshToken = "fixture-refresh",
        userId = 7,
        username = "feedback-fixture",
        nickname = "Feedback fixture",
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
    )

    /** 仅在设备本地预览地址启动夹具，避免测试凭据发送到真实服务。 */
    @Before
    fun startFixture() {
        // 地址不匹配时主动跳过，保持生产地址构建的安全边界。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            // 所有响应只返回本测试需要的最小数据，不记录令牌或完整请求体。
            dispatcher = object : Dispatcher() {
                /** 按接口路径返回推荐反馈列表、恢复结果或清理数量。 */
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(39871)
        }
    }

    /** 卸载页面后关闭夹具并恢复测试前的语言。 */
    @After
    fun stopFixture() {
        // 页面先卸载，避免关闭服务后仍有旧协程写入下一项测试。
        if (!::server.isInitialized) return
        try {
            host.dispose()
        } finally {
            server.shutdown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
        }
    }

    /** 验证恢复单条反馈后立即从当前列表移除，并显示成功提示。 */
    @Test
    fun restoreRemovesFeedbackFromList() {
        // 页面通过真实 NativeRecommendationFeedbackApi 读取夹具列表。
        show()
        waitText("测试文章")

        // 点击恢复图标，确认请求使用目标类型和目标编号。
        compose.onNodeWithContentDescription("恢复推荐").performClick()
        waitText("已恢复该项推荐。")
        compose.onNodeWithText("测试文章").assertDoesNotExist()
        check(calls.contains("DELETE" to "/discovery/recommendations/feedback/article/11"))
    }

    /** 验证类型筛选会重新请求服务端，并可清理当前筛选类型的全部反馈。 */
    @Test
    fun filterAndClearCurrentTypeUseServerScope() {
        // 先展示全部类型，确保筛选栏和列表已经进入组合树。
        show()
        waitText("测试文章")

        // 专题筛选必须带 targetType 参数，不能只在客户端过滤旧列表。
        compose.onNodeWithText("专题", substring = false).performClick()
        waitText("测试专题")
        check(calls.contains("GET" to "/discovery/recommendations/feedback?targetType=topic"))

        // 当前类型清理需要二次确认，并把数量提示回显给用户。
        compose.onNodeWithContentDescription("清理当前反馈").performClick()
        compose.onNodeWithText("确认清理", substring = false).performClick()
        waitText("已清理 1 条推荐反馈。")
        compose.onNodeWithText("测试专题").assertDoesNotExist()
        check(calls.contains("DELETE" to "/discovery/recommendations/feedback?targetType=topic"))
    }

    /** 挂载推荐反馈页面并使用固定会话，测试不经过登录页面或真实账号。 */
    private fun show() {
        // 通过真实 Compose 页面装配列表、筛选栏和确认弹窗。
        host.setContent {
            HlovetTheme {
                NativeRecommendationFeedbackScreen(session = session, onBack = {})
            }
        }
    }

    /** 等待指定文案进入语义树，异步网络完成前不使用固定睡眠替代断言。 */
    private fun waitText(value: String, timeoutMillis: Long = 15_000) {
        // 文案是当前测试夹具中的稳定业务标识，超时直接暴露页面或请求失败。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    /** 按请求方法和路径返回对应夹具，并记录不含敏感信息的调用摘要。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 只保留方法和路径，避免测试证据持久化 Authorization 或请求正文。
        val method = request.method.orEmpty()
        val path = request.path.orEmpty()
        calls += method to path
        val body = when {
            method == "GET" && path == "/discovery/recommendations/feedback" -> feedbackPage("article", "测试文章", 11)
            method == "GET" && path == "/discovery/recommendations/feedback?targetType=topic" -> feedbackPage("topic", "测试专题", 22)
            method == "DELETE" && path == "/discovery/recommendations/feedback/article/11" -> JSONObject()
            method == "DELETE" && path == "/discovery/recommendations/feedback?targetType=topic" -> JSONObject().put("count", 1)
            else -> return MockResponse().setResponseCode(404).setBody(JSONObject().put("message", "Unexpected fixture request: $method $path").toString())
        }
        return MockResponse().addHeader("Content-Type", "application/json").setBody(body.toString())
    }

    /** 构造一个稳定的单项反馈列表，分别供文章和专题筛选使用。 */
    private fun feedbackPage(targetType: String, label: String, targetId: Int): JSONObject = JSONObject()
        .put("items", JSONArray().put(JSONObject()
            .put("id", targetId)
            .put("targetType", targetType)
            .put("targetId", targetId)
            .put("label", label)
            .put("createdAt", "2026-11-09T00:00:00.000Z")))
}
