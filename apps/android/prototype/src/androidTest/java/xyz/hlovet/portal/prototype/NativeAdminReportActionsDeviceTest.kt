package xyz.hlovet.portal.prototype

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 在隔离 HTTP 服务验证实际举报中心操作、失败恢复和身份隔离，不替代真实数据库验收。 */
@RunWith(AndroidJUnit4::class)
class NativeAdminReportActionsDeviceTest {
    // 设备内 MockWebServer 必须绕过模拟器的系统代理，避免回环请求被改写为外部错误。
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val session = NativeSession("report-action-fixture", "fixture-refresh", 7, "admin-fixture", "Admin fixture",
        PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46), isAdministrator = true)
    private val account = mutableStateOf<NativeSession?>(session)
    private val rows = CopyOnWriteArrayList<JSONObject>()
    private val calls = CopyOnWriteArrayList<String>()
    private val writes = CopyOnWriteArrayList<JSONObject>()
    /** 仅覆盖本次用例的响应，不保存或输出任何 Authorization。 */
    @Volatile private var respond: (RecordedRequest) -> MockResponse? = { null }
    private var oldLocale = "zh-CN"

    /** 仅允许专用隔离来源，服务在模拟器内部监听，不访问真实账号。 */
    @Before fun start() {
        // 不匹配来源时必须在读取页面或发送请求前退出。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 对 request 返回夹具，只有变更请求才保留无凭据的正文用于断言。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 网络证据仅包含方法、业务路径和处理参数。
                calls.add("${request.method} ${request.path}")
                if (request.method == "PATCH" || request.method == "POST") writes.add(JSONObject(request.body.clone().readUtf8()))
                return respond(request) ?: response(request)
            }
        }
        server.start(39871)
    }

    /** 先销毁页面再停止服务，最后恢复本轮开始时的界面语言。 */
    @After fun stop() {
        // 被来源保护跳过的用例不访问未初始化服务。
        if (!::server.isInitialized) return
        try { host.dispose() } finally {
            server.shutdown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
        }
    }

    /** 空反馈不发请求；服务端失败在弹窗内可见，重试保留原文字与处理方式。 */
    @Test fun singleFailureKeepsDraftAndRetriesVisibleError() {
        // 使用真实卡片按钮进入操作，不直接注入弹窗状态。
        rows.add(row(1, "article")); show(); openAction("article-1", "处理")
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithText("请填写处理反馈。").assertIsDisplayed()
        assertTrue(writes.isEmpty())
        val attempts = AtomicInteger()
        respond = { request -> if (request.method == "PATCH" && attempts.getAndIncrement() == 0) MockResponse().setResponseCode(503).setBody("{\"message\":\"处理暂不可用，请重试\"}") else null }
        compose.onNodeWithText("屏蔽内容").performClick()
        fill("保留的人工反馈")
        compose.onNodeWithText("确认").performClick()
        waitText("处理暂不可用，请重试")
        compose.onNodeWithText("处理暂不可用，请重试").assertIsDisplayed()
        compose.onNodeWithTag("admin-report-resolution").assertTextContains("保留的人工反馈")
        compose.onNodeWithText("确认").performClick()
        waitClosed()
        assertEquals(2, writes.size)
        assertEquals("blocked", writes.last().getString("articleStatus"))
        assertEquals("保留的人工反馈", writes.last().getString("resolution"))
    }

    /** 群消息默认删除，但选择保留后必须明确提交 false。 */
    @Test fun groupKeepDoesNotDeleteMessage() {
        // 群消息使用同一处理弹窗，不能误用文章状态字段。
        rows.add(row(1, "group_message")); show(); openAction("group_message-1", "处理")
        compose.onNodeWithText("保留内容").performClick(); fill("群消息保留")
        compose.onNodeWithText("确认").performClick(); waitClosed()
        assertFalse(writes.single().getBoolean("deleteMessage"))
        assertFalse(writes.single().has("articleStatus"))
    }

    /** 混合来源不能同时勾选，已完成记录也不能进入批次。 */
    @Test fun onlySameSourcePendingReportsAreSelectable() {
        // 全部状态视图包含已处理记录，检查真正禁用而不是仅提交时报错。
        rows.addAll(listOf(row(1, "article"), row(2, "comment"), row(3, "article", "resolved")))
        show()
        compose.onNode(hasText("全部") and hasClickAction()).performScrollTo().performClick()
        scrollRow("article-3")
        compose.onNodeWithTag("admin-report-select-article-3").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("admin-report-select-article-1").performScrollTo().performClick()
        compose.onNodeWithTag("admin-report-select-comment-2").performScrollTo().assertIsNotEnabled()
        assertTrue(writes.isEmpty())
    }

    /** 批量部分失败后仅重试失败编号，反馈和逐条错误仍在当前弹窗。 */
    @Test fun partialBulkFailureRetriesOnlyFailedRows() {
        // 第一批成功一条、失败一条，响应与下一次队列刷新同步变化。
        rows.addAll(listOf(row(1, "article"), row(2, "article")))
        val attempts = AtomicInteger()
        respond = { request ->
            if (request.path == "/moderation/reports/bulk" && attempts.getAndIncrement() == 0) {
                rows.first { it.getInt("id") == 1 }.put("status", "resolved")
                MockResponse().setBody("{\"succeeded\":[1],\"failed\":[{\"id\":2,\"message\":\"暂时冲突，请重试\"}]}")
            } else null
        }
        show(); select("article-1"); select("article-2")
        compose.onNodeWithText("批量处理").performScrollTo().performClick()
        fill("批量保留原文")
        compose.onNodeWithText("确认").performClick()
        waitText("#2 · 暂时冲突，请重试")
        compose.onNodeWithText("#2 · 暂时冲突，请重试").assertIsDisplayed()
        compose.onNodeWithTag("admin-report-resolution").assertTextContains("批量保留原文")
        compose.onNodeWithText("确认").performClick(); waitClosed()
        assertEquals(listOf(1, 2), ints(writes[0].getJSONArray("reportIds")))
        assertEquals(listOf(2), ints(writes[1].getJSONArray("reportIds")))
        assertEquals("批量保留原文", writes[1].getString("resolution"))
    }

    /** 批量网络失败保留选择与文字，用户可直接重试而不必重新勾选。 */
    @Test fun bulkNetworkFailureKeepsSelectionAndFeedback() {
        // 整体失败不制造任何成功结果。
        rows.add(row(1, "comment"))
        val attempts = AtomicInteger()
        respond = { request -> if (request.path == "/moderation/reports/bulk" && attempts.getAndIncrement() == 0) MockResponse().setResponseCode(503).setBody("{\"message\":\"批量暂不可用\"}") else null }
        show(); select("comment-1")
        compose.onNodeWithText("批量驳回").performScrollTo().performClick(); fill("批量驳回说明")
        compose.onNodeWithText("确认").performClick(); waitText("批量暂不可用")
        compose.onNodeWithText("批量暂不可用").assertIsDisplayed()
        compose.onNodeWithTag("admin-report-resolution").assertTextContains("批量驳回说明")
        compose.onNodeWithText("确认").performClick(); waitClosed()
        assertEquals(listOf(1), ints(writes.last().getJSONArray("reportIds")))
        assertEquals("rejected", writes.last().getString("status"))
    }

    /** 普通用户不会触发后台请求，普通管理员也看不到超级管理员治理入口。 */
    @Test fun roleGateDoesNotRequestProtectedData() {
        // 权限摘要用于界面门槛，接口授权仍由服务端负责。
        account.value = session.copy(isAdministrator = false)
        show(); waitText("当前账号没有访问举报中心的权限。")
        compose.waitForIdle(); assertTrue(calls.isEmpty())
        compose.runOnUiThread { account.value = session }
        waitText("当前筛选下没有举报记录。")
        compose.onNodeWithText("规则与自动化").assertDoesNotExist()
    }

    /** 退出登录会销毁正在处理的旧页面，迟到成功不得出现在新身份界面。 */
    @Test fun logoutDisposesDelayedAction() {
        // 使用迟到响应模拟旧账号请求已发送但服务器尚未返回。
        rows.add(row(1, "article"))
        respond = { request -> if (request.method == "PATCH") MockResponse().setBody("{}").setBodyDelay(1500, TimeUnit.MILLISECONDS) else null }
        show(); openAction("article-1", "驳回"); fill("旧账号反馈")
        compose.onNodeWithText("确认").performClick()
        compose.waitUntil(10000) { writes.isNotEmpty() }
        compose.runOnUiThread { account.value = null }
        waitText("登录后查看举报中心。")
        compose.onNodeWithTag("admin-report-resolution").assertDoesNotExist()
        Thread.sleep(1800); compose.waitForIdle()
        compose.onNodeWithText("举报已驳回。").assertDoesNotExist()
        compose.onNodeWithTag("admin-report-article-1").assertDoesNotExist()
    }

    /** 创建 source/id/status 的单条隔离举报，按来源使用真实响应字段。 */
    private fun row(id: Int, source: String, status: String = "pending"): JSONObject {
        // 不同来源允许相同数字编号，身份由完整 key 区分。
        val user = JSONObject().put("id", 34).put("username", "fixture-reader").put("nickname", "测试读者")
        val result = JSONObject().put("id", id).put("key", "$source-$id").put("source", source).put("status", status)
            .put("reporter", user).put("targetUser", user).put("createdAt", "2026-10-15T00:00:00Z")
        when (source) {
            "article" -> result.put("article", JSONObject().put("id", id).put("title", "测试文章 $id").put("author", user))
            "comment" -> result.put("comment", JSONObject().put("id", id).put("body", "测试评论 $id"))
            else -> result.put("group", JSONObject().put("id", 15).put("name", "测试群"))
                .put("message", JSONObject().put("id", id).put("body", "测试群消息 $id").put("sender", user))
        }
        return result
    }

    /** 挂载真实举报页面，account 变化由正常重组驱动。 */
    private fun show() {
        // 不直接调用页面内部处理函数或伪造本地成功状态。
        host.setContent { HlovetTheme { NativeAdminReportsScreen(account.value, {}) } }
        compose.waitForIdle()
        if (account.value?.isAdministrator == true && rows.isNotEmpty()) waitTag("admin-report-${rows.first().getString("key")}")
    }

    /** 为 key 对应行点击 label 操作，不误点其它举报的同名按钮。 */
    private fun openAction(key: String, label: String) {
        // 使用业务稳定标签约束操作归属。
        scrollRow(key)
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("admin-report-$key"))).performScrollTo().performClick()
        waitTag("admin-report-resolution")
    }

    /** 勾选 key 对应的统一复选框。 */
    private fun select(key: String) {
        // 先等待异步列表，再通过真正选择控件触发操作。
        scrollRow(key)
        compose.onNodeWithTag("admin-report-select-$key").performScrollTo().performClick()
    }

    /** 滚动到 key 对应的懒加载卡片，允许筛选请求短暂尚未完成。 */
    private fun scrollRow(key: String) {
        // 使用列表自身定位，不假定屏幕外的卡片已被 Compose 挂载。
        compose.waitUntil(15000) {
            runCatching { compose.onNodeWithTag("admin-reports-list").performScrollToNode(hasTestTag("admin-report-$key")); true }.getOrDefault(false)
        }
    }

    /** 填写 text 到当前操作弹窗的反馈框。 */
    private fun fill(text: String) {
        // 滚到输入框后通过 Compose 输入通道写入用户草稿。
        compose.onNodeWithTag("admin-report-resolution").performScrollTo().performTextReplacement(text)
    }

    /** 等待 text 实际出现在当前页面。 */
    private fun waitText(text: String) {
        // 无固定等待代替业务断言，最长十五秒。
        compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 等待 tag 对应组件实际挂载。 */
    private fun waitTag(tag: String) {
        // 稳定标签避免列表中重复文字串选。
        compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 等待操作成功后弹窗关闭，不只等待网络返回。 */
    private fun waitClosed() {
        // 表单消失证明页面完成成功状态迁移。
        compose.waitUntil(15000) { compose.onAllNodesWithTag("admin-report-resolution").fetchSemanticsNodes().isEmpty() }
    }

    /** 将 array 的整数编号转换成有序列表供请求范围断言。 */
    private fun ints(array: JSONArray): List<Int> {
        // 保留提交顺序，不通过排序掩盖额外或重复编号。
        return (0 until array.length()).map { array.getInt(it) }
    }

    /** 为 request 提供分页、汇总和变更响应，不匹配的业务路径明确失败。 */
    private fun response(request: RecordedRequest): MockResponse {
        // 列表尊重状态与来源，刷新后已处理行不能仍出现在待处理队列。
        val path = request.path.orEmpty()
        if (path.startsWith("/moderation/reports?")) {
            val status = request.requestUrl?.queryParameter("status")
            val source = request.requestUrl?.queryParameter("type")
            val items = rows.filter { (status == "all" || it.getString("status") == status) && (source == null || it.getString("source") == source) }
            return MockResponse().setBody(JSONObject().put("items", JSONArray(items)).put("total", items.size).put("page", 1).put("totalPages", 1).toString())
        }
        if (path == "/moderation/reports/summary") return MockResponse().setBody(JSONObject().put("pending", rows.count { it.getString("status") == "pending" }).put("total", rows.size).put("bySource", JSONObject()).toString())
        if (path == "/moderation/templates") return MockResponse().setBody("{\"items\":[]}")
        // 变更只作用本测试的独立记录，保留正文与反馈便于重试断言。
        if (request.method == "PATCH") {
            val body = writes.last()
            val id = path.substringAfterLast('/').toInt()
            rows.first { it.getInt("id") == id }.put("status", body.getString("status"))
            return MockResponse().setBody("{}")
        }
        if (path == "/moderation/reports/bulk") {
            val body = writes.last()
            val ids = ints(body.getJSONArray("reportIds"))
            rows.filter { it.getInt("id") in ids }.forEach { it.put("status", body.getString("status")) }
            return MockResponse().setBody(JSONObject().put("succeeded", JSONArray(ids)).put("failed", JSONArray()).toString())
        }
        return MockResponse().setResponseCode(404)
    }
}
