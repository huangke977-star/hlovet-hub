package xyz.hlovet.portal.prototype

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** 在隔离服务中控制公告响应，核对已读同步、详情失败反馈、手动重试和账号切换边界。 */
@RunWith(AndroidJUnit4::class)
class NativeAnnouncementsReadDeviceTest {
    // 设备内 MockWebServer 必须绕过模拟器的系统代理，避免回环请求被改写为外部错误。
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"
    private val gates = CopyOnWriteArrayList<CountDownLatch>()
    private val calls = CopyOnWriteArrayList<String>()
    /** 根据 request 生成响应，不记录请求授权或正文，不访问真实服务器。 */
    @Volatile private var respond: (RecordedRequest) -> MockResponse = { json(JSONObject(), 404) }
    private val reader = NativeSession("fixture-announcement-reader-a", "fixture-refresh", 91, "reader_a", "读者甲",
        PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46))

    /** 仅接受设备内隔离来源，准备真实公告页面所需的 HTTP 服务并暂时使用中文。 */
    @Before fun prepare() {
        // 正式和本地业务构建只能跳过，不能让测试服务接管真实 API。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 记录 request 的方法与路径，再按照当前场景控制响应顺序。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 方法和路径足以检查无重复阅读请求，不持久化任何授权。
                calls.add("${request.method} ${request.path}")
                return respond(request)
            }
        }
        server.start(39871)
    }

    /** 先释放网络屏障再销毁组合，防止失败场景遗留挂起请求；不读写真实会话或业务数据。 */
    @After fun stop() {
        // 未通过来源保护时服务未初始化，不执行不属于本轮的清理。
        if (!::server.isInitialized) return
        gates.forEach { it.countDown() }
        try { host.dispose() } finally {
            try { server.shutdown() } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 初始列表晚于深链详情到达时，旧的未读标记和浏览计数不能覆盖已确认阅读。 */
    @Test fun delayedInitialListCannotRestoreUnreadAfterOpeningDetail() {
        // 在服务器保留未读快照，直到用户已经打开并关闭详情后才返回。
        val listStarted = gate()
        val releaseList = gate()
        respond = { request ->
            when {
                request.path?.startsWith("/announcements/visible?") == true -> {
                    listStarted.countDown()
                    check(releaseList.await(15, TimeUnit.SECONDS))
                    json(page(91, "延迟列表公告"))
                }
                request.path == "/announcements/visible/91" -> json(detail(91, "延迟列表公告"))
                else -> json(JSONObject(), 404)
            }
        }
        host.setContent { HlovetTheme { NativeAnnouncementsScreen(reader, {}, 91) } }
        compose.waitUntil(10_000) { listStarted.count == 0L }
        closeDetail("延迟列表公告")
        releaseList.countDown()

        // 列表必须保留服务端返回的成员范围，但合并详情确认过的已读和更新后的计数。
        waitRow("延迟列表公告").assert(hasText("未读").not()).assert(hasText("8"))
        assertEquals(1, calls.count { it == "GET /announcements/visible/91" })
        assertTrue(calls.none { it.startsWith("POST ") })
    }

    /** 用户读完后刷新失败时保留已读列表，并显示网络错误，不回退为空列表或再次确认。 */
    @Test fun refreshFailureRetainsConfirmedRowAndShowsError() {
        // 首次列表未读，详情确认阅读，下一次列表由服务端明确返回故障。
        val requests = AtomicInteger()
        respond = { request ->
            when {
                request.path?.startsWith("/announcements/visible?") == true -> {
                    if (requests.incrementAndGet() == 1) json(page(91, "刷新保留公告"))
                    else json(JSONObject().put("message", "公告列表测试故障"), 503)
                }
                request.path == "/announcements/visible/91" -> json(detail(91, "刷新保留公告"))
                else -> json(JSONObject(), 404)
            }
        }
        host.setContent { HlovetTheme { NativeAnnouncementsScreen(reader, {}) } }
        waitRow("刷新保留公告").assert(hasText("未读")).performNativeStableClick(compose)
        closeDetail("刷新保留公告")
        waitRow("刷新保留公告").assert(hasText("未读").not()).assert(hasText("8"))
        compose.onNodeWithContentDescription("刷新").performNativeStableClick(compose)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("公告列表测试故障").fetchSemanticsNodes().isNotEmpty() }

        // 错误提示可见且原行保持已读；详情请求一次，页面不能为同步未读重复增加浏览次数。
        compose.onNodeWithText("公告列表测试故障").assertIsDisplayed()
        waitRow("刷新保留公告").assert(hasText("未读").not())
        assertEquals(1, calls.count { it == "GET /announcements/visible/91" })
    }

    /** 失效深链必须独立提示不可访问，不自动重试，也不把其他可见公告标记已读。 */
    @Test fun unavailableDeepLinkShowsFailureWithoutRetrying() {
        // 可见列表与失效目标不同，服务端错误正文也不能泄漏给无权限读者。
        respond = { request ->
            when {
                request.path?.startsWith("/announcements/visible?") == true -> json(page(92, "仍可阅读的公告"))
                request.path == "/announcements/visible/91" -> json(JSONObject().put("message", "不可泄漏的内部详情"), 404)
                else -> json(JSONObject(), 404)
            }
        }
        host.setContent { HlovetTheme { NativeAnnouncementsScreen(reader, {}, 91) } }
        waitFailure("公告已下线、不存在或当前账号无权查看。")
        compose.onAllNodesWithText("重试").assertCountEquals(0)
        compose.onAllNodesWithText("不可泄漏的内部详情").assertCountEquals(0)
        closeFailure()

        // 关闭提示后仍可继续读列表，失败详情不得伪造确认阅读或陷入自动重试。
        waitRow("仍可阅读的公告").assert(hasText("未读"))
        assertNoAutomaticDetailRetry()
        assertTrue(calls.none { it.startsWith("POST ") })
    }

    /** 英文界面的权限拒绝使用英文提示，且不能显示服务器内部正文或允许无效重试。 */
    @Test fun forbiddenDetailUsesEnglishFailureMessage() {
        // 仅改变测试内语言，不修改真实账号配置或权限。
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("en-US") }
        respond = { request ->
            when {
                request.path?.startsWith("/announcements/visible?") == true -> json(page(92, "Visible announcement"))
                request.path == "/announcements/visible/91" -> json(JSONObject().put("message", "Private server reason"), 403)
                else -> json(JSONObject(), 404)
            }
        }
        host.setContent { HlovetTheme { NativeAnnouncementsScreen(reader, {}, 91) } }
        waitFailure("This account cannot view this announcement.")
        compose.onNodeWithText("Announcement unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
        compose.onAllNodesWithText("Private server reason").assertCountEquals(0)
        closeFailure("Close")

        // 权限失败不污染列表阅读状态，也不触发后台重复详情请求。
        waitRow("Visible announcement").assert(hasText("Unread"))
        assertNoAutomaticDetailRetry()
    }

    /** 登录失效须给出重新登录提示，不静默清空原会话、伪造游客成功或自动重试。 */
    @Test fun expiredSessionShowsActionableFailure() {
        // 列表先返回当前账号的可见行，随后详情明确返回认证失效。
        respond = { request ->
            when {
                request.path?.startsWith("/announcements/visible?") == true -> json(page(91, "会话失效公告"))
                request.path == "/announcements/visible/91" -> json(JSONObject().put("message", "Unauthorized"), 401)
                else -> json(JSONObject(), 404)
            }
        }
        host.setContent { HlovetTheme { NativeAnnouncementsScreen(reader, {}) } }
        waitRow("会话失效公告").performNativeStableClick(compose)
        waitFailure("登录状态已失效，请重新登录后重试。")
        compose.onAllNodesWithText("重试").assertCountEquals(0)
        closeFailure()

        // 保留当前列表及未读状态，重新登录必须由上层正常流程处理。
        waitRow("会话失效公告").assert(hasText("未读"))
        assertNoAutomaticDetailRetry()
    }

    /** 临时详情故障保留未读；只有用户点击重试才发起下一次请求并同步服务端已读结果。 */
    @Test fun temporaryFailureRetriesOnlyOnExplicitClick() {
        // 首次详情故障与后续成功使用相同目标 ID，不靠重新挂载页面或跳转恢复。
        val requests = AtomicInteger()
        respond = { request ->
            when {
                request.path?.startsWith("/announcements/visible?") == true -> json(page(91, "可重试公告"))
                request.path == "/announcements/visible/91" -> {
                    if (requests.incrementAndGet() == 1) json(JSONObject().put("message", "Temporarily unavailable"), 503)
                    else json(detail(91, "可重试公告"))
                }
                else -> json(JSONObject(), 404)
            }
        }
        host.setContent { HlovetTheme { NativeAnnouncementsScreen(reader, {}) } }
        waitRow("可重试公告").assert(hasText("未读")).performNativeStableClick(compose)
        waitFailure("公告详情暂时无法读取，请检查网络后重试。")
        compose.onNode(hasText("重试") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertNoAutomaticDetailRetry()
        compose.onNode(hasText("重试") and hasClickAction() and hasAnyAncestor(isDialog())).performNativeStableClick(compose)
        closeDetail("可重试公告")

        // 成功前只有一个请求，点击后共两个请求；阅读与计数来自第二次真实 HTTP 响应。
        waitRow("可重试公告").assert(hasText("未读").not()).assert(hasText("8"))
        assertEquals(2, calls.count { it == "GET /announcements/visible/91" })
        assertTrue(calls.none { it.startsWith("POST ") })
    }

    /** 账号切换后旧详情即使返回成功，也不能打开旧账号私有公告或恢复旧列表。 */
    @Test fun switchingAccountDropsLatePrivateDetail() {
        // 只延迟甲的详情，乙立即取得自己的列表；甲的请求始终在真实 IO 线程执行。
        val detailStarted = gate()
        val releaseDetail = gate()
        val detailReturned = gate()
        val account = mutableStateOf<NativeSession?>(reader)
        respond = { request ->
            val firstReader = request.getHeader("Authorization") == "Bearer ${reader.accessToken}"
            when {
                request.path?.startsWith("/announcements/visible?") == true ->
                    json(if (firstReader) page(91, "甲的私有公告") else page(92, "乙的可见公告"))
                request.path == "/announcements/visible/91" && firstReader -> {
                    detailStarted.countDown()
                    check(releaseDetail.await(15, TimeUnit.SECONDS))
                    detailReturned.countDown()
                    json(detail(91, "甲的私有公告"))
                }
                else -> json(JSONObject().put("message", "无权读取此公告"), 404)
            }
        }
        host.setContent { HlovetTheme { NativeAnnouncementsScreen(account.value, {}, 91) } }
        compose.waitUntil(10_000) { detailStarted.count == 0L }
        compose.runOnIdle { account.value = reader.copy(accessToken = "fixture-announcement-reader-b", userId = 92, username = "reader_b") }
        waitFailure("公告已下线、不存在或当前账号无权查看。")
        closeFailure()
        waitRow("乙的可见公告")
        releaseDetail.countDown()
        compose.waitUntil(10_000) { detailReturned.count == 0L }

        // 观察响应返回后的真实帧，旧详情和列表均不得进入乙的界面。
        val started = SystemClock.uptimeMillis()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("甲的私有公告").assertCountEquals(0)
            SystemClock.uptimeMillis() - started >= 500
        }
        waitRow("乙的可见公告").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    /** 等待独立失败弹窗显示 message，不能以仅更新后台错误状态代替用户反馈。 */
    private fun waitFailure(message: String) {
        // 必须位于真实弹窗内，避免误匹配背后列表或内部状态。
        val matcher = hasText(message) and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(matcher).assertIsDisplayed()
    }

    /** 真实点击 label 对应的关闭按钮，并等待失败弹窗消失。 */
    private fun closeFailure(label: String = "关闭") {
        // 不直接调用业务回调，保留系统弹窗关闭与重组流程。
        compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog())).performNativeStableClick(compose)
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    /** 连续观察真实帧，确认目标 91 的失败详情没有在用户操作前被自动重复请求。 */
    private fun assertNoAutomaticDetailRetry() {
        // 真实 IO 使用实时时钟，不能只推进 Compose 虚拟时间后立即断言。
        val started = SystemClock.uptimeMillis()
        compose.waitUntil(5_000) {
            assertEquals(1, calls.count { it == "GET /announcements/visible/91" })
            SystemClock.uptimeMillis() - started >= 400
        }
    }

    /** 创建并登记网络屏障，返回可在收尾无条件释放的计数器。 */
    private fun gate(): CountDownLatch {
        // 失败也必须释放所有服务线程，不能影响下一项测试。
        return CountDownLatch(1).also { gates.add(it) }
    }

    /** 等待 title 的列表卡片挂载并返回真实可点击行。 */
    private fun waitRow(title: String): SemanticsNodeInteraction {
        // 弹窗与列表可能同名，只匹配带点击语义的卡片。
        val matcher = hasText(title) and hasClickAction()
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher).assertIsDisplayed()
    }

    /** 等待 title 的详情并真实点击关闭，确保弹窗已经消失再检查列表。 */
    private fun closeDetail(title: String) {
        // 不用语义回调关闭，也不在弹窗还覆盖列表时提前断言已读状态。
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(title) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("关闭") and hasClickAction() and hasAnyAncestor(isDialog())).performNativeStableClick(compose)
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    /** 用 id、title、unread、views 构建公告摘要，不包含账号或授权信息。 */
    private fun item(id: Int, title: String, unread: Boolean, views: Int): JSONObject {
        // 时间和字段结构与真实接口一致，便于比较列表和详情对同一 ID 的不同快照。
        return JSONObject().put("id", id).put("title", title).put("summary", "公告读取同步验收")
            .put("isPinned", false).put("viewCount", views).put("unread", unread)
            .put("publishedAt", "2026-10-19T00:00:00Z").put("updatedAt", "2026-10-19T00:00:00Z")
    }

    /** 返回 id 和 title 的单页未读快照，浏览次数固定为详情读取之前的 7 次。 */
    private fun page(id: Int, title: String): JSONObject {
        // 只包含当前接口允许可见的公告，不模拟客户端自行添加行。
        return JSONObject().put("items", JSONArray().put(item(id, title, true, 7))).put("total", 1).put("page", 1).put("totalPages", 1)
    }

    /** 返回 id 和 title 的已确认详情，成功打开后浏览次数增加为 8 次。 */
    private fun detail(id: Int, title: String): JSONObject {
        // 已读确认来自服务端响应，不能靠隐藏标签伪造成功。
        return item(id, title, false, 8).put("content", "当前账号可以阅读的公告正文。")
            .put("confirmedAt", "2026-10-19T00:01:00Z")
    }

    /** 将 body 封装为指定 status 的 JSON 响应，默认 200。 */
    private fun json(body: JSONObject, status: Int = 200): MockResponse {
        // 明确字符集，中文故障文案需要与真实页面一致。
        return MockResponse().setResponseCode(status).addHeader("Content-Type", "application/json; charset=utf-8").setBody(body.toString())
    }
}
