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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** 使用保留的本地账号及文章完成真实阅读页下载到系统保存，不创建、覆盖或删除业务数据。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalAttachmentDownloadTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("apiBaseUrl", BuildConfig.API_BASE_URL).put("articleId", 10).put("testDataRemoved", false)

    /** 限定精确的本地来源和保留账号，正式地址构建不得执行本场景。 */
    @Before fun requireLocalFixture() {
        // 不降级认证策略，复用当前加密会话；仅刷新这个已明确标记的测试账号。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        val refreshed = NativeAuthApi.refresh(stored.refreshToken)
        check(refreshed.userId == stored.userId && NativeSessionStore.save(context, refreshed))
        report.put("userId", refreshed.userId)
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 保留全部下载及报告，只关闭测试页面并恢复本次切换的语言。 */
    @After fun stop() {
        // 失败时也写下已实际通过的检查点，不能把未执行的步骤标为成功。
        try {
            if (oldLocale != null) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
                File(context.filesDir, "native-local-download-last-state.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            scenario?.close()
            if (oldLocale != null) writeReport()
        } finally {
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 打开已有文章真实链接，验证原名称提示和系统保存后的真实认证下载字节。 */
    @Test fun realArticleLinkDownloadsAndSavesThroughSystemPicker() {
        // 读取作者现有草稿及服务端附件元数据，不改文章状态或插入额外正文引用。
        val session = requireNotNull(NativeSessionStore.load(context))
        val draft = NativeArticleApi.getMyArticle(session.accessToken, 10)
        val attachment = draft.attachments.first { it.kind == "file" }
        report.put("attachmentId", attachment.id).put("originalName", attachment.originalName)
        val remote = NativeArticleApi.getArticle(session.accessToken, draft.slug)
        assertTrue(remote.content.contains(attachment.downloadUrl))
        record("existing-author-article-and-attachment-readable")

        // 通过 MainActivity 的真实来源校验及阅读路由进入，不直接挂载附件面板。
        val request = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/articles/${draft.slug}"))
        scenario = ActivityScenario.launch(request)
        var clicked = false
        // 使用 Compose 等待循环推进真实重组时钟；仅睡眠会使登录恢复后的界面一直停在首帧。
        compose.waitUntil(60_000) {
            val latch = CountDownLatch(1)
            scenario!!.onActivity { activity ->
                val webView = readingWebView(activity.window.decorView)
                if (webView == null) latch.countDown() else {
                    // 正文渲染会把相对附件地址规范为绝对 URL，按同一规则定位原链接并触发正式事件桥。
                    val script = "(function(){var a=Array.from(document.querySelectorAll('a')).find(function(x){return x.getAttribute('href')===" + JSONObject.quote(nativeMediaUrl(attachment.downloadUrl)) + ";});if(!a)return false;a.click();return true;})()"
                    webView.evaluateJavascript(script) { result -> clicked = result == "true"; latch.countDown() }
                }
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            clicked
        }
        assertTrue("Existing article attachment link was not found", clicked)
        compose.waitUntil(45_000) { compose.onAllNodes(hasText("另存为")).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithText(attachment.originalName).assertExists()
        record("main-activity-article-link-opened-shared-file-actions")

        // 系统首先必须显示服务端原文件名，再改为唯一测试名称，避免覆盖用户已有下载。
        compose.onNodeWithText("另存为").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val title = systemNode("android:id/title", attachment.originalName)
        val saveName = "hlovet-main-download-${UUID.randomUUID()}.txt"
        assertTrue(title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, saveName)
        }))
        assertTrue(systemNode("android:id/button1").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(45_000) { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
        compose.waitUntil(45_000) { compose.onAllNodes(hasText("附件已保存到所选位置。")).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        record("system-picker-displayed-original-name-and-save-succeeded")

        // 使用真实认证响应作为预期值，并在系统目录回读，不把缓存内容与自己比较当作下载验收。
        val connection = java.net.URL(BuildConfig.API_BASE_URL + attachment.downloadUrl).openConnection() as HttpURLConnection
        val expected = try {
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        val actual = automation.executeShellCommand("cat /sdcard/Download/$saveName").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        assertArrayEquals(expected, actual)
        report.put("savedName", saveName).put("bytes", actual.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(actual).joinToString("") { "%02x".format(it) })
        record("system-saved-file-matches-real-authenticated-response")
        automation.takeScreenshot()?.let { image ->
            File(context.filesDir, "native-local-download-success.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    /** 从 view 的真实子树寻找阅读 WebView；缺失时返回 null，等待真实页面挂载。 */
    private fun readingWebView(view: View): WebView? {
        // 不构造替代视图或调用下载函数，必须使用实际 Activity 中的阅读组件。
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) readingWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    /** 等待系统 DocumentsUI 的 viewId；text 有值时必须匹配原文件名。 */
    private fun systemNode(viewId: String, text: String? = null): AccessibilityNodeInfo {
        // 只使用本次实时的可见系统节点，不依赖固定坐标或先前保存的层级。
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

    /** 记录已通过的 check 并保存即时进度，不记录密码、令牌或附件原文。 */
    private fun record(check: String) {
        // 每个检查点必须在实际断言通过后写入，失败不能生成后续成功记录。
        checks.put(JSONObject().put("check", check).put("passed", true))
        writeReport()
    }

    /** 保存本次 report 和实际 checks，便于在失败或完整成功后核对覆盖范围。 */
    private fun writeReport() {
        // 测试证据独立于会话存储，不覆盖其它专项的报告。
        report.put("checks", checks)
        File(context.filesDir, "native-local-download-report.json").writeText(report.toString(2))
    }
}
