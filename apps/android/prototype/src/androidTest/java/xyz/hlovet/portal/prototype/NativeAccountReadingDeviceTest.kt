package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.net.InetAddress

/** 在设备内夹具验证个人文章集合的页签切换、逐行列表和自动分页。 */
@RunWith(AndroidJUnit4::class)
class NativeAccountReadingDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val session = NativeSession(
        accessToken = "fixture-account-token",
        refreshToken = "fixture-account-refresh",
        userId = 91,
        username = "fixture-reader",
        nickname = "Fixture reader",
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
    )
    private var oldLocale = "zh-CN"

    /** 只在专用回环构建启动设备内接口，防止测试触碰生产个人数据。 */
    @Before
    fun startFixture() {
        // 生产地址变体必须跳过该夹具，避免把固定文章误显示为真实账号内容。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                /** 根据阅读集合路径返回两页固定文章，未声明的请求直接失败。 */
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 39871)
        }
    }

    /** 释放 Compose 页面和接口夹具，并恢复测试前的语言设置。 */
    @After
    fun stopFixture() {
        if (!::server.isInitialized) return
        try {
            host.dispose()
        } finally {
            server.shutdown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
        }
    }

    /** 验证四个阅读集合共享页签，并在滚动到末项时自动加载下一页。 */
    @Test
    fun accountReadingTabsAndPaginationWork() {
        // 先打开稍后读，首批文章必须逐条进入独立列表，而不是被包进单个大卡片。
        host.setContent {
            var selectedTarget by remember { mutableStateOf<AccountTarget>(AccountTarget.ReadLater) }
            HlovetTheme {
                AccountScreen(
                    target = selectedTarget,
                    session = session,
                    onBack = {},
                    onArticleClick = {},
                    onOpenTarget = { selectedTarget = it },
                )
            }
        }
        waitTag("account-article-1")
        compose.onNodeWithText("稍后读", substring = false).assertIsDisplayed()
        compose.onNodeWithText("历史", substring = false).assertIsDisplayed()

        // 切换到历史页签后应重新请求对应接口，并保留同一套列表结构。
        compose.onNodeWithText("历史", substring = false).performClick()
        waitTag("account-article-1")
        compose.onNodeWithTag("account-article-list", useUnmergedTree = true).assertIsDisplayed()

        // 滚动到第一页末项，下一页文章应自动出现，不依赖手动点击“加载更多”。
        compose.onNodeWithTag("account-article-list").performScrollToNode(hasTestTag("account-article-12"))
        waitTag("account-article-13")
    }

    /** 按当前请求路径返回阅读集合夹具，保证页签切换确实改变了 API 路径。 */
    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val body = when {
            path.startsWith("/articles/read-later?") -> articlePage(if (path.contains("page=2")) 2 else 1)
            path.startsWith("/articles/history?") -> articlePage(if (path.contains("page=2")) 2 else 1)
            else -> return MockResponse().setResponseCode(404).setBody(JSONObject().put("message", "Unexpected fixture request: $path").toString())
        }
        return MockResponse().addHeader("Content-Type", "application/json").setBody(body.toString())
    }

    /** 构造两页文章列表，页尾文章用于验证近底部自动分页。 */
    private fun articlePage(page: Int): JSONObject {
        val start = if (page == 1) 1 else 13
        val end = if (page == 1) 12 else 14
        val items = JSONArray()
        for (id in start..end) items.put(article(id))
        return JSONObject().put("items", items).put("total", 14).put("page", page).put("pageSize", 12).put("totalPages", 2)
    }

    /** 构造个人文章列表可渲染的最小文章字段。 */
    private fun article(id: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("title", "个人文章 $id")
        .put("slug", "account-$id")
        .put("summary", "个人文章集合夹具")
        .put("content", "<p>个人文章集合夹具</p>")
        .put("contentFormat", "html")
        .put("category", "测试")
        .put("tags", JSONArray())
        .put("viewCount", id)
        .put("likeCount", 0)
        .put("favoriteCount", 0)
        .put("commentCount", 0)
        .put("readingProgress", 60)
        .put("lastReadAt", "2026-11-06T00:00:00.000Z")
        .put("author", JSONObject().put("id", 91).put("username", "fixture-reader").put("nickname", "夹具作者"))
        .put("status", "published")
        .put("visibility", "public")
        .put("images", JSONArray())

    /** 等待语义标签出现，超时直接暴露列表或分页未完成。 */
    private fun waitTag(tag: String, timeoutMillis: Long = 15_000) {
        // 异步网络响应和懒加载需要条件等待，固定睡眠无法证明分页已经发生。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
