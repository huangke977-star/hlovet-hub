package xyz.hlovet.portal.prototype

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** 在设备内回环夹具验证专题与合集详情、订阅、离线保存和文章入口，不访问生产接口。 */
@RunWith(AndroidJUnit4::class)
class NativeDiscoveryDetailDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private var collectionSubscriptionStatus = 200
    private var topicDetailStatus = 200
    private var oldLocale = "zh-CN"

    /** 只允许设备预览构建启动回环夹具，避免测试包向生产服务发起详情请求。 */
    @Before
    fun startFixture() {
        // 生产地址构建直接跳过本类，确保测试成功只代表隔离夹具验证。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        requests.clear()
        collectionSubscriptionStatus = 200
        topicDetailStatus = 200
        server = MockWebServer().apply {
            // 未声明的路径统一返回错误，尽早暴露页面新增的意外请求。
            dispatcher = object : Dispatcher() {
                /** 根据详情页实际请求路径返回对应隔离数据或订阅失败响应。 */
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 39871)
        }
    }

    /** 卸载页面并关闭夹具，避免异步回包污染后续设备用例。 */
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

    /** 验证在线专题详情会加载文章清单，并且订阅按钮可以完成订阅和取消订阅。 */
    @Test
    fun onlineTopicLoadsAndTogglesSubscription() {
        // 通过真实详情页面挂载专题目标，接口回包会覆盖导航快照中的占位标题。
        host.setContent {
            HlovetTheme {
                DiscoveryDetailScreen(
                    target = DetailTarget.Topic(topicSeed()),
                    session = session(),
                    onLogin = {},
                    onBack = {},
                    onArticleClick = {},
                    onContentChanged = {},
                )
            }
        }

        // 详情请求成功后应显示夹具标题和收录文章，而不是只显示初始占位内容。
        waitForText("在线专题详情")
        compose.onNodeWithTag("discovery-detail-article-1101").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("discovery-subscription-topic-1100").performClick()
        waitForRequest("POST /discovery/topics/1100/subscribe")
        waitForText("已订阅")

        // 再次点击必须使用当前订阅状态发送 DELETE，并恢复未订阅文案。
        compose.onNodeWithTag("discovery-subscription-topic-1100").performClick()
        waitForRequest("DELETE /discovery/topics/1100/subscribe")
        waitForText("订阅")
    }

    /** 验证在线合集详情会加载合集元数据，并将收录文章点击交给宿主导航。 */
    @Test
    fun onlineCollectionLoadsAndArticleClickBubblesToHost() {
        // 用线程安全变量记录页面回调，避免测试通过绕过 Compose 事件链直接调用导航逻辑。
        val clickedArticleId = AtomicInteger(0)
        host.setContent {
            HlovetTheme {
                DiscoveryDetailScreen(
                    target = DetailTarget.Collection(collectionSeed()),
                    session = session(),
                    onLogin = {},
                    onBack = {},
                    onArticleClick = { clickedArticleId.set(it.id) },
                    onContentChanged = {},
                )
            }
        }

        // 合集详情需要通过 visible 接口加载，标题和文章行同时证明接口解析成功。
        waitForText("在线合集详情")
        compose.onNodeWithTag("discovery-detail-article-1201").performScrollTo().performClick()
        compose.waitUntil(5_000) { clickedArticleId.get() == 1201 }
        assertEquals(1201, clickedArticleId.get())
        assertTrue(requests.contains("GET /discovery/collections/visible/1200"))
    }

    /** 验证合集订阅接口失败时页面保留正文并展示可理解的失败提示。 */
    @Test
    fun collectionSubscriptionFailureShowsMessageWithoutHidingContent() {
        // 只让订阅写请求失败，详情 GET 仍保持成功，区分操作错误和页面加载错误。
        collectionSubscriptionStatus = 503
        host.setContent {
            HlovetTheme {
                DiscoveryDetailScreen(
                    target = DetailTarget.Collection(collectionSeed()),
                    session = session(),
                    onLogin = {},
                    onBack = {},
                    onArticleClick = {},
                    onContentChanged = {},
                )
            }
        }

        // 页面正文应先可见，随后点击订阅并等待服务端错误进入操作提示区域。
        waitForText("在线合集详情")
        compose.onNodeWithTag("discovery-subscription-collection-1200").performClick()
        waitForText("合集订阅夹具失败")
        compose.onNodeWithTag("discovery-detail-article-1201").assertIsDisplayed()
    }

    /** 验证详情页保存离线后可以从 NativeOfflineContentStore 读回完整专题快照。 */
    @Test
    fun saveOfflineWritesTopicSnapshotThatCanBeReadBack() {
        // 采用独立 owner 和 slug，避免本机历史验收数据改变本用例的读取结果。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureSession = session("fixture-detail-save")
        val owner = NativeOfflineOwner.forSession(fixtureSession)
        host.setContent {
            HlovetTheme {
                DiscoveryDetailScreen(
                    target = DetailTarget.Topic(topicSeed()),
                    session = fixtureSession,
                    onLogin = {},
                    onBack = {},
                    onArticleClick = {},
                    onContentChanged = {},
                )
            }
        }

        // 等待线上快照加载完成后点击顶部保存按钮，验证页面实际调用离线存储而非只改变图标。
        waitForText("在线专题详情")
        compose.onNodeWithTag("discovery-save-offline").performClick()
        compose.waitUntil(15_000) {
            NativeOfflineContentStore.loadTopic(context, owner, "isolated-topic")?.title == "在线专题详情"
        }
        assertEquals("在线专题文章", NativeOfflineContentStore.loadTopic(context, owner, "isolated-topic")?.articles?.single()?.title)
    }

    /** 验证网络失败时详情页显示已保存专题快照，而不是丢失文章内容。 */
    @Test
    fun networkFailureFallsBackToSavedTopicSnapshot() {
        // 先写入本地快照，再让详情 GET 返回失败，覆盖线上刷新失败的真实降级路径。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 缓存所有者必须与详情页根据稳定 userId 计算出的账号边界一致，不能使用仅测试可见的用户名键。
        val owner = NativeOfflineOwner.forSession(session())
        val cached = topicSeed().copy(title = "已保存专题快照", articles = listOf(articleSeed(1199, "已保存文章")))
        NativeOfflineContentStore.saveTopic(context, owner, cached)
        topicDetailStatus = 503
        host.setContent {
            HlovetTheme {
                DiscoveryDetailScreen(
                    target = DetailTarget.Topic(topicSeed(), offlineOnly = false),
                    session = session(owner),
                    onLogin = {},
                    onBack = {},
                    onArticleClick = {},
                    onContentChanged = {},
                )
            }
        }

        // 夹具对该请求返回 500，页面仍应展示缓存标题、文章和离线回退提示。
        waitForText("已保存专题快照")
        waitForText("当前网络不可用，正在显示已保存的内容。订阅和互动暂不可用。")
        compose.onNodeWithTag("discovery-detail-article-1199").assertIsDisplayed()
        assertTrue(requests.contains("GET /discovery/topics/visible/isolated-topic"))
    }

    /** 验证离线模式中的订阅按钮不可用，且不会向服务器发送订阅请求。 */
    @Test
    fun offlineTopicDisablesSubscriptionWithoutRequest() {
        // 离线目标直接使用传入快照，按钮状态应与离线提示保持一致。
        host.setContent {
            HlovetTheme {
                DiscoveryDetailScreen(
                    target = DetailTarget.Topic(topicSeed(), offlineOnly = true),
                    session = session(),
                    onLogin = {},
                    onBack = {},
                    onArticleClick = {},
                    onContentChanged = {},
                )
            }
        }

        // 等待离线文案后核对按钮真的禁用，避免测试只证明没有主动触发点击。
        waitForText("当前处于离线阅读模式，订阅和互动暂不可用。")
        compose.onNodeWithTag("discovery-subscription-topic-1100").assertIsNotEnabled()
        assertEquals(0, server.requestCount)
    }

    /** 等待指定文案进入 Compose 语义树，避免用固定睡眠掩盖异步接口问题。 */
    private fun waitForText(text: String, timeoutMillis: Long = 15_000) {
        // 详情请求和订阅操作都在 IO 协程中完成，按语义节点等待实际 UI 状态。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text, substring = false)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    /** 等待指定方法和路径进入请求记录，确认点击触发了真实网络动作。 */
    private fun waitForRequest(expected: String, timeoutMillis: Long = 15_000) {
        // 请求记录只保存方法和路径，不保存 token 等敏感信息。
        compose.waitUntil(timeoutMillis) { synchronized(requests) { requests.contains(expected) } }
    }

    /** 根据当前请求路径返回详情数据、订阅结果或故障响应。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 只为本轮详情页面声明的路径返回成功，未知请求应立即暴露页面调用链变化。
        val method = request.method.orEmpty()
        val path = request.path.orEmpty()
        val record = "$method $path"
        requests += record
        return when {
            path == "/discovery/topics/visible/isolated-topic" && method == "GET" && topicDetailStatus != 200 ->
                error(topicDetailStatus, "详情夹具不可用")
            path == "/discovery/topics/visible/isolated-topic" && method == "GET" -> json(topicJson())
            path == "/discovery/collections/visible/1200" && method == "GET" -> json(collectionJson())
            path == "/discovery/topics/1100/subscribe" && method == "POST" -> json(subscriptionJson(true, 8))
            path == "/discovery/topics/1100/subscribe" && method == "DELETE" -> json(subscriptionJson(false, 7))
            path == "/discovery/collections/1200/subscribe" && method == "POST" && collectionSubscriptionStatus != 200 -> error(collectionSubscriptionStatus, "合集订阅夹具失败")
            path == "/discovery/collections/1200/subscribe" && method == "POST" -> json(subscriptionJson(true, 6))
            else -> error(404, "Unexpected fixture request: $record")
        }
    }

    /** 构造在线专题详情响应，文章清单与页面测试标签保持稳定。 */
    private fun topicJson(): JSONObject = JSONObject()
        .put("id", 1100)
        .put("title", "在线专题详情")
        .put("slug", "isolated-topic")
        .put("description", "专题详情隔离测试")
        .put("articleCount", 1)
        .put("subscriberCount", 7)
        .put("subscribed", false)
        .put("articles", JSONArray().put(articleJson(1101, "在线专题文章")))

    /** 构造在线合集详情响应，合集使用独立 ID 走 visible 接口。 */
    private fun collectionJson(): JSONObject = JSONObject()
        .put("id", 1200)
        .put("name", "在线合集详情")
        .put("description", "合集详情隔离测试")
        .put("articleCount", 1)
        .put("subscriberCount", 5)
        .put("subscribed", false)
        .put("owner", JSONObject().put("username", "fixture-owner").put("nickname", "隔离作者"))
        .put("articles", JSONArray().put(articleJson(1201, "在线合集文章")))

    /** 构造最小文章 JSON，满足 NativeDiscoveryApi 的作者和正文解析约束。 */
    private fun articleJson(id: Int, title: String): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("slug", "fixture-article-$id")
        .put("summary", "详情文章测试摘要")
        .put("content", "详情文章测试正文")
        .put("contentFormat", "markdown")
        .put("category", "测试")
        .put("tags", JSONArray().put("detail"))
        .put("viewCount", 0)
        .put("likeCount", 0)
        .put("favoriteCount", 0)
        .put("commentCount", 0)
        .put("author", JSONObject().put("id", 1300).put("username", "fixture-author").put("nickname", "隔离作者"))
        .put("status", "published")
        .put("visibility", "public")

    /** 构造订阅接口返回，页面据此更新按钮文案和订阅人数。 */
    private fun subscriptionJson(subscribed: Boolean, count: Int): JSONObject = JSONObject()
        .put("subscribed", subscribed)
        .put("subscriberCount", count)

    /** 构造离线专题目标，导航快照和在线响应共用稳定 slug。 */
    private fun topicSeed(): RemoteTopic = RemoteTopic(
        id = 1100,
        title = "专题占位",
        description = "占位专题",
        articleCount = 1,
        subscriberCount = 7,
        articles = listOf(articleSeed(1101, "占位文章")),
        slug = "isolated-topic",
    )

    /** 构造离线合集目标，保证详情页面可以先渲染导航快照再等待在线接口。 */
    private fun collectionSeed(): RemoteCollection = RemoteCollection(
        id = 1200,
        name = "合集占位",
        description = "占位合集",
        articleCount = 1,
        subscriberCount = 5,
        ownerName = "隔离作者",
        articles = listOf(articleSeed(1201, "占位文章")),
    )

    /** 构造测试文章对象，供导航快照和离线存储回读使用。 */
    private fun articleSeed(id: Int, title: String): RemoteArticle = RemoteArticle(
        id = id,
        title = title,
        slug = "fixture-article-$id",
        summary = "详情文章测试摘要",
        content = "详情文章测试正文",
        contentFormat = "markdown",
        category = "测试",
        tags = listOf("detail"),
        viewCount = 0,
        likeCount = 0,
        favoriteCount = 0,
        commentCount = 0,
        authorName = "隔离作者",
        authorUsername = "fixture-author",
        authorId = 1300,
    )

    /** 构造使用回环地址的最小登录会话，令详情接口走 visible 路径。 */
    private fun session(owner: String = "fixture-reader"): NativeSession = NativeSession(
        accessToken = "fixture-access",
        refreshToken = "fixture-refresh",
        userId = 99,
        username = owner,
        nickname = "Fixture reader",
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
    )

    /** 将 JSON 夹具包装为成功响应，并统一声明内容类型。 */
    private fun json(body: JSONObject): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body.toString())

    /** 将夹具错误包装为与真实接口一致的 JSON 错误响应。 */
    private fun error(status: Int, message: String): MockResponse = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("message", message).toString())
}
