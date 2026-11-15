package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

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
    private val calls = CopyOnWriteArrayList<String>()

    /** 只在专用回环构建启动设备内接口，防止测试触碰生产个人数据。 */
    @Before
    fun startFixture() {
        // 生产地址变体必须跳过该夹具，避免把固定文章误显示为真实账号内容。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        calls.clear()
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                /** 根据阅读集合路径返回两页固定文章，未声明的请求直接失败。 */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    calls.add("${request.method} ${request.path}")
                    return respond(request)
                }
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

    /** 验证收藏、点赞、搜索和两个集合的移除操作都更新请求与当前列表。 */
    @Test
    fun accountCollectionsSearchAndRemovalWork() {
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

        // 搜索输入必须重新请求当前集合，并把关键词编码到真实查询参数。
        compose.onNodeWithTag("account-article-search").performTextReplacement("needle")
        compose.waitUntil(10_000) { calls.any { it.startsWith("GET /articles/read-later?") && it.contains("search=needle") } }
        waitTag("account-article-901")
        assertTrue(calls.any { it.startsWith("GET /articles/read-later?") && it.contains("search=needle") })

        // 收藏和点赞页签必须切换到各自的生产接口，而不是继续复用稍后读结果。
        compose.onNodeWithText("收藏", substring = false).performClick()
        compose.waitUntil(10_000) { calls.any { it.startsWith("GET /articles/favorites?") } }
        waitTag("account-article-101")
        compose.onNodeWithText("赞过", substring = false).performClick()
        compose.waitUntil(10_000) { calls.any { it.startsWith("GET /articles/liked?") } }
        waitTag("account-article-201")

        // 回到稍后读后点击行尾操作，服务端成功才从当前列表移除目标文章。
        compose.onNodeWithText("稍后读", substring = false).performClick()
        compose.waitUntil(10_000) { calls.count { it.startsWith("GET /articles/read-later?") } >= 2 }
        waitTag("account-article-1")
        compose.onNode(hasContentDescription("移出稍后读") and hasAnyAncestor(hasTestTag("account-article-1"))).performClick()
        compose.waitUntil(10_000) { calls.any { it == "DELETE /articles/1/read-later" } }
        compose.onNodeWithTag("account-article-1").assertDoesNotExist()
    }

    /** 验证单条阅读历史删除和清空确认都会发送正确请求并呈现最终空状态。 */
    @Test
    fun readingHistoryRemovalAndClearWork() {
        host.setContent {
            var selectedTarget by remember { mutableStateOf<AccountTarget>(AccountTarget.ReadingHistory) }
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

        // 单条删除先验证列表移除，再确认服务端 DELETE 路径，避免只验证了视觉隐藏。
        compose.onNode(hasContentDescription("移除阅读记录") and hasAnyAncestor(hasTestTag("account-article-1"))).performClick()
        compose.waitUntil(10_000) { calls.any { it == "DELETE /articles/history/1" } }
        compose.onNodeWithTag("account-article-1").assertDoesNotExist()

        // 清空按钮必须先打开确认弹窗，确认后才清空并显示阅读历史空状态。
        compose.onNodeWithContentDescription("清空阅读历史").performClick()
        compose.onNodeWithText("确定清空全部阅读历史吗？", substring = true).assertIsDisplayed()
        compose.onNodeWithText("清空", substring = false).performClick()
        compose.waitUntil(10_000) { calls.any { it == "DELETE /articles/history" } }
        compose.onNodeWithText("还没有阅读历史。", substring = true).assertIsDisplayed()
        assertEquals(1, calls.count { it == "DELETE /articles/history" })
    }

    /** 按当前请求路径返回阅读集合夹具，保证页签切换确实改变了 API 路径。
     * @param request 当前页面发出的隔离 HTTP 请求，响应不接触生产接口。
     */
    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        // 删除接口返回真实成功状态，让页面只能在服务端成功后更新集合。
        if (request.method == "DELETE" && path == "/articles/1/read-later") {
            return MockResponse().addHeader("Content-Type", "application/json").setBody(JSONObject().put("readLater", false).toString())
        }
        if (request.method == "DELETE" && path == "/articles/history/1") {
            return MockResponse().addHeader("Content-Type", "application/json").setBody(JSONObject().toString())
        }
        if (request.method == "DELETE" && path == "/articles/history") {
            return MockResponse().addHeader("Content-Type", "application/json").setBody(JSONObject().toString())
        }
        // 列表接口按当前集合和搜索词返回不同编号，能够识别页面是否显示了新结果。
        val body = when {
            path.startsWith("/articles/read-later?") && request.requestUrl?.queryParameter("search") == "needle" -> singleArticlePage(901)
            path.startsWith("/articles/read-later?") -> articlePage(if (path.contains("page=2")) 2 else 1)
            path.startsWith("/articles/history?") -> articlePage(if (path.contains("page=2")) 2 else 1)
            path.startsWith("/articles/favorites?") -> singleArticlePage(101)
            path.startsWith("/articles/liked?") -> singleArticlePage(201)
            else -> return MockResponse().setResponseCode(404).setBody(JSONObject().put("message", "Unexpected fixture request: $path").toString())
        }
        return MockResponse().addHeader("Content-Type", "application/json").setBody(body.toString())
    }

    /** 构造两页文章列表，页尾文章用于验证近底部自动分页。
     * @param page 需要返回的页码，只允许本夹具约定的第一页或第二页。
     * @return 对应页码的固定文章及分页元数据。
     */
    private fun articlePage(page: Int): JSONObject {
        val start = if (page == 1) 1 else 13
        val end = if (page == 1) 12 else 14
        val items = JSONArray()
        for (id in start..end) items.put(article(id))
        return JSONObject().put("items", items).put("total", 14).put("page", page).put("pageSize", 12).put("totalPages", 2)
    }

    /** 构造单条结果页，用于判断搜索和页签切换是否显示了新响应。
     * @param id 该集合或搜索结果的固定文章编号。
     * @return 一条文章且总页数为一的列表响应。
     */
    private fun singleArticlePage(id: Int): JSONObject = JSONObject()
        .put("items", JSONArray().put(article(id)))
        .put("total", 1)
        .put("page", 1)
        .put("pageSize", 12)
        .put("totalPages", 1)

    /** 构造个人文章列表可渲染的最小文章字段。
     * @param id 文章编号，同时作为可识别标题的一部分。
     * @return 可被阅读中心列表解析的固定文章对象。
     */
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
