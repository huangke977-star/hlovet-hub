package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Operational writes run only against the explicitly selected device-local fixture. */
@RunWith(AndroidJUnit4::class)
class NativeAdminOperationsTest {
    @get:Rule val compose = createComposeRule()
    private val contentHost = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var override: (RecordedRequest) -> MockResponse? = { null }
    @Volatile private var policy = JSONObject("""{"cleanupEnabled":true,"businessDays":180,"securityDays":365,"serverDays":90,"lastCleanupCount":0}""")
    @Volatile private var alerts = JSONArray().put(alert("open"))
    @Volatile private var runs = JSONArray()
    @Volatile private var dependencies = JSONArray()
    private val session = NativeSession(accessToken = "fixture-token", refreshToken = "fixture-refresh", userId = 1,
        username = "fixture-admin", nickname = "Fixture admin", isAdministrator = true, isSuperAdmin = true,
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46))

    @Before fun start() {
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
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
        try { contentHost.dispose() }
        finally {
            try { server.shutdown() }
            finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) } }
        }
    }

    @Test fun regularAdminCannotReadOrRunSuperAdminOperations() {
        contentHost.setContent { HlovetTheme { NativeAdminOperationsScreen(session.copy(isSuperAdmin = false), {}) } }
        waitText("运营与质量仅超级管理员可访问。")
        assertTrue(calls.isEmpty())
    }

    @Test fun qualityReadFailureDoesNotHideOperationsAndLocalRetryPreservesPolicyDraft() {
        val reads = AtomicInteger()
        override = { request -> if (path(request) == "/admin/system/quality/overview" && reads.incrementAndGet() == 1) error("Quality unavailable") else null }
        show()
        field("audit", "业务").performScrollTo().performTextReplacement("21")
        text("quality", "Quality unavailable").performScrollTo().assertIsDisplayed()
        button("quality", "重试").performClick()
        waitText("数据库连接正常")
        button("audit", "保存策略").performScrollTo().performClick()
        waitText("审计留存策略已保存。")
        assertEquals(21, body("/admin/system/operations/audit-policy").getInt("businessDays"))
        assertEquals(1, requests("GET", "/admin/system/operations/overview").size)
    }

    @Test fun acknowledgingAndResolvingUpdateRowsImmediatelyWithoutReload() {
        show()
        field("audit", "业务").performScrollTo().performTextReplacement("22")
        button("alerts", "确认").performScrollTo().performClick()
        dialogButton("确认").performClick()
        waitNoDialog(); waitText("已确认")
        compose.onNodeWithTag("operations-alert-7").assertIsDisplayed()
        capture("alert-acknowledged")
        button("alerts", "解决").performScrollTo().performClick()
        dialogButton("确认解决").performClick()
        waitNoDialog(); waitText("当前没有未解决告警。")
        text("alerts", "当前没有未解决告警。").assertIsDisplayed()
        assertEquals(1, requests("GET", "/admin/system/operations/overview").size)
        button("audit", "保存策略").performScrollTo().performClick()
        waitText("审计留存策略已保存。")
        assertEquals(22, body("/admin/system/operations/audit-policy").getInt("businessDays"))
    }

    @Test fun failedAlertActionKeepsConfirmationAndPreventsDuplicateSubmission() {
        val attempts = AtomicInteger()
        override = { request -> if (path(request).endsWith("/7/acknowledge") && attempts.incrementAndGet() == 1) error("Alert unavailable").setBodyDelay(1200, TimeUnit.MILLISECONDS) else null }
        show()
        button("alerts", "确认").performScrollTo().performClick()
        dialogButton("确认").performClick()
        dialogButton("执行中…").assertIsNotEnabled().performClick()
        waitDialogText("Alert unavailable")
        capture("alert-error-in-confirmation")
        dialogButton("确认").assertIsEnabled().performClick()
        waitNoDialog(); waitText("已确认")
        assertEquals(2, attempts.get())
    }

    @Test fun loadTestRejectsInvalidNumbersWithoutSilentlyClamping() {
        show()
        listOf("0", "-2", "2.0", "2abc").forEach { invalid ->
            field("load", "并发").performScrollTo().performTextReplacement(invalid)
            button("load", "开始只读压测").performScrollTo().performClick()
            waitText("并发 应为 1 到 50 的整数。")
            text("load", "并发 应为 1 到 50 的整数。").assertIsDisplayed()
            assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        }
        field("load", "并发").performTextReplacement("2")
        listOf("301", "1000", "10x").forEach { invalid ->
            field("load", "秒数").performTextReplacement(invalid)
            button("load", "开始只读压测").performClick()
            waitText("秒数 应为 1 到 300 的整数。")
            assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        }
        assertTrue(requests("POST", "/admin/system/operations/load-tests").isEmpty())
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
    }

    @Test fun loadTestPollsArrayResponsesAndUpdatesOpenRunDetailsWhileKeepingDraft() {
        val reads = AtomicInteger()
        val complete = AtomicBoolean(false)
        override = { request -> when (path(request)) {
            "/admin/system/operations/load-tests" -> { runs = JSONArray().put(run(19, "running")); json(run(19, "running")) }
            "/admin/system/operations/runs" -> {
                reads.incrementAndGet()
                if (!complete.get()) error("Progress unavailable")
                else {
                    runs = JSONArray().put(run(19, "passed").put("metrics", JSONObject().put("requests", 120).put("successRate", 100)))
                    array(runs)
                }
            }
            else -> null
        } }
        show()
        field("audit", "业务").performScrollTo().performTextReplacement("23")
        button("load", "开始只读压测").performScrollTo().performClick()
        dialogButton("确认启动").performClick()
        waitNoDialog()
        node("runs", hasTestTag("operations-run-19")).performScrollTo().performClick()
        waitDialogText("Progress unavailable")
        complete.set(true)
        waitDialogText("状态：通过")
        capture("load-test-live-completed-details")
        dialogButton("关闭").performClick()
        button("audit", "保存策略").performScrollTo().performClick()
        waitText("审计留存策略已保存。")
        assertEquals(23, body("/admin/system/operations/audit-policy").getInt("businessDays"))
        assertEquals(listOf("/health"), (0 until body("/admin/system/operations/load-tests").getJSONArray("paths").length()).map { body("/admin/system/operations/load-tests").getJSONArray("paths").getString(it) })
        assertTrue(reads.get() >= 2)
    }

    @Test fun acknowledgedRunRemainsVisibleWhenOverviewRefreshFails() {
        val created = AtomicInteger()
        override = { request -> when {
            path(request).endsWith("/alert-check") -> { created.incrementAndGet(); json(run(25, "passed", "alert_check")) }
            path(request) == "/admin/system/operations/overview" && created.get() > 0 -> error("Refresh unavailable")
            else -> null
        } }
        show()
        button("checks", "检查告警").performScrollTo().performClick()
        dialogButton("开始检查").performClick()
        waitNoDialog(); waitText("Refresh unavailable")
        text("checks", "告警检查已完成。").performScrollTo().assertIsDisplayed()
        node("runs", hasTestTag("operations-run-25")).performScrollTo().assertIsDisplayed()
        capture("acknowledged-run-refresh-failed")
    }

    @Test fun recoveryFailureCanRetryAndBlockedResultIsNotReportedAsPassed() {
        val attempts = AtomicInteger()
        override = { request -> if (path(request).endsWith("/recovery-drills")) {
            if (attempts.incrementAndGet() == 1) error("Recovery unavailable")
            else { val value = run(31, "blocked", "recovery_drill").put("provider", "local"); runs = JSONArray().put(value); json(value) }
        } else null }
        show()
        button("recovery", "OSS").performScrollTo().assertIsNotEnabled()
        button("recovery", "R2").assertIsNotEnabled()
        button("recovery", "本地").performClick()
        dialogButton("确认演练").performClick()
        waitDialogText("Recovery unavailable")
        capture("recovery-error-dialog")
        dialogButton("确认演练").performClick()
        waitNoDialog(); waitText("本地恢复演练被阻塞。")
        assertEquals("local", body("/admin/system/operations/recovery-drills").getString("provider"))
        assertEquals(2, attempts.get())
    }

    @Test fun auditSaveValidatesLocallyAndRetainsInputsOnServerFailure() {
        val attempts = AtomicInteger()
        override = { request -> if (path(request).endsWith("/audit-policy") && attempts.incrementAndGet() == 1) error("Policy unavailable") else null }
        show()
        listOf("", "36500", "36x").forEach { invalid ->
            field("audit", "安全").performScrollTo().performTextReplacement(invalid)
            button("audit", "保存策略").performScrollTo().performClick()
            waitText("安全留存天数 应为 7 到 3650 的整数。")
        }
        assertTrue(requests("POST", "/admin/system/operations/audit-policy").isEmpty())
        field("audit", "安全").performTextReplacement("366")
        button("audit", "保存策略").performClick()
        waitText("Policy unavailable")
        text("audit", "Policy unavailable").assertIsDisplayed()
        button("audit", "保存策略").performClick()
        waitText("审计留存策略已保存。")
        assertEquals(366, body("/admin/system/operations/audit-policy").getInt("securityDays"))
        assertEquals(2, attempts.get())
        capture("audit-policy-saved-feedback")
    }

    @Test fun auditCleanupFailureKeepsDialogAndSuccessPreservesUnsavedRetention() {
        val attempts = AtomicInteger()
        override = { request -> if (path(request).endsWith("/audit-cleanup")) {
            if (attempts.incrementAndGet() == 1) error("Cleanup unavailable")
            else { policy = JSONObject(policy.toString()).put("lastCleanupCount", 7).put("lastCleanupAt", "2026-10-13T01:00:00Z"); json(JSONObject().put("policy", policy).put("deletedCount", 7)) }
        } else null }
        show()
        field("audit", "业务").performScrollTo().performTextReplacement("24")
        button("audit", "立即清理").performScrollTo().performClick()
        dialogButton("确认清理").performClick()
        waitDialogText("Cleanup unavailable")
        dialogButton("确认清理").performClick()
        waitNoDialog(); waitText("审计日志清理完成，共删除 7 条记录。")
        button("audit", "保存策略").performScrollTo().performClick()
        waitText("审计留存策略已保存。")
        assertEquals(24, body("/admin/system/operations/audit-policy").getInt("businessDays"))
    }

    @Test fun qualityCheckRetainsFailedConfirmationAndRecordsFailedRunHonestly() {
        val attempts = AtomicInteger()
        override = { request -> if (path(request).endsWith("/release-check")) {
            if (attempts.incrementAndGet() == 1) error("Quality check unavailable")
            else { val value = run(37, "failed", "quality_check"); runs = JSONArray().put(value); json(value) }
        } else null }
        show()
        button("quality", "重新执行质量检查").performScrollTo().performClick()
        dialogButton("开始检查").performClick()
        waitDialogText("Quality check unavailable")
        dialogButton("开始检查").performClick()
        waitNoDialog(); waitText("质量检查失败。")
        node("runs", hasTestTag("operations-run-37")).performScrollTo().assertIsDisplayed()
        assertEquals(2, attempts.get())
    }

    @Test fun permissionLossDuringOperationCannotReopenDialogOrExposeRun() {
        override = { request -> if (path(request).endsWith("/alert-check")) json(run(49, "passed", "alert_check")).setBodyDelay(1200, TimeUnit.MILLISECONDS) else null }
        val account = mutableStateOf(session)
        contentHost.setContent { HlovetTheme { NativeAdminOperationsScreen(account.value, {}) } }
        waitText("运营检查")
        button("checks", "检查告警").performScrollTo().performClick()
        dialogButton("开始检查").performClick()
        compose.waitUntil(10_000) { requests("POST", "/admin/system/operations/runs/alert-check").isNotEmpty() }
        compose.runOnIdle { account.value = session.copy(isSuperAdmin = false) }
        waitText("运营与质量仅超级管理员可访问。")
        Thread.sleep(1800); compose.waitForIdle()
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("告警检查 #49").fetchSemanticsNodes().isEmpty())
    }

    @Test fun dependencyReviewFailureRetriesAndShowsAllReturnedVersions() {
        val attempts = AtomicInteger()
        override = { request -> if (path(request).endsWith("/dependency-review")) {
            if (attempts.incrementAndGet() == 1) error("Dependency review unavailable")
            else {
                dependencies = JSONArray((1..20).map { JSONObject().put("name", "fixture-package-$it").put("current", "1.0.$it").put("category", "api").put("status", "pinned").put("note", "Fixture dependency") })
                val value = run(53, "passed", "dependency_review"); runs = JSONArray().put(value); json(value)
            }
        } else null }
        show()
        button("checks", "依赖评估").performScrollTo().performClick()
        dialogButton("开始评估").performClick()
        waitDialogText("Dependency review unavailable")
        dialogButton("开始评估").performClick()
        waitNoDialog(); waitText("依赖评估已完成。")
        compose.onNodeWithTag("operations-list").performScrollToNode(hasTestTag("operations-dependencies-card"))
        waitText("fixture-package-20")
        node("dependencies", hasText("fixture-package-20")).performScrollTo().assertIsDisplayed()
        capture("all-dependency-versions")
        node("runs", hasTestTag("operations-run-53")).performScrollTo().assertIsDisplayed()
        assertEquals(2, attempts.get())
    }

    @Test fun longRunDetailsRemainScrollableThroughTheFinalMetric() {
        val detail = JSONObject()
        (1..40).forEach { detail.put("fixture-detail-$it", "Fixture value $it") }
        runs = JSONArray().put(run(59, "passed").put("detail", detail).put("metrics", JSONObject().put("fixture-final-metric", 456)))
        show()
        node("runs", hasTestTag("operations-run-59")).performScrollTo().performClick()
        compose.onNode(hasText("fixture-final-metric", substring = true) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        dialogButton("关闭").assertIsDisplayed()
        capture("long-run-details-final-metric")
        dialogButton("关闭").performClick()
        waitNoDialog()
    }

    @Test fun browserAcceptanceLoadsAllLocalContractsAndDesktopMobilePages() {
        override = { request -> publicFixture(request) }
        show()
        button("quality", "浏览器验收").performScrollTo().performClick()
        dialogButton("开始验收").performClick()
        dialogButton("执行中…").assertIsNotEnabled()
        waitText("浏览器验收全部通过。", 90_000)
        waitNoDialog()
        text("quality", "合集列表（移动）").performScrollTo().assertIsDisplayed()
        capture("browser-acceptance-all-local-pages")
        assertEquals(1, requests("GET", "/api/health").size)
        assertEquals(1, requests("GET", "/api/health/ready").size)
        assertEquals(1, requests("GET", "/sw.js").size)
        assertEquals(2, requests("GET", "/articles").size)
        assertEquals(2, requests("GET", "/en/articles/collections").size)
        assertTrue(calls.none { it.method == "POST" })
    }

    @Test fun browserAcceptanceReportsHttpFailureAndOverflowWithoutClaimingPassed() {
        override = { request -> when (path(request)) {
            "/api/distribution/feeds/site.rss" -> error("Fixture RSS unavailable")
            "/topics" -> html("<p>Fixture page unavailable</p>").setResponseCode(503)
            "/articles/collections" -> html("<div style=\"width:2400px\">Fixture overflow</div>")
            else -> publicFixture(request)
        } }
        show()
        button("quality", "浏览器验收").performScrollTo().performClick()
        dialogButton("开始验收").performClick()
        waitText("浏览器验收发现问题，请查看逐项结果。", 90_000)
        waitNoDialog()
        text("quality", "HTTP 503").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("浏览器错误：HTTP 503")[0].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("浏览器错误：HTTP 503").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        val overflow = compose.onAllNodesWithText("横向溢出", substring = true)
        capture("browser-acceptance-local-http-overflow-before-assertion")
        val dimensions = compose.onAllNodesWithText("视口", substring = true).fetchSemanticsNodes().joinToString("\n") { it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString() }
        assertEquals(dimensions, 2, overflow.fetchSemanticsNodes().size)
        overflow[1].performScrollTo().assertIsDisplayed()
        capture("browser-acceptance-local-http-overflow-failures")
        assertTrue(compose.onAllNodesWithText("浏览器验收全部通过。").fetchSemanticsNodes().isEmpty())
        assertTrue(calls.none { it.method == "POST" })
    }

    @Test fun englishLabelsAndAllLoadedRunRowsAreAvailable() {
        runs = JSONArray((1..20).map { run(it, "passed") })
        NativeI18n.applyLocale("en-US")
        contentHost.setContent { HlovetTheme { NativeAdminOperationsScreen(session, {}) } }
        waitText("Operations checks")
        text("quality", "Database ready").performScrollTo().assertIsDisplayed()
        text("quality", "Health script · npm run check").performScrollTo().assertIsDisplayed()
        node("runs", hasTestTag("operations-run-20")).performScrollTo().assertIsDisplayed()
        field("load", "Concurrency").performScrollTo().assertIsDisplayed()
        val left = field("load", "Concurrency").fetchSemanticsNode().boundsInRoot
        val right = field("load", "Seconds").fetchSemanticsNode().boundsInRoot
        assertEquals(left.height, right.height, 1f)
        assertEquals(left.top, right.top, 1f)
        capture("english-load-fields-aligned")
    }

    private fun show() { contentHost.setContent { HlovetTheme { NativeAdminOperationsScreen(session, {}) } }; waitText("运营检查") }
    private fun node(card: String, matcher: SemanticsMatcher): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("operations-list").performScrollToNode(hasTestTag("operations-$card-card"))
        return compose.onNode(matcher and hasAnyAncestor(hasTestTag("operations-$card-card")))
    }
    private fun button(card: String, label: String) = node(card, hasText(label) and hasClickAction())
    private fun field(card: String, label: String) = node(card, hasText(label) and hasSetTextAction())
    private fun text(card: String, value: String) = node(card, hasText(value))
    private fun dialogButton(label: String) = compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun waitText(value: String, timeoutMillis: Long = 10_000) { compose.waitUntil(timeoutMillis) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitDialogText(value: String) { compose.waitUntil(10_000) { compose.onAllNodes(hasText(value) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitNoDialog() { compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() } }
    private fun path(request: RecordedRequest) = request.requestUrl!!.encodedPath
    private fun requests(method: String, target: String) = calls.filter { it.method == method && path(it) == target }
    private fun body(target: String) = JSONObject(requests("POST", target).last().body.clone().readUtf8())
    private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun array(value: JSONArray) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun error(message: String) = json(JSONObject().put("message", message)).setResponseCode(503)
    private fun alert(status: String) = JSONObject().put("id", 7).put("title", "Fixture disk alert").put("message", "Fixture disk threshold").put("status", status).put("severity", "warning").put("lastSeenAt", "2026-10-13T01:00:00Z")
    private fun run(id: Int, status: String, kind: String = "load_test") = JSONObject().put("id", id).put("kind", kind).put("status", status).put("summary", "Fixture run $id").put("createdAt", "2026-10-13T01:00:00Z").put("startedAt", "2026-10-13T01:00:00Z").put("completedAt", if (status == "running") JSONObject.NULL else "2026-10-13T01:00:05Z")
    private fun respond(request: RecordedRequest): MockResponse = when (path(request)) {
        "/admin/system/operations/overview" -> json(JSONObject().put("auditPolicy", policy).put("alerts", alerts).put("runs", runs)
            .put("dependencyAssessment", JSONObject().put("items", dependencies)).put("recoveryTargets", JSONArray())
            .put("externalStorage", JSONObject().put("encryptionConfigured", true).put("ossConfigured", false).put("r2Configured", false).put("message", "Fixture storage"))
            .put("loadTestTargets", JSONArray().put(JSONObject().put("path", "/health").put("label", "健康检查").put("labelEn", "Health check"))))
        "/admin/system/quality/overview" -> json(JSONObject().put("checks", JSONArray().put(JSONObject().put("id", "database").put("label", "数据库").put("labelEn", "Database").put("detail", "数据库连接正常").put("detailEn", "Database ready").put("status", "passed")))
            .put("browserChecks", JSONArray().put(JSONObject().put("label", "健康脚本").put("labelEn", "Health script").put("command", "npm run check"))))
        "/admin/system/operations/runs" -> array(runs)
        "/admin/system/operations/audit-policy" -> { val body = JSONObject(request.body.clone().readUtf8()); policy = JSONObject(policy.toString()).put("cleanupEnabled", body.getBoolean("cleanupEnabled")).put("businessDays", body.getInt("businessDays")).put("securityDays", body.getInt("securityDays")).put("serverDays", body.getInt("serverDays")); json(policy) }
        "/admin/system/operations/alerts/7/acknowledge" -> { alerts = JSONArray().put(alert("acknowledged")); json(alert("acknowledged")) }
        "/admin/system/operations/alerts/7/resolve" -> { alerts = JSONArray(); json(alert("resolved")) }
        else -> error("Unexpected fixture request")
    }
    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>html,body{margin:0;padding:0}</style></head><body>$body</body></html>")
    private fun publicFixture(request: RecordedRequest): MockResponse? = when {
        path(request).startsWith("/admin/system/") -> null
        path(request).startsWith("/api/") || path(request).endsWith(".webmanifest") || path(request) == "/sw.js" -> json(JSONObject().put("fixture", true))
        else -> html("<p>Fixture public page</p>")
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: instrumentation.targetContext.getExternalFilesDir(null)!!.absolutePath
        val directory = File(output, "operations-evidence").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
}
