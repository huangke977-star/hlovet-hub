package xyz.hlovet.portal.prototype

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWriteFormattingTest {
    @Test
    fun newArticleDefaultsToHtmlLikePcEditor() {
        assertEquals("html", nativeNewArticleContentFormat(null))
        assertEquals("html", nativeNewArticleContentFormat(""))
    }

    @Test
    fun explicitArticleFormatIsPreserved() {
        assertEquals("markdown", nativeNewArticleContentFormat(" Markdown "))
        assertEquals("html", nativeNewArticleContentFormat("HTML"))
        assertEquals("html", nativeNewArticleContentFormat("unsupported"))
    }

    @Test
    fun htmlStartsInVisualEditorWhileMarkdownKeepsSourceMode() {
        assertEquals(true, nativeStartsInVisualEditor(null))
        assertEquals(true, nativeStartsInVisualEditor(" HTML "))
        assertEquals(false, nativeStartsInVisualEditor("markdown"))
    }

    @Test
    fun templateEditorCanOmitOptionalButtonsWithoutUnsafeBindings() {
        val html = buildNativeRichEditorHtml(
            content = "",
            enableAttachments = false,
            enableAiAssistant = false,
        )

        assertFalse(html.contains("id=\"upload-attachment\""))
        assertFalse(html.contains("id=\"open-ai-assistant\""))
        assertTrue(html.contains("if (attachmentButton) attachmentButton.addEventListener('click'"))
        assertTrue(html.contains("if (aiButton) aiButton.addEventListener('click'"))
    }

    @Test
    fun visualEditorUsesPerBlockCodeCardControls() {
        val html = buildNativeRichEditorHtml(content = "<pre data-language=\"json\"><code class=\"language-json\">{}</code></pre>")

        assertTrue(html.contains("native-code-card-header"))
        assertTrue(html.contains("ensureNativeCodeCards"))
        assertTrue(html.contains("native-code-card-language"))
        assertTrue(html.contains("native-code-card-format"))
        assertFalse(html.contains("id=\"code-language\""))
        assertFalse(html.contains("id=\"format-code\""))
    }

    @Test
    fun editorLinksNormalizeDomainsAndKeepSupportedSchemes() {
        assertEquals("https://example.com/path", normalizeNativeEditorLink(" example.com/path "))
        assertEquals("https://example.com/path", normalizeNativeEditorLink("https://example.com/path"))
        assertEquals("mailto:test@example.com", normalizeNativeEditorLink("mailto:test@example.com"))
        assertEquals("/articles/example", normalizeNativeEditorLink("/articles/example"))
    }

    @Test
    fun editorLinksRejectBlankAndUnsafeSchemes() {
        assertNull(normalizeNativeEditorLink(""))
        assertNull(normalizeNativeEditorLink("javascript:alert(1)"))
        assertNull(normalizeNativeEditorLink("//evil.example/path"))
    }

    @Test
    fun typedAutoLinkCandidatesNormalizeUrlsAndIgnoreTrailingPunctuation() {
        assertEquals("https://example.com/path", normalizeNativeAutoLinkCandidate("example.com/path"))
        assertEquals("https://www.example.com", normalizeNativeAutoLinkCandidate("www.example.com."))
        assertEquals("https://example.com/?q=1", normalizeNativeAutoLinkCandidate("https://example.com/?q=1)"))
    }

    @Test
    fun typedAutoLinkCandidatesRejectUnsafeOrIncompleteValues() {
        assertNull(normalizeNativeAutoLinkCandidate("javascript:alert(1)"))
        assertNull(normalizeNativeAutoLinkCandidate("mailto:test@example.com"))
        assertNull(normalizeNativeAutoLinkCandidate("example"))
        assertNull(normalizeNativeAutoLinkCandidate("https://user:password@example.com"))
        assertNull(normalizeNativeAutoLinkCandidate("https://example.com:99999"))
    }

    @Test
    fun codeLanguageUpdatesMarkdownWithoutChangingCodeOrCaretOffset() {
        val source = "前文\n\n```javascript\nconst value = 1\n```\n\n后文"
        val caret = source.indexOf("value")
        val result = updateNativeCodeBlockLanguage(TextFieldValue(source, TextRange(caret)), "markdown", "json")

        assertNotNull(result)
        assertEquals(source.replace("```javascript", "```json"), result?.text)
        assertEquals(TextRange(caret + "json".length - "javascript".length), result?.selection)
    }

    @Test
    fun codeLanguageKeepsCrLfAndAnEmptyMarkdownBlock() {
        val source = "```plaintext\r\n\r\n```"
        val caret = source.indexOf("\r\n") + 2
        val result = updateNativeCodeBlockLanguage(TextFieldValue(source, TextRange(caret)), "markdown", "sql")

        assertEquals("```sql\r\n\r\n```", result?.text)
        assertEquals(TextRange("```sql\r\n".length), result?.selection)
    }

    @Test
    fun codeLanguageUpdatesHtmlAttributesAndKeepsEncodedBodyAndSelection() {
        val source = "<p>前文</p><pre data-language='html' data-extra='keep'>\n" +
            "<code class='extra language-html'>&lt;div&gt;Text&lt;/div&gt;</code>\n</pre><p>后文</p>"
        val start = source.indexOf("Text")
        val result = updateNativeCodeBlockLanguage(TextFieldValue(source, TextRange(start + 4, start)), "html", "xml")

        val expected = "<p>前文</p><pre data-language=\"xml\" data-extra=\"keep\">" +
            "<code class=\"extra language-xml\">&lt;div&gt;Text&lt;/div&gt;</code>\n</pre><p>后文</p>"
        assertEquals(expected, result?.text)
        val updatedStart = expected.indexOf("Text")
        assertEquals(TextRange(updatedStart + 4, updatedStart), result?.selection)
    }

    @Test
    fun codeLanguageAddsMissingHtmlLanguageMetadata() {
        val source = "<pre><code>{}</code></pre>"
        val result = updateNativeCodeBlockLanguage(TextFieldValue(source, TextRange(source.indexOf("{}"))), "html", "json")

        assertEquals("<pre data-language=\"json\"><code class=\"language-json\">{}</code></pre>", result?.text)
    }

    @Test
    fun codeLanguageChangesOnlyTheSelectedBlock() {
        val source = "```json\n{}\n```\n\n```html\n&lt;p&gt;\n```"
        val result = updateNativeCodeBlockLanguage(TextFieldValue(source, TextRange(source.indexOf("&lt;"))), "markdown", "xml")

        assertEquals(source.replace("```html", "```xml"), result?.text)
    }

    @Test
    fun codeLanguageDoesNotUpdateOutsideBlockOrAcrossMultipleBlocks() {
        val source = "前文\n```json\n{}\n```\n后文"

        assertNull(updateNativeCodeBlockLanguage(TextFieldValue(source, TextRange(1)), "markdown", "sql"))
        assertNull(updateNativeCodeBlockLanguage(TextFieldValue(source, TextRange(source.length)), "markdown", "sql"))
        assertNull(updateNativeCodeBlockLanguage(TextFieldValue(source, TextRange(source.indexOf("{}"), source.length)), "markdown", "sql"))
        assertNull(updateNativeCodeBlockLanguage(TextFieldValue(source, TextRange(source.indexOf("{}"))), "markdown", "invalid"))
    }

    @Test
    fun visualEditorConvertsPastedMarkdownTaskLists() {
        val html = nativeVisualEditorMarkdownPaste("- [x] Done\n- [ ] Pending")

        assertEquals(true, html?.contains("data-type=\"taskList\""))
        assertEquals(true, html?.contains("data-checked=\"true\""))
        assertEquals(true, html?.contains("data-checked=\"false\""))
    }

    @Test
    fun visualEditorKeepsOrdinaryPastedTextOutOfMarkdownConversion() {
        assertNull(nativeVisualEditorMarkdownPaste("ordinary text\nwithout task markers"))
    }

    @Test
    fun visualEditorPreservesSafePastedHtmlAndNormalizesTaskLists() {
        val html = nativeVisualEditorHtmlPaste(
            "<div onclick=\"alert(1)\"><h2>Copied title</h2><p><strong>Bold</strong> text</p>" +
                "<ul><li><input type=\"checkbox\" checked>Done</li><li><input type=\"checkbox\">Pending</li></ul>" +
                "<script>alert(2)</script></div>",
        )

        assertEquals(true, html?.contains("<h2>Copied title</h2>"))
        assertEquals(true, html?.contains("<strong>Bold</strong>"))
        assertEquals(true, html?.contains("data-type=\"taskList\""))
        assertEquals(true, html?.contains("data-checked=\"true\""))
        assertEquals(true, html?.contains("data-checked=\"false\""))
        assertEquals(false, html?.contains("onclick"))
        assertEquals(false, html?.contains("<script"))
    }

    @Test
    fun visualEditorHtmlPasteFallsBackToEscapedPlainTextWhenClipboardHtmlIsEmpty() {
        assertEquals(
            "<p>1 &lt; 2<br>next</p>",
            nativeVisualEditorHtmlPaste("<script>alert(1)</script>", "1 < 2\nnext"),
        )
    }

    @Test
    fun parseAiTaxonomySupportsChineseLabelsAndLimitsTags() {
        val result = parseNativeAiTaxonomy(
            "分类：技术\n标签：AI，开发，数据库，网络，工具，教程，生活",
        )

        assertEquals("技术", result?.first)
        assertEquals(listOf("AI", "开发", "数据库", "网络", "工具", "教程"), result?.second)
    }

    @Test
    fun parseAiTaxonomySupportsEnglishLabelsAndRemovesHash() {
        val result = parseNativeAiTaxonomy("Category: Engineering\nTags: #android, #compose")

        assertEquals("Engineering", result?.first)
        assertEquals(listOf("android", "compose"), result?.second)
    }

    @Test
    fun parseAiTaxonomyReturnsNullForUnstructuredText() {
        assertNull(parseNativeAiTaxonomy("这里只是一段没有字段名的说明。"))
    }

    @Test
    fun removeMarkdownLinkKeepsLabelAndSelection() {
        val source = "[Portal](https://example.com)"
        val result = removeNativeLink(TextFieldValue(source, TextRange(1, 7)), "markdown")

        assertEquals("Portal", result?.text)
        assertEquals(TextRange(0, 6), result?.selection)
    }

    @Test
    fun removeHtmlLinkPreservesNestedFormatting() {
        val source = "<p><a href=\"https://example.com\"><strong>Portal</strong></a></p>"
        val selectionStart = source.indexOf("Portal")
        val result = removeNativeLink(
            TextFieldValue(source, TextRange(selectionStart, selectionStart + "Portal".length)),
            "html",
        )

        assertEquals("<p><strong>Portal</strong></p>", result?.text)
        assertEquals(TextRange(11, 17), result?.selection)
    }

    @Test
    fun removeMarkdownLinkFromCaretInsideUrlKeepsLinkText() {
        val source = "[Portal](https://example.com)"
        val caret = source.indexOf("example")
        val result = removeNativeLink(TextFieldValue(source, TextRange(caret)), "markdown")

        assertEquals("Portal", result?.text)
        assertEquals(TextRange(6), result?.selection)
    }

    @Test
    fun removeLinkDoesNothingWhenSelectionIsNotLinked() {
        assertNull(removeNativeLink(TextFieldValue("plain text", TextRange(0, 5)), "markdown"))
    }

    @Test
    fun removeMarkdownAttachmentReferenceRemovesImageAndKeepsOtherContent() {
        val attachment = NativeArticleAttachment(
            id = 7,
            kind = "image",
            originalName = "diagram.png",
            mimeType = "image/png",
            sizeBytes = 10,
            downloadUrl = "/articles/attachments/7/download",
            thumbnailUrl = null,
        )
        val source = "正文\n\n![diagram.png](${attachment.downloadUrl})\n\n结尾"

        assertEquals("正文\n\n\n\n结尾", removeNativeAttachmentReference(source, attachment, "markdown"))
    }

    @Test
    fun removeHtmlAttachmentReferenceRemovesWrappedMedia() {
        val attachment = NativeArticleAttachment(
            id = 8,
            kind = "audio",
            originalName = "voice.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 12,
            downloadUrl = "/articles/attachments/8/download",
            thumbnailUrl = null,
        )
        val source = "<p>前文</p><p><audio controls src=\"${attachment.downloadUrl}\"></audio></p><p>后文</p>"

        assertEquals("<p>前文</p><p>后文</p>", removeNativeAttachmentReference(source, attachment, "html"))
    }

    @Test
    fun removeAttachmentReferenceDoesNothingWhenUrlIsNotPresent() {
        val attachment = NativeArticleAttachment(
            id = 9,
            kind = "file",
            originalName = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = 5,
            downloadUrl = "/articles/attachments/9/download",
            thumbnailUrl = null,
        )

        assertEquals("没有附件引用", removeNativeAttachmentReference("没有附件引用", attachment, "markdown"))
    }
}
