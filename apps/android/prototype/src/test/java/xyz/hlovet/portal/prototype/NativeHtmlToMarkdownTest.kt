package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHtmlToMarkdownTest {
    @Test
    fun convertsHeadingsInlineFormattingLinksAndImages() {
        val markdown = nativeHtmlToMarkdown(
            "<h2>Title</h2><p><strong>Bold</strong> and <em>italic</em> <a href=\"https://example.com/a\">read</a>.</p><p><img src=\"/uploads/a.png\" alt=\"A picture\"></p>",
        )

        assertTrue(markdown.contains("## Title"))
        assertTrue(markdown.contains("**Bold** and *italic* [read](https://example.com/a)."))
        assertTrue(markdown.contains("![A picture](/uploads/a.png)"))
    }

    @Test
    fun preservesOrderedAndTaskLists() {
        val markdown = nativeHtmlToMarkdown(
            "<ul data-type=\"taskList\"><li data-type=\"taskItem\" data-checked=\"true\"><label><input type=\"checkbox\" checked><span></span></label><div><p>Done</p><ul><li>Subtask</li></ul></div></li><li data-type=\"taskItem\" data-checked=\"false\"><label><input type=\"checkbox\"><span></span></label><div><p>Next</p></div></li></ul><ol start=\"3\"><li>First</li><li>Second</li></ol>",
        )

        assertTrue(markdown.contains("- [x] Done"))
        assertTrue(markdown.contains("  - Subtask"))
        assertTrue(markdown.contains("- [ ] Next"))
        assertTrue(markdown.contains("3. First"))
        assertTrue(markdown.contains("4. Second"))
    }

    @Test
    fun preservesCodeLanguageResourceAndTable() {
        val markdown = nativeHtmlToMarkdown(
            "<pre data-language=\"json\"><code class=\"language-json\">{\"ok\":true}</code></pre><resource-block data-points=\"12\"><p>Paid text</p></resource-block><table><thead><tr><th>A</th><th>B</th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table>",
        )

        assertTrue(markdown.contains("```json\n{\"ok\":true}\n```"))
        assertTrue(markdown.contains(":::resource{points=12}\nPaid text\n:::"))
        assertTrue(markdown.contains("|A|B|"))
        assertTrue(markdown.contains("|---|---|"))
        assertTrue(markdown.contains("|1|2|"))
    }

    @Test
    fun sanitizesUnsafeMarkupBeforeConversion() {
        val markdown = nativeHtmlToMarkdown(
            "<script>alert(1)</script><p onclick=\"alert(2)\">safe</p><a href=\"javascript:alert(3)\">bad</a>",
        )

        assertEquals("safe\n\nbad", markdown)
        assertFalse(markdown.contains("script", ignoreCase = true))
        assertFalse(markdown.contains("onclick", ignoreCase = true))
    }
}
