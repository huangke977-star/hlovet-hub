package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeArticleHtmlSanitizerTest {
    @Test
    fun rendersMarkdownArticleBodyFeaturesForNativePreview() {
        val html = buildNativeArticleBodyHtml(
            "# Heading\n\n- [x] Done\n- [ ] Pending\n\n```sql\nselect * from users;\n```\n\n| Name | Value |\n| --- | --- |\n| A | B |",
            "markdown",
        )
        assertTrue(html.contains("<h1>Heading</h1>"))
        assertTrue(html.contains("data-type=\"taskList\""))
        assertTrue(html.contains("data-language=\"sql\""))
        assertTrue(html.contains("select * from users;"))
        assertTrue(html.contains("<table>"))
    }

    @Test
    fun rendersHtmlArticleBodyAndResolvesRelativeMediaUrls() {
        val html = buildNativeArticleBodyHtml(
            "<h2>HTML body</h2><p>Text</p><img src=\"/uploads/article/image.png\" alt=\"Preview\">",
            "html",
        )

        assertTrue(html.contains("<h2>HTML body</h2>"))
        assertTrue(html.contains(BuildConfig.API_BASE_URL.trimEnd('/') + "/uploads/article/image.png"))
        assertTrue(html.contains("alt=\"Preview\""))
    }

    @Test
    fun detectsHtmlAnnouncementContentWithoutMisclassifyingMarkdown() {
        assertEquals("html", nativeDetectedArticleContentFormat("\n  <p>Announcement</p>"))
        assertEquals("html", nativeDetectedArticleContentFormat("<pre><code>select 1</code></pre>"))
        assertEquals("markdown", nativeDetectedArticleContentFormat("# Announcement\n\nUse **Markdown**."))
    }

    @Test
    fun marksProtectedArticleAttachmentLinksForNativeOpening() {
        val html = buildNativeArticleBodyHtml(
            "<p><a href=\"/articles/attachments/42/download\">report.pdf</a></p>",
            "html",
        )

        assertTrue(html.contains("isAttachmentUrl"))
        assertTrue(html.contains("onAttachmentClicked"))
        assertTrue(nativeArticleMediaPaths("<a href=\"/articles/attachments/42/download\">report.pdf</a>", "html").contains("/articles/attachments/42/download"))
    }

    @Test
    fun extractsOnlyArticleMediaAndAttachmentLinksForOfflinePrefetch() {
        val paths = nativeArticleMediaPaths(
            "<img src=\"/uploads/image.png\"><audio src=\"/uploads/audio.mp3\"></audio>" +
                "<a href=\"/files/report.pdf\">report</a><a href=\"https://example.com/page\">page</a>",
            "html",
        )

        assertTrue(paths.contains("/uploads/image.png"))
        assertTrue(paths.contains("/uploads/audio.mp3"))
        assertTrue(paths.contains("/files/report.pdf"))
        assertFalse(paths.contains("https://example.com/page"))
        assertFalse(paths.contains("https://example.com/articles/attachments/42/download"))
    }

    @Test
    fun keepsSupportedFormattingAndResourceBlocks() {
        val html = sanitizeNativeArticleHtml(
            "<h2>Title</h2><p><strong>Bold</strong><u>Underlined</u></p>" +
                "<ul data-type=\"taskList\"><li data-type=\"taskItem\" data-checked=\"true\"><input type=\"checkbox\" checked><div>Done</div></li></ul>" +
                "<resource-block data-points=\"12\"><p>Paid content</p></resource-block>",
        )

        assertTrue(html.contains("<h2>Title</h2>"))
        assertTrue(html.contains("<strong>Bold</strong>"))
        assertTrue(html.contains("<u>Underlined</u>"))
        assertTrue(html.contains("data-checked=\"true\""))
        assertTrue(html.contains("<resource-block data-points=\"12\">"))
    }

    @Test
    fun keepsBrowserFormattingTagsFromVisualEditor() {
        val html = sanitizeNativeArticleHtml("<p><b>Bold</b><i>Italic</i><del>Deleted</del></p>")

        assertTrue(html.contains("<b>Bold</b>"))
        assertTrue(html.contains("<i>Italic</i>"))
        assertTrue(html.contains("<del>Deleted</del>"))
    }

    @Test
    fun taskListToolbarCreatesPcCompatibleHtmlAndEscapesText() {
        val html = buildNativeTaskListHtml(listOf("First <task>", "Second & done"))
        val sanitized = sanitizeNativeArticleHtml(html)

        assertTrue(sanitized.contains("<ul data-type=\"taskList\">"))
        assertTrue(sanitized.contains("<li data-type=\"taskItem\" data-checked=\"false\">"))
        assertTrue(sanitized.contains("<input type=\"checkbox\">"))
        assertTrue(sanitized.contains("<p>First &lt;task&gt;</p>"))
        assertTrue(sanitized.contains("<p>Second &amp; done</p>"))
    }

    @Test
    fun synchronizesTaskCheckedAttributeBeforeSaving() {
        val checked = sanitizeNativeArticleHtml(
            "<ul data-type=\"taskList\"><li data-type=\"taskItem\" data-checked=\"false\"><label><input type=\"checkbox\" checked><span></span></label><div><p>Done</p></div></li></ul>",
        )
        val unchecked = sanitizeNativeArticleHtml(
            "<ul data-type=\"taskList\"><li data-type=\"taskItem\" data-checked=\"false\"><label><input type=\"checkbox\"><span></span></label><div><p>Pending</p></div></li></ul>",
        )

        assertTrue(checked.contains("data-checked=\"true\""))
        assertTrue(checked.contains("type=\"checkbox\" checked"))
        assertTrue(unchecked.contains("data-checked=\"false\""))
        assertTrue(unchecked.contains("type=\"checkbox\">"))
    }

    @Test
    fun removesExecutableMarkupAndUnsafeUrls() {
        val html = sanitizeNativeArticleHtml(
            "<script>alert(1)</script><p onclick=\"alert(2)\">Text</p>" +
                "<a href=\"javascript:alert(3)\">bad link</a><img src=\"data:text/html,bad\" onerror=\"alert(4)\">",
        )

        assertFalse(html.contains("<script"))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("data:text/html"))
        assertFalse(html.contains("onerror"))
        assertTrue(html.contains("Text"))
        assertTrue(html.contains("bad link"))
    }

    @Test
    fun preservesOnlySafeFontSizeAndNormalizesCodeLanguage() {
        val html = sanitizeNativeArticleHtml(
            "<span style=\"font-size:16px\">Sized</span><span style=\"color:red\">Plain</span>" +
                "<pre data-language=\"unknown\"><code class=\"language-unknown\">x</code></pre>",
        )

        assertTrue(html.contains("style=\"font-size:16px\""))
        assertFalse(html.contains("color:red"))
        assertTrue(html.contains("data-language=\"plaintext\""))
        assertTrue(html.contains("class=\"language-plaintext\""))
    }

    @Test
    fun richEditorPageKeepsSafeContentAndDoesNotEmbedExecutableArticleMarkup() {
        val page = buildNativeRichEditorHtml(
            "<p onclick=\"alert(1)\">Safe text</p><script>alert(2)</script>" +
                "<pre data-language=\"json\"><code class=\"language-json\">{}</code></pre>",
        )

        assertTrue(page.contains("<p>Safe text</p>"))
        assertTrue(page.contains("data-language=\"json\""))
        assertTrue(page.contains("id=\"insert-task-list\""))
        assertTrue(page.contains("id=\"block-type\""))
        assertTrue(page.contains("id=\"font-size\""))
        assertTrue(page.contains("data-command=\"removeFormat\""))
        assertTrue(page.contains("id=\"insert-resource\""))
        assertTrue(page.contains("id=\"remove-resource\""))
        assertTrue(page.contains("data-command=\"unlink\""))
        assertTrue(page.contains("native-code-card-language"))
        assertTrue(page.contains("native-code-card-format"))
        assertTrue(page.contains("convertTypedTaskShortcut"))
        assertTrue(page.contains("var listItem = block.tagName.toLowerCase() === 'li' ? block : block.closest('li')"))
        assertTrue(page.contains("indentTaskItem"))
        assertTrue(page.contains("outdentTaskItem"))
        assertTrue(page.contains("event.key === 'Tab'"))
        assertTrue(page.contains("if (event.key === ' ')") )
        assertTrue(page.contains("event.key === ' ' || event.key === 'Enter'"))
        assertTrue(page.contains("onFormatRequested"))
        assertTrue(page.contains("window.applyFormattedCode"))
        assertTrue(page.contains("setAttribute('data-language'"))
        assertTrue(page.contains("function autoLinkAtCaret()"))
        assertTrue(page.contains("function convertMarkdownBlockShortcut()"))
        assertTrue(page.contains("function convertMarkdownInlineShortcut()"))
        assertTrue(page.contains("var heading = /^(#{1,3})$/"))
        assertTrue(page.contains("var fence = /^(?:\\x60{3})"))
        assertTrue(page.contains("normalizeAutoLinkCandidate(label)"))
        assertTrue(page.contains("event.inputType === 'insertFromPaste'"))
        assertTrue(page.contains("id=\"upload-attachment\""))
        assertTrue(page.contains("id=\"open-ai-assistant\""))
        assertTrue(page.contains("window.insertEditorHtml"))
        assertTrue(page.contains("if (button) button.addEventListener('mousedown'"))
        assertTrue(page.contains("clipboard.getData('text/html')"))
        assertTrue(page.contains("onHtmlPasteRequested"))
        assertTrue(page.contains("onClipboardFileStart"))
        assertTrue(page.contains("onClipboardFileChunk"))
        assertTrue(page.contains("completeNativeClipboardFile"))
        assertTrue(page.contains("editor.addEventListener('drop'"))
        assertTrue(page.contains("clipboardFileQueue"))
        assertTrue(page.contains("onLinkRequested"))
        assertTrue(page.contains("window.applyEditorLink"))
        assertFalse(page.contains("window.prompt"))
        assertTrue(page.contains("data-type=\"taskItem\""))
        assertFalse(page.contains("onclick"))
        assertFalse(page.contains("alert(1)"))
        assertFalse(page.contains("alert(2)"))
        assertTrue(page.contains("contenteditable=\"true\""))
    }

    @Test
    fun templateRichEditorCanHideToolsThatHaveNoTemplateAction() {
        val page = buildNativeRichEditorHtml(
            "<p>Template</p>",
            enableAttachments = false,
            enableAiAssistant = false,
        )

        assertFalse(page.contains("id=\"upload-attachment\""))
        assertFalse(page.contains("id=\"open-ai-assistant\""))
        assertTrue(page.contains("var attachmentButton = document.getElementById('upload-attachment');"))
        assertTrue(page.contains("var aiButton = document.getElementById('open-ai-assistant');"))
        assertTrue(page.contains("id=\"insert-resource\""))
        assertTrue(page.contains("native-code-card-language"))
    }

    @Test
    fun articleRichEditorKeepsAttachmentAndAiToolsEnabledByDefault() {
        val page = buildNativeRichEditorHtml("<p>Article</p>")

        assertTrue(page.contains("id=\"upload-attachment\""))
        assertTrue(page.contains("id=\"open-ai-assistant\""))
    }

    @Test
    fun syntaxHighlightingStaysInPresentationLayerInsteadOfStoredArticleHtml() {
        val source = "<pre data-language=\"javascript\"><code class=\"language-javascript\">const answer = 42;</code></pre>"
        val stored = sanitizeNativeArticleHtml(source)
        val editorPage = buildNativeRichEditorHtml(source)
        val previewPage = buildPreviewHtml("Code", "", "", source, "html")
        val articlePage = buildNativeArticleBodyHtml(source, "html")

        assertFalse(stored.contains("hljs"))
        assertFalse(stored.contains("hlovet-code-highlight"))
        assertTrue(editorPage.contains("serializedEditorHtml()"))
        assertTrue(editorPage.contains("window.HlovetSyntax.stripEditorArtifacts(wrapper)"))
        assertTrue(editorPage.contains("hlovet-code-highlight"))
        assertTrue(previewPage.contains(".hljs-keyword"))
        assertTrue(articlePage.contains(".hljs-keyword"))
    }
}
