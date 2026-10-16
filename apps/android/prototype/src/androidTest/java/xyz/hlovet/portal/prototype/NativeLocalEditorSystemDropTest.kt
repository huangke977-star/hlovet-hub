package xyz.hlovet.portal.prototype

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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

/** 在真实 MainActivity、系统分屏和本地 API 中验证文件拖放上传，不替代系统 DragEvent 或上传回调。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NativeLocalEditorSystemDropTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private lateinit var session: NativeSession
    private val checks = JSONArray()
    private val report = JSONObject().put("apiBaseUrl", BuildConfig.API_BASE_URL).put("testDataRemoved", false)

    /** 仅复用本地账号 34 的已有会话，不绕过认证或对生产文章执行写入。 */
    @Before fun requireLocalSession() {
        // 精确来源匹配先于会话读取，测试 APK 即使装在正式版本也不能写生产数据。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        session = NativeAuthApi.refresh(stored.refreshToken)
        check(session.userId == stored.userId && NativeSessionStore.save(context, session))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("userId", session.userId)
    }

    /** 保存最后证据并关闭本测试窗口，保留草稿、附件、来源文件和登录会话。 */
    @After fun stop() {
        // 未满足本地条件的跳过用例不能触碰设备上其它页面或语言配置。
        if (!::session.isInitialized) return
        try {
            saveScreenshot("last-state")
            writeReport()
            context.startActivity(sourceIntent("close", ""))
            activity?.close()
        } finally {
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 拖入独立 UID 的文本文件，通过实际编辑页上传、保存，再核对服务端正文和认证下载字节。 */
    @Test fun realSystemDropUploadsSavesAndRefetchesActualFile() {
        // 新建专用草稿，不编辑或覆盖用户原文章；API 仅准备场景，不直接上传附件。
        val suffix = UUID.randomUUID().toString().take(8)
        val draft = NativeArticleApi.createArticle(session.accessToken, NativeDraftInput(
            "native_system_drop_20261015_$suffix", "Real cross-app Android drop verification", "<p>Original system drop body.</p>",
            "html", "随笔", "",
        ))
        val name = "native_system_local_$suffix.txt"
        val uri = Uri.parse("content://xyz.hlovet.portal.prototype.test.editor-files/$name")
        report.put("articleId", draft.id).put("articleSlug", draft.slug).put("title", draft.title).put("fileName", name)
        assertReadDenied(uri)
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/articles/edit/${draft.id}")))
        waitText("编辑方式")
        compose.onNodeWithText("编辑方式").performScrollTo()
        waitEditor()
        record("independent-local-draft-and-real-editor-ready")

        // Android 12L 以上原生相邻启动形成真实分屏，来源不提前 grant 也不发送假的浏览器事件。
        prepareSource(name)
        waitForSplitEditor()
        waitText("编辑方式")
        compose.onNodeWithText("编辑方式").performScrollTo()
        waitEditor()
        var point = dropPoint()
        if (point == null) {
            compose.onNodeWithText("分类").performScrollTo()
            point = dropPoint()
        }
        val actualPoint = requireNotNull(point) { "Actual rich editor body is outside the visible task" }
        report.put("dropPoint", JSONObject().put("x", actualPoint.x).put("y", actualPoint.y))
        saveScreenshot("before-drop")
        requireNotNull(device.wait(Until.findObject(By.text("拖放测试文件")), 10000)).drag(actualPoint, 1000)
        assertNotNull("Android did not deliver the real drop to the editor", device.wait(Until.findObject(By.text("拖放测试文件已交付")), 10000))
        waitText(name)
        record("actual-cross-app-system-drop-uploaded-through-editor")
        saveScreenshot("uploaded")

        // 只点页面真实保存按钮，随后按文章业务 ID 回读，不把上传回包当作持久化结果。
        compose.onNodeWithText("保存草稿").assertIsDisplayed().performClick()
        var saved: NativeDraftArticle? = null
        var lastRead = 0L
        compose.waitUntil(60000) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRead >= 700) {
                lastRead = now
                val current = NativeArticleApi.getMyArticle(session.accessToken, draft.id)
                if (current.attachments.size == 1 && current.content.contains(current.attachments.single().downloadUrl)) saved = current
            }
            saved != null
        }
        val actual = requireNotNull(saved)
        val attachment = actual.attachments.single()
        assertEquals(name, attachment.originalName)
        assertTrue(actual.content.contains("Original system drop body."))
        val expected = "Actual cross-app system file $name\n".toByteArray()
        assertArrayEquals(expected, downloadBytes(attachment.downloadUrl))
        report.put("attachmentId", attachment.id).put("bytes", expected.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) })
        record("saved-body-reference-and-authenticated-download-bytes-match-source")
        assertReadDenied(uri)
        record("temporary-source-read-grant-released-after-upload")
        saveScreenshot("saved")
    }

    /** 构造 mode/name 对应的测试来源入口，拖动使用原生相邻任务而非全屏透明遮罩。 */
    private fun sourceIntent(mode: String, name: String): Intent {
        // 只向独立测试包启动 Activity，不包含读取、写入或持久 URI 权限标志。
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            if (mode == "drag") Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0
        return Intent().setComponent(ComponentName("${context.packageName}.test", NativeEditorFileSourceActivity::class.java.name))
            .addFlags(flags).putExtra("mode", mode).putExtra("name", name)
    }

    /** 让独立来源生成 name 文件并报告就绪，广播只传元数据，不传内容或 grant。 */
    private fun prepareSource(name: String) {
        // 每次使用随机 action，避免其它用例的广播被当成本次准备完成。
        val action = "${context.packageName}.LOCAL_DROP_${UUID.randomUUID()}"
        val done = CountDownLatch(1)
        var failure: String? = null
        val receiver = object : BroadcastReceiver() {
            /** 接收 intent 的就绪状态；context 只是广播环境，不获取来源数据读取权限。 */
            override fun onReceive(context: Context?, intent: Intent?) {
                // 失败明确传给测试，不继续拖动一个尚不存在的文件。
                failure = intent?.getStringExtra("error")
                done.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
        try {
            // 相邻启动必须关联当前业务任务，Application 上下文启动会退化为遮挡目标的全屏任务。
            requireNotNull(activity).onActivity { current ->
                current.startActivity(sourceIntent("drag", name).putExtra("replyAction", action).putExtra("replyPackage", context.packageName))
            }
            assertTrue("Independent file source did not become ready", done.await(15, TimeUnit.SECONDS))
            assertNull(failure)
        } finally { context.unregisterReceiver(receiver) }
    }

    /** 等待相邻启动后的真实业务实例和尺寸稳定，避免取到系统即将销毁的旧编辑器坐标。 */
    private fun waitForSplitEditor() {
        // 不屏蔽 MainActivity 重建；只有当前实例、分屏状态和实际窗口尺寸连续稳定才继续操作。
        var lastActivity: MainActivity? = null
        var lastHeight = 0
        var stableSince = 0L
        compose.waitUntil(30000) {
            var stable = false
            activity?.onActivity { current ->
                val height = current.window.decorView.height
                if (current.isInMultiWindowMode && !current.isChangingConfigurations && height > 0) {
                    if (current !== lastActivity || height != lastHeight) {
                        lastActivity = current
                        lastHeight = height
                        stableSince = SystemClock.elapsedRealtime()
                    }
                    stable = SystemClock.elapsedRealtime() - stableSince >= 700
                } else stableSince = SystemClock.elapsedRealtime()
            }
            stable
        }
    }

    /** 返回正文当前真实可见区域的落点，正文完全被工具栏或任务边界裁切时返回 null。 */
    private fun dropPoint(): Point? {
        // 页面坐标、原生位置和系统可见任务边界必须同时满足，不能固定用设备中心。
        val bounds = JSONArray(evaluate("(function(){var b=document.getElementById('editor').getBoundingClientRect();return [b.left,b.top,b.right,b.bottom,window.devicePixelRatio];})()"))
        var result: Point? = null
        activity?.onActivity { current ->
            val editor = requireNotNull(findEditor(current.window.decorView))
            val origin = IntArray(2)
            val visible = Rect()
            editor.getLocationOnScreen(origin)
            if (editor.getGlobalVisibleRect(visible)) {
                val scale = bounds.getDouble(4)
                val body = Rect(origin[0] + (bounds.getDouble(0) * scale).toInt(), origin[1] + (bounds.getDouble(1) * scale).toInt(),
                    origin[0] + (bounds.getDouble(2) * scale).toInt(), origin[1] + (bounds.getDouble(3) * scale).toInt())
                if (body.intersect(visible) && body.height() > 40) result = Point(body.centerX(), body.centerY())
            }
        }
        return result
    }

    /** 等待 text 在真实业务语义树中出现，尺寸重建或暂时没有根时继续等待。 */
    private fun waitText(text: String) {
        // 不将网络请求数或其它来源窗口中的同名文本作为业务反馈。
        compose.waitUntil(60000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待新 Activity 中的实际编辑页面就绪，不沿用分屏重建前已销毁的 WebView。 */
    private fun waitEditor() {
        // ActivityScenario 每次取当前实例，保留正常业务重建行为而不修改 MainActivity 配置。
        compose.waitUntil(45000) { evaluate("typeof window.readEditorContent === 'function'") == "true" }
    }

    /** 在当前真实编辑器执行 script，返回浏览器结果；视图未恢复时返回 null 字符串供等待。 */
    private fun evaluate(script: String): String {
        // 只读取布局和正文，不派发 DOM paste/drop，也不直接调用上传桥接。
        val done = CountDownLatch(1)
        var result = "null"
        activity?.onActivity { current ->
            val editor = findEditor(current.window.decorView)
            if (editor == null) done.countDown() else editor.evaluateJavascript(script) { result = it; done.countDown() }
        } ?: done.countDown()
        assertTrue("Actual editor JavaScript did not respond", done.await(5, TimeUnit.SECONDS))
        return result
    }

    /** 从 view 查找真实富文本编辑器，未挂载或已销毁时返回 null。 */
    private fun findEditor(view: View): NativeEditorSystemWebView? {
        // 排除其它正文预览或背景 WebView，确保位置和保存断言属于实际编辑组件。
        if (view is NativeEditorSystemWebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let { return it }
        return null
    }

    /** 实际打开 uri 并要求系统拒绝，文件缺失或返回空流不能冒充权限回收成功。 */
    private fun assertReadDenied(uri: Uri) {
        // 私有来源的读取能力只来自本次系统拖放，没有任何长期或写入授权。
        try {
            context.contentResolver.openInputStream(uri)?.close()
            fail("Private source was readable without the system drop grant")
        } catch (_: SecurityException) { /* Android 权限拒绝是本断言唯一成功结果。 */ }
    }

    /** 使用真实账号认证下载 path 对应附件，返回字节用于与来源内容独立比较。 */
    private fun downloadBytes(path: String): ByteArray {
        // 仅访问固定本地 API 来源，不跟随重定向，也不输出认证信息。
        require(path.startsWith('/') && !path.startsWith("//"))
        val connection = java.net.URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    /** 记录已通过的 check 并即时保存，不把尚未执行的业务步骤列为通过。 */
    private fun record(check: String) {
        // 保留文章、附件业务 ID，供用户在本地网页核对同一份数据。
        checks.put(JSONObject().put("check", check).put("passed", true))
        writeReport()
    }

    /** 保存不含凭据的当前报告，失败也保留已执行范围和真实数据标识。 */
    private fun writeReport() {
        // 生成结果不覆盖历史文章或原测试文件。
        File(context.filesDir, "native-local-system-drop-report.json").writeText(report.put("checks", checks).toString(2))
    }

    /** 保存 suffix 对应的实际设备截图，不用旧图片或 DOM 构造图代替界面。 */
    private fun saveScreenshot(suffix: String) {
        // 截图仅来自测试业务页面，不导出令牌、密码或其它应用认证画面。
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(context.filesDir, "native-local-system-drop-$suffix.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
