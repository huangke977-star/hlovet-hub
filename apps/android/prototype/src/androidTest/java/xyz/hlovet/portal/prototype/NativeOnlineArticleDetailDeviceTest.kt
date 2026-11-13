package xyz.hlovet.portal.prototype

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.Collections

/** 在设备内夹具验证在线文章详情的正文、评论、作者资料、订阅和专题合集入口，不访问生产环境。 */
@RunWith(AndroidJUnit4::class)
class NativeOnlineArticleDetailDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val subscriptionMethods = Collections.synchronizedList(mutableListOf<String>())
    private var authorProfileStatus = 200
    private var oldLocale = "zh-CN"

    /** 只允许设备预览构建启动回环夹具，避免测试包把详情请求发往生产接口。 */
    @Before
    fun startFixture() {
        // 生产构建不替换地址，也不执行任何业务请求；不匹配时由测试框架跳过本类。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            // 所有接口都由同一个固定夹具处理，未声明的请求返回错误以暴露额外调用。
            dispatcher = object : Dispatcher() {
                /** 根据详情页实际路径返回最小可用响应，并记录订阅写请求。 */
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 39871)
        }
    }

    /** 卸载详情页、关闭网络夹具并恢复测试前语言，避免异步回包污染后续用例。 */
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

    /** 验证在线文章、评论以及专题和合集入口都来自详情页真实加载链路。 */
    @Test
    fun onlineArticleLoadsBodyCommentsAndGroupingCallbacks() {
        // 以不同标题作为占位值，确认页面已经用详情接口响应替换初始导航快照。
        val openedGroupings = mutableListOf<RemoteArticleGrouping>()
        host.setContent {
            HlovetTheme {
                ArticleDetailScreen(
                    initialArticle = seedArticle(),
                    session = null,
                    onLogin = {},
                    onBack = {},
                    onOpenUser = {},
                    onOpenGrouping = { openedGroupings += it },
                )
            }
        }

        // 文章标题和评论必须在详情与评论接口返回后出现，不能只依赖初始快照。
        waitForText("在线详情文章")
        compose.onNodeWithText("隔离评论", substring = false).assertIsDisplayed()

        // 两类关联入口都由页面回调传出，宿主可以据此继续打开对应详情。
        compose.onNodeWithTag("article-grouping-topic-501").performScrollTo().performClick()
        compose.onNodeWithTag("article-grouping-collection-502").performScrollTo().performClick()
        assertEquals(listOf("topic", "collection"), openedGroupings.map { it.type })
    }

    /** 验证作者资料接口失败时只降级作者卡片，正文标题和评论仍然正常显示。 */
    @Test
    fun authorProfileFailureDoesNotBlockArticleAndComments() {
        // 让可选作者资料接口返回错误，模拟作者服务短暂不可用。
        authorProfileStatus = 503
        host.setContent {
            HlovetTheme {
                ArticleDetailScreen(
                    initialArticle = seedArticle(),
                    session = null,
                    onLogin = {},
                    onBack = {},
                    onOpenUser = {},
                )
            }
        }

        // 正文和评论仍须加载，同时作者区域明确展示降级提示而不是卡住整个页面。
        waitForText("在线详情文章")
        compose.onNodeWithText("隔离评论", substring = false).assertIsDisplayed()
        waitForText("作者资料暂时不可用。")
    }

    /** 验证登录后的订阅和取消订阅都使用服务端状态与订阅人数更新作者卡片。 */
    @Test
    fun signedInAuthorSubscriptionUsesServerState() {
        // 使用独立夹具会话访问 visible 文章和作者资料接口，确保鉴权路径也被覆盖。
        host.setContent {
            HlovetTheme {
                ArticleDetailScreen(
                    initialArticle = seedArticle(),
                    session = session(),
                    onLogin = {},
                    onBack = {},
                    onOpenUser = {},
                )
            }
        }

        // 作者资料加载后应显示未订阅按钮，点击后等待服务端返回的已订阅状态。
        waitForText("在线详情文章")
        waitForText("订阅")
        compose.onNodeWithText("订阅", substring = false).performClick()
        waitForText("已订阅")

        // 再次点击必须走 DELETE，并回到服务端返回的未订阅状态。
        compose.onNodeWithText("已订阅", substring = false).performClick()
        waitForText("订阅")
        compose.waitUntil(5_000) { synchronized(subscriptionMethods) { subscriptionMethods.size == 2 } }
        assertEquals(listOf("POST", "DELETE"), synchronized(subscriptionMethods) { subscriptionMethods.toList() })
    }

    /** 等待指定文案进入 Compose 语义树，避免固定睡眠掩盖异步详情加载问题。 */
    private fun waitForText(text: String, timeoutMillis: Long = 15_000) {
        // 网络回包和作者资料是异步任务，条件等待能区分页面未加载与固定延迟不足。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text, substring = false)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    /** 根据当前请求路径返回文章详情、评论、作者资料和订阅响应。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 文章接口按是否携带会话区分公开和 visible 路径，但共享同一份隔离内容。
        val path = request.path.orEmpty()
        return when {
            path == "/articles/test-article" || path == "/articles/visible/test-article" -> json(articleJson())
            path.startsWith("/articles/test-article/comments?") || path.startsWith("/articles/visible/test-article/comments?") -> json(commentsJson())
            path == "/profiles/public/test-author" || path == "/profiles/visible/test-author" -> {
                if (authorProfileStatus == 200) json(profileJson()) else error(authorProfileStatus, "Author profile fixture failure")
            }
            path == "/social/subscriptions/77" && request.method == "POST" -> {
                subscriptionMethods += "POST"
                json(JSONObject().put("subscribed", true).put("subscriberCount", 4))
            }
            path == "/social/subscriptions/77" && request.method == "DELETE" -> {
                subscriptionMethods += "DELETE"
                json(JSONObject().put("subscribed", false).put("subscriberCount", 3))
            }
            else -> error(404, "Unexpected fixture request: $path")
        }
    }

    /** 构造文章接口响应，标题与初始快照不同以证明在线请求确实完成。 */
    private fun articleJson(): JSONObject = JSONObject()
        .put("id", 701)
        .put("title", "在线详情文章")
        .put("slug", "test-article")
        .put("summary", "在线详情隔离测试")
        .put("content", "在线详情正文")
        .put("contentFormat", "markdown")
        .put("category", "测试")
        .put("tags", JSONArray().put("detail"))
        .put("viewCount", 1)
        .put("likeCount", 0)
        .put("favoriteCount", 0)
        .put("commentCount", 1)
        .put("author", JSONObject().put("id", 77).put("username", "test-author").put("nickname", "隔离作者"))
        .put("status", "published")
        .put("visibility", "public")
        .put("images", JSONArray())
        .put("topics", JSONArray().put(JSONObject().put("id", 501).put("title", "隔离专题").put("slug", "isolated-topic")))
        .put("collections", JSONArray().put(JSONObject().put("id", 502).put("name", "隔离合集")))

    /** 构造评论接口响应，验证正文加载后评论请求仍被正确解析并显示。 */
    private fun commentsJson(): JSONObject = JSONObject()
        .put("items", JSONArray().put(
            JSONObject()
                .put("id", 801)
                .put("articleId", 701)
                .put("body", "隔离评论")
                .put("status", "active")
                .put("likeCount", 0)
                .put("liked", false)
                .put("reported", false)
                .put("author", JSONObject().put("id", 88).put("username", "comment-author").put("nickname", "评论作者")),
        ))
        .put("hasMore", false)
        .put("nextCursor", JSONObject.NULL)
        .put("totalThreads", 1)

    /** 构造作者公开资料响应，提供订阅按钮需要的身份和关系状态。 */
    private fun profileJson(): JSONObject = JSONObject()
        .put("id", 77)
        .put("username", "test-author")
        .put("nickname", "隔离作者")
        .put("isSelf", false)
        .put("subscribed", false)
        .put("subscriberCount", 3)
        .put("publicArticleCount", 2)
        .put("receivedLikeCount", 5)
        .put("publicViewCount", 20)
        .put("visibleFields", JSONObject().put("bio", false).put("stats", true))

    /** 构造测试用登录会话，令订阅请求使用 visible 文章和鉴权作者接口。 */
    private fun session(): NativeSession = NativeSession(
        accessToken = "fixture-access",
        refreshToken = "fixture-refresh",
        userId = 99,
        username = "fixture-reader",
        nickname = "Fixture reader",
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
    )

    /** 构造与接口标题不同的初始文章，模拟从列表点击详情时传入的导航快照。 */
    private fun seedArticle(): RemoteArticle = RemoteArticle(
        id = 700,
        title = "占位详情",
        slug = "test-article",
        summary = "占位摘要",
        content = "占位正文",
        contentFormat = "markdown",
        category = "测试",
        tags = emptyList(),
        viewCount = 0,
        likeCount = 0,
        favoriteCount = 0,
        commentCount = 0,
        authorName = "占位作者",
        authorUsername = "test-author",
        authorId = 77,
    )

    /** 将 JSON 夹具包装成统一的成功响应，避免不同路由遗漏内容类型。 */
    private fun json(body: JSONObject): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body.toString())

    /** 构造失败响应，使可选资料失败场景保持和真实 HTTP 错误一致。 */
    private fun error(status: Int, message: String): MockResponse = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("message", message).toString())
}
