package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 在回环 MockWebServer 中验证消息页的加载、分页、批量操作和请求竞态保护，不访问生产接口。 */
@RunWith(AndroidJUnit4::class)
class NativeMessagesScreenDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val notificationRequestCount = AtomicInteger(0)
    private val delayedRefreshStarted = AtomicBoolean(false)
    private var failInitialLoad = false
    private var delaySecondNotificationLoad = false
    private val session = NativeSession(
        accessToken = "fixture-messages-token",
        refreshToken = "fixture-messages-refresh",
        userId = 7,
        username = "fixture-reader",
        nickname = "消息测试用户",
        appearance = PreviewAppearance(
            themeId = "sky-blue",
            accent = "#0284C7",
            foreground = "#1F2937",
            muted = "#52616F",
            surface = "#FFFFFF",
            cardAlpha = 50,
            glassBlur = 18,
            glassTint = "#FFFFFF",
            glassTintAlpha = 46,
        ),
    )

    /** 在专用回环构建中启动虚构消息接口，来源不匹配时直接跳过以避免访问生产。 */
    @Before
    fun startFixture() {
        // 设备测试必须显式注入回环地址，防止误把夹具请求发到线上账号。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        requests.clear()
        notificationRequestCount.set(0)
        delayedRefreshStarted.set(false)
        failInitialLoad = false
        delaySecondNotificationLoad = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            // 只返回消息页实际使用的社交接口，任何未声明请求都以 404 暴露测试范围错误。
            dispatcher = object : Dispatcher() {
                /** 按请求路径返回隔离夹具，并记录请求供断言查询参数和 JSON 请求体。 */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // 记录内存中的请求副本，不写入文件，也不保存认证头。
                    requests += request
                    return respond(request)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 39871)
        }
    }

    /** 卸载 Compose 页面并关闭夹具，确保测试结束后不残留网络服务或页面协程。 */
    @After
    fun stopFixture() {
        // 页面先卸载，避免关闭服务后仍有旧回包尝试更新下一项测试。
        if (!::server.isInitialized) return
        try {
            host.dispose()
        } finally {
            server.shutdown()
        }
    }

    /** 验证首屏接口失败时页面显示可操作的重试入口，重试成功后恢复消息列表。 */
    @Test
    fun failedInitialLoadCanRecoverThroughRetry() {
        // 首次加载故意返回服务错误，覆盖页面的错误状态而不是只验证空列表。
        failInitialLoad = true
        mountMessages()
        // 错误来自异步接口，等待页面状态切换后再检查可见提示和重试入口。
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("消息暂时无法读取", substring = false)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        compose.onNodeWithText("消息暂时无法读取", substring = false).assertIsDisplayed()
        compose.onNodeWithText("重新加载", substring = false).assertIsDisplayed().performClick()

        // 用户点击重试后夹具恢复正常，页面必须重新读取三组消息数据并显示通知。
        failInitialLoad = false
        waitForTag("notification-1")
        assertTrue(requests.count { it.path?.startsWith("/social/summary") == true } >= 2)
    }

    /** 验证通知下一页使用最早通知 ID，并按稳定 ID 合并重复项而不重复渲染。 */
    @Test
    fun notificationPaginationUsesBeforeIdAndDeduplicates() {
        // 首屏包含 1 到 20 号通知，下一页故意重复 20 号并追加 21、22 号。
        mountMessages()
        waitForTag("notification-1")
        // 分页控件位于长通知列表之后，先通过列表语义滚动到目标行再点击。
        compose.onNodeWithTag("messages-screen-list")
            .performScrollToNode(hasText("加载更多通知", substring = false))
        compose.onNodeWithText("加载更多通知", substring = false).assertIsDisplayed().performClick()
        waitForTag("notification-21")

        // 查询参数必须从当前列表末项生成，稳定 ID 去重后 20 号只能存在一个节点。
        compose.onAllNodesWithTag("notification-20").assertCountEquals(1)
        assertTrue(requests.any { it.path?.contains("/social/notifications?limit=20&beforeId=20") == true })
    }

    /** 验证批量已读和批量删除都发送选中 ID，并在服务端成功后更新当前列表。 */
    @Test
    fun selectedNotificationsCanBeMarkedReadAndDeleted() {
        // 进入选择模式后选择单条通知，先走批量已读路径，确认请求体不是显示文字。
        mountMessages()
        waitForTag("notification-1")
        compose.onNodeWithText("选择", substring = false).performClick()
        compose.onNodeWithTag("notification-1").performClick()
        compose.onNodeWithText("标为已读", substring = false).performClick()
        waitForRequest("/social/notifications/read-selected")
        assertEquals("[1]", jsonBody("/social/notifications/read-selected").getJSONArray("notificationIds").toString())

        // 再次选择另一条通知并确认批量删除，成功后该通知必须从页面语义树中消失。
        compose.onNodeWithText("选择", substring = false).performClick()
        compose.onNodeWithTag("notification-2").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.onNodeWithText("确认删除", substring = false).performClick()
        waitForRequest("/social/notifications/delete-selected")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("notification-2").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        assertTrue(
            compose.onAllNodesWithTag("notification-2")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
        assertEquals("[2]", jsonBody("/social/notifications/delete-selected").getJSONArray("notificationIds").toString())
    }

    /** 验证刷新竞态中较早回包不能覆盖较晚刷新得到的消息快照。 */
    @Test
    fun staleRefreshResponseCannotOverwriteNewSnapshot() {
        // 第二次通知请求延迟，测试通过页面外部的刷新控制连续发起第三次请求。
        delaySecondNotificationLoad = true
        mountMessages(withRefreshControl = true)
        waitForTag("notification-1")
        compose.onNodeWithTag("fixture-refresh").performClick()
        compose.waitUntil(10_000) { delayedRefreshStarted.get() }
        compose.onNodeWithTag("fixture-refresh").performClick()
        waitForTag("notification-200")

        // 等待延迟回包返回后，旧快照仍不得重新出现在当前页面。
        Thread.sleep(1_400)
        compose.waitForIdle()
        compose.onNodeWithTag("notification-200").assertIsDisplayed()
        compose.onNodeWithTag("notification-100").assertDoesNotExist()
    }

    /** 挂载真实消息页；可选的刷新控制只用于制造两个重叠刷新请求，不改变生产组件。 */
    private fun mountMessages(withRefreshControl: Boolean = false) {
        // 竞态用例需要在页面外控制 refreshKey，普通用例直接使用稳定的初始键值。
        host.setContent {
            var refreshKey by remember { mutableIntStateOf(0) }
            HlovetTheme {
                if (withRefreshControl) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.material3.TextButton(
                            onClick = { refreshKey += 1 },
                            modifier = Modifier.testTag("fixture-refresh"),
                        ) { androidx.compose.material3.Text("刷新夹具") }
                        messages(refreshKey)
                    }
                } else {
                    messages(refreshKey)
                }
            }
        }
    }

    /** 将消息页固定回调到测试夹具，避免测试触发登录、导航、推送或真实系统通知。 */
    @androidx.compose.runtime.Composable
    private fun messages(refreshKey: Int) {
        // 所有页面回调都保持空操作，只有收件箱摘要保存在内存中供组合继续运行。
        MessagesScreen(
            padding = PaddingValues(),
            session = session,
            refreshKey = refreshKey,
            onLogin = {},
            onNotice = {},
            onOpenPeople = {},
            onOpenGroups = {},
            onOpenConversation = {},
            onOpenNotification = { _, _ -> },
            onInboxSummaryChanged = {},
            onEnableNativeNotifications = {},
        )
    }

    /** 等待指定通知节点出现，确保断言发生在真实网络回包和 Compose 重组之后。 */
    private fun waitForTag(tag: String, timeoutMillis: Long = 15_000) {
        // 语义树为空时继续等待，超时由 Compose 测试规则直接报告夹具或页面失败。
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    /** 等待某个写接口到达夹具，避免只凭 UI 重组误判请求已经发出。 */
    private fun waitForRequest(path: String) {
        // 请求体断言必须建立在网络线程已经记录完整请求之后。
        compose.waitUntil(10_000) { requests.any { it.path?.startsWith(path) == true } }
    }

    /** 读取指定路径最近一次 JSON 请求体，供批量 ID 断言使用。 */
    private fun jsonBody(path: String): JSONObject {
        // clone 保留 RecordedRequest 的可重复读取能力，不影响其它断言继续检查请求。
        val request = requests.last { it.path?.startsWith(path) == true }
        return JSONObject(request.body.clone().readUtf8())
    }

    /** 按消息页接口契约返回虚构摘要、会话、通知和批量操作结果。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 首次摘要失败即可让 loadInbox 进入错误状态，随后重试使用正常响应。
        if (failInitialLoad && request.path?.startsWith("/social/summary") == true) return failure()
        return when {
            request.method == "GET" && request.path?.startsWith("/social/summary") == true -> json(summary())
            request.method == "GET" && request.path?.startsWith("/social/conversations") == true -> json(conversations())
            request.method == "GET" && request.path?.startsWith("/social/notifications") == true -> notifications()
            request.method == "POST" && request.path == "/social/notifications/read-selected" -> json(JSONObject().put("count", 1))
            request.method == "POST" && request.path == "/social/notifications/delete-selected" -> json(JSONObject().put("count", 1))
            else -> MockResponse().setResponseCode(404).setBody(JSONObject().put("message", "Unexpected fixture request: ${request.path}").toString())
        }
    }

    /** 构造消息页首屏摘要，保持字段名与线上接口一致但不写入真实账号数据。 */
    private fun summary(): JSONObject = JSONObject()
        .put("unreadMessages", 1)
        .put("pendingFriendRequests", 0)
        .put("pendingStrangerRequests", 0)
        .put("unreadNotifications", 20)

    /** 构造一个最小可渲染的私聊会话，避免消息页把错误归因于空会话列表。 */
    private fun conversations(): JSONObject = JSONObject().put(
        "items",
        JSONArray().put(
            JSONObject()
                .put("id", 10)
                .put("kind", "direct")
                .put("unreadCount", 1)
                .put("user", JSONObject().put("id", 8).put("username", "fixture-peer").put("nickname", "测试对话"))
                .put("lastMessage", JSONObject().put("body", "测试消息").put("createdAt", "2026-11-15T00:00:00.000Z")),
        ),
    )

    /** 构造通知分页并在竞态用例中控制第二次刷新延迟、第三次刷新返回新快照。 */
    private fun notifications(): MockResponse {
        // 统计 GET 通知次数，首次为首屏，第二次为延迟旧快照，第三次为最新快照。
        val count = notificationRequestCount.incrementAndGet()
        if (delaySecondNotificationLoad && count == 2) {
            delayedRefreshStarted.set(true)
            return json(notificationPage(100, 1, hasMore = false)).setBodyDelay(1_200, TimeUnit.MILLISECONDS)
        }
        if (delaySecondNotificationLoad && count >= 3 && requests.lastOrNull()?.path?.contains("beforeId=") != true) {
            return json(notificationPage(200, 1, hasMore = false))
        }
        val beforeId = requests.lastOrNull()?.path?.substringAfter("beforeId=", "")?.toIntOrNull()
        return if (beforeId == 20) {
            json(JSONObject().put("items", JSONArray().put(notification(20)).put(notification(21)).put(notification(22))).put("hasMore", false))
        } else {
            json(notificationPage(1, 20, hasMore = true))
        }
    }

    /** 构造指定编号区间的通知页，竞态测试使用独立编号识别新旧快照。 */
    private fun notificationPage(start: Int, count: Int, hasMore: Boolean): JSONObject {
        // 服务端按 ID 倒序返回；页面自身会再次按稳定 ID 合并和排序。
        val items = JSONArray()
        for (offset in 0 until count) items.put(notification(start + offset))
        return JSONObject().put("items", items).put("hasMore", hasMore)
    }

    /** 构造单条最小通知，缺失关联上下文时仍应能在消息列表中正常展示。 */
    private fun notification(id: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("title", "通知 $id")
        .put("body", "消息页夹具通知 $id")
        .put("channel", "system")
        .put("createdAt", "2026-11-15T00:00:00.000Z")

    /** 返回带 JSON 响应头的夹具结果，保证客户端按正式接口方式解析。 */
    private fun json(value: JSONObject): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(value.toString())

    /** 返回首屏加载失败响应，验证页面错误状态和重试行为。 */
    private fun failure(): MockResponse = MockResponse()
        .setResponseCode(503)
        .addHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("message", "fixture unavailable").toString())
}
