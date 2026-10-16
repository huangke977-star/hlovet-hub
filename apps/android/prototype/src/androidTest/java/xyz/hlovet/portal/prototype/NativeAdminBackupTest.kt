package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assert.assertFalse
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

/** Destructive backup operations are exercised only against a device-local fixture. */
@RunWith(AndroidJUnit4::class)
class NativeAdminBackupTest {
    @get:Rule val compose = createComposeRule()
    private val contentHost = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var responseOverride: (RecordedRequest) -> MockResponse? = { null }
    private val session = NativeSession(accessToken = "fixture-token", refreshToken = "fixture-refresh", userId = 1,
        username = "fixture-admin", nickname = "Fixture admin", isAdministrator = true, isSuperAdmin = true,
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46))
    private val name = "hlovet-hub-20261013-010000.sql.gz.enc"
    private val secondName = "hlovet-hub-20261013-020000.sql.gz.enc"
    private var backups = JSONArray().put(backup(name))
    private var mediaJobs = JSONArray()
    private var files = (1..25).map { file(it) }
    private var restoreAllowed = true
    private val configuration = JSONObject("""{"automaticEnabled":false,"scheduleTime":"02:00","timezone":"UTC","localRetentionDays":7,"remoteRetentionDays":30,"encryptionConfigured":true,"oss":{"enabled":false,"region":"cn-hangzhou","bucket":"fixture-oss","prefix":"portal/","hasAccessKeyId":true,"hasSecretAccessKey":true},"r2":{"enabled":false,"accountId":"fixture-account","bucket":"fixture-r2","prefix":"portal/","hasAccessKeyId":true,"hasSecretAccessKey":true}}""")

    @Before fun start() {
        assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.add(request)
                return responseOverride(request) ?: respond(request)
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

    @Test fun mediaPaginationRequestsAndDisplaysEachPage() {
        show()
        catalogDescription("下一页").performScrollTo().performClick()
        waitText("file-21.png")
        catalogText("file-21.png").performScrollTo().assertIsDisplayed()
        catalogText("第 2 / 2 页").performScrollTo().assertIsDisplayed()
        capture("catalog-page-two")
        catalogDescription("上一页").performClick()
        waitText("file-1.png")
        assertEquals(listOf("1", "2", "1"), requests("GET", "/admin/system/media-backups/files").map { it.requestUrl!!.queryParameter("page") })
        assertEquals(1, requests("GET", "/admin/system/backups/configuration").size)
    }

    @Test fun searchAndCategoryResetPageWithoutLosingPolicyDraft() {
        show()
        policyField("执行时间").performScrollTo().performTextReplacement("04:15")
        policyField("本地保留天数").performTextReplacement("17")
        providerField("oss", "Access key").performScrollTo().performTextReplacement("fixture-unsaved-access")
        providerField("oss", "Secret key").performTextReplacement("fixture-unsaved-secret")
        catalogDescription("下一页").performScrollTo().performClick()
        waitText("file-21.png")
        catalogNode(hasTestTag("backup-media-search")).performScrollTo().performTextReplacement("file-21")
        catalogDescription("搜索").performClick()
        waitText("共 1 个")
        catalogText("file-21.png").assertIsDisplayed()
        val searched = requests("GET", "/admin/system/media-backups/files").last()
        assertEquals("1", searched.requestUrl!!.queryParameter("page"))
        assertEquals("file-21", searched.requestUrl!!.queryParameter("q"))
        catalogNode(hasTestTag("backup-media-search")).performTextReplacement("")
        catalogDescription("搜索").performClick()
        waitText("共 25 个")
        catalogNode(hasText("全部分类") and hasClickAction()).performClick()
        compose.onNode(hasText("背景图") and hasClickAction()).performClick()
        waitText("共 12 个")
        assertEquals("backgrounds", requests("GET", "/admin/system/media-backups/files").last().requestUrl!!.queryParameter("category"))
        policyButton("保存备份策略").performScrollTo().performClick()
        waitText("备份策略已保存。")
        val saved = lastBody("POST", "/admin/system/backups/configuration")
        assertEquals("04:15", saved.getString("scheduleTime"))
        assertEquals(17, saved.getInt("localRetentionDays"))
        assertEquals("fixture-unsaved-access", saved.getString("ossAccessKeyId"))
        assertEquals("fixture-unsaved-secret", saved.getString("ossAccessKeySecret"))
        capture("saved-policy-after-catalog-search")
    }

    @Test fun catalogFailureIsLocalAndRetryDoesNotReloadPolicy() {
        val attempts = AtomicInteger()
        responseOverride = { request -> if (path(request) == "/admin/system/media-backups/files" && attempts.incrementAndGet() == 1) json(JSONObject().put("message", "Catalog unavailable"), 503) else null }
        show()
        policyField("执行时间").assertIsEnabled()
        catalogText("Catalog unavailable").performScrollTo().assertIsDisplayed()
        catalogNode(hasText("重试") and hasClickAction()).performClick()
        waitText("file-1.png")
        assertEquals(2, attempts.get())
        assertEquals(1, requests("GET", "/admin/system/backups/configuration").size)
        capture("catalog-retry-recovered")
    }

    @Test fun acknowledgedBackupRemainsVisibleWhenRefreshFailsAndPolicyDraftSurvives() {
        val created = AtomicInteger()
        responseOverride = { request -> when {
            request.method == "POST" && path(request) == "/admin/system/backups" -> { created.incrementAndGet(); json(backup(secondName)) }
            request.method == "GET" && path(request) == "/admin/system/backups/configuration" && created.get() > 0 -> json(JSONObject().put("message", "Refresh after backup failed"), 503)
            else -> null
        } }
        show()
        policyField("本地保留天数").performScrollTo().performTextReplacement("21")
        databaseButton("立即备份").performScrollTo().performClick()
        waitText("Refresh after backup failed")
        databaseText(secondName).performScrollTo().assertIsDisplayed()
        capture("created-backup-refresh-failed")
        policyButton("保存备份策略").performScrollTo().performClick()
        waitText("备份策略已保存。")
        assertEquals(21, lastBody("POST", "/admin/system/backups/configuration").getInt("localRetentionDays"))
    }

    @Test fun policySaveFailureKeepsCredentialsAndAllowsRetry() {
        val attempts = AtomicInteger()
        responseOverride = { request -> if (request.method == "POST" && path(request) == "/admin/system/backups/configuration" && attempts.incrementAndGet() == 1) json(JSONObject().put("message", "Policy save unavailable"), 503) else null }
        show()
        providerField("r2", "Secret key").performScrollTo().performTextReplacement("fixture-r2-new-secret")
        policyButton("保存备份策略").performScrollTo().performClick()
        waitText("Policy save unavailable")
        policyNode(hasText("Policy save unavailable")).assertIsDisplayed()
        policyButton("保存备份策略").performClick()
        waitText("备份策略已保存。")
        assertEquals("fixture-r2-new-secret", lastBody("POST", "/admin/system/backups/configuration").getString("r2SecretAccessKey"))
        providerField("r2", "Secret key").performScrollTo()
        assertEquals("", providerField("r2", "Secret key").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        capture("policy-saved-key-not-echoed")
    }

    @Test fun invalidRetentionBlocksRequestAndShowsLocalValidation() {
        show()
        policyField("本地保留天数").performScrollTo().performTextReplacement("0")
        policyButton("保存备份策略").performScrollTo().performClick()
        waitText("本地保留天数应为 1 到 365 的整数。")
        policyNode(hasText("本地保留天数应为 1 到 365 的整数。")).assertIsDisplayed()
        assertTrue(requests("POST", "/admin/system/backups/configuration").isEmpty())
    }

    @Test fun providerTestRequiresSavedPolicyAndUsesServerCredentials() {
        val attempts = AtomicInteger()
        responseOverride = { request -> if (path(request) == "/admin/system/backups/providers/test" && attempts.incrementAndGet() == 1) json(JSONObject().put("message", "Provider unavailable"), 503) else null }
        show()
        policyField("本地保留天数").performScrollTo().performTextReplacement("14")
        providerButton("r2", "测试连接").performScrollTo().performClick()
        waitText("请先保存备份策略，再测试已保存的远端连接。")
        providerNode("r2", hasText("请先保存备份策略，再测试已保存的远端连接。")).performScrollTo().assertIsDisplayed()
        assertTrue(requests("POST", "/admin/system/backups/providers/test").isEmpty())
        policyButton("保存备份策略").performScrollTo().performClick()
        waitText("备份策略已保存。")
        providerButton("r2", "测试连接").performScrollTo().performClick()
        waitText("Provider unavailable")
        providerNode("r2", hasText("Provider unavailable")).performScrollTo().assertIsDisplayed()
        capture("provider-error-near-button")
        providerButton("r2", "测试连接").performScrollTo().performClick()
        waitText("Cloudflare R2 连接正常。")
        providerNode("r2", hasText("Cloudflare R2 连接正常。")).assertIsDisplayed()
        assertEquals("r2", lastBody("POST", "/admin/system/backups/providers/test").getString("provider"))
        assertEquals(1, lastBody("POST", "/admin/system/backups/providers/test").length())
        assertEquals(2, attempts.get())
    }

    @Test fun batchDeleteFailureKeepsDialogAndSelectionForRetry() {
        backups.put(backup(secondName))
        val attempts = AtomicInteger()
        responseOverride = { request -> if (path(request) == "/admin/system/backups/batch-delete") {
            if (attempts.incrementAndGet() == 1) json(JSONObject().put("message", "Backup delete unavailable"), 503).setBodyDelay(1200, TimeUnit.MILLISECONDS)
            else { backups = JSONArray(); json(JSONObject().put("success", true).put("deletedCount", 2)) }
        } else null }
        show()
        compose.onNodeWithTag("backup-list").performScrollToNode(hasTestTag("backup-database-card"))
        compose.onNodeWithTag("backup-select-all").performScrollTo().performClick()
        databaseButton("批量删除").performScrollTo().performClick()
        dialogButton("确认删除").performClick()
        dialogButton("确认删除").assertIsNotEnabled().performClick()
        waitDialogText("Backup delete unavailable")
        dialogButton("确认删除").assertIsEnabled()
        capture("batch-delete-error-dialog")
        dialogButton("确认删除").performClick()
        waitNoDialog()
        waitText("暂无可见数据库备份。")
        assertEquals(2, attempts.get())
        assertEquals(2, lastBody("POST", "/admin/system/backups/batch-delete").getJSONArray("names").length())
    }

    @Test fun restoreFailurePreservesConfirmationAndAllowsRetry() {
        val attempts = AtomicInteger()
        responseOverride = { request -> if (path(request).endsWith("/$name/restore")) {
            if (attempts.incrementAndGet() == 1) json(JSONObject().put("message", "Restore unavailable"), 503)
            else json(JSONObject().put("storageScanId", 12))
        } else null }
        show()
        databaseRowDescription(name, "恢复").performScrollTo().performClick()
        waitText("恢复数据库备份")
        compose.onNodeWithTag("backup-restore-confirmation").performTextReplacement("wrong-name")
        dialogButton("确认恢复").assertIsNotEnabled()
        compose.onNodeWithTag("backup-restore-confirmation").performTextReplacement(name)
        dialogButton("确认恢复").performClick()
        waitDialogText("Restore unavailable")
        assertEquals(name, compose.onNodeWithTag("backup-restore-confirmation").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        capture("restore-error-dialog")
        dialogButton("确认恢复").performClick()
        waitNoDialog()
        assertEquals(2, attempts.get())
        assertEquals(name, lastBody("POST", "/admin/system/backups/$name/restore").getString("confirmation"))
    }

    @Test fun failedPreflightAndBlockedRestoreNeverSubmitRestore() {
        restoreAllowed = false
        val reads = AtomicInteger()
        responseOverride = { request -> if (path(request).endsWith("/$name/preflight") && reads.incrementAndGet() == 1) json(JSONObject().put("message", "Preflight unavailable"), 503) else null }
        show()
        databaseRowDescription(name, "恢复").performScrollTo().performClick()
        waitText("Preflight unavailable")
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        databaseRowDescription(name, "恢复").performClick()
        waitText("恢复数据库备份")
        waitDialogText("恢复前检查未通过，暂不能恢复。请关闭弹窗并检查备份校验状态和服务器配置。")
        compose.onNodeWithTag("backup-restore-confirmation").assertIsNotEnabled()
        dialogButton("确认恢复").assertIsNotEnabled()
        capture("preflight-blocked-explanation")
        assertTrue(calls.none { it.method == "POST" && path(it).endsWith("/restore") })
    }

    @Test fun mediaJobPollingUpdatesOpenDetailsAndRefreshesCatalogOnCompletion() {
        files = listOf(file(1))
        val detailReads = AtomicInteger()
        responseOverride = { request -> when {
            request.method == "POST" && path(request) == "/admin/system/media-backups/jobs" -> { mediaJobs = JSONArray().put(job("running")); json(job("running")) }
            path(request) == "/admin/system/media-backups/jobs/9" -> {
                val status = if (detailReads.incrementAndGet() >= 2) "completed" else "running"
                if (status == "completed") { mediaJobs = JSONArray().put(job(status)); files = listOf(file(1), file(2)) }
                val manifest = JSONObject().put("storedName", "file-2.png").put("provider", "r2").put("status", "uploaded")
                json(job(status).put("manifests", JSONArray().put(manifest)))
            }
            else -> null
        } }
        show()
        policyField("本地保留天数").performScrollTo().performTextReplacement("18")
        mediaButton("立即备份").performScrollTo().performClick()
        waitText("任务 #9 · 执行中")
        mediaButton("详情").performClick()
        waitDialogText("状态：已完成 · 2/2 个文件")
        compose.waitUntil(10_000) { requests("GET", "/admin/system/media-backups/files").size >= 2 }
        capture("media-job-live-completed-details")
        dialogButton("关闭").performClick()
        catalogText("file-2.png").performScrollTo().assertIsDisplayed()
        policyButton("保存备份策略").performScrollTo().performClick()
        waitText("备份策略已保存。")
        assertEquals(18, lastBody("POST", "/admin/system/backups/configuration").getInt("localRetentionDays"))
    }

    @Test fun mediaRestoreFailureIsInsideDialogAndCanRetry() {
        files = listOf(file(1))
        val attempts = AtomicInteger()
        responseOverride = { request -> if (path(request) == "/admin/system/media-backups/files/1/restore") {
            if (attempts.incrementAndGet() == 1) json(JSONObject().put("message", "Media restore unavailable"), 503) else json(JSONObject().put("success", true))
        } else null }
        show()
        catalogNode(hasText("恢复") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("backup-media-restore-confirmation").performTextReplacement("file-1.png")
        dialogButton("确认恢复").performClick()
        waitDialogText("Media restore unavailable")
        capture("media-restore-error-dialog")
        dialogButton("确认恢复").performClick()
        waitNoDialog()
        assertEquals(2, attempts.get())
        assertEquals("file-1.png", lastBody("POST", "/admin/system/media-backups/files/1/restore").getString("confirmation"))
    }

    @Test fun permissionLossClosesSensitiveDialogAndStopsAdminRequests() {
        val account = mutableStateOf(session)
        contentHost.setContent { HlovetTheme { NativeAdminBackupScreen(account.value, {}) } }
        waitText("备份策略")
        databaseRowDescription(name, "恢复").performScrollTo().performClick()
        waitText("恢复数据库备份")
        compose.onNodeWithTag("backup-restore-confirmation").assertIsDisplayed()
        val priorCalls = calls.size
        compose.runOnIdle { account.value = session.copy(isSuperAdmin = false) }
        waitText("备份管理仅超级管理员可访问。")
        waitNoDialog()
        assertEquals(priorCalls, calls.size)
    }

    @Test fun permissionLossDuringPreflightCannotReopenSensitiveDialog() {
        responseOverride = { request -> if (path(request).endsWith("/$name/preflight"))
            json(JSONObject().put("backup", backup(name)).put("canRestore", true).put("warnings", JSONArray())).setBodyDelay(1500, TimeUnit.MILLISECONDS)
            else null }
        val account = mutableStateOf(session)
        contentHost.setContent { HlovetTheme { NativeAdminBackupScreen(account.value, {}) } }
        waitText("备份策略")
        databaseRowDescription(name, "恢复").performScrollTo().performClick()
        compose.waitUntil(10_000) { requests("GET", "/admin/system/backups/$name/preflight").isNotEmpty() }
        compose.runOnIdle { account.value = session.copy(isSuperAdmin = false) }
        waitText("备份管理仅超级管理员可访问。")
        Thread.sleep(2_000)
        compose.waitForIdle()
        capture("permission-revoked-during-preflight")
        assertTrue("A late preflight result must not reopen the restore dialog", compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
    }

    @Test fun verifyUpdatesRowWithoutReloadingPolicy() {
        val attempts = AtomicInteger()
        responseOverride = { request -> if (path(request).endsWith("/$name/verify")) {
            json(backup(name).put("verification", JSONObject().put("status", if (attempts.incrementAndGet() == 1) "failed" else "verified").put("error", if (attempts.get() == 1) "Fixture checksum mismatch" else JSONObject.NULL)))
        } else null }
        show()
        databaseRowDescription(name, "校验").performScrollTo().performClick()
        waitText("Fixture checksum mismatch")
        databaseText("校验失败", substring = true).assertIsDisplayed()
        val retry = databaseRowDescription(name, "校验")
        capture("backup-verify-before-retry")
        retry.assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { attempts.get() == 2 }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("已校验", substring = true) and hasAnyAncestor(hasTestTag("backup-database-$name"))).fetchSemanticsNodes().isNotEmpty() }
        capture("backup-verify-after-retry")
        compose.onNodeWithTag("backup-database-$name").assertIsDisplayed()
        waitText("备份校验完成：已校验。")
        databaseNode(hasText("已校验", substring = true) and hasAnyAncestor(hasTestTag("backup-database-$name"))).assertIsDisplayed()
        assertEquals(1, requests("GET", "/admin/system/backups/configuration").size)
        capture("backup-verified-row")
    }

    private fun show() { contentHost.setContent { HlovetTheme { NativeAdminBackupScreen(session, {}) } }; waitText("备份策略") }
    private fun node(card: String, matcher: SemanticsMatcher): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("backup-list").performScrollToNode(hasTestTag(card))
        return compose.onNode(matcher and hasAnyAncestor(hasTestTag(card)))
    }
    private fun policyNode(matcher: SemanticsMatcher) = node("backup-policy-card", matcher)
    private fun policyField(text: String) = policyNode(hasText(text) and hasSetTextAction())
    private fun policyButton(text: String) = policyNode(hasText(text) and hasClickAction())
    private fun providerNode(provider: String, matcher: SemanticsMatcher) = policyNode(hasAnyAncestor(hasTestTag("backup-provider-$provider")) and matcher)
    private fun providerField(provider: String, text: String) = policyNode(hasAnyAncestor(hasTestTag("backup-provider-$provider")) and hasText(text) and hasSetTextAction())
    private fun providerButton(provider: String, text: String) = policyNode(hasAnyAncestor(hasTestTag("backup-provider-$provider")) and hasText(text) and hasClickAction())
    private fun databaseNode(matcher: SemanticsMatcher) = node("backup-database-card", matcher)
    private fun databaseButton(text: String) = databaseNode(hasText(text) and hasClickAction())
    private fun databaseText(text: String, substring: Boolean = false) = databaseNode(hasText(text, substring = substring))
    private fun description(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))
    private fun databaseRowDescription(backupName: String, text: String) = databaseNode(description(text) and hasAnyAncestor(hasTestTag("backup-database-$backupName")))
    private fun catalogNode(matcher: SemanticsMatcher): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("backup-list").performScrollToNode(hasTestTag("backup-media-files-card"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("正在读取媒体目录…") and hasAnyAncestor(hasTestTag("backup-media-files-card"))).fetchSemanticsNodes().isEmpty() }
        return compose.onNode(matcher and hasAnyAncestor(hasTestTag("backup-media-files-card")))
    }
    private fun catalogText(text: String) = catalogNode(hasText(text))
    private fun catalogDescription(text: String) = catalogNode(description(text))
    private fun mediaButton(text: String) = node("backup-media-jobs-card", hasText(text) and hasClickAction())
    private fun dialogButton(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun waitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitDialogText(text: String) { compose.waitUntil(10_000) { compose.onAllNodes(hasText(text) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitNoDialog() { compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() } }
    private fun path(request: RecordedRequest) = request.requestUrl!!.encodedPath
    private fun requests(method: String, requestPath: String) = calls.filter { it.method == method && path(it) == requestPath }
    private fun lastBody(method: String, path: String) = JSONObject(requests(method, path).last().body.clone().readUtf8())
    private fun json(body: JSONObject, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun backup(name: String) = JSONObject().put("name", name).put("sizeBytes", 512).put("updatedAt", "2026-10-13T01:00:00Z").put("verification", JSONObject().put("status", "verified"))
    private fun file(id: Int) = JSONObject().put("id", id).put("storedName", "file-$id.png").put("category", if (id % 2 == 0) "backgrounds" else "articles")
        .put("sourceType", "article").put("sourceLabel", "Fixture article $id").put("sizeBytes", 1024).put("lastSeenAt", "2026-10-13T01:00:00Z").put("lastBackedUpAt", "2026-10-13T01:00:00Z")
    private fun job(status: String) = JSONObject().put("id", 9).put("status", status).put("trigger", "manual").put("providers", JSONArray().put("r2"))
        .put("totalFiles", 2).put("processedFiles", if (status == "completed") 2 else 0).put("uploadedFiles", if (status == "completed") 2 else 0).put("createdAt", "2026-10-13T01:00:00Z")

    private fun respond(request: RecordedRequest): MockResponse {
        val path = path(request)
        if (request.method == "GET") return when (path) {
            "/admin/system/backups/configuration" -> json(configuration)
            "/admin/system/status" -> json(JSONObject().put("backups", JSONObject().put("items", backups)))
            "/admin/system/media-backups/jobs" -> json(JSONObject().put("items", mediaJobs))
            "/admin/system/media-backups/files" -> {
                val url = request.requestUrl!!
                val category = url.queryParameter("category").orEmpty()
                val query = url.queryParameter("q").orEmpty()
                val page = url.queryParameter("page")!!.toInt()
                val matches = files.filter { (category.isEmpty() || it.getString("category") == category) && it.getString("storedName").contains(query) }
                json(JSONObject().put("items", JSONArray(matches.drop((page - 1) * 20).take(20))).put("page", page).put("pageSize", 20).put("total", matches.size).put("pageCount", (matches.size + 19) / 20))
            }
            "/admin/system/backups/$name/preflight" -> json(JSONObject().put("backup", backup(name)).put("canRestore", restoreAllowed).put("warnings", JSONArray()))
            else -> json(JSONObject().put("message", "Unexpected fixture GET"), 404)
        }
        return when (path) {
            "/admin/system/backups/configuration" -> {
                val body = JSONObject(request.body.clone().readUtf8())
                listOf("automaticEnabled", "scheduleTime", "localRetentionDays", "remoteRetentionDays").forEach { configuration.put(it, body.get(it)) }
                json(configuration)
            }
            "/admin/system/backups/providers/test" -> json(JSONObject().put("success", true))
            else -> json(JSONObject().put("message", "Unexpected fixture mutation"), 404)
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: instrumentation.targetContext.getExternalFilesDir(null)!!.absolutePath
        val directory = File(output, "backup-evidence").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
