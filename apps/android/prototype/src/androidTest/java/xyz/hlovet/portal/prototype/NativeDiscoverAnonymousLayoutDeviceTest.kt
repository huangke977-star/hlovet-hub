package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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

/** 在设备内夹具验证发现和匿名页的紧凑工具栏、独立列表及自动分页，不访问生产接口。 */
@RunWith(AndroidJUnit4::class)
class NativeDiscoverAnonymousLayoutDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"

    /** 只允许专用回环构建启动夹具，避免测试包把请求发往生产站点。 */
    @Before
    fun startFixture() {
        // 地址不匹配时让 Gradle 设备任务跳过，而不是替换测试配置或触碰真实数据。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            // 只按页面实际路径返回固定列表，第二页用于验证滚动触发的请求。
            dispatcher = object : Dispatcher() {
                /** 返回发现和匿名页各自的分页夹具，未声明的请求直接失败。 */
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 39871)
        }
    }

    /** 卸载 Compose 页面、关闭夹具并恢复测试前语言，避免影响同一设备上的后续用例。 */
    @After
    fun stopFixture() {
        if (!::server.isInitialized) return
        try {
            host.dispose()
        } finally {
            try {
                server.shutdown()
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 验证发现页只有紧凑搜索筛选首行，并在滚动到末项时自动追加第二页文章。 */
    @Test
    fun discoverKeepsToolbarAndLoadsNextArticlePage() {
        // 直接挂载真实发现页面组合，首批数据由设备内接口夹具提供。
        host.setContent {
            HlovetTheme {
                DiscoverScreen(
                    scaffoldPadding = PaddingValues(),
                    session = null,
                    initialMode = "文章",
                    articles = emptyList(),
                    topics = emptyList(),
                    collections = emptyList(),
                    onLogin = {},
                )
            }
        }
        waitTag("discover-search")
        compose.onNodeWithTag("discover-search").assertIsDisplayed()
        compose.onNodeWithTag("discover-filter").assertIsDisplayed()
        compose.onNodeWithTag("discover-feed-list").assertIsDisplayed()
        waitTag("discover-article-1")

        // 通过真实筛选弹窗切到最新排序，确认首行仍保持不变且列表继续可用。
        compose.onNodeWithTag("discover-filter").performClick()
        compose.onNodeWithText("最新", substring = false).performClick()
        waitTag("discover-article-1")
        compose.onNodeWithTag("discover-feed-list").performScrollToNode(hasTestTag("discover-article-8"))
        waitTag("discover-article-9")

        // 先切回文章模式并再次滚到列表深处，确保后续排序动作确实有旧滚动位置可复用。
        compose.onNodeWithTag("discover-filter").performClick()
        compose.onNodeWithText("发现", substring = false).performClick()
        waitTag("discover-article-1")
        compose.onNodeWithTag("discover-feed-list").performScrollToNode(hasTestTag("discover-article-8"))
        waitTag("discover-article-9")

        // 切换文章排序后第一条必须回到可见区域，不能沿用旧滚动位置。
        compose.onNodeWithTag("discover-filter").performClick()
        compose.onNodeWithText("最多点赞", substring = false).performClick()
        waitTag("discover-article-1")
        compose.onNodeWithTag("discover-article-1").assertIsDisplayed()
    }

    /** 验证匿名页搜索筛选首行不被返回按钮挤压，并在触底后追加第二页匿名话题。 */
    @Test
    fun anonymousKeepsToolbarAndLoadsNextTopicPage() {
        // 使用公开匿名入口，页面只应显示列表工具栏和发起入口，不需要登录会话。
        host.setContent {
            HlovetTheme {
                NativeAnonymousTopicsScreen(
                    session = null,
                    showBackNavigation = false,
                    onBack = {},
                )
            }
        }
        waitTag("anonymous-topic-search")
        compose.onNodeWithTag("anonymous-topic-search").assertIsDisplayed()
        compose.onNodeWithTag("anonymous-topic-filter").assertIsDisplayed()
        compose.onNodeWithTag("anonymous-topic-list").assertIsDisplayed()
        waitTag("anonymous-topic-1")
        compose.onNodeWithTag("anonymous-topic-list").performScrollToNode(hasTestTag("anonymous-topic-12"))
        waitTag("anonymous-topic-13")

        // 搜索会重读第一页；输入前列表位于末尾，搜索后第一条话题应回到顶部可见。
        // 使用 ASCII 夹具词，避免设备未配置中文输入法时把 UI 注入失败误判为产品问题。
        compose.onNodeWithTag("anonymous-topic-search").performTextInput("filter")
        waitTag("anonymous-topic-1")
        compose.onNodeWithTag("anonymous-topic-1").assertIsDisplayed()
    }

    /** 根据当前请求路径返回文章、专题、合集或匿名话题分页夹具。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 测试夹具只关心路径和页码，不把请求头、访客标识或正文写入结果文件。
        val path = request.path.orEmpty()
        val body = when {
            path.startsWith("/articles?") -> articlePage(if (path.contains("page=2")) 2 else 1).toString()
            path.startsWith("/discovery/topics?") || path.startsWith("/discovery/collections?") -> emptyPage().toString()
            path.startsWith("/anonymous-topics?") -> anonymousPage(if (path.contains("page=2")) 2 else 1).toString()
            else -> return MockResponse().setResponseCode(404).setBody(JSONObject().put("message", "Unexpected fixture request: $path").toString())
        }
        return MockResponse().addHeader("Content-Type", "application/json").setBody(body)
    }

    /** 构造两页文章列表，页面编号稳定便于滚动断言和重复请求核对。 */
    private fun articlePage(page: Int): JSONObject {
        val start = if (page == 1) 1 else 9
        val end = if (page == 1) 8 else 10
        val items = JSONArray()
        for (id in start..end) items.put(article(id))
        return JSONObject().put("items", items).put("total", 10).put("page", page).put("pageSize", 8).put("totalPages", 2)
    }

    /** 构造空专题和合集页，避免非文章内容占用发现文章流的首屏空间。 */
    private fun emptyPage(): JSONObject = JSONObject()
        .put("items", JSONArray())
        .put("total", 0)
        .put("page", 1)
        .put("pageSize", 8)
        .put("totalPages", 1)

    /** 构造两页匿名话题列表，页尾第二页用于验证 LazyColumn 近底部监听。 */
    private fun anonymousPage(page: Int): JSONObject {
        val start = if (page == 1) 1 else 13
        val end = if (page == 1) 12 else 14
        val items = JSONArray()
        for (id in start..end) {
            items.put(
                JSONObject()
                    .put("id", id)
                    .put("title", "匿名话题 $id")
                    .put("status", "active")
                    .put("messageCount", id)
                    .put("messageLikeCount", 0)
                    .put("favoriteCount", 0)
                    .put("favorited", false)
                    .put("createdAt", "2026-11-06T00:00:00.000Z")
                    .put("updatedAt", "2026-11-06T00:00:00.000Z"),
            )
        }
        return JSONObject().put("items", items).put("total", 14).put("page", page).put("pageSize", 12).put("totalPages", 2)
    }

    /** 构造发现列表可直接显示的公开文章最小字段集合。 */
    private fun article(id: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("title", "发现文章 $id")
        .put("slug", "discover-$id")
        .put("summary", "布局分页夹具")
        .put("content", "<p>布局分页夹具</p>")
        .put("contentFormat", "html")
        .put("category", "测试")
        .put("tags", JSONArray())
        .put("viewCount", 0)
        .put("likeCount", 0)
        .put("favoriteCount", 0)
        .put("commentCount", 0)
        .put("author", JSONObject().put("id", 99).put("username", "feed-author").put("nickname", "信息流作者"))
        .put("status", "published")
        .put("visibility", "public")
        .put("images", JSONArray())

    /** 等待指定语义标签进入 Compose 树，超时直接暴露页面加载失败。 */
    private fun waitTag(tag: String, timeoutMillis: Long = 15_000) {
        // 网络回调和列表分页都是异步的，条件等待比固定睡眠更能区分真正的布局回归。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
