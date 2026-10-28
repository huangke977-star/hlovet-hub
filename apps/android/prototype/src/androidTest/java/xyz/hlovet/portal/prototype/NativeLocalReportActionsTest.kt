package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 在真实 MainActivity 与本地 API 验证举报处理及后台媒体；短期管理员授权不代表交互登录。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalReportActionsTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    /** 测试只读取指定应用私有证据和专用临时授权文件。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var reader: NativeSession? = null
    private lateinit var admin: NativeSession
    private lateinit var fixture: JSONObject
    private var scenario: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("adminInteractiveLoginTested", false).put("productionTested", false)
    private val targets = JSONArray()

    /** 只在精确本地来源消费临时授权，并校验既有读者和管理员真实身份。 */
    @Before fun requireLocal() {
        // 在读取任何凭据前拒绝生产和隔离来源。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        reader = NativeAuthApi.refresh(stored.refreshToken)
        check(NativeSessionStore.save(context, requireNotNull(reader)))
        fixture = JSONObject(File(context.filesDir, "native-local-report-actions-fixture.json").readText())
        check(fixture.getString("marker").startsWith("native_report_actions_20261015_"))
        val credential = File(context.filesDir, "native-local-report-actions-admin.json")
        val token = try { JSONObject(credential.readText()).getString("accessToken") } finally { credential.delete() }
        val me = JSONObject(http("/auth/me", token, 200).toString(Charsets.UTF_8))
        check(me.getInt("id") == 7 && me.getString("username") == "android_parity_admin" && me.getBoolean("isSuperAdmin"))
        admin = NativeSession(token, "", 7, me.getString("username"), me.getString("nickname"),
            PreviewApi.appearanceFromJson(me.optJSONObject("appearance") ?: JSONObject()), isSuperAdmin = true)
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 保存全部已完成检查与最后画面，关闭实际页面并恢复原读者会话和语言。 */
    @After fun restore() {
        // 管理操作不恢复已处理举报，失败证据也不能被收尾清除。
        try {
            if (oldLocale != null) {
                screenshot("last-state")
                File(context.filesDir, "native-local-report-actions-report.json").writeText(report.put("targets", targets).put("checks", checks).toString(2))
            }
        } finally {
            scenario?.close(); scenario = null
            reader?.let { check(NativeSessionStore.save(context, it)) }
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 提交三类举报，再通过真实页面单条处理和批量处理，最后打开既有群举报媒体。 */
    @Test fun realReportActionsRefreshAndRetainDecisions() {
        // 普通读者和匿名不能访问后台队列，管理员使用现有角色真实授权。
        val owner = requireNotNull(reader)
        http("/moderation/reports", null, 401)
        http("/moderation/reports", owner.accessToken, 403)
        report.put("marker", fixture.getString("marker")).put("authorId", fixture.getJSONObject("author").getInt("id"))
            .put("groupId", fixture.getJSONObject("group").getInt("id"))
        record("real-anonymous-401-and-ordinary-reader-403")
        for ((source, field) in listOf("article" to "articles", "comment" to "comments", "group_message" to "messages")) {
            val array = fixture.getJSONArray(field)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                when (source) {
                    "article" -> NativeArticleApi.reportArticle(owner.accessToken, item.getInt("id"), "other", fixture.getString("marker"))
                    "comment" -> NativeArticleApi.reportComment(owner.accessToken, item.getInt("id"), "other", fixture.getString("marker"))
                    else -> NativeSocialApi.reportGroupMessage(owner.accessToken, fixture.getJSONObject("group").getInt("id"), item.getInt("id"), "other", fixture.getString("marker"))
                }
                val matched = NativeReportsApi.listAdmin(admin.accessToken, pageSize = 50, status = "all", source = source).items.single { candidate ->
                    when (source) { "article" -> candidate.article?.id; "comment" -> candidate.comment?.id; else -> candidate.message?.id } == item.getInt("id")
                }
                targets.put(JSONObject().put("source", source).put("mode", item.getString("mode")).put("contentId", item.getInt("id"))
                    .put("reportId", matched.id).put("key", matched.key))
            }
        }
        val before = NativeReportsApi.summary(admin.accessToken)
        report.put("pendingBefore", before.pending).put("totalBefore", before.total)
        check(NativeSessionStore.save(context, admin))
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + "/admin/reports")))
        waitText("举报队列")

        // 单条操作覆盖屏蔽、删除、驳回和保留；内容状态由后续只读数据库另行核验。
        for (index in 0 until targets.length()) {
            val target = targets.getJSONObject(index)
            val mode = target.getString("mode")
            if (mode.startsWith("bulk-")) continue
            val rejected = mode == "reject"
            openAction(target.getString("key"), if (rejected) "驳回" else "处理")
            if (!rejected) {
                val option = when (mode) { "block" -> "屏蔽内容"; "delete" -> if (target.getString("source") == "group_message") "删除消息" else "删除内容"; else -> "保留内容" }
                compose.onNodeWithText(option).performClick()
            }
            val feedback = "${fixture.getString("marker")} ${target.getString("source")} $mode"
            compose.onNodeWithTag("admin-report-resolution").performScrollTo().performTextReplacement(feedback)
            if (index == 0) screenshot("single-action")
            compose.onNodeWithText("确认").performClick(); waitClosed()
            val current = NativeReportsApi.listAdmin(admin.accessToken, pageSize = 50, status = "all", source = target.getString("source")).items.firstOrNull { it.key == target.getString("key") }
            if (target.getString("source") == "group_message" && mode == "delete") {
                // 现有 PC/API 的物理删除会级联删除举报，留存由群活动日志核验，不伪称已处理列表仍有该行。
                assertNull(current)
                target.put("expectedReportRemovedWithMessage", true)
            } else {
                assertEquals(if (rejected) "rejected" else "resolved", requireNotNull(current).status)
                assertEquals(feedback, current.resolution)
            }
            target.put("verified", true)
            record("real-single-${target.getString("source")}-$mode")
        }

        // 三类批量操作均保留原内容，分别使用本来源的两个待处理编号。
        for (source in listOf("article", "comment", "group_message")) {
            val batch = (0 until targets.length()).map { targets.getJSONObject(it) }.filter { it.getString("source") == source && it.getString("mode").startsWith("bulk-") }
            for (target in batch) {
                scrollRow(target.getString("key"))
                compose.onNodeWithTag("admin-report-select-${target.getString("key")}").performScrollTo().performClick()
            }
            compose.onNodeWithTag("admin-reports-list").performScrollToNode(hasText("批量处理") and hasClickAction())
            compose.onNodeWithText("批量处理").performClick()
            val feedback = "${fixture.getString("marker")} bulk $source keep"
            compose.onNodeWithTag("admin-report-resolution").performScrollTo().performTextReplacement(feedback)
            if (source == "comment") screenshot("bulk-action")
            compose.onNodeWithText("确认").performClick(); waitClosed()
            val current = NativeReportsApi.listAdmin(admin.accessToken, pageSize = 50, status = "resolved", source = source).items
            for (target in batch) {
                val saved = current.single { it.key == target.getString("key") }
                assertEquals(feedback, saved.resolution); target.put("verified", true)
            }
            record("real-bulk-$source-keeps-content-and-feedback")
        }
        val after = NativeReportsApi.summary(admin.accessToken)
        assertEquals(before.pending - targets.length(), after.pending)
        assertEquals(before.total - 1, after.total)
        report.put("pendingAfter", after.pending).put("totalAfter", after.total)
        // 同时核对实际页面的最新汇总，不能只把后台查询成功当成界面已刷新。
        val pendingMetric = hasText(after.pending.toString()) and hasAnyAncestor(hasTestTag("admin-report-metric-待处理"))
        compose.waitUntil(30000) {
            runCatching { compose.onNodeWithTag("admin-reports-list").performScrollToNode(pendingMetric); true }.getOrDefault(false)
        }
        compose.onNode(pendingMetric).assertIsDisplayed()
        screenshot("updated-summary")
        record("all-fifteen-decisions-removed-from-pending-with-correct-summary")

        // 独立后台举报中心读取前轮保留的群消息，不改变其待处理状态。
        val mediaId = requireNotNull(InstrumentationRegistry.getArguments().getString("mediaReportId")).toInt()
        val media = NativeReportsApi.listAdmin(admin.accessToken, pageSize = 50, source = "group_message").items.single { it.id == mediaId }
        assertTrue(media.message?.attachments?.size == 4)
        scrollRow(media.key); compose.onNodeWithTag("admin-report-${media.key}").performClick()
        waitTag("report-message-${requireNotNull(media.message).id}")
        val attachments = media.message.attachments
        compose.onNodeWithTag("report-attachment-${attachments[0].id}").performScrollTo()
        compose.onNodeWithContentDescription(attachments[0].originalName).performClick()
        waitDescription("关闭图片预览")
        compose.waitUntil(30000) { compose.onAllNodesWithText("正在读取图片…").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        compose.onNodeWithText("图片加载失败，请检查网络后重试。").assertDoesNotExist()
        screenshot("admin-image"); compose.onNodeWithContentDescription("关闭图片预览").performClick()
        for (attachment in attachments.filter { it.kind == "audio" || it.kind == "video" }) play(attachment)
        val file = attachments.single { it.kind == "file" }
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("report-attachment-${file.id}"))).performScrollTo().performClick()
        waitText("另存为"); compose.onNodeWithText("另存为").assertIsDisplayed()
        val cached = NativeOfflineCache.loadCachedMediaFile(context, file.downloadUrl, NativeOfflineOwner.forSession(admin))
        assertArrayEquals(http(file.downloadUrl, admin.accessToken, 200), requireNotNull(cached).readBytes())
        screenshot("admin-file")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        report.put("mediaReportId", mediaId).put("mediaReportStatusUnchanged", true).put("systemSaveFromThisPageTested", false)
        record("real-general-admin-report-original-image-audio-video-and-file-cache-bytes")
    }

    /** 真实播放 attachment，视频除控件外还核对解码后的红色画面。 */
    private fun play(attachment: NativeReportAttachment) {
        // 限定附件编号，避免音视频播放控件之间串选。
        val parent = hasAnyAncestor(hasTestTag("native-inline-media-${attachment.id}"))
        compose.onNode(hasContentDescription("播放媒体") and parent, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        if (attachment.kind == "video") compose.waitUntil(15000) {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitUntil false
            var red = 0
            for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) < 90 && android.graphics.Color.blue(color) < 90) red++
            }
            bitmap.recycle(); red > 100
        }
        screenshot("admin-${attachment.kind}")
        compose.onNode(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).performClick()
    }

    /** 为 key 对应记录点击 label，保证动作来自实际举报卡片。 */
    private fun openAction(key: String, label: String) {
        // 懒加载定位后再点击该卡片内的处理按钮。
        scrollRow(key)
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("admin-report-$key"))).performScrollTo().performClick()
        waitTag("admin-report-resolution")
    }

    /** 查找并滚动到 key 对应当前页记录，允许刷新短暂未完成。 */
    private fun scrollRow(key: String) {
        // 不假定屏幕外记录已经挂载，使用列表自身定位业务键。
        compose.waitUntil(30000) { runCatching { compose.onNodeWithTag("admin-reports-list").performScrollToNode(hasTestTag("admin-report-$key")); true }.getOrDefault(false) }
    }

    /** 等待处理弹窗真正关闭，同时拒绝隐藏业务错误。 */
    private fun waitClosed() {
        // 仅成功请求结束后的状态迁移能关闭表单。
        compose.waitUntil(30000) { compose.onAllNodesWithTag("admin-report-resolution").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
    }

    /** 等待 text 出现在实际 MainActivity 页面。 */
    private fun waitText(text: String) {
        // 首次加载允许短暂没有 Compose 语义根。
        compose.waitUntil(60000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待 tag 对应实际业务组件出现。 */
    private fun waitTag(tag: String) {
        // 不用网络成功替代视图挂载。
        compose.waitUntil(30000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待 description 对应原生图片窗口控件出现。 */
    private fun waitDescription(description: String) {
        // 控件必须由真实用户操作触发。
        compose.waitUntil(30000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 请求固定本地 path，验证 expected 状态并返回成功字节；token 不进入日志。 */
    private fun http(path: String, token: String?, expected: Int): ByteArray {
        // 拒绝跨源和重定向，授权不发给未知地址。
        check(path.startsWith("/") && !path.startsWith("//"))
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 15000
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            assertEquals(expected, connection.responseCode)
            if (expected == 200) connection.inputStream.use { it.readBytes() } else byteArrayOf()
        } finally { connection.disconnect() }
    }

    /** 将 name 对应当前业务画面保存为专用 PNG，不捕获凭据输入。 */
    private fun screenshot(name: String) {
        // 等待实际窗口稳定后再保存，视频另外已核对像素。
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1500)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(context.filesDir, "native-local-report-actions-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** 保存 name 对应已完成检查点，后续失败不抹掉已有证据。 */
    private fun record(name: String) {
        // 记录业务结论，不输出会话或认证文件。
        checks.put(JSONObject().put("name", name).put("passed", true))
    }
}
