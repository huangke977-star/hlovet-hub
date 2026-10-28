package xyz.hlovet.portal.prototype

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 核对真实 FileProvider、独立 UID 权限及系统文档写入，夹具不访问业务接口、不清理用户数据。 */
@RunWith(AndroidJUnit4::class)
class NativeAttachmentActionsTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var context: Context
    private lateinit var directory: File
    private var oldLocale = "zh-CN"

    /** 每条用例只创建独立媒体夹具目录，保留用户会话、缓存和数据库记录。 */
    @Before fun setUp() {
        // 使用应用的真实缓存父目录，确保 FileProvider 的目录限制参与验证。
        context = compose.activity
        directory = File(context.cacheDir, "media-temp/actions-${UUID.randomUUID()}").apply { mkdirs() }
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 关闭测试组件并恢复原语言，不删除系统保存记录或其它用例证据。 */
    @After fun tearDown() {
        // 分离界面后再恢复共享外观，避免跨用例状态干扰。
        host.dispose()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
    }

    /** 返回独立夹具 attachment，原名称与私有磁盘名称刻意不同。 */
    private fun fixture(name: String = "测试 original.txt", mime: String = "text/plain"): NativeAttachmentFile {
        // 真实内容用于摘要与系统复制核对，不含凭据或用户文章。
        val file = File(directory, "opaque-cache-key.txt").apply { writeText("HLOVET attachment read fixture\n") }
        return NativeAttachmentFile(file, name, mime)
    }

    /** 真实提供器返回原名称、精确大小及可识别类型，同时 intent 只携带读取权限。 */
    @Test fun providerMetadataUsesOriginalName() {
        // 展示名来自元数据，不能泄漏摘要缓存名。
        val attachment = fixture()
        val intent = nativeAttachmentOpenIntent(context, attachment)
        val uri = requireNotNull(intent.data)
        context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(attachment.originalName, cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(attachment.file.length(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertEquals("text/plain", context.contentResolver.getType(uri))
        assertEquals("text/plain", intent.type)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    /** 目录外私有文件不因另存为或系统打开入口而被放行。 */
    @Test fun providerRejectsPrivateFilesOutsideMediaRoots() {
        // 只测试独立无敏感内容文件，不读取会话目录。
        val file = File(context.filesDir, "attachment-outside-${UUID.randomUUID()}.txt").apply { writeText("fixture") }
        assertThrows(IllegalArgumentException::class.java) {
            nativeAttachmentOpenIntent(context, NativeAttachmentFile(file, "outside.txt", "text/plain"))
        }
    }

    /** 独立 UID 查看器可读取明确授予的 URI，仍无写入权限。 */
    @Test fun externalViewerReadsOnlyGrantedAttachment() {
        // 真正启动测试 APK Activity，不以本应用 ContentResolver 读取替代跨进程授权。
        val attachment = fixture()
        val result = externalViewer(nativeAttachmentOpenIntent(context, attachment))
        assertNull(result.getStringExtra("error"))
        assertNotEquals(Process.myUid(), result.getIntExtra("uid", -1))
        assertEquals(attachment.originalName, result.getStringExtra("name"))
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(attachment.file.readBytes()), result.getByteArrayExtra("digest"))
        assertFalse(result.getBooleanExtra("writeGranted", true))
    }

    /** 去掉临时授权后独立 UID 无法读取 FileProvider，验证授权不是仅写在 intent 上。 */
    @Test fun externalViewerWithoutGrantIsRejected() {
        // 使用全新 URI，避免另一个用例尚未回收的 grant 影响拒绝分支。
        val request = nativeAttachmentOpenIntent(context, fixture()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            clipData = null
        }
        assertEquals("SecurityException", externalViewer(request).getStringExtra("error"))
    }

    /** 将附件复制到真实 MediaStore 文档并回读字节，系统保存记录保留供复核。 */
    @Test fun savesExactBytesToSystemDocument() = runBlocking {
        // 文件仅放在测试专用 Downloads 子目录，不需要存储空间授权。
        val attachment = fixture()
        val name = "attachment-actions-${UUID.randomUUID()}.txt"
        val uri = newDocument(name)
        assertEquals(attachment.file.length(), saveNativeAttachment(context, attachment, uri))
        assertArrayEquals(attachment.file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(name, cursor.getString(0))
        }
    }

    /** 缺失及超大小源在打开输出前拒绝，不能覆盖已选择的目标文件。 */
    @Test fun rejectsInvalidSourceBeforeOverwritingDestination() = runBlocking {
        // 先保存识别内容，验证失败分支保持目标文件未变。
        val uri = newDocument("attachment-reject-${UUID.randomUUID()}.txt")
        val expected = "destination remains".toByteArray()
        context.contentResolver.openOutputStream(uri)!!.use { it.write(expected) }
        val missing = NativeAttachmentFile(File(directory, "missing.txt"), "missing.txt", "text/plain")
        assertTrue(runCatching { saveNativeAttachment(context, missing, uri) }.isFailure)
        val large = File(directory, "oversized.txt")
        RandomAccessFile(large, "rw").use { it.setLength(50L * 1024 * 1024 + 1) }
        assertTrue(runCatching { saveNativeAttachment(context, NativeAttachmentFile(large, "large.txt", "text/plain"), uri) }.isFailure)
        assertArrayEquals(expected, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    /** 超出持久预算的真实流式下载保留类型后缀，仍不进入 32 MiB 永久媒体缓存。 */
    @Test fun largeTemporaryDownloadKeepsMimeType() {
        // 使用独立缓存上下文及随机本机服务，不淘汰已保存的真实用户媒体。
        val isolated = object : ContextWrapper(context) {
            /** 将测试的持久文件放在独立目录，避免预算淘汰其它账号缓存。 */
            override fun getFilesDir(): File = File(directory, "isolated-files").apply { mkdirs() }
            /** 将测试临时文件放在 FileProvider 已允许的媒体子目录。 */
            override fun getCacheDir(): File = File(directory, "isolated-cache").apply { mkdirs() }
        }
        MockWebServer().use { server ->
            val body = Buffer()
            repeat(33) { body.write(ByteArray(1024 * 1024) { 65 }) }
            server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "text/plain"))
            val file = NativeOfflineCache.loadMediaFile(isolated, server.url("/large").toString(), cacheOwner = "actions-fixture", originalName = "large.txt", mimeType = "text/plain", apiBaseUrl = server.url("/").toString())!!
            assertEquals(33L * 1024 * 1024, file.length())
            assertTrue(file.name.endsWith(".tmp.txt"))
            assertEquals(0, NativeOfflineCache.mediaCacheSummary(isolated).fileCount)
            val uri = nativeAttachmentOpenIntent(context, NativeAttachmentFile(file, "large.txt", "text/plain")).data!!
            assertEquals("text/plain", context.contentResolver.getType(uri))
        }
    }

    /** 系统取消保存不显示成功，仍可再次选择保存目标。 */
    @Test fun cancelledSaveKeepsActionsAvailable() {
        // 只替代选择结果，其余真实弹窗及保存合同均参与验证。
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            /** 核对原名称及 MIME 后以取消结果回调，不实际创建文档。 */
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(context, input)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("测试 original.txt", intent.getStringExtra(Intent.EXTRA_TITLE))
                assertEquals("text/plain", intent.type)
                launches++
                compose.activity.window.decorView.post { dispatchResult<Uri?>(requestCode, null) }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val attachment = fixture()
        host.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                NativeAttachmentActionsDialog(attachment, onDismiss = {})
            }
        }
        compose.onNodeWithText("另存为").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("另存为").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(2, launches)
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("附件已保存到所选位置。")).fetchSemanticsNodes().isEmpty())
    }

    /** 没有查看器时明确提示，并保留另存为操作。 */
    @Test fun missingViewerShowsErrorAndSaveOption() {
        // 受控注入系统的 ActivityNotFound 分支，不修改模拟器的已安装应用。
        val unavailable = object : ContextWrapper(context) {
            /** 为 intent 模拟设备无查看器，验证页面是否给用户可执行的后续操作。 */
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("fixture") }
        }
        val attachment = fixture()
        host.setContent {
            CompositionLocalProvider(LocalContext provides unavailable) {
                NativeAttachmentActionsDialog(attachment, onDismiss = {})
            }
        }
        compose.onNodeWithText("打开").performClick()
        compose.onNodeWithText("设备上没有可以打开此附件的应用，仍可另存为。").assertIsDisplayed()
        compose.onNodeWithText("另存为").assertIsDisplayed()
    }

    /** 实际打开系统创建文档页并点击保存，核对成功反馈和系统 Downloads 中的字节。 */
    @Test fun systemSavePickerPersistsOriginalAttachment() {
        // 使用唯一名称避免覆盖用户文件，系统保留保存记录以供后续复核。
        val name = "hlovet-system-save-${UUID.randomUUID()}.txt"
        val attachment = fixture(name)
        // 前一条系统查看器用例可能把外部应用留在前台，先恢复宿主再查询 Compose 节点。
        if (device.currentPackageName != context.packageName) device.pressBack()
        compose.waitUntil(10_000) { device.currentPackageName == context.packageName && compose.activity.hasWindowFocus() }
        host.setContent { NativeAttachmentActionsDialog(attachment, onDismiss = {}) }
        compose.onNodeWithText("另存为").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        // 以实际系统视图 ID 定位保存按钮，不用固定坐标或伪造 ActivityResult。
        val saveButton = systemDocumentNode("android:id/button1")
        val title = systemDocumentNode("android:id/title", name)
        assertEquals(name, title.text.toString())
        assertTrue(saveButton.isEnabled)
        assertTrue(saveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        // 系统保存页关闭与宿主恢复是异步过程，先等真实宿主窗口恢复再查询 Compose 语义树。
        compose.waitUntil(45_000) {
            automation.rootInActiveWindow?.packageName?.toString() == context.packageName
        }
        compose.waitUntil(45_000) {
            // 首个恢复帧可能尚未登记语义根，此时继续等待，不能把空根当作保存成功。
            compose.onAllNodes(androidx.compose.ui.test.hasText("附件已保存到所选位置。")).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        // shell 仅回读本次唯一测试路径，避免用同 UID MediaStore 写入冒充系统选择器保存。
        val savedBytes = automation.executeShellCommand("cat /sdcard/Download/$name").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        assertArrayEquals(attachment.file.readBytes(), savedBytes)
        automation.takeScreenshot()?.let { image ->
            File(directory, "system-save-success.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    /** 实际启动已安装的图片查看器，检查画面中的绿色夹具，证明内容 URI 被系统应用解码。 */
    @Test fun systemImageViewerRendersCachedImage() {
        // 图片完全由夹具生成，不依赖用户图库、网络或测试查看器 Activity。
        val file = File(directory, "opaque-image-cache.png")
        Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        // 测试 Activity 本身就是 Activity，移除 NEW_TASK 让系统查看器返回同一任务，避免回到测试包空窗口。
        context.startActivity(nativeAttachmentOpenIntent(context, NativeAttachmentFile(file, "hlovet-system-viewer.png", "image/png")).apply {
            removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var green = 0
        do {
            val screenshot = automation.takeScreenshot()
            if (screenshot != null) {
                // 取真实屏幕像素，而不是只检查 Intent 或查看器 Activity 是否启动。
                val readable = screenshot.copy(Bitmap.Config.ARGB_8888, false)
                val pixels = IntArray(readable.width * readable.height)
                readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
                green = pixels.count { Color.green(it) > 230 && Color.red(it) < 30 && Color.blue(it) < 30 }
                File(directory, "system-viewer.png").outputStream().use { readable.compress(Bitmap.CompressFormat.PNG, 100, it) }
                readable.recycle()
                screenshot.recycle()
            }
            if (green >= 4_000) break
            SystemClock.sleep(1_000)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertTrue("Actual system viewer did not render fixture image ($green green pixels)", green >= 4_000)
        // 返回测试宿主并等待窗口恢复，不能把外部查看器状态混入下一条组件用例。
        device.pressBack()
        compose.waitUntil(10_000) { device.currentPackageName == context.packageName && compose.activity.hasWindowFocus() }
    }

    /** 在真实系统文件窗口等待 viewId 节点；expectedText 有值时要求精确匹配，超时不使用旧层级。 */
    private fun systemDocumentNode(viewId: String, expectedText: String? = null): AccessibilityNodeInfo {
        // 只接收 DocumentsUI 包内的新节点，应用弹窗中的同名节点不能替代系统证据。
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 45_000
        do {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull {
                it.packageName?.toString() == "com.google.android.documentsui" && it.isVisibleToUser &&
                    (expectedText == null || it.text?.toString() == expectedText)
            }?.let { return it }
            SystemClock.sleep(500)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("System document node unavailable: $viewId")
    }

    /** 用 name 创建真实系统文档，返回 URI；保留测试保存记录，不接触既有下载文件。 */
    private fun newDocument(name: String): Uri {
        // 测试文件明确标记独立目录，便于用户在系统文件管理器复核。
        return requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Hlovet-attachment-actions")
        }))
    }

    /** 用 request 启动独立 UID 测试查看器，等待其读取结果，不把外部读取替换为同 UID 调用。 */
    private fun externalViewer(request: Intent): Intent {
        // 随机回报 action 避免其它测试或系统广播混入本次证据。
        val latch = CountDownLatch(1)
        val action = "xyz.hlovet.attachment.${UUID.randomUUID()}"
        var result: Intent? = null
        val receiver = object : BroadcastReceiver() {
            /** 收到当前 action 的系统回报后唤醒用例，intent 只含名称及摘要。 */
            override fun onReceive(context: Context?, intent: Intent?) {
                result = intent
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        try {
            // 组件位于测试 APK，不共享应用 UID；实际 grant 由 Android Activity 启动流程授予。
            request.component = ComponentName(InstrumentationRegistry.getInstrumentation().context.packageName, NativeAttachmentReceiverActivity::class.java.name)
            request.putExtra("replyAction", action).putExtra("replyPackage", context.packageName)
            context.startActivity(request)
            assertTrue("External viewer did not report", latch.await(30, TimeUnit.SECONDS))
            return requireNotNull(result)
        } finally {
            // 仅撤销本条用例的广播监听，不清理用户文件或其它媒体授权。
            context.unregisterReceiver(receiver)
        }
    }
}
