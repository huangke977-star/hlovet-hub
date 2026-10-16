package xyz.hlovet.portal.prototype

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
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

/** 在真实 MainActivity 中验证评论、群聊和公告附件入口，保留独立本地业务数据及系统保存记录。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalCommunityAttachmentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private var activity: ActivityScenario<MainActivity>? = null
    private var socket: NativeChatSocket? = null
    private var oldLocale: String? = null
    private lateinit var session: NativeSession
    private var scenario = ""
    private val checks = JSONArray()
    private val report = JSONObject().put("apiBaseUrl", BuildConfig.API_BASE_URL).put("testDataRemoved", false)

    /** 只允许精确本地来源及已保留账号，刷新真实加密会话而不注入替代登录状态。 */
    @Before fun requireLocalSession() {
        // 正式构建不得创建测试业务数据；缺少已验证会话时直接跳过。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        session = NativeAuthApi.refresh(stored.refreshToken)
        check(session.userId == stored.userId && NativeSessionStore.save(context, session))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("userId", session.userId)
    }

    /** 关闭页面和测试 Socket，保留全部业务数据、下载和本轮通过/失败证据。 */
    @After fun stop() {
        // 即使失败也记录真实最后一帧，不能把历史截图或尚未执行的步骤当作通过。
        try {
            if (scenario.isNotBlank()) screenshot("last-state")
            socket?.close()
            activity?.close()
            if (scenario.isNotBlank()) writeReport()
        } finally {
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 通过真实评论上传构造附件，再从文章评论定位入口下载并另存为。 */
    @Test fun commentAttachmentUsesRealArticleRouteAndSystemSave() {
        // 只向专用测试文章追加独立评论，不能更改原文或删除其它评论。
        scenario = "comment"
        val articleId = requireNotNull(arguments.getString("communityArticleId")).toInt()
        val draft = NativeArticleApi.getArticle(session.accessToken, requireNotNull(arguments.getString("communityArticleSlug")))
        check(draft.id == articleId && draft.title.startsWith("native_community_attachment_20261014_"))
        val fileName = "native_comment_${UUID.randomUUID().toString().take(8)}.txt"
        val bytes = "Retained real Android comment attachment.\n".toByteArray()
        val comment = NativeArticleApi.createComment(session.accessToken, articleId, "Android attachment entry verification",
            files = listOf(NativeUploadFile(fileName, "text/plain", bytes)))
        val attachment = comment.attachments.single()
        assertEquals(fileName, attachment.originalName)
        assertTrue(NativeArticleApi.listComments(session.accessToken, draft.slug, focusId = comment.id).items.any { it.id == comment.id })
        report.put("articleId", articleId).put("articleSlug", draft.slug).put("commentId", comment.id).put("attachmentId", attachment.id)
        record("real-comment-upload-and-persisted-read")
        assertArrayEquals(bytes, httpBytes(attachment.downloadUrl, session.accessToken, 200))
        assertArrayEquals(bytes, httpBytes(attachment.downloadUrl, null, 200))
        record("public-comment-file-permission-and-authenticated-bytes")

        // 使用真实评论定位，点击文件行触发既有下载，不直接调用共享面板。
        launchRoute("/articles/${draft.slug}?commentId=${comment.id}")
        waitText(fileName)
        compose.onNodeWithText(fileName).performScrollTo().performClick()
        saveFromPanel(fileName, attachment.downloadUrl, bytes)
    }

    /** 通过真实群聊、上传及 Socket 发送创建消息，从消息定位入口保存附件并检查匿名拒绝。 */
    @Test fun groupChatAttachmentUsesRealSocketMessageAndSystemSave() {
        // 独立群只含当前测试账号，不向已有群或真实用户发送测试内容。
        scenario = "chat"
        val group = NativeGroupApi.create(session.accessToken, "native_attachment_${UUID.randomUUID().toString().take(8)}", false, 1, "invite_only")
        val conversationId = group.summary.conversationId
        val fileName = "native_chat_${UUID.randomUUID().toString().take(8)}.txt"
        val bytes = "Retained real Android chat attachment.\n".toByteArray()
        val attachment = NativeSocialApi.uploadChatAttachments(session.accessToken, conversationId,
            listOf(NativeUploadFile(fileName, "text/plain", bytes))).single()
        report.put("groupId", group.summary.id).put("conversationId", conversationId).put("attachmentId", attachment.id)
        val connected = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        var failure: String? = null
        var received: NativeChatMessage? = null
        socket = NativeChatSocket(session.accessToken,
            onMessage = { message -> if (message.conversationId == conversationId && message.attachments.any { it.id == attachment.id }) { received = message; delivered.countDown() } },
            onRead = { _, _ -> }, onError = { failure = it }, onConnectionChanged = { if (it) connected.countDown() })
        socket!!.connect()
        assertTrue("Real local chat socket did not connect: $failure", connected.await(30, TimeUnit.SECONDS))
        val acknowledged = CountDownLatch(1)
        var sent = false
        socket!!.send(conversationId, "Android file-save acceptance", listOf(attachment.id)) { ok, error -> sent = ok; failure = error; acknowledged.countDown() }
        assertTrue(acknowledged.await(30, TimeUnit.SECONDS))
        assertTrue("Real socket rejected the message: $failure", sent)
        assertTrue(delivered.await(30, TimeUnit.SECONDS))
        val message = requireNotNull(received)
        assertTrue(NativeSocialApi.listMessages(session.accessToken, conversationId).items.any { it.id == message.id && it.attachments.any { file -> file.id == attachment.id } })
        report.put("messageId", message.id)
        record("real-group-upload-socket-send-and-persisted-message")
        assertArrayEquals(bytes, httpBytes(attachment.downloadUrl, session.accessToken, 200))
        httpBytes(attachment.downloadUrl, null, 401)
        record("chat-file-authenticated-download-and-anonymous-rejection")
        verifyNonMemberRejected(conversationId, attachment.downloadUrl)
        socket!!.close()
        socket = null

        // MainActivity 继续按真实权限解析会话和定位消息，文件行与评论共用同一组件。
        launchRoute("/messages?conversation=$conversationId&messageId=${message.id}")
        waitText(fileName)
        compose.onNodeWithText(fileName).performScrollTo().performClick()
        saveFromPanel(fileName, attachment.downloadUrl, bytes)
    }

    /** 读取预先由真实管理接口创建的登录可见公告，从正文链接进入系统保存。 */
    @Test fun announcementAttachmentUsesRealDetailRouteAndSystemSave() {
        // 精确核对公告标识和受众，匿名不能读正文，普通登录账号不能访问管理详情。
        scenario = "announcement"
        val announcementId = requireNotNull(arguments.getString("communityAnnouncementId")).toInt()
        val expectedName = requireNotNull(arguments.getString("communityOriginalName"))
        val downloadUrl = requireNotNull(arguments.getString("communityDownloadUrl"))
        val detail = NativeCommunityApi.getAnnouncement(session.accessToken, announcementId)
        check(detail.summary.title.startsWith("native_announcement_download_20261014_"))
        assertTrue(detail.content.contains(downloadUrl))
        httpBytes("/announcements/public/$announcementId", null, 404)
        httpBytes("/announcements/admin/$announcementId", session.accessToken, 403)
        val bytes = httpBytes(downloadUrl, session.accessToken, 200)
        report.put("announcementId", announcementId).put("originalName", expectedName)
        record("visible-announcement-read-and-public-admin-permission-boundary")

        // 点击实际详情 WebView 的链接；相对 URL 使用正式媒体解析规则，不手动挂载对话框。
        launchRoute("/announcements/$announcementId")
        clickRealBodyLink(downloadUrl)
        saveFromPanel(expectedName, downloadUrl, bytes)
    }

    /** 通过 MainActivity 打开本站 path，保留真实来源、会话恢复和路由权限逻辑。 */
    private fun launchRoute(path: String) {
        // 每个用例只挂载一个真实 Activity，不能绕过业务页面直接调用下载函数。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + path)))
    }

    /** 等待当前 Compose 页面中的 text 出现，容许系统返回过程暂时没有语义根。 */
    private fun waitText(text: String) {
        // Compose 等待负责推进重组时钟，不能仅睡眠后用旧语义节点判定成功。
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 点击实际阅读视图中指向 url 的正文链接，缺少视图或链接时继续等待。 */
    private fun clickRealBodyLink(url: String) {
        // 遍历真实窗口，包括公告 Dialog 的视图，响应回调必须在本次视图中返回成功。
        var clicked = false
        compose.waitUntil(60_000) {
            val latch = CountDownLatch(1)
            activity!!.onActivity {
                val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
                val view = roots.flatMap { bodyViews(it) }.firstOrNull()
                if (view == null) latch.countDown() else view.evaluateJavascript(
                    "(function(){var a=Array.from(document.querySelectorAll('a')).find(function(x){return x.getAttribute('href')===" + JSONObject.quote(nativeMediaUrl(url)) + ";});if(!a)return false;a.click();return true;})()"
                ) { result -> clicked = result == "true"; latch.countDown() }
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            clicked
        }
    }

    /** 收集 view 下真实 WebView，结果为空表示业务正文尚未挂载。 */
    private fun bodyViews(view: View): List<WebView> {
        // 只读取窗口树，不创建替代正文，也不调用私有事件桥。
        if (view is WebView) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { bodyViews(view.getChildAt(it)) }
    }

    /** 在原生下载面板点击系统保存，核对 originalName、url 对应的 HTTP 和 expected 字节。 */
    private fun saveFromPanel(originalName: String, url: String, expected: ByteArray) {
        // 面板标题必须保留真实文件名，保存名称另用唯一值，避免覆盖用户下载。
        waitText("另存为")
        compose.onNode(hasText(originalName) and hasAnyAncestor(isDialog())).assertExists()
        record("real-$scenario-entry-opened-shared-file-actions")
        screenshot("panel")
        compose.onNodeWithText("另存为").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val title = systemNode("android:id/title", originalName)
        // 默认名称与系统文档类型都正确，不能只验证能保存而漏掉查看器匹配所需的 MIME。
        val activityState = automation.executeShellCommand("dumpsys activity activities").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(activityState.contains("act=android.intent.action.CREATE_DOCUMENT typ=text/plain"))
        val saveName = "hlovet-$scenario-${UUID.randomUUID()}.txt"
        assertTrue(title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, saveName)
        }))
        assertTrue(systemNode("android:id/button1").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(45_000) { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
        waitText("附件已保存到所选位置。")
        record("system-document-picker-original-name-and-success-feedback")

        // 回读真实系统目录并与已知上传字节、认证服务端响应同时比较，不仅与缓存自身比较。
        val actual = automation.executeShellCommand("cat /sdcard/Download/$saveName").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        assertArrayEquals(expected, httpBytes(url, session.accessToken, 200))
        assertArrayEquals(expected, actual)
        report.put("originalName", originalName).put("savedName", saveName).put("bytes", actual.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(actual).joinToString("") { "%02x".format(it) })
        record("saved-system-file-matches-upload-and-authenticated-response")
        screenshot("success")
    }

    /** 使用 token 读取固定本站 path 并核对 status，成功返回原始字节，拒绝响应不读取业务正文。 */
    private fun httpBytes(path: String, token: String?, status: Int): ByteArray {
        // 禁止外部地址和重定向，日志不包含认证头、令牌或原始业务响应。
        require(path.startsWith('/') && !path.startsWith("//"))
        val connection = java.net.URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            assertEquals("Unexpected response for $path", status, connection.responseCode)
            if (status == 200) connection.inputStream.use { it.readBytes() } else byteArrayOf()
        } finally {
            connection.disconnect()
        }
    }

    /** 注册独立普通 outsider，验证非群成员不能读 conversationId 或 downloadUrl，不保存替代设备会话。 */
    private fun verifyNonMemberRejected(conversationId: Int, downloadUrl: String) {
        // 只在现有开放且无需邮件/人机验证的本地策略下注册，不为测试关闭安全开关。
        val policy = NativeAuthApi.registrationPolicy()
        check(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled)
        val username = "native_file_peer_" + UUID.randomUUID().toString().replace("-", "").take(8)
        val result = NativeAuthApi.register(username, "Android file peer", "$username@android-fixture.invalid",
            "Native_${UUID.randomUUID()}_9!", null, null)
        check(result is NativeAuthResult.Authenticated)
        val outsider = result.session
        report.put("nonMemberUserId", outsider.userId).put("nonMemberUsername", username)
        try {
            // 群消息和文件必须使用同一成员权限，不能因知道文件 URL 就绕过会话授权。
            httpBytes("/social/conversations/$conversationId/messages", outsider.accessToken, 403)
            httpBytes(downloadUrl, outsider.accessToken, 403)
            record("non-member-cannot-read-group-messages-or-file")
        } finally {
            // 只注销本次额外 API 会话，测试账号及当前真实设备登录保持不变。
            NativeAuthApi.logout(outsider.refreshToken)
        }
    }

    /** 等待 DocumentsUI 的 viewId，text 非空时核对默认原名，返回本轮实时节点。 */
    private fun systemNode(viewId: String, text: String? = null): AccessibilityNodeInfo {
        // 系统节点仅从当前窗口获取，不依赖固定坐标和旧层级文件。
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 45_000
        do {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull {
                it.packageName?.toString() == "com.google.android.documentsui" && it.isVisibleToUser &&
                    (text == null || it.text?.toString() == text)
            }?.let { return it }
            SystemClock.sleep(500)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("System document node unavailable: $viewId")
    }

    /** 记录已断言通过的 check，不生成未执行的后续成功项。 */
    private fun record(check: String) {
        // 即时保存进度便于失败后核对部分覆盖，不记录文件原文或密码。
        checks.put(JSONObject().put("check", check).put("passed", true))
        writeReport()
    }

    /** 将 stage 的真实截图保存在当前场景的独立证据文件中。 */
    private fun screenshot(stage: String) {
        // 测试图像不覆盖其它专项，也不清理已保留业务文件。
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "native-local-community-$scenario-$stage.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    /** 保存当前 report 和 checks，所有业务标识可用于本地网页复验。 */
    private fun writeReport() {
        // 来源和场景分开记录，不能把不同入口的通过数相互替代。
        report.put("scenario", scenario).put("checks", checks)
        File(context.filesDir, "native-local-community-$scenario-report.json").writeText(report.toString(2))
    }
}
