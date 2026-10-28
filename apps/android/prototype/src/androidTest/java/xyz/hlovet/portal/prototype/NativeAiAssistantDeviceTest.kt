package xyz.hlovet.portal.prototype

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证用户侧原生 AI 助手的对话、受控工具和文章草稿确认闭环，不访问外部模型服务。 */
@RunWith(AndroidJUnit4::class)
class NativeAiAssistantDeviceTest {
    @get:Rule val compose = createComposeRule()
    // 设备代理可能改写本地 MockWebServer 请求，回环规则确保测试只访问本机夹具。
    @get:Rule val loopbackNetwork = NativeLoopbackNetworkRule()

    private val contentHost = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"
    private val session = NativeSession(
        accessToken = "fixture-token",
        refreshToken = "fixture-refresh",
        userId = 7,
        username = "fixture-user",
        nickname = "Fixture user",
        isSuperAdmin = false,
        isAdministrator = false,
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
    )

    /** 启动只返回固定业务数据的本地接口，并保存当前语言以隔离测试状态。 */
    @Before
    fun startFixture() {
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 根据原生页面实际请求路径返回可验证的对话、工具和草稿结果。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    request.method == "GET" && path == "/ai/conversations" -> json(JSONObject().put("items", JSONArray()))
                    request.method == "GET" && path == "/ai/tools" -> json(tools())
                    request.method == "GET" && path == "/ai/media-capabilities" -> json(JSONObject().put("items", JSONArray()))
                    request.method == "GET" && path == "/ai/media-tasks" -> json(JSONObject().put("items", JSONArray()))
                    request.method == "POST" && path == "/ai/chat" -> json(
                        JSONObject()
                            .put("conversationId", 12)
                            .put("messageId", 33)
                            .put("title", "测试对话")
                            .put("text", "站内回答")
                            .put("sources", JSONArray())
                            .put("durationMs", 12),
                    )
                    request.method == "POST" && path == "/ai/tools/create_article_draft" -> json(draftPreview())
                    request.method == "POST" && path == "/ai/tool-invocations/44/confirm" -> json(
                        JSONObject()
                            .put("success", true)
                            .put("invocationId", 44)
                            .put("article", JSONObject().put("id", 91).put("slug", "native-ai-draft").put("title", "移动端测试文章")),
                    )
                    else -> json(JSONObject().put("message", "Unexpected fixture request: ${request.method} $path"), 404)
                }
            }
        }
        server.start(39871)
    }

    /** 卸载组合后关闭本地服务，并恢复测试前的语言设置。 */
    @After
    fun stopFixture() {
        if (!::server.isInitialized) return
        try {
            contentHost.dispose()
        } finally {
            try {
                server.shutdown()
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 验证用户可以从输入问题得到站内回答，并且页面显示受控工具入口。 */
    @Test
    fun chatAndControlledToolsAreAvailableToUser() {
        show()
        compose.onNodeWithText("受控工具").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("请介绍站内文章")
        compose.onNodeWithContentDescription("发送").performClick()
        waitText("站内回答")
        compose.onNodeWithText("站内回答").assertExists()
    }

    /** 验证文章草稿先生成预览，只有点击确认后才调用创建接口并显示成功提示。 */
    @Test
    fun articleDraftRequiresPreviewAndConfirmation() {
        show()
        compose.onNodeWithText("创建文章草稿").performClick()
        compose.onNode(hasSetTextAction() and hasText("文章描述")).performTextReplacement("写一篇移动端测试文章")
        compose.onNodeWithText("生成预览").performClick()
        waitText("草稿预览，确认后才会写入文章")
        compose.onNodeWithText("移动端测试文章").assertExists()
        compose.onNodeWithText("确认创建").performClick()
        waitText("文章草稿“移动端测试文章”已创建。")
    }

    /** 挂载用户 AI 页面，并等待首次接口加载完成后再执行交互。 */
    private fun show() {
        contentHost.setContent {
            HlovetTheme {
                NativeAiScreen(session = session, onBack = {}, onOpenSource = {})
            }
        }
        waitText("受控工具")
    }

    /** 等待页面出现指定文字，避免把网络响应时序当成同步状态使用。 */
    private fun waitText(value: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 构造用户侧可见的受控工具定义，覆盖只读工具和需要确认的写入工具。 */
    private fun tools(): JSONArray = JSONArray()
        .put(
            JSONObject()
                .put("name", "search_visible_articles")
                .put("label", "搜索可见文章")
                .put("labelEn", "Search visible articles")
                .put("description", "只搜索当前账号有权限查看的站内文章。")
                .put("descriptionEn", "Search visible articles.")
                .put("readOnly", true)
                .put("requiresConfirmation", false),
        )
        .put(
            JSONObject()
                .put("name", "create_article_draft")
                .put("label", "创建文章草稿")
                .put("labelEn", "Create article draft")
                .put("description", "预览后确认才会写入文章。")
                .put("descriptionEn", "Confirm after preview before writing.")
                .put("readOnly", false)
                .put("requiresConfirmation", true),
        )

    /** 构造服务端返回的待确认草稿预览和短期确认令牌。 */
    private fun draftPreview(): JSONObject = JSONObject()
        .put("invocationId", 44)
        .put("status", "awaiting_confirmation")
        .put("requiresConfirmation", true)
        .put("confirmationToken", "fixture-confirmation-token")
        .put(
            "preview",
            JSONObject()
                .put("title", "移动端测试文章")
                .put("summary", "用于验证原生 AI 草稿流程。")
                .put("category", "测试")
                .put("tags", "Android")
                .put("suggestedTags", JSONArray().put("移动端"))
                .put("content", "这是草稿正文。"),
        )

    /** 生成 JSON 响应并统一设置接口内容类型。 */
    private fun json(value: JSONObject, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(value.toString())

    /** 把数组包装成 JSON 响应，供工具列表接口直接返回数组结构。 */
    private fun json(value: JSONArray, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(value.toString())
}
