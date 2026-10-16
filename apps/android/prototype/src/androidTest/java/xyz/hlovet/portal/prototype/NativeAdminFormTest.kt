package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
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
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 用隔离 HTTP 服务验证真实管理表单的校验、反馈与权限，不代表真实数据库联调。 */
@RunWith(AndroidJUnit4::class)
class NativeAdminFormTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val evidence = object : TestWatcher() {
        override fun starting(description: Description) { currentTest = description.methodName }
        override fun failed(error: Throwable, description: Description) {
            runCatching { capture("failed-${description.methodName}") }
            runCatching {
                File(evidenceDirectory(), "failed-${description.methodName}.txt").writeText(
                    compose.onAllNodes(isRoot()).printToString(maxDepth = 30) + "\nRequests:\n" +
                        calls.joinToString("\n") { "${it.first} ${it.second}" })
            }
        }
    }

    private lateinit var server: MockWebServer
    private val contentHost = NativeComposeTestHost(compose)
    private var currentTest = ""
    private val calls = CopyOnWriteArrayList<Pair<String, String>>()
    private var oldLocale = "zh-CN"
    @Volatile private var responseOverride: (RecordedRequest) -> MockResponse? = { null }
    private var announcement: JSONObject? = null
    private var category = JSONObject("""{"id":21,"kind":"navigation","name":"Test category","status":"active","entries":[]}""")
    private val topic = JSONObject("""{"id":31,"title":"Test topic","slug":"test-topic","articles":[{"id":41,"title":"Included article"}]}""")
    private val session = NativeSession(
        accessToken = "fixture-token", refreshToken = "fixture-refresh", userId = 1,
        username = "fixture-admin", nickname = "Fixture admin", isSuperAdmin = true, isAdministrator = true,
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
    )

    @Before
    fun startFixture() {
        // Opt in using a preview build. Never send fixture credentials to a real server.
        assumeTrue("Run with -PpreviewApiBaseUrl=http://127.0.0.1:39871", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.add(request.method.orEmpty() to request.path.orEmpty().substringBefore('?'))
                return responseOverride(request) ?: defaultResponse(request)
            }
        }
        server.start(39871)
    }

    @After
    fun stopFixture() {
        if (!::server.isInitialized) return
        try {
            contentHost.dispose()
        } finally {
            try { server.shutdown() }
            finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) } }
        }
    }

    @Test
    fun announcementDraftSuccessIsVisibleInsideEditor() {
        show { NativeAdminAnnouncementsScreen(session, {}) }
        compose.onNodeWithContentDescription("新建公告").performClick()
        fill("标题", "Fixture announcement")
        fill("正文", "Fixture body")
        clickDialog("保存草稿")
        waitText("公告草稿已保存。")
        dialogText("公告草稿已保存。").assertIsDisplayed()
        capture("announcement-saved")
        assertEquals(1, count("POST", "/announcements/admin"))
        assertTrue(announcement?.optString("content") == "Fixture body")
    }

    @Test
    fun announcementPublishFailureRetainsDraftIdForRetry() {
        val attempts = AtomicInteger()
        responseOverride = { request ->
            if (request.path == "/announcements/admin/11/publish" && attempts.incrementAndGet() == 1) failure("Publish unavailable") else null
        }
        show { NativeAdminAnnouncementsScreen(session, {}) }
        compose.onNodeWithContentDescription("新建公告").performClick()
        fill("标题", "Fixture announcement")
        fill("正文", "Fixture body")
        compose.onNodeWithTag("announcement-publish").assertIsDisplayed().performClick()
        waitText("Publish unavailable")
        dialogText("Publish unavailable").assertIsDisplayed()
        capture("announcement-publish-error")
        compose.onNodeWithTag("announcement-publish").assertIsDisplayed().performClick()
        waitText("公告已发布。")
        assertEquals(1, count("POST", "/announcements/admin"))
        assertEquals(1, count("PATCH", "/announcements/admin/11"))
        assertEquals(2, count("POST", "/announcements/admin/11/publish"))
    }

    @Test
    fun announcementSaveErrorKeepsInputAndAllowsRetry() {
        val attempts = AtomicInteger()
        responseOverride = { request ->
            if (request.method == "POST" && request.path == "/announcements/admin" && attempts.incrementAndGet() == 1) failure("Save unavailable") else null
        }
        show { NativeAdminAnnouncementsScreen(session, {}) }
        compose.onNodeWithContentDescription("新建公告").performClick()
        fill("标题", "Retry title")
        clickDialog("保存草稿")
        waitText("Save unavailable")
        dialogText("Save unavailable").assertIsDisplayed()
        clickDialog("保存草稿")
        waitText("公告草稿已保存。")
        assertEquals("Retry title", announcement?.optString("title"))
    }

    @Test
    fun portalCategoryValidationAndNetworkErrorAreVisibleAndRecoverable() {
        val attempts = AtomicInteger()
        responseOverride = { request ->
            if (request.method == "POST" && request.path == "/portal/admin/categories" && attempts.incrementAndGet() == 1) failure("Category unavailable") else null
        }
        show { NativeAdminPortalContentScreen(session, {}) }
        waitText("Test category")
        compose.onNodeWithContentDescription("新建分类").performClick()
        clickDialog("保存")
        dialogText("分类名称不能为空。").assertIsDisplayed()
        assertEquals(0, count("POST", "/portal/admin/categories"))
        fill("分类名称", "New category")
        clickDialog("保存")
        waitText("Category unavailable")
        dialogText("Category unavailable").assertIsDisplayed()
        capture("portal-category-error")
        clickDialog("保存")
        waitText("分类已创建。")
        waitText("New category")
    }

    @Test
    fun portalEntryValidationAndNetworkErrorAreVisible() {
        responseOverride = { request ->
            if (request.method == "POST" && request.path == "/portal/admin/entries") failure("Entry unavailable") else null
        }
        show { NativeAdminPortalContentScreen(session, {}) }
        waitText("Test category")
        compose.onAllNodesWithContentDescription("新建条目")[0].performClick()
        clickDialog("保存")
        dialogText("条目标题不能为空。").assertIsDisplayed()
        fill("标题", "Entry title")
        clickDialog("保存")
        waitText("Entry unavailable")
        dialogText("Entry unavailable").assertIsDisplayed()
        capture("portal-entry-error")
    }

    @Test
    fun portalDeleteFailureIsVisibleInConfirmation() {
        responseOverride = { request -> if (request.method == "DELETE") failure("Delete unavailable") else null }
        show { NativeAdminPortalContentScreen(session, {}) }
        waitText("Test category")
        compose.onNodeWithContentDescription("删除分类").performClick()
        clickDialog("删除")
        waitText("Delete unavailable")
        dialogText("Delete unavailable").assertIsDisplayed()
    }

    @Test
    fun topicRoleValidationIsVisibleWithoutSendingMutation() {
        show { NativeTopicManagementScreen(session, {}, {}) }
        waitText("Test topic")
        compose.onNodeWithContentDescription("新建专题").performClick()
        fill("专题名称", "Role-restricted topic")
        dialogText("按角色").performScrollTo().performClick()
        clickDialog("保存")
        dialogText("按角色可见时至少选择一个角色。").assertIsDisplayed()
        capture("topic-role-error")
        assertEquals(0, count("POST", "/discovery/admin/topics"))
    }

    @Test
    fun topicNetworkErrorIsVisibleAndRetrySaves() {
        val attempts = AtomicInteger()
        responseOverride = { request ->
            if (request.method == "POST" && request.path == "/discovery/admin/topics" && attempts.incrementAndGet() == 1) failure("Topic unavailable") else null
        }
        show { NativeTopicManagementScreen(session, {}, {}) }
        waitText("Test topic")
        compose.onNodeWithContentDescription("新建专题").performClick()
        fill("专题名称", "Retry topic")
        clickDialog("保存")
        waitText("Topic unavailable")
        dialogText("Topic unavailable").assertIsDisplayed()
        clickDialog("保存")
        waitText("专题已创建。")
        waitText("Retry topic")
    }

    @Test
    fun topicRetryDoesNotRepeatCompletedArticleRemoval() {
        responseOverride = { request ->
            if (request.path == "/discovery/admin/topics/31/articles/order") failure("Reorder unavailable") else null
        }
        show { NativeTopicManagementScreen(session, {}, {}) }
        waitText("Test topic")
        compose.onAllNodesWithContentDescription("编辑专题")[0].performClick()
        compose.onNodeWithContentDescription("移除文章").performScrollTo().performClick()
        clickDialog("保存")
        waitText("Reorder unavailable")
        dialogText("Reorder unavailable").assertIsDisplayed()
        clickDialog("保存")
        waitText("专题设置已保存。")
        assertEquals(1, count("DELETE", "/discovery/admin/topics/31/articles/41"))
        assertEquals(2, count("PATCH", "/discovery/admin/topics/31"))
        assertEquals(0, count("POST", "/discovery/admin/topics"))
    }

    /** 校验错误和请求失败留在当前积分弹窗，重试成功才关闭并显示账本更新。 */
    @Test
    fun pointAdjustmentValidationErrorAndSuccessUseActiveDialog() {
        // 首次提交模拟网络失败，确认用户输入不丢失且第二次真正发出请求。
        val attempts = AtomicInteger()
        responseOverride = { request ->
            if (request.path == "/resources/admin/points/top-up" && attempts.incrementAndGet() == 1) failure("Points unavailable") else null
        }
        show { NativeAdminResourcesScreen(session, {}) }
        waitText("暂无积分调整记录。")
        compose.onNodeWithContentDescription("积分调整").performClick()
        clickDialog("确认提交")
        dialogText("请填写有效的 @用户名、正整数积分和唯一事件键。").assertIsDisplayed()
        fill("目标 @username", "@fixture-user")
        fill("积分数量", "5")
        fill("唯一事件键", "fixture-top-up")
        clickDialog("确认提交")
        waitText("Points unavailable")
        dialogText("Points unavailable").assertIsDisplayed()
        capture("point-adjustment-error")
        clickDialog("确认提交")
        waitText("积分账本已更新。")
        assertEquals(2, count("POST", "/resources/admin/points/top-up"))
    }

    /** 重复事件返回 applied=false 时显示已处理，不声称再次补发积分。 */
    @Test
    fun duplicatePointAdjustmentShowsAlreadyProcessed() {
        // 使用真实客户端解析幂等字段，不根据 HTTP 200 自行推断业务成功。
        responseOverride = { request ->
            if (request.path == "/resources/admin/points/top-up") json(JSONObject().put("applied", false)) else null
        }
        show { NativeAdminResourcesScreen(session, {}) }
        waitText("暂无积分调整记录。")
        compose.onNodeWithContentDescription("积分调整").performClick()
        fill("目标 @username", "@fixture-user")
        fill("积分数量", "5")
        fill("唯一事件键", "fixture-top-up")
        clickDialog("确认提交")
        waitText("该事件键已经处理过。")
        compose.onNodeWithText("积分账本已更新。").assertDoesNotExist()
        assertEquals(1, count("POST", "/resources/admin/points/top-up"))
    }

    /** 普通管理员只读资源列表，不展示服务器仅向超级管理员开放的写入动作。 */
    @Test
    fun ordinaryAdminCanReadButCannotMutateResources() {
        // 真实页面仍读取交付与调整记录，但任何写入入口都必须缺失。
        show { NativeAdminResourcesScreen(session.copy(isSuperAdmin = false, isAdministrator = true), {}) }
        waitText("Fixture resource")
        compose.onNodeWithContentDescription("积分调整").assertDoesNotExist()
        compose.onNodeWithContentDescription("记录失败").assertDoesNotExist()
        compose.onNodeWithContentDescription("退款").assertDoesNotExist()
        assertEquals(1, count("GET", "/resources/admin/deliveries"))
        assertEquals(1, count("GET", "/resources/admin/points/adjustments"))
        assertTrue(calls.none { it.first != "GET" })
    }

    @Test
    fun deliveryFailureAndRefundErrorsStayInTheirDialogs() {
        responseOverride = { request -> if (request.method == "POST") failure("Delivery unavailable") else null }
        show { NativeAdminResourcesScreen(session, {}) }
        waitText("Fixture resource")
        compose.onNodeWithContentDescription("记录失败").performClick()
        clickDialog("确认记录")
        dialogText("请填写失败原因。").assertIsDisplayed()
        fill("失败原因", "Fixture failure")
        clickDialog("确认记录")
        waitText("Delivery unavailable")
        dialogText("Delivery unavailable").assertIsDisplayed()
        clickDialog("取消")
        compose.onNodeWithContentDescription("退款").performClick()
        clickDialog("确认退款")
        waitText("Delivery unavailable")
        dialogText("Delivery unavailable").assertIsDisplayed()
    }

    @Test
    fun nonAdminCannotCreateAnnouncementsOrSendAdminRequests() {
        show { NativeAdminAnnouncementsScreen(session.copy(isSuperAdmin = false, isAdministrator = false), {}) }
        waitText("当前账号没有公告管理权限。")
        compose.onNodeWithContentDescription("新建公告").assertIsNotEnabled()
        compose.onNodeWithContentDescription("刷新").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun nonAdminCannotLoadPortalAdminData() {
        show { NativeAdminPortalContentScreen(session.copy(isSuperAdmin = false, isAdministrator = false), {}) }
        waitText("当前账号没有内容管理权限。")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun nonAdminCannotLoadTopicAdminData() {
        show { NativeTopicManagementScreen(session.copy(isSuperAdmin = false, isAdministrator = false), {}, {}) }
        waitText("当前账号没有专题管理权限。")
        compose.onNodeWithContentDescription("新建专题").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun nonAdminCannotLoadResourceAdminData() {
        show { NativeAdminResourcesScreen(session.copy(isSuperAdmin = false, isAdministrator = false), {}) }
        waitText("当前账号没有资源与积分管理权限。")
        compose.onNodeWithContentDescription("刷新").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }

    private fun show(content: @Composable () -> Unit) {
        contentHost.setContent { HlovetTheme { content() } }
    }

    private fun dialogText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun fill(label: String, value: String) {
        compose.onNode(hasSetTextAction() and hasText(label) and hasAnyAncestor(isDialog()))
            .performScrollTo().performTextReplacement(value)
    }

    private fun clickDialog(text: String) {
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
    }

    private fun waitText(text: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            runCatching { capture("timeout-$currentTest") }
            runCatching {
                File(evidenceDirectory(), "timeout-$currentTest.txt").writeText(
                    compose.onAllNodes(isRoot()).printToString(maxDepth = 30) + "\nRequests:\n" +
                        calls.joinToString("\n") { "${it.first} ${it.second}" })
            }
            throw error
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = evidenceDirectory()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun evidenceDirectory(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)).absolutePath
        return File(path, "admin-form-evidence").apply { mkdirs() }
    }

    private fun count(method: String, path: String) = calls.count { it == (method to path) }

    private fun json(value: Any, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(value.toString())

    private fun failure(message: String) = json(JSONObject().put("message", message), 503)

    private fun page(items: JSONArray = JSONArray()) = JSONObject().put("items", items).put("total", items.length())
        .put("page", 1).put("pageSize", 12).put("totalPages", 1)

    /** 为 request 返回明确契约的隔离管理响应；未声明路线直接拒绝，防止误判通过。 */
    private fun defaultResponse(request: RecordedRequest): MockResponse {
        // 按实际路由匹配只读列表与写入结果，积分接口必须带服务端 applied 标志。
        val path = request.path.orEmpty().substringBefore('?')
        if (request.method == "GET") return when (path) {
            "/roles" -> json("[]")
            "/announcements/admin" -> json(page(JSONArray().apply { announcement?.let { put(it) } }))
            "/portal/admin" -> json(JSONObject().put("categories", JSONArray().put(category)))
            "/discovery/admin/topics" -> json(page(JSONArray().put(topic)))
            "/articles/mine" -> json(page())
            "/resources/admin/deliveries" -> json(page(JSONArray("""[{"id":51,"article":{"title":"Fixture resource"},"status":"unlocked"}]""")))
            "/resources/admin/points/adjustments" -> json(page())
            else -> json(JSONObject().put("message", "Unexpected fixture GET $path"), 404)
        }
        val body = request.body.clone().readUtf8().takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        return when (path) {
            "/announcements/admin", "/announcements/admin/11" -> {
                announcement = body.put("id", 11).put("status", "draft")
                json(announcement!!)
            }
            "/announcements/admin/11/publish" -> json(announcement!!.put("status", "published"))
            "/portal/admin/categories" -> { category = body.put("id", 22); json(category) }
            "/discovery/admin/topics" -> json(body.put("id", 32))
            "/discovery/admin/topics/31" -> {
                body.keys().forEach { key -> topic.put(key, body.get(key)) }
                json(topic)
            }
            "/discovery/admin/topics/31/articles/41" -> json(topic.put("articles", JSONArray()))
            "/resources/admin/points/top-up" -> json(JSONObject().put("applied", true))
            else -> json(JSONObject().put("message", "Unexpected fixture mutation $path"), 404)
        }
    }
}
