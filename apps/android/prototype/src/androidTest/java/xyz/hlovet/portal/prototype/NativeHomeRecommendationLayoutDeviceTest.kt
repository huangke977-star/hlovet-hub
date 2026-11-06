package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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

/** 在设备内夹具验证首页推荐文章的独立懒行和自动分页，不访问生产接口。 */
@RunWith(AndroidJUnit4::class)
class NativeHomeRecommendationLayoutDeviceTest {
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
            // 只为首页推荐的第二页返回固定文章，页面其它请求不应被测试触发。
            dispatcher = object : Dispatcher() {
                /** 返回首页推荐分页夹具，未声明的请求直接失败。 */
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

    /** 验证首页滚到推荐区末尾后自动追加下一页，且文章行拥有独立回收边界。 */
    @Test
    fun homeRecommendationLoadsNextPageAtVisibleEnd() {
        // 直接挂载真实首页组合，第一页由测试参数提供，第二页由设备内接口夹具提供。
        host.setContent {
            HlovetTheme {
                HomeScreen(
                    scaffoldPadding = PaddingValues(),
                    session = null,
                    articles = (1..8).map(::article),
                    onArticleClick = {},
                    onOpenAnnouncements = {},
                    onOpenAnonymousTopics = {},
                    onOpenSuggestions = {},
                    onOpenSearch = {},
                    onOpenWrite = {},
                    onOpenSubscriptions = {},
                    onLogin = {},
                )
            }
        }
        waitTag("home-article-1")

        // 滚到推荐区最后一行，触发接近末尾监听；下一页必须追加而不是替换第一页。
        compose.onNodeWithTag("home-feed-list").performScrollToNode(hasTestTag("home-article-8"))
        compose.onNodeWithTag("home-article-8").assertIsDisplayed()
        waitTag("home-article-9")
        compose.onNodeWithTag("home-feed-list").performScrollToNode(hasTestTag("home-article-10"))
        compose.onNodeWithTag("home-article-10").assertIsDisplayed()

        // 回到推荐区顶部确认第二页是追加而不是替换第一页，分页完成后原首条仍可正常定位。
        compose.onNodeWithTag("home-feed-list").performScrollToNode(hasTestTag("home-article-1"))
        compose.onNodeWithTag("home-article-1").assertIsDisplayed()
    }

    /** 根据当前请求路径返回首页推荐第二页夹具。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 测试夹具只关心首页文章分页，不把请求头或访客标识写入结果文件。
        val path = request.path.orEmpty()
        if (!path.startsWith("/articles?")) {
            return MockResponse().setResponseCode(404).setBody(JSONObject().put("message", "Unexpected fixture request: $path").toString())
        }
        val body = JSONObject()
            .put("items", JSONArray((9..10).map(::articleJson)))
            .put("total", 10)
            .put("page", 2)
            .put("pageSize", 8)
            .put("totalPages", 2)
        return MockResponse().addHeader("Content-Type", "application/json").setBody(body.toString())
    }

    /** 构造首页列表可直接显示的最小文章模型。 */
    private fun article(id: Int): RemoteArticle = RemoteArticle(
        id = id,
        title = "首页推荐文章 $id",
        slug = "home-$id",
        summary = "首页懒加载夹具",
        content = "首页懒加载夹具",
        category = "测试",
        tags = emptyList(),
        viewCount = 0,
        likeCount = 0,
        favoriteCount = 0,
        commentCount = 0,
        authorName = "首页作者",
        authorUsername = "home-author",
        authorId = 99,
    )

    /** 构造首页第二页接口所需的最小 JSON 文章。 */
    private fun articleJson(id: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("title", "首页推荐文章 $id")
        .put("slug", "home-$id")
        .put("summary", "首页懒加载夹具")
        .put("content", "首页懒加载夹具")
        .put("contentFormat", "markdown")
        .put("category", "测试")
        .put("tags", JSONArray())
        .put("viewCount", 0)
        .put("likeCount", 0)
        .put("favoriteCount", 0)
        .put("commentCount", 0)
        .put("author", JSONObject().put("id", 99).put("username", "home-author").put("nickname", "首页作者"))
        .put("status", "published")
        .put("visibility", "public")
        .put("images", JSONArray())

    /** 等待指定语义标签进入 Compose 树，超时直接暴露首页加载失败。 */
    private fun waitTag(tag: String, timeoutMillis: Long = 15_000) {
        // 网络回调和列表分页都是异步的，条件等待比固定睡眠更能区分真正的布局回归。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
