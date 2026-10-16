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

/** 在实际富文本 WebView 验证原生命令和结构化操作共享撤销顺序，不替换生产编辑逻辑。 */
@RunWith(AndroidJUnit4::class)
class NativeRichEditorHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var editor: WebView
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 从 root 中找到实际编辑 WebView，没有时返回 null。 */
    private fun findEditor(root: View): WebView? {
        // 只遍历本测试 Activity，不访问其它应用或业务会话。
        if (root is WebView) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findEditor(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    /** 在主线程执行 script 并返回真实网页的 JSON 编码回包，timeoutMs 仅放宽大正文压力步骤的旧 WebView 等待。 */
    private fun evaluate(script: String, timeoutMs: Long = 10_000): String {
        // 渲染线程异步回调必须实际到达，不能把脚本已提交当作执行成功。
        val done = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread { editor.evaluateJavascript(script) { result = it; done.countDown() } }
        check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "History WebView callback timed out" }
        return result
    }

    /** 打开 html 对应的生产组件，并等待脚本及代码块控件就绪。 */
    private fun open(html: String) {
        // 使用文章与模板的同一编辑组件，组件测试不发起业务写请求。
        compose.setContent {
            HlovetTheme {
                NativeRichEditorWebView(content = html, onContentChange = {}, onResourceRequest = {},
                    visualInsertRequest = null, onVisualInsertHandled = {}, modifier = Modifier.fillMaxSize())
            }
        }
        compose.runOnIdle { editor = requireNotNull(findEditor(compose.activity.window.decorView)) }
        compose.waitUntil(20_000) { evaluate("typeof window.readEditorContent === 'function'") == "true" }
        if (html.contains("<pre")) compose.waitUntil(20_000) {
            evaluate("!!document.querySelector('#editor .native-code-card-language') && !!window.HlovetSyntax") == "true"
        }
    }

    /** 将光标或选区放到 selector 的首个文本节点；end 默认等于 start，为折叠光标。 */
    private fun select(selector: String, start: Int, end: Int = start) {
        // 实际 selectionchange 用于保存后续工具栏操作应恢复的正文选区。
        compose.runOnUiThread { editor.requestFocus() }
        val selected = evaluate("""
            (function() {
                document.getElementById('editor').focus();
                var element=document.querySelector(${JSONObject.quote(selector)});
                var node=element.firstChild || element;
                var range=document.createRange(); range.setStart(node,$start); range.setEnd(node,$end);
                var selection=window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
                document.dispatchEvent(new Event('selectionchange'));
                return selection.anchorNode === node && selection.getRangeAt(0).startOffset === $start && selection.getRangeAt(0).endOffset === $end;
            })();
        """.trimIndent())
        assertEquals("Requested test selection must exist before sending keys", "true", selected)
        compose.waitForIdle()
    }

    /** 通过实际工具栏执行 command，不直接调用浏览器的撤销 API。 */
    private fun command(command: String) {
        // 生产按钮应负责把自定义 DOM 操作和普通输入放在同一历史中。
        evaluate("document.querySelector('[data-command=${command}]').click();")
    }

    /** 用真实 Android 输入键入 ascii，并等待 expected 出现在正文；不模拟 input 回调。 */
    private fun type(ascii: String, expected: String) {
        // 等待默认编辑完成，不能只等待按键已投递。
        compose.waitUntil(10_000) {
            var focused = false
            compose.runOnUiThread { focused = editor.hasFocus() && editor.hasWindowFocus() }
            focused && evaluate("document.activeElement === document.getElementById('editor') && document.hasFocus()") == "true"
        }
        InstrumentationRegistry.getInstrumentation().sendStringSync(ascii)
        try {
            compose.waitUntil(10_000) { evaluate("document.getElementById('editor').textContent === ${JSONObject.quote(expected)}") == "true" }
        } catch (error: Throwable) {
            // 输入失败时保留真实正文，不能把焦点或投递失败通过重试掩盖。
            snapshot("typing-timeout")
            throw error
        }
    }

    /** 读取与生产提交相同的净化 HTML，并保留 name 对应诊断片段。 */
    private fun snapshot(name: String): String {
        // 不保存会话或用户资料，只保存该测试独立正文。
        val html = sanitizeNativeArticleHtml(JSONTokener(evaluate("window.readEditorContent()" )).nextValue() as String)
        File(context.filesDir, "native-editor-history-$name.html").writeText(html)
        return html
    }

    /** 任务拆分应能整体撤销、重做，并恢复拆分前的光标位置。 */
    @Test fun taskSplitUndoRedoRestoresBodyAndCaret() {
        // 真实 Android 回车先生成两个任务，撤销不能只处理浏览器默认输入。
        val original = buildNativeTaskListHtml(listOf("alpha beta"))
        open(original)
        select("#editor li > div p", 6)
        evaluate("window.historyEnterSeen=0; document.addEventListener('keydown',function(e){if(e.key==='Enter')window.historyEnterSeen++;},true);")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(10_000) { evaluate("window.historyEnterSeen > 0") == "true" }
        assertEquals("2", evaluate("document.querySelectorAll('#editor li').length"))
        command("undo")
        assertEquals(sanitizeNativeArticleHtml(original), snapshot("task-undo"))
        assertEquals("6", evaluate("window.getSelection().anchorOffset"))
        command("redo")
        assertEquals("2", evaluate("document.querySelectorAll('#editor li > label > input').length"))
        assertTrue(snapshot("task-redo").contains("beta"))
    }

    /** 代码语言变更应可撤销，并且还原后卡片控件和代码高亮仍可使用。 */
    @Test fun codeLanguageChangeParticipatesInUndoHistory() {
        // 初始 JSON 代码不是本次输入，不能以浏览器自带文本撤销代替语言属性回退。
        open("<pre data-language=\"json\"><code class=\"language-json\">{\"answer\":42}</code></pre>")
        select("#editor pre > code", 3)
        evaluate("var language=document.querySelector('.native-code-card-language');language.value='javascript';language.dispatchEvent(new Event('change'));")
        assertEquals("\"javascript\"", evaluate("document.querySelector('#editor pre').getAttribute('data-language')"))
        command("undo")
        assertEquals("\"json\"", evaluate("document.querySelector('#editor pre').getAttribute('data-language')"))
        assertEquals("\"json\"", evaluate("document.querySelector('.native-code-card-language').value"))
        command("redo")
        assertEquals("\"javascript\"", evaluate("document.querySelector('.native-code-card-language').value"))
        assertFalse(snapshot("language-redo").contains("native-code-card"))
    }

    /** 资源或 AI 插入的正文应作为独立一步撤销，重做不能丢失资源属性。 */
    @Test fun bridgedHtmlInsertionIsUndoableAndRedoable() {
        // 使用生产桥接入口插入资源，不在测试中直接删除或恢复节点。
        open("<p>before</p>")
        select("#editor p", 6)
        evaluate("window.insertEditorHtml('<resource-block data-points=\"3\"><p>paid body</p></resource-block>');")
        assertEquals("1", evaluate("document.querySelectorAll('#editor resource-block').length"))
        command("undo")
        assertEquals("<p>before</p>", snapshot("resource-undo"))
        command("redo")
        assertEquals("\"3\"", evaluate("document.querySelector('#editor resource-block').getAttribute('data-points')"))
        assertTrue(snapshot("resource-redo").contains("paid body"))
    }

    /** 原生命令与任务包装应按整次操作撤销，不能回到只有一半转换的节点。 */
    @Test fun formattingAndTaskConversionShareOneOrderedHistory() {
        // 加粗、转任务和勾选都是不同业务动作，连续三次撤销必须依次还原。
        open("<p>alpha</p>")
        select("#editor p", 0, 5)
        command("bold")
        val bold = snapshot("mixed-bold")
        assertTrue(bold.contains("<b>") || bold.contains("<strong>"))
        evaluate("document.getElementById('insert-task-list').click();")
        assertEquals("1", evaluate("document.querySelectorAll('#editor li[data-type=taskItem]').length"))
        evaluate("document.querySelector('#editor input[type=checkbox]').click();")
        command("undo")
        assertEquals("false", evaluate("document.querySelector('#editor input[type=checkbox]').checked"))
        command("undo")
        assertEquals(bold, snapshot("mixed-task-undo"))
        command("undo")
        assertEquals("<p>alpha</p>", snapshot("mixed-bold-undo"))
        repeat(3) { command("redo") }
        assertEquals("true", evaluate("document.querySelector('#editor input[type=checkbox]').checked"))
        assertTrue(snapshot("mixed-redo").contains("data-checked=\"true\""))
    }

    /** 普通键入可成组撤销，撤销后新输入必须让旧重做分支失效。 */
    @Test fun nativeTypingGroupsAndNewInputInvalidatesRedo() {
        // 真正的 Android 按键验证 beforeinput/input，不借助脚本直接拼接文字。
        open("<p>seed</p>")
        select("#editor p", 4)
        type("ab", "seedab")
        command("undo")
        assertEquals("<p>seed</p>", snapshot("typing-undo"))
        command("redo")
        assertEquals("<p>seedab</p>", snapshot("typing-redo"))
        command("undo")
        type("x", "seedx")
        command("redo")
        assertEquals("<p>seedx</p>", snapshot("typing-new-branch"))
    }

    /** 用户移动光标后再输入应开始新步骤，不能连同原位置的文字一起撤销。 */
    @Test fun caretMovementEndsTypingGroup() {
        // 两次输入没有人为等待分组超时，只由真实选区变化区分。
        open("<p>seed</p>")
        select("#editor p", 4)
        type("ab", "seedab")
        select("#editor p", 0)
        type("x", "xseedab")
        command("undo")
        assertEquals("<p>seedab</p>", snapshot("caret-group-undo"))
        assertEquals("0", evaluate("window.getSelection().anchorOffset"))
        command("undo")
        assertEquals("<p>seed</p>", snapshot("caret-group-original"))
    }

    /** 格式化真实代码后能够整体撤销和重做，重建的控件仍指向当前代码。 */
    @Test fun formattedCodeIsUndoableAndControlsRemainUsable() {
        // 走生产格式化按钮及本地格式化器，等待实际结果而非手写格式化回包。
        open("<pre data-language=\"json\"><code class=\"language-json\">{\"answer\":42}</code></pre>")
        select("#editor pre > code", 3)
        val original = snapshot("format-before")
        evaluate("document.querySelector('.native-code-card-format').click();")
        compose.waitUntil(20_000) { evaluate("document.querySelector('.native-code-card-status').classList.contains('success')") == "true" }
        val formatted = snapshot("format-after")
        assertNotEquals(original, formatted)
        command("undo")
        assertEquals(original, snapshot("format-undo"))
        assertEquals("3", evaluate("window.getSelection().anchorOffset"))
        assertEquals("\"CODE\"", evaluate("window.getSelection().anchorNode.parentElement.tagName"))
        command("redo")
        assertEquals(formatted, snapshot("format-redo"))
        evaluate("var language=document.querySelector('.native-code-card-language');language.value='javascript';language.dispatchEvent(new Event('change'));")
        command("undo")
        assertEquals("\"json\"", evaluate("document.querySelector('.native-code-card-language').value"))
    }

    /** 正文元素边界位于代码块之后时，撤销应恢复到卡片后而非卡片标题内部。 */
    @Test fun elementBoundarySurvivesCodeCardReconstruction() {
        // 选区位于 editor 的子节点间隙，不等价于某个文本节点的字符偏移。
        open("<pre data-language=\"json\"><code class=\"language-json\">{}</code></pre><p>end</p>")
        evaluate("var body=document.getElementById('editor');body.focus();var range=document.createRange();range.setStart(body,1);range.collapse(true);window.getSelection().removeAllRanges();window.getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));")
        evaluate("window.insertEditorHtml('<p>inserted</p>');")
        command("undo")
        assertEquals("true", evaluate("window.getSelection().anchorNode === document.getElementById('editor')"))
        assertEquals("1", evaluate("window.getSelection().anchorOffset"))
        command("redo")
        assertEquals("\"inserted\"", evaluate("document.querySelector('#editor > p').textContent"))
        assertFalse(snapshot("element-boundary").contains("history-caret"))
    }

    /** 反向选区经过格式操作撤销后仍保持方向与字符范围。 */
    @Test fun backwardSelectionRestoresDirectionAndText() {
        // 反向选区不能只恢复相同文本而丢失键盘继续扩选的方向。
        open("<p>alpha</p>")
        select("#editor p", 1, 4)
        evaluate("var text=document.querySelector('#editor p').firstChild;window.getSelection().setBaseAndExtent(text,4,text,1);document.dispatchEvent(new Event('selectionchange'));")
        command("bold")
        command("undo")
        assertEquals("\"lph\"", evaluate("window.getSelection().toString()"))
        assertEquals("4", evaluate("window.getSelection().anchorOffset"))
        assertEquals("1", evaluate("window.getSelection().focusOffset"))
    }

    /** 外部来源替换正文建立新基线，旧文章及之前的重做内容不得泄入新正文。 */
    @Test fun externalContentReplacementResetsUndoAndRedo() {
        // 模拟源码切换或版本恢复的真实桥接入口，不销毁组件来绕过历史。
        open("<p>seed</p>")
        select("#editor p", 4)
        type("x", "seedx")
        command("undo")
        evaluate("window.setEditorContent('<p>replacement</p>');")
        command("undo")
        command("redo")
        assertEquals("<p>replacement</p>", snapshot("external-reset"))
    }

    /** 输入法组合中的中间候选不会形成独立历史，已确认的整段文本可以一步撤销。 */
    @Test fun compositionCommitsOneHistoryStep() {
        // 此项验证组合事件分支，不宣称覆盖每种真实中文输入法。
        open("<p>seed</p>")
        select("#editor p", 4)
        evaluate("var body=document.getElementById('editor');body.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));document.execCommand('insertText',false,'你');document.execCommand('insertText',false,'好');body.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true,data:'你好'}));")
        command("undo")
        assertEquals("<p>seed</p>", snapshot("composition-undo"))
        command("redo")
        assertEquals("<p>seed你好</p>", snapshot("composition-redo"))
    }

    /** 浏览器历史事件与键盘快捷键共用同一栈，不调用隐藏的原生撤销分支。 */
    @Test fun historyBeforeInputAndKeyboardShareToolbarState() {
        // 使用资源插入确保原生浏览器自身没有足够历史可代替这次撤销。
        open("<p>seed</p>")
        select("#editor p", 4)
        evaluate("window.insertEditorHtml('<strong>x</strong>');")
        assertEquals("false", evaluate("document.getElementById('editor').dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'historyUndo'}))"))
        assertEquals("<p>seed</p>", snapshot("beforeinput-undo"))
        evaluate("document.getElementById('editor').dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,cancelable:true,key:'z',ctrlKey:true,shiftKey:true}));")
        assertTrue(snapshot("keyboard-redo").contains("<strong>x</strong>"))
    }

    /** 高频结构化操作的历史最多保留八十份，反复越界撤销或重做不改变边界正文。 */
    @Test fun historyCountIsBoundedWithoutBreakingRedoOrder() {
        // 85 次独立插入包含 86 份状态，裁掉最早六份后应停在第六次插入。
        open("<p>seed</p>")
        select("#editor p", 4)
        evaluate("for(var index=0;index<85;index++)window.insertEditorHtml('<span>x</span>');")
        evaluate("for(var index=0;index<100;index++)document.querySelector('[data-command=undo]').click();")
        assertEquals("6", evaluate("document.querySelectorAll('#editor span').length"))
        evaluate("for(var index=0;index<100;index++)document.querySelector('[data-command=redo]').click();")
        assertEquals("85", evaluate("document.querySelectorAll('#editor span').length"))
        snapshot("bounded-redo")
    }

    /** 资源解包应保留正文，撤销和重做同步恢复资源边界及积分属性。 */
    @Test fun resourceUnwrapRestoresAttributesAndSelection() {
        // 已有资源不依赖插入历史，以免只验证某个插入动作的偶然原生撤销。
        open("<p>before</p><resource-block data-points=\"7\"><p>paid body</p></resource-block><p>after</p>")
        select("#editor resource-block p", 3)
        evaluate("document.getElementById('remove-resource').click();")
        assertEquals("0", evaluate("document.querySelectorAll('#editor resource-block').length"))
        command("undo")
        assertEquals("\"7\"", evaluate("document.querySelector('#editor resource-block').getAttribute('data-points')"))
        assertEquals("3", evaluate("window.getSelection().anchorOffset"))
        assertEquals("true", evaluate("!!window.getSelection().anchorNode.parentElement.closest('resource-block')"))
        command("redo")
        assertEquals("0", evaluate("document.querySelectorAll('#editor resource-block').length"))
        assertTrue(snapshot("resource-unwrapped-redo").contains("paid body"))
    }

    /** 跨段删除的撤销恢复完整双端选区，不能把范围压缩为某个段落内的字符偏移。 */
    @Test fun crossParagraphDeletionRestoresRange() {
        // 使用真实删除按键消费跨节点选区，校验内容和选区两个维度。
        open("<p>alpha</p><p>beta</p>")
        select("#editor p", 2)
        evaluate("var paragraphs=document.querySelectorAll('#editor p');var range=document.createRange();range.setStart(paragraphs[0].firstChild,2);range.setEnd(paragraphs[1].firstChild,2);window.getSelection().removeAllRanges();window.getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
        compose.waitUntil(10_000) { evaluate("document.getElementById('editor').textContent === 'alta'") == "true" }
        command("undo")
        assertEquals("<p>alpha</p><p>beta</p>", snapshot("cross-paragraph-undo"))
        assertEquals("2", evaluate("window.getSelection().getRangeAt(0).startOffset"))
        assertEquals("2", evaluate("window.getSelection().getRangeAt(0).endOffset"))
        assertEquals("true", evaluate("window.getSelection().getRangeAt(0).startContainer.parentElement !== window.getSelection().getRangeAt(0).endContainer.parentElement"))
        command("redo")
        assertEquals("\"alta\"", evaluate("document.getElementById('editor').textContent"))
    }

    /** 大正文历史按八 MiB 的 UTF-16 字符预算淘汰旧状态，不仅限制记录份数。 */
    @Test fun largeDocumentHistoryAlsoRespectsMemoryBudget() {
        // 单份正文约二百 KiB，六十次操作未达八十份但应先触发体积上限。
        open("<p>" + "a".repeat(100_000) + "</p>")
        File(context.filesDir, "native-editor-history-large-initial-nodes.json").writeText(evaluate("JSON.stringify(Array.from(document.querySelector('#editor p').childNodes).map(function(node){return {type:node.nodeType,length:node.textContent.length};}))"))
        evaluate("var paragraph=document.querySelector('#editor p');paragraph.focus();document.getElementById('editor').focus();var range=document.createRange();range.selectNodeContents(paragraph);range.collapse(false);window.getSelection().removeAllRanges();window.getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));")
        evaluate("for(var index=0;index<60;index++)window.insertEditorHtml('<span>x</span>');")
        evaluate("for(var index=0;index<100;index++)document.querySelector('[data-command=undo]').click();", 120_000)
        assertTrue(evaluate("document.querySelectorAll('#editor span').length").toInt() > 0)
        snapshot("memory-bounded-undo")
        assertEquals("100000", evaluate("document.getElementById('editor').textContent.replace(/x/g,'').length"))
        evaluate("for(var index=0;index<100;index++)document.querySelector('[data-command=redo]').click();", 120_000)
        assertEquals("60", evaluate("document.querySelectorAll('#editor span').length"))
        snapshot("memory-bounded-redo")
    }
}
