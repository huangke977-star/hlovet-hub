package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** 在真实本地 MainActivity 验证提及候选、旧通知拦截及历史消息锚点；账号和业务记录全部保留。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalMentionNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** 使用目标应用上下文读取既有会话与写入非敏感验收报告。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var reader: NativeSession
    private lateinit var peer: NativeSession
    private lateinit var socket: NativeChatSocket
    private lateinit var conversation: NativeConversationPreview
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private val messages = LinkedBlockingQueue<NativeChatMessage>()
    private val checks = JSONArray()
    private val marker = "mention_${UUID.randomUUID().toString().replace("-", "").take(10)}"
    private val report = JSONObject().put("completed", false).put("productionTested", false).put("testDataRemoved", false)

    /** 限制本地地址后刷新主账号，注册独立普通辅助身份并合法建立好友会话。 */
    @Before fun prepareLocalAccounts() {
        // 不依赖固定测试账号，不修改线上策略、数据库卷或设备登录身份。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val policy = NativeAuthApi.registrationPolicy()
        check(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled)
        reader = NativeAuthApi.refresh(requireNotNull(NativeSessionStore.load(context)).refreshToken)
        check(NativeSessionStore.save(context, reader))
        val username = "mn_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        peer = (NativeAuthApi.register(username, "Mention test", "$username@example.com", "Local!${UUID.randomUUID()}", null, null) as NativeAuthResult.Authenticated).session
        NativeDiscoveryApi.completeOnboarding(peer.accessToken, emptyList(), emptyList())
        val friendship = NativeFriendsApi.requestFriend(peer.accessToken, reader.userId, marker)
        NativeFriendsApi.respondFriendRequest(reader.accessToken, friendship.id, "accepted")
        conversation = NativeFriendsApi.getOrCreateConversation(reader.accessToken, peer.userId)
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        // 辅助 Socket 只准备真实服务端消息及删除事件，所有跳转和候选选择由 MainActivity 验收。
        val connected = CountDownLatch(1)
        socket = NativeChatSocket(peer.accessToken, onMessage = { messages.offer(it) }, onRead = { _, _ -> }, onError = {},
            onConnectionChanged = { if (it) connected.countDown() })
        socket.connect()
        check(connected.await(20, TimeUnit.SECONDS))
        report.put("marker", marker).put("readerId", reader.userId).put("peerId", peer.userId).put("peerUsername", peer.username)
            .put("conversationId", conversation.id).put("friendshipId", friendship.id)
        saveReport()
    }

    /** 关闭辅助连接并恢复语言，保留主账号会话、测试账号和所有测试业务数据。 */
    @After fun finish() {
        // 失败也保存已完成检查，不能把未运行或失败步骤写成通过。
        if (activity != null) screenshot("final")
        activity?.close()
        if (::socket.isInitialized) socket.close()
        if (::peer.isInitialized) runCatching { NativeAuthApi.logout(peer.refreshToken) }
        if (::reader.isInitialized) report.put("sessionPreserved", NativeSessionStore.load(context)?.userId == reader.userId)
        saveReport()
        oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
    }

    /** 检查候选范围和实际选择，历史加载锚点，新消息不抢位置，以及聊天/评论旧通知删除拦截。 */
    @Test fun realMentionsDeletedTargetsAndHistoryAnchor() {
        // 私聊只能提及对端，空关键词和单字符都合法；评论仍使用站点可搜索范围。
        val candidates = NativeFriendsApi.searchUsers(reader.accessToken, "", 20, mention = true, conversationId = conversation.id)
        assertEquals(listOf(peer.userId), candidates.map { it.user.id })
        assertEquals(listOf(peer.userId), NativeFriendsApi.searchUsers(reader.accessToken, "m", 20, mention = true, conversationId = conversation.id).map { it.user.id })
        assertTrue(NativeFriendsApi.searchUsers(reader.accessToken, "", 8, mention = true).isNotEmpty())
        openRoute("/messages?conversation=${conversation.id}")
        waitFor(hasContentDescription("聊天已连接"))
        composer().performClick()
        composer().performTextReplacement("@")
        waitFor(hasText("@${peer.username}"))
        compose.onNode(hasText("@${peer.username}")).performClick()
        assertEquals("@${peer.username} ", composer().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        composer().performTextClearance()
        record("empty-and-single-character-mention-candidates-are-conversation-scoped-and-selectable")
        screenshot("mention-selected")

        // 显式复用前序双设备保留群，核对候选来自有效成员；新注册的非成员不能枚举群成员。
        val groupId = requireNotNull(InstrumentationRegistry.getArguments().getString("mentionGroupId")).toInt()
        val group = NativeGroupApi.get(reader.accessToken, groupId)
        val expectedMembers = group.members.filter { it.status == "active" && it.user.id != reader.userId }.map { it.user.id }.sorted()
        val groupCandidates = NativeFriendsApi.searchUsers(reader.accessToken, "", 20, mention = true, conversationId = group.summary.conversationId)
        assertEquals(expectedMembers, groupCandidates.map { it.user.id }.sorted())
        assertFalse(groupCandidates.any { it.user.id == peer.userId })
        assertTrue(runCatching { NativeFriendsApi.searchUsers(peer.accessToken, "", 20, mention = true, conversationId = group.summary.conversationId) }.isFailure)
        report.put("existingGroupId", groupId).put("groupCandidateIds", JSONArray(expectedMembers))
        record("group-mention-candidates-match-active-members-and-reject-outsider-enumeration")

        // 超过一页的历史按网关限流逐条准备，不关闭限流；正文宽度不同以覆盖可变行高。
        val history = (1..35).map { index ->
            val message = send("$marker history-$index" + if (index % 3 == 0) " multiple lines of retained historical content ".repeat(4) else "")
            Thread.sleep(1_000)
            message
        }
        openRoute("/messages?conversation=${conversation.id}")
        waitFor(hasContentDescription("聊天已连接"))
        waitFor(hasTestTag("chat-message-${history.last().id}"))
        val latest = NativeSocialApi.listMessages(reader.accessToken, conversation.id)
        assertTrue(latest.hasMore)
        compose.onNodeWithTag("chat-messages").performScrollToIndex(0)
        val anchor = "chat-message-${latest.items.first().id}"
        compose.onNodeWithTag(anchor).assertIsDisplayed()
        val before = compose.onNodeWithTag(anchor).fetchSemanticsNode().boundsInWindow.top
        screenshot("anchor-before")
        compose.onNodeWithTag("chat-load-older").performClick()
        Thread.sleep(1_200)
        compose.waitForIdle()
        compose.onNodeWithTag(anchor).assertIsDisplayed()
        val after = compose.onNodeWithTag(anchor).fetchSemanticsNode().boundsInWindow.top
        assertTrue("Loading the final historical page must preserve the visible message pixel position: $before -> $after", abs(before - after) <= 2f)
        report.put("historyAnchorMessageId", latest.items.first().id).put("anchorBeforeY", before).put("anchorAfterY", after)
        screenshot("anchor-after")
        record("history-prepend-preserves-message-pixel-anchor-even-when-load-button-disappears")

        // 对端新消息在阅读旧记录时只追加；界面不应跟随到底部或改变现有锚点。
        val incoming = send("$marker incoming-while-reading-history")
        Thread.sleep(1_500)
        compose.waitForIdle()
        val afterIncoming = compose.onNodeWithTag(anchor).fetchSemanticsNode().boundsInWindow.top
        assertTrue(abs(after - afterIncoming) <= 2f)
        compose.onNodeWithTag("chat-message-${incoming.id}").assertDoesNotExist()
        report.put("anchorAfterIncomingY", afterIncoming)
        screenshot("history-incoming")
        record("incoming-message-does-not-steal-history-reading-position")

        // 存活聊天通知从真实消息列表点击后定位整行；同一通知的系统 Intent 也必须正常打开。
        val live = send("@${reader.username} $marker live-chat")
        val liveNotification = notificationFor(live.id)
        openRoute("/messages")
        clickNotification(liveNotification.id)
        assertChatLocated(live.id)
        openNotification(liveNotification.id)
        assertChatLocated(live.id)
        report.put("liveMessageId", live.id).put("liveNotificationId", liveNotification.id)
        record("live-chat-notification-opens-and-highlights-from-inbox-and-system-intent")

        // 先让页面缓存活通知，再在服务端删除；点击旧节点必须刷新状态并停留在消息页。
        val removed = send("@${reader.username} $marker removed-chat")
        val stale = notificationFor(removed.id)
        openRoute("/messages")
        revealNotification(stale.id)
        mutate("chat:messages:delete-everyone", JSONObject().put("conversationId", conversation.id).put("messageIds", JSONArray().put(removed.id)))
        assertTrue(NativeSocialApi.markNotificationRead(reader.accessToken, stale.id).messageDeleted)
        assertEquals(404, (runCatching { NativeSocialApi.getMessageContext(reader.accessToken, conversation.id, removed.id) }.exceptionOrNull() as NativeSocialHttpException).statusCode)
        assertDeletionNotice { clickNotification(stale.id) }
        compose.onNodeWithContentDescription("搜索聊天内容").assertDoesNotExist()
        screenshot("cached-deleted-notification")
        openNotification(stale.id, deleted = true)
        openRoute("/messages?conversation=${conversation.id}&messageId=${removed.id}", deleted = true)
        report.put("deletedMessageId", removed.id).put("deletedNotificationId", stale.id)
        record("cached-deleted-chat-notification-system-intent-and-direct-link-block-navigation")

        // 仅当前账号隐藏也应拦截；对端仍可读取，不能误实现成全局删除。
        val hidden = send("@${reader.username} $marker hidden-for-reader")
        val hiddenNotification = notificationFor(hidden.id)
        val readerConnected = CountDownLatch(1)
        val readerSocket = NativeChatSocket(reader.accessToken, onMessage = {}, onRead = { _, _ -> }, onError = {},
            onConnectionChanged = { if (it) readerConnected.countDown() })
        try {
            readerSocket.connect()
            check(readerConnected.await(20, TimeUnit.SECONDS))
            mutate("chat:messages:delete-self", JSONObject().put("conversationId", conversation.id).put("messageIds", JSONArray().put(hidden.id)), readerSocket)
        } finally { readerSocket.close() }
        assertEquals(hidden.id, NativeSocialApi.getMessageContext(peer.accessToken, conversation.id, hidden.id).id)
        assertEquals(404, (runCatching { NativeSocialApi.getMessageContext(reader.accessToken, conversation.id, hidden.id) }.exceptionOrNull() as NativeSocialHttpException).statusCode)
        openNotification(hiddenNotification.id, deleted = true)
        report.put("selfHiddenMessageId", hidden.id).put("selfHiddenNotificationId", hiddenNotification.id)
        record("self-hidden-message-blocks-reader-navigation-but-remains-visible-to-peer")

        verifyCommentNotifications()
        report.put("completed", true)
        saveReport()
    }

    /** 单独重跑真实评论通知链路，报告明确限定范围，不复用或冒充整套历史消息验收。 */
    @Test fun realCommentNotificationNavigation() {
        // 仍通过普通辅助账号准备公开文章和评论，避免再次生成与本专项无关的历史消息。
        report.put("scope", "comment-notifications-only")
        verifyCommentNotifications()
        report.put("completed", true)
        saveReport()
    }

    /** 交替重开真实聊天和评论系统通知，检查入口消费不会取消尚未完成的目标加载。 */
    @Test fun repeatedSystemNotificationsKeepNavigationOwnedUntilComplete() {
        // 使用同一账号下真实且保持存活的两个目标，重复启动不依赖前一页面缓存。
        report.put("scope", "repeated-system-notification-navigation")
        val live = send("@${reader.username} $marker repeated-live-chat")
        val chatNotice = notificationFor(live.id)
        val category = NativeSiteSettingsApi.loadWriterSettings().categories.first().name
        val article = NativeArticleApi.createArticle(peer.accessToken, NativeDraftInput("$marker repeated notification", "Notification ownership regression", "<p>$marker</p>", "html", category, ""))
        val published = NativeArticleApi.publish(peer.accessToken, article.id)
        val comment = NativeArticleApi.createComment(peer.accessToken, article.id, "@${reader.username} $marker repeated-live-comment")
        val commentNotice = NativeSocialApi.listNotifications(reader.accessToken, limit = 50).items.single { it.commentId == comment.id && it.type == "mention_received" }
        report.put("liveMessageId", live.id).put("liveNotificationId", chatNotice.id).put("articleId", article.id)
            .put("articleSlug", published.slug).put("liveCommentId", comment.id).put("commentNotificationId", commentNotice.id)
        saveReport()

        // 每次销毁上一 Activity 后重新走系统 Intent，验证真实目标可见，不以 API 可读代替跳转成功。
        repeat(3) { index ->
            report.put("stage", "chat-${index + 1}")
            saveReport()
            openNotification(chatNotice.id)
            assertChatLocated(live.id)
            record("system-chat-notification-${index + 1}-located-and-highlight-cleared")
            report.put("stage", "comment-${index + 1}")
            saveReport()
            openNotification(commentNotice.id)
            waitFor(hasTestTag("article-comment-${comment.id}"))
            compose.onNodeWithTag("article-comment-${comment.id}").assertIsDisplayed()
            screenshot("repeated-comment-${index + 1}")
            record("system-comment-notification-${index + 1}-opened-real-target")
        }
        report.put("completed", true).put("stage", "completed")
        saveReport()
    }

    /** 在真实文章页验证空 @、光标中间插入、评论发布及服务端通知，保留生成记录。 */
    @Test fun realCommentComposerMentions() {
        // 普通辅助账号创建公开文章；主账号必须通过实际评论输入框完成发送。
        report.put("scope", "comment-composer-mentions")
        val category = NativeSiteSettingsApi.loadWriterSettings().categories.first().name
        val article = NativeArticleApi.createArticle(peer.accessToken, NativeDraftInput("$marker composer", "Shared mention composer", "<p>$marker</p>", "html", category, ""))
        val published = NativeArticleApi.publish(peer.accessToken, article.id)
        report.put("articleId", article.id).put("articleSlug", published.slug)
        openRoute("/articles/${published.slug}")
        waitFor(hasText("$marker composer"))
        waitFor(hasScrollToIndexAction())
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasSetTextAction())
        composer().performClick()
        composer().performTextInput("@")
        waitFor(hasTestTag("mention-suggestions"))
        val allowed = NativeFriendsApi.searchUsers(reader.accessToken, "", 20, mention = true)
        assertTrue(allowed.isNotEmpty())
        // 菜单先显示搜索状态，必须等真实候选渲染完成，不能把加载提示当成首项已就绪。
        waitFor(hasTestTag("mention-option-${allowed.first().user.id}"))
        compose.onNodeWithTag("mention-option-${allowed.first().user.id}").assertIsSelected()
        assertCommentPopupVisible(allowed.first().user.username)
        record("comment-at-sign-displays-allowed-candidates-with-first-option-selected")
        screenshot("comment-composer-candidates")

        // 精确搜索辅助账号并插入到正文中间，检查候选选择没有丢失原有后缀。
        composer().performTextReplacement("prefix @${peer.username} suffix")
        val cursor = "prefix @${peer.username}".length
        composer().performSemanticsAction(SemanticsActions.SetSelection) { it(cursor, cursor, false) }
        waitFor(hasTestTag("mention-option-${peer.userId}"))
        compose.onNodeWithTag("mention-option-${peer.userId}").performClick()
        val expected = "prefix @${peer.username}  suffix"
        assertEquals(expected, composer().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composer().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().layoutInput.text.spanStyles.size)
        record("comment-selection-retains-suffix-and-renders-confirmed-token")
        screenshot("comment-composer-selected")

        // 实际点击发布后核对持久化正文和收件人通知，而不是直接调用发评论接口。
        compose.onNodeWithContentDescription("发布评论").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.EditableText)?.text == ""
        }
        // 输入成功后键盘和列表会重新测量，先回到稳定的顶部，再等待实际成功提示。
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        waitFor(hasText("评论已发布。"))
        val comment = NativeArticleApi.listComments(reader.accessToken, published.slug).items.single { it.body == expected }
        val notification = NativeSocialApi.listNotifications(peer.accessToken, limit = 50).items.single { it.commentId == comment.id && it.type == "mention_received" }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("article-comment-${comment.id}"))
        compose.onNodeWithTag("article-comment-${comment.id}").assertIsDisplayed()
        report.put("commentId", comment.id).put("commentBody", comment.body).put("notificationId", notification.id)
        record("native-comment-publish-persists-text-and-creates-real-mention-notification")
        screenshot("comment-composer-published")
        report.put("completed", true)
        saveReport()
    }

    /** 验证存活评论可打开、删除后各入口拦截；失败时保留实际目标与系统提示供排查。 */
    private fun verifyCommentNotifications() {
        // 评论目标使用正常发布文章和真实 @ 评论，不能仅用伪造删除标志测试分支。
        val category = NativeSiteSettingsApi.loadWriterSettings().categories.first().name
        val article = NativeArticleApi.createArticle(peer.accessToken, NativeDraftInput("$marker retained article", "Mention navigation regression", "<p>$marker</p>", "html", category, ""))
        val published = NativeArticleApi.publish(peer.accessToken, article.id)
        val comment = NativeArticleApi.createComment(peer.accessToken, article.id, "@${reader.username} $marker live-comment")
        val commentNotification = NativeSocialApi.listNotifications(reader.accessToken, limit = 50).items.single { it.commentId == comment.id && it.type == "mention_received" }
        report.put("articleId", article.id).put("articleSlug", published.slug).put("liveCommentId", comment.id)
            .put("commentNotificationId", commentNotification.id).put("notificationArticleSlug", commentNotification.articleSlug)
            .put("notificationCommentId", commentNotification.commentId).put("notificationActionUrl", commentNotification.actionUrl)
        saveReport()
        assertFalse(NativeSocialApi.markNotificationRead(reader.accessToken, commentNotification.id).commentDeleted)
        // 先确认同一读取账号能实时读到文章和评论，区分权限错误、通知解析错误与界面跳转问题。
        assertEquals(article.id, NativeArticleApi.getArticle(reader.accessToken, published.slug).id)
        assertTrue(NativeArticleApi.listComments(reader.accessToken, published.slug, focusId = comment.id).items.any { it.id == comment.id && it.status == "active" })
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.packageName?.toString() == context.packageName) {
                report.put("commentOpenNotice", event.text.joinToString(" "))
            }
        }
        try {
            openNotification(commentNotification.id)
            waitFor(hasTestTag("article-comment-${comment.id}"))
        } finally {
            // 诊断提示只属于本次评论打开，不污染后续删除 Toast 的独立断言。
            automation.setOnAccessibilityEventListener(null)
            saveReport()
        }
        compose.onNodeWithTag("article-comment-${comment.id}").assertIsDisplayed()
        screenshot("live-comment")
        openRoute("/messages")
        revealNotification(commentNotification.id)
        NativeArticleApi.deleteComment(peer.accessToken, comment.id)
        assertTrue(NativeSocialApi.markNotificationRead(reader.accessToken, commentNotification.id).commentDeleted)
        assertDeletionNotice { clickNotification(commentNotification.id) }
        openNotification(commentNotification.id, deleted = true)
        openRoute("/articles/${published.slug}?commentId=${comment.id}", deleted = true)
        report.put("articleId", article.id).put("articleSlug", published.slug).put("deletedCommentId", comment.id).put("commentNotificationId", commentNotification.id)
        record("live-comment-opens-but-deleted-comment-blocks-inbox-system-intent-and-direct-link")
        saveReport()
    }

    /** 返回当前实际聊天输入框，不把同文案的消息或候选项当作输入框。 */
    private fun composer(): SemanticsNodeInteraction {
        // 连接可先于历史页渲染完成；等待真正可编辑节点，且不能依赖输入后就会消失的 placeholder。
        val matcher = hasSetTextAction() and isEnabled()
        waitFor(matcher)
        return compose.onNode(matcher)
    }

    /** 按 username 找到真实候选，核对评论输入和菜单都在键盘上方且彼此不重叠。 */
    private fun assertCommentPopupVisible(username: String) {
        // 页面内语义节点可能在键盘背后仍被判为可见，必须比较独立窗口的屏幕边界。
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var geometry = "No accessibility bounds observed"
        try {
            compose.waitUntil(10_000) {
                val field = device.findObject(By.clazz("android.widget.EditText"))
                val candidate = device.findObject(By.textContains("@$username"))
                if (field == null || candidate == null) {
                    geometry = "fieldFound=${field != null}, candidateFound=${candidate != null}"
                    return@waitUntil false
                }
                var popupRoot = candidate
                while (true) {
                    val parent = popupRoot.parent ?: break
                    popupRoot = parent
                }
                val fieldBounds = field.visibleBounds
                val popupBounds = popupRoot.visibleBounds
                // 键盘包里还包含浮动小视图，必须使用窗口系统提供的 IME 边距，不能取任意同包节点。
                var keyboardTop: Int? = null
                activity?.onActivity { current ->
                    val decor = current.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(decor)
                    if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                        val origin = IntArray(2)
                        decor.getLocationOnScreen(origin)
                        keyboardTop = origin[1] + decor.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    }
                }
                val gap = if (popupBounds.bottom <= fieldBounds.top) fieldBounds.top - popupBounds.bottom
                    else if (popupBounds.top >= fieldBounds.bottom) popupBounds.top - fieldBounds.bottom else -1
                geometry = "field=$fieldBounds, popup=$popupBounds, keyboardTop=$keyboardTop, gap=$gap"
                // 允许评论容器的正常内边距，但不能接受输入框或菜单被键盘遮挡。
                gap in 0..48 && fieldBounds.height() > 0 &&
                    keyboardTop?.let { fieldBounds.bottom <= it && popupBounds.bottom <= it } == true
            }
        } catch (failure: ComposeTimeoutException) {
            // 失败保存真实屏幕，不能为满足断言而隐藏系统键盘或移动测试窗口。
            report.put("commentPopupGeometry", geometry)
            screenshot("comment-composer-position-failure")
            saveReport()
            throw AssertionError("Comment popup is not visible next to its editor: $geometry", failure)
        }
        // 成功坐标也随业务报告保留，供宿主机截图复核。
        report.put("commentPopupGeometry", geometry)
        saveReport()
    }

    /** 通过真实辅助 Socket 发送 body 并等待 ACK 和广播，返回持久化后的消息。 */
    private fun send(body: String): NativeChatMessage {
        // 不以本地乐观消息替代服务端确认；队列忽略历史和系统事件。
        val done = CountDownLatch(1)
        var accepted = false
        socket.send(conversation.id, body) { ok, _ -> accepted = ok; done.countDown() }
        check(done.await(20, TimeUnit.SECONDS) && accepted)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val item = messages.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (item.body == body) return item
        }
        error("Expected real message broadcast did not arrive")
    }

    /** 用 instance 在真实网关执行 event/payload 删除操作，只接受服务端成功 ACK。 */
    private fun mutate(event: String, payload: JSONObject, instance: NativeChatSocket = socket) {
        // 删除属于本专项业务夹具，不删除账号、文章或整个会话历史。
        val done = CountDownLatch(1)
        var accepted = false
        instance.mutate(event, payload) { ok, _ -> accepted = ok; done.countDown() }
        check(done.await(20, TimeUnit.SECONDS) && accepted)
    }

    /** 读取 messageId 生成的真实提及通知，以稳定 ID 而不是通知文字定位。 */
    private fun notificationFor(messageId: Int): NativeNotificationPreview {
        // 通知与消息在同一事务保存，广播到达后应可立即读取。
        return NativeSocialApi.listNotifications(reader.accessToken, limit = 50).items.single { it.messageId == messageId && it.type == "mention_received" }
    }

    /** 显式启动 path 对应原生页面；deleted 表示同时断言删除提示及禁止进入聊天。 */
    private fun openRoute(path: String, deleted: Boolean = false) {
        // 仅启动本应用组件，不调用外部浏览器或未经验证的隐式系统路由。
        activity?.close()
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + path))
        if (deleted) assertDeletionNotice { activity = ActivityScenario.launch(intent) } else activity = ActivityScenario.launch(intent)
    }

    /** 用真实系统通知 action 和 notificationId 启动，覆盖通知离开最近列表后的按 ID 读取链路。 */
    private fun openNotification(notificationId: Int, deleted: Boolean = false) {
        // 系统 Intent 不附带删除状态，必须由服务端实时响应识别。
        activity?.close()
        val intent = Intent(context, MainActivity::class.java).setAction(NativeLocalNotifications.ACTION_OPEN_NOTIFICATION)
            .putExtra(NativeLocalNotifications.EXTRA_NOTIFICATION_ID, notificationId)
        if (deleted) assertDeletionNotice { activity = ActivityScenario.launch(intent) } else activity = ActivityScenario.launch(intent)
    }

    /** 滚动到 notificationId 的当前通知节点并执行真实点击回调。 */
    private fun clickNotification(notificationId: Int) {
        // 部分通知在会话卡片下方，使用可滚动祖先定位，而不是屏幕绝对坐标。
        revealNotification(notificationId)
        compose.onNodeWithTag("notification-$notificationId").performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    /** 将 notificationId 所在的惰性列表项滚入屏幕，再验证通知实际可见。 */
    private fun revealNotification(notificationId: Int) {
        // 消息页会话较多时通知区尚未组合，不能在滚动之前等待不存在于语义树中的子节点。
        val matcher = hasTestTag("notification-$notificationId")
        if (compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()) {
            // 已显示的缓存通知直接复用当前节点，此时顶部刷新按钮可能已被惰性列表移出组合树。
            compose.onNode(matcher).performScrollTo().assertIsDisplayed()
            return
        }
        waitFor(hasScrollToIndexAction())
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        waitFor(hasContentDescription("刷新消息") and isEnabled())
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        compose.onNodeWithTag("notification-$notificationId").assertIsDisplayed()
    }

    /** 捕获 action 后系统实际显示的删除 Toast，不以 API 标志代替用户可见反馈。 */
    private fun assertDeletionNotice(action: () -> Unit) {
        // 监听真实 Toast 的同时推进 Compose 时钟；阻塞式系统等待会让冷启动的重组与登录恢复停在测试调度器中。
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val observed = AtomicBoolean(false)
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.packageName?.toString() == context.packageName &&
                event.text.any { it.toString().contains("已删除") }) {
                observed.set(true)
            }
        }
        try {
            action()
            compose.waitUntil(30_000) { observed.get() }
        } finally {
            // 单次检查结束即移除监听，避免后续操作复用上一条 Toast 造成假阳性。
            automation.setOnAccessibilityEventListener(null)
        }
        // 确认用户收到提示后仍未进入聊天页，而不只检查接口状态或 Toast 文字。
        compose.onNodeWithContentDescription("搜索聊天内容").assertDoesNotExist()
        report.put("deletionNoticeCount", report.optInt("deletionNoticeCount") + 1)
        saveReport()
    }

    /** 核对 messageId 的整行高亮在真实定位后出现并在两秒后清除。 */
    private fun assertChatLocated(messageId: Int) {
        // 高亮语义属于消息行，不接受置顶摘要或搜索下拉框命中。
        val located = hasTestTag("chat-message-$messageId") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已定位的消息")
        waitFor(located)
        compose.onNode(located).assertIsDisplayed()
        screenshot("live-chat-highlight")
        compose.waitUntil(10_000) { compose.onAllNodes(located).fetchSemanticsNodes().isEmpty() }
    }

    /** 有限等待 matcher 对应节点进入真实 Compose 树。 */
    private fun waitFor(matcher: SemanticsMatcher) {
        // 网络与界面异步，超时明确失败而不是忽略本检查。
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 将通过的 check 立即写入报告，保留失败前的进度。 */
    private fun record(check: String) {
        // 记录业务步骤和标识，不记录密码、会话令牌或认证材料。
        checks.put(JSONObject().put("check", check).put("passed", true))
        saveReport()
    }

    /** 保存本次非敏感验证结果，供宿主机和用户核对。 */
    private fun saveReport() {
        // 固定私有文件名允许 ADB run-as 提取，但不暴露主账号凭据。
        File(context.filesDir, "native-local-mention-navigation-report.json").writeText(report.put("checks", checks).toString(2))
    }

    /** 保存 name 对应当前原生页面截图，避开任何密码或密钥输入过程。 */
    private fun screenshot(name: String) {
        // 截图验证实际位置和样式，接口报告单独保存。
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "native-mention-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
