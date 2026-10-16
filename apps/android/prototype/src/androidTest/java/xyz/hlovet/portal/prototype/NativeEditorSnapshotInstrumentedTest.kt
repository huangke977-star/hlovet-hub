package xyz.hlovet.portal.prototype

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEditorSnapshotInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val snapshot = NativeEditorSnapshot()
    private val visible = mutableStateOf(true)
    private var reportedContent = "<p>original body</p>"
    private lateinit var webView: WebView

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun evaluate(script: String): String {
        val completed = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread {
            webView.evaluateJavascript(script) { value -> result = value; completed.countDown() }
        }
        assertTrue("WebView JavaScript callback timed out", completed.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun openEditor() {
        compose.setContent {
            HlovetTheme {
                if (visible.value) {
                    NativeRichEditorWebView(
                        content = "<p>original body</p>",
                        onContentChange = { reportedContent = it },
                        onResourceRequest = {},
                        editorSnapshot = snapshot,
                        visualInsertRequest = null,
                        onVisualInsertHandled = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.runOnIdle {
            webView = requireNotNull(findWebView(compose.activity.window.decorView))
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            evaluate("typeof window.readEditorContent === 'function'") == "true"
        }
    }

    private fun capture(): String = runBlocking {
        withContext(Dispatchers.Main) { snapshot.capture() }
    }

    private fun selectText(selector: String, start: Int = 0, end: Int? = null) {
        evaluate("""
            (function() {
                var editor = document.getElementById('editor');
                var node = editor.querySelector(${JSONObject.quote(selector)}).firstChild;
                editor.focus();
                var range = document.createRange();
                range.setStart(node, $start);
                range.setEnd(node, ${end?.toString() ?: "node.length"});
                var selection = window.getSelection();
                selection.removeAllRanges();
                selection.addRange(range);
                document.dispatchEvent(new Event('selectionchange'));
            })();
        """.trimIndent())
    }

    private fun chooseFontSize(size: Int) {
        evaluate("""
            (function() {
                var select = document.getElementById('font-size');
                select.dispatchEvent(new MouseEvent('mousedown'));
                select.value = '${size}px';
                select.dispatchEvent(new Event('change'));
            })();
        """.trimIndent())
    }

    @Test
    fun actionReadsLiveBodyWithoutWaitingForDebouncedChangeNotification() {
        openEditor()
        val liveContent = "<p>just typed <strong>final character</strong></p>"
        evaluate("window.setEditorContent(${JSONObject.quote(liveContent)});")
        assertEquals("<p>original body</p>", reportedContent)
        assertEquals(liveContent, capture())
    }

    @Test
    fun clearingBodyDoesNotReuseThePreviousNonEmptyBody() {
        openEditor()
        evaluate("window.setEditorContent('');")
        assertEquals("", capture())
        assertEquals("<p>original body</p>", reportedContent)
    }

    @Test
    fun actionSnapshotUsesTheSameSanitizerAsSavedArticleHtml() {
        openEditor()
        val content = "<p onclick=\"unsafe()\">safe body</p><script>unsafe()</script>"
        evaluate("window.setEditorContent(${JSONObject.quote(content)});")
        val result = capture()
        assertTrue(result.contains("safe body"))
        assertFalse(result.contains("onclick"))
        assertFalse(result.contains("<script"))
    }

    @Test
    fun closingTheEditorInvalidatesItsSnapshotBinding() {
        openEditor()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        val result = runCatching { capture() }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun fontSizeChangeFormatsOnlyTheSelectionAndPersistsTheChosenSize() {
        openEditor()
        selectText("p", 0, 8)
        chooseFontSize(12)

        val html = capture()
        assertTrue("Chosen font size missing: $html", html.contains("font-size: 12px"))
        assertFalse("Legacy browser size leaked into saved HTML: $html", html.contains("48px"))
        assertTrue(html.contains("original</span> body"))
        assertEquals("\"original\"", evaluate("window.getSelection().toString()"))
    }

    @Test
    fun fontSizeAtCaretAppliesToNewInputAndSurvivesSnapshot() {
        openEditor()
        selectText("p", 8, 8)
        chooseFontSize(20)
        evaluate("document.execCommand('insertText', false, 'X');")

        val html = capture()
        assertTrue("Typed font size missing: $html", html.contains("font-size: 20px"))
        assertFalse(html.contains("48px"))
        assertEquals("\"originalX body\"", evaluate("document.getElementById('editor').textContent"))
        assertTrue(html.contains(">X</span>"))
    }

    @Test
    fun toolbarReflectsTheSelectedTextInsteadOfKeepingDefaultValues() {
        openEditor()
        evaluate("window.setEditorContent('<h2><strong><span style=\"font-size:20px\">heading</span></strong></h2><p>normal</p>');")
        selectText("strong span", 0, 7)

        assertEquals("\"h2\"", evaluate("document.getElementById('block-type').value"))
        assertEquals("\"20px\"", evaluate("document.getElementById('font-size').value"))
        assertEquals("\"true\"", evaluate("document.querySelector('[data-command=bold]').getAttribute('aria-pressed')"))

        selectText("p", 0, 6)
        assertEquals("\"p\"", evaluate("document.getElementById('block-type').value"))
        assertEquals("\"14px\"", evaluate("document.getElementById('font-size').value"))
        assertEquals("\"false\"", evaluate("document.querySelector('[data-command=bold]').getAttribute('aria-pressed')"))
    }

    @Test
    fun clearFormattingRestoresParagraphAndKeepsTheOtherBlocks() {
        openEditor()
        evaluate("window.setEditorContent('<h2><strong>heading</strong></h2><p><em>other</em></p>');")
        selectText("strong", 0, 7)
        evaluate("document.querySelector('[data-command=removeFormat]').click();")

        assertEquals("<p>heading</p><p><em>other</em></p>", capture())
    }
}
