package xyz.hlovet.portal.prototype

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/** 在设备内夹具验证离线文章、专题和合集详情不等待网络超时。 */
@RunWith(AndroidJUnit4::class)
class NativeOfflineDetailDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"

    /** 只在设备内预览构建启动夹具，避免离线回归触碰生产 API。 */
    @Before
    fun startFixture() {
        // 生产地址变体必须跳过本地夹具，不能把无网络请求误报为真实接口验证。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 39871) }
    }

    /** 释放 Compose 页面和接口夹具，并恢复测试前语言。 */
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

    /** 验证离线文章直接显示快照，不发起文章或评论网络请求。 */
    @Test
    fun offlineArticleOpensWithoutWaitingForNetwork() {
        // 以离线模式挂载真实文章详情，正文来自传入的已保存快照。
        host.setContent {
            HlovetTheme {
                ArticleDetailScreen(
                    initialArticle = article(),
                    offlineOnly = true,
                    session = null,
                    onLogin = {},
                    onBack = {},
                    onOpenUser = {},
                )
            }
        }

        // 离线提示和正文都应在网络请求之前显示，证明页面没有等待在线失败回退。
        waitForText("当前处于离线阅读模式，评论和互动暂不可用。")
        compose.onNodeWithText("离线文章", substring = false).assertIsDisplayed()
        assertEquals(0, server.requestCount)
    }

    /** 验证离线专题详情直接显示收录文章，并且不会请求在线专题接口。 */
    @Test
    fun offlineTopicOpensWithoutWaitingForNetwork() {
        // 传入专题快照，文章点击仍由宿主接管，不会触发外部接口。
        host.setContent {
            HlovetTheme {
                DiscoveryDetailScreen(
                    target = DetailTarget.Topic(topic(), offlineOnly = true),
                    session = null,
                    onLogin = {},
                    onBack = {},
                    onArticleClick = {},
                    onContentChanged = {},
                )
            }
        }

        // 专题页应立即展示离线状态和收录文章，不进入等待或错误状态。
        waitForText("当前处于离线阅读模式，订阅和互动暂不可用。")
        compose.onNodeWithText("离线专题文章", substring = false).assertIsDisplayed()
        assertEquals(0, server.requestCount)
    }

    /** 等待指定文案进入 Compose 语义树，避免固定睡眠掩盖异步渲染问题。 */
    private fun waitForText(text: String, timeoutMillis: Long = 15_000) {
        // 离线模式不应该等待网络，但仍需要等待 Compose 完成本地状态提交。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    /** 构造包含正文的最小文章快照。 */
    private fun article(): RemoteArticle = RemoteArticle(
        id = 901,
        title = "离线文章",
        slug = "offline-article",
        summary = "离线测试摘要",
        content = "<p>离线正文</p>",
        contentFormat = "html",
        category = "测试",
        tags = listOf("offline"),
        viewCount = 0,
        likeCount = 0,
        favoriteCount = 0,
        commentCount = 0,
        authorName = "离线作者",
        authorUsername = "offline-author",
        authorId = 902,
    )

    /** 构造包含文章清单的最小专题快照。 */
    private fun topic(): RemoteTopic = RemoteTopic(
        id = 903,
        title = "离线专题",
        description = "离线专题描述",
        articleCount = 1,
        subscriberCount = 0,
        articles = listOf(article().copy(id = 904, title = "离线专题文章", slug = "offline-topic-article")),
        slug = "offline-topic",
    )
}
