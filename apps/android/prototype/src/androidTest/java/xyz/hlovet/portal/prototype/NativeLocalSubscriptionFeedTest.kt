package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用本地真实 API 验证订阅动态分页、已读状态和订阅管理详情入口。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalSubscriptionFeedTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    /** 只在本地测试目录保存不含令牌的报告和截图。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var reader: NativeSession? = null
    private var author: NativeSession? = null
    private var originalSession: NativeSession? = null
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("apiOrigin", BuildConfig.API_BASE_URL).put("testDataRemoved", false)

    /** 验证来源和本地读者会话，禁止该专项误触生产 API。 */
    @Before
    fun requireLocal() {
        // 只有本地 API 与本地门户同时匹配时才允许创建文章和订阅关系。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        originalSession = NativeSessionStore.load(context)
        // 优先复用主会话专项读者；没有合适会话时注册隔离读者，保证专项可独立执行。
        val stored = originalSession?.takeIf { it.username.startsWith("native_restore_") }
        reader = if (stored != null) {
            NativeAuthApi.refresh(stored.refreshToken)
        } else {
            val suffix = UUID.randomUUID().toString().take(8)
            val result = NativeAuthApi.register(
                "subscription_reader_$suffix",
                "Reader $suffix",
                "subscription_reader_$suffix@example.invalid",
                "Local!${UUID.randomUUID()}",
                null,
                null,
            )
            (result as? NativeAuthResult.Authenticated)?.session
                ?: error("Local subscription reader registration did not authenticate")
        }
        check(NativeSessionStore.save(context, requireNotNull(reader)))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 关闭页面、恢复读者会话并保留所有测试数据与失败现场。 */
    @After
    fun restore() {
        // 报告在失败时也落盘，不通过删除数据掩盖未完成的检查。
        try {
            screenshot("last-state")
            File(context.filesDir, "native-local-subscription-feed-report.json").writeText(report.put("checks", checks).toString(2))
        } finally {
            scenario?.close()
            scenario = null
            author?.let { runCatching { NativeAuthApi.logout(it.refreshToken) } }
            // 测试没有覆盖原有会话时恢复原记录；无原会话则清理本地测试读者凭据但保留服务端测试数据。
            if (originalSession == null) {
                NativeSessionStore.clear(context)
            } else if (originalSession?.username?.startsWith("native_restore_") == true) {
                reader?.let { NativeSessionStore.save(context, it) }
            } else {
                NativeSessionStore.save(context, requireNotNull(originalSession))
            }
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 创建超过一页的真实订阅内容，验证自动加载、去重、已读和合集详情返回。 */
    @Test
    fun subscriptionFeedLoadsNextPageAndOpensSubscribedCollection() {
        // 先创建独立作者及 13 篇公开文章，让服务端分页必然产生第二页。
        val suffix = UUID.randomUUID().toString().take(8)
        val marker = "native_subscription_$suffix"
        val username = "subscription_$suffix"
        author = (NativeAuthApi.register(username, username, "$username@example.invalid", "Local!${UUID.randomUUID()}", null, null) as NativeAuthResult.Authenticated).session
        val authorSession = requireNotNull(author)
        NativeDiscoveryApi.completeOnboarding(authorSession.accessToken, emptyList(), emptyList())
        val articles = (1..13).map { number ->
            // 每篇文章保持最小公开正文，避免测试把时间消耗在无关附件和富文本链路。
            val draft = NativeArticleApi.createArticle(
                authorSession.accessToken,
                NativeDraftInput("$marker-$number", "Subscription feed fixture", "<p>$marker-$number</p>", "html", "随笔", ""),
            )
            NativeArticleApi.publish(authorSession.accessToken, draft.id)
        }
        val collectionName = "$marker-collection"
        val collection = NativeDiscoveryApi.createCollection(
            authorSession.accessToken,
            collectionName,
            "Subscription detail fixture",
            "public",
            articles.take(1).map { it.id },
        )
        val readerSession = requireNotNull(reader)

        // 订阅作者和合集后，服务端应把同一文章合并为一个动态项。
        NativeFriendsApi.setAuthorSubscription(readerSession.accessToken, authorSession.userId, true)
        NativeDiscoveryApi.setCollectionSubscription(readerSession.accessToken, collection.id, true)
        val firstPage = NativeDiscoveryApi.listSubscriptionFeed(readerSession.accessToken, page = 1, pageSize = 12, sort = "latest")
        val secondPage = NativeDiscoveryApi.listSubscriptionFeed(readerSession.accessToken, page = 2, pageSize = 12, sort = "latest")
        val expectedIds = articles.map { it.id }.toSet()
        val loadedIds = (firstPage.items + secondPage.items).map { it.article.id }
        assertTrue(firstPage.totalPages >= 2)
        assertTrue(secondPage.items.isNotEmpty())
        assertTrue(expectedIds.all { it in loadedIds })
        assertEquals(loadedIds.size, loadedIds.distinct().size)
        report.put("marker", marker).put("readerId", readerSession.userId).put("authorId", authorSession.userId)
            .put("collectionId", collection.id).put("articleIds", JSONArray(articles.map { it.id }))
        record("real-api-pagination-and-author-collection-deduplication")

        // 进入订阅页并打开设置，专题/合集名称应是可点击的详情入口。
        open(readerSession)
        waitText("我的订阅")
        compose.onNodeWithContentDescription("订阅管理").performClick()
        waitText(collectionName)
        compose.onNode(hasText(collectionName, substring = false) and hasClickAction()).performClick()
        waitText("合集详情")
        waitText(collectionName)
        record("real-ui-opens-subscribed-collection-detail")

        // 返回订阅页后滚到第一页末尾，等待第二页文章自动追加而不是点击分页按钮。
        compose.onNodeWithContentDescription("返回").performClick()
        waitText("我的订阅")
        val lastFirstPageTitle = firstPage.items.last().article.title
        val firstSecondPageTitle = secondPage.items.first().article.title
        compose.onNodeWithTag("subscription-feed-list")
            .performScrollToNode(hasText(lastFirstPageTitle, substring = false))
        waitText(firstSecondPageTitle)
        record("real-ui-auto-loads-next-page-at-list-end")

        // 点击未读文章应先写入已读，再进入文章详情；服务端状态用于最终断言。
        val unreadItem = firstPage.items.firstOrNull { it.readAt == null } ?: secondPage.items.first()
        compose.onNodeWithTag("subscription-feed-list")
            .performScrollToNode(hasText(unreadItem.article.title, substring = false))
        compose.onNodeWithText(unreadItem.article.title, substring = false).performClick()
        waitText("文章详情")
        compose.waitUntil(15_000) {
            NativeDiscoveryApi.listSubscriptionFeed(readerSession.accessToken, 1, 12, "latest").items
                .firstOrNull { it.article.id == unreadItem.article.id }?.readAt != null
        }
        record("real-ui-read-marks-before-opening-article")
    }

    /** 保存当前读者会话并打开订阅动态深链。 */
    private fun open(session: NativeSession) {
        // 每次切换身份都重新创建 Activity，避免旧页面协程覆盖当前会话。
        check(NativeSessionStore.save(context, session))
        scenario = ActivityScenario.launch(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/articles/subscriptions")),
        )
    }

    /** 等待语义树中的业务文字出现，避免固定睡眠掩盖加载竞态。 */
    private fun waitText(value: String, timeoutMillis: Long = 30_000) {
        // 目标文本可能在分页或详情请求完成后才进入 Compose 语义树，等待期间不主动滚动列表。
        try {
            compose.waitUntil(timeoutMillis) {
                compose.onAllNodesWithText(value, substring = false)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            }
        } catch (failure: Throwable) {
            // 失败报告带上当前目标，便于区分排序差异、接口数据缺失和页面加载竞态。
            val semantics = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrDefault("语义树读取失败")
            throw AssertionError("等待页面文字失败: $value\n${semantics.take(4_000)}", failure)
        }
    }

    /** 将通过的检查追加到报告。 */
    private fun record(name: String) {
        // 只记录业务检查名称，不保存访问令牌、密码或文章正文。
        checks.put(JSONObject().put("name", name).put("passed", true))
    }

    /** 截取当前窗口作为失败后的人类可读证据。 */
    private fun screenshot(name: String) {
        // 等待系统和 Compose 空闲后再截图，避免记录半渲染状态。
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1_000)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(context.filesDir, "native-local-subscription-feed-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
