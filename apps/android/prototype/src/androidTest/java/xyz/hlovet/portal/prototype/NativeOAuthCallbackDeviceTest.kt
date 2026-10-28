package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** 使用设备内隔离接口验证 Google 回调进入原生宿主后的成功和失败时序。 */
@RunWith(AndroidJUnit4::class)
class NativeOAuthCallbackDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale = "zh-CN"

    /** 仅在隔离构建中启动回环服务，禁止回调测试向生产 API 发送令牌。 */
    @Before
    fun startFixture() {
        // 来源保护必须先于任何 Activity 启动，避免误测正式账号或线上认证结果。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 按 OAuth 消费请求返回固定会话，其它首页请求返回空的合法 JSON。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 回调响应延迟一小段时间，给处理中状态一个可观测的渲染窗口。
                if (request.method == "POST" && request.requestUrl?.encodedPath == "/auth/oauth/result") {
                    Thread.sleep(1_200)
                    return json(authenticatedResponse())
                }
                // 首页和登录配置请求使用空对象，验证重点保持在认证宿主，不引入额外业务夹具。
                return json(JSONObject())
            }
        }
        // 固定 IPv4 回环，避免设备代理或地址族变化导致假失败。
        server.start(InetAddress.getByName("127.0.0.1"), 39871)
    }

    /** 关闭真实 Activity 和隔离服务，清除本测试建立的本地会话但不触碰数据库。 */
    @After
    fun stopFixture() {
        // 本用例只验证认证回调时序，不把测试会话留在模拟器上干扰后续专项。
        activity?.close()
        NativeSessionStore.clear(context, "http://127.0.0.1:39871")
        if (::server.isInitialized) server.shutdown()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
    }

    /** 成功回调先显示独立处理中状态，认证响应回来后直接进入首页，不闪现账号密码表单。 */
    @Test
    fun authenticatedCallbackSkipsCredentialFormAndEntersHome() {
        // 通过真实 MainActivity 深链注入回调入口，不直接挂载登录组件或调用 onAuthenticated。
        activity = ActivityScenario.launch(callbackIntent("hlovet-native://auth/google?result=fixture-oauth"))
        waitTag("native-oauth-processing")
        compose.onNodeWithTag("native-oauth-processing").assertIsDisplayed()
        compose.onNodeWithTag("native-login-account").assertDoesNotExist()

        // 成功消费一次性回调后，宿主应保留首页导航且不再显示登录输入框。
        waitTag("native-navigation-0")
        compose.onNodeWithTag("native-login-account").assertDoesNotExist()
        compose.onNodeWithTag("native-login-password").assertDoesNotExist()
        // 启动首页会产生其它公开读取请求，只检查回调消费请求确实出现。
        val requests = (0 until server.requestCount).mapNotNull { server.takeRequest(1, TimeUnit.SECONDS) }
        assertTrue(requests.any { it.requestUrl?.encodedPath == "/auth/oauth/result" })
    }

    /** OAuth 被用户取消时保留登录页并显示错误，不建立半成品会话或进入首页。 */
    @Test
    fun deniedCallbackReturnsToLoginWithProviderError() {
        // 取消分支由真实回调链接承接，服务端不会收到 OAuth 消费请求。
        activity = ActivityScenario.launch(callbackIntent("hlovet-native://auth/google?error=access_denied"))
        waitTag("native-login-account")
        compose.onNodeWithTag("native-login-account").assertIsDisplayed()
        compose.onNodeWithText("access_denied", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("native-navigation-0").assertDoesNotExist()
        val requests = (0 until server.requestCount).mapNotNull { server.takeRequest(1, TimeUnit.SECONDS) }
        assertTrue(requests.none { it.requestUrl?.encodedPath == "/auth/oauth/result" })
    }

    /** 构造带 Google 回调数据的 Activity Intent，复用生产深链解析路径。 */
    private fun callbackIntent(uri: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse(uri))

    /** 等待指定语义标签出现；超时表示真实页面没有完成对应状态迁移。 */
    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 等待指定文案出现，用于确认回调处理中或错误状态已经真正绘制。 */
    private fun waitText(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 构造完整的最小登录会话，让首页能按真实成功响应切换而不是依赖写死页面状态。 */
    private fun authenticatedResponse(): JSONObject = JSONObject().apply {
        put("accessToken", "fixture-oauth-access")
        put("refreshToken", "fixture-oauth-refresh")
        put("user", JSONObject().apply {
            put("id", 9901)
            put("username", "oauth_fixture")
            put("nickname", "OAuth fixture")
            put("email", "oauth_fixture@example.invalid")
            put("locale", "zh-CN")
            put("appearance", JSONObject())
        })
    }

    /** 返回统一 JSON 响应头，保证 Android 网络层按接口成功分支解析。 */
    private fun json(body: JSONObject): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body.toString())
}
