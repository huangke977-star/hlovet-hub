package xyz.hlovet.portal.prototype

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实已登录 MainActivity 中验证评论和 Socket 群聊音视频，业务数据与可核对证据全部保留。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalInlineMediaTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** 使用当前业务 APK 的 Context 保存证据，不访问测试 APK 外的任意账号目录。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var socket: NativeChatSocket? = null
    private lateinit var session: NativeSession
    private var oldLocale: String? = null
    private var scenario = ""
    private val checks = JSONArray()
    private val report = JSONObject().put("apiBaseUrl", BuildConfig.API_BASE_URL).put("testDataRemoved", false)

    /** 仅使用已保留的真实本地登录会话，正式来源不允许本测试创建业务记录。 */
    @Before fun requireLocalSession() {
        // 不注入伪造登录、不关闭安全配置、不清空原数据库。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        session = NativeAuthApi.refresh(stored.refreshToken)
        check(session.userId == stored.userId && NativeSessionStore.save(context, session))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("userId", session.userId)
    }

    /** 关闭页面与独立 Socket，恢复语言；成功和失败截图都保留，不删除业务数据。 */
    @After fun stop() {
        // 只回收当前测试的 UI 和连接，不注销原设备登录。
        try {
            if (scenario.isNotBlank()) screenshot("last-state")
            socket?.close()
            activity?.close()
            if (scenario.isNotBlank()) writeReport()
        } finally {
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 真实评论上传音频与视频，定位高亮自动消失，并通过附件行实际播放和下载。 */
    @Test fun commentMediaPlaysInlineAndFocusExpires() {
        // 使用专门公开文章，新增独立评论，不修改原文或其它用户评论。
        scenario = "comment"
        val arguments = InstrumentationRegistry.getArguments()
        val articleId = requireNotNull(arguments.getString("communityArticleId")).toInt()
        val slug = requireNotNull(arguments.getString("communityArticleSlug"))
        val article = NativeArticleApi.getArticle(session.accessToken, slug)
        check(article.id == articleId && article.title.startsWith("native_community_attachment_20261014_"))
        val files = mediaFiles()
        val comment = NativeArticleApi.createComment(session.accessToken, articleId, "Retained Android inline media acceptance", files = files)
        assertEquals(setOf("audio", "video"), comment.attachments.map { it.kind }.toSet())
        comment.attachments.forEach { file -> assertArrayEquals(files.first { it.name == file.originalName }.bytes, httpBytes(file.downloadUrl, session.accessToken, 200)) }
        report.put("articleId", articleId).put("articleSlug", slug).put("commentId", comment.id)
            .put("attachments", JSONArray(comment.attachments.map { JSONObject().put("id", it.id).put("name", it.originalName).put("kind", it.kind) }))
        record("real-comment-media-upload-and-authenticated-byte-match")
        launchRoute("/articles/$slug?commentId=${comment.id}")

        // 使用当前窗口的无障碍状态确认定位，不用之前的截图代替当前高亮。
        val focused = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已定位的评论")
        compose.waitUntil(60000) { compose.onAllNodes(focused, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        screenshot("focused")
        compose.waitUntil(15000) { compose.onAllNodes(focused, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        compose.onNodeWithTag("article-comment-${comment.id}", useUnmergedTree = true).assertIsDisplayed()
        screenshot("focus-expired")
        record("real-comment-focus-background-expires")
        comment.attachments.forEach { playAndPause(it.id, it.kind) }
        openDownload(comment.attachments.first { it.kind == "audio" }.id)
    }

    /** 独立群通过真实上传和 Socket 消息路径播放，匿名不能下载群媒体。 */
    @Test fun groupMediaPlaysInlineWithExistingMemberPermissions() {
        // 不向已有聊天或其它用户推送测试内容。
        scenario = "chat"
        val group = NativeGroupApi.create(session.accessToken, "native_inline_${UUID.randomUUID().toString().take(8)}", false, 1, "invite_only")
        val conversationId = group.summary.conversationId
        val files = mediaFiles()
        val attachments = NativeSocialApi.uploadChatAttachments(session.accessToken, conversationId, files)
        assertEquals(setOf("audio", "video"), attachments.map { it.kind }.toSet())
        val connected = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        var received: NativeChatMessage? = null
        var failure: String? = null
        socket = NativeChatSocket(session.accessToken,
            onMessage = { message -> if (message.conversationId == conversationId && message.attachments.map { it.id }.containsAll(attachments.map { it.id })) { received = message; delivered.countDown() } },
            onRead = { _, _ -> }, onError = { failure = it }, onConnectionChanged = { if (it) connected.countDown() })
        socket!!.connect()
        assertTrue(connected.await(30, TimeUnit.SECONDS))
        val acknowledged = CountDownLatch(1)
        var sent = false
        socket!!.send(conversationId, "Retained Android inline media acceptance", attachments.map { it.id }) { ok, error -> sent = ok; failure = error; acknowledged.countDown() }
        assertTrue(acknowledged.await(30, TimeUnit.SECONDS))
        assertTrue("Socket send failed: $failure", sent)
        assertTrue(delivered.await(30, TimeUnit.SECONDS))
        val message = requireNotNull(received)
        assertTrue(NativeSocialApi.listMessages(session.accessToken, conversationId).items.any { it.id == message.id })
        report.put("groupId", group.summary.id).put("conversationId", conversationId).put("messageId", message.id)
            .put("attachments", JSONArray(attachments.map { JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind) }))
        attachments.forEach { file ->
            assertArrayEquals(files.first { it.name == file.name }.bytes, httpBytes(file.downloadUrl, session.accessToken, 200))
            httpBytes(file.downloadUrl, null, 401)
        }
        record("real-group-socket-media-persisted-and-anonymous-rejected")
        socket!!.close()
        socket = null
        launchRoute("/messages?conversation=$conversationId&messageId=${message.id}")
        attachments.forEach { playAndPause(it.id, it.kind) }
        openDownload(attachments.first { it.kind == "video" }.id)
    }

    /** 返回自生成静音 WAV 与红色视频的独立上传文件，原名用于网页复核。 */
    private fun mediaFiles(): List<NativeUploadFile> {
        // 测试媒体总计很小，不消耗外部服务、生产存储或大规模下载资源。
        val suffix = UUID.randomUUID().toString().take(8)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return listOf(
            NativeUploadFile("native_inline_$suffix.wav", "audio/wav", assets.open("inline-silence.wav").use { it.readBytes() }),
            NativeUploadFile("native_inline_$suffix.mp4", "video/mp4", assets.open("inline-test-pattern.mp4").use { it.readBytes() }),
        )
    }

    /** 从真实路由 path 打开 MainActivity，不挂载替代屏幕或修改账号状态。 */
    private fun launchRoute(path: String) {
        // 系统接收正式入口使用的 Intent，再由现有导航解析文章或聊天定位。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + path)))
    }

    /** 在 id 对应真实附件组件播放与暂停；kind 为视频时另验全屏画面、返回和暂停状态。 */
    private fun playAndPause(id: Int, kind: String) {
        // 同屏多条媒体用附件编号定位，不能误点其它音频的按钮。
        val parent = hasAnyAncestor(hasTestTag("native-inline-media-$id"))
        compose.waitUntil(60000) { compose.onAllNodesWithTag("native-inline-media-$id", useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNode(hasContentDescription("播放媒体") and parent, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        // 进度真实推进后才取截图，不能把刚切换的暂停图标当作视频画面已输出。
        val advanced = SemanticsMatcher("Elapsed media time is greater than zero") { node ->
            // 系统繁忙时可能跳过恰好一秒的采样；任意实际正进度都满足推进要求。
            val texts = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text] else emptyList()
            texts.any { value ->
                val match = Regex("^(\\d+):([0-5]\\d) / \\d+:[0-5]\\d$").matchEntire(value.text)
                match != null && match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt() > 0
            }
        }
        compose.waitUntil(10000) {
            // MainActivity 的播放进度协程也由测试时钟驱动，原生解码时间仍来自真实 MediaPlayer。
            compose.mainClock.advanceTimeBy(250)
            compose.onAllNodes(advanced and parent, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        if (kind == "video") assertRealVideoFrame()
        screenshot("$kind-playing")
        // 截图耗时可能跨过短视频末尾；若已播完，手动重新开始后再验证暂停，不把自动结束算暂停。
        if (compose.onAllNodes(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasContentDescription("播放媒体") and parent, useUnmergedTree = true).performClick()
            compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNode(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).performClick()
        compose.onNode(hasContentDescription("播放媒体") and parent, useUnmergedTree = true).assertIsDisplayed()
        record("real-$scenario-$kind-inline-play-and-pause")
        if (kind == "video") {
            // 从实际业务附件进入全屏，不重新上传、不挂载替代播放器页面。
            compose.onNode(hasContentDescription("全屏播放") and parent, useUnmergedTree = true).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("native-video-fullscreen-$id").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            // 背景仍可能存在其它音频附件，只断言当前全屏窗口的播放状态。
            val fullscreenParent = hasAnyAncestor(hasTestTag("native-video-fullscreen-$id"))
            compose.onNode(hasContentDescription("播放媒体") and fullscreenParent, useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(hasContentDescription("暂停媒体") and fullscreenParent, useUnmergedTree = true).assertDoesNotExist()
            assertRealVideoFrame("fullscreen")
            screenshot("video-fullscreen-paused")
            // 返回值只代表无障碍事件时限，业务结果必须用全屏关闭及原附件状态核对。
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("native-video-fullscreen-$id").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
            compose.onNode(hasContentDescription("播放媒体") and parent, useUnmergedTree = true).assertIsDisplayed()
            assertRealVideoFrame("inline-return")
            record("real-$scenario-video-fullscreen-back-retains-paused-state")
        }
    }

    /** 验证 stage 当前业务窗口实际出现红色视频画面，不能用播放器元数据代替渲染证据。 */
    private fun assertRealVideoFrame(stage: String = "inline") {
        // 测试视频为纯红色，要求足够多的真实像素，排除头像或删除图标的干扰。
        var redPixels = 0
        // 系统解码和 Surface 合成独立于 Compose 时钟，必须限时等到真实像素。
        try {
            compose.waitUntil(10000) {
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                redPixels = 0
                for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                    val color = bitmap.getPixel(x, y)
                    val red = android.graphics.Color.red(color)
                    if (red > 120 && red > android.graphics.Color.green(color) * 2 && red > android.graphics.Color.blue(color) * 2) redPixels++
                }
                File(context.filesDir, "native-local-inline-$scenario-$stage-frame.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                redPixels > 100
            }
        } catch (timeout: ComposeTimeoutException) {
            // 失败截图与已有业务数据都保留，不能将黑屏或等待超时视为成功。
            throw AssertionError("Real $scenario $stage video frame is blank", timeout)
        }
        assertTrue("Real $scenario $stage video frame is blank", redPixels > 100)
        record("real-$scenario-$stage-video-frame-is-visible")
    }

    /** 点击 id 的原下载入口，确认仍使用统一另存为面板而非播放器私有路径。 */
    private fun openDownload(id: Int) {
        // 行内播放不能移除用户对原始文件的下载和系统打开能力。
        compose.onNode(hasContentDescription("下载附件") and hasAnyAncestor(hasTestTag("native-inline-media-$id")), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("另存为").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithText("打开").assertExists()
        record("inline-media-retains-shared-file-actions")
        screenshot("download-panel")
    }

    /** 用 token 获取本站 path，核对 status 后返回字节；拒绝响应不读取内容或记录凭据。 */
    private fun httpBytes(path: String, token: String?, status: Int): ByteArray {
        // 服务端文件权限仍由真实 API 判断，不依赖客户端缓存证明权限。
        require(path.startsWith('/') && !path.startsWith("//"))
        val connection = java.net.URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            assertEquals(status, connection.responseCode)
            if (status == 200) connection.inputStream.use { it.readBytes() } else byteArrayOf()
        } finally {
            connection.disconnect()
        }
    }

    /** 只记录已执行并断言的 check，失败时仍保留此前成功的范围。 */
    private fun record(check: String) {
        // 进度和业务标识可用于网页核对，不包含令牌、密码或媒体正文。
        checks.put(JSONObject().put("check", check).put("passed", true))
        writeReport()
    }

    /** 保存 stage 当前屏幕，覆盖当前测试的同阶段截图，不覆盖其它专项证据。 */
    private fun screenshot(stage: String) {
        // 实时抓取系统画面，不使用旧 UI 层级或渲染资产冒充实际页面。
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "native-local-inline-$scenario-$stage.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    /** 输出当前 report 和 checks，保留文章、群聊、消息和附件编号供复验。 */
    private fun writeReport() {
        // 成功数量只从已执行检查汇总，不能先生成最终通过报告。
        report.put("scenario", scenario).put("checks", checks)
        File(context.filesDir, "native-local-inline-$scenario-report.json").writeText(report.toString(2))
    }
}
