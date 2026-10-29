package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
import java.security.MessageDigest
import java.util.UUID

/** 在真实本地 MainActivity/API/MySQL 验证举报媒体和退群后的访问拒绝，保留所有测试记录。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalReportMediaTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    /** 当前业务 APK 的上下文仅用于既有会话和本次无凭据证据。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var session: NativeSession
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("testDataRemoved", false).put("apiBaseUrl", BuildConfig.API_BASE_URL)

    /** 沿用任意已授权的本地读者登录，禁止使用此用例写入正式服务器。 */
    @Before fun requireLocalSession() {
        // 不注入替身会话、不读取密码、不关闭站点安全配置。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId > 0 && stored.username.startsWith("native_restore_") && !stored.isSuperAdmin)
        session = NativeAuthApi.refresh(stored.refreshToken)
        check(NativeSessionStore.save(context, session))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 保存成功和失败证据，关闭自己的页面并恢复语言，不清理数据库或媒体。 */
    @After fun finish() {
        // 即使失败也保留已走过的业务检查点，不能只生成全成功结论。
        if (oldLocale != null) {
            screenshot("last-state")
            File(context.filesDir, "native-local-report-media-report.json").writeText(report.put("checks", checks).toString(2))
        }
        activity?.close()
        oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
    }

    /** 真实提交举报后查看图片、音视频和系统保存；退群后新附件必须被服务端与界面同时拒绝。 */
    @Test fun realReportPreviewMediaSaveAndMembershipBoundary() {
        // 只操作单独准备且名称带验收标识的群，禁止误操作已有业务聊天。
        val arguments = InstrumentationRegistry.getArguments()
        val groupId = requireNotNull(arguments.getString("groupId")).toInt()
        val messageId = requireNotNull(arguments.getString("messageId")).toInt()
        val group = NativeGroupApi.get(session.accessToken, groupId)
        check(group.summary.name.startsWith("native_report_media_20261015_"))
        NativeSocialApi.reportGroupMessage(session.accessToken, groupId, messageId, "other", "Retained Android report media acceptance")
        val submitted = NativeReportsApi.list(session.accessToken).single { it.source == "group_message" && it.message?.id == messageId }
        val attachments = requireNotNull(submitted.message).attachments
        assertEquals(listOf("image", "audio", "video", "file", "file"), attachments.map { it.kind })
        report.put("userId", session.userId).put("groupId", groupId).put("messageId", messageId).put("reportId", submitted.id)
            .put("attachments", JSONArray(attachments.map { JSONObject().put("id", it.id).put("kind", it.kind).put("name", it.originalName) }))
        record("real-report-api-persisted-and-listed-under-own-account")

        // 身份头必须由真实下载接口接受，匿名请求不允许读取任何附件。
        attachments.take(4).forEach { attachment ->
            val bytes = http(attachment.downloadUrl, session.accessToken, 200)
            http(attachment.downloadUrl, null, 401)
            when (attachment.kind) {
                "image" -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { assertEquals(120, it.width); assertEquals(90, it.height); it.recycle() }
                "audio", "video" -> {
                    val asset = if (attachment.kind == "audio") "inline-silence.wav" else "inline-test-pattern.mp4"
                    assertArrayEquals(InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { it.readBytes() }, bytes)
                }
                else -> assertTrue(bytes.toString(Charsets.UTF_8).startsWith("Retained report attachment: "))
            }
        }
        record("real-authenticated-media-bytes-match-fixtures-and-anonymous-is-401")
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + "/profile/reports")))
        val card = "my-report-${submitted.key}"
        waitTag(card)
        compose.onNodeWithTag(card).performScrollTo().performClick()
        waitTag("report-message-$messageId")
        screenshot("detail")

        // 图片由实际举报附件触发预览，不将聊天中的成功截图充当举报验收。
        val image = attachments.first()
        compose.onNodeWithTag("report-attachment-${image.id}").performScrollTo()
        compose.onNodeWithContentDescription(image.originalName).performClick()
        waitDescription("关闭图片预览")
        compose.waitUntil(20000) { compose.onAllNodesWithText("正在读取图片…").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        compose.onNodeWithText("图片加载失败，请检查网络后重试。").assertDoesNotExist()
        screenshot("image")
        compose.onNodeWithContentDescription("关闭图片预览").performClick()
        record("real-report-image-thumbnail-and-original-preview")

        // 真实系统解码器进入播放态后暂停，确保音视频不是只有名称或按钮。
        attachments.filter { it.kind in listOf("audio", "video") }.forEach { attachment ->
            val parent = hasAnyAncestor(hasTestTag("native-inline-media-${attachment.id}"))
            compose.onNode(hasContentDescription("播放媒体") and parent, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(30000) { compose.onAllNodes(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            if (attachment.kind == "video") assertVideoFrame()
            screenshot(attachment.kind)
            compose.onNode(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).performClick()
        }
        record("real-report-audio-and-video-decode-and-pause")

        // 系统另存为必须真正生成可回读文件，而不止出现下载面板。
        val file = attachments[3]
        clickFile(file.id); waitText("另存为")
        compose.onNodeWithText("另存为").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // 系统目录条目也使用 title，必须限定为可编辑文件名，不能误取最近目录名称。
        val title = device.wait(Until.findObject(By.res("android", "title").clazz("android.widget.EditText")), 20000) ?: error("System file name input missing")
        assertEquals(file.originalName, title.text)
        val savedName = "hlovet-report-${UUID.randomUUID()}.txt"
        title.text = savedName
        requireNotNull(device.wait(Until.findObject(By.res("android", "button1")), 20000)).click()
        waitText("附件已保存到所选位置。")
        val saved = device.executeShellCommand("cat /sdcard/Download/$savedName").toByteArray()
        val expected = http(file.downloadUrl, session.accessToken, 200)
        assertArrayEquals(expected, saved)
        report.put("savedFileName", savedName).put("savedBytes", saved.size)
            .put("savedSha256", MessageDigest.getInstance("SHA-256").digest(saved).joinToString("") { "%02x".format(it) })
        screenshot("saved")
        record("real-system-save-output-matches-authenticated-file-bytes")
        device.pressBack()
        waitTag("report-message-$messageId")

        // 退群后举报记录仍存在，但从未缓存的附件仍须经过权限判断并显示失败。
        NativeGroupApi.leave(session.accessToken, groupId)
        assertTrue(NativeReportsApi.list(session.accessToken).any { it.key == submitted.key })
        val denied = attachments[4]
        http(denied.downloadUrl, session.accessToken, 403)
        clickFile(denied.id)
        waitText("附件下载失败，请检查网络后重试。")
        compose.onNodeWithText("附件下载失败，请检查网络后重试。").assertIsDisplayed()
        compose.onNodeWithText("另存为").assertDoesNotExist()
        screenshot("denied-after-leave")
        record("real-left-group-report-retained-but-uncached-file-denied-403-with-visible-error")
    }

    /** 点击 id 对应的真实文件行，保持其它附件状态不变。 */
    private fun clickFile(id: Int) {
        // 父附件标识限定本次点击范围。
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("report-attachment-$id"))).performScrollTo().performClick()
    }

    /** 认证下载 path 并断言 expected 状态，只返回字节而不记录身份信息。 */
    private fun http(path: String, token: String?, expected: Int): ByteArray {
        // 不跟随重定向，避免把本站会话发送到其它来源。
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            assertEquals(expected, connection.responseCode)
            if (expected == 200) connection.inputStream.use { it.readBytes() } else byteArrayOf()
        } finally { connection.disconnect() }
    }

    /** 等待真实组件 tag 挂载，允许系统窗口切换时短暂无语义根。 */
    private fun waitTag(tag: String) {
        // 用业务键确认路径而非任意同名文字。
        compose.waitUntil(60000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待 text 反馈出现，最长六十秒。 */
    private fun waitText(text: String) {
        // 网络与系统文件窗口均可能需要额外调度时间。
        compose.waitUntil(60000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待 description 对应操作控件挂载。 */
    private fun waitDescription(description: String) {
        // 只有真正进入图片窗口才继续断言。
        compose.waitUntil(30000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 将 name 检查点记录为已通过，后续失败不会抹去已有检查点。 */
    private fun record(name: String) {
        // 不把任何凭据或原始认证响应写入报告。
        checks.put(JSONObject().put("name", name).put("passed", true))
    }

    /** 核对举报弹窗确实输出红色测试视频画面，不能只凭播放器处于播放态判定成功。 */
    private fun assertVideoFrame() {
        // 等待真实 Surface 首帧，避免在准备完成到首帧之间截图而误判为黑屏。
        compose.waitUntil(12000) {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitUntil false
            var red = 0
            for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) < 90 && android.graphics.Color.blue(color) < 90) red++
            }
            bitmap.recycle()
            red > 100
        }
    }

    /** 保存 name 对应当前窗口截图，不拍摄登录凭据页面。 */
    private fun screenshot(name: String) {
        // 证据存放在业务私有目录，由宿主单独提取。
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1500)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(context.filesDir, "native-local-report-media-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
