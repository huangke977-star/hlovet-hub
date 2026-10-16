package xyz.hlovet.portal.prototype

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Point
import android.net.Uri
import android.os.Process
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import androidx.test.filters.SdkSuppress
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用独立 UID 来源和真实系统输入验证剪贴板及拖放，不派发假的浏览器 paste/drop 事件。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NativeEditorSystemFileInputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private val context get() = compose.activity
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val scope = CoroutineScope(Dispatchers.IO)
    private val received = CopyOnWriteArrayList<NativeUploadFile>()
    private val failures = CopyOnWriteArrayList<String>()
    @Volatile private var dropReadGranted = false
    @Volatile private var dropWriteGranted = false
    @Volatile private var dropPermissionsReleased = false
    private var previousClip: ClipData? = null
    private lateinit var evidence: File

    /** 挂载实际编辑组件并保存原剪贴板，测试不连接 API 或覆盖真实草稿。 */
    @Before fun start() {
        // 所有文件来源均为测试应用，正文界面仍为实际业务 WebView。
        evidence = File(context.filesDir, "native-system-input-${UUID.randomUUID()}").apply { mkdirs() }
        compose.runOnUiThread { previousClip = context.getSystemService(ClipboardManager::class.java).primaryClip }
        host.setContent { HlovetTheme {
            NativeRichEditorWebView("<p>Existing editor body</p>", {}, {},
                onClipboardFile = { file, complete -> received.add(file); complete(true) },
                onClipboardFileRejected = { failures.add(it) },
                onSystemFiles = { uris, permissions ->
                    // 与真实编辑页相同，在 IO 线程读文件并在消费完成后释放系统拖放权限。
                    scope.launch {
                        try {
                            uris.forEach { uri ->
                                dropReadGranted = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                                dropWriteGranted = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                                received.add(readUploadFile(context, uri, 10L * 1024 * 1024))
                            }
                        } catch (error: Exception) { failures.add(error.javaClass.simpleName) }
                        finally { permissions?.release(); dropPermissionsReleased = true }
                    }
                },
                visualInsertRequest = null, onVisualInsertHandled = {}, modifier = Modifier.fillMaxSize())
        } }
        compose.waitUntil(20000) { evaluate("typeof window.readEditorContent === 'function'") == "true" }
        compose.runOnUiThread { assertTrue(findEditor(context.window.decorView) is NativeEditorSystemWebView) }
    }

    /** 保存最后画面并恢复原剪贴板，只回收本测试窗口和协程，不删除文件。 */
    @After fun stop() {
        // 失败也保留系统真实窗口，防止把未发生的输入误报为成功。
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(evidence, "last-state.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        context.startActivity(sourceIntent("close", ""))
        compose.runOnUiThread {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
        }
        scope.cancel()
        host.dispose()
    }

    /** 图片通过系统剪贴板和真实 Ctrl+V 交付实际字节，不能只插入 content URI 文本。 */
    @Test fun imageClipboardUsesSystemPasteAndActualFileBytes() {
        // 未授权来源即使知道地址也不能读取，系统粘贴是唯一交付入口。
        val name = "native_system_clip_${UUID.randomUUID().toString().take(8)}.png"
        val uri = fixtureUri(name)
        assertReadDenied(uri)
        val source = prepareSource("clipboard", name)
        assertNotEquals(Process.myUid(), source.getIntExtra("uid", -1))
        focusEditor()
        waitImageClipboardDescription()
        device.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
        compose.waitUntil(15000) { received.isNotEmpty() || failures.isNotEmpty() }
        assertTrue("Unexpected file rejection: $failures", failures.isEmpty())
        assertEquals(1, received.size)
        assertEquals("image/png", received.single().mimeType)
        assertEquals(source.getLongExtra("size", -1), received.single().bytes.size.toLong())
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(received.single().bytes, 0, received.single().bytes.size)
        assertNotNull(bitmap)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        bitmap.recycle()
        assertFalse(evaluate("window.readEditorContent()").contains("content://"))
        // 替换剪贴板后旧内容 grant 应被系统回收，不取得永久授权。
        compose.runOnUiThread { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fixture", "Replaced")) }
        assertReadDenied(uri)
    }

    /** 真正长按选区再点击系统粘贴菜单，图片文件仍只消费一次，菜单不能出现重复粘贴项。 */
    @Test fun imageClipboardUsesActualContextualPasteMenu() {
        // 独立来源仅通过系统剪贴板提供文件，不调用 InputConnection 或业务回调替代操作。
        val name = "native_system_menu_${UUID.randomUUID().toString().take(8)}.png"
        prepareSource("clipboard", name)
        focusEditor()
        waitImageClipboardDescription()
        val coordinates = JSONArray(evaluate("(function(){var e=document.getElementById('editor'),r=document.createRange();r.selectNodeContents(e.querySelector('p'));var b=r.getBoundingClientRect();return [b.left+20,b.top+b.height/2,window.devicePixelRatio];})()"))
        val location = IntArray(2)
        compose.runOnUiThread { requireNotNull(findEditor(context.window.decorView)).getLocationOnScreen(location) }
        val x = location[0] + (coordinates.getDouble(0) * coordinates.getDouble(2)).toInt()
        val y = location[1] + (coordinates.getDouble(1) * coordinates.getDouble(2)).toInt()
        device.swipe(x, y, x, y, 160)
        val paste = requireNotNull(device.wait(Until.findObject(By.text(context.getString(android.R.string.paste))), 10000))
        assertEquals(1, device.findObjects(By.text(context.getString(android.R.string.paste))).size)
        paste.click()
        compose.waitUntil(15000) { received.isNotEmpty() || failures.isNotEmpty() }
        assertTrue("Unexpected contextual rejection: $failures", failures.isEmpty())
        assertEquals(1, received.size)
        assertEquals(name, received.single().name)
        assertEquals("image/png", received.single().mimeType)
    }

    /** 普通文字沿用浏览器粘贴和安全转义，不误上传也不丢失原正文。 */
    @Test fun plainClipboardTextRetainsNativePasteBehavior() {
        // 只写入测试文本，真实按键由系统传给焦点 WebView。
        compose.runOnUiThread { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fixture", "SystemPaste<safe>")) }
        focusEditor()
        device.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
        compose.waitUntil(10000) { evaluate("window.readEditorContent()").contains("SystemPaste") }
        val html = evaluate("window.readEditorContent()")
        assertTrue(html.contains("Existing editor body"))
        assertTrue(html.contains("&lt;safe&gt;"))
        assertTrue(received.isEmpty())
    }

    /** 跨窗口实际拖放必须授予临时只读权限，消费完成后权限被释放。 */
    @Test fun externalDragUsesTemporaryReadGrantAndReleasesIt() {
        // 独立来源小窗口与真实编辑器共存，用系统指针手势完成全局拖动。
        val name = "native_system_drop_${UUID.randomUUID().toString().take(8)}.txt"
        val uri = fixtureUri(name)
        assertReadDenied(uri)
        val source = prepareSource("drag", name)
        assertNotEquals(Process.myUid(), source.getIntExtra("uid", -1))
        val dragSource = device.findObject(UiSelector().text("拖放测试文件"))
        assertTrue(dragSource.waitForExists(5000))
        val target = IntArray(2)
        compose.runOnUiThread { requireNotNull(findEditor(context.window.decorView)).getLocationOnScreen(target) }
        val bounds = JSONArray(evaluate("(function(){var b=document.getElementById('editor').getBoundingClientRect();return [b.left+b.width/2,b.top+Math.min(80,b.height/2),window.devicePixelRatio];})()"))
        // 真实正文落点由当前页面决定，不能落到编辑器工具栏、透明空白或其它窗口。
        val x = target[0] + (bounds.getDouble(0) * bounds.getDouble(2)).toInt()
        val y = target[1] + (bounds.getDouble(1) * bounds.getDouble(2)).toInt()
        requireNotNull(device.findObject(By.text("拖放测试文件"))).drag(Point(x, y), 1000)
        compose.waitUntil(15000) { dropPermissionsReleased || failures.isNotEmpty() }
        assertTrue("Unexpected drag rejection: $failures", failures.isEmpty())
        assertTrue(dropReadGranted)
        assertFalse(dropWriteGranted)
        assertTrue(dropPermissionsReleased)
        assertEquals(name, received.single().name)
        assertEquals("Actual cross-app system file $name\n", received.single().bytes.toString(Charsets.UTF_8))
        assertReadDenied(uri)
    }

    /** 返回 name 对应独立测试提供器地址，不包含读取权限。 */
    private fun fixtureUri(name: String): Uri {
        // 名称仅用于隔离本用例的文件，不能访问来源其它私有路径。
        return Uri.parse("content://xyz.hlovet.portal.prototype.test.editor-files/$name")
    }

    /** 为 mode 和 name 构造来源应用入口，不使用任何 URI 读取或写入授权标志。 */
    private fun sourceIntent(mode: String, name: String): Intent {
        // 源 Activity 必须由系统在测试包 UID 中运行，而不是目标应用内模拟。
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            if (mode == "drag") Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0
        return Intent().setComponent(ComponentName("${context.packageName}.test", NativeEditorFileSourceActivity::class.java.name))
            .addFlags(flags).putExtra("mode", mode).putExtra("name", name)
    }

    /** 让独立来源实际准备 mode/name 文件，返回系统广播的身份和元数据。 */
    private fun prepareSource(mode: String, name: String): Intent {
        // 随机 action 防止其它用例广播被误认为本次已准备完成。
        val action = "${context.packageName}.SYSTEM_INPUT_${UUID.randomUUID()}"
        val ready = CountDownLatch(1)
        var reply: Intent? = null
        val receiver = object : BroadcastReceiver() {
            /** 接收来源 intent，context 仅为广播环境，回报不包含文件内容或授权。 */
            override fun onReceive(context: Context?, intent: Intent?) {
                // 只记录本次随机 action 已过滤后的元数据。
                reply = intent
                ready.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
        try {
            context.startActivity(sourceIntent(mode, name).putExtra("replyAction", action).putExtra("replyPackage", context.packageName))
            assertTrue("Independent source did not report readiness", ready.await(10, TimeUnit.SECONDS))
            val result = requireNotNull(reply).also { assertFalse("Source failed: ${it.getStringExtra("error")}", it.hasExtra("error")) }
            if (mode == "clipboard") {
                // 真实点击来源窗口才能复制，不直接在目标 UID 中设置文件剪贴板。
                val copy = requireNotNull(device.wait(Until.findObject(By.text("复制测试文件")), 10000))
                copy.click()
                compose.waitUntil(10000) { device.currentPackageName == context.packageName }
            }
            return result
        } finally { context.unregisterReceiver(receiver) }
    }

    /** 让真正的 contenteditable 获得焦点和末尾选区，不派发粘贴或拖放 JavaScript 事件。 */
    private fun focusEditor() {
        // 原生焦点和页面选区都到位，系统快捷键才会进入实际编辑输入链。
        compose.runOnUiThread { requireNotNull(findEditor(context.window.decorView)).requestFocus() }
        compose.waitUntil(10000) { var focused = false; compose.runOnUiThread { focused = context.hasWindowFocus() }; focused }
        evaluate("(function(){var e=document.getElementById('editor');e.focus();var r=document.createRange();r.selectNodeContents(e);r.collapse(false);var s=window.getSelection();s.removeAllRanges();s.addRange(r);return document.activeElement===e;})()")
        compose.waitForIdle()
    }

    /** 等到目标窗口真正重新取得系统剪贴板描述的访问权，不通过读取文件提前授予 URI 权限。 */
    private fun waitImageClipboardDescription() {
        // 透明来源退出的第一帧可能仍由系统判为未聚焦，描述只用于等待真实前台权限到位。
        val clipboardReady = runCatching {
            compose.waitUntil(10000) {
                var ready = false
                compose.runOnUiThread { ready = context.hasWindowFocus() && context.getSystemService(ClipboardManager::class.java).primaryClipDescription?.hasMimeType("image/png") == true }
                ready
            }
        }.isSuccess
        if (!clipboardReady) {
            // 记录主机共享替换后的实际 MIME，区分模拟器前置条件与编辑器文件消费失败。
            var description = "unavailable"
            var windowFocused = false
            compose.runOnUiThread {
                windowFocused = context.hasWindowFocus()
                description = context.getSystemService(ClipboardManager::class.java).primaryClipDescription
                    ?.let { clip -> (0 until clip.mimeTypeCount).joinToString(",") { index -> clip.getMimeType(index) } }
                    ?: "none"
            }
            org.junit.Assume.assumeTrue(
                "Emulator clipboard did not retain image/png (windowFocused=$windowFocused, mimeTypes=$description); file paste is not verifiable in this host session.",
                clipboardReady,
            )
        }
    }

    /** 断言 uri 在没有系统 grant 时被 Android 框架拒绝，而不是仅检查标志位。 */
    private fun assertReadDenied(uri: Uri) {
        // 文件不存在和空内容都不是权限拒绝，必须实际捕获 SecurityException。
        try {
            context.contentResolver.openInputStream(uri)?.close()
            fail("Private source URI was readable without a grant")
        } catch (_: SecurityException) { /* Android 拒绝读取是本断言的唯一成功结果。 */ }
    }

    /** 执行 script 并返回真实 WebView 回包，尚无视图时返回 null 字符串供等待。 */
    private fun evaluate(script: String): String {
        // 不在主线程阻塞等待 JS，也不用生成 DOM 输入事件绕过系统授权。
        val done = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread {
            val view = findEditor(context.window.decorView)
            if (view == null) done.countDown() else view.evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue("Editor JavaScript did not respond", done.await(5, TimeUnit.SECONDS))
        return result
    }

    /** 从 view 查找实际编辑器，未挂载时返回 null，不使用其它应用的窗口替代。 */
    private fun findEditor(view: View): WebView? {
        // 只遍历本测试宿主的视图树，保持来源和目标窗口边界。
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let { return it }
        return null
    }
}
