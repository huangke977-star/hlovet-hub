package xyz.hlovet.portal.prototype

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** 在隔离设备服务中验证文章管理的评论筛选和待处理举报计数，不写入真实业务数据。 */
@RunWith(AndroidJUnit4::class)
class NativeAdminArticlesDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val calls = CopyOnWriteArrayList<String>()
    private var oldLocale = "zh-CN"
    private val admin = NativeSession(
        accessToken = "article-admin-fixture",
        refreshToken = "article-admin-refresh",
        userId = 11,
        username = "article-admin",
        nickname = "文章管理员",
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
        isAdministrator = true,
    )

    /** 只允许专用回环构建启动服务，避免误把测试请求发到生产接口。 */
    @Before
    fun start() {
        // 地址保护必须先于启动夹具，正式包或其它本地地址直接跳过本用例。
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                /** 按文章管理页面实际调用的接口路径返回固定夹具。 */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // 只记录方法和路径，避免把测试 Authorization 或正文写入报告。
                    calls += "${request.method} ${request.path}"
                    return respond(request)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 39871)
        }
    }

    /** 卸载页面后关闭隔离服务，并恢复测试开始前的界面语言。 */
    @After
    fun stop() {
        // 地址保护跳过时服务尚未初始化，不能尝试关闭未创建的资源。
        if (!::server.isInitialized) return
        try {
            host.dispose()
        } finally {
            try {
                server.shutdown()
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 全部、有举报和待处理筛选分别返回正确评论，工具栏同时显示文章与评论待处理数量。 */
    @Test
    fun commentFiltersExposeReportedAndPendingRows() {
        // 通过真实文章管理页的文章列表、页签和筛选按钮操作，不直接调用页面内部状态。
        show()
        compose.onNodeWithText("评论与回复 3").performScrollTo().performClick()
        waitText("普通评论")
        compose.onNodeWithText("待处理举报 1").performScrollTo().assertIsDisplayed()
        // 顶部举报按钮的数字可能位于横向工具栏的屏幕外，存在性比当前视口可见性更能验证汇总值。
        compose.onNodeWithText("2").assertExists()

        // “有举报”保留所有已经产生举报记录的评论，普通评论不能继续显示。
        compose.onNodeWithText("有举报").performScrollTo().performClick()
        compose.onNodeWithText("已举报评论").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("待处理评论").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("普通评论").assertDoesNotExist()

        // “待处理”进一步按 pendingReportCount 收窄，只保留尚未处理的举报评论。
        // 语义树中可能同时包含筛选按钮和状态摘要，按页面出现顺序点击评论面板的第一个精确匹配。
        compose.onAllNodesWithText("待处理", substring = false)[0].performClick()
        compose.onNodeWithText("待处理评论").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已举报评论").assertDoesNotExist()
        assertTrue(calls.any { it.startsWith("GET /articles/admin?") })
        assertTrue(calls.any { it.startsWith("GET /articles/admin/comments?") })
        assertTrue(calls.any { it.startsWith("GET /moderation/reports/summary") })
    }

    /** 挂载正式文章管理组合，账号变化仍由正常 Compose 重组驱动。 */
    private fun show() {
        // 页面首次加载会并发读取文章、申诉和举报数据，等待稳定文章行再开始点击。
        host.setContent { HlovetTheme { NativeAdminArticlesScreen(mutableStateOf(admin).value, {}, {}) } }
        waitText("测试文章")
    }

    /** 等待指定文本真正出现在语义树中，最长十五秒，不以固定睡眠代替业务断言。 */
    private fun waitText(text: String) {
        // 网络夹具返回后 Compose 仍需完成一次重组和布局，条件等待覆盖这段异步窗口。
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 根据当前请求路径返回页面所需的文章、评论、举报和汇总夹具。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 文章管理页面的初始加载必须覆盖所有并发请求，任一遗漏都会被页面显示为统一读取失败。
        val path = request.path.orEmpty()
        val body = when {
            path.startsWith("/articles/admin?") -> JSONObject()
                .put("items", JSONArray().put(article()))
                .put("total", 1)
                .put("page", 1)
                .put("totalPages", 1)
                .toString()
            path == "/articles/admin/appeals" -> JSONObject().put("items", JSONArray()).toString()
            path == "/articles/admin/comments?articleId=101" -> JSONObject()
                .put("items", JSONArray().put(comment(1, "普通评论", 0, 0)).put(comment(2, "已举报评论", 0, 1)).put(comment(3, "待处理评论", 1, 1)))
                .toString()
            path.startsWith("/moderation/reports?status=all") -> reportPage().toString()
            path.startsWith("/moderation/reports?status=pending") -> reportPage().toString()
            path == "/moderation/reports/summary" -> JSONObject()
                .put("total", 2)
                .put("pending", 2)
                .put("bySource", JSONObject().put("article", 1).put("comment", 1).put("group_message", 0))
                .toString()
            else -> return MockResponse().setResponseCode(404).setBody(JSONObject().put("message", "Unexpected test request: $path").toString())
        }
        return MockResponse().addHeader("Content-Type", "application/json").setBody(body)
    }

    /** 构造含评论统计和管理字段的文章，字段形状与生产文章管理接口保持一致。 */
    private fun article(): JSONObject = JSONObject()
        .put("id", 101)
        .put("title", "测试文章")
        .put("slug", "test-article")
        .put("summary", "用于验证文章管理评论筛选")
        .put("content", "<p>测试正文</p>")
        .put("contentFormat", "html")
        .put("category", "测试")
        .put("tags", JSONArray())
        .put("viewCount", 12)
        .put("likeCount", 3)
        .put("favoriteCount", 2)
        .put("commentCount", 3)
        .put("author", user(20, "作者", "article-author"))
        .put("status", "published")
        .put("visibility", "public")
        .put("allowedRoleCodes", JSONArray())
        .put("isPinned", false)
        .put("pinOrder", 0)
        .put("titleColor", "")
        .put("blockedReason", JSONObject.NULL)
        .put("images", JSONArray())
        .put("attachments", JSONArray())
        .put("contentSegments", JSONArray())
        .put("resource", JSONObject().put("enabled", false).put("blocks", JSONArray()))

    /** 构造具有不同举报状态的评论，reports 数量与 pendingReportCount 分别验证两个筛选维度。 */
    private fun comment(id: Int, body: String, pendingCount: Int, reportCount: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("articleId", 101)
        .put("body", body)
        .put("status", "active")
        .put("likeCount", 0)
        .put("liked", false)
        .put("reported", false)
        .put("author", user(30 + id, "评论用户$id", "comment-user$id"))
        .put("createdAt", "2026-11-03T00:00:00Z")
        .put("attachments", JSONArray())
        .put("pendingReportCount", pendingCount)
        .put("reports", JSONArray().apply { repeat(reportCount) { put(JSONObject().put("id", 500 + id + it)) } })

    /** 构造管理举报队列中最小可解析的文章和评论记录。 */
    private fun reportPage(): JSONObject = JSONObject()
        .put("items", JSONArray().apply {
            // 队列中同时保留文章举报和评论举报，验证工具栏计数来自两个来源的合计。
            put(articleReport())
            put(commentReport())
        })
        .put("total", 2)
        .put("page", 1)
        .put("pageSize", 50)
        .put("totalPages", 1)

    /** 构造一条待处理文章举报，供顶部举报队列解析和计数使用。 */
    private fun articleReport(): JSONObject = JSONObject()
        .put("id", 601)
        .put("key", "article-601")
        .put("source", "article")
        .put("sourceLabel", "文章举报")
        .put("status", "pending")
        .put("reason", "spam")
        .put("detail", "测试文章举报")
        .put("reporter", user(40, "举报人", "reporter"))
        .put("article", articleReference())
        .put("createdAt", "2026-11-03T00:00:00Z")

    /** 构造一条待处理评论举报，供评论举报面板读取。 */
    private fun commentReport(): JSONObject = JSONObject()
        .put("id", 602)
        .put("key", "comment-602")
        .put("source", "comment")
        .put("sourceLabel", "评论举报")
        .put("status", "pending")
        .put("reason", "spam")
        .put("detail", "测试评论举报")
        .put("reporter", user(41, "举报人二", "reporter-two"))
        .put("article", articleReference())
        .put("comment", JSONObject().put("id", 3).put("body", "待处理评论").put("status", "active"))
        .put("createdAt", "2026-11-03T00:00:00Z")

    /** 构造举报记录共享的文章摘要，避免两个来源使用不同文章编号。 */
    private fun articleReference(): JSONObject = JSONObject()
        .put("id", 101)
        .put("title", "测试文章")
        .put("slug", "test-article")
        .put("author", user(20, "作者", "article-author"))

    /** 构造统一用户对象，避免夹具因缺少昵称或用户名而走空值兜底。 */
    private fun user(id: Int, nickname: String, username: String): JSONObject = JSONObject()
        .put("id", id)
        .put("nickname", nickname)
        .put("username", username)
}
