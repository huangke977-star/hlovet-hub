package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Security mutations must never reach a real server during these scenarios. */
@RunWith(AndroidJUnit4::class)
class NativeAdminSecurityDeviceTest {
    // 设备内 MockWebServer 必须绕过模拟器的系统代理，避免回环请求被改写为外部错误。
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val contentHost = NativeComposeTestHost(compose)
    private var oldLocale = "zh-CN"
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var override: (RecordedRequest) -> MockResponse? = { null }
    @Volatile private var saved = config()
    private val session = NativeSession(accessToken = "fixture-token", refreshToken = "fixture-refresh", userId = 1,
        username = "fixture-admin", nickname = "Fixture admin", isAdministrator = true, isSuperAdmin = true,
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46))

    @Before fun start() {
        assumeTrue("Requires the exact device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { calls.add(request); return override(request) ?: respond(request) }
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

    @Test fun nonAdminNeverReadsSecurityConfigurationOrRecords() {
        setTestContent { HlovetTheme { NativeAdminSecurityScreen(session.copy(isAdministrator = false, isSuperAdmin = false), {}) } }
        waitText("当前账号没有安全管理权限。")
        assertTrue(calls.isEmpty())
    }

    @Test fun ordinaryAdministratorCanReadButCannotEditOrTest() {
        show(session.copy(isSuperAdmin = false))
        field("security-host").assertIsNotEnabled()
        field("security-password").assertIsNotEnabled()
        text("fixture-recipient@example.invalid").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("保存配置").fetchSemanticsNodes().isEmpty())
        assertTrue(calls.all { it.method == "GET" })
    }

    @Test fun configurationFailureDoesNotHideRecordsAndRetryIsIndependent() {
        val reads = AtomicInteger()
        override = { r -> if (path(r) == "/security-admin/config" && reads.incrementAndGet() == 1) error("Fixture configuration unavailable") else null }
        setTestContent { HlovetTheme { NativeAdminSecurityScreen(session, {}) } }
        text("Fixture configuration unavailable").assertIsDisplayed()
        text("fixture-recipient@example.invalid").assertIsDisplayed()
        button("重试配置").performClick()
        compose.waitUntil(10_000) { reads.get() == 2 }
        field("security-host").assertTextContains("fixture.invalid")
        assertEquals(1, requests("GET", "/security-admin/overview").size)
    }

    @Test fun recordFailureKeepsConfigurationAndRetriesOnlyRecords() {
        val reads = AtomicInteger()
        override = { r -> if (path(r) == "/security-admin/overview" && reads.incrementAndGet() == 1) error("Fixture records unavailable") else null }
        show()
        field("security-host").performTextReplacement("draft.invalid")
        text("Fixture records unavailable").assertIsDisplayed()
        button("重试记录").performClick()
        text("fixture-recipient@example.invalid").assertIsDisplayed()
        field("security-host").assertTextContains("draft.invalid")
        assertEquals(1, requests("GET", "/security-admin/config").size)
    }

    @Test fun repeatedRecordRetryAndPageExitDisposeTheCompositionSafely() {
        val opened = mutableStateOf(false)
        val reads = AtomicInteger()
        override = { r -> if (path(r) == "/security-admin/overview" && reads.incrementAndGet() % 2 == 1) error("Fixture records unavailable") else null }
        setTestContent {
            HlovetTheme {
                if (opened.value) NativeAdminSecurityScreen(session, { opened.value = false })
                else Text("Fixture outside security")
            }
        }
        repeat(8) {
            compose.runOnIdle { opened.value = true }
            field("security-host").assertTextContains("fixture.invalid")
            field("security-host").performTextReplacement("draft.invalid")
            text("Fixture records unavailable").assertIsDisplayed()
            button("重试记录").performClick()
            text("fixture-recipient@example.invalid").assertIsDisplayed()
            field("security-host").assertTextContains("draft.invalid")
            compose.onNodeWithTag("security-list").performScrollToNode(hasContentDescription("返回"))
            compose.onNodeWithContentDescription("返回").performClick()
            waitText("Fixture outside security")
            compose.waitForIdle()
        }
        assertEquals(8, requests("GET", "/security-admin/config").size)
        assertEquals(16, reads.get())
        assertTrue(calls.all { it.method == "GET" })
    }

    @Test fun refreshPreservesAllDraftFieldsRawNumbersAndBothSecrets() {
        show()
        field("security-host").performTextReplacement("draft.invalid")
        field("security-port").performTextReplacement("-587")
        field("security-password").performTextReplacement("fixture-smtp-draft")
        field("security-site-key").performTextReplacement("fixture-site-draft")
        field("security-secret").performTextReplacement("fixture-turnstile-draft")
        field("security-threshold").performTextReplacement("9x")
        saved = config().put("smtpHost", "server-changed.invalid").put("updatedAt", "2026-10-13T02:00:00Z")
        refresh()
        compose.waitUntil(10_000) { requests("GET", "/security-admin/config").size == 2 }
        field("security-host").assertTextContains("draft.invalid")
        field("security-port").assertTextContains("-587")
        field("security-password").assertTextContains("fixture-smtp-draft")
        field("security-site-key").assertTextContains("fixture-site-draft")
        field("security-secret").assertTextContains("fixture-turnstile-draft")
        field("security-threshold").assertTextContains("9x")
        assertTrue(calls.all { it.method == "GET" })
    }

    @Test fun pristineRefreshAdoptsChangedServerConfiguration() {
        show()
        saved = config().put("smtpHost", "server-changed.invalid").put("smtpPort", 465).put("loginFailureTurnstileThreshold", 5)
        refresh()
        compose.waitUntil(10_000) { requests("GET", "/security-admin/config").size == 2 }
        compose.waitUntil(10_000) { compose.onNodeWithTag("security-host").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "server-changed.invalid" }
        field("security-host").assertTextContains("server-changed.invalid")
        field("security-port").assertTextContains("465")
        field("security-threshold").assertTextContains("5")
    }

    @Test fun invalidPortStaysRawAndNeverSendsPatch() {
        show()
        listOf("", "0", "-587", "587x", "65536", "99999999999999999999").forEach { raw ->
            field("security-port").performTextReplacement(raw)
            button("保存配置").performClick()
            text("SMTP 端口必须是 1 到 65535 之间的整数。").assertIsDisplayed()
            field("security-port").assertTextContains(raw)
        }
        assertTrue(requests("PATCH", "/security-admin/config").isEmpty())
    }

    @Test fun invalidThresholdStaysRawAndNeverSendsPatch() {
        show()
        listOf("", "0", "-3", "6", "3x", "99999999999999999999").forEach { raw ->
            field("security-threshold").performTextReplacement(raw)
            button("保存配置").performClick()
            text("登录失败触发次数必须是 1 到 5 之间的整数。").assertIsDisplayed()
            field("security-threshold").assertTextContains(raw)
        }
        assertTrue(requests("PATCH", "/security-admin/config").isEmpty())
    }

    @Test fun failedSaveShowsValidationArrayPreservesSecretsAndBlocksDuplicates() {
        val attempts = AtomicInteger()
        val releaseFailure = CountDownLatch(1)
        override = { r -> if (r.method == "PATCH" && attempts.incrementAndGet() == 1) {
            releaseFailure.await(30, TimeUnit.SECONDS)
            json(JSONObject().put("message", JSONArray(listOf("Fixture validation error", "Fixture sender invalid")))).setResponseCode(400)
        } else null }
        show()
        field("security-host").performTextReplacement("draft.invalid")
        field("security-password").performTextReplacement("fixture-password")
        try {
            button("保存配置").performClick()
            compose.waitUntil(10_000) { attempts.get() == 1 }
            button("保存中…").assertIsNotEnabled().performTouchInput { click() }
            compose.waitForIdle()
            assertEquals(1, attempts.get())
        } finally {
            releaseFailure.countDown()
        }
        waitText("Fixture validation error\nFixture sender invalid")
        text("Fixture validation error\nFixture sender invalid").assertIsDisplayed()
        field("security-host").assertTextContains("draft.invalid")
        field("security-password").assertTextContains("fixture-password")
        text("Fixture validation error\nFixture sender invalid").assertIsDisplayed()
        capture("security-save-error")
        button("保存配置").performClick()
        text("安全配置已保存。").assertIsDisplayed()
        assertEquals(2, attempts.get())
        assertFieldValue("security-password", "")
    }

    @Test fun successfulSaveUsesConfirmedValuesClearsSecretsAndKeepsGoogleUntouched() {
        override = { r -> if (r.method == "PATCH") json(config().put("smtpHost", "confirmed.invalid").put("smtpPort", 465).put("loginFailureTurnstileThreshold", 5)) else null }
        show()
        field("security-password").performTextReplacement("fixture-smtp-new")
        field("security-secret").performTextReplacement("fixture-turnstile-new")
        button("保存配置").performClick()
        text("安全配置已保存。").assertIsDisplayed()
        val body = body(requests("PATCH", "/security-admin/config").single())
        assertEquals("fixture-smtp-new", body.getString("smtpPassword"))
        assertEquals("fixture-turnstile-new", body.getString("turnstileSecret"))
        assertFalse(body.keys().asSequence().any { it.startsWith("google") })
        field("security-host").assertTextContains("confirmed.invalid")
        field("security-port").assertTextContains("465")
        field("security-threshold").assertTextContains("5")
        assertFieldValue("security-password", "")
        assertFieldValue("security-secret", "")
    }

    @Test fun emptyOptionalSecretsAreOmittedAndDisabledEmailCanSaveWithoutSender() {
        saved = config().put("smtpEnabled", false).put("smtpFromEmail", "")
        show()
        button("保存配置").performClick()
        text("安全配置已保存。").assertIsDisplayed()
        val body = body(requests("PATCH", "/security-admin/config").single())
        assertFalse(body.has("smtpPassword")); assertFalse(body.has("turnstileSecret")); assertFalse(body.has("smtpFromEmail"))
        button("测试 SMTP").assertIsNotEnabled()
    }

    @Test fun smtpTestRequiresSavedSettingsAndFailureCanRetry() {
        val attempts = AtomicInteger()
        override = { r -> if (path(r) == "/security-admin/smtp/test" && attempts.incrementAndGet() == 1) error("Fixture SMTP connection unavailable") else null }
        show()
        field("security-host").performTextReplacement("draft.invalid")
        button("测试 SMTP").performClick()
        text("请先保存配置，再测试已保存的 SMTP 设置。").assertIsDisplayed()
        assertEquals(0, attempts.get())
        button("保存配置").performClick(); text("安全配置已保存。").assertIsDisplayed()
        button("测试 SMTP").performClick(); text("Fixture SMTP connection unavailable").assertIsDisplayed()
        button("测试 SMTP").performClick(); text("Fixture SMTP success").assertIsDisplayed()
        assertEquals(2, attempts.get())
    }

    @Test fun encryptionUnavailableDisablesBothSecretFields() {
        saved = config().put("encryptionConfigured", false)
        show()
        field("security-password").assertIsNotEnabled()
        field("security-secret").assertIsNotEnabled()
        text("密钥加密未配置").assertExists()
    }

    @Test fun recordFiltersPaginationAndTabsOnlyReadRecords() {
        show()
        button("下一页").performClick()
        compose.waitUntil(10_000) { requests("GET", "/security-admin/overview").any { it.requestUrl!!.queryParameter("page") == "2" } }
        text("fixture-page-2@example.invalid").assertIsDisplayed()
        compose.onNodeWithTag("security-search").performScrollTo().performTextReplacement("fixture query")
        compose.waitUntil(10_000) { requests("GET", "/security-admin/overview").any { it.requestUrl!!.queryParameter("search") == "fixture query" && it.requestUrl!!.queryParameter("page") == "1" } }
        compose.onNodeWithTag("security-status").performScrollTo().performClick()
        compose.onNodeWithText("发送失败").performClick()
        compose.waitUntil(10_000) { requests("GET", "/security-admin/overview").any { it.requestUrl!!.queryParameter("status") == "failed" } }
        button("验证码").performClick()
        compose.waitUntil(10_000) { requests("GET", "/security-admin/overview").any { it.requestUrl!!.queryParameter("tab") == "verification" } }
        val last = requests("GET", "/security-admin/overview").last().requestUrl!!
        assertNull(last.queryParameter("status")); assertNull(last.queryParameter("search")); assertEquals("1", last.queryParameter("page"))
        assertEquals(1, requests("GET", "/security-admin/config").size)
        text("敏感操作验证 · 2 次尝试").assertIsDisplayed()
    }

    @Test fun emptyLaterPageStillHasPreviousPageAction() {
        override = { r -> if (path(r) == "/security-admin/overview" && r.requestUrl!!.queryParameter("page") == "2") json(JSONObject().put("items", JSONArray()).put("page", 2).put("pageSize", 10).put("totalPages", 2).put("total", 10)) else null }
        show(); button("下一页").performClick()
        text("暂无匹配记录。").assertIsDisplayed()
        button("上一页").assertIsEnabled().performClick()
        text("fixture-recipient@example.invalid").assertIsDisplayed()
    }

    @Test fun longRecordDetailsAreScrollableAndCloseRemainsVisible() {
        override = { r -> if (path(r) == "/security-admin/overview") json(records(r).put("items", JSONArray().put(record("1").put("lastError", (1..60).joinToString("\n") { "Fixture error line $it" })))) else null }
        show()
        text("fixture-recipient@example.invalid").performClick()
        waitText("安全记录详情")
        compose.onNodeWithText("关闭").assertIsDisplayed()
        compose.onNodeWithTag("security-record-detail").performTouchInput { swipeUp() }
        compose.onNodeWithText("关闭").assertIsDisplayed()
        capture("security-long-record-detail")
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun losingPermissionDuringDelayedSaveCannotReportSuccess() {
        override = { r -> if (r.method == "PATCH") json(config()).setBodyDelay(1400, TimeUnit.MILLISECONDS) else null }
        val current = mutableStateOf(session)
        setTestContent { HlovetTheme { NativeAdminSecurityScreen(current.value, {}) } }
        waitText("安全配置")
        button("保存配置").performClick()
        compose.waitUntil(10_000) { requests("PATCH", "/security-admin/config").size == 1 }
        compose.runOnIdle { current.value = session.copy(isAdministrator = false, isSuperAdmin = false) }
        waitText("当前账号没有安全管理权限。")
        Thread.sleep(1700); compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("安全配置已保存。").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("security-password").fetchSemanticsNodes().isEmpty())
        assertEquals(1, requests("PATCH", "/security-admin/config").size)
    }

    @Test fun losingPermissionClosesRecordDetailsAndNoFurtherReadsOccur() {
        val current = mutableStateOf(session)
        setTestContent { HlovetTheme { NativeAdminSecurityScreen(current.value, {}) } }
        waitText("安全配置"); text("fixture-recipient@example.invalid").performClick(); waitText("安全记录详情")
        val count = calls.size
        compose.runOnIdle { current.value = session.copy(isAdministrator = false, isSuperAdmin = false) }
        waitText("当前账号没有安全管理权限。")
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        assertEquals(count, calls.size)
    }

    @Test fun switchingAccountDiscardsPreviousDelayedConfigurationAndSecretDraft() {
        val current = mutableStateOf(session)
        setTestContent { HlovetTheme { NativeAdminSecurityScreen(current.value, {}) } }
        waitText("安全配置"); field("security-password").performTextReplacement("first-account-fixture-secret")
        override = { r -> when {
            path(r) == "/security-admin/config" && r.getHeader("Authorization") == "Bearer second-fixture-token" -> json(config().put("smtpHost", "second-account.invalid"))
            path(r) == "/security-admin/config" -> json(config().put("smtpHost", "late-first-account.invalid")).setBodyDelay(1400, TimeUnit.MILLISECONDS)
            else -> null
        } }
        refresh(); compose.waitUntil(10_000) { requests("GET", "/security-admin/config").size == 2 }
        compose.runOnIdle { current.value = session.copy(accessToken = "second-fixture-token", userId = 2) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("security-host").fetchSemanticsNodes().any { it.config[SemanticsProperties.EditableText].text == "second-account.invalid" } }
        Thread.sleep(1700); compose.waitForIdle()
        field("security-host").assertTextContains("second-account.invalid")
        assertFieldValue("security-password", "")
        assertTrue(requests("PATCH", "/security-admin/config").isEmpty())
    }

    @Test fun englishFallbackAndKnownRecordLabelsAreLocalized() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("en-US") }
        override = { r -> if (path(r) == "/security-admin/smtp/test") json(JSONObject()).setResponseCode(503) else null }
        setTestContent { HlovetTheme { NativeAdminSecurityScreen(session, {}) } }
        waitText("Security settings")
        button("Test SMTP").performClick()
        text("Security request failed (HTTP 503).").assertIsDisplayed()
        button("Verification codes").performClick()
        text("Sensitive-action verification · 2 attempts").assertIsDisplayed()
        text("fixture-code@example.invalid").performClick()
        waitText("Security record details")
        compose.onNodeWithText("Close").assertIsDisplayed()
        capture("security-english-record-detail")
    }

    @Test fun riskLevelFilterAndDetailsUseVisibleUserAndSource() {
        override = { r -> if (path(r) == "/security-admin/overview" && r.requestUrl!!.queryParameter("tab") == "risk")
            json(records(r).put("items", JSONArray().put(record("risk-1").put("type", "new_device").put("riskLevel", "high").put("deviceLabel", "Fixture Android").put("ip", "192.0.2.7").put("user", JSONObject().put("username", "fixture-person").put("nickname", "Fixture person"))))) else null }
        show(); button("风险事件").performClick()
        text("新设备登录 · @fixture-person").assertIsDisplayed()
        compose.onNodeWithTag("security-status").performScrollTo().performClick()
        compose.onNode(hasText("高风险") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(10_000) { requests("GET", "/security-admin/overview").any { it.requestUrl!!.queryParameter("tab") == "risk" && it.requestUrl!!.queryParameter("status") == "high" } }
        text("Fixture person").performClick(); waitText("安全记录详情")
        compose.onNodeWithText("Fixture person · @fixture-person").assertExists()
        compose.onNodeWithText("192.0.2.7").assertExists()
    }

    @Test fun emptyHttpSuccessIsShownAsReadFailureInsteadOfDefaultConfiguration() {
        val reads = AtomicInteger()
        override = { r -> if (path(r) == "/security-admin/config" && reads.incrementAndGet() == 1) MockResponse().setResponseCode(200) else null }
        setTestContent { HlovetTheme { NativeAdminSecurityScreen(session, {}) } }
        text("安全管理返回为空，请重试。").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("security-host").fetchSemanticsNodes().isEmpty())
        text("fixture-recipient@example.invalid").assertIsDisplayed()
        button("重试配置").performClick(); waitText("安全配置")
        field("security-host").assertTextContains("fixture.invalid")
    }

    @Test fun superAdminDowngradeClearsSecretsEvenWhenReadAccessRemains() {
        val current = mutableStateOf(session)
        setTestContent { HlovetTheme { NativeAdminSecurityScreen(current.value, {}) } }
        waitText("安全配置"); field("security-password").performTextReplacement("fixture-super-admin-secret")
        compose.runOnIdle { current.value = session.copy(isSuperAdmin = false) }
        compose.waitUntil(10_000) { requests("GET", "/security-admin/config").size == 2 }
        field("security-password").assertIsNotEnabled()
        assertFieldValue("security-password", "")
        text("fixture-recipient@example.invalid").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("保存配置").fetchSemanticsNodes().isEmpty())
        assertTrue(requests("PATCH", "/security-admin/config").isEmpty())
    }

    private fun show(account: NativeSession = session) {
        setTestContent { HlovetTheme { NativeAdminSecurityScreen(account, {}) } }
        waitText("安全配置")
    }
    private fun field(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("security-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun assertFieldValue(tag: String, value: String) { assertEquals(value, field(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text) }
    private fun text(value: String): SemanticsNodeInteraction {
        compose.waitUntil(10_000) {
            try {
                compose.onNodeWithTag("security-list").performScrollToNode(hasText(value))
                compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
            } catch (_: AssertionError) {
                false
            }
        }
        return compose.onAllNodesWithText(value)[0].performScrollTo()
    }
    private fun button(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("security-list").performScrollToNode(hasText(value) and hasClickAction())
        return compose.onAllNodes(hasText(value) and hasClickAction())[0].performScrollTo()
    }
    private fun refresh() {
        compose.onNodeWithTag("security-list").performScrollToNode(hasContentDescription("刷新"))
        compose.onNodeWithContentDescription("刷新").assertIsEnabled().performClick()
    }
    private fun waitText(value: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
    private fun path(request: RecordedRequest) = request.requestUrl!!.encodedPath
    private fun requests(method: String, target: String) = calls.filter { it.method == method && path(it) == target }
    private fun body(request: RecordedRequest) = JSONObject(request.body.clone().readUtf8())
    private fun config() = JSONObject("""{"smtpEnabled":true,"smtpHost":"fixture.invalid","smtpPort":587,"smtpSecure":false,"smtpUsername":"fixture-user","smtpPasswordConfigured":true,"smtpFromName":"Fixture","smtpFromEmail":"fixture@example.invalid","registrationEmailVerificationEnabled":true,"passwordRecoveryEnabled":true,"untrustedDeviceEmailVerificationEnabled":true,"turnstileSiteKey":"fixture-site","turnstileSecretConfigured":true,"turnstileRegistrationEnabled":false,"turnstileLoginEnabled":true,"turnstileRecoveryEnabled":false,"loginFailureTurnstileThreshold":3,"encryptionConfigured":true,"updatedAt":"2026-10-13T01:00:00Z"}""")
    private fun record(id: String) = JSONObject().put("id", id).put("recipient", if (id == "1") "fixture-recipient@example.invalid" else "fixture-page-2@example.invalid").put("email", "fixture-code@example.invalid").put("subject", "Fixture security mail").put("type", "device_login_verification").put("purpose", "sensitive_action").put("status", "pending").put("attempts", 2).put("lastError", JSONObject.NULL).put("sentAt", JSONObject.NULL).put("createdAt", "2026-10-13T01:00:00Z").put("expiresAt", "2026-10-13T01:10:00Z")
    private fun records(request: RecordedRequest): JSONObject {
        val page = request.requestUrl!!.queryParameter("page")?.toInt() ?: 1
        return JSONObject().put("items", JSONArray().put(record(page.toString()))).put("total", 11).put("page", page).put("pageSize", 10).put("totalPages", 2)
    }
    private fun respond(request: RecordedRequest): MockResponse = when (path(request)) {
        "/security-admin/config" -> if (request.method == "PATCH") {
            val update = body(request)
            saved = JSONObject(saved.toString()).apply { update.keys().forEach { key -> if (key != "smtpPassword" && key != "turnstileSecret") put(key, update.get(key)) } }
            json(saved)
        } else json(saved)
        "/security-admin/overview" -> json(records(request))
        "/security-admin/smtp/test" -> json(JSONObject().put("message", "Fixture SMTP success"))
        else -> error("Unexpected fixture request")
    }
    private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun error(message: String) = json(JSONObject().put("message", message)).setResponseCode(503)
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: instrumentation.targetContext.getExternalFilesDir(null)!!.absolutePath
        val directory = File(output, "security-evidence").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
}
