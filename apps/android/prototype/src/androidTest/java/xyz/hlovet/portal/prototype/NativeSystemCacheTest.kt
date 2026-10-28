package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
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

/** 核对系统概况与缓存维护界面，所有写操作只允许精确匹配的设备本地测试服务。 */
@RunWith(AndroidJUnit4::class)
class NativeSystemCacheTest {
    // 设备内 MockWebServer 必须绕过模拟器的系统代理，避免回环请求被改写为外部错误。
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val contentHost = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var override: (RecordedRequest) -> MockResponse? = { null }
    @Volatile private var keys = (1..45).map { key("fixture:$it") }
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
        try { contentHost.dispose() }
        finally {
            try { server.shutdown() }
            finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) } }
        }
    }

    @Test fun systemOverviewDoesNotReadForRegularAdmin() {
        contentHost.setContent { HlovetTheme { NativeSystemStatusScreen(session.copy(isSuperAdmin = false), {}, {}, {}, {}) } }
        waitText("系统概况仅超级管理员可访问。")
        assertTrue(calls.isEmpty())
    }

    @Test fun cacheDoesNotReadForRegularAdmin() {
        contentHost.setContent { HlovetTheme { NativeAdminCacheScreen(session.copy(isSuperAdmin = false), {}) } }
        waitText("缓存数据仅超级管理员可查看和操作。")
        assertTrue(calls.isEmpty())
    }

    @Test fun systemShowsDependenciesReliabilitySeparateEventsAndRealCharts() {
        showSystem()
        systemText("8.4-fixture").performScrollTo().assertIsDisplayed()
        systemText("17").performScrollTo().assertIsDisplayed()
        systemText("7.4-fixture").performScrollTo().assertIsDisplayed()
        systemText("8 / 10").performScrollTo().assertIsDisplayed()
        systemText("备份失败").performScrollTo().assertIsDisplayed()
        systemText("GET /fixture-slow").performScrollTo().assertIsDisplayed()
        systemText("POST /fixture-error").performScrollTo().assertIsDisplayed()
        systemText("Fixture client failure").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("system-list").performScrollToNode(hasTestTag("system-memory-trend"))
        compose.onNodeWithTag("system-memory-trend").performScrollTo().assertIsDisplayed()
        capture("system-memory-chart")
        compose.onNodeWithTag("system-list").performScrollToNode(hasTestTag("system-disk-trend"))
        compose.onNodeWithTag("system-disk-trend").performScrollTo().assertIsDisplayed()
        capture("system-disk-chart")
        assertTrue(calls.all { it.method == "GET" })
    }

    /** 保持备份请求处理中以核对重复提交保护，失败后重试应成功且总共只提交两次。 */
    @Test fun systemBackupFailureStaysInConfirmationAndCanRetryWithoutDoubleSubmission() = withFailureEvidence("backup-retry") {
        // 首次响应由测试显式释放，保证检查按钮状态时请求仍在处理中。
        val attempts = AtomicInteger()
        val releaseBackup = CountDownLatch(1)
        override = { request -> if (path(request) == "/admin/system/backups" && attempts.incrementAndGet() == 1) {
            if (releaseBackup.await(30, TimeUnit.SECONDS)) error("Fixture backup failed")
            else error("Fixture backup response was not released")
        } else null }
        try {
            showSystem(); systemButton("立即备份").performScrollTo().performClick()
            dialogButton("确认生成").performClick()
            compose.waitUntil(10_000) { requests("POST", "/admin/system/backups").size == 1 }
            dialogButton("生成中…").assertIsNotEnabled().performClick()
            assertEquals(1, attempts.get())
        } finally {
            // 异常路径也释放响应，避免未完成请求影响服务关闭及下一条用例。
            releaseBackup.countDown()
        }
        // 失败保留确认弹窗，再核对真实重试结果和精确的提交次数。
        waitDialogText("Fixture backup failed")
        capture("system-backup-error")
        dialogButton("确认生成").performClick()
        waitNoDialog(); systemText("备份已生成：fixture-new.sql.gz").assertIsDisplayed()
        assertEquals(2, attempts.get())
    }

    @Test fun systemAcknowledgedBackupRemainsVisibleWhenRefreshFails() {
        val created = AtomicInteger()
        override = { request -> when {
            path(request) == "/admin/system/backups" -> { created.incrementAndGet(); json(backup()) }
            path(request) == "/admin/system/status" && created.get() > 0 -> error("Fixture refresh failed")
            else -> null
        } }
        showSystem(); systemButton("立即备份").performScrollTo().performClick(); dialogButton("确认生成").performClick()
        waitNoDialog(); systemText("Fixture refresh failed").performScrollTo().assertIsDisplayed()
        systemText("fixture-new.sql.gz · 2026-10-13 01:00:00").performScrollTo().assertIsDisplayed()
        systemText("1 个 · 600 B").performScrollTo().assertIsDisplayed()
        capture("system-backup-refresh-failure")
    }

    /** 发起备份后撤销超级管理员权限，确认迟到响应不会重新展示结果或弹窗。 */
    @Test fun systemPermissionLossDisposesDelayedActionAndDialog() {
        // 先发起受延迟控制的备份，按实际请求确认操作已开始。
        override = { request -> if (path(request) == "/admin/system/backups") json(backup()).setBodyDelay(1200, TimeUnit.MILLISECONDS) else null }
        val current = mutableStateOf(session)
        contentHost.setContent { HlovetTheme { NativeSystemStatusScreen(current.value, {}, {}, {}, {}) } }
        waitText("应用服务"); systemButton("立即备份").performScrollTo().performClick(); dialogButton("确认生成").performClick()
        compose.waitUntil(10_000) { requests("POST", "/admin/system/backups").size == 1 }
        // 撤销权限后检查页面及弹窗状态，响应完成也不能恢复敏感操作。
        compose.runOnIdle { current.value = session.copy(isSuperAdmin = false) }
        waitText("系统概况仅超级管理员可访问。"); Thread.sleep(1500); compose.waitForIdle()
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("备份已生成：fixture-new.sql.gz").fetchSemanticsNodes().isEmpty())
    }

    /** 让概况响应晚于缓存列表，验证概况失败不阻断列表，重试也不重新查询列表。 */
    @Test fun overviewFailureDoesNotBlockKeysAndCanRetryIndependently() {
        // 保持首次概况请求未完成，先确认独立的缓存列表已经可见。
        val reads = AtomicInteger()
        val releaseOverview = CountDownLatch(1)
        override = { request -> if (path(request) == "/admin/cache/overview" && reads.incrementAndGet() == 1) {
            if (releaseOverview.await(30, TimeUnit.SECONDS)) error("Fixture Redis overview failed")
            else error("Fixture overview response was not released")
        } else null }
        try {
            showCache()
            cacheText("fixture:1").assertIsDisplayed()
        } finally {
            // 即使列表断言失败，也释放服务端响应，保证测试服务能够正常关闭。
            releaseOverview.countDown()
        }
        // 等待概况的失败状态真正进入界面，再确认其独立重试入口。
        cacheText("Fixture Redis overview failed").assertIsDisplayed()
        cacheButton("重试概览").assertIsDisplayed().assertIsEnabled().performClick()
        waitText("Redis 运行概览")
        // 重试只增加概况读取次数，缓存列表保持原来的一次查询。
        assertEquals(1, requests("GET", "/admin/cache/keys").size)
        assertEquals(2, reads.get())
    }

    /** 修改缓存关键词后等待防抖查询，核对列表筛选、选择清理及概况请求数量。 */
    @Test fun filtersOnlyReloadKeysAndSelectionClearsForChangedQuery() {
        // 先建立批量选择，再触发关键词防抖，使用异步请求统一的等待预算。
        showCache(); selectAll()
        compose.onNodeWithTag("cache-search").performScrollTo().performTextReplacement("fixture:2")
        compose.waitUntil(10_000) { requests("GET", "/admin/cache/keys").any { it.requestUrl!!.queryParameter("search") == "fixture:2" } }
        // 确认新查询的列表已经显示，且筛选不会读取概况或保留旧选择。
        cacheText("fixture:2").assertIsDisplayed()
        assertEquals(1, requests("GET", "/admin/cache/overview").size)
        assertTrue(compose.onAllNodesWithText("已选 10 项").fetchSemanticsNodes().isEmpty())
    }

    @Test fun deleteSelectAllProcessesMoreThanTwentyKeysWithoutSilentTruncation() {
        showCache(); batch50(); selectAll(); cacheButton("删除/清理").performScrollTo().performClick()
        dialogButton("确认清理").performClick(); waitNoDialog()
        waitText("已删除 45 个缓存键；撤销会话 0 个；清除失败计数 0 个。")
        val batches = requests("POST", "/admin/cache/delete").map { requestKeys(it) }
        assertEquals(listOf(20, 20, 5), batches.map { it.size })
        assertEquals(45, batches.flatten().distinct().size)
        assertTrue(keys.isEmpty())
        capture("cache-delete-45-completed")
    }

    /** 第二批删除失败时保留剩余项，重试只处理未确认完成的批次，并保留失败现场。 */
    @Test fun partialDeleteFailureRetainsRemainingKeysAndRetryDoesNotRepeatCompletedBatch() = withFailureEvidence("partial-delete") {
        // 通过实际请求记录核对首批完成和次批失败，区分未提交与反馈未展示。
        val attempts = AtomicInteger()
        override = { request -> if (path(request) == "/admin/cache/delete" && attempts.incrementAndGet() == 2) error("Fixture second batch failed") else null }
        showCache(); batch50(); selectAll(); cacheButton("删除/清理").performScrollTo().performClick()
        dialogButton("确认清理").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { requests("POST", "/admin/cache/delete").size >= 2 }
        waitDialogText("已完成 20 项，其余未确认完成：Fixture second batch failed")
        capture("cache-partial-delete-error")
        // 重试必须复用剩余项，不能再次删除首批已经完成的缓存键。
        dialogButton("确认清理").performClick(); waitNoDialog()
        cacheText("已删除 45 个缓存键；撤销会话 0 个；清除失败计数 0 个。").assertIsDisplayed()
        val batches = requests("POST", "/admin/cache/delete").map { requestKeys(it) }
        assertEquals(listOf(20, 20, 20, 5), batches.map { it.size })
        assertEquals(batches[1], batches[2])
        assertTrue(batches[0].none { it in batches[2] || it in batches[3] })
        assertTrue(keys.isEmpty())
    }

    @Test fun ttlKeepsInvalidPasteAndReportsValidationInDialog() {
        showCache(); selectAll(); cacheButton("修改 TTL").performScrollTo().performClick()
        listOf("-60", "60x", "31536001", "99999999999999999999", "").forEach { raw ->
            compose.onNodeWithTag("cache-ttl-input").performTextReplacement(raw)
            dialogButton("保存").performClick()
            waitDialogText("TTL 必须是 60 到 31536000 之间的整数秒。")
            compose.onNodeWithTag("cache-ttl-input").assertTextContains(raw)
        }
        assertTrue(calls.none { it.method == "PATCH" })
    }

    @Test fun partialTtlFailureRetainsInputAndRetriesOnlyRemainingKeys() {
        val attempts = AtomicInteger()
        override = { request -> if (path(request) == "/admin/cache/ttl/bulk" && attempts.incrementAndGet() == 2) error("Fixture TTL failed") else null }
        showCache(); batch50(); selectAll(); cacheButton("修改 TTL").performScrollTo().performClick()
        compose.onNodeWithTag("cache-ttl-input").performTextReplacement("7200")
        dialogButton("保存").performClick(); waitDialogText("已完成 20 项，其余未确认完成：Fixture TTL failed")
        compose.onNodeWithTag("cache-ttl-input").assertTextContains("7200")
        waitDialogText("待处理 25 项"); dialogButton("保存").performClick(); waitNoDialog()
        cacheText("已更新 45 个业务缓存的 TTL。").assertIsDisplayed()
        val batches = requests("PATCH", "/admin/cache/ttl/bulk").map { requestKeys(it) }
        assertEquals(listOf(20, 20, 20, 5), batches.map { it.size })
        assertTrue(keys.all { it.getLong("ttlSeconds") == 7200L })
        assertTrue(batches[0].none { it in batches[2] || it in batches[3] })
    }

    @Test fun cacheDetailScrollKeepsCloseVisibleAndTtlFailureRetainsDraft() {
        val attempts = AtomicInteger()
        override = { request -> when {
            path(request) == "/admin/cache/inspect" -> json(key("fixture:1").put("value", (1..90).joinToString("\n") { "Fixture long value $it" }).put("length", 90).put("format", "text"))
            path(request) == "/admin/cache/ttl" && attempts.incrementAndGet() == 1 -> error("Fixture single TTL failed")
            else -> null
        } }
        showCache(); openFirstDetail()
        compose.onNodeWithTag("cache-detail-ttl-input").performScrollTo().performTextReplacement("3601")
        compose.onNodeWithTag("cache-detail-ttl-input").performImeAction()
        dialogButton("保存 TTL").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("cache-ttl-before-save")
        dialogButton("保存 TTL").performClick()
        compose.waitUntil(10_000) { requests("PATCH", "/admin/cache/ttl").size == 1 }
        waitDialogText("Fixture single TTL failed")
        capture("cache-ttl-error-feedback")
        dialogText("Fixture single TTL failed").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭详情").assertIsDisplayed()
        compose.onNodeWithTag("cache-detail-ttl-input").performScrollTo().assertTextContains("3601")
        dialogButton("保存 TTL").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick(); waitDialogText("已更新 1 个业务缓存的 TTL。")
        dialogText("已更新 1 个业务缓存的 TTL。").assertIsDisplayed()
        assertEquals(2, requests("PATCH", "/admin/cache/ttl").size)
        assertEquals(3601L, keys.first().getLong("ttlSeconds"))
        compose.onNodeWithContentDescription("关闭详情").assertIsDisplayed()
        capture("cache-long-detail-fixed-close")
    }

    @Test fun authenticationKeysHaveNoTtlEditorAndDeleteConfirmationIsNotCoveredByDetail() {
        keys = listOf(key("refresh_token:fixture", false).put("category", "refresh-session"))
        showCache(); openFirstDetail()
        waitDialogText("认证缓存 TTL 由登录策略统一管理。")
        assertTrue(compose.onAllNodesWithTag("cache-detail-ttl-input").fetchSemanticsNodes().isEmpty())
        dialogButton("撤销此会话").performScrollTo().performClick()
        dialogButton("确认清理").assertIsDisplayed()
        dialogButton("取消").performClick()
        compose.onNodeWithContentDescription("关闭详情").assertIsDisplayed()
        assertTrue(calls.none { it.method == "PATCH" })
    }

    /** 缓存详情查询期间撤销权限，确认迟到结果不能打开详情或展示敏感值。 */
    @Test fun cachePermissionLossCannotReopenDelayedDetail() {
        // 按详情请求确认操作已开始，避免把设备调度延迟误判为请求未发起。
        override = { request -> if (path(request) == "/admin/cache/inspect") json(key("fixture:1").put("value", "Fixture secret value")).setBodyDelay(1200, TimeUnit.MILLISECONDS) else null }
        val current = mutableStateOf(session)
        contentHost.setContent { HlovetTheme { NativeAdminCacheScreen(current.value, {}) } }; waitText("缓存键")
        cacheButton("查看").performScrollTo().performClick()
        compose.waitUntil(10_000) { requests("POST", "/admin/cache/inspect").size == 1 }
        // 权限撤销后等待原响应完成，检查详情与内容仍不可见。
        compose.runOnIdle { current.value = session.copy(isSuperAdmin = false) }
        waitText("缓存数据仅超级管理员可查看和操作。"); Thread.sleep(1500); compose.waitForIdle()
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Fixture secret value").fetchSemanticsNodes().isEmpty())
    }

    /** 首批删除发起后撤销权限，验证余下批次停止且确认弹窗关闭。 */
    @Test fun permissionLossStopsRemainingDeleteBatchesAfterFirstRequest() {
        // 先加载真实测试列表并发起批量删除，按请求记录确认首批已经到达服务。
        override = { request -> if (path(request) == "/admin/cache/delete") json(JSONObject().put("deletedKeys", 20)).setBodyDelay(1500, TimeUnit.MILLISECONDS) else null }
        val current = mutableStateOf(session)
        contentHost.setContent { HlovetTheme { NativeAdminCacheScreen(current.value, {}) } }
        compose.waitUntil(10_000) { requests("GET", "/admin/cache/keys").isNotEmpty() }
        batch50(); selectAll(); cacheButton("删除/清理").performScrollTo().performClick(); dialogButton("确认清理").performClick()
        compose.waitUntil(10_000) { requests("POST", "/admin/cache/delete").isNotEmpty() }
        // 撤销权限后不允许继续后续批次，保留精确的一次删除请求断言。
        compose.runOnIdle { current.value = session.copy(isSuperAdmin = false) }
        waitText("缓存数据仅超级管理员可查看和操作。"); Thread.sleep(1800); compose.waitForIdle()
        assertEquals(1, requests("POST", "/admin/cache/delete").size)
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
    }

    @Test fun englishSystemDisplaysDependencyAndMonitoringLabelsWithoutChineseFallback() {
        NativeI18n.applyLocale("en-US")
        contentHost.setContent { HlovetTheme { NativeSystemStatusScreen(session, {}, {}, {}, {}) } }
        waitText("Application service")
        systemText("Migrations").performScrollTo().assertIsDisplayed()
        systemText("Covered files").performScrollTo().assertIsDisplayed()
        systemText("Slow requests").performScrollTo().assertIsDisplayed()
        systemText("Client errors").performScrollTo().assertIsDisplayed()
        systemText("API RSS memory").performScrollTo().assertIsDisplayed()
        systemText("Warning threshold 85%").performScrollTo().assertIsDisplayed()
        capture("system-english-resource-trends")
    }

    @Test fun emptySystemTrendsShowWaitingInsteadOfDrawingMadeUpSamples() {
        override = { request -> if (path(request) == "/admin/system/status") json(status().put("monitoring", JSONObject())) else null }
        showSystem()
        compose.onNodeWithTag("system-list").performScrollToNode(hasText("API RSS 内存"))
        compose.onAllNodesWithText("等待首次采样")[0].assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("system-memory-trend").fetchSemanticsNodes().isEmpty())
    }

    @Test fun englishSystemAndCacheErrorFallbacksStayInSelectedLanguage() {
        NativeI18n.applyLocale("en-US")
        override = { request -> if (path(request) == "/admin/cache/overview") json(JSONObject()).setResponseCode(503) else null }
        contentHost.setContent { HlovetTheme { NativeAdminCacheScreen(session, {}) } }
        waitText("Cache request failed (HTTP 503).")
        compose.onNodeWithTag("cache-list").performScrollToNode(hasText("fixture:1"))
        compose.onNodeWithText("fixture:1").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Redis 运行概览").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Hit rate").fetchSemanticsNodes().isEmpty())
    }

    private fun showSystem() { contentHost.setContent { HlovetTheme { NativeSystemStatusScreen(session, {}, {}, {}, {}) } }; waitText("应用服务") }
    /** 挂载缓存页，等列表请求和首个缓存键均就绪；概况请求仍可独立进行。 */
    private fun showCache() {
        // 请求到达不代表界面已完成重组，因此继续核对列表内容的可见状态。
        contentHost.setContent { HlovetTheme { NativeAdminCacheScreen(session, {}) } }
        compose.waitUntil(10_000) { requests("GET", "/admin/cache/keys").isNotEmpty() }
        cacheText(keys.first().getString("key")).assertIsDisplayed()
    }
    private fun systemText(text: String): SemanticsNodeInteraction { compose.onNodeWithTag("system-list").performScrollToNode(hasText(text)); return compose.onAllNodesWithText(text)[0] }
    private fun systemButton(text: String) = systemText(text)
    /** 滚动并返回匹配 text 的非编辑展示文本；未加载完成时在统一预算内等待。 */
    private fun cacheText(text: String): SemanticsNodeInteraction {
        // 搜索词也出现在输入框中，必须排除可编辑节点才能核对真实结果行。
        val matcher = hasText(text) and !hasSetTextAction()
        awaitCacheNode(matcher)
        return compose.onNode(matcher)
    }
    /** 滚动并返回第一个匹配 text 且可点击的缓存按钮，不改变后续业务断言。 */
    private fun cacheButton(text: String): SemanticsNodeInteraction {
        // 按文本和点击语义共同匹配，避免把相同文案的普通文本当作按钮。
        val matcher = hasText(text) and hasClickAction()
        awaitCacheNode(matcher)
        return compose.onAllNodes(matcher)[0]
    }
    /** 等待 matcher 对应的懒加载节点并滚动至该节点，超时仍使测试失败。 */
    private fun awaitCacheNode(matcher: SemanticsMatcher) {
        // 列表和概况独立响应；只重试尚未进入组合树的查找，不吞后续断言失败。
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag("cache-list").performScrollToNode(matcher)
                compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }
    private fun selectAll() {
        compose.onNodeWithTag("cache-list").performScrollToNode(hasTestTag("cache-select-all"))
        compose.onNodeWithTag("cache-select-all").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitText("已选 ${minOf(keys.size, if (requests("GET", "/admin/cache/keys").last().requestUrl!!.queryParameter("count") == "50") 50 else 10)} 项")
    }
    /** 把缓存页批次切换为 50，并等待请求参数和返回的 45 项测试数据一致。 */
    private fun batch50() {
        // 通过真实下拉控件改变批次，再确认新请求和对应列表已经展示。
        compose.onNodeWithTag("cache-menu-数量").performScrollTo().performClick()
        compose.onNodeWithText("50").performClick()
        compose.waitUntil(10_000) { requests("GET", "/admin/cache/keys").any { it.requestUrl!!.queryParameter("count") == "50" } }
        cacheText("45 项").assertIsDisplayed()
    }
    private fun openFirstDetail() { cacheButton("查看").performScrollTo().performClick(); waitDialogText("缓存键详情") }
    private fun dialogButton(label: String) = compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun waitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun dialogText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
    private fun waitDialogText(text: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(text) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            runCatching {
                capture("cache-dialog-timeout")
                val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)).absolutePath)
                directory.mkdirs()
                File(directory, "cache-dialog-timeout.txt").writeText("Waiting for: $text\n" +
                    compose.onAllNodes(isRoot()).printToString(maxDepth = 30) + "\nRequests:\n" +
                    calls.joinToString("\n") { "${it.method} ${path(it)}" })
            }
            throw error
        }
    }
    private fun waitNoDialog() { compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() } }
    private fun path(request: RecordedRequest) = request.requestUrl!!.encodedPath
    private fun requests(method: String, target: String) = calls.filter { it.method == method && path(it) == target }
    private fun requestKeys(request: RecordedRequest): List<String> { val array = JSONObject(request.body.clone().readUtf8()).getJSONArray("keys"); return (0 until array.length()).map { array.getString(it) } }
    private fun key(name: String, editable: Boolean = true) = JSONObject().put("key", name).put("type", "string").put("category", "business-cache").put("ttlSeconds", 3600).put("memoryBytes", 512).put("canUpdateTtl", editable)
    private fun backup() = JSONObject().put("name", "fixture-new.sql.gz").put("sizeBytes", 200).put("mediaSnapshotAvailable", true).put("mediaSnapshotSizeBytes", 400).put("updatedAt", "2026-10-13T01:00:00Z").put("verification", JSONObject().put("status", "verified"))
    private fun status() = JSONObject("""{
      "generatedAt":"2026-10-13T01:00:00Z","application":{"service":"fixture-api","nodeVersion":"v22","environment":"fixture","uptimeSeconds":300,"memory":{"rssBytes":1024,"heapUsedBytes":512,"heapTotalBytes":2048}},
      "database":{"connected":true,"latencyMs":2,"version":"8.4-fixture","sizeBytes":4096,"migrationCount":17,"latestMigration":{"name":"fixture-migration","finishedAt":"2026-10-13T01:00:00Z"}},
      "redis":{"connected":true,"latencyMs":1,"version":"7.4-fixture","keyCount":45,"usedMemoryBytes":2048,"maxMemoryBytes":0,"connectedClients":3},
      "storage":{"totalBytes":512,"totalFiles":1,"items":[]},"backups":{"available":true,"totalBytes":0,"fileCount":0,"items":[]},
      "reliability":{"backupCoverage":{"backedUpFiles":8,"totalFiles":10,"uncoveredFiles":2,"percentage":80},"lastSuccessfulBackupSource":"media","anomalies":{"backupFailures":1,"diskPressure":2,"missingFiles":3,"orphanFiles":4,"metadataMismatches":5,"recentApiErrors":6,"total":21},"storage":{"diskUsedPercent":42,"warningThresholdPercent":85}},
      "monitoring":{"retentionMinutes":60,"slowRequestThresholdMs":1000,"slowRequests":[{"method":"GET","path":"/fixture-slow","durationMs":1200,"statusCode":200}],"recentErrors":[{"method":"POST","path":"/fixture-error","statusCode":503,"message":"Fixture API failure"}],"recentClientErrors":[{"source":"android","path":"/fixture-screen","message":"Fixture client failure"}],"memoryTrend":[{"recordedAt":"2026-10-13T00:58:00Z","rssBytes":1024},{"recordedAt":"2026-10-13T00:59:00Z","rssBytes":4096},{"recordedAt":"2026-10-13T01:00:00Z","rssBytes":2048}],"diskTrend":[{"recordedAt":"2026-10-13T00:58:00Z","usedPercent":39},{"recordedAt":"2026-10-13T00:59:00Z","usedPercent":42}]},
      "containerRuntime":{"connected":false,"message":"Fixture container unavailable"}
    }""")
    private fun respond(request: RecordedRequest): MockResponse = when (path(request)) {
        "/admin/system/status" -> json(status())
        "/admin/system/backups" -> json(backup())
        "/admin/cache/overview" -> json(JSONObject().put("redisVersion", "7.4").put("keyCount", keys.size).put("usedMemoryBytes", 4096).put("maxMemoryBytes", 0).put("connectedClients", 3).put("hitRate", 0.75))
        "/admin/cache/keys" -> {
            val search = request.requestUrl!!.queryParameter("search").orEmpty()
            val count = request.requestUrl!!.queryParameter("count")?.toInt() ?: 10
            json(JSONObject().put("cursor", "0").put("nextCursor", "0").put("done", true).put("keys", JSONArray(keys.filter { it.getString("key").contains(search) }.take(count))))
        }
        "/admin/cache/inspect" -> json(keys.first().put("value", "Fixture redacted value").put("format", "text").put("length", 1))
        "/admin/cache/delete" -> { val selected = requestKeys(request); keys = keys.filterNot { it.getString("key") in selected }; json(JSONObject().put("deletedKeys", selected.size).put("revokedSessions", 0).put("clearedLoginFailures", 0)) }
        "/admin/cache/ttl/bulk" -> { val selected = requestKeys(request); val ttl = JSONObject(request.body.clone().readUtf8()).getInt("ttlSeconds"); keys = keys.map { if (it.getString("key") in selected) JSONObject(it.toString()).put("ttlSeconds", ttl) else it }; array(JSONArray(keys.filter { it.getString("key") in selected })) }
        "/admin/cache/ttl" -> { val body = JSONObject(request.body.clone().readUtf8()); val updated = key(body.getString("key")).put("ttlSeconds", body.getInt("ttlSeconds")); keys = keys.map { if (it.getString("key") == updated.getString("key")) updated else it }; json(updated) }
        else -> error("Unexpected fixture request")
    }
    private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun array(value: JSONArray) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun error(message: String) = json(JSONObject().put("message", message)).setResponseCode(503)
    /** 执行 block，失败时按 name 保存本地测试界面与请求路径；诊断异常不覆盖原始失败。 */
    private fun withFailureEvidence(name: String, block: () -> Unit) {
        // 在测试页面销毁前收集现场，只记录测试请求路径和缓存键数量，不保存认证头。
        try {
            block()
        } catch (failure: Throwable) {
            runCatching {
                val requests = calls.joinToString("\n") { request ->
                    "${request.method} ${request.path}" +
                        if (path(request) == "/admin/cache/delete") " keys=${requestKeys(request).size}" else ""
                }
                val tree = compose.onAllNodes(isRoot()).printToString(maxDepth = 20)
                File(evidenceDirectory(), "$name-failure.txt").writeText("${failure.javaClass.simpleName}\n$requests\n\n$tree")
                capture("$name-failure")
            }
            throw failure
        }
    }
    /** 返回仪器测试的截图目录，不创建站点数据，也不写入凭据。 */
    private fun evidenceDirectory(): File {
        // 优先复用 Gradle 提供的归档目录，直接 ADB 运行时使用应用外部测试目录。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: instrumentation.targetContext.getExternalFilesDir(null)!!.absolutePath
        return File(output, "system-cache-evidence").apply { mkdirs() }
    }
    /** 按 name 保存当前本地测试界面，写入后释放截图位图。 */
    private fun capture(name: String) {
        // 截图仅作交互证据，位图无论写入成功或失败都及时释放。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = evidenceDirectory()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
}
