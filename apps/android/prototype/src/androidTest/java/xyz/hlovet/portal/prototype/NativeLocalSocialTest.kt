package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实本地账号、MainActivity、Socket.IO 和数据库验收好友、私聊及权限边界，保留业务数据。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalSocialTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** 仅访问目标应用的私有报告目录和既有加密会话。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var reader: NativeSession
    private val peers = mutableListOf<NativeSession>()
    private var activity: ActivityScenario<MainActivity>? = null
    private var peerSocket: NativeChatSocket? = null
    private var oldLocale: String? = null
    private val received = LinkedBlockingQueue<NativeChatMessage>()
    private val checks = JSONArray()
    private val marker = "native_social_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    private val report = JSONObject().put("testDataRemoved", false).put("productionTested", false).put("completed", false)

    /** 核对本地来源与开放注册策略后刷新既有会话，不修改安全配置或登录其他既有账号。 */
    @Before fun requireLocalReader() {
        // 真实写入只能落在本地容器；注册与邮件策略不满足时不发送验证邮件。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val policy = NativeAuthApi.registrationPolicy()
        check(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled)
        val stored = requireNotNull(NativeSessionStore.load(context))
        reader = NativeAuthApi.refresh(stored.refreshToken)
        check(NativeSessionStore.save(context, reader))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("readerId", reader.userId).put("readerUsername", reader.username).put("marker", marker)
    }

    /** 关闭页面及辅助 Socket/会话，保留账号、好友、消息和通知，报告不记录任何凭据。 */
    @After fun finish() {
        // 辅助账号从未替换主应用会话；退出的只是本专项注册后得到的临时会话。
        activity?.close()
        peerSocket?.close()
        peers.forEach { peer -> runCatching { NativeAuthApi.logout(peer.refreshToken) } }
        if (::reader.isInitialized) {
            report.put("readerSessionPreserved", NativeSessionStore.load(context)?.userId == reader.userId)
            saveReport()
        }
        oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
    }

    /** 实际好友申请产生通知并建立会话，MainActivity 收发和搜索，最后检查非成员及拉黑边界。 */
    @Test fun realFriendshipSocketChatSearchAndAccessBoundaries() {
        // 辅助身份全部通过正常注册获得，好友关系的稳定标识是 friendshipId 而非昵称。
        val peer = registerPeer("peer")
        val outsider = registerPeer("outsider")
        val friendship = NativeFriendsApi.requestFriend(peer.accessToken, reader.userId, marker)
        assertTrue(NativeFriendsApi.listFriendships(reader.accessToken).incoming.any { it.id == friendship.id })
        assertTrue(NativeFriendsApi.listFriendships(peer.accessToken).outgoing.any { it.id == friendship.id })
        val notification = NativeSocialApi.listNotifications(reader.accessToken, limit = 50).items.single { it.friendshipId == friendship.id }
        assertEquals("friend_request_received", notification.type)
        assertTrue(notification.unread)
        assertEquals(403, status("/social/friendships/${friendship.id}/respond", peer.accessToken, "PATCH", JSONObject().put("status", "accepted")))
        NativeFriendsApi.respondFriendRequest(reader.accessToken, friendship.id, "accepted")
        assertTrue(NativeFriendsApi.listFriendships(reader.accessToken).friends.any { it.id == friendship.id })
        assertTrue(NativeFriendsApi.listFriendships(peer.accessToken).friends.any { it.id == friendship.id })
        assertFalse(NativeSocialApi.listNotifications(reader.accessToken, limit = 50).items.single { it.id == notification.id }.unread)
        val conversation = NativeFriendsApi.getOrCreateConversation(reader.accessToken, peer.userId)
        assertEquals(conversation.id, NativeFriendsApi.getOrCreateConversation(peer.accessToken, reader.userId).id)
        report.put("friendshipId", friendship.id).put("notificationId", notification.id).put("conversationId", conversation.id)
        record("real-friend-request-direction-notification-read-and-shared-conversation")

        // 对方通过实际 Socket.IO 连接，主账号经生产 MainActivity 深链进入真实聊天页面。
        connectPeer(peer)
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/messages?conversation=${conversation.id}")))
        waitFor(hasContentDescription("聊天已连接"))
        val ownBody = "$marker from-main-activity"
        compose.onNode(hasSetTextAction() and hasText("输入消息…")).performClick().performTextReplacement(ownBody)
        // 输入法展开后标题和连接状态仍需可见，不能由系统把整个页面平移到屏幕上方。
        assertKeyboardLayout()
        record("real-keyboard-keeps-header-composer-and-send-button-visible")
        screenshot("keyboard")
        compose.onNodeWithContentDescription("发送").performClick()
        val ownMessage = receive(ownBody)
        assertEquals(reader.username, ownMessage.senderUsername)
        assertEquals(ownBody, NativeSocialApi.listMessages(peer.accessToken, conversation.id).items.single { it.id == ownMessage.id }.body)
        waitFor(hasText(ownBody))
        report.put("mainMessageId", ownMessage.id)
        record("real-main-activity-send-broadcasts-to-peer-and-persists-on-server")

        // 在当前聊天中收到对方消息后应自动已读，不能只检查乐观插入的界面内容。
        val peerBody = "$marker from-peer-socket"
        assertTrue(send(conversation.id, peerBody))
        val peerMessage = receive(peerBody)
        waitFor(hasText(peerBody))
        assertKeyboardLayout()
        awaitRead(conversation.id)
        assertEquals(peerBody, NativeSocialApi.listMessages(reader.accessToken, conversation.id).items.single { it.id == peerMessage.id }.body)
        report.put("peerMessageId", peerMessage.id)
        record("real-peer-socket-message-arrives-in-ui-and-current-chat-marks-read")
        screenshot("messages")

        // 实际搜索图标、输入框和结果行完成定位，选择后搜索框应收起，消息仍在会话中可见。
        compose.onNodeWithContentDescription("搜索聊天内容").performClick()
        compose.onNode(hasSetTextAction() and hasText("搜索聊天内容")).performTextReplacement("from-main-activity")
        val result = hasText(ownBody) and hasClickAction() and isEnabled()
        waitFor(result)
        compose.onNode(result).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction() and hasText("搜索聊天内容")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(ownBody).assertIsDisplayed()
        assertEquals(listOf(ownMessage.id), NativeSocialApi.searchMessages(reader.accessToken, conversation.id, "from-main-activity").items.map { it.id })
        record("real-chat-search-result-click-restores-conversation-and-matches-api")
        screenshot("search-located")

        // 非参与者不能枚举、搜索或定位该私聊；不能把普通账号的有效登录等同于会话权限。
        for (path in listOf("/social/conversations/${conversation.id}/messages", "/social/conversations/${conversation.id}/messages/${ownMessage.id}/context")) {
            assertTrue("Nonparticipant must not read $path", status(path, outsider.accessToken) in listOf(403, 404))
        }
        // 搜索接口按成员关系过滤并返回空页，属于正常拒绝语义，不能强制改成异常状态码。
        val foreignSearch = NativeSocialApi.searchMessages(outsider.accessToken, conversation.id, "from-main-activity")
        assertTrue(foreignSearch.items.isEmpty())
        assertFalse(foreignSearch.hasMore)
        record("real-nonparticipant-denied-history-context-and-search")

        // 拉黑在已连接 Socket 上也必须立即生效，解除后需要重新同意好友，而不是自动恢复关系。
        NativeFriendsApi.blockFriendship(reader.accessToken, friendship.id)
        assertTrue(NativeFriendsApi.listFriendships(reader.accessToken).blocked.any { it.id == friendship.id })
        assertFalse(send(conversation.id, "$marker blocked-message-must-not-exist"))
        NativeFriendsApi.unblockFriendship(reader.accessToken, friendship.id)
        assertTrue(NativeFriendsApi.listFriendships(reader.accessToken).friends.none { it.id == friendship.id })
        val renewed = NativeFriendsApi.requestFriend(peer.accessToken, reader.userId, "$marker restored")
        NativeFriendsApi.respondFriendRequest(reader.accessToken, renewed.id, "accepted")
        assertEquals(conversation.id, NativeFriendsApi.getOrCreateConversation(reader.accessToken, peer.userId).id)
        assertFalse(NativeSocialApi.listMessages(reader.accessToken, conversation.id).items.any { it.body.endsWith("blocked-message-must-not-exist") })
        record("real-block-denies-existing-socket-and-unblock-requires-new-consent")
        report.put("completed", true)
        saveReport()
    }

    /** 注册 label 标记的独立普通用户，返回短期真实会话，凭据仅在测试内存中使用。 */
    private fun registerPeer(label: String): NativeSession {
        // 短昵称满足真实服务端字符限制，用户名用独立后缀避免重复运行冲突。
        val username = "social_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val peer = (NativeAuthApi.register(username, "Social $label", "$username@example.com", "Local!${UUID.randomUUID()}", null, null)
            as NativeAuthResult.Authenticated).session
        peers.add(peer)
        NativeDiscoveryApi.completeOnboarding(peer.accessToken, emptyList(), emptyList())
        report.put("peerAccounts", JSONArray(peers.map { JSONObject().put("id", it.userId).put("username", it.username) }))
        saveReport()
        return peer
    }

    /** 使用 peer 的真实访问令牌连接聊天网关，连接超时明确失败，不替换应用会话。 */
    private fun connectPeer(peer: NativeSession) {
        // 回调跨线程只写线程安全队列；不把服务端错误正文或凭据写入报告。
        val ready = CountDownLatch(1)
        peerSocket = NativeChatSocket(peer.accessToken, onMessage = { received.offer(it) },
            onRead = { _, _ -> }, onError = {},
            onConnectionChanged = { if (it) ready.countDown() })
        peerSocket!!.connect()
        check(ready.await(20, TimeUnit.SECONDS)) { "Real peer socket connection timed out" }
    }

    /** 等待 body 对应的真实 Socket 广播，返回服务端消息，不以本地回调模拟接收。 */
    private fun receive(body: String): NativeChatMessage {
        // 忽略同一会话的系统消息，等待目标消息时保留稳定的超时边界。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val message = received.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (message.body == body) return message
        }
        error("Expected real message broadcast did not arrive")
    }

    /** 向 conversationId 发送 body，等待服务端 ACK 并返回是否接受，不记录错误正文。 */
    private fun send(conversationId: Int, body: String): Boolean {
        // ACK 表示服务端完成鉴权和持久化，与仅调用 emit 不同。
        val done = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        peerSocket!!.send(conversationId, body) { ok, _ -> accepted.set(ok); done.countDown() }
        check(done.await(20, TimeUnit.SECONDS)) { "Real socket acknowledgement timed out" }
        return accepted.get()
    }

    /** 等待 conversationId 的真实未读数归零，不强制 HTTP 已读更新必须产生 Socket 广播。 */
    private fun awaitRead(conversationId: Int) {
        // 页面渲染与 HTTP 已读请求独立调度，低频回读持久化状态并设置明确超时。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val conversation = NativeSocialApi.listConversations(reader.accessToken).single { it.id == conversationId }
            if (conversation.unreadCount == 0) return
            Thread.sleep(250)
        }
        error("Current chat did not persist its read state")
    }

    /** 实测输入法可见时标题不被上推、输入框和发送按钮不在键盘下方，不能只依赖节点存在。 */
    private fun assertKeyboardLayout() {
        // 系统窗口给出键盘实际遮挡边界，语义坐标使用同一窗口坐标系，等待动画完成后的有效布局。
        compose.waitUntil(10_000) {
            var keyboardVisible = false
            var keyboardTop = 0
            activity!!.onActivity { host ->
                val decor = host.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor)
                keyboardVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                val visible = Rect()
                val origin = IntArray(2)
                decor.getWindowVisibleDisplayFrame(visible)
                decor.getLocationOnScreen(origin)
                keyboardTop = minOf(decor.height, visible.bottom - origin[1])
            }
            if (!keyboardVisible) return@waitUntil false
            val title = compose.onNodeWithText("在线聊天").fetchSemanticsNode().boundsInWindow
            val composer = compose.onNode(hasSetTextAction() and isEnabled()).fetchSemanticsNode().boundsInWindow
            val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInWindow
            title.height > 0 && composer.height > 0 && send.height > 0 && title.top >= 0 &&
                composer.top >= title.bottom && composer.bottom <= keyboardTop + 1 && send.bottom <= keyboardTop + 1
        }
        compose.onNodeWithText("在线聊天").assertIsDisplayed()
        compose.onNodeWithContentDescription("聊天已连接").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
    }

    /** 使用 bearer 请求 path，只返回状态码；method/body 用于验证真实角色和归属拒绝。 */
    private fun status(path: String, bearer: String, method: String = "GET", body: JSONObject? = null): Int {
        // 请求正文与响应不写日志，读取后释放连接以便连续权限检查。
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 12_000; connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer $bearer")
        return try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val result = connection.responseCode
            (if (result in 200..299) connection.inputStream else connection.errorStream)?.close()
            result
        } finally { connection.disconnect() }
    }

    /** 等待 matcher 对应的实际语义节点渲染，不用固定延时猜测接口完成。 */
    private fun waitFor(matcher: SemanticsMatcher) {
        // 页面和网络有独立调度，未渲染或未执行的节点不能被计为通过。
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 将通过的 check 追加到无凭据报告，失败后的步骤不会出现。 */
    private fun record(check: String) {
        // 报告保留业务标识与实际完成的检查点，便于从网页复查测试数据。
        checks.put(JSONObject().put("check", check).put("passed", true))
        saveReport()
    }

    /** 保存本专项的账号编号、消息编号和结论，不包含任何认证材料。 */
    private fun saveReport() {
        // 使用应用私有目录，避免受模拟器外部存储权限影响。
        File(context.filesDir, "native-local-social-report.json").writeText(report.put("checks", checks).toString(2))
    }

    /** 保存 name 标记的真实聊天页面截图，调用点不包含密码或一次性凭据。 */
    private fun screenshot(name: String) {
        // 截图用于复核实际布局，不替代 Socket、数据库和权限断言。
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "native-local-social-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
