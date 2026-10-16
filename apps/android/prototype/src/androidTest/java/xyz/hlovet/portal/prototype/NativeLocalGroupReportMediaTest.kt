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

/** 验证真实群主管理与后台群聊举报媒体，保留数据并恢复原读者会话；不代表管理员交互登录。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalGroupReportMediaTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** 当前测试包只访问业务私有目录中的专用证据和临时授权。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var owner: NativeSession? = null
    private lateinit var admin: NativeSession
    private var scenario: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("testDataRemoved", false).put("adminInteractiveLoginTested", false)
        .put("adminAuthorization", "short-lived-local-container-signed-jwt")

    /** 仅接受精确本地来源及既有测试身份，临时管理员授权读取后立即删除。 */
    @Before fun requireLocalSessions() {
        // 在读取会话或授权前拒绝任何生产或隔离来源。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        owner = NativeAuthApi.refresh(stored.refreshToken)
        check(NativeSessionStore.save(context, requireNotNull(owner)))
        val credential = File(context.filesDir, "native-local-group-report-admin.json")
        val token = try { JSONObject(credential.readText()).getString("accessToken") } finally { credential.delete() }
        val user = JSONObject(http("/auth/me", token, 200).toString(Charsets.UTF_8))
        check(user.getInt("id") == 7 && user.getString("username") == "android_parity_admin" && user.getBoolean("isSuperAdmin"))
        admin = NativeSession(token, "", 7, user.getString("username"), user.getString("nickname"),
            PreviewApi.appearanceFromJson(user.optJSONObject("appearance") ?: JSONObject()), isSuperAdmin = true,
            isAdministrator = user.optBoolean("isAdministrator"))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 无论成功失败均保存证据；关闭实际 Activity 后恢复读者，不改群或举报处理状态。 */
    @After fun restoreOwner() {
        // 收尾不能用旧刷新令牌覆盖 MainActivity 已轮换的新凭据。
        try {
            if (oldLocale != null) {
                screenshot("last-state")
                File(context.filesDir, "native-local-group-report-report.json").writeText(report.put("checks", checks).toString(2))
            }
        } finally {
            closePage()
            owner?.let { check(NativeSessionStore.save(context, it)) }
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 通过两个真实入口读取同一举报，验证媒体操作与管理员最小附件权限。 */
    @Test fun ownerAndAdminGroupReportMediaRespectRealPermissions() {
        // 只处理专用夹具群，真实客户端提交举报，不直接写举报表。
        val reader = requireNotNull(owner)
        val args = InstrumentationRegistry.getArguments()
        val groupId = requireNotNull(args.getString("groupId")).toInt()
        val messageId = requireNotNull(args.getString("messageId")).toInt()
        val unreportedId = requireNotNull(args.getString("unreportedAttachmentId")).toInt()
        val group = NativeGroupApi.get(reader.accessToken, groupId)
        check(group.summary.name.startsWith("native_group_report_20261015_") && group.summary.owner.id == 34 && group.summary.canManage)
        NativeSocialApi.reportGroupMessage(reader.accessToken, groupId, messageId, "other", "Retained group panel media acceptance")
        val item = NativeGroupApi.listAdminReports(reader.accessToken, groupId = groupId).single { it.message.id == messageId }
        assertEquals(listOf("image", "audio", "video", "file"), item.message.attachments.map { it.kind })
        report.put("groupId", groupId).put("messageId", messageId).put("reportId", item.id).put("ownerId", 34).put("adminId", 7)
            .put("unreportedAttachmentId", unreportedId).put("attachments", JSONArray(item.message.attachments.map { it.id }))
        record("real-owner-report-api-created-and-readable-with-group-manager-permission")

        // 群主入口来自实际 messages 深链，媒体操作均点击实际页面控件。
        openAs(reader, "/messages?groupApproval=$groupId&report=${item.id}")
        waitTag("group-management-report-${item.id}")
        previewImage(item.message.attachments[0], "owner-image")
        play(item.message.attachments[1], "owner-audio")
        play(item.message.attachments[2], "owner-video")
        openFile(item.message.attachments[3], "owner-file")
        record("real-group-manager-page-original-image-decoded-media-and-file-actions")
        closePage()

        // 站点管理员不属于群成员，只能通过已举报消息获得有限媒体读取权限。
        val adminGroup = NativeGroupApi.get(admin.accessToken, groupId)
        assertTrue(adminGroup.members.none { it.user.id == admin.userId && it.status == "active" })
        val adminReport = NativeGroupApi.listAdminReports(admin.accessToken, groupId = groupId).single { it.id == item.id }
        for (attachment in adminReport.message.attachments) {
            assertArrayEquals(http(attachment.downloadUrl, requireNotNull(owner).accessToken, 200), http(attachment.downloadUrl, admin.accessToken, 200))
            http(attachment.downloadUrl, null, 401)
        }
        http("/social/attachments/$unreportedId/download", admin.accessToken, 403)
        record("real-nonmember-admin-can-read-reported-media-but-unreported-file-is-403-and-anonymous-is-401")

        // 后台群聊页面独立切换举报页签并定位本轮报告，不复用本人举报弹窗。
        val pendingCount = NativeGroupApi.listAdminReports(admin.accessToken).size
        openAs(admin, "/admin/groups")
        waitText("举报 $pendingCount")
        compose.onNodeWithText("举报 $pendingCount").performScrollTo().performClick()
        waitTag("admin-group-report-${item.id}")
        val card = hasAnyAncestor(hasTestTag("admin-group-report-${item.id}"))
        compose.onNode(hasText("查看详情") and card).performScrollTo().performClick()
        waitTag("report-message-$messageId")
        previewImage(item.message.attachments[0], "admin-image")
        play(item.message.attachments[1], "admin-audio")
        play(item.message.attachments[2], "admin-video")
        openFile(item.message.attachments[3], "admin-file")
        record("real-admin-groups-report-page-original-image-decoded-media-and-file-actions")
    }

    /** 保存 session 后用 path 启动真正 MainActivity，先关闭旧页避免并发刷新。 */
    private fun openAs(session: NativeSession, path: String) {
        // 相同账号只使用最新安全存储，管理员短期授权不作为登录成功证据。
        closePage()
        val current = if (session.userId == 34) requireNotNull(owner) else session
        check(NativeSessionStore.save(context, current))
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + path)))
    }

    /** 关闭实际页面并接收最新读者会话，不保存管理员令牌到测试报告。 */
    private fun closePage() {
        // Activity 停止后读取，避免旧异步写入覆盖下一次会话恢复。
        scenario?.close(); scenario = null
        NativeSessionStore.load(context)?.takeIf { it.userId == 34 }?.let { owner = it }
    }

    /** 点击 attachment 原图并确认加载结束，保存 name 截图后回到当前举报。 */
    private fun previewImage(attachment: NativeChatAttachmentPreview, name: String) {
        // 等待图片真实读取而不是把缩略图请求当作预览成功。
        compose.onNodeWithTag("report-attachment-${attachment.id}").performScrollTo()
        compose.onNodeWithContentDescription(attachment.name).performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("关闭图片预览").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.waitUntil(30000) { compose.onAllNodesWithText("正在读取图片…").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        compose.onNodeWithText("图片加载失败，请检查网络后重试。").assertDoesNotExist()
        screenshot(name)
        compose.onNodeWithContentDescription("关闭图片预览").performClick()
    }

    /** 播放 attachment 的真实媒体，name 记录实际屏幕，视频还需要真实首帧像素。 */
    private fun play(attachment: NativeChatAttachmentPreview, name: String) {
        // 按附件编号选择控件，防止同屏音视频交叉操作。
        val parent = hasAnyAncestor(hasTestTag("native-inline-media-${attachment.id}"))
        compose.onNode(hasContentDescription("播放媒体") and parent, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        if (attachment.kind == "video") compose.waitUntil(15000) {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitUntil false
            var red = 0
            for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) < 90 && android.graphics.Color.blue(color) < 90) red++
            }
            bitmap.recycle()
            red > 100
        }
        screenshot(name)
        compose.onNode(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).performClick()
    }

    /** 点击 attachment 文件操作并核对当前账号缓存字节，name 保存面板截图。 */
    private fun openFile(attachment: NativeChatAttachmentPreview, name: String) {
        // 此处验证统一打开/另存为面板，系统真正保存已由上一轮独立流程验证。
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("report-attachment-${attachment.id}"))).performScrollTo().performClick()
        waitText("另存为")
        compose.onNodeWithText("另存为").assertIsDisplayed()
        val session = requireNotNull(NativeSessionStore.load(context))
        val file = NativeOfflineCache.loadCachedMediaFile(context, attachment.downloadUrl, NativeOfflineOwner.forSession(session))
        assertArrayEquals(http(attachment.downloadUrl, session.accessToken, 200), requireNotNull(file).readBytes())
        screenshot(name)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    }

    /** 认证访问固定本地 path，核对 expected HTTP 状态并返回成功字节，不跟随跳转。 */
    private fun http(path: String, token: String?, expected: Int): ByteArray {
        // 禁止把测试授权发送给其他来源。
        check(path.startsWith("/") && !path.startsWith("//"))
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            assertEquals(expected, connection.responseCode)
            if (expected == 200) connection.inputStream.use { it.readBytes() } else byteArrayOf()
        } finally { connection.disconnect() }
    }

    /** 等待 tag 对应当前真实业务组件出现。 */
    private fun waitTag(tag: String) {
        // 允许 Activity 首次加载时暂时没有语义根，最长等待一分钟。
        compose.waitUntil(60000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待 text 对应实际页面文案出现。 */
    private fun waitText(text: String) {
        // 文案匹配业务视图而非 HTTP 完成信号。
        compose.waitUntil(60000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 为 name 保存当前业务窗口，不拍摄凭据输入或授权文件。 */
    private fun screenshot(name: String) {
        // 等待窗口稳定后保存 PNG，避免只捕获系统文件面板的返回过渡。
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1500)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(context.filesDir, "native-local-group-report-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** 记录已经通过的 name 检查点，后续失败不会抹去之前证据。 */
    private fun record(name: String) {
        // 报告只包含非敏感断言与业务编号。
        checks.put(JSONObject().put("name", name).put("passed", true))
    }
}
