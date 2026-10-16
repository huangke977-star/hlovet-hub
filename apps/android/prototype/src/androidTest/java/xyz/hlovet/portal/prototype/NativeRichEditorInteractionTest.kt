package xyz.hlovet.portal.prototype

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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在实际 Android WebView 中验证任务列表的键盘交互及序列化，不替换编辑器脚本。 */
@RunWith(AndroidJUnit4::class)
class NativeRichEditorInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var webView: WebView
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 查找 view 下当前编辑器 WebView；无匹配时返回 null。 */
    private fun findEditor(view: View): WebView? {
        // 只访问本测试 Activity 的视图，不切换应用会话或触碰其他页面。
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findEditor(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /** 在 WebView 主线程执行 script，并返回 JavaScript 的 JSON 编码结果。 */
    private fun evaluate(script: String): String {
        // 等待真实网页回包，禁止在尚未加载时使用预设成功结果。
        val completed = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread { webView.evaluateJavascript(script) { value -> result = value; completed.countDown() } }
        check(completed.await(10, TimeUnit.SECONDS)) { "WebView script timed out" }
        return result
    }

    /** 打开初始 html 对应的生产编辑组件，等待实际脚本就绪。 */
    private fun open(html: String) {
        // 组件使用与文章、模板相同的 WebView，测试不调用网络接口。
        compose.setContent {
            HlovetTheme {
                NativeRichEditorWebView(content = html, onContentChange = {}, onResourceRequest = {},
                    visualInsertRequest = null, onVisualInsertHandled = {}, modifier = Modifier.fillMaxSize())
            }
        }
        compose.runOnIdle { webView = requireNotNull(findEditor(compose.activity.window.decorView)) }
        compose.waitUntil(20_000) { evaluate("typeof window.readEditorContent === 'function'") == "true" }
        evaluate("window.nativeObservedEnters = 0; document.addEventListener('keydown', function(event) { if (event.key === 'Enter') window.nativeObservedEnters++; }, true);")
    }

    /** 将 selector 首个文本节点的光标移至 offset；不模拟任何列表结构转换。 */
    private fun caret(selector: String, offset: Int) {
        // 捕获实际选区供工具栏使用，再通过 Android 键盘事件触发浏览器默认输入。
        compose.runOnUiThread { webView.requestFocus() }
        evaluate("""
            (function() {
                var target = document.querySelector(${JSONObject.quote(selector)});
                var node = target.firstChild || target;
                document.getElementById('editor').focus();
                var range = document.createRange();
                range.setStart(node, $offset); range.collapse(true);
                var selection = window.getSelection();
                selection.removeAllRanges(); selection.addRange(range);
                document.dispatchEvent(new Event('selectionchange'));
            })();
        """.trimIndent())
        compose.waitForIdle()
    }

    /** 发送真实 Android 回车键，等待网页事件和默认输入完成。 */
    private fun enter() {
        // 不以 dispatchEvent 代替浏览器默认行为，确保能发现真实拆行错误。
        val previous = evaluate("window.nativeObservedEnters").toInt()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitUntil(10_000) { evaluate("window.nativeObservedEnters").toInt() > previous }
    }

    /** 读取当前编辑器的业务 HTML，同时保存 name 对应的诊断证据。 */
    private fun snapshot(name: String): String {
        // 证据只包含本测试文章片段，不含会话、令牌或本地凭据。
        val html = sanitizeNativeArticleHtml(JSONTokener(evaluate("window.readEditorContent()" )).nextValue() as String)
        File(context.filesDir, "native-rich-editor-$name.html").writeText(html)
        return html
    }

    /** 回车应在光标处分成两个独立任务，后半段保留格式并默认未完成。 */
    @Test fun enterSplitsTaskAndPreservesFollowingText() {
        // 从已完成任务中间回车，不能复制复选框或把两行归到同一任务。
        open(buildNativeTaskListHtml(listOf("alpha beta")))
        evaluate("document.querySelector('input[type=checkbox]').click();")
        caret("#editor li[data-type=taskItem] > div p", 6)
        enter()
        val html = snapshot("split")
        assertEquals(html, "2", evaluate("document.querySelectorAll('#editor li[data-type=taskItem]').length"))
        assertEquals("\"alpha \"", evaluate("document.querySelectorAll('#editor li[data-type=taskItem] > div')[0].textContent.replace(/\\u00a0/g, ' ')"))
        assertEquals("\"beta\"", evaluate("document.querySelectorAll('#editor li[data-type=taskItem] > div')[1].textContent"))
        assertEquals("\"true\"", evaluate("document.querySelectorAll('#editor li[data-type=taskItem]')[0].getAttribute('data-checked')"))
        assertEquals("\"false\"", evaluate("document.querySelectorAll('#editor li[data-type=taskItem]')[1].getAttribute('data-checked')"))
    }

    /** 顶层空任务回车应退出列表，保留前面的任务并创建普通正文段落。 */
    @Test fun enterOnEmptyTaskExitsToParagraph() {
        // 空任务不能继续生成只有复选框的空行，也不能丢失前后正常内容。
        open(buildNativeTaskListHtml(listOf("kept", "")) + "<p>after</p>")
        caret("#editor li[data-type=taskItem]:last-child > div p", 0)
        enter()
        val html = snapshot("exit")
        assertEquals(html, "1", evaluate("document.querySelectorAll('#editor li[data-type=taskItem]').length"))
        assertEquals("true", evaluate("window.getSelection().anchorNode.parentElement.closest('li') === null"))
        assertEquals("true", evaluate("document.getElementById('editor').textContent.includes('kept') && document.getElementById('editor').textContent.includes('after')"))
    }

    /** 嵌套空任务回车只提升一级，不能直接跳到整个任务列表外部。 */
    @Test fun enterOnNestedEmptyTaskOutdentsOneLevel() {
        // 使用已保存的标准嵌套结构验证 PC TaskItem 的提升语义。
        val nested = buildNativeTaskListHtml(listOf("parent")).replace("</div></li>", buildNativeTaskListHtml(listOf("")) + "</div></li>")
        open(nested)
        caret("#editor ul ul li > div p", 0)
        enter()
        val html = snapshot("outdent")
        assertEquals(html, "0", evaluate("document.querySelectorAll('#editor ul ul').length"))
        assertEquals("2", evaluate("document.querySelector('#editor > ul').children.length"))
        assertEquals("true", evaluate("!!window.getSelection().anchorNode.parentElement.closest('li[data-type=taskItem]')"))
    }

    /** 勾选和嵌套任务经过读取再加载仍应保留业务状态，且编辑辅助节点不进入正文。 */
    @Test fun checkboxAndNestedTasksSurviveRoundTrip() {
        // 从真实复选框交互开始，而不是仅断言预置 HTML 内的 data-checked。
        val nested = buildNativeTaskListHtml(listOf("parent")).replace("</div></li>", buildNativeTaskListHtml(listOf("child")) + "</div></li>")
        open(nested)
        evaluate("document.querySelector('#editor ul ul input').click();")
        val before = snapshot("roundtrip-before")
        evaluate("window.setEditorContent(${JSONObject.quote(before)});")
        val after = snapshot("roundtrip-after")
        assertEquals(before, after)
        assertEquals("\"true\"", evaluate("document.querySelector('#editor ul ul li').getAttribute('data-checked')"))
        assertEquals("true", evaluate("document.querySelector('#editor ul ul input').checked"))
        assertFalse(after.contains("native-code-card"))
    }

    /** 软键盘没有 keydown 时也通过同一换段处理，防止中文输入法再次破坏任务结构。 */
    @Test fun softKeyboardParagraphEventUsesTheSameTaskSplit() {
        // 此用例只验证 beforeinput 分支；真实 Android 回车由前面的用例独立覆盖。
        open(buildNativeTaskListHtml(listOf("alpha beta")))
        caret("#editor li > div p", 6)
        assertEquals("false", evaluate("document.getElementById('editor').dispatchEvent(new InputEvent('beforeinput', {bubbles:true, cancelable:true, inputType:'insertParagraph'}))"))
        val html = snapshot("soft-input")
        assertEquals(html, "2", evaluate("document.querySelectorAll('#editor li > label > input').length"))
        assertEquals("\"beta\"", evaluate("document.querySelectorAll('#editor li > div')[1].textContent"))
    }

    /** 中间的空任务退出时，后续任务仍位于新段落之后，不能调换业务顺序。 */
    @Test fun exitingMiddleTaskKeepsFollowingTasksAfterParagraph() {
        // 保留列表两侧的内容，防止退出操作把整个尾部列表挪到上方。
        open(buildNativeTaskListHtml(listOf("before", "", "after")))
        caret("#editor li:nth-child(2) > div p", 0)
        enter()
        val html = snapshot("middle-exit")
        assertEquals(html, "\"UL,P,UL\"", evaluate("Array.from(document.getElementById('editor').children).map(function(node){return node.tagName;}).join(',')"))
        assertEquals("\"before\"", evaluate("document.querySelectorAll('#editor > ul')[0].textContent"))
        assertEquals("\"after\"", evaluate("document.querySelectorAll('#editor > ul')[1].textContent"))
    }

    /** 拆分时保留前后文字的粗体与链接，不把 HTML 标签显示成普通文本。 */
    @Test fun splittingFormattedTaskPreservesInlineMarks() {
        // 后半段包含粗体和链接，应作为结构化片段移动，而不是 textContent 重建。
        open(buildNativeTaskListHtml(listOf("" )).replace("<p></p>", "<p>start <strong>bold tail</strong> <a href=\"https://example.com\">link</a></p>"))
        caret("#editor strong", 5)
        enter()
        val html = snapshot("formatted-split")
        assertEquals(html, "\"tail\"", evaluate("document.querySelectorAll('#editor li > div')[1].querySelector('strong').textContent"))
        assertEquals("\"https://example.com\"", evaluate("document.querySelectorAll('#editor li > div')[1].querySelector('a').getAttribute('href')"))
        assertEquals("2", evaluate("document.querySelectorAll('#editor li > label > input').length"))
    }

    /** 已有代码块首次打开也应显示语言和格式化控件，不要求用户先切换源码。 */
    @Test fun initialCodeBlockLoadsItsOwnControls() {
        // 等待真正的代码卡片，不能把已有 pre 的渲染当作控件已经加载。
        open("<pre data-language=\"json\"><code class=\"language-json\">{\"a\":1}</code></pre>")
        compose.waitUntil(15_000) { evaluate("document.querySelectorAll('#editor .native-code-card-language').length") == "1" }
        assertEquals("\"json\"", evaluate("document.querySelector('#editor .native-code-card-language').value"))
        assertEquals("1", evaluate("document.querySelectorAll('#editor .native-code-card-format').length"))
        assertFalse(snapshot("initial-code").contains("native-code-card"))
    }

    /** 任务回车仍应自动识别网址，不能因拆分规则而丢失原有自动链接能力。 */
    @Test fun taskEnterKeepsAutomaticLinkRecognition() {
        // 不预置 a 标签；由当前真实链接桥接校验网址后再拆分任务。
        open(buildNativeTaskListHtml(listOf("example.com")))
        caret("#editor li > div p", 11)
        enter()
        val html = snapshot("task-auto-link")
        assertEquals(html, "\"https://example.com\"", evaluate("document.querySelector('#editor li a').getAttribute('href')"))
        assertEquals("2", evaluate("document.querySelectorAll('#editor li > label > input').length"))
    }
}
