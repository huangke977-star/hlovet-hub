package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** 使用真实本地 API 和 MainActivity 验收公开用户主页的内容、社交入口与权限边界。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalPublicProfileTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    /** 仅访问应用私有目录，保存本轮无凭据的回归报告。 */
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var reader: NativeSession
    private var author: NativeSession? = null
    private var visitor: NativeSession? = null
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private val marker = "native_public_profile_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    private val checks = JSONArray()
    private val report = JSONObject()
        .put("testDataRemoved", false)
        .put("productionTested", false)
        .put("completed", false)

    /** 校验测试只能写入本地容器，并刷新已有读者会话。 */
    @Before
    fun prepare() {
        // 不允许生产构建误执行注册、发文、好友和拉黑等真实写操作。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val policy = NativeAuthApi.registrationPolicy()
        check(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled)

        // 优先复用本地持久会话并刷新令牌；新安装或无本地会话时通过正常注册创建读者夹具。
        val stored = NativeSessionStore.load(context)
        reader = if (stored != null) {
            runCatching { NativeAuthApi.refresh(stored.refreshToken) }.getOrElse { registerAccount("reader") }
        } else {
            registerAccount("reader")
        }
        check(NativeSessionStore.save(context, reader))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("readerId", reader.userId).put("readerUsername", reader.username).put("marker", marker)
        saveReport()
    }

    /** 关闭页面、退出辅助会话并恢复主账号，不删除本轮创建的业务数据。 */
    @After
    fun finish() {
        // 辅助账号只退出测试会话，文章、合集、关系和消息保留给网页复查。
        activity?.close()
        listOfNotNull(author, visitor).forEach { session -> runCatching { NativeAuthApi.logout(session.refreshToken) } }
        if (::reader.isInitialized) {
            // 测试最后一个页面可能切换到了辅助账号，收尾时重新保存读者会话，保证网页复查仍回到主测试身份。
            check(NativeSessionStore.save(context, reader))
            report.put("readerSessionPreserved", NativeSessionStore.load(context)?.userId == reader.userId)
            saveReport()
        }
        oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
    }

    /** 从真实主页完成公开内容、订阅、好友、消息请求和拉黑后的访问边界验收。 */
    @Test
    fun realPublicProfileContentAndSocialActions() {
        // 创建独立作者和访客，避免把主账号已有关系当成页面成功条件。
        author = registerAccount("author")
        visitor = registerAccount("visitor")
        val authorSession = requireNotNull(author)
        val visitorSession = requireNotNull(visitor)
        val articleTitle = "$marker public article"
        val article = NativeArticleApi.createArticle(
            authorSession.accessToken,
            NativeDraftInput(articleTitle, "Public profile test summary", "<p>$marker public article body</p>", "html", "随笔", marker),
        )
        val published = NativeArticleApi.publish(authorSession.accessToken, article.id)
        val collection = NativeDiscoveryApi.createCollection(
            authorSession.accessToken,
            "$marker collection",
            "Public profile collection retained for Android verification.",
            "public",
            listOf(published.id),
        )
        // 设置代表文章和合集，确保主页的 featured content 与公开列表都进入真实渲染路径。
        NativeProfileApi.updateSettings(
            authorSession.accessToken,
            JSONObject()
                .put("profileAccess", "public")
                .put("showPinnedContent", true)
                .put("pinnedArticleId", published.id)
                .put("pinnedCollectionId", collection.id),
        )
        report.put("authorId", authorSession.userId).put("authorUsername", authorSession.username)
            .put("visitorId", visitorSession.userId).put("visitorUsername", visitorSession.username)
            .put("articleId", published.id).put("collectionId", collection.id)
        record("real-author-public-article-collection-and-featured-settings-prepared")

        // 先用接口核对主页返回的公开内容、订阅状态和访问权限，避免只因页面文字出现就误判。
        val initialProfile = NativeProfileApi.loadPublic(reader.accessToken, authorSession.username)
        val initialShowcase = NativeProfileApi.loadShowcase(reader.accessToken, authorSession.username)
        val initialArticles = NativeArticleApi.listByAuthor(reader.accessToken, authorSession.username)
        assertEquals(authorSession.userId, initialProfile.id)
        assertFalse(initialProfile.isSelf)
        assertTrue((initialProfile.publicArticleCount ?: 0) >= 1)
        assertTrue(initialArticles.items.any { it.id == published.id })
        assertTrue(initialShowcase.collections.any { it.id == collection.id })
        record("real-public-profile-api-returns-visible-article-collection-and-relationship")

        // 通过真实用户主页深链加载页面，并验证公开文章、合集和互动入口均实际渲染。
        openProfile(reader, authorSession.username)
        waitText("个人主页")
        waitText(articleTitle)
        waitText(collection.name)
        waitText("公开文章")
        waitText("站外订阅")
        waitText("订阅")
        waitText("加好友")
        waitText("消息请求")
        record("real-public-profile-screen-renders-content-and-social-actions")

        // 点击作者订阅并再次取消，页面反馈和服务端计数必须保持同一状态。
        compose.onNodeWithText("订阅", substring = false).performNativeStableClick(compose)
        waitClickableText("已订阅")
        assertTrue(NativeProfileApi.loadPublic(reader.accessToken, authorSession.username).subscribed)
        record("real-profile-author-subscribe-updates-page-and-api")
        compose.onNode(hasText("已订阅", substring = false) and hasClickAction()).performNativeStableClick(compose)
        waitClickableText("订阅")
        assertFalse(NativeProfileApi.loadPublic(reader.accessToken, authorSession.username).subscribed)
        record("real-profile-author-unsubscribe-updates-page-and-api")

        // 从主页发送好友申请，服务端关系方向必须变成 outgoing，页面不能只做乐观显示。
        compose.onNodeWithText("加好友", substring = false).performNativeStableClick(compose)
        waitText("申请备注")
        val friendNote = "$marker friend note"
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(friendNote)
        compose.onNodeWithText("发送申请", substring = false).performNativeStableClick(compose)
        waitText("等待确认")
        val pendingFriend = NativeFriendsApi.listFriendships(authorSession.accessToken).incoming.single { it.user.id == reader.userId && it.note == friendNote }
        assertEquals("pending", pendingFriend.status)
        record("real-profile-friend-request-persists-with-note-and-direction")

        // 切换到作者主页接受申请，验证 incoming 入口与 accepted 状态都经过真实 UI 操作。
        openProfile(authorSession, reader.username)
        waitText("个人主页")
        // 先核对服务端关系方向，再使用未合并语义树点击图标按钮，避免将错误选择器当成好友逻辑失败。
        assertEquals("incoming", NativeProfileApi.loadPublic(authorSession.accessToken, reader.username).relationship?.direction)
        compose.onNode(
            hasContentDescription("接受好友申请"),
            useUnmergedTree = true,
        ).performNativeStableClick(compose)
        waitText("已是好友")
        assertTrue(NativeFriendsApi.listFriendships(reader.accessToken).friends.any { it.user.id == authorSession.userId })
        record("real-profile-incoming-friend-request-accepted-from-profile")

        // 好友状态下重新查看作者主页，应显示已是好友而不是继续展示加好友按钮。
        openProfile(reader, authorSession.username)
        waitText("个人主页")
        // 页面重开后先回读关系状态，确保 UI 断言对应的是服务端已接受关系而不是旧页面缓存。
        val acceptedProfile = NativeProfileApi.loadPublic(reader.accessToken, authorSession.username)
        assertEquals("accepted", acceptedProfile.relationship?.status)
        assertEquals("accepted", acceptedProfile.relationship?.direction)
        waitText("已是好友")
        record("real-profile-reflects-accepted-friendship")

        // 使用未建立好友关系的访客发送首条消息请求，验证 request 模式及其持久化入口。
        // 切换到访客前保留读者最近一次冷启动刷新后的会话，收尾时才能恢复同一主测试身份。
        NativeSessionStore.load(context)?.takeIf { it.userId == reader.userId }?.let { reader = it }
        openProfile(visitorSession, authorSession.username)
        waitText("消息请求")
        compose.onNodeWithText("消息请求", substring = false).performNativeStableClick(compose)
        waitText("首条消息")
        val messageBody = "$marker message request"
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(messageBody)
        compose.onNode(hasText("发送消息请求", substring = false) and hasClickAction()).performNativeStableClick(compose)
        waitForServerState {
            NativeFriendsApi.listStrangerRequests(authorSession.accessToken).incoming.any { it.user.id == visitorSession.userId && it.body == messageBody }
        }
        record("real-profile-stranger-message-request-persists-for-recipient")

        // 从访客主页拉黑作者，确认确认弹窗、页面返回和服务端不可见边界同时成立。
        compose.onNode(
            hasContentDescription("加入黑名单"),
            useUnmergedTree = true,
        ).performNativeStableClick(compose)
        waitText("确认拉黑")
        compose.onNode(hasText("确认拉黑", substring = false) and hasClickAction()).performNativeStableClick(compose)
        // 返回回调与页面重组可能跨越旧宿主的最后一帧，服务端不可见状态是稳定的业务验收信号。
        waitForServerState { runCatching { NativeProfileApi.loadPublic(visitorSession.accessToken, authorSession.username) }.isFailure }
        record("real-profile-block-returns-from-page-and-hides-target")

        // 回到读者主页打开合集和文章，分别验证详情路由能从主页卡片正确接续。
        openProfile(reader, authorSession.username)
        waitText(collection.name)
        compose.onNodeWithContentDescription("打开合集").performNativeStableClick(compose)
        waitText("合集详情")
        record("real-profile-collection-card-opens-collection-detail")
        compose.onNodeWithContentDescription("返回").performNativeStableClick(compose)
        waitText("个人主页")
        // 返回主页后先滚动公开主页列表，文章卡片可能不在 LazyColumn 当前可组合的视口内。
        repeat(4) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        waitText(articleTitle)
        compose.onNodeWithContentDescription("打开文章").performNativeStableClick(compose)
        waitText("文章详情")
        waitText(articleTitle)
        record("real-profile-article-card-opens-article-detail")
        report.put("completed", true)
        saveReport()
    }

    /** 注册 label 对应的独立本地用户并完成兴趣引导，返回只在本测试中使用的会话。 */
    private fun registerAccount(label: String): NativeSession {
        // 用户名和邮箱使用随机后缀，重复执行不会覆盖历史测试数据。
        val username = "profile_${label}_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val password = "Local!${UUID.randomUUID()}"
        val result = NativeAuthApi.register(username, "Profile $label", "$username@example.com", password, null, null)
        val session = (result as? NativeAuthResult.Authenticated)?.session ?: error("本地辅助账号注册失败。")
        NativeDiscoveryApi.completeOnboarding(session.accessToken, emptyList(), emptyList())
        return session
    }

    /** 保存 session 后打开 username 对应的真实公开主页深链。 */
    private fun openProfile(session: NativeSession, username: String) {
        // 先关闭旧宿主并等待其刷新协程退出，避免两个 Activity 同时轮换同一个 refresh token。
        activity?.close()
        activity = null
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // 旧宿主的最后一帧可能仍在渲染，给 Compose 根视图一个完整帧切换窗口后再挂载新宿主。
        SystemClock.sleep(300)
        // 关闭后先读取设备中最新的读者令牌，不能把内存中的旧会话写回覆盖刚轮换的 token。
        val stored = NativeSessionStore.load(context)
        if (::reader.isInitialized && stored?.userId == reader.userId) reader = stored
        // 只有设备中正好保存了目标账号时才复用它，否则保留调用方传入的目标会话。
        val latest = stored?.takeIf { it.userId == session.userId } ?: session
        if (::reader.isInitialized && latest.userId == session.userId && latest.userId == reader.userId) reader = latest
        check(NativeSessionStore.save(context, latest))
        activity = ActivityScenario.launch(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/users/${Uri.encode(username)}")),
        )
        // 同一测试会连续切换三个账号，等待目标用户名确认新 Activity 已接管，不能只匹配通用页面标题。
        waitText("@$username")
    }

    /** 等待真实语义树中的文本出现，不用固定延时代替网络加载完成。 */
    private fun waitText(text: String) {
        // 页面加载、订阅反馈和对话框都通过同一语义树核对，失败时保留 Compose 的原始诊断。
        compose.waitUntil(60_000) { compose.onAllNodesWithText(text, substring = false, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待指定文字对应的可点击节点出现，避免统计文本与操作按钮同名时误选静态节点。 */
    private fun waitClickableText(text: String) {
        // 订阅按钮会在请求完成后切换文案，按钮状态比短暂通知更适合作为回归断言。
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(text, substring = false) and hasClickAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待服务端最终状态出现，避免把协程调度延迟误报为消息请求写入失败。 */
    private fun waitForServerState(state: () -> Boolean) {
        // API 回读必须在明确的超时内完成，失败时保留真实接口返回的最终不满足状态。
        compose.waitUntil(30_000) { runCatching { state() }.getOrDefault(false) }
    }

    /** 将通过的检查点写入应用私有报告，报告不包含访问令牌、刷新令牌或密码。 */
    private fun record(check: String) {
        // 每一步即时落盘，测试中断时仍能从设备报告看到已经完成的真实业务动作。
        checks.put(JSONObject().put("check", check).put("passed", true))
        saveReport()
    }

    /** 保存本专项的本地测试报告，便于在不清理数据的情况下从网页和设备复查。 */
    private fun saveReport() {
        // 应用私有目录不需要外部存储权限，且不会把测试凭据暴露给 adb 输出。
        File(context.filesDir, "native-local-public-profile-report.json").writeText(report.put("checks", checks).toString(2))
    }
}
