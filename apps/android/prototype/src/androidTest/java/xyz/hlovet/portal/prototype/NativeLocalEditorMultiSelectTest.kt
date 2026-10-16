package xyz.hlovet.portal.prototype

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实系统 DocumentsUI 多选和真实本地 API 验证文章批量附件，不替换文件选择器回调。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalEditorMultiSelectTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("apiBaseUrl", BuildConfig.API_BASE_URL).put("testDataRemoved", false)
    private lateinit var session: NativeSession

    /** 只允许精确的本地来源及保留账号，避免在生产环境上传验证文件。 */
    @Before fun requireLocalSession() {
        // 系统 Downloads 只新增独立命名文件，不清空任何已有下载或设备账号。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        session = NativeAuthApi.refresh(stored.refreshToken)
        check(session.userId == stored.userId && NativeSessionStore.save(context, session))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        report.put("userId", session.userId)
    }

    /** 保留测试文章、系统文件和最后证据，只关闭 Activity 并恢复语言。 */
    @After fun stop() {
        // 失败也保存真实系统节点，不能以旧截图或仅 API 上传成功代替选择器验收。
        try {
            if (oldLocale != null) {
                saveScreenshot("last-state")
                File(context.filesDir, "native-local-multiselect-last-window.json").writeText(windowNodes().toString(2))
                writeReport()
            }
            activity?.close()
        } finally {
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 取消不变更文章，然后一次系统多选图片与文本，验证正文引用、手动保存和服务端真实字节。 */
    @Test fun realSystemMultiSelectUploadsImageAndTextInOneBatch() {
        // 独立草稿避免覆盖已有文章，所有上传必须由实际编辑页的系统回调发起。
        val suffix = UUID.randomUUID().toString().take(8)
        val draft = NativeArticleApi.createArticle(session.accessToken, NativeDraftInput(
            "native_multiselect_20261014_$suffix", "Real Android system picker verification", "<p>Original multi-select body.</p>",
            "html", "随笔", "",
        ))
        report.put("articleId", draft.id).put("articleSlug", draft.slug).put("title", draft.title)
        val textName = "hlovet_multi_$suffix.txt"
        val imageName = "hlovet_multi_$suffix.png"
        val text = "Real system multi-select attachment $suffix.\n".toByteArray()
        val image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).let { bitmap ->
            // 图片具有可检查的非空绿色像素，不依赖外部图片或网络资源。
            bitmap.eraseColor(Color.rgb(31, 170, 96))
            ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
        }
        val textUri = createSystemFile(textName, "text/plain", text)
        val imageUri = createSystemFile(imageName, "image/png", image)
        report.put("systemTextUri", textUri.toString()).put("systemImageUri", imageUri.toString())
        report.put("fileNames", JSONArray().put(textName).put(imageName))
        record("independent-draft-and-real-system-downloads-created")
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/articles/edit/${draft.id}")))
        waitText("附件")

        // 真实系统取消必须返回编辑页，且数据库正文及附件集合与取消前相同。
        compose.onNodeWithText("附件").performScrollTo().performClick()
        waitSystemPackage()
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(45_000) { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
        waitText("附件")
        val cancelled = NativeArticleApi.getMyArticle(session.accessToken, draft.id)
        assertEquals(draft.content, cancelled.content)
        assertEquals(draft.attachments, cancelled.attachments)
        record("real-picker-cancel-keeps-existing-body-and-attachments")

        // 使用可见的系统长按多选，再点击第二个文档；不调用 ActivityResultRegistry 的替代回包。
        compose.onNodeWithText("附件").performScrollTo().performClick()
        waitSystemPackage()
        val first = systemFile(textName)
        pressDocument(first, longPress = true)
        val second = systemFile(imageName)
        pressDocument(second, longPress = false)
        saveScreenshot("two-selected")
        File(context.filesDir, "native-local-multiselect-selection-window.json").writeText(windowNodes().toString(2))
        val confirm = systemConfirm()
        assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(60_000) { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
        waitText(textName)
        waitText(imageName)
        record("real-documents-ui-two-files-selected-and-returned-to-editor")

        // 从真实编辑页保存后按业务 ID 回读，验证两个文件及正确正文引用没有被静默截断。
        waitText("保存草稿")
        compose.onNodeWithText("保存草稿").assertIsDisplayed().performClick()
        var saved: NativeDraftArticle? = null
        var lastRead = 0L
        compose.waitUntil(60_000) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRead >= 700) {
                lastRead = now
                val value = NativeArticleApi.getMyArticle(session.accessToken, draft.id)
                if (value.attachments.size == 2 && value.attachments.all { value.content.contains(it.downloadUrl) }) saved = value
            }
            saved != null
        }
        val actual = requireNotNull(saved)
        assertEquals(setOf(textName, imageName), actual.attachments.map { it.originalName }.toSet())
        assertTrue(actual.content.contains("Original multi-select body."))
        val attachmentResults = JSONArray()
        for (attachment in actual.attachments) {
            // 每个响应必须与系统源字节一致；不使用同一上传回包同时作预期和实际。
            val expected = if (attachment.originalName == textName) text else image
            assertArrayEquals(expected, downloadBytes(attachment.downloadUrl))
            attachmentResults.put(JSONObject().put("attachmentId", attachment.id).put("originalName", attachment.originalName)
                .put("kind", attachment.kind).put("bytes", expected.size)
                .put("sha256", MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }))
        }
        report.put("attachments", attachmentResults)
        record("two-attachments-body-references-and-authenticated-bytes-persisted")
        saveScreenshot("saved")
    }

    /** 新增 name 对应的系统 Downloads 文件，mime 指定类型，bytes 是内容，返回可由 DocumentsUI 读取的 URI。 */
    private fun createSystemFile(name: String, mime: String, bytes: ByteArray): Uri {
        // 使用系统 MediaStore 不申请所有文件权限，失败不删除用户 Downloads 中的目标文件。
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        return uri
    }

    /** 等待实际 Compose text 可用，系统窗口切换时无语义根继续等待。 */
    private fun waitText(text: String) {
        // 等待同时推进 Compose 时钟，避免页面恢复后的首帧未重组。
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待真实 DocumentsUI 到达前台，不把替代提供器或主应用界面误认为选择器。 */
    private fun waitSystemPackage() {
        // 此处为系统窗口，仅轮询实时包名，无需推动 Compose 业务时钟。
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (automation.rootInActiveWindow?.packageName?.toString() == "com.google.android.documentsui") return
            SystemClock.sleep(300)
        }
        error("Real DocumentsUI did not become visible")
    }

    /** 等待选择器中真实 filename 节点，缺少文件时保留窗口供定位而不伪造回调。 */
    private fun systemFile(filename: String): AccessibilityNodeInfo {
        // 文件名具有独立随机后缀，不能选到已有用户文件或历史测试文件。
        val deadline = SystemClock.elapsedRealtime() + 30_000
        do {
            systemNodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == filename }?.let { return it }
            SystemClock.sleep(300)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("System file not visible: $filename")
    }

    /** 对 node 所在系统文档项执行真实触摸，longPress 区分进入多选与追加选择。 */
    private fun pressDocument(node: AccessibilityNodeInfo, longPress: Boolean) {
        // Android 15 选择器不导出文档长按动作，由 UiAutomator 在当前真实名称节点上执行标准触摸。
        val filename = requireNotNull(node.text).toString()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val document = requireNotNull(device.wait(Until.findObject(By.pkg("com.google.android.documentsui").text(filename)), 15_000))
        if (longPress) document.longClick() else document.click()
        device.waitForIdle(3_000)
    }

    /** 查找当前多选界面的实际确认按钮，仅接受系统菜单选择/打开操作。 */
    private fun systemConfirm(): AccessibilityNodeInfo {
        // 文案覆盖系统中英文，仍以当前可见且可点击的 DocumentsUI 节点为准。
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            systemNodes().firstOrNull { node ->
                node.isVisibleToUser && node.isClickable && (node.viewIdResourceName?.endsWith("/action_menu_select") == true ||
                    node.viewIdResourceName?.endsWith("/action_menu_open") == true ||
                    node.text?.toString() in setOf("选择", "打开", "Select", "Open"))
            }?.let { return it }
            SystemClock.sleep(300)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("System multi-select confirmation unavailable")
    }

    /** 返回当前 DocumentsUI 的真实节点，主应用窗口时返回空列表。 */
    private fun systemNodes(): List<AccessibilityNodeInfo> {
        // 只遍历实时前台系统子树，不读取其它应用的账号或敏感输入。
        val root = automation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != "com.google.android.documentsui") return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            nodes.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return nodes
    }

    /** 输出当前系统选择器的非认证节点，保留定位多选失败所需的资源 ID 和文案。 */
    private fun windowNodes(): JSONArray {
        // 文件选择器只含本次文件记录，主应用或其它应用窗口不导出。
        return JSONArray().apply { systemNodes().forEach { node -> put(JSONObject()
            .put("id", node.viewIdResourceName).put("text", node.text?.toString()).put("description", node.contentDescription?.toString())
            .put("clickable", node.isClickable).put("longClickable", node.isLongClickable).put("selected", node.isSelected)) } }
    }

    /** 通过真实当前账号下载 path 附件，返回成功响应字节用于与系统源对照。 */
    private fun downloadBytes(path: String): ByteArray {
        // 固定来源并拒绝重定向，不向日志写入任何认证值。
        require(path.startsWith('/') && !path.startsWith("//"))
        val connection = java.net.URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    /** 记录通过的 check，保存即时进度，不将未执行步骤计为通过。 */
    private fun record(check: String) {
        // 同时保留系统文件和文章业务标识，用户可通过本地网页对照记录。
        checks.put(JSONObject().put("check", check).put("passed", true))
        writeReport()
    }

    /** 保存当前报告，不覆盖其它专项和旧失败记录。 */
    private fun writeReport() {
        // 每个检查点都对应实际断言，系统选择成功不能替代 API 保存或字节校验。
        report.put("checks", checks)
        File(context.filesDir, "native-local-multiselect-report.json").writeText(report.toString(2))
    }

    /** 保存 stage 对应的真实设备截图，记录选择器和编辑页最终状态。 */
    private fun saveScreenshot(stage: String) {
        // 使用真实系统画面，不生成替代界面或截图来支持验收。
        automation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "native-local-multiselect-$stage.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
