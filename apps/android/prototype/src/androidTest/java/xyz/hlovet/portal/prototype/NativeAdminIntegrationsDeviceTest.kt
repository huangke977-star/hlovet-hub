package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** All configuration writes and external delivery scenarios use a device-local fixture. */
@RunWith(AndroidJUnit4::class)
class NativeAdminIntegrationsDeviceTest {
    // 设备内 MockWebServer 必须绕过模拟器的系统代理，避免回环请求被改写为外部错误。
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule(order = 2) val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching { capture("failed-${description.methodName}") }
            runCatching {
                val directory = evidenceDirectory()
                File(directory, "failed-${description.methodName}.txt").writeText(
                    compose.onAllNodes(isRoot()).printToString(maxDepth = 30) + "\nRequests:\n" +
                        calls.joinToString("\n") { "${it.method} ${path(it)}" })
            }
        }
    }
    private lateinit var server: MockWebServer
    private val contentHost = NativeComposeTestHost(compose)
    private var oldLocale = "zh-CN"
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var override: (RecordedRequest) -> MockResponse? = { null }
    @Volatile private var saved = config()
    @Volatile private var hooks = JSONArray().put(hook(1))
    @Volatile private var deliveries = JSONArray().put(delivery(1))
    private val session = NativeSession(accessToken = "fixture-token", refreshToken = "fixture-refresh", userId = 1,
        username = "fixture-admin", nickname = "Fixture admin", isAdministrator = true, isSuperAdmin = true,
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46))
    private val googlePath = "/integrations/admin/google-oauth"
    private val hooksPath = "/integrations/admin/webhooks"
    private val deliveriesPath = "/integrations/admin/webhook-deliveries"

    @Before fun start() {
        assumeTrue("Requires the exact device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.add(request)
                return override(request) ?: respond(request)
            }
        }
        server.start(39871)
    }

    @After fun stop() {
        if (!::server.isInitialized) return
        try {
            contentHost.dispose()
        } finally {
            try { server.shutdown() }
            finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) } }
        }
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        contentHost.setContent(content)
    }

    @Test fun signedOutAndOrdinaryAdminNeverReadIntegrationSettings() {
        val account = mutableStateOf<NativeSession?>(null)
        setTestContent { HlovetTheme { NativeAdminIntegrationsScreen(account.value, {}) } }
        waitText("登录后查看外部集成。")
        assertTrue(calls.isEmpty())
        compose.runOnIdle { account.value = session.copy(isSuperAdmin = false) }
        waitText("外部集成仅超级管理员可管理。")
        assertTrue(calls.isEmpty())
    }

    @Test fun googleFailureDoesNotHideOtherSectionsAndRetryIsIndependent() {
        val reads = AtomicInteger()
        override = { r -> if (path(r) == googlePath && reads.incrementAndGet() == 1) error("Fixture Google unavailable") else null }
        setTestContent { HlovetTheme { NativeAdminIntegrationsScreen(session, {}) } }
        text("Fixture Google unavailable").assertIsDisplayed()
        text("Fixture endpoint 1").assertIsDisplayed()
        text("#1 · integration.test").assertIsDisplayed()
        button("重试 Google 配置").performClick()
        field("integration-client-id").assertTextContains("fixture-client")
        assertEquals(2, reads.get())
        assertEquals(1, requests("GET", hooksPath).size)
        assertEquals(1, requests("GET", deliveriesPath).size)
    }

    @Test fun webhookFailureDoesNotHideGoogleOrDeliveriesAndRetryPreservesDraft() {
        val reads = AtomicInteger()
        override = { r -> if (path(r) == hooksPath && reads.incrementAndGet() == 1) error("Fixture endpoints unavailable") else null }
        show()
        field("integration-client-id").performTextReplacement("fixture-draft-client")
        text("Fixture endpoints unavailable").assertIsDisplayed()
        text("#1 · integration.test").assertIsDisplayed()
        button("重试 Webhook").performClick()
        text("Fixture endpoint 1").assertIsDisplayed()
        field("integration-client-id").assertTextContains("fixture-draft-client")
        assertEquals(1, requests("GET", googlePath).size)
        assertEquals(1, requests("GET", deliveriesPath).size)
    }

    @Test fun deliveryFailureHasIndependentRetryAndKeepsGoogleDraft() {
        val reads = AtomicInteger()
        override = { r -> if (path(r) == deliveriesPath && reads.incrementAndGet() == 1) error("Fixture deliveries unavailable") else null }
        show()
        field("integration-client-secret").performTextReplacement("fixture-draft-secret")
        text("Fixture deliveries unavailable").assertIsDisplayed()
        button("重试投递记录").performClick()
        text("#1 · integration.test").assertIsDisplayed()
        assertField("integration-client-secret", "fixture-draft-secret")
        assertEquals(1, requests("GET", googlePath).size)
        assertEquals(1, requests("GET", hooksPath).size)
    }

    @Test fun dirtyRefreshPreservesAllGoogleFieldsAndNewSecret() {
        show()
        field("integration-client-id").performTextReplacement("draft-client")
        field("integration-client-secret").performTextReplacement("fixture-draft-secret")
        field("integration-redirect").performTextReplacement("https://draft.invalid/callback")
        field("integration-google-enabled").performClick()
        saved = config().put("clientId", "server-client").put("redirectUri", "https://server.invalid/callback")
        refresh()
        compose.waitUntil(10_000) { requests("GET", googlePath).size == 2 }
        field("integration-client-id").assertTextContains("draft-client")
        assertField("integration-client-secret", "fixture-draft-secret")
        field("integration-redirect").assertTextContains("https://draft.invalid/callback")
        field("integration-google-enabled").assertIsOff()
        assertTrue(calls.all { it.method == "GET" })
    }

    @Test fun pristineRefreshAdoptsUpdatedServerFields() {
        show()
        saved = config().put("clientId", "server-client").put("enabled", false)
        refresh()
        compose.waitUntil(10_000) { compose.onNodeWithTag("integration-client-id").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "server-client" }
        field("integration-client-id").assertTextContains("server-client")
        field("integration-google-enabled").assertIsOff()
    }

    @Test fun unavailableEncryptionDisablesSecretAndSave() {
        saved = config().put("encryptionConfigured", false)
        show()
        field("integration-client-secret").assertIsNotEnabled()
        button("保存配置").assertIsNotEnabled()
        text("服务器尚未配置密钥加密").assertIsDisplayed()
        assertTrue(requests("PATCH", googlePath).isEmpty())
    }

    @Test fun repeatedUnavailableEncryptionPageExitDisposesContentSafely() {
        saved = config().put("encryptionConfigured", false)
        val opened = mutableStateOf(false)
        setTestContent {
            HlovetTheme {
                if (opened.value) NativeAdminIntegrationsScreen(session, { opened.value = false })
                else Text("Fixture outside integrations")
            }
        }
        repeat(8) { cycle ->
            compose.runOnIdle { opened.value = true }
            field("integration-client-secret").assertIsNotEnabled()
            button("保存配置").assertIsNotEnabled()
            text("服务器尚未配置密钥加密").assertIsDisplayed()
            compose.waitUntil(10_000) { requests("GET", deliveriesPath).size == cycle + 1 }
            compose.onNodeWithTag("integrations-list").performScrollToNode(hasContentDescription("返回"))
            compose.onNodeWithContentDescription("返回").performClick()
            waitText("Fixture outside integrations")
            compose.waitForIdle()
        }
        assertEquals(8, requests("GET", googlePath).size)
        assertEquals(8, requests("GET", hooksPath).size)
        assertEquals(8, requests("GET", deliveriesPath).size)
        assertTrue(calls.all { it.method == "GET" })
    }

    @Test fun invalidGoogleRedirectAndOverlongFieldsAreNotTruncatedOrSubmitted() {
        show()
        field("integration-redirect").performTextReplacement("http://example.invalid/callback")
        button("保存配置").performClick()
        text("Google 回调地址必须是有效的 HTTPS 地址（本地开发可使用 localhost）。").assertIsDisplayed()
        field("integration-redirect").performTextReplacement("https://example.invalid/callback")
        field("integration-client-id").performTextReplacement("x".repeat(256))
        button("保存配置").performClick()
        text("Google 配置超过允许长度，请缩短后重试。").assertIsDisplayed()
        assertField("integration-client-id", "x".repeat(256))
        assertTrue(requests("PATCH", googlePath).isEmpty())
    }

    @Test fun googleSaveFailurePreservesSecretAndBlocksDuplicateSubmission() {
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        override = { r -> if (r.method == "PATCH" && path(r) == googlePath && attempts.incrementAndGet() == 1) {
            release.await(30, TimeUnit.SECONDS)
            json(JSONObject().put("message", JSONArray(listOf("Fixture client invalid", "Fixture redirect invalid")))).setResponseCode(400)
        } else null }
        show()
        field("integration-client-secret").performTextReplacement("fixture-new-secret")
        try {
            button("保存配置").performClick()
            compose.waitUntil(10_000) { attempts.get() == 1 }
            button("保存中…").assertIsNotEnabled().performTouchInput { click() }
            compose.waitForIdle()
            assertEquals(1, attempts.get())
        } finally { release.countDown() }
        text("Fixture client invalid\nFixture redirect invalid").assertIsDisplayed()
        assertField("integration-client-secret", "fixture-new-secret")
        text("Fixture client invalid\nFixture redirect invalid").assertIsDisplayed()
        capture("google-save-error")
        button("保存配置").performClick()
        text("Google 登录配置已保存。").assertIsDisplayed()
        assertEquals(2, attempts.get())
        assertField("integration-client-secret", "")
    }

    @Test fun googleSaveUsesConfirmedFieldsClearsSecretAndOmitsBlankSecret() {
        override = { r -> if (r.method == "PATCH" && path(r) == googlePath) json(config().put("clientId", "confirmed-client")) else null }
        show()
        field("integration-client-secret").performTextReplacement("fixture-new-secret")
        button("保存配置").performClick()
        text("Google 登录配置已保存。").assertIsDisplayed()
        assertEquals("fixture-new-secret", body(requests("PATCH", googlePath).single()).getString("clientSecret"))
        field("integration-client-id").assertTextContains("confirmed-client")
        assertField("integration-client-secret", "")
        button("保存配置").performClick()
        compose.waitUntil(10_000) { requests("PATCH", googlePath).size == 2 }
        assertFalse(body(requests("PATCH", googlePath).last()).has("clientSecret"))
        assertTrue(requests("POST", hooksPath).isEmpty())
    }

    @Test fun cancelCreateDiscardsSecretAndDoesNotSendRequest() {
        show(); openCreate()
        field("integration-hook-name").performTextReplacement("Fixture unsaved")
        field("integration-hook-secret").performTextReplacement("fixture-webhook-secret")
        compose.onNodeWithContentDescription("关闭").performClick()
        openCreate()
        assertField("integration-hook-name", "")
        assertField("integration-hook-secret", "")
        assertTrue(requests("POST", hooksPath).isEmpty())
    }

    @Test fun webhookValidationRejectsEmptyNameHttpShortSecretAndNoEvents() {
        show(); openCreate()
        button("保存 Webhook").performClick()
        waitText("Webhook 名称需要 1 到 120 个字符。")
        field("integration-hook-name").performTextReplacement("Fixture new endpoint")
        field("integration-hook-url").performTextReplacement("http://example.invalid/hook")
        button("保存 Webhook").performClick()
        waitText("Webhook 地址必须是有效的 HTTPS 地址，且不超过 512 个字符。")
        field("integration-hook-url").performTextReplacement("https://example.invalid/hook")
        field("integration-hook-secret").performTextReplacement("short")
        button("保存 Webhook").performClick()
        waitText("签名 Secret 需要 16 到 255 个字符。")
        field("integration-hook-secret").performTextReplacement("fixture-webhook-secret")
        field("integration-event-integration.test").performClick()
        button("保存 Webhook").performClick()
        waitText("至少选择一个支持的 Webhook 事件。")
        assertTrue(requests("POST", hooksPath).isEmpty())
        capture("webhook-validation")
    }

    @Test fun webhookCreateFailureStaysInDialogAndRetainsInputForRetry() {
        val attempts = AtomicInteger()
        override = { r -> if (r.method == "POST" && path(r) == hooksPath && attempts.incrementAndGet() == 1) error("Fixture create unavailable") else null }
        show(); openCreate(); fillCreate()
        button("保存 Webhook").performClick()
        waitText("Fixture create unavailable")
        assertField("integration-hook-secret", "fixture-webhook-secret")
        compose.onNodeWithText("Fixture create unavailable").assertIsDisplayed()
        capture("webhook-create-error")
        button("保存 Webhook").performClick()
        text("Webhook 已添加。").assertIsDisplayed()
        text("Fixture new endpoint").assertIsDisplayed()
        assertEquals(2, attempts.get())
        assertEquals(1, requests("GET", googlePath).size)
        assertEquals(listOf("integration.test"), (body(requests("POST", hooksPath).last()).getJSONArray("events")).let { (0 until it.length()).map(it::getString) })
    }

    @Test fun acknowledgedCreateSurvivesFailedRefreshWithoutOverwritingGoogleDraft() {
        val reads = AtomicInteger()
        override = { r -> if (r.method == "GET" && path(r) == hooksPath && reads.incrementAndGet() > 1) error("Fixture refresh unavailable") else null }
        show()
        field("integration-client-id").performTextReplacement("fixture-unsaved-client")
        openCreate(); fillCreate(); button("保存 Webhook").performClick()
        text("Fixture refresh unavailable").assertIsDisplayed()
        text("Fixture new endpoint").assertIsDisplayed()
        field("integration-client-id").assertTextContains("fixture-unsaved-client")
        assertEquals(1, requests("GET", googlePath).size)
    }

    @Test fun toggleUsesServerStatePreservesMetadataAndFailedReadDoesNotUndoIt() {
        val reads = AtomicInteger()
        override = { r -> if (r.method == "GET" && path(r) == hooksPath && reads.incrementAndGet() > 1) error("Fixture read unavailable") else null }
        show()
        field("integration-hook-1-enabled").performClick()
        text("Webhook 已停用。").assertIsDisplayed()
        text("Fixture read unavailable").assertIsDisplayed()
        field("integration-hook-1-enabled").assertIsOff()
        text("7 条投递").assertIsDisplayed()
        button("发送测试事件").assertIsNotEnabled()
        assertFalse(body(requests("PATCH", "$hooksPath/1").single()).getBoolean("enabled"))
        assertEquals(1, requests("GET", googlePath).size)
    }

    @Test fun toggleFailureDoesNotOptimisticallyChangeExistingState() {
        override = { r -> if (r.method == "PATCH" && path(r) == "$hooksPath/1") error("Fixture toggle unavailable") else null }
        show(); field("integration-hook-1-enabled").performClick()
        text("Fixture toggle unavailable").assertIsDisplayed()
        field("integration-hook-1-enabled").assertIsOn()
    }

    @Test fun deleteNeedsConfirmationFailureStaysOpenAndSuccessRemovesDeliveries() {
        val attempts = AtomicInteger()
        override = { r -> if (r.method == "DELETE" && attempts.incrementAndGet() == 1) error("Fixture delete unavailable") else null }
        show(); action("删除 Webhook Fixture endpoint 1").performClick()
        waitText("删除 Webhook")
        assertTrue(requests("DELETE", "$hooksPath/1").isEmpty())
        button("取消").performClick()
        assertTrue(requests("DELETE", "$hooksPath/1").isEmpty())
        action("删除 Webhook Fixture endpoint 1").performClick()
        button("确认删除").performClick()
        waitText("Fixture delete unavailable")
        compose.onNodeWithText("删除 Webhook").assertIsDisplayed()
        button("确认删除").performClick()
        text("Webhook 及其投递记录已删除。").assertIsDisplayed()
        text("尚未配置 Webhook。").assertIsDisplayed()
        text("暂无投递记录。").assertIsDisplayed()
        assertEquals(2, attempts.get())
        assertEquals(1, requests("GET", googlePath).size)
    }

    @Test fun testEventNeedsConfirmationAcceptsEmpty201AndPreservesGoogleDraft() {
        show(); field("integration-client-secret").performTextReplacement("fixture-draft-secret")
        button("发送测试事件").performClick()
        waitText("将向已启用并订阅 integration.test 的端点发送真实事件，是否继续？")
        assertTrue(requests("POST", "$hooksPath/test").isEmpty())
        button("取消").performClick()
        button("发送测试事件").performClick(); button("确认发送").performClick()
        text("测试事件已提交，请查看投递记录。").assertIsDisplayed()
        assertEquals(1, requests("POST", "$hooksPath/test").size)
        assertField("integration-client-secret", "fixture-draft-secret")
        assertEquals(1, requests("GET", googlePath).size)
    }

    @Test fun testFailureAndReplayFailureStayInTheirConfirmationDialog() {
        override = { r -> when (path(r)) { "$hooksPath/test" -> error("Fixture test unavailable"); "$deliveriesPath/1/replay" -> error("Fixture replay unavailable"); else -> null } }
        show(); button("发送测试事件").performClick(); button("确认发送").performClick()
        waitText("Fixture test unavailable")
        compose.onNodeWithText("确认发送").assertIsDisplayed()
        button("取消").performClick()
        action("重放投递 1").performClick()
        waitText("重放投递 1")
        assertTrue(requests("POST", "$deliveriesPath/1/replay").isEmpty())
        button("确认发送").performClick()
        waitText("Fixture replay unavailable")
        capture("delivery-replay-error")
        assertEquals(1, requests("POST", "$deliveriesPath/1/replay").size)
    }

    @Test fun replayAcknowledgementDoesNotClaimExternalDeliverySucceeded() {
        show(); action("重放投递 1").performClick(); button("确认发送").performClick()
        text("重放请求已完成，请查看投递状态。").assertIsDisplayed()
        assertEquals(1, requests("POST", "$deliveriesPath/1/replay").size)
        text("失败 · 尝试 2 次 · ${fixtureTime()}").assertExists()
    }

    @Test fun allOneHundredReturnedDeliveriesAreReachable() {
        deliveries = JSONArray().apply { (1..100).forEach { put(delivery(it)) } }
        show()
        text("#100 · integration.test").assertIsDisplayed()
        action("重放投递 100").assertIsDisplayed()
        assertEquals(1, requests("GET", deliveriesPath).size)
    }

    @Test fun longEndpointAndDeliveryDetailsScrollWithCloseVisible() {
        val longError = "Fixture detail ".repeat(150) + "Fixture error tail"
        hooks = JSONArray().put(hook(1).put("lastError", longError))
        deliveries = JSONArray().put(delivery(1).put("lastError", longError))
        show(); text("#1 · integration.test").assertIsDisplayed()
        text("Fixture endpoint 1").performClick(); waitText("Webhook 详情")
        compose.onNode(hasText(longError) and hasAnyAncestor(hasTestTag("integration-detail"))).performScrollTo().assertExists()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        text("#1 · integration.test").performClick(); waitText("投递详情")
        compose.onNode(hasText(longError) and hasAnyAncestor(hasTestTag("integration-detail"))).performScrollTo().assertExists()
        compose.onNodeWithText("关闭").assertIsDisplayed()
        capture("delivery-long-details")
    }

    @Test fun pendingDeliveryPollsAndUpdatesOpenDetails() {
        val reads = AtomicInteger()
        val pollingStarted = AtomicBoolean(false)
        deliveries = JSONArray().put(delivery(1).put("status", "pending"))
        override = { r -> if (path(r) == deliveriesPath && reads.incrementAndGet() > 1 && pollingStarted.get()) {
            json(JSONArray().put(delivery(1).put("status", "delivered").put("deliveredAt", "2026-10-13T02:00:00Z")))
        } else null }
        show(); text("#1 · integration.test").performClick(); waitText("投递详情")
        compose.onNodeWithText("待投递").assertExists()
        val initialReads = reads.get()
        pollingStarted.set(true)
        compose.mainClock.advanceTimeBy(5_100)
        compose.waitUntil(10_000) { reads.get() > initialReads }
        compose.waitUntil(12_000) { compose.onAllNodesWithText("已投递").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已投递").assertExists()
        compose.onNodeWithText("关闭").performClick()
        capture("delivery-poll-complete")
        assertEquals(1, requests("GET", googlePath).size)
    }

    @Test fun deniedDeliveryPollStopsFurtherAutomaticRequests() {
        val reads = AtomicInteger()
        val pollingStarted = AtomicBoolean(false)
        deliveries = JSONArray().put(delivery(1).put("status", "pending"))
        override = { r -> if (path(r) == deliveriesPath && reads.incrementAndGet() > 1 && pollingStarted.get()) error("Fixture delivery access denied").setResponseCode(403) else null }
        show(); text("#1 · integration.test").assertIsDisplayed()
        val initialReads = reads.get()
        pollingStarted.set(true)
        compose.mainClock.advanceTimeBy(5_100)
        compose.waitUntil(12_000) { reads.get() > initialReads }
        text("Fixture delivery access denied").assertIsDisplayed()
        val deniedReads = reads.get()
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        assertEquals(initialReads + 1, deniedReads)
        assertEquals(deniedReads, reads.get())
    }

    @Test fun losingPermissionClosesDialogDiscardsSecretsAndStopsReads() {
        val account = mutableStateOf(session)
        setTestContent { HlovetTheme { NativeAdminIntegrationsScreen(account.value, {}) } }
        waitText("Google 登录")
        field("integration-client-secret").performTextReplacement("fixture-private-draft")
        openCreate(); field("integration-hook-secret").performTextReplacement("fixture-webhook-draft")
        compose.runOnIdle { account.value = session.copy(isSuperAdmin = false) }
        waitText("外部集成仅超级管理员可管理。")
        assertTrue(compose.onAllNodesWithTag("integration-hook-secret").fetchSemanticsNodes().isEmpty())
        val count = calls.size
        Thread.sleep(300)
        assertEquals(count, calls.size)
        compose.runOnIdle { account.value = session }
        field("integration-client-secret").assertIsEnabled()
        assertField("integration-client-secret", "")
        openCreate(); assertField("integration-hook-secret", "")
    }

    @Test fun accountSwitchRejectsLateGoogleSaveAndClearsPreviousDraft() {
        val release = CountDownLatch(1)
        override = { r -> if (r.method == "PATCH") {
            release.await(30, TimeUnit.SECONDS)
            json(config().put("clientId", "previous-account-result"))
        } else if (r.getHeader("Authorization") == "Bearer fixture-second-token" && path(r) == googlePath) json(config().put("clientId", "second-account-client")) else null }
        val account = mutableStateOf(session)
        setTestContent { HlovetTheme { NativeAdminIntegrationsScreen(account.value, {}) } }
        waitText("Google 登录")
        field("integration-client-secret").performTextReplacement("previous-account-secret")
        try {
            button("保存配置").performClick()
            compose.waitUntil(10_000) { requests("PATCH", googlePath).size == 1 }
            compose.runOnIdle { account.value = session.copy(accessToken = "fixture-second-token", userId = 2) }
            field("integration-client-id").assertTextContains("second-account-client")
            assertField("integration-client-secret", "")
        } finally { release.countDown() }
        Thread.sleep(300)
        field("integration-client-id").assertTextContains("second-account-client")
        assertTrue(compose.onAllNodesWithText("Google 登录配置已保存。").fetchSemanticsNodes().isEmpty())
    }

    @Test fun englishFallbackErrorsAndDeliveryStatusesAreLocalized() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("en-US") }
        override = { r -> if (path(r) == googlePath) MockResponse().setResponseCode(503).setBody("not-json") else null }
        setTestContent { HlovetTheme { NativeAdminIntegrationsScreen(session, {}) } }
        text("Integration request failed (HTTP 503).").assertIsDisplayed()
        text("Fixture endpoint 1").assertIsDisplayed()
        text("Failed · 2 attempts · ${fixtureTime()}").assertIsDisplayed()
        text("#1 · integration.test").performClick()
        waitText("Delivery details")
        compose.onNodeWithText("Next attempt").assertExists()
        compose.onNodeWithText("Delivered").assertExists()
        compose.onNodeWithText("Close").assertIsDisplayed()
        capture("integrations-english")
    }

    @Test fun emptyReadResponseIsNotPresentedAsDefaultGoogleConfiguration() {
        override = { r -> if (path(r) == googlePath) MockResponse().setResponseCode(200) else null }
        setTestContent { HlovetTheme { NativeAdminIntegrationsScreen(session, {}) } }
        text("外部集成返回为空，请重试。").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("integration-client-id").fetchSemanticsNodes().isEmpty())
        text("Fixture endpoint 1").assertIsDisplayed()
    }

    @Test fun createFormKeepsHeaderAndFooterReachableWithKeyboardOpen() {
        show(); openCreate()
        field("integration-hook-url").performClick().performTextInput("https://example.invalid/hook")
        Thread.sleep(900)
        compose.onNodeWithContentDescription("关闭").assertIsDisplayed()
        compose.onNodeWithText("保存 Webhook").assertIsDisplayed()
        field("integration-event-resource.redeemed").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").assertIsDisplayed()
        compose.onNodeWithText("保存 Webhook").assertIsDisplayed()
        capture("webhook-keyboard")
        shell("input keyevent 4")
    }

    private fun show() {
        setTestContent { HlovetTheme { NativeAdminIntegrationsScreen(session, {}) } }
        field("integration-client-id").assertTextContains("fixture-client")
        compose.waitUntil(10_000) { calls.count { it.method == "GET" } >= 3 }
    }

    private fun openCreate() { action("新增 Webhook").performClick(); waitText("名称") }
    private fun fillCreate() {
        field("integration-hook-name").performTextReplacement("Fixture new endpoint")
        field("integration-hook-url").performTextReplacement("https://example.invalid/new-hook")
        field("integration-hook-secret").performTextReplacement("fixture-webhook-secret")
    }

    private fun field(tag: String): SemanticsNodeInteraction {
        if (compose.onAllNodesWithTag("integration-webhook-form").fetchSemanticsNodes().isEmpty()) {
            val section = if (tag.startsWith("integration-hook-") && tag.endsWith("-enabled")) "webhooks" else "google"
            compose.onNodeWithTag("integrations-list").performScrollToKey(section)
        }
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: ComposeTimeoutException) {
            recordLookupTimeout(tag)
            throw error
        }
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun text(value: String): SemanticsNodeInteraction {
        try {
            compose.waitUntil(10_000) {
                try {
                    if (compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()) return@waitUntil true
                    compose.onNodeWithTag("integrations-list").performScrollToNode(hasText(value))
                    compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
                } catch (_: AssertionError) { false }
            }
        } catch (error: ComposeTimeoutException) {
            recordLookupTimeout("text")
            throw error
        }
        return compose.onAllNodesWithText(value)[0].performScrollTo()
    }

    private fun button(value: String): SemanticsNodeInteraction {
        val matcher = hasText(value) and hasClickAction()
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("integrations-list").performScrollToNode(matcher)
        }
        val node = compose.onAllNodes(matcher)[0]
        try { node.performScrollTo() } catch (_: AssertionError) { node.assertIsDisplayed() }
        return node
    }

    private fun action(description: String): SemanticsNodeInteraction {
        compose.waitUntil(10_000) {
            try { compose.onNodeWithTag("integrations-list").performScrollToNode(hasContentDescription(description)); true }
            catch (_: AssertionError) { false }
        }
        return compose.onNodeWithContentDescription(description).performScrollTo()
    }

    private fun refresh() { action("刷新").assertIsEnabled().performClick() }
    private fun assertField(tag: String, value: String) {
        val node = field(tag)
        if (tag.endsWith("secret")) {
            val reveal = hasContentDescription(nativePhrase("显示密钥", "Show secret")) and hasAnyAncestor(hasTestTag(tag))
            if (compose.onAllNodes(reveal, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(reveal, useUnmergedTree = true).performClick()
            }
        }
        assertEquals(value, node.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun waitText(value: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
    private fun path(request: RecordedRequest) = request.requestUrl!!.encodedPath
    private fun fixtureTime() = Instant.parse("2026-10-13T01:00:00Z").atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    private fun requests(method: String, target: String) = calls.filter { it.method == method && path(it) == target }
    private fun body(request: RecordedRequest) = JSONObject(request.body.clone().readUtf8())
    private fun config() = JSONObject("""{"managed":true,"enabled":true,"clientId":"fixture-client","clientSecretConfigured":true,"redirectUri":"https://example.invalid/callback","source":"database","encryptionConfigured":true,"updatedAt":"2026-10-13T01:00:00Z"}""")
    private fun hook(id: Int) = JSONObject().put("id", id).put("name", "Fixture endpoint $id").put("url", "https://example.invalid/hook").put("events", JSONArray(listOf("integration.test"))).put("enabled", true).put("deliveryCount", 7).put("lastError", JSONObject.NULL).put("createdAt", "2026-10-13T01:00:00Z").put("updatedAt", "2026-10-13T01:00:00Z")
    private fun delivery(id: Int) = JSONObject().put("id", id).put("endpointId", 1).put("eventType", "integration.test").put("eventId", "fixture-event-$id").put("status", "failed").put("attempts", 2).put("lastError", JSONObject.NULL).put("createdAt", "2026-10-13T01:00:00Z").put("nextAttemptAt", JSONObject.NULL).put("deliveredAt", JSONObject.NULL)

    private fun respond(request: RecordedRequest): MockResponse = when (path(request)) {
        googlePath -> if (request.method == "PATCH") {
            val update = body(request)
            saved = JSONObject(saved.toString()).apply { update.keys().forEach { key -> if (key != "clientSecret") put(key, update.get(key)) } }
            json(saved)
        } else json(saved)
        hooksPath -> if (request.method == "POST") {
            val created = hook(2).apply { val update = body(request); listOf("name", "url", "events").forEach { put(it, update.get(it)) } }
            hooks = JSONArray().put(created).put(hook(1))
            json(JSONObject(created.toString()).apply { remove("deliveryCount") })
        } else json(hooks)
        "$hooksPath/1" -> if (request.method == "DELETE") {
            hooks = JSONArray(); deliveries = JSONArray()
            MockResponse().setResponseCode(204)
        } else {
            val changed = hook(1).put("enabled", body(request).getBoolean("enabled"))
            hooks = JSONArray().put(changed)
            json(JSONObject(changed.toString()).apply { remove("deliveryCount") })
        }
        "$hooksPath/test" -> MockResponse().setResponseCode(201)
        deliveriesPath -> json(deliveries)
        "$deliveriesPath/1/replay" -> json(JSONObject().put("success", true))
        else -> error("Unexpected fixture request")
    }

    private fun json(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun error(message: String) = json(JSONObject().put("message", message)).setResponseCode(503)
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
    private fun evidenceDirectory(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: instrumentation.targetContext.getExternalFilesDir(null)!!.absolutePath
        return File(output, "integrations-evidence").apply { mkdirs() }
    }
    private fun recordLookupTimeout(name: String) {
        capture("lookup-timeout-$name")
        File(evidenceDirectory(), "lookup-timeout-$name.txt").writeText(
            compose.onAllNodes(isRoot()).printToString(maxDepth = 30) + "\nRequests:\n" +
                calls.joinToString("\n") { "${it.method} ${path(it)}" })
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = evidenceDirectory()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
