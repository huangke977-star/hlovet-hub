package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 两台设备分别运行 host/peer，通过真实 MainActivity 互聊，不以辅助 Socket 冒充第二个原生客户端。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalDualDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** 读取当前设备的测试上下文，不跨设备传递会话或任何认证材料。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var session: NativeSession
    private lateinit var peer: NativeSocialUser
    private lateinit var marker: String
    private var host = false
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("completed", false).put("productionTested", false).put("testDataRemoved", false)

    /** 校验本地来源、配对角色及真实既有身份，刷新后仍保存到本设备的加密会话。 */
    @Before fun requireLocalPair() {
        // 不接受生产地址或隐式跳过；参数只允许非敏感的用户名、角色与运行标记。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val args = InstrumentationRegistry.getArguments()
        val role = requireNotNull(args.getString("dualRole"))
        check(role in listOf("host", "peer"))
        host = role == "host"
        val run = requireNotNull(args.getString("dualRunId"))
        check(run.matches(Regex("[A-Za-z0-9_]{1,24}")))
        marker = "dual_$run"
        // 复用设备现有的本地会话；此专项不需要轮换 refresh token，避免网络响应丢失后令旧令牌失效。
        session = requireNotNull(NativeSessionStore.load(context)) { "本地测试会话缺失，停止测试且不自动登录。" }
        val username = requireNotNull(args.getString("dualPeerUsername"))
        peer = NativeFriendsApi.searchUsers(session.accessToken, username).single { it.user.username == username }.user
        check(peer.id != session.userId)
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("role", role).put("marker", marker).put("userId", session.userId).put("username", session.username).put("peerId", peer.id)
        saveReport()
    }

    /** 关闭测试页面并恢复语言，保留两端账号、好友、群聊、消息及本地会话。 */
    @After fun finish() {
        // 失败时也写入已完成检查点，不把未执行步骤记录为通过。
        if (activity != null) screenshot("final")
        activity?.close()
        if (::session.isInitialized) {
            report.put("sessionPreserved", NativeSessionStore.load(context)?.userId == session.userId)
            saveReport()
        }
        oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
    }

    /** 配对双方完成好友同意、双向原生发送/接收，再进入带历史记录的同一群聊。 */
    @Test fun pairedNativeChatsAndHistoricalGroupLocation() {
        // 好友申请和群历史是业务 API 夹具；实际接收端通过好友页面同意申请。
        if (host) prepareHostFriendship() else acceptPeerFriendship()
        val conversation = NativeFriendsApi.getOrCreateConversation(session.accessToken, peer.id)
        report.put("directConversationId", conversation.id)
        openConversation(conversation.id)
        if (host) {
            waitMessage("$marker peer-ready")
            sendUi("$marker host-reply")
            waitMessage("$marker peer-confirmed")
        } else {
            sendUi("$marker peer-ready")
            waitMessage("$marker host-reply")
            sendUi("$marker peer-confirmed")
        }
        // 页面与真实服务端各自检查，避免只通过乐观插入判断消息发送成功。
        val direct = NativeSocialApi.listMessages(session.accessToken, conversation.id).items
        assertTrue(direct.any { it.body == "$marker host-reply" && it.senderUsername == if (host) session.username else peer.username })
        assertTrue(direct.any { it.body == "$marker peer-ready" && it.senderUsername == if (host) peer.username else session.username })
        awaitCondition("Both clients must persist read state") {
            NativeSocialApi.listConversations(session.accessToken).single { it.id == conversation.id }.unreadCount == 0
        }
        record("two-native-main-activities-send-receive-and-persist-read-state")
        screenshot("direct")

        // 群主准备超过一页的历史与两个置顶；成员通过正常邀请接受加入，不提升角色。
        val existingGroupId = InstrumentationRegistry.getArguments().getString("dualGroupId")?.toIntOrNull()
        val group = if (existingGroupId != null) {
            // 允许显式复用本专项保留的历史群，仍核对群主和双方成员身份，不按同名群猜测。
            NativeGroupApi.get(session.accessToken, existingGroupId).also { existing ->
                check(existing.summary.owner.id == if (host) session.userId else peer.id)
                check(existing.members.filter { it.status == "active" }.map { it.user.id }.containsAll(listOf(session.userId, peer.id)))
            }
        } else if (host) prepareGroup() else awaitGroupInvitation()
        report.put("groupId", group.summary.id).put("groupConversationId", group.summary.conversationId)
        saveReport()
        openConversation(group.summary.conversationId)
        if (host) {
            waitMessage("$marker group-peer-ready")
            sendUi("@${peer.username} $marker mention-live")
            waitMessage("$marker group-peer-confirmed")
        } else {
            sendUi("$marker group-peer-ready")
            waitMessage("@${session.username} $marker mention-live")
            val mentionedMessage = NativeSocialApi.listMessages(session.accessToken, group.summary.conversationId).items.single {
                it.body == "@${session.username} $marker mention-live"
            }
            val mention = NativeSocialApi.listNotifications(session.accessToken, limit = 50).items.single {
                it.type == "mention_received" && it.conversationId == group.summary.conversationId && it.messageId == mentionedMessage.id
            }
            assertFalse(mention.messageDeleted)
            report.put("mentionNotificationId", mention.id).put("mentionMessageId", mention.messageId)
            sendUi("$marker group-peer-confirmed")
        }
        record("two-native-group-clients-receive-mention-and-valid-notification")
        screenshot("group")

        // 关闭再打开同一真实会话，确保首次置顶定位目标原本不在最新历史页中。
        openConversation(group.summary.conversationId)
        val pins = NativeSocialApi.listPinnedMessages(session.accessToken, group.summary.conversationId)
        assertEquals(2, pins.size)
        val latest = NativeSocialApi.listMessages(session.accessToken, group.summary.conversationId)
        assertTrue(latest.hasMore)
        assertTrue(pins.any { pin -> latest.items.none { it.id == pin.id } })
        pins.forEachIndexed { index, pin ->
            waitFor(hasText("点击定位") and hasClickAction())
            compose.onNode(hasText("点击定位") and hasClickAction()).performClick()
            assertLocated(pin, "pin-$index")
        }
        record("first-pin-location-loads-history-cycles-and-clears-two-second-row-highlight")

        // 搜索目标使用稳定业务正文，结果点击后收起输入框且定位到实际消息行。
        val earliest = pins.minBy { it.id }
        compose.onNodeWithContentDescription("搜索聊天内容").performClick()
        compose.onNode(hasSetTextAction() and hasText("搜索聊天内容")).performTextReplacement(earliest.body)
        // 搜索输入框本身也带同样正文和点击动作，必须排除可编辑节点，等待真正的异步结果行。
        val searchResult = hasText(earliest.body) and hasClickAction() and isEnabled() and hasSetTextAction().not()
        waitFor(searchResult)
        compose.onNode(searchResult).performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertLocated(earliest, "search")
        compose.onNode(hasSetTextAction() and hasText("搜索聊天内容")).assertDoesNotExist()
        record("native-group-search-locates-full-row-and-clears-highlight")
        report.put("completed", true)
        saveReport()
    }

    /** 主端对 peer 建立合法申请；重跑时保留已有好友，不删除旧业务关系。 */
    private fun prepareHostFriendship() {
        // 仅在尚未成为好友时创建申请，既有申请保持同一个关系标识。
        val current = NativeFriendsApi.listFriendships(session.accessToken)
        if (current.friends.none { it.user.id == peer.id }) {
            val request = current.outgoing.firstOrNull { it.user.id == peer.id }
                ?: NativeFriendsApi.requestFriend(session.accessToken, peer.id, marker)
            report.put("friendshipId", request.id)
            saveReport()
        }
        awaitCondition("Peer must accept the real friend request") {
            NativeFriendsApi.listFriendships(session.accessToken).friends.any { it.user.id == peer.id }
        }
        record("friendship-accepted-by-second-native-client")
    }

    /** 从当前设备真正打开好友页面并接受主端申请，已有好友时不重复生成关系。 */
    private fun acceptPeerFriendship() {
        // 等待另一设备发出的请求，不能代用主端会话替接收方同意。
        if (NativeFriendsApi.listFriendships(session.accessToken).friends.any { it.user.id == peer.id }) return
        var incoming: NativeFriendship? = null
        awaitCondition("Host request must arrive") {
            incoming = NativeFriendsApi.listFriendships(session.accessToken).incoming.firstOrNull { it.user.id == peer.id }
            incoming != null
        }
        launchRoute("/messages?friendshipId=${incoming!!.id}")
        waitFor(hasText("好友申请", substring = true) and hasClickAction())
        compose.onNode(hasText("好友申请", substring = true) and hasClickAction()).performClick()
        val accept = hasText("接受") and hasAnyAncestor(hasTestTag("friendship-${incoming!!.id}"))
        waitFor(accept)
        compose.onNode(accept).performScrollTo().performClick()
        awaitCondition("UI acceptance must persist") {
            NativeFriendsApi.listFriendships(session.accessToken).friends.any { it.user.id == peer.id }
        }
        record("real-friend-request-accepted-in-main-activity")
    }

    /** 创建本地群聊、邀请 peer 并保存 75 条历史，辅助 Socket 仅用于历史夹具而非双端互聊。 */
    private fun prepareGroup(): NativeGroupDetail {
        // 使用当前普通账号真实建群，保持站点成员/权限规则，不访问数据库绕过 API。
        val group = NativeGroupApi.create(session.accessToken, marker, false, 7, "invite_only")
        NativeGroupApi.invite(session.accessToken, group.summary.id, listOf(peer.id))
        val ready = CountDownLatch(1)
        val received = LinkedBlockingQueue<NativeChatMessage>()
        val socket = NativeChatSocket(session.accessToken, onMessage = { received.offer(it) }, onRead = { _, _ -> }, onError = {},
            onConnectionChanged = { if (it) ready.countDown() })
        try {
            socket.connect()
            check(ready.await(20, TimeUnit.SECONDS))
            // 序列写入是聊天历史的先后顺序要求，不用于实现业务侧逐条查询。
            for (index in 1..75) {
                val body = "$marker history-${index.toString().padStart(3, '0')}"
                val ack = CountDownLatch(1)
                var accepted = false
                socket.send(group.summary.conversationId, body) { ok, _ -> accepted = ok; ack.countDown() }
                check(ack.await(20, TimeUnit.SECONDS) && accepted)
                var message: NativeChatMessage
                do { message = requireNotNull(received.poll(20, TimeUnit.SECONDS)) } while (message.body != body)
                if (index == 1 || index == 20) NativeSocialApi.setMessagePin(session.accessToken, group.summary.conversationId, message.id, true)
                // 现有网关每 10 秒最多 12 条，本地历史夹具同样遵守限流，不关闭或绕过安全策略。
                Thread.sleep(1_000)
            }
        } finally { socket.close() }
        awaitCondition("Invited native peer must join") {
            NativeGroupApi.get(session.accessToken, group.summary.id).members.any { it.user.id == peer.id && it.status == "active" }
        }
        return NativeGroupApi.get(session.accessToken, group.summary.id)
    }

    /** 等待主端同名群邀请并通过普通邀请 API 加入，返回有权限读取的真实群详情。 */
    private fun awaitGroupInvitation(): NativeGroupDetail {
        // 群名只用于本次唯一夹具发现，真正的访问和断言始终使用服务端群 ID。
        var invitation: NativeGroupInvitation? = null
        awaitCondition("Host group invitation must arrive") {
            invitation = NativeGroupApi.listApprovals(session.accessToken).invitations.firstOrNull { it.group.name == marker }
            invitation != null
        }
        val pending = requireNotNull(invitation)
        NativeGroupApi.respondInvitation(session.accessToken, pending.id, "accepted")
        // 邀请可能早于历史准备完成，成员等最后一条夹具持久化后再发送同步消息，避免落在旧页。
        awaitCondition("Historical fixture must finish before native chat") {
            NativeSocialApi.searchMessages(session.accessToken, pending.group.conversationId, "$marker history-075").items.isNotEmpty()
        }
        return NativeGroupApi.get(session.accessToken, pending.group.id)
    }

    /** 打开 conversationId 对应原生聊天，等待真实连接状态，不模拟聊天页面。 */
    private fun openConversation(conversationId: Int) {
        // 关闭旧 Activity 后重建真实入口，独立检验首次加载而不是复用已加载消息。
        launchRoute("/messages?conversation=$conversationId")
        waitFor(hasContentDescription("聊天已连接"))
        waitFor(hasSetTextAction() and hasText("输入消息…"))
    }

    /** 以 path 为应用内 HTTPS/本地入口启动真实 MainActivity，保留加密账号来源隔离。 */
    private fun launchRoute(path: String) {
        // 测试只启动本应用显式 Activity，避免外部浏览器或未验证的系统深链接管。
        activity?.close()
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + path)))
    }

    /** 在真实输入框发送 body，等待清空和服务端回显，不能直接调用发送 API 代替用户动作。 */
    private fun sendUi(body: String) {
        // 输入、按钮点击和消息回显必须同时成功，才允许对端继续后续步骤。
        compose.onNode(hasSetTextAction() and hasText("输入消息…")).performTextReplacement(body)
        // 多行输入及输入法动画会改变屏幕坐标，使用系统无障碍点击执行同一个真实按钮动作。
        val send = hasContentDescription("发送") and hasClickAction() and isEnabled()
        waitFor(send)
        compose.onNode(send).performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitMessage(body)
        waitFor(hasSetTextAction() and hasText("输入消息…"))
    }

    /** 等待 body 在真实消息列表出现并可见，最多等待对端 90 秒，失败不回退到 API 伪造 UI。 */
    private fun waitMessage(body: String) {
        // 两台设备速度不同，使用界面内容作为同步信号而非固定等待。
        val message = hasText(body) and hasSetTextAction().not()
        // 气泡行合并了头像、时间与正文语义；用未合并树锁定正文 Text，避免父行语义导致漏检。
        compose.waitUntil(90_000) {
            compose.onAllNodes(message, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        compose.onNode(message, useUnmergedTree = true).assertIsDisplayed()
    }

    /** 核对 message 行的定位状态、可见性与两秒后清除，保存 name 对应的前后截图。 */
    private fun assertLocated(message: NativeChatMessage, name: String) {
        // 使用业务消息 ID 对应的整行语义，而不是结果列表或置顶摘要中的同名正文。
        val tag = "chat-message-${message.id}"
        val located = hasTestTag(tag) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已定位的消息")
        waitFor(located)
        compose.onNode(located).assertIsDisplayed()
        screenshot("$name-highlight")
        compose.waitUntil(10_000) { compose.onAllNodes(located).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
        screenshot("$name-cleared")
        report.put("locatedMessageIds", report.optJSONArray("locatedMessageIds")?.put(message.id) ?: JSONArray().put(message.id))
    }

    /** 等待 matcher 对应节点出现，节点尚未渲染时不让 Compose 抛出空根异常。 */
    private fun waitFor(matcher: SemanticsMatcher) {
        // 网络和 Compose 独立调度，使用有限等待避免把卡死视为成功。
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 低频执行 condition 等待真实持久化状态，超时以 label 标识失败业务阶段。 */
    private fun awaitCondition(label: String, condition: () -> Boolean) {
        // 仅在测试协调中轮询真实接口；每次请求后休眠，不持续占用服务器。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(500)
        }
        error(label)
    }

    /** 将已通过的 check 立即追加到报告，保留失败前最后一个真实进度点。 */
    private fun record(check: String) {
        // 不输出认证材料，报告仅含业务标识、角色和通过步骤。
        checks.put(JSONObject().put("check", check).put("passed", true))
        saveReport()
    }

    /** 保存非敏感的双端验收记录，供宿主机提取与网页复查。 */
    private fun saveReport() {
        // 固定私有报告名，跨设备通过各自报告核对同一会话和群 ID。
        File(context.filesDir, "native-local-dual-device-report.json").writeText(report.put("checks", checks).toString(2))
    }

    /** 保存 name 对应的实际页面画面，调用点只在无认证材料的聊天页面。 */
    private fun screenshot(name: String) {
        // 画面验收与业务断言分开保存，便于检查高亮范围及布局。
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "native-local-dual-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
