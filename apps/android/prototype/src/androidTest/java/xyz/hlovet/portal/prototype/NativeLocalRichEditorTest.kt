package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实本地文章页面完成富文本修改、按钮保存、重开及预览，保留服务器测试记录。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class NativeLocalRichEditorTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private lateinit var session: NativeSession
    private lateinit var webView: WebView
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("apiBaseUrl", BuildConfig.API_BASE_URL).put("testDataRemoved", false)

    /** 只使用已保留的本地读者账号，禁止对生产地址或其他账号执行文章写入。 */
    @Before fun requireLocalAccount() {
        // 刷新现有加密会话，不注册新管理员、不更改用户权限。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        session = NativeAuthApi.refresh(stored.refreshToken)
        check(session.userId == stored.userId && NativeSessionStore.save(context, session))
        oldLocale = NativeI18n.locale
        instrumentation.runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("userId", session.userId)
    }

    /** 保存已经完成的检查点并关闭页面，保留业务数据及原有会话。 */
    @After fun finish() {
        // 测试中断也保存部分报告，不能将尚未执行的检查点记为成功。
        try {
            if (oldLocale != null) writeReport()
            activity?.close()
        } finally {
            oldLocale?.let { locale -> instrumentation.runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 验证任务及代码历史操作后仍可通过真实按钮保存，复杂内容重开和预览不丢失。 */
    @Test fun realEditorSavesAndReopensTaskSplitWithComplexHtml() {
        // 新建独立草稿，不覆盖已有文章；代码、表格仅验证保留，不新增 PC 没有的表格工具栏。
        val suffix = UUID.randomUUID().toString().take(8)
        val source = "<h2>Rich editor check</h2><p><strong>Bold</strong> <u>underlined</u> <a href=\"https://example.com\">link</a></p>" +
            buildNativeTaskListHtml(listOf("alpha beta")) +
            "<pre data-language=\"json\"><code class=\"language-json\">{\"answer\":42}</code></pre>" +
            "<table><tbody><tr><th>Column</th></tr><tr><td>Retained cell</td></tr></tbody></table>"
        val draft = NativeArticleApi.createArticle(session.accessToken, NativeDraftInput(
            "native_rich_editor_20261015_$suffix", "Android rich editor save and reopen verification", source, "html", "随笔", "",
        ))
        report.put("articleId", draft.id).put("articleSlug", draft.slug).put("title", draft.title)
        record("independent-html-draft-created")
        openEditor(draft.id)

        // 使用实际编辑 WebView 的选区与 Android 回车，不直接改写任务 HTML。
        instrumentation.runOnMainSync { webView.requestFocus() }
        evaluate("""
            (function() {
                var editor=document.getElementById('editor'); editor.focus();
                var node=editor.querySelector('li > div p').firstChild;
                var range=document.createRange(); range.setStart(node,6); range.collapse(true);
                var selection=window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
                window.localRichEnters=0;
                editor.addEventListener('keydown',function(event){if(event.key==='Enter')window.localRichEnters++;},true);
            })();
        """.trimIndent())
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(15_000) { evaluate("window.localRichEnters > 0 && document.querySelectorAll('#editor li > label > input').length === 2") == "true" }
        record("actual-editor-android-enter-created-two-checkbox-tasks")

        // 在真实业务页面撤销与重做任务拆分，不能只在独立组件里证明编辑历史有效。
        evaluate("document.querySelector('[data-command=undo]').click();")
        assertEquals("1", evaluate("document.querySelectorAll('#editor li > label > input').length"))
        assertEquals("\"alpha beta\"", evaluate("document.querySelector('#editor li > div p').textContent"))
        evaluate("document.querySelector('[data-command=redo]').click();")
        assertEquals("2", evaluate("document.querySelectorAll('#editor li > label > input').length"))
        record("real-page-task-undo-redo-restored-exact-task-structure")

        // 代码语言和异步插入与任务共用同一历史，最终保存的仍是用户最后恢复的版本。
        evaluate("var language=document.querySelector('.native-code-card-language');language.value='javascript';language.dispatchEvent(new Event('change'));document.querySelector('[data-command=undo]').click();")
        assertEquals("\"json\"", evaluate("document.querySelector('.native-code-card-language').value"))
        evaluate("var body=document.getElementById('editor');body.focus();var range=document.createRange();range.selectNodeContents(body);range.collapse(false);window.getSelection().removeAllRanges();window.getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));window.insertEditorHtml('<p>history insertion should not be saved</p>');document.querySelector('[data-command=undo]').click();")
        assertEquals("false", evaluate("document.getElementById('editor').textContent.includes('history insertion should not be saved')"))
        record("real-page-language-and-bridged-insertion-undone-before-save")
        screenshot("edited")
        instrumentation.runOnMainSync {
            // 收起键盘后由真实 Compose 保存按钮发起请求，而非直接调用更新 API。
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(webView.windowToken, 0)
            webView.clearFocus()
        }
        compose.onNodeWithText("保存草稿").assertIsEnabled().performClick()

        // 轮询服务端持久化结果；由数据库回读判断保存完成，不能只依赖成功文案。
        var saved: NativeDraftArticle? = null
        var lastRead = 0L
        compose.waitUntil(60_000) {
            if (SystemClock.elapsedRealtime() - lastRead > 700) {
                lastRead = SystemClock.elapsedRealtime()
                val value = NativeArticleApi.getMyArticle(session.accessToken, draft.id)
                if (Jsoup.parseBodyFragment(value.content).select("li[data-type=taskItem] > label > input").size == 2) saved = value
            }
            saved != null
        }
        val persisted = requireNotNull(saved)
        assertComplexBody(persisted.content)
        report.put("savedContent", persisted.content)
        record("real-save-button-persisted-complex-html-and-two-task-items")

        // 完全关闭并重新进入同一文章，确保不是只验证尚在内存中的编辑器。
        activity?.close(); activity = null
        openEditor(draft.id)
        val reopened = sanitizeNativeArticleHtml(JSONTokener(evaluate("window.readEditorContent()" )).nextValue() as String)
        assertComplexBody(reopened)
        assertEquals(Jsoup.parseBodyFragment(persisted.content).text(), Jsoup.parseBodyFragment(reopened).text())
        evaluate("document.querySelector('[data-command=undo]').click();document.querySelector('[data-command=redo]').click();")
        assertEquals(reopened, sanitizeNativeArticleHtml(JSONTokener(evaluate("window.readEditorContent()" )).nextValue() as String))
        compose.onNodeWithText("草稿已同步").assertExists()
        record("activity-reopened-saved-body-with-new-history-baseline-and-without-loss")
        screenshot("reopened")

        // 预览使用同一文章内容，检查真实预览 WebView 中的任务、代码和表格布局。
        compose.onNodeWithContentDescription("预览").performClick()
        waitText("文章预览")
        compose.waitUntil(15_000) { findActiveWebViews().any { evaluateIn(it, "!!document.querySelector('h1') && !!document.querySelector('table')") == "true" } }
        val preview = findActiveWebViews().first { evaluateIn(it, "!!document.querySelector('h1') && !!document.querySelector('table')") == "true" }
        assertEquals("2", evaluateIn(preview, "document.querySelectorAll('li > label > input').length"))
        assertEquals("true", evaluateIn(preview, "document.querySelector('pre code').textContent.includes('answer')"))
        screenshot("preview")
        record("real-preview-retained-task-code-and-table")
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.onNodeWithText("草稿已同步").assertExists()
        record("preview-without-edit-does-not-mark-saved-draft-dirty")
    }

    /** 打开 id 对应的真实文章编辑路由，并等待正文从服务器加载。 */
    private fun openEditor(id: Int) {
        // 深链由生产 MainActivity 分发，不注入替代页面或伪造保存回调。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/articles/edit/$id")))
        waitText("保存草稿")
        compose.waitUntil(30_000) {
            val editor = findActiveWebViews().firstOrNull { evaluateIn(it, "typeof window.readEditorContent === 'function'") == "true" }
            if (editor != null) webView = editor
            editor != null && evaluate("document.getElementById('editor').textContent.includes('alpha') && document.querySelectorAll('#editor .native-code-card-language').length === 1") == "true"
        }
    }

    /** 校验 html 的正文业务结构，忽略浏览器空格编码及不影响内容的属性顺序。 */
    private fun assertComplexBody(html: String) {
        // 按语义验证格式和结构，避免只用包含标题的字符串作为完整回读证据。
        val body = Jsoup.parseBodyFragment(html)
        assertEquals(listOf("alpha", "beta"), body.select("li > div p").map { it.text().trim() })
        assertEquals(2, body.select("li[data-checked=false] > label > input").size)
        assertEquals("Bold", body.selectFirst("strong")?.text())
        assertEquals("underlined", body.selectFirst("u")?.text())
        assertEquals("https://example.com", body.selectFirst("a")?.attr("href"))
        assertEquals("json", body.selectFirst("pre")?.attr("data-language"))
        assertEquals("{\"answer\":42}", body.selectFirst("pre code")?.wholeText())
        assertEquals("Retained cell", body.selectFirst("td")?.text())
        assertFalse(html.contains("native-code-card"))
    }

    /** 获取当前应用所有窗口中的 WebView，包含实际预览弹窗。 */
    private fun findActiveWebViews(): List<WebView> {
        // 弹窗是独立窗口，不能只扫描 Activity 根节点就漏掉预览。
        val views = mutableListOf<WebView>()
        instrumentation.runOnMainSync {
            WindowInspector.getGlobalWindowViews().forEach { root -> collectWebViews(root, views) }
        }
        return views
    }

    /** 递归收集 view 内的 WebView 到 result，不改变视图状态。 */
    private fun collectWebViews(view: View, result: MutableList<WebView>) {
        // 仅遍历已附着窗口，不创建新的 WebView 或读取外部应用页面。
        if (view is WebView) result.add(view)
        else if (view is ViewGroup) for (index in 0 until view.childCount) collectWebViews(view.getChildAt(index), result)
    }

    /** 在当前正文 WebView 执行 script，返回真实回包。 */
    private fun evaluate(script: String): String {
        // 所有正文读写都复用下方有界等待，不假设渲染线程同步完成。
        return evaluateIn(webView, script)
    }

    /** 在 view 执行 script，并有界等待网页脚本回包。 */
    private fun evaluateIn(view: WebView, script: String): String {
        // WebView 只能在主线程调用，但回包等待必须留在测试线程。
        val done = CountDownLatch(1)
        var value = "null"
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { value = it; done.countDown() } }
        check(done.await(10, TimeUnit.SECONDS)) { "Editor callback timed out" }
        return value
    }

    /** 等待页面实际显示 text，而非只等待网络返回。 */
    private fun waitText(text: String) {
        // 等待 Compose 重组，窗口切换的短暂无根状态不作为业务失败。
        compose.waitUntil(45_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 保存 name 对应的实际应用屏幕，不包含账号凭据。 */
    private fun screenshot(name: String) {
        // 证据保留在测试专用文件名中，不覆盖用户图片。
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(context.filesDir, "native-local-rich-editor-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    /** 已通过 check 检查后追加结果并保存进度。 */
    private fun record(check: String) {
        // 不预填尚未执行的成功记录。
        checks.put(JSONObject().put("check", check).put("passed", true)); writeReport()
    }

    /** 保存无密钥的本轮报告，保留文章供用户核对。 */
    private fun writeReport() {
        // 内容仅包含本测试的文章和检查点，不序列化会话。
        report.put("checks", checks)
        File(context.filesDir, "native-local-rich-editor-report.json").writeText(report.toString(2))
    }
}
