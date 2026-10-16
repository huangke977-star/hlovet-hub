package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 验证真实 AI 表单组件在窄屏中的布局、菜单交互和凭据操作，不访问外部模型服务。 */
@RunWith(AndroidJUnit4::class)
class NativeAiControlsTest {
    @get:Rule val compose = createComposeRule()
    private val contentHost = NativeComposeTestHost(compose)
    private var oldLocale = "zh-CN"

    @Before fun start() {
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }
    @After fun stop() {
        try { contentHost.dispose() }
        finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) } }
    }

    /** 模型选择器与拉取按钮等高，展开后的真实弹层清晰可见，点击选项回传正确模型标识。 */
    @Test fun modelFetchButtonIsInsideRowAndMatchesSelectorHeight() {
        // 使用长模型名覆盖窄屏截断，保持真实共享组件，不复制菜单实现。
        var selected = ""
        val model = "a-long-model-name-with-a-version-2026-10-13"
        contentHost.setContent { HlovetTheme {
            Column(Modifier.width(320.dp).padding(12.dp).testTag("frame")) {
                NativeAiModelField("模型", model, listOf(NativeAiModelOption(model, model), NativeAiModelOption("second", "Second model")), true, false, { selected = it }, {})
            }
        } }
        // 几何断言同时检查按钮高度、底边及父容器边界，防止布局溢出。
        val selector = compose.onNodeWithTag("ai-select-模型").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val fetch = compose.onNodeWithTag("ai-fetch-模型").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertEquals(selector.height, fetch.height, 1f)
        assertEquals(selector.bottom, fetch.bottom, 1f)
        assertTrue(fetch.right <= frame.right && selector.right < fetch.left)
        captureAiEvidence("model-controls")
        // 截图必须在菜单展开后保存；只截选择框不能证明弹层的实际背景和可点击性。
        compose.onNodeWithTag("ai-select-模型").performClick()
        compose.onNodeWithText("Second model").assertIsDisplayed()
        captureAiEvidence("model-menu-expanded")
        // 选择候选项后核对回传代码，不能只检查菜单文字出现。
        compose.onNodeWithText("Second model").performClick()
        assertEquals("second", selected)
    }

    @Test fun loadingDisablesOnlyFetchAndLeavesSelectorUsable() {
        contentHost.setContent { HlovetTheme {
            NativeAiModelField("模型", "saved", emptyList(), true, true, {}, {})
        } }
        compose.onNodeWithTag("ai-fetch-模型").assertIsNotEnabled()
        compose.onNodeWithTag("ai-select-模型").assertIsEnabled()
    }

    @Test fun secretVisibilityAndClearAreInsideInputAndClearCanBeCancelled() {
        contentHost.setContent { HlovetTheme {
            var clear by remember { mutableStateOf(false) }
            Column(Modifier.width(320.dp).padding(12.dp)) {
                NativeAiAdminSecret("API Key", "fixture-only-key", true, clear, true, {}, { clear = !clear })
            }
        } }
        val field = compose.onNodeWithTag("ai-key-API Key").fetchSemanticsNode()
        val show = compose.onNodeWithContentDescription("查看 API Key").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithContentDescription("清除已保存 API Key").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(show.right <= delete.left && delete.right <= field.boundsInRoot.right)
        assertTrue(field.config.contains(SemanticsProperties.Password))
        assertFalse(renderedKeyText() == "fixture-only-key")
        compose.onNodeWithContentDescription("查看 API Key").performClick()
        assertEquals("fixture-only-key", renderedKeyText())
        captureAiEvidence("revealed-key-controls")
        compose.onNodeWithContentDescription("隐藏 API Key").performClick()
        assertFalse(renderedKeyText() == "fixture-only-key")
        compose.onNodeWithContentDescription("清除已保存 API Key").performClick()
        compose.onNodeWithContentDescription("取消清除 API Key").performClick()
        compose.onNodeWithContentDescription("清除已保存 API Key").assertIsDisplayed()
        captureAiEvidence("saved-key-controls")
    }

    @Test fun unconfiguredSecretHasNoEmptyClearSlot() {
        contentHost.setContent { HlovetTheme {
            Column(Modifier.width(320.dp).padding(12.dp)) {
                NativeAiAdminSecret("API Key", "", false, false, true, {}, {})
            }
        } }
        val field = compose.onNodeWithTag("ai-key-API Key").fetchSemanticsNode().boundsInRoot
        val eye = compose.onNodeWithContentDescription("查看 API Key").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(field.right - eye.right < 16f)
        compose.onNodeWithContentDescription("查看 API Key").assertIsNotEnabled()
        captureAiEvidence("empty-key-controls")
    }

    @Test fun pricesAreThreeRowsTwoColumnsAndMapToCorrectFields() {
        val edited = mutableMapOf<Int, Long>()
        contentHost.setContent { HlovetTheme {
            Column(Modifier.width(320.dp).padding(12.dp)) {
                NativeAiPriceGrid(1_000_000, 100_000, 2_000_000, 3_000_000, 200_000, 4_000_000, true) { index, value -> edited[index] = value }
            }
        } }
        val labels = listOf("输入价", "缓存输入价", "输出价")
        val fallbackLabels = listOf("备用输入价", "备用缓存价", "备用输出价")
        var previousBottom = 0f
        labels.forEachIndexed { index, label ->
            val left = compose.onNode(hasSetTextAction() and hasText(label)).fetchSemanticsNode().boundsInRoot
            val right = compose.onNode(hasSetTextAction() and hasText(fallbackLabels[index])).fetchSemanticsNode().boundsInRoot
            assertEquals(left.top, right.top, 1f)
            assertEquals(left.width, right.width, 1f)
            assertTrue(left.right < right.left && left.top >= previousBottom)
            previousBottom = left.bottom
        }
        compose.onNode(hasSetTextAction() and hasText("缓存输入价")).performTextReplacement("0.123456")
        compose.onNode(hasSetTextAction() and hasText("备用缓存价")).performTextReplacement("0.654321")
        assertEquals(123456L, edited[1])
        assertEquals(654321L, edited[4])
        captureAiEvidence("price-grid")
    }

    @Test fun englishModelControlsStillFitNarrowWidth() {
        NativeI18n.applyLocale("en-US")
        contentHost.setContent { HlovetTheme {
            Column(Modifier.width(320.dp).padding(12.dp)) {
                NativeAiModelField("Fallback model", "long-model-name-2026-10-13", emptyList(), true, false, {}, {})
                NativeAiAdminSecret("Fallback API key", "", true, false, true, {}, {})
                NativeAiPriceGrid(1, 2, 3, 4, 5, 6, true) { _, _ -> }
            }
        } }
        compose.onNodeWithTag("ai-fetch-Fallback model").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show Fallback API key").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear saved Fallback API key").assertIsDisplayed()
        captureAiEvidence("english-controls")
    }

    private fun renderedKeyText(): String {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("ai-key-API Key").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().layoutInput.text.text
    }
}

/** 核对 AI 管理界面与 HTTP 契约，只访问显式启用的设备本地测试服务。 */
@RunWith(AndroidJUnit4::class)
class NativeAdminAiTest {
    @get:Rule val compose = createComposeRule()
    private val contentHost = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"
    private val calls = CopyOnWriteArrayList<Triple<String, String, JSONObject>>()
    @Volatile private var responseOverride: (RecordedRequest) -> MockResponse? = { null }
    private val session = NativeSession(accessToken = "fixture-token", refreshToken = "fixture-refresh", userId = 1, username = "fixture-admin", nickname = "Fixture admin", isSuperAdmin = true, isAdministrator = true,
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46))
    private val main = configuration().put("encryptionConfigured", true).put("recommendation", JSONObject("""{"cpuCores":2,"totalMemoryMiB":2048,"globalConcurrency":2,"userConcurrency":1,"maxGlobalConcurrency":4,"maxUserConcurrency":2}"""))
    private val capability = configuration().put("capability", "embedding").put("label", JSONObject("""{"zh":"向量模型","en":"Embedding"}""")).put("provider", "openai").put("baseUrl", "https://api.openai.com/v1").put("fallbackEnabled", true)
    private var knowledgeOverview = JSONObject()
    private var knowledgeDocuments = JSONArray()

    @Before fun startFixture() {
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.clone().readUtf8().takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
                calls.add(Triple(request.method.orEmpty(), request.path.orEmpty().substringBefore('?'), body))
                return responseOverride(request) ?: defaultResponse(request, body)
            }
        }
        server.start(39871)
    }

    @After fun stopFixture() {
        if (!::server.isInitialized) return
        try { contentHost.dispose() }
        finally {
            try { server.shutdown() }
            finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) } }
        }
    }

    @Test fun mainFetchSelectsFirstModelAndUsesSavedCredential() {
        show()
        primary("ai-fetch-模型").performScrollTo().performClick()
        waitText("已获取 2 个模型。")
        primary("ai-select-模型").assertIsDisplayed()
        assertEquals("deepseek", lastBody("POST", "/ai/admin/models").getString("provider"))
        assertFalse(lastBody("POST", "/ai/admin/models").has("apiKey"))
        assertFalse(lastBody("POST", "/ai/admin/models").has("capability"))
        assertFalse(lastBody("POST", "/ai/admin/models").has("credential"))
        assertTrue(compose.onAllNodesWithText("First model").fetchSemanticsNodes().isNotEmpty())
        captureAiEvidence("main-models-loaded")
    }

    @Test fun fallbackFetchUsesFallbackCredentialAndUnsavedKey() {
        main.put("fallbackEnabled", true)
        show()
        primaryField("备用 API Key").performScrollTo().performTextReplacement("fixture-fallback-key")
        primary("ai-fetch-备用模型").performScrollTo().performClick()
        waitText("已获取 2 个模型。")
        val body = lastBody("POST", "/ai/admin/models")
        assertEquals("fallback", body.getString("credential"))
        assertEquals("fixture-fallback-key", body.getString("apiKey"))
        assertEquals("https://relay.example/v1", body.getString("baseUrl"))
        assertFalse(body.has("capability"))
    }

    @Test fun capabilityPrimaryAndFallbackFetchUseCorrectScope() {
        show()
        capability("ai-fetch-模型").performScrollTo().performClick()
        waitText("已获取 2 个模型。")
        assertEquals("embedding", lastBody("POST", "/ai/admin/models").getString("capability"))
        assertFalse(lastBody("POST", "/ai/admin/models").has("credential"))
        capability("ai-fetch-备用模型").performScrollTo().performClick()
        compose.waitUntil(10_000) { calls.count { it.first == "POST" && it.second == "/ai/admin/models" } == 2 }
        waitEnabled(capability("ai-fetch-备用模型"))
        val body = lastBody("POST", "/ai/admin/models")
        assertEquals("embedding", body.getString("capability"))
        assertEquals("fallback", body.getString("credential"))
        captureAiEvidence("capability-models-loaded")
    }

    @Test fun clearSavedKeyCanBeCancelledWithoutClearingOnSave() {
        show()
        primaryDescription("清除已保存 API Key").performScrollTo().performClick()
        primaryDescription("取消清除 API Key").performClick()
        primaryText("保存配置").performScrollTo().performClick()
        waitText("AI 配置已保存。")
        val body = lastBody("PATCH", "/ai/admin/configuration")
        assertFalse(body.has("clearApiKey"))
        assertFalse(body.has("apiKey"))
        compose.onNodeWithText("AI 配置已保存。").assertIsDisplayed()
        captureAiEvidence("main-saved")
    }

    @Test fun clearSavedKeyIsOnlyAppliedOnSave() {
        show()
        primaryDescription("清除已保存 API Key").performScrollTo().performClick()
        assertTrue(calls.none { it.first == "PATCH" })
        primaryText("保存配置").performScrollTo().performClick()
        waitText("AI 配置已保存。")
        assertTrue(lastBody("PATCH", "/ai/admin/configuration").getBoolean("clearApiKey"))
        assertFalse(lastBody("PATCH", "/ai/admin/configuration").has("apiKey"))
    }

    @Test fun newKeyCancelsPendingClearAndIsSubmittedOnce() {
        show()
        primaryDescription("清除已保存 API Key").performScrollTo().performClick()
        primaryField("API Key").performTextReplacement("fixture-new-key")
        primaryDescription("清除已保存 API Key").assertIsDisplayed()
        primaryText("保存配置").performScrollTo().performClick()
        waitText("AI 配置已保存。")
        val body = lastBody("PATCH", "/ai/admin/configuration")
        assertEquals("fixture-new-key", body.getString("apiKey"))
        assertFalse(body.has("clearApiKey"))
    }

    @Test fun emptyModelListAndRequestErrorsStayNearPicker() {
        val attempts = AtomicInteger()
        responseOverride = { request -> if (request.path == "/ai/admin/models") {
            if (attempts.incrementAndGet() == 1) json(JSONObject().put("message", "Models unavailable"), 503)
            else json(JSONObject().put("models", JSONArray()))
        } else null }
        show()
        primary("ai-fetch-模型").performScrollTo().performClick()
        waitText("Models unavailable")
        compose.onNodeWithText("Models unavailable").assertIsDisplayed()
        primary("ai-fetch-模型").performClick()
        waitText("供应商未返回可选模型。")
        compose.onNodeWithText("供应商未返回可选模型。").assertIsDisplayed()
        captureAiEvidence("models-empty")
    }

    /** 切换供应商后才释放旧模型响应，确认迟到结果不会污染新供应商及保存配置。 */
    @Test fun responseForPreviousProviderDoesNotPopulateNewPicker() {
        // 用受控响应建立先切换、后返回的顺序，避免固定延迟早于设备交互完成。
        val releaseResponse = CountDownLatch(1)
        val responseReady = CountDownLatch(1)
        responseOverride = { request -> if (request.path == "/ai/admin/models") {
            if (releaseResponse.await(30, TimeUnit.SECONDS)) {
                responseReady.countDown()
                models()
            } else json(JSONObject().put("message", "Controlled model response was not released"), 503)
        } else null }
        try {
            // 确认模型请求已发起且 OpenAI 已选中，才允许旧供应商返回结果。
            show()
            primary("ai-fetch-模型").performScrollTo().performClick()
            compose.waitUntil(10_000) { calls.any { it.second == "/ai/admin/models" } }
            primary("ai-select-供应商").performScrollTo().performClick()
            compose.onNode(hasText("OpenAI") and hasClickAction()).performClick()
            assertTrue(primary("ai-select-供应商").fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text == "OpenAI" })
        } finally {
            // 断言失败也必须释放测试服务，避免关闭阶段被未完成请求阻塞。
            releaseResponse.countDown()
        }
        assertTrue("The controlled response must be delivered", responseReady.await(10, TimeUnit.SECONDS))
        // 等请求结束后核对候选项、反馈与保存结果，保留原始业务断言。
        waitEnabled(primary("ai-fetch-模型").performScrollTo())
        assertTrue(compose.onAllNodesWithText("First model").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("已获取 2 个模型。").fetchSemanticsNodes().isEmpty())
        primaryText("保存配置").performScrollTo().performClick()
        waitText("AI 配置已保存。")
        assertEquals("", lastBody("PATCH", "/ai/admin/configuration").getString("model"))
        assertEquals("https://api.openai.com/v1", lastBody("PATCH", "/ai/admin/configuration").getString("baseUrl"))
    }

    @Test fun changingProviderKeepsCustomRelayUrl() {
        main.put("baseUrl", "https://my-relay.example/v1")
        show()
        primary("ai-select-供应商").performScrollTo().performClick()
        compose.onNode(hasText("Google") and hasClickAction()).performClick()
        primaryText("保存配置").performScrollTo().performClick()
        waitText("AI 配置已保存。")
        assertEquals("https://my-relay.example/v1", lastBody("PATCH", "/ai/admin/configuration").getString("baseUrl"))
    }

    @Test fun pricingPresetUpdatesPrimaryPricesWithoutChangingFallback() {
        main.put("model", "deepseek-chat")
        show()
        primaryText("套用价格").performScrollTo().performClick()
        primaryText("保存配置").performScrollTo().performClick()
        waitText("AI 配置已保存。")
        val body = lastBody("PATCH", "/ai/admin/configuration")
        assertEquals("CNY", body.getString("billingCurrency"))
        assertEquals(2_000_000L, body.getLong("inputCostPerMillionMicros"))
        assertEquals(200_000L, body.getLong("cachedInputCostPerMillionMicros"))
        assertEquals(4_000_000L, body.getLong("outputCostPerMillionMicros"))
        assertEquals(3_000_000L, body.getLong("fallbackInputCostPerMillionMicros"))
    }

    @Test fun capabilitySaveIncludesCachePricesAndKeepsUnexposedSettings() {
        show()
        capabilityField("缓存输入价").performScrollTo().performTextReplacement("0.123456")
        capabilityField("备用缓存价").performScrollTo().performTextReplacement("0.654321")
        capabilityDescription("清除已保存 API Key").performScrollTo().performClick()
        capabilityText("保存").performScrollTo().performClick()
        waitText("向量模型 配置已保存。")
        val body = lastBody("PATCH", "/ai/admin/capabilities/embedding")
        assertEquals(123456L, body.getLong("cachedInputCostPerMillionMicros"))
        assertEquals(654321L, body.getLong("fallbackCachedInputCostPerMillionMicros"))
        assertEquals("request", body.getString("unitName"))
        assertEquals(1_048_576L, body.getLong("maxInputBytes"))
        assertEquals("USD", body.getString("billingCurrency"))
        assertTrue(body.getBoolean("clearApiKey"))
        compose.onNodeWithText("向量模型 配置已保存。").assertIsDisplayed()
        captureAiEvidence("capability-saved")
    }

    @Test fun savedConfigurationTestAndSaveFailureShowLocalFeedback() {
        responseOverride = { request -> if (request.method == "PATCH") json(JSONObject().put("message", "Save unavailable"), 503) else null }
        show()
        primaryText("测试连接").performScrollTo().performClick()
        waitText("Connection verified")
        compose.onNodeWithText("Connection verified").assertIsDisplayed()
        assertEquals(0, lastBody("POST", "/ai/admin/test-connection").length())
        primaryText("保存配置").performClick()
        waitText("Save unavailable")
        compose.onNodeWithText("Save unavailable").assertIsDisplayed()
        captureAiEvidence("save-error")
    }

    @Test fun encryptionNotConfiguredDisablesKeyChanges() {
        main.put("encryptionConfigured", false)
        show()
        primary("ai-key-API Key").performScrollTo().assertIsNotEnabled()
        primaryDescription("清除已保存 API Key").assertIsNotEnabled()
    }

    @Test fun regularAdminDoesNotSendSuperAdminRequests() {
        show(session.copy(isSuperAdmin = false))
        waitText("AI 配置仅超级管理员可访问。")
        assertTrue(calls.isEmpty())
    }

    @Test fun usagePercentagesAndAllAuditRecordsAreVisible() {
        responseOverride = { request -> when (request.path?.substringBefore('?')) {
            "/ai/admin/invocations" -> json(JSONObject("""{"today":{"cacheHitRate":25},"logs":[{"id":1,"operation":"chat","model":"fixture-chat-model","provider":"deepseek","status":"success","totalTokens":1000,"cachedPromptTokens":250,"durationMs":32}]}"""))
            "/ai/admin/usage" -> json(JSONObject("""{"byCapability":[{"capability":"embedding","requests":4,"successRate":75}]}"""))
            "/ai/admin/tools" -> json(JSONObject().put("items", JSONArray().apply {
                repeat(50) { index -> put(JSONObject().put("id", index + 1).put("toolName", "fixture-tool-${index + 1}").put("status", "success").put("username", "fixture-admin")) }
            }))
            else -> null
        } }
        show()
        compose.onNodeWithTag("ai-config-list").performScrollToNode(hasTestTag("ai-usage-card"))
        compose.onNode(hasText("缓存命中 25%", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("成功率 75%", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("调用记录（1）").performClick()
        compose.onNode(hasText("fixture-chat-model", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        captureAiEvidence("invocation-records")
        compose.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("工具审计（50）").performClick()
        compose.onNode(hasText("fixture-tool-50", substring = true) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        captureAiEvidence("tool-audit-last-record")
    }

    @Test fun knowledgeApiParsesAllDocumentsAndNullableMetadata() {
        knowledgeDocuments = JSONArray().apply { repeat(100) { put(knowledgeDocument(it + 1)) } }
        knowledgeDocuments.getJSONObject(0).put("embeddingModel", JSONObject.NULL).put("errorSummary", JSONObject.NULL)
            .put("updatedAt", JSONObject.NULL).put("lastIndexedAt", JSONObject.NULL)
        knowledgeDocuments.getJSONObject(99).put("embeddingModel", "fixture-embedding-model").put("lastIndexedAt", "2026-10-13T09:00:00Z")
        responseOverride = { request ->
            if (request.path?.substringBefore('?') == "/ai/admin/knowledge/documents") assertEquals("100", request.requestUrl?.queryParameter("limit"))
            null
        }
        val items = NativeAdminAiApi.documents(session.accessToken)
        assertEquals(100, items.size)
        assertEquals("", items.first().embeddingModel)
        assertEquals("", items.first().error)
        assertEquals("", items.first().updatedAt)
        assertEquals("", items.first().lastIndexedAt)
        assertEquals(100, items.last().id)
        assertEquals("fixture-embedding-model", items.last().embeddingModel)
        assertEquals("2026-10-13T09:00:00Z", items.last().lastIndexedAt)
    }

    @Test fun knowledgeDialogCanReachLastOf100Documents() {
        knowledgeDocuments = JSONArray().apply { repeat(100) { put(knowledgeDocument(it + 1)) } }
        knowledgeDocuments.getJSONObject(99).put("embeddingModel", "fixture-embedding-model").put("lastIndexedAt", "2026-10-13T09:00:00Z")
        show()
        openDocuments(100)
        compose.onNodeWithTag("ai-knowledge-documents").performScrollToNode(hasTestTag("ai-knowledge-document-100"))
        compose.onNodeWithTag("ai-knowledge-document-100").assertIsDisplayed()
        compose.onNodeWithText("fixture-document-100").assertIsDisplayed()
        compose.onNodeWithText("Embedding: fixture-embedding-model").assertIsDisplayed()
        compose.onNodeWithText("索引时间: 2026-10-13T09:00:00Z").assertIsDisplayed()
        compose.onNodeWithText("null", substring = true).assertDoesNotExist()
        captureAiEvidence("knowledge-last-of-100")
    }

    @Test fun documentIndexReportsKeywordAndVectorResultsWithoutQueueNotice() {
        knowledgeDocuments.put(knowledgeDocument(1, "pending"))
        val indexed = AtomicInteger()
        responseOverride = { request -> if (request.path == "/ai/admin/knowledge/documents/1/reindex") {
            val status = if (indexed.incrementAndGet() == 1) "keyword_ready" else "ready"
            knowledgeDocuments = JSONArray().put(knowledgeDocument(1, status).put("embeddingModel", if (status == "ready") "fixture-vector-model" else JSONObject.NULL)
                .put("vectorCount", if (status == "ready") 3 else 0).put("errorSummary", if (status == "keyword_ready") "Embedding is not configured" else JSONObject.NULL))
            json(JSONObject().put("id", 1).put("removed", false).put("status", status))
        } else null }
        show()
        openDocuments(1)
        compose.onNodeWithTag("ai-reindex-document-1").performClick()
        waitDialogText("“fixture-document-1”：关键词可用。")
        waitEnabled(compose.onNodeWithTag("ai-reindex-document-1"))
        compose.onNodeWithText("向量提示: Embedding is not configured").assertIsDisplayed()
        compose.onNodeWithText("已排队", substring = true).assertDoesNotExist()
        captureAiEvidence("knowledge-keyword-indexed")
        compose.onNodeWithTag("ai-reindex-document-1").performClick()
        waitDialogText("“fixture-document-1”：向量可用。")
        waitEnabled(compose.onNodeWithTag("ai-reindex-document-1"))
        compose.onNodeWithText("向量可用 · 3 片段 · 3 向量").assertIsDisplayed()
        compose.onNodeWithText("Embedding: fixture-vector-model").assertIsDisplayed()
        compose.onNodeWithText("Embedding is not configured", substring = true).assertDoesNotExist()
        captureAiEvidence("knowledge-vector-indexed")
        assertEquals(2, calls.count { it.first == "POST" && it.second.endsWith("/1/reindex") })
    }

    @Test fun bulkIndexShowsPartialFailureCountsAndFreshOverview() {
        responseOverride = { request -> if (request.path?.substringBefore('?') == "/ai/admin/knowledge/reindex") {
            assertEquals("50", request.requestUrl?.queryParameter("limit"))
            knowledgeOverview = JSONObject("""{"documents":10,"chunks":30,"vectors":6,"ready":2,"keywordReady":3,"pendingEmbedding":4,"failed":1,"semanticSearchAvailable":true,"keywordSearchAvailable":true}""")
            json(JSONObject("""{"scanned":10,"indexed":2,"keywordReady":3,"pending":4,"failed":1}""").put("overview", knowledgeOverview))
        } else null }
        show()
        knowledgeNode("ai-knowledge-reindex").performClick()
        waitText("已扫描 10 篇，向量 2 篇，关键词 3 篇，待处理 4 篇，失败 1 篇。")
        waitEnabled(knowledgeNode("ai-knowledge-refresh"))
        compose.onNodeWithText("10 篇文章 · 30 个片段 · 6 个向量").assertIsDisplayed()
        compose.onNodeWithText("向量就绪 2 · 关键词就绪 3 · 待向量 4 · 失败 1").assertIsDisplayed()
        compose.onNodeWithText("语义检索可用").assertIsDisplayed()
        assertEquals(1, calls.count { it.second == "/ai/admin/configuration" })
        captureAiEvidence("knowledge-partial-index-result")
    }

    @Test fun initialKnowledgeFailureLeavesModelConfigurationUsableAndCanRetry() {
        val reads = AtomicInteger()
        responseOverride = { request -> if (request.path == "/ai/admin/knowledge" && reads.incrementAndGet() == 1) {
            json(JSONObject().put("message", "Knowledge temporarily unavailable"), 503)
        } else null }
        show()
        primary("ai-fetch-模型").assertIsEnabled()
        knowledgeNode("ai-knowledge-refresh")
        waitText("Knowledge temporarily unavailable")
        compose.onNodeWithText("Knowledge temporarily unavailable").assertIsDisplayed()
        knowledgeNode("ai-knowledge-refresh").performClick()
        waitText("知识库已刷新。")
        compose.onNodeWithText("Knowledge temporarily unavailable").assertDoesNotExist()
        assertEquals(1, calls.count { it.second == "/ai/admin/configuration" })
        captureAiEvidence("knowledge-refresh-recovered")
    }

    @Test fun knowledgeReadAndIndexKeepUnsavedModelKeysPricesAndPicker() {
        main.put("fallbackEnabled", true)
        show()
        primaryField("API Key").performScrollTo().performTextReplacement("fixture-unsaved-main")
        primaryField("接口地址").performScrollTo().performTextReplacement("https://unsaved-relay.example/v1")
        primaryDescription("清除已保存 备用 API Key").performScrollTo().performClick()
        primary("ai-fetch-模型").performScrollTo().performClick()
        waitText("已获取 2 个模型。")
        capabilityField("API Key").performScrollTo().performTextReplacement("fixture-unsaved-embedding")
        capabilityField("缓存输入价").performScrollTo().performTextReplacement("0.123456")
        knowledgeNode("ai-knowledge-refresh").performClick()
        waitText("知识库已刷新。")
        knowledgeNode("ai-knowledge-reindex").performClick()
        waitText("已扫描 0 篇，向量 0 篇，关键词 0 篇，待处理 0 篇，失败 0 篇。")
        waitEnabled(knowledgeNode("ai-knowledge-refresh"))
        assertEquals(1, calls.count { it.first == "GET" && it.second == "/ai/admin/configuration" })
        compose.onNodeWithTag("ai-config-list").performScrollToNode(hasTestTag("ai-primary-card"))
        primary("ai-select-模型").performScrollTo().performClick()
        compose.onNode(hasText("Second model") and hasClickAction()).performClick()
        primaryText("保存配置").performScrollTo().performClick()
        waitText("AI 配置已保存。")
        val mainBody = lastBody("PATCH", "/ai/admin/configuration")
        assertEquals("fixture-unsaved-main", mainBody.getString("apiKey"))
        assertEquals("https://unsaved-relay.example/v1", mainBody.getString("baseUrl"))
        assertEquals("second-model", mainBody.getString("model"))
        assertTrue(mainBody.getBoolean("clearFallbackApiKey"))
        capabilityText("保存").performScrollTo().performClick()
        waitText("向量模型 配置已保存。")
        val capabilityBody = lastBody("PATCH", "/ai/admin/capabilities/embedding")
        assertEquals("fixture-unsaved-embedding", capabilityBody.getString("apiKey"))
        assertEquals(123456L, capabilityBody.getLong("cachedInputCostPerMillionMicros"))
        captureAiEvidence("knowledge-preserved-form-drafts")
    }

    @Test fun removedIndexDisappearsEvenWhenReadAfterActionFails() {
        knowledgeDocuments.put(knowledgeDocument(1))
        val removed = AtomicInteger()
        responseOverride = { request -> when {
            request.path == "/ai/admin/knowledge/documents/1/reindex" -> {
                removed.incrementAndGet(); knowledgeDocuments = JSONArray()
                json(JSONObject("""{"id":1,"removed":true,"status":"removed"}"""))
            }
            request.path == "/ai/admin/knowledge" && removed.get() > 0 -> json(JSONObject().put("message", "Read after removal failed"), 503)
            else -> null
        } }
        show()
        openDocuments(1)
        compose.onNodeWithTag("ai-reindex-document-1").performClick()
        waitDialogText("Read after removal failed", substring = true)
        compose.onNodeWithTag("ai-knowledge-document-1").assertDoesNotExist()
        compose.onNodeWithText("暂无索引文档。").assertIsDisplayed()
        dialogText("已移除“fixture-document-1”的失效索引。", substring = true).assertIsDisplayed()
        captureAiEvidence("knowledge-removed-read-failure")
        closeDialog()
        responseOverride = { null }
        knowledgeNode("ai-knowledge-refresh").performClick()
        waitText("知识库已刷新。")
        openDocuments(0)
        compose.onNodeWithText("暂无索引文档。").assertIsDisplayed()
    }

    @Test fun documentIndexKeepsOutcomeWhenRefreshFailsAndManualRefreshRecovers() {
        knowledgeDocuments.put(knowledgeDocument(1, "pending"))
        val reads = AtomicInteger()
        responseOverride = { request -> when (request.path) {
            "/ai/admin/knowledge/documents/1/reindex" -> {
                knowledgeDocuments = JSONArray().put(knowledgeDocument(1, "ready").put("vectorCount", 3))
                json(JSONObject("""{"id":1,"removed":false,"status":"ready"}"""))
            }
            "/ai/admin/knowledge" -> if (reads.incrementAndGet() == 2) json(JSONObject().put("message", "Latest state unavailable"), 503) else null
            else -> null
        } }
        show()
        openDocuments(1)
        compose.onNodeWithTag("ai-reindex-document-1").performClick()
        waitDialogText("Latest state unavailable", substring = true)
        dialogText("“fixture-document-1”：向量可用。", substring = true).assertIsDisplayed()
        waitEnabled(compose.onNodeWithTag("ai-reindex-document-1"))
        closeDialog()
        knowledgeNode("ai-knowledge-refresh").performClick()
        waitText("知识库已刷新。")
        openDocuments(1)
        compose.onNodeWithText("向量可用 · 3 片段 · 3 向量").assertIsDisplayed()
        captureAiEvidence("knowledge-post-index-read-recovered")
    }

    @Test fun failedDocumentIndexCanRetryAndBlocksDuplicateSubmission() {
        knowledgeDocuments.put(knowledgeDocument(1))
        val attempts = AtomicInteger()
        responseOverride = { request -> if (request.path == "/ai/admin/knowledge/documents/1/reindex") {
            if (attempts.incrementAndGet() == 1) json(JSONObject().put("message", "Index request unavailable"), 503).setBodyDelay(1500, TimeUnit.MILLISECONDS)
            else json(JSONObject("""{"id":1,"removed":false,"status":"keyword_ready"}"""))
        } else null }
        show()
        openDocuments(1)
        compose.onNodeWithTag("ai-reindex-document-1").performClick()
        compose.onNodeWithTag("ai-reindex-document-1").assertIsNotEnabled().performClick()
        waitDialogText("Index request unavailable")
        waitEnabled(compose.onNodeWithTag("ai-reindex-document-1"))
        assertEquals(1, attempts.get())
        captureAiEvidence("knowledge-index-request-error")
        compose.onNodeWithTag("ai-reindex-document-1").performClick()
        waitDialogText("“fixture-document-1”：关键词可用。")
        waitEnabled(compose.onNodeWithTag("ai-reindex-document-1"))
        assertEquals(2, attempts.get())
    }

    @Test fun bulkIndexDisablesRefreshAndDuplicateRequestsUntilReadCompletes() {
        responseOverride = { request -> if (request.path?.substringBefore('?') == "/ai/admin/knowledge/reindex") {
            json(JSONObject("""{"scanned":0,"indexed":0,"keywordReady":0,"pending":0,"failed":0}""")).setBodyDelay(1500, TimeUnit.MILLISECONDS)
        } else null }
        show()
        knowledgeNode("ai-knowledge-reindex").performClick()
        compose.onNodeWithTag("ai-knowledge-reindex").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("ai-knowledge-refresh").assertIsNotEnabled().performClick()
        waitText("已扫描 0 篇，向量 0 篇，关键词 0 篇，待处理 0 篇，失败 0 篇。")
        waitEnabled(compose.onNodeWithTag("ai-knowledge-refresh"))
        assertEquals(1, calls.count { it.first == "POST" && it.second == "/ai/admin/knowledge/reindex" })
        assertEquals(2, calls.count { it.first == "GET" && it.second == "/ai/admin/knowledge" })
    }

    @Test fun documentPendingAndFailedStatusesRemainDistinct() {
        knowledgeDocuments.put(knowledgeDocument(1, "pending"))
        val attempts = AtomicInteger()
        responseOverride = { request -> if (request.path == "/ai/admin/knowledge/documents/1/reindex") {
            val status = if (attempts.incrementAndGet() == 1) "pending_embedding" else "failed"
            knowledgeDocuments = JSONArray().put(knowledgeDocument(1, status).put("errorSummary", "Embedding endpoint unavailable"))
            json(JSONObject().put("id", 1).put("removed", false).put("status", status))
        } else null }
        show()
        openDocuments(1)
        compose.onNodeWithTag("ai-reindex-document-1").performClick()
        waitDialogText("“fixture-document-1”：待生成向量。")
        waitEnabled(compose.onNodeWithTag("ai-reindex-document-1"))
        compose.onNodeWithText("向量提示: Embedding endpoint unavailable").assertIsDisplayed()
        compose.onNodeWithTag("ai-reindex-document-1").performClick()
        waitDialogText("“fixture-document-1”：索引失败。")
        waitEnabled(compose.onNodeWithTag("ai-reindex-document-1"))
        compose.onNodeWithText("Embedding endpoint unavailable").assertIsDisplayed()
        captureAiEvidence("knowledge-document-index-failed")
    }

    @Test fun longKnowledgeErrorDoesNotHideDocumentsOrCloseButton() {
        knowledgeDocuments = JSONArray().apply { repeat(100) { put(knowledgeDocument(it + 1)) } }
        val longMessage = "Fixture indexing error. ".repeat(200)
        responseOverride = { request -> if (request.path == "/ai/admin/knowledge/documents/1/reindex") json(JSONObject().put("message", longMessage), 503) else null }
        show()
        openDocuments(100)
        compose.onNodeWithTag("ai-reindex-document-1").performClick()
        waitDialogText("Fixture indexing error.", substring = true)
        val feedback = compose.onNodeWithTag("ai-knowledge-feedback").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("ai-knowledge-documents").fetchSemanticsNode().boundsInRoot
        assertTrue(feedback.bottom <= list.top)
        compose.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag("ai-knowledge-documents").performScrollToNode(hasTestTag("ai-knowledge-document-100"))
        compose.onNodeWithText("fixture-document-100").assertIsDisplayed()
        captureAiEvidence("knowledge-long-error-dialog")
        closeDialog()
    }

    @Test fun emptyKnowledgeDialogAndEnglishActionsFitScreen() {
        NativeI18n.applyLocale("en-US")
        contentHost.setContent { HlovetTheme { NativeAdminAiScreen(session, {}) } }
        waitText("Model connection and global policy")
        compose.onNodeWithTag("ai-config-list").performScrollToNode(hasTestTag("ai-knowledge-card"))
        waitEnabled(compose.onNodeWithTag("ai-knowledge-refresh"))
        val card = compose.onNodeWithTag("ai-knowledge-card").fetchSemanticsNode().boundsInRoot
        val refresh = compose.onNodeWithTag("ai-knowledge-refresh").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val build = compose.onNodeWithTag("ai-knowledge-reindex").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val docs = compose.onNodeWithText("Documents (0)").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(refresh.left >= card.left && build.right <= card.right && docs.right <= build.left)
        captureAiEvidence("knowledge-english-actions")
        compose.onNodeWithText("Documents (0)").performClick()
        compose.onNodeWithText("No indexed documents.").assertIsDisplayed()
        captureAiEvidence("knowledge-empty-dialog")
    }

    private fun knowledgeNode(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("ai-config-list").performScrollToNode(hasTestTag("ai-knowledge-card"))
        val refresh = compose.onNodeWithTag("ai-knowledge-refresh")
        waitEnabled(refresh)
        return compose.onNodeWithTag(tag)
    }
    private fun openDocuments(count: Int) {
        knowledgeNode("ai-knowledge-refresh")
        compose.onNodeWithText("索引文档（$count）").performClick()
        compose.onNodeWithTag("ai-knowledge-documents").assertIsDisplayed()
    }
    private fun dialogText(text: String, substring: Boolean = false) = compose.onNode(hasText(text, substring = substring) and hasAnyAncestor(isDialog()))
    private fun waitDialogText(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = substring) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun closeDialog() { compose.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick() }
    private fun knowledgeDocument(id: Int, status: String = "keyword_ready") = JSONObject().put("id", id).put("title", "fixture-document-$id")
        .put("status", status).put("chunkCount", 3).put("vectorCount", 0).put("embeddingModel", JSONObject.NULL)
        .put("errorSummary", JSONObject.NULL).put("updatedAt", "2026-10-13T08:00:00Z").put("lastIndexedAt", JSONObject.NULL)

    private fun show(account: NativeSession = session) {
        contentHost.setContent { HlovetTheme { NativeAdminAiScreen(account, {}) } }
        if (account.isSuperAdmin) waitText("模型连接与全局策略")
    }

    private fun primary(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("ai-primary-card")))
    private fun primaryText(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag("ai-primary-card")))
    private fun primaryField(text: String) = compose.onNode(hasText(text) and hasSetTextAction() and hasAnyAncestor(hasTestTag("ai-primary-card")))
    private fun primaryDescription(text: String) = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text)) and hasAnyAncestor(hasTestTag("ai-primary-card")))
    private fun capability(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("ai-config-list").performScrollToNode(hasTestTag("ai-capability-embedding"))
        return compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("ai-capability-embedding")))
    }
    private fun capabilityText(text: String) = capabilityNode(hasText(text) and hasClickAction())
    private fun capabilityField(text: String) = capabilityNode(hasText(text) and hasSetTextAction())
    private fun capabilityDescription(text: String) = capabilityNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text)))
    private fun capabilityNode(matcher: SemanticsMatcher): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("ai-config-list").performScrollToNode(hasTestTag("ai-capability-embedding"))
        return compose.onNode(matcher and hasAnyAncestor(hasTestTag("ai-capability-embedding")))
    }
    private fun waitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitEnabled(node: androidx.compose.ui.test.SemanticsNodeInteraction) { compose.waitUntil(10_000) { !node.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) } }
    private fun lastBody(method: String, path: String) = calls.last { it.first == method && it.second == path }.third
    private fun json(value: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun models() = json(JSONObject("""{"models":[{"id":"first-model","label":"First model"},{"id":"second-model","label":"Second model"}]}"""))
    private fun defaultResponse(request: RecordedRequest, body: JSONObject): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        if (request.method == "GET") return when (path) {
            "/ai/admin/configuration" -> json(main)
            "/ai/admin/capabilities" -> json(JSONObject().put("items", JSONArray().put(capability)))
            "/ai/admin/invocations", "/ai/admin/usage" -> json(JSONObject())
            "/ai/admin/knowledge" -> json(knowledgeOverview)
            "/ai/admin/knowledge/documents" -> json(JSONObject().put("items", knowledgeDocuments))
            "/ai/admin/tools" -> json(JSONObject().put("items", JSONArray()))
            else -> json(JSONObject().put("message", "Unexpected fixture GET"), 404)
        }
        return when (path) {
            "/ai/admin/models" -> models()
            "/ai/admin/test-connection", "/ai/admin/capabilities/embedding/test" -> json(JSONObject().put("message", "Connection verified"))
            "/ai/admin/configuration" -> { applyPatch(main, body); json(main) }
            "/ai/admin/capabilities/embedding" -> { applyPatch(capability, body); json(capability) }
            "/ai/admin/knowledge/reindex" -> json(JSONObject("""{"scanned":0,"indexed":0,"keywordReady":0,"pending":0,"failed":0}""").put("overview", knowledgeOverview))
            else -> json(JSONObject().put("message", "Unexpected fixture mutation"), 404)
        }
    }

    private fun applyPatch(target: JSONObject, body: JSONObject) {
        body.keys().forEach { key -> if (key != "apiKey" && key != "fallbackApiKey") target.put(key, body.get(key)) }
        if (body.optBoolean("clearApiKey")) target.put("apiKeyConfigured", false)
        if (body.has("apiKey")) target.put("apiKeyConfigured", true)
        if (body.optBoolean("clearFallbackApiKey")) target.put("fallbackApiKeyConfigured", false)
    }
}

private fun configuration() = JSONObject("""{
    "enabled":false,"provider":"deepseek","baseUrl":"https://api.deepseek.com/v1","model":"","apiKeyConfigured":true,
    "fallbackEnabled":false,"fallbackProvider":"custom","fallbackBaseUrl":"https://relay.example/v1","fallbackModel":"","fallbackApiKeyConfigured":true,
    "inputCostPerMillionMicros":1000000,"cachedInputCostPerMillionMicros":100000,"outputCostPerMillionMicros":2000000,
    "fallbackInputCostPerMillionMicros":3000000,"fallbackCachedInputCostPerMillionMicros":200000,"fallbackOutputCostPerMillionMicros":4000000,
    "unitName":"request","maxInputBytes":1048576,"billingCurrency":"USD","globalConcurrency":1,"userConcurrency":1,
    "pricingPresets":[{"provider":"deepseek","model":"deepseek-chat","label":"Fixture prices","billingCurrency":"CNY","inputCostPerMillionMicros":2000000,"cachedInputCostPerMillionMicros":200000,"outputCostPerMillionMicros":4000000}]
}""")

private fun captureAiEvidence(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?: instrumentation.targetContext.getExternalFilesDir(null)!!.absolutePath
    val directory = File(output, "ai-config-evidence").apply { mkdirs() }
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    finally { bitmap.recycle() }
}
