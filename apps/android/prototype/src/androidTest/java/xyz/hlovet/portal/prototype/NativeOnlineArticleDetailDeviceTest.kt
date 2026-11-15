package xyz.hlovet.portal.prototype

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 在设备内夹具验证在线文章详情的正文、评论、作者资料、订阅和专题合集入口，不访问生产环境。 */
@RunWith(AndroidJUnit4::class)
class NativeOnlineArticleDetailDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val subscriptionMethods = Collections.synchronizedList(mutableListOf<String>())
    private val articleRequests = Collections.synchronizedList(mutableListOf<String>())
    private val commentPageRequests = Collections.synchronizedList(mutableListOf<String>())
    private val focusCommentRequests = Collections.synchronizedList(mutableListOf<String>())
    private val interactionSettingsRequests = Collections.synchronizedList(mutableListOf<String>())
    private var authorProfileStatus = 200
    private var interactionSettingsStatus = 200
    private var interactionCommentsEnabled = true
    private var interactionReportsEnabled = true
    @Volatile private var interactionSettingsRelease: CountDownLatch? = null
    @Volatile private var interactionSettingsStarted = CountDownLatch(1)
    private var commentInitialFailureOnce = false
    private var commentInitialFailureReturned = false
    private var commentPaginationEnabled = false
    private var commentPaginationFailureOnce = false
    private var commentPaginationFailureReturned = false
    private var commentQuoteEnabled = false
    private var commentQuoteTargetId = 802
    private var commentQuoteAvailable = true
    private var commentQuoteFocusTargetAvailable = true
    private var commentQuoteTargetInInitialList = false
    private var oldLocale = "zh-CN"

    /** 只允许设备预览构建启动回环夹具，避免测试包把详情请求发往生产接口。 */
    @Before
    fun startFixture() {
        // 生产构建不替换地址，也不执行任何业务请求；不匹配时由测试框架跳过本类。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        // 每个详情用例都从干净的请求记录和夹具开关开始，避免分页状态污染订阅或作者资料回归。
        subscriptionMethods.clear()
        articleRequests.clear()
        commentPageRequests.clear()
        focusCommentRequests.clear()
        interactionSettingsRequests.clear()
        authorProfileStatus = 200
        interactionSettingsStatus = 200
        interactionCommentsEnabled = true
        interactionReportsEnabled = true
        interactionSettingsRelease = null
        interactionSettingsStarted = CountDownLatch(1)
        commentInitialFailureOnce = false
        commentInitialFailureReturned = false
        commentPaginationEnabled = false
        commentPaginationFailureOnce = false
        commentPaginationFailureReturned = false
        commentQuoteEnabled = false
        commentQuoteTargetId = 802
        commentQuoteAvailable = true
        commentQuoteFocusTargetAvailable = true
        commentQuoteTargetInInitialList = false
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
        compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comment-801"))
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

    /** 验证关闭评论只移除写入与回复入口，已有评论和独立举报功能仍可见。 */
    @Test
    fun disabledCommentsKeepReadingAndHideCommentComposerAndReply() {
        // 关闭评论但保留举报，确认两个站点开关不会互相覆盖。
        interactionCommentsEnabled = false
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

        // 详情正文和已有评论仍可阅读，评论输入和回复入口均须隐藏。
        waitForText("在线详情文章")
        waitForText("评论功能暂未开放。")
        compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comment-801"))
        compose.onNodeWithText("隔离评论", substring = false).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("article-comment-composer").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty())
        assertTrue(compose.onAllNodesWithTag("article-comment-reply-801").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty())

        // 举报仍开启，因此文章和评论举报入口保持可用。
        compose.onNodeWithTag("article-report-button").assertIsDisplayed()
        compose.onNodeWithTag("article-comment-report-801").assertIsDisplayed()
        assertEquals(1, synchronized(interactionSettingsRequests) { interactionSettingsRequests.size })
    }

    /** 验证关闭举报只隐藏文章和评论举报入口，不影响评论输入与回复。 */
    @Test
    fun disabledReportsKeepCommentingAndHideReportActions() {
        // 关闭举报但保留评论，确认两个独立开关分别控制自己的入口。
        interactionReportsEnabled = false
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

        // 评论编辑区正常显示，滚到已有评论后仍可回复，但任何举报入口都不应存在。
        waitForText("在线详情文章")
        waitForTag("article-comment-composer")
        compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comment-801"))
        compose.onNodeWithText("隔离评论", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("article-comment-reply-801").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("article-report-button").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty())
        assertTrue(compose.onAllNodesWithTag("article-comment-report-801").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty())
        assertEquals(1, synchronized(interactionSettingsRequests) { interactionSettingsRequests.size })
    }

    /** 验证站点设置暂时不可用时按开启降级，且不阻断正文与评论读取。 */
    @Test
    fun interactionSettingsFailureFallsBackToEnabledWithoutBlockingContent() {
        // 只让可选设置接口失败，文章、评论和作者接口仍按正常响应返回。
        interactionSettingsStatus = 503
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

        // 失败回退为兼容开启，正文、评论、评论输入及文章举报入口都不被可选设置接口阻断。
        waitForText("在线详情文章")
        waitForText("隔离评论")
        waitForTag("article-comment-composer")
        compose.onNodeWithTag("article-report-button").assertIsDisplayed()
        assertEquals(1, synchronized(interactionSettingsRequests) { interactionSettingsRequests.size })
    }

    /** 验证在线正文已加载但互动设置尚未返回时，评论和举报入口仍按开启状态呈现。 */
    @Test
    fun interactionSettingsLoadingKeepsOnlineActionsAvailable() {
        // 设置请求到达夹具后保持挂起，确保断言发生在 null 加载状态而非请求完成之后。
        val settingsGate = CountDownLatch(1)
        interactionSettingsRelease = settingsGate
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

        try {
            // 先等待可选设置请求进入夹具，再核验在线互动入口在结果未返回时已经可用。
            assertTrue("Interaction settings request did not start", interactionSettingsStarted.await(10, TimeUnit.SECONDS))
            waitForTag("article-comment-composer")
            compose.onNodeWithTag("article-report-button").assertIsDisplayed()
            compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comment-801"))
            compose.onNodeWithTag("article-comment-reply-801").assertIsDisplayed()
            compose.onNodeWithTag("article-comment-report-801").assertIsDisplayed()
        } finally {
            // 即使断言失败也释放夹具线程，避免遗留请求拖慢后续用例。
            settingsGate.countDown()
            interactionSettingsRelease = null
        }
    }

    /** 验证评论首屏失败时正文仍可见，重试只重新读取评论而不会重复请求文章。 */
    @Test
    fun initialCommentFailureKeepsArticleAndRetriesCommentsOnly() {
        // 仅让评论首屏第一次失败，模拟正文服务正常而评论服务短暂不可用。
        commentInitialFailureOnce = true
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

        // 文章响应成功后必须立即可见，评论失败只能显示评论区的独立错误入口。
        waitForText("在线详情文章")
        waitForText("评论加载失败，请重试。")
        // 错误入口位于懒列表后段，先滚入视口并确认启用，再按真实用户路径触发重试。
        compose.onNodeWithTag("article-comments-retry").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { synchronized(commentPageRequests) { commentPageRequests.size == 2 } }
        compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comment-801"))
        compose.onNodeWithText("隔离评论", substring = false).assertIsDisplayed()

        // 重试只增加一次评论请求；文章详情从始至终只请求一次。
        assertEquals(1, synchronized(articleRequests) { articleRequests.size })
        assertEquals(listOf("initial", "initial"), synchronized(commentPageRequests) { commentPageRequests.toList() })
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

    /** 验证评论分页入口进入可视区域后自动加载下一页，而不是只能依赖手动按钮。 */
    @Test
    fun commentsLoadNextPageWhenFooterEntersViewport() {
        // 为本用例开启两页夹具，首屏仍保留分页入口，第二页通过 cursor 区分。
        commentPaginationEnabled = true
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

        // 滚到稳定存在的首屏末条评论，使页尾进入视口；成功分页会移除页尾，因此不以它作为滚动目标。
        waitForText("在线详情文章")
        waitForText("隔离评论")
        compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comment-810"))
        waitForText("分页成功评论内容")

        // 第二页请求必须携带首屏返回的游标，且只发生一次，避免滚动状态重复触发请求。
        compose.waitUntil(5_000) { synchronized(commentPageRequests) { commentPageRequests.size == 2 } }
        assertEquals(listOf("initial", "10"), synchronized(commentPageRequests) { commentPageRequests.toList() })
    }

    /** 验证下一页失败只显示页尾重试提示，重试继续使用原游标并成功追加评论。 */
    @Test
    fun failedCommentPaginationStaysInFooterAndCanRetry() {
        // 令第二页首次返回 503，重试时恢复成功，以覆盖失败保留游标与恢复状态。
        commentPaginationEnabled = true
        commentPaginationFailureOnce = true
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

        // 滚动到分页入口触发第二页失败，页尾应提示用户手动重试。
        waitForText("在线详情文章")
        waitForText("隔离评论")
        compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comments-footer"))
        waitForText("加载失败，点击重试")

        // 服务端错误文案不能进入正文详情的通用错误区，首轮只请求 initial 与 10。
        assertTrue(
            "分页错误文案不应出现在详情通用错误区",
            compose.onAllNodesWithText("Fixture comment pagination failure", substring = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
        assertEquals(listOf("initial", "10"), synchronized(commentPageRequests) { commentPageRequests.toList() })

        // 点击页尾入口再次请求相同游标，成功后追加第二页评论并恢复正常提示。
        compose.onNodeWithTag("article-comments-footer").performClick()
        waitForText("分页成功评论内容")
        compose.waitUntil(5_000) { synchronized(commentPageRequests) { commentPageRequests.size == 3 } }
        assertEquals(listOf("initial", "10", "10"), synchronized(commentPageRequests) { commentPageRequests.toList() })
    }

    /** 验证引用目标不在当前评论列表时，会通过 focusId 加载目标并短暂高亮。 */
    @Test
    fun commentQuoteLoadsMissingTargetAndHighlightsIt() {
        // 首屏只返回引用评论，定位请求返回缺失的原评论，覆盖焦点加载链路。
        commentQuoteEnabled = true
        commentQuoteTargetId = 802
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

        // 先滚到引用卡片并点击，确保测试走真实的评论引用交互入口。
        waitForText("在线详情文章")
        compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comment-801"))
        compose.onNodeWithTag("article-comment-quote-802").performClick()

        // 目标评论加载后必须显示定位高亮，并且请求只携带一次目标 id。
        waitForLocatedComment(802)
        assertEquals(listOf("802"), synchronized(focusCommentRequests) { focusCommentRequests.toList() })
    }

    /** 验证引用目标已在当前列表时直接高亮，不重复发起 focusId 请求。 */
    @Test
    fun commentQuoteUsesLoadedTargetWithoutFocusRequest() {
        // 首屏同时返回引用评论和原评论，覆盖复用已加载数据的路径。
        commentQuoteEnabled = true
        commentQuoteTargetId = 802
        commentQuoteTargetInInitialList = true
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

        // 点击首屏引用后应直接更新目标行状态，不刷新评论列表。
        waitForText("在线详情文章")
        compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comment-801"))
        compose.onNodeWithTag("article-comment-quote-802").performClick()
        waitForLocatedComment(802)
        assertTrue(synchronized(focusCommentRequests) { focusCommentRequests.isEmpty() })
    }

    /** 验证引用目标已删除或不可见时显示提示，并阻止跳转到不存在的评论。 */
    @Test
    fun deletedCommentQuoteShowsNoticeAndDoesNotNavigate() {
        // 引用本身仍可点击，但定位接口不返回目标，模拟原评论随后被删除的情况。
        commentQuoteEnabled = true
        commentQuoteTargetId = 803
        commentQuoteAvailable = true
        commentQuoteFocusTargetAvailable = false
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

        // 定位响应不包含目标评论时必须显示明确提示，且页面中不能出现被删除的目标评论。
        waitForText("在线详情文章")
        compose.onNodeWithTag("article-detail-list").performScrollToNode(hasTestTag("article-comment-801"))
        compose.onNodeWithTag("article-comment-quote-803").performClick()
        waitForText("该评论已删除或当前不可见，无法定位。")
        assertTrue(
            compose.onAllNodesWithTag("article-comment-803")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
        assertEquals(listOf("803"), synchronized(focusCommentRequests) { focusCommentRequests.toList() })
    }

    /** 等待评论行出现定位状态，确认高亮不是仅凭节点存在而是实际状态已切换。 */
    private fun waitForLocatedComment(id: Int, timeoutMillis: Long = 15_000) {
        // 通过语义状态核对定位结果，避免把普通评论节点误判为已高亮。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag("article-comment-highlight-$id")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .any { node -> node.config.getOrElse(SemanticsProperties.StateDescription) { "" } == "已定位的评论" }
        }
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

    /** 等待指定测试标识进入 Compose 语义树，核对异步开关控制的入口。 */
    private fun waitForTag(tag: String, timeoutMillis: Long = 15_000) {
        // 互动设置通过独立请求异步加载，按语义节点等待避免依赖固定延时。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    /** 根据当前请求路径返回文章详情、站点开关、评论、作者资料和订阅响应。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 文章接口按是否携带会话区分公开和 visible 路径，但共享同一份隔离内容。
        val path = request.path.orEmpty()
        return when {
            path == "/site-settings/public" -> {
                interactionSettingsRequests += "${request.method.orEmpty()} $path"
                // 由回归用例主动释放设置响应，稳定覆盖异步设置尚未读取的窗口。
                interactionSettingsStarted.countDown()
                interactionSettingsRelease?.await(10, TimeUnit.SECONDS)
                if (interactionSettingsStatus == 200) {
                    json(
                        JSONObject()
                            .put("commentsEnabled", interactionCommentsEnabled)
                            .put("reportsEnabled", interactionReportsEnabled),
                    )
                } else {
                    error(interactionSettingsStatus, "Interaction settings fixture failure")
                }
            }
            path == "/articles/test-article" || path == "/articles/visible/test-article" -> {
                articleRequests += path
                json(articleJson())
            }
            path.startsWith("/articles/test-article/comments?") || path.startsWith("/articles/visible/test-article/comments?") -> commentsResponse(request)
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

    /** 构造评论首屏响应；分页用例返回足够长的列表，确保页尾需由滚动进入视口。 */
    private fun commentsJson(): JSONObject {
        // 分页或引用已加载目标场景需要额外评论，其余详情回归继续使用原有单条评论。
        val items = JSONArray()
        val itemCount = when {
            commentPaginationEnabled -> 10
            commentQuoteTargetInInitialList -> 2
            else -> 1
        }
        repeat(itemCount) { index ->
            val id = 801 + index
            val comment = JSONObject()
                    .put("id", id)
                    .put("articleId", 701)
                    .put("body", if (index == 0) "隔离评论" else "被引用的原评论")
                    .put("status", "active")
                    .put("likeCount", 0)
                    .put("liked", false)
                    .put("reported", false)
                    .put("author", JSONObject().put("id", 88 + index).put("username", "comment-author-$id").put("nickname", "评论作者"))
            // 只有首屏引用评论携带 quote，避免目标评论再次生成无关的引用入口。
            if (commentQuoteEnabled && index == 0) {
                comment.put(
                    "quote",
                    JSONObject()
                        .put("id", commentQuoteTargetId)
                        .put("body", "被引用的原评论")
                        .put("authorName", "原评论作者")
                        .put("available", commentQuoteAvailable),
                )
            }
            items.put(comment)
        }

        // 自动分页场景用 cursor=10 标记第二页；其它测试仍表现为单页列表。
        return JSONObject()
            .put("items", items)
            .put("hasMore", commentPaginationEnabled)
            .put("nextCursor", if (commentPaginationEnabled) 10 else JSONObject.NULL)
            .put("totalThreads", if (commentPaginationEnabled) 11 else 1)
    }

    /** 根据游标返回评论首屏或下一页，并记录请求顺序供分页回归核对。 */
    private fun commentsResponse(request: RecordedRequest): MockResponse {
        // 只记录详情评论请求，确保分页是否携带 cursor 能被测试直接核对。
        val cursor = request.requestUrl?.queryParameter("cursor")
        commentPageRequests += cursor ?: "initial"
        // 记录引用定位请求的目标 id，区分普通首屏与 focusId 加载路径。
        val focusId = request.requestUrl?.queryParameter("focusId")?.toIntOrNull()
        if (focusId != null) focusCommentRequests += focusId.toString()
        // 首屏故障只返回一次，重试时恢复成功以验证正文与评论的独立生命周期。
        if (cursor == null && commentInitialFailureOnce && !commentInitialFailureReturned) {
            commentInitialFailureReturned = true
            return error(503, "Fixture initial comment failure")
        }
        // 只让首次第二页请求失败，便于同一用例验证错误提示、游标保留和手动重试恢复。
        if (cursor == "10" && commentPaginationFailureOnce && !commentPaginationFailureReturned) {
            commentPaginationFailureReturned = true
            return error(503, "Fixture comment pagination failure")
        }
        // focusId 命中时只返回目标评论，模拟服务端将定位评论放入新的首屏。
        if (focusId == commentQuoteTargetId && commentQuoteFocusTargetAvailable) {
            return json(commentQuoteTargetJson())
        }
        return if (cursor == "10") json(commentsPageTwoJson()) else json(commentsJson())
    }

    /** 构造引用定位成功的评论响应，确保目标评论进入列表并触发高亮。 */
    private fun commentQuoteTargetJson(): JSONObject = JSONObject()
        .put(
            "items",
            JSONArray().put(
                JSONObject()
                    .put("id", commentQuoteTargetId)
                    .put("articleId", 701)
                    .put("body", "被引用的原评论")
                    .put("status", "active")
                    .put("likeCount", 0)
                    .put("liked", false)
                    .put("reported", false)
                    .put("author", JSONObject().put("id", 90).put("username", "original-comment-author").put("nickname", "原评论作者")),
            ),
        )
        .put("hasMore", false)
        .put("nextCursor", JSONObject.NULL)
        .put("totalThreads", 1)

    /** 构造评论分页第二页响应，确认新内容合并到原列表而非替换首屏。 */
    private fun commentsPageTwoJson(): JSONObject = JSONObject()
        .put("items", JSONArray().put(
            JSONObject()
                .put("id", 821)
                .put("articleId", 701)
                .put("body", "分页成功评论内容")
                .put("status", "active")
                .put("likeCount", 0)
                .put("liked", false)
                .put("reported", false)
                .put("author", JSONObject().put("id", 108).put("username", "comment-author-21").put("nickname", "分页作者")),
        ))
        .put("hasMore", false)
        .put("nextCursor", JSONObject.NULL)
        .put("totalThreads", 2)

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
