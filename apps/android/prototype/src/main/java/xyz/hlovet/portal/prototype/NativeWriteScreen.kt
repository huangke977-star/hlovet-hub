package xyz.hlovet.portal.prototype

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.ClipDescription
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.DragAndDropPermissions
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal sealed interface NativeWriteTarget {
    data class New(val template: NativeArticleTemplate? = null) : NativeWriteTarget
    data class Existing(val id: Int) : NativeWriteTarget
}

private val nativeScheduleFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private data class NativeCodeLanguageOption(
    val value: String,
    val label: String,
)

private fun nativeCodeLanguageOptions() = listOf(
    NativeCodeLanguageOption("plaintext", nativePhrase("纯文本", "Plain text")),
    NativeCodeLanguageOption("html", "HTML"),
    NativeCodeLanguageOption("css", "CSS"),
    NativeCodeLanguageOption("javascript", "JavaScript"),
    NativeCodeLanguageOption("typescript", "TypeScript"),
    NativeCodeLanguageOption("json", "JSON"),
    NativeCodeLanguageOption("sql", "SQL"),
    NativeCodeLanguageOption("python", "Python"),
    NativeCodeLanguageOption("bash", "Bash"),
    NativeCodeLanguageOption("java", "Java"),
    NativeCodeLanguageOption("go", "Go"),
    NativeCodeLanguageOption("c", "C"),
    NativeCodeLanguageOption("cpp", "C++"),
    NativeCodeLanguageOption("csharp", "C#"),
    NativeCodeLanguageOption("php", "PHP"),
    NativeCodeLanguageOption("rust", "Rust"),
    NativeCodeLanguageOption("xml", "XML"),
    NativeCodeLanguageOption("yaml", "YAML"),
    NativeCodeLanguageOption("markdown", "Markdown"),
)

private val nativeFontSizeOptions = listOf(
    "12px" to "12",
    "14px" to "14",
    "16px" to "16",
    "18px" to "18",
    "20px" to "20",
)

private data class NativeArticleAssistantOperation(
    val value: String,
    val label: String,
    val description: String,
)

private fun nativeArticleAssistantOperations() = listOf(
    NativeArticleAssistantOperation(
        "title",
        nativePhrase("生成标题", "Generate title"),
        nativePhrase("根据当前文章内容拟定一个准确、简洁的标题。", "Create an accurate, concise title from the current article."),
    ),
    NativeArticleAssistantOperation(
        "outline",
        nativePhrase("生成提纲", "Generate outline"),
        nativePhrase("整理文章结构和重点，结果会插入正文。", "Organize the structure and key points, then insert the result into the body."),
    ),
    NativeArticleAssistantOperation(
        "summary",
        nativePhrase("生成摘要", "Generate summary"),
        nativePhrase("生成一段简洁摘要并应用到摘要字段。", "Create a concise summary and apply it to the summary field."),
    ),
    NativeArticleAssistantOperation(
        "taxonomy",
        nativePhrase("建议分类和标签", "Suggest category and tags"),
        nativePhrase("根据文章内容建议分类和最多六个标签。", "Suggest a category and up to six tags from the article."),
    ),
    NativeArticleAssistantOperation(
        "polish",
        nativePhrase("润色正文", "Polish body"),
        nativePhrase("保留原意，改善表达和结构。", "Preserve the meaning while improving wording and structure."),
    ),
    NativeArticleAssistantOperation(
        "rewrite",
        nativePhrase("改写正文", "Rewrite body"),
        nativePhrase("保留事实，让内容更清晰自然。", "Keep the facts while making the content clearer and more natural."),
    ),
    NativeArticleAssistantOperation(
        "expand",
        nativePhrase("扩写正文", "Expand body"),
        nativePhrase("补充有用细节，但不编造事实。", "Add useful detail without inventing facts."),
    ),
    NativeArticleAssistantOperation(
        "shorten",
        nativePhrase("缩写正文", "Shorten body"),
        nativePhrase("保留关键信息，压缩冗余内容。", "Keep key information and remove redundancy."),
    ),
    NativeArticleAssistantOperation(
        "correct",
        nativePhrase("纠错正文", "Correct body"),
        nativePhrase("修正语法、错别字、标点和 Markdown 结构。", "Correct grammar, typos, punctuation, and Markdown structure."),
    ),
    NativeArticleAssistantOperation(
        "format",
        nativePhrase("整理排版", "Format body"),
        nativePhrase("优化标题、列表、引用和代码块等 Markdown 结构。", "Improve Markdown structure such as headings, lists, quotes, and code blocks."),
    ),
)

internal fun parseNativeAiTaxonomy(value: String): Pair<String, List<String>>? {
    val category = Regex("(?im)^\\s*(?:分类|category)\\s*[:：]\\s*(.+?)\\s*$")
        .find(value)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val tagsLine = Regex("(?im)^\\s*(?:标签|tags?)\\s*[:：]\\s*(.+?)\\s*$")
        .find(value)?.groupValues?.getOrNull(1).orEmpty()
    val tags = tagsLine
        .split(',', '，', '、')
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotBlank() }
        .distinct()
        .take(6)
    return if (category.isBlank() && tags.isEmpty()) null else category to tags
}

private fun nativeAiInsertText(value: String, contentFormat: String): String =
    if (contentFormat == "html") {
        sanitizeNativeArticleHtml(previewMarkdownRenderer.render(previewMarkdownParser.parse(value)))
    } else {
        value
    }

private val nativeTaskMarkdownPattern = Regex("^\\s*[-*+]\\s+\\[[ xX]\\]", setOf(RegexOption.MULTILINE))

internal fun nativeVisualEditorMarkdownPaste(value: String): String? {
    if (!nativeTaskMarkdownPattern.containsMatchIn(value)) return null
    val rendered = previewMarkdownRenderer.render(previewMarkdownParser.parse(value))
    val document = Jsoup.parseBodyFragment(sanitizeNativeArticleHtml(rendered))
    normalizeNativeArticleTaskLists(document)
    return document.body().html()
}

private const val nativeClipboardHtmlLimit = 200_000
private const val nativeClipboardTextLimit = 50_000
private const val nativeClipboardFileMaxBytes = 10L * 1024L * 1024L
private const val nativeClipboardFileMaxCount = NATIVE_ARTICLE_ATTACHMENT_MAX_COUNT
private const val nativeClipboardFileChunkChars = 131_072

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun nativeVisualEditorHtmlPaste(html: String, plainText: String = ""): String? {
    val sanitized = sanitizeNativeArticleHtml(html.take(nativeClipboardHtmlLimit))
    if (sanitized.isNotBlank()) {
        val document = Jsoup.parseBodyFragment(sanitized)
        normalizeNativeArticleTaskLists(document)
        return document.body().html().trim().takeIf(String::isNotBlank)
    }

    val fallback = plainText.take(nativeClipboardTextLimit).trimEnd()
    if (fallback.isBlank()) return null
    return "<p>" + fallback.split("\r\n", "\n", "\r").joinToString("<br>") { line ->
        htmlEscape(line)
    } + "</p>"
}

internal fun normalizeNativeEditorLink(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    val supportedScheme = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("mailto:", ignoreCase = true)
    if (!supportedScheme && Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return null
    val normalized = if (
        trimmed.startsWith("/") ||
        supportedScheme
    ) {
        trimmed
    } else {
        "https://$trimmed"
    }
    return normalized.takeIf(::isNativeArticleSafeUrl)
}

private val nativeAutoLinkCandidatePattern = Regex(
    """^(?:(?:https?://|www\.)[^\s<>"']+|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?:[/?#][^\s<>"']*)?)$""",
    RegexOption.IGNORE_CASE,
)

internal fun normalizeNativeAutoLinkCandidate(value: String): String? {
    val candidate = value.trim().trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}', '"', '\'')
    if (candidate.isBlank() || !nativeAutoLinkCandidatePattern.matches(candidate)) return null
    val normalized = normalizeNativeEditorLink(candidate) ?: return null
    val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    if (uri.scheme !in setOf("http", "https") || !host.contains('.') || uri.rawUserInfo != null) return null
    if (uri.port != -1 && uri.port !in 1..65535) return null
    return normalized
}

/** 保留未提交的正文输入及保存时间，用于同一服务器和账号下恢复写作。 */
internal data class NativeLocalDraftSnapshot(
    val input: NativeDraftInput,
    val savedAt: Long,
)

/** 当前来源账号下可恢复的草稿条目，articleId 为 null 时代表尚未获得服务器编号。 */
internal data class NativeLocalDraftEntry(val articleId: Int?, val snapshot: NativeLocalDraftSnapshot)

private const val nativeLocalDraftPrefs = "native-article-drafts"

/** 根据 userId、articleId 和 apiBaseUrl 生成本地草稿键；新文章使用独立 new 标识。 */
internal fun nativeLocalDraftKey(userId: Int, articleId: Int?, apiBaseUrl: String = BuildConfig.API_BASE_URL): String =
    // 文章 ID 与账号 ID 只在所属服务器内稳定，来源不参与时会恢复另一站的正文。
    NativeOfflineOrigin.key("draft|$userId|${articleId ?: "new"}", apiBaseUrl)

/** 从 context 读取 key 对应的 apiBaseUrl 草稿；旧版、缺失、损坏或来源不符时返回 null。 */
internal fun readNativeLocalDraft(context: Context, key: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): NativeLocalDraftSnapshot? = runCatching {
    // 旧版键未记录服务器来源，不能自动当作当前账号的未提交内容。
    if (!key.startsWith("v2-")) return@runCatching null
    val raw = context.getSharedPreferences(nativeLocalDraftPrefs, Context.MODE_PRIVATE).getString(key, null)
        ?: return@runCatching null
    val value = JSONObject(raw)
    if (value.optString("serverOrigin") != NativeOfflineOrigin.origin(apiBaseUrl)) return@runCatching null
    // 来源校验后再还原已有编辑字段，异常或无有效保存时间按没有可恢复草稿处理。
    val input = value.optJSONObject("input") ?: return@runCatching null
    NativeLocalDraftSnapshot(
        input = NativeDraftInput(
            title = input.optString("title"),
            summary = input.optString("summary"),
            content = input.optString("content"),
            contentFormat = input.optString("contentFormat", "markdown"),
            category = input.optString("category", "随笔"),
            tags = input.optString("tags"),
            titleColor = input.optString("titleColor"),
            visibility = input.optString("visibility", "public"),
            roleCodes = input.optJSONArray("roleCodes")?.let { values ->
                buildList {
                    for (index in 0 until values.length()) values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            } ?: emptyList(),
        ),
        savedAt = value.optLong("savedAt", 0L),
    ).takeIf { it.savedAt > 0L }
}.getOrNull()

/** 在 context 为 key 保存 input 与 apiBaseUrl 来源；userId、articleId 提供可列出的已校验身份。 */
internal fun writeNativeLocalDraft(
    context: Context,
    key: String,
    input: NativeDraftInput,
    apiBaseUrl: String = BuildConfig.API_BASE_URL,
    userId: Int? = null,
    articleId: Int? = null,
) {
    runCatching {
        // 新记录附带来源元数据，即使原文被复制到另一来源键也不自动恢复。
        require(key.startsWith("v2-")) { "Local draft key must be origin scoped" }
        if (userId != null) require(key == nativeLocalDraftKey(userId, articleId, apiBaseUrl)) { "Local draft identity mismatch" }
        val value = JSONObject().apply {
            put("serverOrigin", NativeOfflineOrigin.origin(apiBaseUrl))
            // 只有身份与来源键一致的新版副本才进入离线写作列表，未知身份记录仍保留。
            if (userId != null) {
                put("userId", userId)
                put("articleId", articleId ?: JSONObject.NULL)
            }
            put("savedAt", System.currentTimeMillis())
            put("input", JSONObject().apply {
                put("title", input.title)
                put("summary", input.summary)
                put("content", input.content)
                put("contentFormat", input.contentFormat)
                put("category", input.category)
                put("tags", input.tags)
                put("titleColor", input.titleColor)
                put("visibility", input.visibility)
                put("roleCodes", JSONArray(input.roleCodes))
            })
        }
        // 写作失败保留本地副本，不保存访问令牌或密码。
        context.getSharedPreferences(nativeLocalDraftPrefs, Context.MODE_PRIVATE).edit().putString(key, value.toString()).apply()
    }
}

/** 列出 context 中 userId 和 apiBaseUrl 所属的有效草稿，按保存时间倒序返回，不推测其它账号身份。 */
internal fun listNativeLocalDrafts(context: Context, userId: Int, apiBaseUrl: String = BuildConfig.API_BASE_URL): List<NativeLocalDraftEntry> {
    // 未绑定账号的测试或匿名状态不能列出私人草稿；旧 new 键仍可按确定身份精确恢复。
    if (userId <= 0) return emptyList()
    val entries = mutableListOf<NativeLocalDraftEntry>()
    readNativeLocalDraft(context, nativeLocalDraftKey(userId, null, apiBaseUrl), apiBaseUrl)?.let {
        entries.add(NativeLocalDraftEntry(null, it))
    }
    for ((key, raw) in context.getSharedPreferences(nativeLocalDraftPrefs, Context.MODE_PRIVATE).all) {
        // 元数据只能辅助发现条目，最终重新计算摘要并校验来源，不能信任复制过来的标题或编号。
        if (!key.startsWith("v2-") || raw !is String) continue
        val value = runCatching { JSONObject(raw) }.getOrNull() ?: continue
        if (value.optInt("userId", -1) != userId || !value.has("articleId") || value.isNull("articleId")) continue
        val id = value.optInt("articleId", -1).takeIf { it > 0 } ?: continue
        if (key != nativeLocalDraftKey(userId, id, apiBaseUrl)) continue
        readNativeLocalDraft(context, key, apiBaseUrl)?.let { entries.add(NativeLocalDraftEntry(id, it)) }
    }
    // 空白副本没有可继续写作的内容，不在入口中制造不可用条目。
    return entries.filter { it.snapshot.input.run { title.isNotBlank() || summary.isNotBlank() || content.isNotBlank() } }
        .sortedByDescending { it.snapshot.savedAt }
}

/** 从 context 清理已提交或放弃的新版 key 草稿，来源不明的历史草稿不删除。 */
internal fun removeNativeLocalDraft(context: Context, key: String) {
    // 调用方只传当前来源账号文章的键，禁止清空整个偏好文件。
    if (!key.startsWith("v2-")) return
    context.getSharedPreferences(nativeLocalDraftPrefs, Context.MODE_PRIVATE).edit().remove(key).apply()
}

private data class NativeCodeBlockRange(
    val wholeStart: Int,
    val wholeEnd: Int,
    val bodyStart: Int,
    val bodyEnd: Int,
    val language: String,
)

private fun findNativeCodeBlock(value: TextFieldValue, contentFormat: String): NativeCodeBlockRange? {
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    if (contentFormat == "html") {
        val expression = Regex(
            "<pre\\b[^>]*>\\s*<code\\b[^>]*>([\\s\\S]*?)</code>\\s*</pre>",
            RegexOption.IGNORE_CASE,
        )
        return expression.findAll(value.text).firstOrNull { cursor in it.range }?.let { match ->
            val body = match.groups[1]?.range ?: return@let null
            val pre = Jsoup.parseBodyFragment(match.value).selectFirst("pre") ?: return@let null
            val classLanguage = pre.selectFirst("code")?.classNames()
                ?.firstOrNull { it.startsWith("language-") }?.removePrefix("language-").orEmpty()
            NativeCodeBlockRange(
                wholeStart = match.range.first,
                wholeEnd = match.range.last + 1,
                bodyStart = body.first,
                bodyEnd = body.last + 1,
                language = pre.attr("data-language").ifBlank { classLanguage }.ifBlank { "plaintext" }.lowercase(),
            )
        }
    }
    val expression = Regex("```([A-Za-z0-9+#-]*)[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```", RegexOption.IGNORE_CASE)
    return expression.findAll(value.text).firstOrNull { cursor in it.range }?.let { match ->
        val body = match.groups[2]?.range ?: return@let null
        NativeCodeBlockRange(
            wholeStart = match.range.first,
            wholeEnd = match.range.last + 1,
            bodyStart = body.first,
            bodyEnd = body.last + 1,
            language = match.groups[1]?.value.orEmpty().ifBlank { "plaintext" }.lowercase(),
        )
    }
}

private fun htmlUnescapeForNativeCode(value: String): String = value
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&#x27;", "'")
    .replace("&amp;", "&")

private fun clearNativeFormatting(source: String, contentFormat: String): String {
    return if (contentFormat == "html") {
        source.replace(
            Regex("</?(?:strong|b|em|i|s|strike|del|u|span)(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE),
            "",
        )
    } else {
        source
            .replace("**", "")
            .replace("__", "")
            .replace("~~", "")
            .replace("`", "")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s"), "")
    }
}

private fun TextFieldValue.selectionBounds(): Pair<Int, Int> {
    val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
    val end = maxOf(selection.start, selection.end).coerceIn(start, text.length)
    return start to end
}

private fun replaceSelectedText(
    value: TextFieldValue,
    replacement: String,
    cursorOffset: Int = replacement.length,
): TextFieldValue {
    val (start, end) = value.selectionBounds()
    val nextText = buildString(value.text.length - (end - start) + replacement.length) {
        append(value.text, 0, start)
        append(replacement)
        append(value.text, end, value.text.length)
    }
    val cursor = (start + cursorOffset).coerceIn(0, nextText.length)
    return TextFieldValue(nextText, TextRange(cursor))
}

private fun wrapSelectedText(
    value: TextFieldValue,
    before: String,
    after: String,
    placeholder: String,
): TextFieldValue {
    val (start, end) = value.selectionBounds()
    val selected = value.text.substring(start, end)
    val body = if (selected.isEmpty()) placeholder else selected
    val replacement = before + body + after
    val cursorOffset = if (selected.isEmpty()) before.length else replacement.length
    return replaceSelectedText(value, replacement, cursorOffset)
}

private fun prefixSelectedLines(
    value: TextFieldValue,
    prefixForLine: (Int) -> String,
): TextFieldValue {
    val (start, end) = value.selectionBounds()
    val lineStart = value.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0))
        .let { if (it < 0) 0 else it + 1 }
    val lineEnd = value.text.indexOf('\n', end).let { if (it < 0) value.text.length else it }
    val selectedLines = value.text.substring(lineStart, lineEnd).split('\n')
    val replacement = selectedLines.mapIndexed { index, line -> prefixForLine(index) + line }.joinToString("\n")
    val nextText = buildString(value.text.length + replacement.length - (lineEnd - lineStart)) {
        append(value.text, 0, lineStart)
        append(replacement)
        append(value.text, lineEnd, value.text.length)
    }
    val nextSelectionEnd = (lineStart + replacement.length).coerceIn(0, nextText.length)
    return TextFieldValue(nextText, TextRange(nextSelectionEnd))
}

private fun insertCodeBlock(
    value: TextFieldValue,
    language: String,
    contentFormat: String,
): TextFieldValue {
    val (start, end) = value.selectionBounds()
    val selected = value.text.substring(start, end)
    val replacement: String
    val cursorOffset: Int
    if (contentFormat == "html") {
        val escaped = htmlEscape(selected)
        val opening = "<pre data-language=\"$language\"><code class=\"language-$language\">"
        replacement = opening + escaped + "</code></pre>"
        cursorOffset = if (selected.isEmpty()) opening.length else replacement.length
    } else {
        val opening = "```$language\n"
        replacement = opening + selected + "\n```"
        cursorOffset = if (selected.isEmpty()) opening.length else replacement.length
    }
    return replaceSelectedText(value, replacement, cursorOffset)
}

internal fun updateNativeCodeBlockLanguage(
    value: TextFieldValue,
    contentFormat: String,
    language: String,
): TextFieldValue? {
    if (nativeCodeLanguageOptions().none { it.value == language }) return null
    val block = findNativeCodeBlock(value, contentFormat) ?: return null
    val (selectionStart, selectionEnd) = value.selectionBounds()
    if (selectionStart < block.wholeStart || selectionEnd > block.wholeEnd) return null
    val opening = if (contentFormat == "html") {
        val document = Jsoup.parseBodyFragment(value.text.substring(block.wholeStart, block.bodyStart) + "</code></pre>")
        document.outputSettings().prettyPrint(false)
        val pre = document.selectFirst("pre") ?: return null
        val code = pre.selectFirst("code") ?: return null
        pre.attr("data-language", language)
        code.classNames(code.classNames().filterNot { it.startsWith("language-") }.toSet() + "language-$language")
        pre.outerHtml().removeSuffix("</code></pre>")
    } else {
        val lineBreak = if (value.text.substring(block.wholeStart, block.bodyStart).endsWith("\r\n")) "\r\n" else "\n"
        "```$language$lineBreak"
    }
    // Change only the opening markup so the code and its caret offset stay intact.
    val openingDelta = opening.length - (block.bodyStart - block.wholeStart)
    val nextText = value.text.replaceRange(block.wholeStart, block.bodyStart, opening)
    fun mapPosition(position: Int): Int = when {
        position < block.wholeStart -> position
        position >= block.bodyStart -> position + openingDelta
        else -> block.wholeStart + opening.length
    }.coerceIn(0, nextText.length)
    return TextFieldValue(
        nextText,
        TextRange(mapPosition(value.selection.start), mapPosition(value.selection.end)),
    )
}

/** 展示 session 的服务器草稿与本机未提交副本，断网时仍能通过正常写作入口恢复。 */
@Composable
internal fun NativeWriteHomeScreen(
    scaffoldPadding: PaddingValues,
    session: NativeSession?,
    refreshToken: Int = 0,
    onLogin: () -> Unit,
    onNewArticle: () -> Unit,
    onOpenDraft: (Int) -> Unit,
) {
    var drafts by remember(session?.accessToken) { mutableStateOf<NativeDraftList?>(null) }
    var loading by remember(session?.accessToken) { mutableStateOf(false) }
    var loadingMore by remember(session?.accessToken) { mutableStateOf(false) }
    var error by remember(session?.accessToken) { mutableStateOf<String?>(null) }
    var searchQuery by remember(session?.accessToken) { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var localDrafts by remember(session?.userId) { mutableStateOf<List<NativeLocalDraftEntry>>(emptyList()) }

    LaunchedEffect(session?.accessToken, searchQuery, reloadKey, refreshToken) {
        // 先读本站本账号副本，再等待服务器，断网或加载中都保留本地入口。
        localDrafts = session?.let { listNativeLocalDrafts(context, it.userId) }.orEmpty()
        val token = session?.accessToken ?: run {
            drafts = null
            loading = false
            loadingMore = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        loadingMore = false
        error = null
        try {
            drafts = withContext(Dispatchers.IO) {
                NativeArticleApi.listMyDrafts(token, page = 1, pageSize = 12, search = searchQuery.trim().takeIf { it.isNotBlank() })
            }
        } catch (exception: Exception) {
            drafts = null
            error = exception.message ?: nativePhrase("草稿加载失败，请稍后重试。", "Drafts could not be loaded. Try again later.")
        } finally {
            loading = false
        }
    }

    fun loadMoreDrafts() {
        val token = session?.accessToken ?: return
        val current = drafts ?: return
        if (loading || loadingMore || current.page >= current.totalPages) return
        loadingMore = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeArticleApi.listMyDrafts(
                        token,
                        page = current.page + 1,
                        pageSize = 12,
                        search = searchQuery.trim().takeIf { it.isNotBlank() },
                    )
                }
            }.onSuccess { next ->
                drafts = appendNativeDraftPage(drafts, next)
            }.onFailure { exception ->
                error = exception.message ?: nativePhrase("更多草稿加载失败，请稍后重试。", "More drafts could not be loaded. Try again later.")
            }
            loadingMore = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            WriteHeader(
                onRefresh = { reloadKey += 1 },
                onNewArticle = onNewArticle,
            )
        }
        if (session != null && localDrafts.isNotEmpty()) {
            // 本地入口不混入服务器分页总数，避免把尚未提交的副本误报成已保存文章。
            item {
                Text(nativePhrase("未提交的本地草稿", "Unsaved local drafts"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp))
            }
            items(localDrafts, key = { "local-draft-${it.articleId ?: "new"}" }) { entry ->
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                    ListItem(
                        headlineContent = { Text(entry.snapshot.input.title.ifBlank { nativePhrase("未命名文章", "Untitled article") }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(nativePhrase("未提交 · 本机副本", "Unsaved · On-device copy"), color = HlovetUi.muted, fontSize = 11.sp) },
                        leadingContent = { Icon(Icons.Filled.Restore, contentDescription = null, tint = HlovetUi.accent) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().clickable { entry.articleId?.let(onOpenDraft) ?: onNewArticle() },
                    )
                }
            }
        }
        if (session == null) {
            item { WriteLoginCard(onLogin) }
        } else if (loading) {
            item { WriteStateCard(nativePhrase("正在读取草稿…", "Loading drafts..."), null) }
        } else if (error != null) {
            item {
                WriteStateCard(
                    title = nativePhrase("草稿暂时无法读取", "Drafts are unavailable"),
                    detail = error,
                    action = { reloadKey += 1 },
                )
            }
        } else {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(100) },
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    singleLine = true,
                    label = { Text(nativePhrase("搜索草稿", "Search drafts"), fontSize = 12.sp) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }, enabled = !loading && !loadingMore) {
                                Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除搜索", "Clear search"), tint = HlovetUi.muted)
                            }
                        }
                    },
                    colors = HlovetUi.fieldColors(),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(nativePhrase("最近草稿", "Recent drafts"), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        nativePhrase("${drafts?.total ?: 0} 篇", "${drafts?.total ?: 0} articles"),
                        color = HlovetUi.muted,
                        fontSize = 12.sp,
                    )
                }
            }
            val items = drafts?.items.orEmpty()
            if (items.isEmpty()) {
                item {
                    WriteStateCard(
                        nativePhrase("还没有草稿", "No drafts yet"),
                        nativePhrase("点击右上角的新建按钮开始写作。", "Use the new article button in the upper-right to start writing."),
                    )
                }
            } else {
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape,
                    ) {
                        Column {
                            items.forEachIndexed { index, draft ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            draft.title.ifBlank { nativePhrase("未命名文章", "Untitled article") },
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            buildString {
                                                append(draft.category.ifBlank { nativePhrase("未分类", "Uncategorized") })
                                                if (draft.updatedAt.isNotBlank()) {
                                                    append(nativePhrase(" · 更新于 ${draft.updatedAt}", " · Updated ${draft.updatedAt}"))
                                                }
                                            },
                                            color = HlovetUi.muted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingContent = { Icon(Icons.Filled.Article, contentDescription = null, tint = HlovetUi.accent) },
                                    trailingContent = { Text(nativePhrase("编辑", "Edit"), color = HlovetUi.accent, fontSize = 12.sp) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.fillMaxWidth().clickable { onOpenDraft(draft.id) },
                                )
                                if (index < items.lastIndex) {
                                    androidx.compose.material3.HorizontalDivider(
                                        color = HlovetUi.divider,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (drafts!!.page < drafts!!.totalPages) {
                    item {
                        TextButton(
                            onClick = ::loadMoreDrafts,
                            enabled = !loading && !loadingMore,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        ) {
                            Text(
                                if (loadingMore) {
                                    nativePhrase("加载中…", "Loading...")
                                } else {
                                    nativePhrase(
                                        "加载更多草稿（${drafts!!.page}/${drafts!!.totalPages}）",
                                        "Load more drafts (${drafts!!.page}/${drafts!!.totalPages})",
                                    )
                                },
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WriteHeader(onRefresh: () -> Unit, onNewArticle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(nativePhrase("写作", "Write"), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = HlovetUi.foreground)
            Text(nativePhrase("把想法留下来", "Capture your ideas"), color = HlovetUi.muted, fontSize = 12.sp)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新草稿", "Refresh drafts"), tint = HlovetUi.muted)
        }
        IconButton(onClick = onNewArticle) {
            Icon(Icons.Filled.AddCircleOutline, contentDescription = nativePhrase("新建文章", "New article"), tint = HlovetUi.accent)
        }
    }
}

@Composable
private fun WriteLoginCard(onLogin: () -> Unit) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
    ) {
        ListItem(
            headlineContent = { Text(nativePhrase("登录后开始写作", "Sign in to start writing"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) },
            supportingContent = {
                Text(
                    nativePhrase("草稿会自动保存到你的账号中", "Drafts are saved automatically to your account"),
                    color = HlovetUi.muted,
                    fontSize = 12.sp,
                )
            },
            leadingContent = { Icon(Icons.Filled.Article, contentDescription = null, tint = HlovetUi.accent) },
            trailingContent = { TextButton(onClick = onLogin) { Text(nativePhrase("登录", "Sign in")) } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun WriteStateCard(
    title: String,
    detail: String?,
    action: (() -> Unit)? = null,
) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (action == null) Icons.Filled.Article else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = if (action == null) HlovetUi.accent else HlovetUi.secondaryAccent,
                )
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            if (!detail.isNullOrBlank()) Text(detail, color = HlovetUi.muted, fontSize = 12.sp)
            if (action != null) TextButton(onClick = action) { Text(nativePhrase("重新加载", "Reload")) }
        }
    }
}

/** 编辑 session 所属文章或新草稿，分开维护本地恢复、实时输入和远程保存状态。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NativeWriteEditorScreen(
    session: NativeSession?,
    articleId: Int?,
    template: NativeArticleTemplate? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onPublished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var loaded by remember(articleId) { mutableStateOf(articleId == null) }
    var serverId by remember(articleId) { mutableStateOf(articleId) }
    val editorKey = template?.id
    var title by remember(articleId, editorKey) { mutableStateOf(template?.title.orEmpty()) }
    var titleColor by remember(articleId, editorKey) { mutableStateOf(template?.titleColor.orEmpty()) }
    var summary by remember(articleId, editorKey) { mutableStateOf(template?.summary.orEmpty()) }
    var content by remember(articleId, editorKey) { mutableStateOf(TextFieldValue(template?.content.orEmpty())) }
    val editorSnapshot = remember(articleId, editorKey) { NativeEditorSnapshot() }
    var visualEditor by remember(articleId, editorKey) {
        mutableStateOf(nativeStartsInVisualEditor(template?.contentFormat))
    }
    var undoStack by remember(articleId, editorKey) { mutableStateOf<List<TextFieldValue>>(emptyList()) }
    var redoStack by remember(articleId, editorKey) { mutableStateOf<List<TextFieldValue>>(emptyList()) }
    var contentFormat by remember(articleId, editorKey) { mutableStateOf(nativeNewArticleContentFormat(template?.contentFormat)) }
    var category by remember(articleId, editorKey) { mutableStateOf(template?.category?.ifBlank { "随笔" } ?: "随笔") }
    var tags by remember(articleId, editorKey) { mutableStateOf(template?.tags?.joinToString(", ").orEmpty()) }
    var visibility by remember(articleId, editorKey) { mutableStateOf(template?.visibility ?: "public") }
    var roleCodes by remember(articleId, editorKey) { mutableStateOf(template?.roleCodes ?: emptyList()) }
    var status by remember(articleId, editorKey) { mutableStateOf("draft") }
    var saving by remember(articleId, editorKey) { mutableStateOf(false) }
    var readyForSave by remember(articleId, editorKey) { mutableStateOf(articleId == null) }
    var failedSaveRevision by remember(articleId, editorKey) { mutableIntStateOf(-1) }
    var dirty by remember(articleId, editorKey) { mutableStateOf(false) }
    var revision by remember(articleId, editorKey) { mutableIntStateOf(0) }
    var lastSavedSignature by remember(articleId, editorKey) { mutableStateOf("") }
    var message by remember(articleId, editorKey) {
        mutableStateOf(
            template?.let {
                nativePhrase("已套用模板：${it.name}", "Template applied: ${it.name}")
            },
        )
    }
    var error by remember(articleId, editorKey) { mutableStateOf<String?>(null) }
    var formattingCode by remember(articleId, editorKey) { mutableStateOf(false) }
    var publishWarnings by remember(articleId, editorKey) { mutableStateOf<List<String>>(emptyList()) }
    var writerSettings by remember(articleId, editorKey) { mutableStateOf<NativeWriterSettings?>(null) }
    var previewVisible by remember(articleId, editorKey) { mutableStateOf(false) }
    var versionsVisible by remember(articleId, editorKey) { mutableStateOf(false) }
    var versionsLoading by remember(articleId, editorKey) { mutableStateOf(false) }
    var versions by remember(articleId, editorKey) { mutableStateOf<List<NativeArticleVersionSummary>>(emptyList()) }
    var selectedVersion by remember(articleId, editorKey) { mutableStateOf<NativeArticleVersion?>(null) }
    var uploading by remember(articleId, editorKey) { mutableStateOf(false) }
    var uploadedAttachments by remember(articleId, editorKey) { mutableStateOf<List<NativeArticleAttachment>>(emptyList()) }
    val uploadMutex = remember(articleId, editorKey) { Mutex() }
    var roleOptions by remember(articleId, editorKey) { mutableStateOf<List<NativeRole>>(emptyList()) }
    var codeLanguageDialogVisible by remember(articleId, editorKey) { mutableStateOf(false) }
    var codeSelection by remember(articleId, editorKey) { mutableStateOf<TextRange?>(null) }
    var linkDialogVisible by remember(articleId, editorKey) { mutableStateOf(false) }
    var linkUrl by remember(articleId, editorKey) { mutableStateOf("") }
    var linkSelection by remember(articleId, editorKey) { mutableStateOf<TextRange?>(null) }
    var fontSizeDialogVisible by remember(articleId, editorKey) { mutableStateOf(false) }
    var resourceDialogVisible by remember(articleId, editorKey) { mutableStateOf(false) }
    var resourcePoints by remember(articleId, editorKey) { mutableStateOf("10") }
    var resourceSelection by remember(articleId, editorKey) { mutableStateOf<TextRange?>(null) }
    var visualSelectionHtml by remember(articleId, editorKey) { mutableStateOf("") }
    var visualInsertHtml by remember(articleId, editorKey) { mutableStateOf<String?>(null) }
    var visualInsertFeedback by remember(articleId, editorKey) { mutableStateOf<String?>(null) }
    var visualInsertNonce by remember(articleId, editorKey) { mutableIntStateOf(0) }
    var aiDialogVisible by remember(articleId, editorKey) { mutableStateOf(false) }
    var aiOperation by remember(articleId, editorKey) { mutableStateOf("polish") }
    var aiResult by remember(articleId, editorKey) { mutableStateOf<NativeAiArticleAssistantResult?>(null) }
    var aiBusy by remember(articleId, editorKey) { mutableStateOf(false) }
    var aiSelection by remember(articleId, editorKey) { mutableStateOf<TextRange?>(null) }
    var aiContextHint by remember(articleId, editorKey) { mutableStateOf<String?>(null) }
    var visualAiMode by remember(articleId, editorKey) { mutableStateOf(false) }
    var recoveryDraft by remember(articleId, editorKey) { mutableStateOf<NativeLocalDraftSnapshot?>(null) }
    var scheduleDialogVisible by remember(articleId, editorKey) { mutableStateOf(false) }
    var schedulePublishAt by remember(articleId, editorKey) { mutableStateOf("") }
    var scheduleUnpublishAt by remember(articleId, editorKey) { mutableStateOf("") }
    var scheduleBusy by remember(articleId, editorKey) { mutableStateOf(false) }
    var scheduleError by remember(articleId, editorKey) { mutableStateOf<String?>(null) }
    var leaveConfirmationVisible by remember(articleId, editorKey) { mutableStateOf(false) }
    var articleActionsExpanded by remember(articleId, editorKey) { mutableStateOf(false) }
    var destructiveAction by remember(articleId, editorKey) { mutableStateOf<String?>(null) }
    var attachmentBusyId by remember(articleId, editorKey) { mutableIntStateOf(0) }

    val configuredCategories = (writerSettings?.categories?.map { it.name } ?: emptyList())
        .let { values -> (listOf(category) + values).filter(String::isNotBlank).distinct() }
    val configuredTags = (writerSettings?.tags?.map { it.name } ?: emptyList())
        .let { values -> (tags.split(',', '，', '、').map(String::trim) + values).filter(String::isNotBlank).distinct() }
    val selectedTags = tags.split(',', '，', '、').map(String::trim).filter(String::isNotBlank).distinct()

    /** 按当前服务器编号动态生成草稿键，异步回调不能持有首次保存前的 new 键。 */
    fun currentLocalDraftKey(): String {
        // State 中的 serverId 立即更新，重组前执行的输入和落盘任务也读取最新编号。
        return nativeLocalDraftKey(session?.userId ?: 0, serverId)
    }

    /** 读取当前界面的写作字段，返回不包含凭据的提交快照。 */
    fun currentInput() = NativeDraftInput(
        // 标题、权限和正文取自同一轮界面状态，不借用过期服务器回包。
        title = title,
        titleColor = titleColor,
        summary = summary,
        content = content.text,
        contentFormat = contentFormat,
        category = category,
        tags = tags,
        visibility = visibility,
        roleCodes = roleCodes,
    )

    /** 标记当前编辑版本并立即保留本地副本，保存请求进行中也不能暂停落盘。 */
    fun markChanged() {
        // 每次真实输入只递增一次版本，副本始终限定当前来源、账号和文章。
        dirty = true
        message = null
        revision += 1
        if (loaded && recoveryDraft == null) writeNativeLocalDraft(context, currentLocalDraftKey(), currentInput(), userId = session?.userId, articleId = serverId)
    }

    /** 操作前读取实时正文，返回提交快照；可视编辑器未就绪时返回 null 并显示错误。 */
    suspend fun currentInputForAction(): NativeDraftInput? {
        // 原生源码输入已同步，可视输入则读取 WebView，不能依赖防抖通知是否抵达。
        if (!visualEditor) return currentInput()
        val liveContent = try {
            editorSnapshot.capture()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase(
                "编辑器尚未准备好，请稍后重试。",
                "The editor is not ready. Please try again.",
            )
            return null
        }
        val sanitizedContent = sanitizeNativeArticleHtml(liveContent)
        // 同步最后一批可视输入，后续保存回包比较与本地落盘使用相同净化规则。
        if (sanitizedContent != content.text) {
            content = TextFieldValue(sanitizedContent)
            markChanged()
        }
        return currentInput().copy(content = sanitizedContent)
    }

    /** 根据 input 全部可编辑字段生成稳定签名，用于区分已提交快照与较新输入。 */
    fun signature(input: NativeDraftInput): String = listOf(
        // 角色顺序不改变权限含义，其余字段保留实际输入顺序与内容。
        input.title,
        input.titleColor,
        input.summary,
        input.content,
        input.contentFormat,
        input.category,
        input.tags,
        input.visibility,
        input.roleCodes.sorted().joinToString(","),
    ).joinToString("\u0000")

    /** 将 saved 完整载入编辑器，仅用于初始读取或用户明确恢复历史版本。 */
    fun applySavedArticle(saved: NativeDraftArticle) {
        // 完整替换会重置撤销记录，自动保存不得调用此路径覆盖正在输入的内容。
        // HTML 使用与可视快照相同的白名单序列化，避免 input 自闭合差异被当作用户修改；Markdown 保留原文。
        val editableContent = if (saved.contentFormat == "html") sanitizeNativeArticleHtml(saved.content) else saved.content
        val previousSelection = content.selection
        val safeSelectionStart = previousSelection.start.coerceIn(0, editableContent.length)
        val safeSelectionEnd = previousSelection.end.coerceIn(safeSelectionStart, editableContent.length)
        serverId = saved.id
        status = saved.status
        title = saved.title
        titleColor = saved.titleColor
        summary = saved.summary
        content = TextFieldValue(
            editableContent,
            TextRange(safeSelectionStart, safeSelectionEnd),
        )
        undoStack = emptyList()
        redoStack = emptyList()
        contentFormat = saved.contentFormat
        category = saved.category.ifBlank { category }
        tags = saved.tags.joinToString(", ")
        visibility = saved.visibility
        roleCodes = saved.roleCodes
        schedulePublishAt = saved.publishAt.orEmpty()
        scheduleUnpublishAt = saved.unpublishAt.orEmpty()
        scheduleError = saved.scheduleError
        uploadedAttachments = saved.attachments
    }

    /** 将 next 正文及选区同步到编辑器；recordHistory 控制是否记录本次文本变更。 */
    fun updateContent(next: TextFieldValue, recordHistory: Boolean = true) {
        // 纯选区变化不生成草稿版本，正文变化保留最多 80 步撤销记录。
        if (next.text == content.text) {
            content = next
            return
        }
        if (recordHistory && next.text != content.text) {
            undoStack = (undoStack + content).takeLast(80)
            redoStack = emptyList()
        }
        content = next
        markChanged()
    }

    /** 保存当前或 inputOverride 快照，manual 控制接口；成功返回文章，失败保留最新本地输入。 */
    suspend fun saveDraft(
        showMessage: Boolean,
        manual: Boolean = false,
        inputOverride: NativeDraftInput? = null,
    ): NativeDraftArticle? {
        val token = session?.accessToken ?: return null
        if (saving || attachmentBusyId != 0 || (uploading && serverId != null)) return null
        // 原文读取失败且尚未选择恢复时，禁止用空白编辑器覆盖服务器的既有文章。
        if (!readyForSave) {
            error = nativePhrase("请先恢复本地草稿或联网重新打开文章。", "Restore the local draft or reopen the article online first.")
            return null
        }
        val input = inputOverride ?: currentInputForAction() ?: return null
        // 读取可视快照会挂起，期间其它操作可能已开始保存，需再次检查串行状态。
        if (saving || attachmentBusyId != 0 || (uploading && serverId != null)) return null
        if (manual && (input.title.trim().isBlank() || input.content.trim().isBlank())) {
            error = nativePhrase("标题和正文不能为空。", "Title and body are required.")
            return null
        }
        if (input.visibility == "role_restricted" && input.roleCodes.isEmpty()) {
            error = nativePhrase("请选择至少一个可见角色。", "Select at least one visible role.")
            return null
        }
        val normalizedTitleColor = normalizeNativeTitleColor(input.titleColor)
        if (normalizedTitleColor == null) {
            error = nativePhrase(
                "标题颜色请输入六位十六进制颜色，例如 #2B2530。",
                "Enter a six-digit hexadecimal title color, for example #2B2530.",
            )
            return null
        }
        val normalizedInput = input.copy(titleColor = normalizedTitleColor)
        val inputSignature = signature(normalizedInput)
        val requestId = serverId
        val requestKey = currentLocalDraftKey()
        // 发请求前保留快照；后续输入会由 markChanged 覆盖为最新版本。
        writeNativeLocalDraft(context, requestKey, currentInput(), userId = session.userId, articleId = requestId)
        saving = true
        error = null
        try {
            val saved = withContext(Dispatchers.IO) {
                if (manual) {
                    if (requestId == null) NativeArticleApi.createArticle(token, normalizedInput)
                    else NativeArticleApi.updateArticle(token, requestId, normalizedInput, status = statusForUpdate(status))
                } else if (requestId == null) {
                    NativeArticleApi.createAutosave(token, normalizedInput)
                } else {
                    NativeArticleApi.autosave(token, requestId, normalizedInput)
                }
            }
            // 回包只确认提交快照，重新读取可视正文以覆盖尚未发通知的最新输入。
            val liveInput = currentInputForAction()
            val liveSignature = liveInput?.let { value ->
                signature(value.copy(titleColor = normalizeNativeTitleColor(value.titleColor) ?: value.titleColor))
            }
            serverId = saved.id
            status = saved.status
            uploadedAttachments = saved.attachments
            schedulePublishAt = saved.publishAt.orEmpty()
            scheduleUnpublishAt = saved.unpublishAt.orEmpty()
            scheduleError = saved.scheduleError
            lastSavedSignature = inputSignature
            failedSaveRevision = -1
            dirty = liveSignature != inputSignature
            recoveryDraft = null
            val savedKey = nativeLocalDraftKey(session.userId, saved.id)
            // 编号变化时先写最新内容再移除 new 副本，避免回包删掉请求后的修改。
            if (dirty) writeNativeLocalDraft(context, savedKey, liveInput ?: currentInput(), userId = session.userId, articleId = saved.id)
            else removeNativeLocalDraft(context, savedKey)
            if (requestKey != savedKey || !dirty) removeNativeLocalDraft(context, requestKey)
            if (showMessage) message = nativePhrase("草稿已保存", "Draft saved")
            onSaved()
            return saved
        } catch (exception: CancellationException) {
            // 组件退出取消等待时，已经落盘的副本不清除，不把取消误报成业务失败。
            throw exception
        } catch (exception: Exception) {
            // 相同失败版本不自动循环重试；下一次输入或手动保存仍可继续提交。
            failedSaveRevision = revision
            writeNativeLocalDraft(context, currentLocalDraftKey(), currentInput(), userId = session.userId, articleId = serverId)
            error = exception.message ?: nativePhrase("草稿保存失败，请稍后重试。", "The draft could not be saved. Try again later.")
            return null
        } finally {
            saving = false
        }
    }

    /** 将 html 加入可视插入队列；feedback 在插入通知抵达后仍显示，不被内部同步当作新输入清除。 */
    fun queueVisualInsert(html: String, feedback: String? = null) {
        // nonce 区分相同 HTML 的多次插入请求，空内容不生成操作。
        if (html.isBlank()) return
        visualInsertNonce += 1
        visualInsertHtml = html
        visualInsertFeedback = feedback
    }

    /** 串行读取 readFiles 提供的选中文件，完成校验、上传和正文插入；失败返回 false 并保留正文。 */
    suspend fun uploadFiles(readFiles: suspend () -> List<NativeUploadFile>): Boolean {
        val token = session?.accessToken ?: return false
        return uploadMutex.withLock {
            // 正文保存或删除附件尚未完成时不交叉修改服务端附件集合。
            if (saving || attachmentBusyId != 0 || !readyForSave) {
                error = nativePhrase("请等待当前操作完成后再上传附件。", "Wait for the current operation before uploading attachments.")
                return@withLock false
            }
            uploading = true
            error = null
            try {
                // 读取也处于上传串行区间内，避免多个选择批次同时占用大块内存。
                val maxImageBytes = (writerSettings?.imageMaxSizeMb ?: 10) * 1024L * 1024L
                val files = readFiles()
                if (files.isEmpty()) return@withLock false
                validateNativeArticleAttachmentCount(uploadedAttachments.size, files.size)
                validateNativeArticleUpload(files, maxImageBytes, prepared = false)
                val preparedFiles = withContext(Dispatchers.IO) {
                    files.map { file -> compressNativeImageForUpload(file.copy(mimeType = nativeArticleAttachmentMimeType(file.name, file.mimeType))) }
                }
                validateNativeArticleUpload(preparedFiles, maxImageBytes, prepared = true)
                // 所有本地校验通过后才创建新文章，取消或无效文件不能留下空白远程草稿。
                val targetId = serverId ?: saveDraft(showMessage = false)?.id
                if (targetId == null) {
                    error = nativePhrase("草稿创建失败，暂时无法上传附件。", "The draft could not be created, so attachments cannot be uploaded yet.")
                    return@withLock false
                }
                val attachments = withContext(Dispatchers.IO) {
                    NativeArticleApi.uploadAttachments(token, targetId, preparedFiles)
                }
                // 异常回包不当作完整上传成功，已返回附件仍展示供用户核对或移除。
                uploadedAttachments = uploadedAttachments + attachments
                check(attachments.size == preparedFiles.size) {
                    nativePhrase("部分附件上传失败，请检查附件列表后重试。", "Some attachments failed to upload. Check the attachment list before retrying.")
                }
                val references = appendAttachmentReferences("", attachments, contentFormat)
                val feedback = nativePhrase(
                    "已上传 ${attachments.size} 个附件，并插入正文。",
                    "Uploaded ${attachments.size} attachments and inserted them into the body.",
                )
                if (visualEditor) {
                    queueVisualInsert(references, feedback)
                } else {
                    val insertion = if (content.text.isBlank()) references else "\n\n$references"
                    content = replaceSelectedText(content, insertion)
                }
                markChanged()
                message = feedback
                true
            } catch (exception: CancellationException) {
                // 页面退出时由协程取消收尾，不将正常退出反馈为上传失败。
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("附件上传失败，请稍后重试。", "Attachments could not be uploaded. Try again later.")
                false
            } finally {
                uploading = false
            }
        }
    }

    /** 从 uris 读取系统选择的文件；空列表表示取消，不发请求或改变编辑状态。 */
    suspend fun uploadSelectedFiles(uris: List<Uri>) {
        // 在读取前检查数量，未知大小供应商也受逐文件和剩余批次预算约束。
        if (uris.isEmpty()) return
        uploadFiles {
            validateNativeArticleAttachmentCount(uploadedAttachments.size, uris.size)
            withContext(Dispatchers.IO) {
                val files = mutableListOf<NativeUploadFile>()
                var remainingBytes = NATIVE_ARTICLE_ATTACHMENT_BATCH_BYTES
                val maxImageBytes = (writerSettings?.imageMaxSizeMb ?: 10) * 1024L * 1024L
                for (uri in uris) {
                    check(remainingBytes > 0) { nativeArticleAttachmentBatchError() }
                    val file = readUploadFile(context, uri, maxImageBytes, remainingBytes)
                    files.add(file)
                    remainingBytes -= file.bytes.size.toLong()
                }
                files
            }
        }
    }

    /** 上传 file 对应的剪贴板附件；桥接通道仍保留 10MiB 内存保护，接口限制由通用流程校验。 */
    suspend fun uploadClipboardFile(file: NativeUploadFile): Boolean {
        // 大附件使用系统选择器，避免 Base64 桥接扩大内存占用。
        if (file.bytes.size.toLong() > nativeClipboardFileMaxBytes) {
            error = nativePhrase("剪贴板文件不能超过 10MiB，请使用附件选择器。", "Clipboard files cannot exceed 10 MiB. Use the attachment picker instead.")
            return false
        }
        return uploadFiles { listOf(file) }
    }

    /** 移除 attachment 及其正文引用，接口返回后重新读取实时输入，保留请求期间继续输入的文字。 */
    fun removeUploadedAttachment(attachment: NativeArticleAttachment) {
        // 与上传、保存互斥；原文尚未恢复时不能发出删除请求。
        val token = session?.accessToken
        val id = serverId
        if (token.isNullOrBlank() || id == null || attachmentBusyId != 0 || saving || uploading || !readyForSave) return
        attachmentBusyId = attachment.id
        scope.launch {
            try {
                // 删除前验证编辑器可读取，回包后再取最新正文而不是使用请求前快照。
                if (currentInputForAction() == null) return@launch
                withContext(Dispatchers.IO) { NativeArticleApi.removeAttachment(token, id, attachment.id) }
                uploadedAttachments = uploadedAttachments.filter { it.id != attachment.id }
                val liveInput = currentInputForAction() ?: return@launch
                val nextContent = removeNativeAttachmentReference(liveInput.content, attachment, liveInput.contentFormat)
                if (nextContent != content.text) updateContent(TextFieldValue(nextContent))
                message = nativePhrase("附件已移除", "Attachment removed")
            } catch (exception: CancellationException) {
                // 正常页面退出不删除本地正文，也不产生误导的失败提示。
                throw exception
            } catch (exception: Exception) {
                // 服务端拒绝删除时附件列表和正文保持原状，用户可重试。
                error = exception.message ?: nativePhrase("附件移除失败，请稍后重试。", "The attachment could not be removed. Try again later.")
            } finally {
                attachmentBusyId = 0
            }
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        scope.launch { uploadSelectedFiles(uris) }
    }

    LaunchedEffect(session?.accessToken, articleId, editorKey) {
        // 先读取本地副本，即使配置或原文联网失败，也必须保留恢复入口。
        val token = session?.accessToken ?: return@LaunchedEffect
        val localRecovery = readNativeLocalDraft(context, currentLocalDraftKey())?.takeIf { snapshot ->
            snapshot.input.title.isNotBlank() || snapshot.input.summary.isNotBlank() || snapshot.input.content.isNotBlank()
        }
        val (settingsResult, rolesResult) = coroutineScope {
            val settingsJob = async(Dispatchers.IO) { runCatching { NativeSiteSettingsApi.loadWriterSettings() } }
            val rolesJob = async(Dispatchers.IO) { runCatching { NativeRoleApi.listRoles() } }
            settingsJob.await() to rolesJob.await()
        }
        writerSettings = settingsResult.getOrNull()
        roleOptions = rolesResult.getOrElse { emptyList() }
        if (rolesResult.isFailure && articleId != null) {
            error = nativePhrase(
                "角色列表暂时无法读取，受限可见文章可能无法保存。",
                "Roles are temporarily unavailable. Role-restricted articles may not save.",
            )
        }
        if (articleId == null) {
            // 配置迟到时不能覆盖用户已开始输入的分类与权限。
            if (template == null && revision == 0) {
                if (category == "随笔") category = settingsResult.getOrNull()?.categories?.firstOrNull()?.name ?: category
                if (visibility == "public") visibility = settingsResult.getOrNull()?.defaultVisibility ?: visibility
            }
            recoveryDraft = localRecovery
            loaded = true
            lastSavedSignature = signature(currentInput())
            return@LaunchedEffect
        }
        loaded = false
        error = null
        try {
            // 只有成功读取服务器原文或用户明确恢复副本后，才允许远程保存。
            val article = withContext(Dispatchers.IO) { NativeArticleApi.getMyArticle(token, articleId) }
            applySavedArticle(article)
            visualEditor = nativeStartsInVisualEditor(article.contentFormat)
            category = article.category.ifBlank { writerSettings?.categories?.firstOrNull()?.name ?: "随笔" }
            val serverInput = NativeDraftInput(
                title = article.title,
                summary = article.summary,
                content = article.content,
                contentFormat = article.contentFormat,
                category = article.category,
                tags = article.tags.joinToString(", "),
                titleColor = article.titleColor,
                visibility = article.visibility,
                roleCodes = article.roleCodes,
            )
            recoveryDraft = localRecovery?.takeIf { snapshot ->
                signature(snapshot.input) != signature(serverInput)
            }
            readyForSave = true
            dirty = false
            lastSavedSignature = signature(currentInput())
        } catch (exception: CancellationException) {
            // 页面退出时取消初始化，不覆盖下一页面的状态。
            throw exception
        } catch (exception: Exception) {
            recoveryDraft = localRecovery
            error = if (localRecovery != null) nativePhrase(
                "文章暂时无法联网读取，可恢复本地草稿继续编辑，恢复网络后再保存。",
                "The article is unavailable online. Restore the local draft to continue, then save after reconnecting.",
            ) else exception.message ?: nativePhrase("文章加载失败，请稍后重试。", "The article could not be loaded. Try again later.")
        } finally {
            loaded = true
        }
    }

    LaunchedEffect(revision, loaded, dirty, serverId, recoveryDraft) {
        // 配置初始化后补写当前副本；有待用户选择的恢复副本时不能用空表单覆盖它。
        if (!loaded || !dirty || recoveryDraft != null || revision == 0) return@LaunchedEffect
        writeNativeLocalDraft(context, currentLocalDraftKey(), currentInput(), userId = session?.userId, articleId = serverId)
    }

    LaunchedEffect(revision, loaded, dirty, serverId, saving, uploading, attachmentBusyId, readyForSave, recoveryDraft, failedSaveRevision) {
        // 防抖任务只决定何时发起请求，输入改变不得取消一个已经发出的保存。
        if (!loaded || !dirty || saving || uploading || attachmentBusyId != 0 || !readyForSave || recoveryDraft != null || revision == 0 || revision == failedSaveRevision) return@LaunchedEffect
        delay(1400)
        if (!saving && !uploading && attachmentBusyId == 0 && dirty) scope.launch { saveDraft(showMessage = false) }
    }

    /** 手动保存当前实时输入，验证通过后显示保存结果。 */
    fun requestSave() {
        // 手动请求与自动请求共用串行保护，但允许用户重试已失败的版本。
        scope.launch { saveDraft(showMessage = true, manual = true) }
    }

    /** 将当前账号的待恢复快照应用到表单，明确授权继续编辑并保留未提交副本。 */
    fun restoreRecoveryDraft() {
        // 恢复包含正文格式和权限，不能只恢复标题正文而改变可见范围。
        val snapshot = recoveryDraft ?: return
        val input = snapshot.input
        title = input.title
        titleColor = input.titleColor
        summary = input.summary
        content = TextFieldValue(input.content)
        contentFormat = input.contentFormat
        visualEditor = nativeStartsInVisualEditor(input.contentFormat)
        category = input.category
        tags = input.tags
        visibility = input.visibility
        roleCodes = input.roleCodes
        recoveryDraft = null
        readyForSave = true
        error = null
        markChanged()
        message = nativePhrase("已恢复本地草稿，请检查后保存。", "Local draft restored. Review and save it.")
    }

    /** 丢弃当前文章的恢复副本，不清理其它文章或未知来源草稿。 */
    fun discardRecoveryDraft() {
        // 丢弃不等于已读取服务器原文，readyForSave 保持原状态。
        removeNativeLocalDraft(context, currentLocalDraftKey())
        recoveryDraft = null
    }

    /** 放弃当前文章未提交内容后返回，不清除其它账号或文章的本地副本。 */
    fun discardUnsavedAndLeave() {
        // 用户已明确放弃当前草稿，只删除动态编号对应的本地键。
        removeNativeLocalDraft(context, currentLocalDraftKey())
        recoveryDraft = null
        dirty = false
        leaveConfirmationVisible = false
        onBack()
    }

    /** 离开前读取实时正文，保存或附件操作进行中不允许丢弃页面；有未提交输入时请求确认。 */
    fun requestLeave() {
        // 未完成的附件请求可能仍写入服务端，不能在处理中当作无修改直接离开。
        if (saving || uploading || attachmentBusyId != 0) return
        scope.launch {
            if (currentInputForAction() == null) return@launch
            if (dirty) {
                leaveConfirmationVisible = true
            } else {
                onBack()
            }
        }
    }

    fun toggleTag(tag: String) {
        val next = if (selectedTags.contains(tag)) {
            selectedTags.filter { it != tag }
        } else {
            if (selectedTags.size >= 6) {
                error = nativePhrase("最多选择 6 个标签。", "Select up to 6 tags.")
                return
            }
            selectedTags + tag
        }
        tags = next.joinToString(", ")
        markChanged()
    }

    fun openVersions() {
        val token = session?.accessToken
        val id = serverId
        if (token.isNullOrBlank() || id == null) {
            message = nativePhrase("首次保存草稿后才能查看版本历史。", "Save the draft once before viewing version history.")
            return
        }
        scope.launch {
            versionsVisible = true
            versionsLoading = true
            selectedVersion = null
            try {
                versions = withContext(Dispatchers.IO) { NativeArticleApi.listVersions(token, id) }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("版本历史加载失败。", "Version history could not be loaded.")
            } finally {
                versionsLoading = false
            }
        }
    }

    fun selectVersion(version: NativeArticleVersionSummary) {
        val token = session?.accessToken ?: return
        val id = serverId ?: return
        scope.launch {
            versionsLoading = true
            try {
                selectedVersion = withContext(Dispatchers.IO) {
                    NativeArticleApi.getVersion(token, id, version.id)
                }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("版本内容加载失败。", "The version could not be loaded.")
            } finally {
                versionsLoading = false
            }
        }
    }

    fun restoreSelectedVersion() {
        val token = session?.accessToken ?: return
        val id = serverId ?: return
        val version = selectedVersion ?: return
        scope.launch {
            versionsLoading = true
            try {
                val restored = withContext(Dispatchers.IO) {
                    NativeArticleApi.restoreVersion(token, id, version.summary.id)
                }
                applySavedArticle(restored)
                lastSavedSignature = signature(currentInput())
                dirty = false
                selectedVersion = null
                versions = withContext(Dispatchers.IO) { NativeArticleApi.listVersions(token, id) }
                versionsVisible = false
                message = nativePhrase("历史版本已恢复为新草稿。", "The version was restored as a new draft.")
                onSaved()
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("恢复历史版本失败。", "The version could not be restored.")
            } finally {
                versionsLoading = false
            }
        }
    }

    /** 保存最新正文并执行发布检查，只有校验通过才发布；附件操作进行中不交叉提交。 */
    fun requestPublish() {
        // 先拒绝并行写操作，再读取当前可视正文作为本次发布的提交版本。
        val token = session?.accessToken ?: return
        if (saving || uploading || attachmentBusyId != 0) return
        scope.launch {
            // 标题和实时正文均存在才进入发布保存，空内容不发远程请求。
            val actionInput = currentInputForAction() ?: return@launch
            if (actionInput.title.trim().isBlank() || actionInput.content.trim().isBlank()) {
                error = nativePhrase("标题和正文不能为空。", "Title and body are required.")
                return@launch
            }
            // 有修改时先保存本次快照，服务器编号确定后再验证发布条件。
            val id = if (dirty || serverId == null) {
                saveDraft(showMessage = false, manual = true, inputOverride = actionInput)?.id ?: return@launch
            } else {
                serverId ?: return@launch
            }
            saving = true
            error = null
            try {
                // 使用服务器的权限和发布规则，警告可展示但硬性错误必须阻止发布。
                val check = withContext(Dispatchers.IO) { NativeArticleApi.publishCheck(token, id) }
                publishWarnings = check.warnings
                if (!check.valid) {
                    error = check.errors.joinToString(nativePhrase("；", "; ")).ifBlank {
                        nativePhrase("文章暂时不能发布。", "The article cannot be published yet.")
                    }
                    return@launch
                }
                // 发布成功才更新本地状态并通知外层刷新，失败不能伪装已发布。
                withContext(Dispatchers.IO) { NativeArticleApi.publish(token, id) }
                status = "published"
                dirty = false
                message = nativePhrase("文章已发布", "Article published")
                onPublished()
            } catch (exception: Exception) {
                // 发布错误保留编辑内容并显示可读反馈。
                error = exception.message ?: nativePhrase("发布失败，请稍后重试。", "Publishing failed. Try again later.")
            } finally {
                // 任意结果都解除操作中的按钮禁用状态。
                saving = false
            }
        }
    }

    fun requestUnpublish() {
        if (serverId == null || status != "published" || saving) return
        articleActionsExpanded = false
        destructiveAction = "unpublish"
    }

    fun requestDelete() {
        if (serverId == null || saving) return
        articleActionsExpanded = false
        destructiveAction = "delete"
    }

    /** 执行用户确认的下架或回收站操作，成功后只清理当前文章草稿并刷新列表。 */
    fun runDestructiveAction() {
        // 有效凭据、文章编号和操作类型同时存在时才允许串行发起写请求。
        val token = session?.accessToken
        val id = serverId
        val action = destructiveAction
        if (token.isNullOrBlank() || id == null || action == null || saving) return
        scope.launch {
            saving = true
            error = null
            try {
                // 先完成服务器状态转换，再按动态编号清理本地副本，失败保留恢复内容。
                if (action == "unpublish") {
                    withContext(Dispatchers.IO) { NativeArticleApi.unpublish(token, id) }
                    status = "unpublished"
                    dirty = false
                    lastSavedSignature = signature(currentInput())
                    removeNativeLocalDraft(context, currentLocalDraftKey())
                    message = nativePhrase("文章已下架。", "Article unpublished.")
                    onSaved()
                } else {
                    withContext(Dispatchers.IO) { NativeArticleApi.deleteArticle(token, id) }
                    removeNativeLocalDraft(context, currentLocalDraftKey())
                    onSaved()
                    onBack()
                }
            } catch (exception: Exception) {
                // 业务失败停留当前页面，提示实际操作失败原因。
                error = exception.message ?: if (action == "unpublish") {
                    nativePhrase("文章下架失败，请稍后重试。", "The article could not be unpublished. Try again later.")
                } else {
                    nativePhrase("文章移入回收站失败，请稍后重试。", "The article could not be moved to the trash. Try again later.")
                }
            } finally {
                // 无论结果如何释放保存锁和确认状态，便于用户继续操作。
                saving = false
                destructiveAction = null
            }
        }
    }

    fun openScheduleDialog() {
        schedulePublishAt = schedulePublishAt.ifBlank { "" }
        scheduleUnpublishAt = scheduleUnpublishAt.ifBlank { "" }
        scheduleError = null
        scheduleDialogVisible = true
    }

    fun pickScheduleDate(current: String, onSelected: (String) -> Unit) {
        val fallback = LocalDateTime.now().plusHours(1)
        val initial = runCatching { LocalDateTime.parse(current, nativeScheduleFormatter) }.getOrElse { fallback }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        onSelected(LocalDateTime.of(year, month + 1, dayOfMonth, hour, minute).format(nativeScheduleFormatter))
                    },
                    initial.hour,
                    initial.minute,
                    true,
                ).show()
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }

    fun scheduleInstantOrNull(value: String): String? {
        if (value.isBlank()) return null
        return runCatching {
            LocalDateTime.parse(value, nativeScheduleFormatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toString()
        }.getOrElse {
            scheduleError = nativePhrase("时间格式无效，请重新选择。", "The date and time are invalid. Choose again.")
            null
        }
    }

    fun saveSchedule() {
        val token = session?.accessToken
        if (token.isNullOrBlank() || scheduleBusy) return
        scope.launch {
            scheduleBusy = true
            scheduleError = null
            try {
                val actionInput = currentInputForAction() ?: return@launch
                val requestedPublishAt = schedulePublishAt
                val requestedUnpublishAt = scheduleUnpublishAt
                val id = if (dirty || serverId == null) {
                    saveDraft(showMessage = false, manual = true, inputOverride = actionInput)?.id ?: return@launch
                } else {
                    serverId ?: return@launch
                }
                schedulePublishAt = requestedPublishAt
                scheduleUnpublishAt = requestedUnpublishAt
                val publishAt = scheduleInstantOrNull(schedulePublishAt)
                val unpublishAt = scheduleInstantOrNull(scheduleUnpublishAt)
                if (schedulePublishAt.isNotBlank() && publishAt == null || scheduleUnpublishAt.isNotBlank() && unpublishAt == null) return@launch
                withContext(Dispatchers.IO) {
                    NativeArticleApi.scheduleArticle(token, id, publishAt, unpublishAt)
                }
                scheduleDialogVisible = false
                message = if (publishAt == null && unpublishAt == null) {
                    nativePhrase("文章发布计划已取消", "Publishing schedule canceled")
                } else {
                    nativePhrase("文章发布计划已保存", "Publishing schedule saved")
                }
                onSaved()
            } catch (exception: Exception) {
                scheduleError = exception.message ?: nativePhrase(
                    "发布计划保存失败，请稍后重试。",
                    "The publishing schedule could not be saved. Try again later.",
                )
            } finally {
                scheduleBusy = false
            }
        }
    }

    fun undoContent() {
        val previous = undoStack.lastOrNull() ?: return
        undoStack = undoStack.dropLast(1)
        redoStack = (redoStack + content).takeLast(80)
        content = previous
        markChanged()
    }

    fun redoContent() {
        val next = redoStack.lastOrNull() ?: return
        redoStack = redoStack.dropLast(1)
        undoStack = (undoStack + content).takeLast(80)
        content = next
        markChanged()
    }

    fun insertInline(markdownBefore: String, markdownAfter: String, htmlTag: String, placeholder: String) {
        updateContent(
            if (contentFormat == "html") {
                wrapSelectedText(content, "<$htmlTag>", "</$htmlTag>", placeholder)
            } else {
                wrapSelectedText(content, markdownBefore, markdownAfter, placeholder)
            },
        )
    }

    fun insertHeading(level: Int) {
        if (contentFormat == "html") {
            updateContent(wrapSelectedText(content, "<h$level>", "</h$level>", ""))
        } else {
            val marker = "#".repeat(level) + " "
            updateContent(prefixSelectedLines(content) { marker })
        }
    }

    fun insertQuote() {
        if (contentFormat == "html") {
            updateContent(wrapSelectedText(content, "<blockquote>", "</blockquote>", ""))
        } else {
            updateContent(prefixSelectedLines(content) { "> " })
        }
    }

    fun insertList(kind: String) {
        if (contentFormat == "html") {
            val target = content
            val (start, end) = target.selectionBounds()
            val selected = target.text.substring(start, end)
            val lines = if (selected.isEmpty()) listOf("") else selected.split('\n')
            val listHtml = if (kind == "task") {
                buildNativeTaskListHtml(lines)
            } else {
                val tag = if (kind == "ordered") "ol" else "ul"
                val items = lines.joinToString("") { line -> "<li>${htmlEscape(line)}</li>" }
                "<$tag>$items</$tag>"
            }
            updateContent(replaceSelectedText(target, listHtml))
        } else {
            updateContent(
                when (kind) {
                    "ordered" -> prefixSelectedLines(content) { index -> "${index + 1}. " }
                    "task" -> prefixSelectedLines(content) { "- [ ] " }
                    else -> prefixSelectedLines(content) { "- " }
                },
            )
        }
    }

    fun insertFontSize(size: String) {
        updateContent(
            wrapSelectedText(
                content,
                "<span style=\"font-size:$size\">",
                "</span>",
                "",
            ),
        )
        fontSizeDialogVisible = false
    }

    fun insertHorizontalRule() {
        val replacement = if (contentFormat == "html") "<hr>\n" else "\n\n---\n\n"
        updateContent(replaceSelectedText(content, replacement))
    }

    fun clearFormatting() {
        val (start, end) = content.selectionBounds()
        val lineStart = if (start == end) {
            content.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        } else start
        val lineEnd = if (start == end) {
            content.text.indexOf('\n', end).let { if (it < 0) content.text.length else it }
        } else end
        val target = content.copy(selection = TextRange(lineStart, lineEnd))
        updateContent(
            replaceSelectedText(target, clearNativeFormatting(target.text.substring(lineStart, lineEnd), contentFormat)),
        )
    }

    fun formatCodeBlock() {
        val block = findNativeCodeBlock(content, contentFormat)
        if (block == null) {
            error = nativePhrase(
                "请把光标放在可格式化的代码块中。支持 JSON、HTML、CSS、JavaScript、TypeScript、SQL、Java、YAML 和 Markdown。",
                "Place the cursor in a supported code block. JSON, HTML, CSS, JavaScript, TypeScript, SQL, Java, YAML, and Markdown are supported.",
            )
            return
        }
        val original = content
        val originalFormat = contentFormat
        val source = htmlUnescapeForNativeCode(content.text.substring(block.bodyStart, block.bodyEnd))
        formattingCode = true
        error = null
        scope.launch {
            try {
                when (val result = runCatching { NativeCodeFormatter.format(context, source, block.language) }
                    .getOrDefault(NativeCodeFormatResult.Failure.Unavailable)) {
                    is NativeCodeFormatResult.Formatted -> {
                        if (content.text != original.text || contentFormat != originalFormat) {
                            error = nativePhrase("内容已发生变化，请重新选择代码块后再格式化。", "The content changed. Select the code block again and format it.")
                        } else {
                            val replacement = if (contentFormat == "html") htmlEscape(result.code) else result.code
                            val nextText = buildString(original.text.length - (block.bodyEnd - block.bodyStart) + replacement.length) {
                                append(original.text, 0, block.bodyStart)
                                append(replacement)
                                append(original.text, block.bodyEnd, original.text.length)
                            }
                            content = TextFieldValue(nextText, TextRange((block.bodyStart + replacement.length).coerceIn(0, nextText.length)))
                            markChanged()
                            message = nativePhrase("代码块已格式化", "Code block formatted")
                        }
                    }
                    NativeCodeFormatResult.Failure.UnsupportedLanguage -> error = nativePhrase("当前代码语言暂不支持格式化。", "This code language is not supported for formatting.")
                    NativeCodeFormatResult.Failure.InvalidSyntax -> error = nativePhrase("代码语法无法解析，请检查后重试。", "The code could not be parsed. Check its syntax and try again.")
                    NativeCodeFormatResult.Failure.TooLarge -> error = nativePhrase("代码块超过 200 KB，暂不进行格式化。", "Code blocks larger than 200 KB cannot be formatted.")
                    NativeCodeFormatResult.Failure.Unavailable -> error = nativePhrase("格式化引擎暂时不可用，请稍后重试。", "The formatting engine is temporarily unavailable. Try again later.")
                }
            } finally {
                formattingCode = false
            }
        }
    }

    fun openCodeLanguageDialog() {
        codeSelection = content.selection
        codeLanguageDialogVisible = true
    }

    fun insertSelectedCode(language: String) {
        val selection = codeSelection
        val target = selection?.let { content.copy(selection = it) } ?: content
        updateContent(
            updateNativeCodeBlockLanguage(target, contentFormat, language)
                ?: insertCodeBlock(target, language, contentFormat),
        )
        codeLanguageDialogVisible = false
    }

    fun openLinkDialog() {
        linkSelection = content.selection
        linkUrl = ""
        linkDialogVisible = true
    }

    fun removeLink() {
        removeNativeLink(content, contentFormat)?.let(::updateContent)
    }

    fun applyLink() {
        val url = linkUrl.trim()
        if (url.isBlank()) {
            error = nativePhrase("请输入链接地址。", "Enter a link URL.")
            return
        }
        val selection = linkSelection
        val target = selection?.let { content.copy(selection = it) } ?: content
        val (start, end) = target.selectionBounds()
        val selected = target.text.substring(start, end)
        val label = selected.ifBlank { url }
        val replacement = if (contentFormat == "html") {
            "<a href=\"${htmlEscape(url)}\">${htmlEscape(label)}</a>"
        } else {
            "[${label.replace("]", "\\]")}](${url.replace(")", "\\)")})"
        }
        updateContent(replaceSelectedText(target, replacement))
        linkDialogVisible = false
        linkUrl = ""
    }

    fun openResourceDialog() {
        resourceSelection = content.selection
        resourcePoints = "10"
        resourceDialogVisible = true
    }

    fun insertResourceBlock() {
        val points = resourcePoints.trim().toIntOrNull()
        if (points == null || points !in 1..10000) {
            error = nativePhrase("积分必须是 1 到 10000 的整数。", "Points must be an integer from 1 to 10000.")
            return
        }
        if (visualEditor && resourceSelection == null) {
            val body = visualSelectionHtml.trim().ifBlank {
                "<p>${nativePhrase("在这里输入资源内容", "Enter resource content here")}</p>"
            }
            queueVisualInsert("<resource-block data-points=\"$points\">$body</resource-block>")
            visualSelectionHtml = ""
            resourceDialogVisible = false
            return
        }
        val selection = resourceSelection
        val target = selection?.let { content.copy(selection = it) } ?: content
        val (start, end) = target.selectionBounds()
        val selected = target.text.substring(start, end).trim()
        val replacement = if (contentFormat == "html") {
            val body = selected.ifBlank {
                "<p>${nativePhrase("在这里输入资源内容", "Enter resource content here")}</p>"
            }
            "<resource-block data-points=\"$points\">$body</resource-block>"
        } else {
            val body = selected.ifBlank { nativePhrase("在这里输入资源内容", "Enter resource content here") }
            ":::resource{points=$points}\n$body\n:::"
        }
        updateContent(replaceSelectedText(target, replacement))
        resourceDialogVisible = false
    }

    fun openAiAssistant(selectedText: String? = null) {
        aiSelection = if (visualEditor && selectedText != null) null else content.selection
        aiContextHint = selectedText?.takeIf { it.isNotBlank() }
        visualAiMode = visualEditor && selectedText != null
        if (aiOperation !in nativeArticleAssistantOperations().map { it.value }) aiOperation = "polish"
        aiResult = null
        aiDialogVisible = true
    }

    fun requestAiAssist() {
        val token = session?.accessToken
        if (token.isNullOrBlank() || aiBusy) return
        aiBusy = true
        error = null
        scope.launch {
            try {
                val contextHint = aiContextHint ?: content.selectionBounds().let { (start, end) ->
                    content.text.substring(start, end).takeIf { it.isNotBlank() }
                }.orEmpty()
                val result = withContext(Dispatchers.IO) {
                    NativeAiApi.articleAssistant(
                        accessToken = token,
                        operation = aiOperation,
                        title = title,
                        content = content.text,
                        selectedText = contextHint,
                        category = category,
                        tags = tags,
                        locale = session?.locale ?: "zh-CN",
                    )
                }
                aiResult = result.takeIf { it.text.trim().isNotBlank() }
                if (aiResult == null) {
                    error = nativePhrase("AI 没有返回可应用的内容。", "AI did not return content that can be applied.")
                }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("AI 写作请求失败。", "The AI writing request failed.")
            } finally {
                aiBusy = false
            }
        }
    }

    fun insertAiResult() {
        val result = aiResult ?: return
        val text = result.text.trim()
        if (text.isBlank()) return
        when (result.operation) {
            "title" -> {
                title = text.lineSequence().firstOrNull().orEmpty().take(120)
                markChanged()
                message = nativePhrase("AI 标题已应用", "AI title applied")
            }
            "summary" -> {
                summary = text.take(300)
                markChanged()
                message = nativePhrase("AI 摘要已应用", "AI summary applied")
            }
            "taxonomy" -> {
                val taxonomy = parseNativeAiTaxonomy(text)
                if (taxonomy == null) {
                    error = nativePhrase(
                        "没有识别出分类和标签，请重新生成。",
                        "No category or tags were recognized. Generate them again.",
                    )
                    return
                }
                if (taxonomy.first.isNotBlank()) category = taxonomy.first
                tags = taxonomy.second.joinToString(", ")
                markChanged()
                message = nativePhrase("AI 分类和标签已应用", "AI category and tags applied")
            }
            else -> {
                val editorText = nativeAiInsertText(text, contentFormat)
                if (visualAiMode) {
                    queueVisualInsert(editorText)
                } else {
                    val selection = aiSelection
                    val target = selection?.let { content.copy(selection = it) } ?: content
                    updateContent(replaceSelectedText(target, editorText))
                }
                message = nativePhrase(
                    "AI 内容已插入正文，请检查后保存",
                    "AI content was inserted into the body. Review it before saving.",
                )
            }
        }
        aiDialogVisible = false
        aiResult = null
        aiContextHint = null
        visualAiMode = false
    }

    val editorToolbarScroll = rememberScrollState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (articleId == null) nativePhrase("新建文章", "New article")
                            else nativePhrase("编辑文章", "Edit article"),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                saving -> nativePhrase("正在保存…", "Saving...")
                                dirty -> nativePhrase("有未保存修改", "Unsaved changes")
                                status == "published" -> nativePhrase("已发布", "Published")
                                else -> nativePhrase("草稿已同步", "Draft synced")
                            },
                            color = HlovetUi.muted,
                            fontSize = 11.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestLeave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (currentInputForAction() != null) previewVisible = true
                            }
                        },
                        enabled = loaded,
                    ) {
                        Icon(Icons.Filled.Preview, contentDescription = nativePhrase("预览", "Preview"), tint = HlovetUi.accent)
                    }
                    IconButton(onClick = ::openScheduleDialog, enabled = loaded && !saving) {
                        Icon(Icons.Filled.NotificationsActive, contentDescription = nativePhrase("发布计划", "Publishing schedule"), tint = HlovetUi.accent)
                    }
                    IconButton(onClick = ::openVersions, enabled = loaded && serverId != null) {
                        Icon(Icons.Filled.History, contentDescription = nativePhrase("版本历史", "Version history"), tint = HlovetUi.muted)
                    }
                    if (serverId != null) {
                        Box {
                            IconButton(
                                onClick = { articleActionsExpanded = true },
                                enabled = loaded && !saving,
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = nativePhrase("文章操作", "Article actions"),
                                    tint = HlovetUi.muted,
                                )
                            }
                            // 文章操作使用独立弹层，不依赖正文玻璃背景保证菜单文字清晰。
                            DropdownMenu(
                                expanded = articleActionsExpanded,
                                onDismissRequest = { articleActionsExpanded = false },
                                shape = HlovetUi.menuShape,
                                containerColor = HlovetUi.menuSurface,
                                tonalElevation = 0.dp,
                            ) {
                                if (status == "published") {
                                    DropdownMenuItem(
                                        text = { Text(nativePhrase("下架文章", "Unpublish article")) },
                                        leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                                        onClick = ::requestUnpublish,
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(nativePhrase("移入回收站", "Move to trash")) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = HlovetUi.secondaryAccent) },
                                    onClick = ::requestDelete,
                                )
                            }
                        }
                    }
                    Icon(
                        imageVector = when {
                            saving -> Icons.Filled.CloudOff
                            dirty -> Icons.Filled.WarningAmber
                            else -> Icons.Filled.CloudDone
                        },
                        contentDescription = null,
                        tint = when {
                            saving -> HlovetUi.muted
                            dirty -> HlovetUi.secondaryAccent
                            else -> HlovetUi.accent
                        },
                        modifier = Modifier.padding(end = 16.dp).size(19.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = ::requestLeave, enabled = !saving, modifier = Modifier.weight(1f)) {
                    Text(nativePhrase("返回", "Back"))
                }
                Button(onClick = ::requestSave, enabled = loaded && readyForSave && !saving && !uploading && attachmentBusyId == 0 && recoveryDraft == null, modifier = Modifier.weight(1.35f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(nativePhrase("保存草稿", "Save draft"), fontSize = 12.sp, maxLines = 1)
                }
                Button(onClick = ::requestPublish, enabled = loaded && readyForSave && !saving && !uploading && attachmentBusyId == 0 && recoveryDraft == null, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(nativePhrase("发布", "Publish"))
                }
            }
        },
    ) { padding ->
        if (!loaded) {
            WriteStateCard(nativePhrase("正在读取文章…", "Loading article..."), null)
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!error.isNullOrBlank()) {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = HlovetUi.secondaryAccent)
                            Spacer(Modifier.width(8.dp))
                            Text(error!!, color = HlovetUi.secondaryAccent, fontSize = 12.sp)
                        }
                    }
                }
                if (!message.isNullOrBlank()) {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = HlovetUi.accent)
                            Spacer(Modifier.width(8.dp))
                            Text(message!!, color = HlovetUi.accent, fontSize = 12.sp)
                        }
                    }
                }
                if (publishWarnings.isNotEmpty()) {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(nativePhrase("发布提示", "Publishing notes"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            publishWarnings.forEach { warning -> Text(warning, color = HlovetUi.muted, fontSize = 11.sp) }
                        }
                    }
                }
                recoveryDraft?.let {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(nativePhrase("发现未提交的本地草稿", "Unsaved local draft found"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    nativePhrase(
                                        "恢复会覆盖当前编辑器内容，恢复后请检查并保存。",
                                        "Restoring replaces the current editor content. Review and save it afterward.",
                                    ),
                                    color = HlovetUi.muted,
                                    fontSize = 11.sp,
                                )
                            }
                            TextButton(onClick = ::discardRecoveryDraft) { Text(nativePhrase("丢弃", "Discard"), fontSize = 11.sp) }
                            TextButton(onClick = ::restoreRecoveryDraft) { Text(nativePhrase("恢复", "Restore"), fontSize = 11.sp) }
                        }
                    }
                }
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it; markChanged() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(nativePhrase("标题", "Title")) },
                            singleLine = true,
                            colors = editorFieldColors(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = titleColor,
                                onValueChange = { value ->
                                    titleColor = value.filter { it == '#' || it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(7)
                                    markChanged()
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text(nativePhrase("标题颜色", "Title color")) },
                                placeholder = { Text("#2B2530") },
                                singleLine = true,
                                colors = editorFieldColors(),
                            )
                            val colorPreview = normalizeNativeTitleColor(titleColor)?.let {
                                runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
                            }
                            Text(
                                " ",
                                modifier = Modifier.size(30.dp).background(colorPreview ?: HlovetUi.surfaceLow, HlovetUi.rowShape),
                            )
                            TextButton(onClick = { titleColor = ""; markChanged() }) {
                                Text(nativePhrase("清除", "Clear"), fontSize = 11.sp)
                            }
                        }
                        OutlinedTextField(
                            value = summary,
                            onValueChange = { summary = it; markChanged() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(nativePhrase("摘要", "Summary")) },
                            minLines = 2,
                            maxLines = 4,
                            colors = editorFieldColors(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nativePhrase("正文格式", "Body format"), color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                            FilterChip(
                                selected = contentFormat == "markdown",
                                onClick = {
                                    scope.launch {
                                        val actionInput = currentInputForAction() ?: return@launch
                                        if (contentFormat == "html") {
                                            updateContent(TextFieldValue(nativeHtmlToMarkdown(actionInput.content)))
                                        }
                                        contentFormat = "markdown"
                                        visualEditor = false
                                        markChanged()
                                        message = nativePhrase("已将 HTML 转换为 Markdown，请检查后保存。", "HTML was converted to Markdown. Review it before saving.")
                                    }
                                },
                                label = { Text("Markdown", fontSize = 12.sp) },
                                border = null,
                                colors = editorChipColors(),
                            )
                            Spacer(Modifier.width(7.dp))
                            FilterChip(
                                selected = contentFormat == "html",
                                onClick = { contentFormat = "html"; markChanged() },
                                label = { Text("HTML", fontSize = 12.sp) },
                                border = null,
                                colors = editorChipColors(),
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                                enabled = !uploading && !saving && attachmentBusyId == 0 && readyForSave,
                            ) {
                                Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    if (uploading) nativePhrase("上传中", "Uploading")
                                    else nativePhrase("附件", "Attachments"),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(nativePhrase("编辑方式", "Editing mode"), color = HlovetUi.muted, fontSize = 12.sp)
                            FilterChip(
                                selected = !visualEditor,
                                onClick = {
                                    scope.launch {
                                        if (currentInputForAction() != null) visualEditor = false
                                    }
                                },
                                label = { Text(nativePhrase("源码", "Source"), fontSize = 12.sp) },
                                border = null,
                                colors = editorChipColors(),
                            )
                            FilterChip(
                                selected = visualEditor,
                                onClick = {
                                    if (!visualEditor) {
                                        if (contentFormat == "markdown") {
                                            val rendered = previewMarkdownRenderer.render(previewMarkdownParser.parse(content.text))
                                            updateContent(TextFieldValue(sanitizeNativeArticleHtml(rendered)))
                                            contentFormat = "html"
                                            markChanged()
                                        } else {
                                            updateContent(TextFieldValue(sanitizeNativeArticleHtml(content.text)))
                                        }
                                        visualEditor = true
                                    }
                                },
                                label = {
                                    Text(
                                        if (contentFormat == "markdown") {
                                            nativePhrase("可视编辑并转为 HTML", "Visual editor and convert to HTML")
                                        } else {
                                            nativePhrase("可视编辑", "Visual editor")
                                        },
                                        fontSize = 12.sp,
                                    )
                                },
                                border = null,
                                colors = editorChipColors(),
                            )
                        }
                        if (uploadedAttachments.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                uploadedAttachments.forEach { attachment ->
                                    FilterChip(
                                        selected = true,
                                        onClick = { removeUploadedAttachment(attachment) },
                                        label = {
                                            Text(
                                                attachment.originalName,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(13.dp)) },
                                         trailingIcon = {
                                             Icon(
                                                 Icons.Filled.Close,
                                                  contentDescription = nativePhrase("移除附件", "Remove attachment"),
                                                 tint = if (attachmentBusyId == attachment.id) HlovetUi.muted else HlovetUi.secondaryAccent,
                                                 modifier = Modifier.size(13.dp),
                                             )
                                         },
                                        border = null,
                                        colors = editorChipColors(),
                                         enabled = attachmentBusyId == 0 && !uploading && !saving && readyForSave,
                                        modifier = Modifier.widthIn(max = 220.dp),
                                    )
                                }
                            }
                        }
                        if (!visualEditor) Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(editorToolbarScroll),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            TextButton(onClick = { insertHeading(1) }, modifier = Modifier.height(34.dp)) {
                                Text("H1", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { insertHeading(2) }, modifier = Modifier.height(34.dp)) {
                                Text("H2", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { insertHeading(3) }, modifier = Modifier.height(34.dp)) {
                                Text("H3", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { fontSizeDialogVisible = true }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.FormatSize, contentDescription = nativePhrase("字号", "Font size"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { insertInline("**", "**", "strong", "") }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.FormatBold, contentDescription = nativePhrase("粗体", "Bold"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { insertInline("*", "*", "em", "") }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.FormatItalic, contentDescription = nativePhrase("斜体", "Italic"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { insertInline("<s>", "</s>", "s", "") }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.FormatStrikethrough, contentDescription = nativePhrase("删除线", "Strikethrough"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { insertInline("<u>", "</u>", "u", "") }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.FormatUnderlined, contentDescription = nativePhrase("下划线", "Underline"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = ::insertQuote, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.FormatQuote, contentDescription = nativePhrase("引用", "Quote"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { insertList("bullet") }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.FormatListBulleted, contentDescription = nativePhrase("无序列表", "Bulleted list"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { insertList("ordered") }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.FormatListNumbered, contentDescription = nativePhrase("有序列表", "Numbered list"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { insertList("task") }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.Check, contentDescription = nativePhrase("任务列表", "Task list"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { openCodeLanguageDialog() }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.Code, contentDescription = nativePhrase("插入代码块", "Insert code block"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = ::formatCodeBlock, enabled = !formattingCode, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = nativePhrase("格式化代码", "Format code"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = ::insertHorizontalRule, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.HorizontalRule, contentDescription = nativePhrase("分割线", "Horizontal rule"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = ::openLinkDialog, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.Link, contentDescription = nativePhrase("插入链接", "Insert link"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(
                                onClick = ::removeLink,
                                enabled = removeNativeLink(content, contentFormat) != null,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Filled.LinkOff, contentDescription = nativePhrase("取消链接", "Remove link"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = ::openResourceDialog, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.MonetizationOn, contentDescription = nativePhrase("插入积分资源", "Insert point resource"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = ::openAiAssistant, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = nativePhrase("AI 写作助手", "AI writing assistant"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = ::clearFormatting, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.FormatClear, contentDescription = nativePhrase("清除格式", "Clear formatting"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = ::undoContent, enabled = undoStack.isNotEmpty(), modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.Undo, contentDescription = nativePhrase("撤销", "Undo"), modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = ::redoContent, enabled = redoStack.isNotEmpty(), modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.Redo, contentDescription = nativePhrase("重做", "Redo"), modifier = Modifier.size(17.dp))
                            }
                        }
                        if (visualEditor) {
                            NativeRichEditorWebView(
                                content = content.text,
                                onContentChange = { html ->
                                    if (html != content.text) {
                                        undoStack = (undoStack + content).takeLast(80)
                                        redoStack = emptyList()
                                        content = TextFieldValue(html)
                                        markChanged()
                                        // 附件插入的内部同步不应让成功反馈在用户看见前消失。
                                        visualInsertFeedback?.let { feedback -> message = feedback; visualInsertFeedback = null }
                                    }
                                },
                                onResourceRequest = { selectedHtml ->
                                    resourceSelection = null
                                    visualSelectionHtml = selectedHtml
                                    resourcePoints = "10"
                                    resourceDialogVisible = true
                                },
                                onAiRequest = { selectedText -> openAiAssistant(selectedText) },
                                onAttachmentRequest = {
                                    // WebView 工具栏不能绕过原生上传中的互斥保护。
                                    if (!uploading && !saving && attachmentBusyId == 0 && readyForSave) attachmentPicker.launch(arrayOf("*/*"))
                                },
                                onClipboardFile = { file, complete ->
                                    scope.launch { complete(uploadClipboardFile(file)) }
                                },
                                onClipboardFileRejected = { message -> error = message },
                                onSystemFiles = { uris, permissions ->
                                    // 立即进入权限所有者的 try/finally，页面取消也不能跳过临时授权回收。
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        try {
                                            uploadSelectedFiles(uris)
                                        } finally {
                                            permissions?.release()
                                        }
                                    }
                                },
                                visualInsertRequest = visualInsertHtml?.let { NativeRichEditorInsertRequest(visualInsertNonce, it) },
                                onVisualInsertHandled = { visualInsertHtml = null },
                                editorSnapshot = editorSnapshot,
                                mediaSession = session,
                                mediaAttachments = uploadedAttachments,
                                modifier = Modifier.fillMaxWidth().height(390.dp),
                            )
                        } else {
                            OutlinedTextField(
                                value = content,
                                onValueChange = ::updateContent,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                                label = { Text(nativePhrase("正文", "Body")) },
                                placeholder = {
                                    Text(
                                        if (contentFormat == "html") nativePhrase("输入 HTML 内容…", "Enter HTML...")
                                        else nativePhrase("输入 Markdown 内容…", "Enter Markdown..."),
                                    )
                                },
                                minLines = 12,
                                colors = editorFieldColors(),
                            )
                        }
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it; markChanged() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(nativePhrase("分类", "Category")) },
                            singleLine = true,
                            readOnly = configuredCategories.isNotEmpty(),
                            colors = editorFieldColors(),
                        )
                        if (configuredCategories.isNotEmpty()) {
                            Text(nativePhrase("站点分类", "Site categories"), color = HlovetUi.muted, fontSize = 11.sp)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                configuredCategories.forEach { option ->
                                    FilterChip(
                                        selected = category == option,
                                        onClick = { category = option; markChanged() },
                                        label = { Text(option, fontSize = 11.sp) },
                                        border = null,
                                        colors = editorChipColors(),
                                    )
                                }
                            }
                        }
                        Text(nativePhrase("标签（最多 6 个）", "Tags (up to 6)"), color = HlovetUi.muted, fontSize = 11.sp)
                        if (configuredTags.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                configuredTags.forEach { option ->
                                    FilterChip(
                                        selected = selectedTags.contains(option),
                                        onClick = { toggleTag(option) },
                                        label = { Text(option, fontSize = 11.sp) },
                                        border = null,
                                        colors = editorChipColors(),
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = tags,
                            onValueChange = {
                                val next = it.split(',', '，', '、').map(String::trim).filter(String::isNotBlank).distinct()
                                if (next.size <= 6) {
                                    tags = it
                                    markChanged()
                                } else {
                                    error = nativePhrase("最多选择 6 个标签。", "Select up to 6 tags.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(nativePhrase("已选标签", "Selected tags")) },
                            placeholder = { Text(nativePhrase("多个标签用逗号分隔", "Separate tags with commas")) },
                            singleLine = true,
                            readOnly = configuredTags.isNotEmpty(),
                            colors = editorFieldColors(),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(nativePhrase("可见范围", "Visibility"), color = HlovetUi.muted, fontSize = 12.sp)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                listOf(
                                    "public" to nativePhrase("公开", "Public"),
                                    "authenticated" to nativePhrase("登录可见", "Signed-in users"),
                                    "role_restricted" to nativePhrase("按角色", "By role"),
                                    "private" to nativePhrase("仅自己", "Only me"),
                                ).forEach { (value, label) ->
                                    FilterChip(
                                        selected = visibility == value,
                                        onClick = { visibility = value; if (value != "role_restricted") roleCodes = emptyList(); markChanged() },
                                        label = { Text(label, fontSize = 11.sp) },
                                        border = null,
                                        colors = editorChipColors(),
                                    )
                                }
                            }
                            if (visibility == "role_restricted") {
                                Text(nativePhrase("允许的角色（至少选择一个）", "Allowed roles (select at least one)"), color = HlovetUi.muted, fontSize = 11.sp)
                                val knownCodes = roleOptions.map { it.code }.toSet()
                                val visibleRoles = roleOptions + roleCodes
                                    .filter { it !in knownCodes }
                                    .map { NativeRole(code = it, name = it, level = 0) }
                                if (visibleRoles.isEmpty()) {
                                    Text(
                                        nativePhrase(
                                            "暂时没有可用角色，检查网络后重新进入编辑页。",
                                            "No roles are available. Check the network and reopen the editor.",
                                        ),
                                        color = HlovetUi.secondaryAccent,
                                        fontSize = 11.sp,
                                    )
                                } else {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        visibleRoles.forEach { role ->
                                            FilterChip(
                                                selected = roleCodes.contains(role.code),
                                                onClick = {
                                                    roleCodes = if (roleCodes.contains(role.code)) {
                                                        roleCodes.filter { it != role.code }
                                                    } else {
                                                        roleCodes + role.code
                                                    }
                                                    markChanged()
                                                },
                                                label = { Text(role.name, fontSize = 11.sp) },
                                                border = null,
                                                colors = editorChipColors(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(76.dp))
            }
        }
    }
    BackHandler(
        enabled = !previewVisible &&
            !versionsVisible &&
            !codeLanguageDialogVisible &&
            !linkDialogVisible &&
            !fontSizeDialogVisible &&
            !resourceDialogVisible &&
            !aiDialogVisible &&
            !scheduleDialogVisible,
        onBack = {
            if (articleActionsExpanded) articleActionsExpanded = false else requestLeave()
        },
    )
    if (leaveConfirmationVisible) {
        HlovetAlertDialog(
            onDismissRequest = { leaveConfirmationVisible = false },
            title = { Text(nativePhrase("有未保存修改", "Unsaved changes")) },
            text = {
                Text(
                    nativePhrase(
                        "离开前可以先保存当前文章。放弃返回会删除本机保存的未提交草稿。",
                        "You can save the current article before leaving. Discarding will remove the unsaved local draft from this device.",
                    ),
                    color = HlovetUi.muted,
                    fontSize = 12.sp,
                )
            },
            dismissButton = {
                TextButton(onClick = ::discardUnsavedAndLeave, enabled = !saving) {
                    Text(nativePhrase("放弃并返回", "Discard and leave"), color = HlovetUi.secondaryAccent)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val saved = saveDraft(showMessage = false, manual = true)
                            if (saved != null) {
                                leaveConfirmationVisible = false
                                onBack()
                            }
                        }
                    },
                    enabled = !saving,
                ) {
                    Text(nativePhrase("保存并返回", "Save and leave"), color = HlovetUi.accent)
                }
            },
        )
    }
    destructiveAction?.let { action ->
        val deleting = action == "delete"
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) destructiveAction = null },
            icon = {
                Icon(
                    if (deleting) Icons.Filled.Delete else Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = HlovetUi.secondaryAccent,
                )
            },
            title = {
                Text(
                    if (deleting) nativePhrase("移入回收站", "Move to trash") else nativePhrase("下架文章", "Unpublish article"),
                )
            },
            text = {
                Text(
                    if (deleting) {
                        nativePhrase(
                            "确定要把这篇文章移入回收站吗？文章将不再出现在公开内容中，之后仍可从文章管理中恢复。",
                            "Move this article to the trash? It will leave public content and can be restored from article management later.",
                        )
                    } else {
                        nativePhrase(
                            "确定要下架这篇文章吗？下架后文章不再出现在公开内容中。",
                            "Unpublish this article? It will no longer appear in public content.",
                        )
                    },
                    color = HlovetUi.muted,
                    fontSize = 12.sp,
                )
            },
            dismissButton = {
                TextButton(onClick = { destructiveAction = null }, enabled = !saving) {
                    Text(nativePhrase("取消", "Cancel"))
                }
            },
            confirmButton = {
                TextButton(onClick = ::runDestructiveAction, enabled = !saving) {
                    Text(
                        if (deleting) nativePhrase("移入回收站", "Move to trash") else nativePhrase("下架", "Unpublish"),
                        color = HlovetUi.secondaryAccent,
                    )
                }
            },
        )
    }
    if (codeLanguageDialogVisible) {
        val currentCodeLanguage = findNativeCodeBlock(
            content.copy(selection = codeSelection ?: content.selection),
            contentFormat,
        )?.language
        HlovetAlertDialog(
            onDismissRequest = { codeLanguageDialogVisible = false },
            icon = { Icon(Icons.Filled.Code, contentDescription = null, tint = HlovetUi.accent) },
            title = {
                Text(
                    if (currentCodeLanguage == null) nativePhrase("插入代码块", "Insert code block")
                    else nativePhrase("代码语言", "Code language"),
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(nativeCodeLanguageOptions(), key = { it.value }) { option ->
                        ListItem(
                            headlineContent = { Text(option.label, fontSize = 13.sp) },
                            supportingContent = { Text(option.value, color = HlovetUi.muted, fontSize = 10.sp) },
                            leadingContent = { Icon(Icons.Filled.Code, contentDescription = null, tint = HlovetUi.accent) },
                            trailingContent = {
                                if (option.value == currentCodeLanguage) {
                                    Icon(Icons.Filled.Check, contentDescription = nativePhrase("当前语言", "Current language"), tint = HlovetUi.accent)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().clickable { insertSelectedCode(option.value) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { codeLanguageDialogVisible = false }) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }
    if (linkDialogVisible) {
        HlovetAlertDialog(
            onDismissRequest = {
                linkDialogVisible = false
                linkUrl = ""
            },
            icon = { Icon(Icons.Filled.Link, contentDescription = null, tint = HlovetUi.accent) },
            title = { Text(nativePhrase("插入链接", "Insert link")) },
            text = {
                OutlinedTextField(
                    value = linkUrl,
                    onValueChange = { linkUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(nativePhrase("链接地址", "Link URL")) },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    colors = editorFieldColors(),
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    linkDialogVisible = false
                    linkUrl = ""
                }) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = {
                TextButton(onClick = ::applyLink, enabled = linkUrl.isNotBlank()) { Text(nativePhrase("插入", "Insert")) }
            },
        )
    }
    if (fontSizeDialogVisible) {
        HlovetAlertDialog(
            onDismissRequest = { fontSizeDialogVisible = false },
            icon = { Icon(Icons.Filled.FormatSize, contentDescription = null, tint = HlovetUi.accent) },
            title = { Text(nativePhrase("选择字号", "Choose font size")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    nativeFontSizeOptions.forEach { (value, label) ->
                        TextButton(
                            onClick = { insertFontSize(value) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${label}px", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { fontSizeDialogVisible = false }) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }
    if (resourceDialogVisible) {
        HlovetAlertDialog(
            onDismissRequest = { resourceDialogVisible = false },
            icon = { Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = HlovetUi.accent) },
            title = { Text(nativePhrase("插入积分资源", "Insert point resource")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        nativePhrase(
                            "选中的内容会变成需要积分兑换的资源区块；没有选中内容时会插入一个可编辑的占位内容。",
                            "Selected content becomes a point-gated resource. With no selection, an editable placeholder is inserted.",
                        ),
                        color = HlovetUi.muted,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = resourcePoints,
                        onValueChange = { resourcePoints = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(nativePhrase("兑换积分", "Points required")) },
                        singleLine = true,
                        colors = editorFieldColors(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { resourceDialogVisible = false }) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = { TextButton(onClick = ::insertResourceBlock) { Text(nativePhrase("插入", "Insert")) } },
        )
    }
    if (aiDialogVisible) {
        HlovetAlertDialog(
            onDismissRequest = { if (!aiBusy) aiDialogVisible = false },
            icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = HlovetUi.accent) },
            title = { Text(nativePhrase("AI 写作助手", "AI writing assistant")) },
            text = {
                Column(
                    modifier = Modifier.heightIn(min = 180.dp, max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        nativePhrase(
                            "选择一个结构化操作，生成结果后先预览；只有点击应用才会修改文章。",
                            "Choose a structured action and preview the result. The article changes only after you apply it.",
                        ),
                        color = HlovetUi.muted,
                        fontSize = 12.sp,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        nativeArticleAssistantOperations().forEach { operation ->
                            FilterChip(
                                selected = aiOperation == operation.value,
                                onClick = {
                                    aiOperation = operation.value
                                    aiResult = null
                                },
                                label = { Text(operation.label, fontSize = 11.sp) },
                                border = null,
                                colors = editorChipColors(),
                            )
                        }
                    }
                    nativeArticleAssistantOperations().firstOrNull { it.value == aiOperation }?.let { operation ->
                        Text(operation.description, color = HlovetUi.muted, fontSize = 11.sp)
                    }
                    if (!aiContextHint.isNullOrBlank()) {
                        Text(
                            nativePhrase(
                                "已选择正文片段，正文类操作将优先处理该片段。",
                                "A body selection is active. Body actions will process it first.",
                            ),
                            color = HlovetUi.accent,
                            fontSize = 11.sp,
                        )
                    }
                    aiResult?.let { result ->
                        Text(nativePhrase("生成预览", "Generated preview"), color = HlovetUi.accent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        if (result.model.isNotBlank()) {
                            Text(
                                "${result.model}${if (result.durationMs > 0) " · ${result.durationMs} ms" else ""}",
                                color = HlovetUi.muted,
                                fontSize = 10.sp,
                            )
                        }
                        Text(result.text, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    if (aiBusy) Text(nativePhrase("AI 正在生成…", "AI is generating..."), color = HlovetUi.muted, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { aiDialogVisible = false }, enabled = !aiBusy) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = {
                if (aiResult == null) {
                    TextButton(onClick = ::requestAiAssist, enabled = !aiBusy) {
                        Text(if (aiBusy) nativePhrase("生成中…", "Generating...") else nativePhrase("生成预览", "Generate preview"))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { aiResult = null }, enabled = !aiBusy) { Text(nativePhrase("重新生成", "Generate again")) }
                        TextButton(onClick = ::insertAiResult, enabled = !aiBusy) {
                            Text(
                                if (aiOperation == "title" || aiOperation == "summary" || aiOperation == "taxonomy") {
                                    nativePhrase("应用结果", "Apply result")
                                } else {
                                    nativePhrase("应用到正文", "Apply to body")
                                },
                            )
                        }
                    }
                }
            },
        )
    }
    if (scheduleDialogVisible) {
        HlovetAlertDialog(
            onDismissRequest = { if (!scheduleBusy) scheduleDialogVisible = false },
            icon = { Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = HlovetUi.accent) },
            title = { Text(nativePhrase("设置发布计划", "Set publishing schedule")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        nativePhrase(
                            "可设置未来的发布时间和可选的自动下线时间；清空两个时间并确定即可取消现有计划。",
                            "Set a future publish time and an optional automatic unpublish time. Clear both to cancel the schedule.",
                        ),
                        color = HlovetUi.muted,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = schedulePublishAt,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().clickable { pickScheduleDate(schedulePublishAt) { schedulePublishAt = it } },
                        readOnly = true,
                        label = { Text(nativePhrase("发布时间", "Publish time")) },
                        placeholder = { Text(nativePhrase("点击选择时间", "Tap to choose a time")) },
                        singleLine = true,
                        colors = editorFieldColors(),
                    )
                    OutlinedTextField(
                        value = scheduleUnpublishAt,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().clickable { pickScheduleDate(scheduleUnpublishAt) { scheduleUnpublishAt = it } },
                        readOnly = true,
                        label = { Text(nativePhrase("下线时间（可选）", "Unpublish time (optional)")) },
                        placeholder = { Text(nativePhrase("点击选择时间", "Tap to choose a time")) },
                        singleLine = true,
                        colors = editorFieldColors(),
                    )
                    if (!scheduleError.isNullOrBlank()) {
                        Text(scheduleError!!, color = HlovetUi.secondaryAccent, fontSize = 11.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { scheduleDialogVisible = false }, enabled = !scheduleBusy) {
                    Text(nativePhrase("取消", "Cancel"))
                }
            },
            confirmButton = {
                TextButton(onClick = ::saveSchedule, enabled = !scheduleBusy) {
                    Text(
                        if (scheduleBusy) nativePhrase("保存中…", "Saving...") else nativePhrase("确定", "Confirm"),
                        color = HlovetUi.accent,
                    )
                }
            },
        )
    }
    if (previewVisible) {
        NativeArticlePreviewDialog(
            title = title,
            titleColor = titleColor,
            summary = summary,
            content = content.text,
            contentFormat = contentFormat,
            mediaSession = session,
            mediaAttachments = uploadedAttachments,
            onDismiss = { previewVisible = false },
        )
    }
    if (versionsVisible) {
        NativeArticleVersionsDialog(
            versions = versions,
            selectedVersion = selectedVersion,
            loading = versionsLoading,
            mediaSession = session,
            mediaAttachments = uploadedAttachments,
            onSelect = ::selectVersion,
            onRestore = ::restoreSelectedVersion,
            onDismiss = {
                if (!versionsLoading) {
                    versionsVisible = false
                    selectedVersion = null
                }
            },
            onBackToList = { selectedVersion = null },
        )
    }
}

/** 预览 title、titleColor、summary 与指定格式 content；mediaSession 和 mediaAttachments 提供受控媒体，onDismiss 关闭弹窗。 */
@Composable
private fun NativeArticlePreviewDialog(
    title: String,
    titleColor: String,
    summary: String,
    content: String,
    contentFormat: String,
    mediaSession: NativeSession?,
    mediaAttachments: List<NativeArticleAttachment>,
    onDismiss: () -> Unit,
) {
    // 弹窗内独立滚动，媒体仅从当前文章附件列表加载，不开放网页导航。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(16.dp),
            shape = HlovetUi.cardShape,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(nativePhrase("文章预览", "Article preview"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            "${if (contentFormat == "html") "HTML" else "Markdown"} · ${nativePhrase("本地预览", "Local preview")}",
                            color = HlovetUi.muted,
                            fontSize = 11.sp,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭预览", "Close preview"), tint = HlovetUi.muted)
                    }
                }
                NativePreviewWebView(
                    title = title,
                    titleColor = titleColor,
                    summary = summary,
                    content = content,
                    contentFormat = contentFormat,
                    mediaSession = mediaSession,
                    mediaAttachments = mediaAttachments,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 展示 versions 列表或 selectedVersion；loading 控制操作，媒体只使用当前文章仍存在的附件，回调负责查看、恢复和关闭。 */
@Composable
private fun NativeArticleVersionsDialog(
    versions: List<NativeArticleVersionSummary>,
    selectedVersion: NativeArticleVersion?,
    loading: Boolean,
    mediaSession: NativeSession?,
    mediaAttachments: List<NativeArticleAttachment>,
    onSelect: (NativeArticleVersionSummary) -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
    onBackToList: () -> Unit,
) {
    // 历史正文保留原内容，但不能扩大媒体权限；已删除的附件不会因为历史引用而重新请求。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(16.dp),
            shape = HlovetUi.cardShape,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedVersion != null) {
                        IconButton(onClick = onBackToList, enabled = !loading) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = nativePhrase("返回版本列表", "Back to version list"),
                                tint = HlovetUi.muted,
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = if (selectedVersion == null) 10.dp else 0.dp)) {
                        Text(nativePhrase("版本历史", "Version history"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            if (selectedVersion == null) {
                                nativePhrase("自动保存、手动保存和发布版本", "Autosaved, manually saved, and published versions")
                            } else {
                                nativePhrase(
                                    "版本 ${selectedVersion.summary.versionNumber} · ${versionSourceLabel(selectedVersion.summary.source)}",
                                    "Version ${selectedVersion.summary.versionNumber} · ${versionSourceLabel(selectedVersion.summary.source)}",
                                )
                            },
                            color = HlovetUi.muted,
                            fontSize = 11.sp,
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = !loading) {
                        Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭版本历史", "Close version history"), tint = HlovetUi.muted)
                    }
                }
                if (selectedVersion == null) {
                    if (loading && versions.isEmpty()) {
                        WriteStateCard(nativePhrase("正在读取版本历史…", "Loading version history..."), null)
                    } else if (versions.isEmpty()) {
                        WriteStateCard(
                            nativePhrase("暂时没有历史版本", "No version history yet"),
                            nativePhrase("内容发生变化并保存后，这里会保留版本记录。", "Versions appear here after changed content is saved."),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(versions, key = { it.id }) { version ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            nativePhrase("版本 ${version.versionNumber}", "Version ${version.versionNumber}"),
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            "${versionSourceLabel(version.source)} · ${version.createdAt}\n${version.changedFields.joinToString(nativePhrase("、", ", ")) { versionFieldLabel(it) }.ifBlank { nativePhrase("内容未变化", "No content changes") }}",
                                            color = HlovetUi.muted,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingContent = { Icon(Icons.Filled.History, contentDescription = null, tint = HlovetUi.accent) },
                                    trailingContent = {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("查看", "View"), tint = HlovetUi.muted)
                                    },
                                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.fillMaxWidth().clickable { onSelect(version) },
                                )
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                selectedVersion.title.ifBlank { nativePhrase("未命名文章", "Untitled article") },
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = onRestore, enabled = !loading) {
                                Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(nativePhrase("恢复", "Restore"))
                            }
                        }
                        NativePreviewWebView(
                            title = selectedVersion.title,
                            titleColor = selectedVersion.titleColor,
                            summary = selectedVersion.articleSummary,
                            content = selectedVersion.content,
                            contentFormat = selectedVersion.contentFormat,
                            mediaSession = mediaSession,
                            mediaAttachments = mediaAttachments,
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 渲染 title/titleColor/summary/content 的只读预览并同步账号外观；contentFormat 控制转换，媒体凭据只用于确认的附件。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NativePreviewWebView(
    title: String,
    titleColor: String = "",
    summary: String,
    content: String,
    contentFormat: String,
    mediaSession: NativeSession? = null,
    mediaAttachments: List<NativeArticleAttachment> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // 正文转换沿用阅读规则，账号和附件采用实时值而不注入 HTML 凭据。
    val latestMediaSession = rememberUpdatedState(mediaSession)
    val latestMediaAttachments = rememberUpdatedState(mediaAttachments)
    val appearance = nativeArticleWebAppearance()
    val latestAppearance = rememberUpdatedState(appearance)
    val html = remember(title, titleColor, summary, content, contentFormat) {
        buildPreviewHtml(title, titleColor, summary, content, contentFormat, appearance)
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            nativeCreateWebView(context).apply {
                tag = NativePreviewViewState()
                // Keep preview rendering consistent with article reading on Android 11
                // emulators where translucent hardware WebView composition can crash.
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // 阻断所有默认资源请求，仅由原生客户端加载当前文章已确认媒体。
                settings.blockNetworkLoads = false
                webViewClient = object : NativeEditorAttachmentWebViewClient(context, { latestMediaSession.value }, { latestMediaAttachments.value }) {
                    /** 本地预览就绪后应用最新外观，再安装离线只读代码高亮。 */
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // 页面加载期间也可能切换主题，必须使用实时快照而非初始参数。
                        val preview = view ?: return
                        preview.applyNativeArticleAppearance(latestAppearance.value)
                        (preview.tag as? NativePreviewViewState)?.appearance = latestAppearance.value
                        preview.installNativeReadOnlySyntaxHighlight()
                    }
                }
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            // 只有标题/摘要/正文实际变化才重载；单纯外观重组必须保留预览阅读位置。
            val state = (view.tag as? NativePreviewViewState) ?: NativePreviewViewState().also { view.tag = it }
            if (state.html != html) {
                state.html = html
                state.appearance = null
                view.loadDataWithBaseURL("https://preview.invalid/", html, "text/html", "UTF-8", null)
            } else if (state.appearance != appearance) {
                view.applyNativeArticleAppearance(appearance)
                state.appearance = appearance
            }
        },
    )
}

/** 分开记录预览内容和样式，账号外观变化不能触发 HTML 重载。 */
private data class NativePreviewViewState(var html: String? = null, var appearance: NativeArticleWebAppearance? = null)

/** 桥接本地可视编辑器的编辑和文件操作，限制分块传输的大小与数量。 */
private class NativeArticleEditorBridge(
    private val webView: WebView,
    private val onContentChange: (String) -> Unit,
    private val onResourceRequest: (String) -> Unit,
    private val onAiRequest: (String) -> Unit,
    private val onAttachmentRequest: () -> Unit,
    private val onClipboardFile: (NativeUploadFile, (Boolean) -> Unit) -> Unit,
    private val onClipboardFileRejected: (String) -> Unit,
    private val onLinkRequest: (String) -> Unit,
    private val onFormatRequest: (String, String) -> Unit,
) {
    /** 按单个剪贴板文件收集 Base64 分块，不在缓冲完成前发起上传。 */
    private class ClipboardFileBuffer(
        val name: String,
        val mimeType: String,
        val bytes: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(),
    )

    private val clipboardBuffers = java.util.concurrent.ConcurrentHashMap<String, ClipboardFileBuffer>()

    private fun rejectClipboardFile(id: String, message: String) {
        clipboardBuffers.clear()
        webView.post {
            onClipboardFileRejected(message)
            webView.evaluateJavascript(
                "window.completeNativeClipboardFile(${JSONObject.quote(id)}, false);",
                null,
            )
        }
    }

    @JavascriptInterface
    fun onContentChanged(html: String) {
        webView.post {
            val sanitized = sanitizeNativeArticleHtml(html)
            (webView.tag as? NativeRichEditorViewState)?.content = sanitized
            onContentChange(sanitized)
        }
    }

    @JavascriptInterface
    fun onResourceRequested(selectedHtml: String) {
        webView.post { onResourceRequest(selectedHtml) }
    }

    @JavascriptInterface
    fun onAiRequested(selectedText: String) {
        webView.post { onAiRequest(selectedText) }
    }

    @JavascriptInterface
    fun onAttachmentRequested() {
        webView.post { onAttachmentRequest() }
    }

    @JavascriptInterface
    fun onLinkRequested(currentHref: String) {
        webView.post { onLinkRequest(currentHref.trim()) }
    }

    @JavascriptInterface
    fun normalizeAutoLinkCandidate(value: String): String =
        normalizeNativeAutoLinkCandidate(value).orEmpty()

    @JavascriptInterface
    fun onMarkdownPasteRequested(markdown: String) {
        val rendered = nativeVisualEditorMarkdownPaste(markdown.take(50_000)) ?: return
        webView.post {
            webView.evaluateJavascript("window.insertEditorHtml(${JSONObject.quote(rendered)});", null)
        }
    }

    @JavascriptInterface
    fun onHtmlPasteRequested(html: String, plainText: String) {
        val rendered = nativeVisualEditorHtmlPaste(html, plainText) ?: return
        webView.post {
            webView.evaluateJavascript("window.insertEditorHtml(${JSONObject.quote(rendered)});", null)
        }
    }

    @JavascriptInterface
    fun onClipboardFileStart(id: String, name: String, mimeType: String, declaredSize: String): Boolean {
        val size = declaredSize.toLongOrNull() ?: 0L
        if (id.isBlank() || size < 0L || clipboardBuffers.size >= nativeClipboardFileMaxCount || size > nativeClipboardFileMaxBytes) {
            rejectClipboardFile(id, nativePhrase("剪贴板文件过大或数量过多。", "Clipboard files are too large or too numerous."))
            return false
        }
        clipboardBuffers[id] = ClipboardFileBuffer(
            name = name.trim().take(200).ifBlank { nativePhrase("剪贴板附件", "Clipboard attachment") },
            mimeType = mimeType.trim().take(100).ifBlank { "application/octet-stream" },
        )
        return true
    }

    @JavascriptInterface
    fun onClipboardFileChunk(id: String, encodedChunk: String, last: Boolean) {
        val buffer = clipboardBuffers[id] ?: return
        try {
            if (encodedChunk.length > nativeClipboardFileChunkChars) {
                clipboardBuffers.remove(id)
                rejectClipboardFile(id, nativePhrase("剪贴板文件分块超出限制。", "A clipboard file chunk exceeds the allowed size."))
                return
            }
            val decoded = Base64.decode(encodedChunk, Base64.DEFAULT)
            if (buffer.bytes.size().toLong() + decoded.size > nativeClipboardFileMaxBytes) {
                clipboardBuffers.remove(id)
                rejectClipboardFile(id, nativePhrase("剪贴板文件超过 10 MiB 限制。", "Clipboard files are limited to 10 MiB."))
                return
            }
            buffer.bytes.write(decoded)
            if (last) {
                clipboardBuffers.remove(id)
                val file = NativeUploadFile(buffer.name, buffer.mimeType, buffer.bytes.toByteArray())
                if (file.bytes.isEmpty()) {
                    rejectClipboardFile(id, nativePhrase("无法读取剪贴板文件。", "The clipboard file could not be read."))
                } else {
                    webView.post {
                        onClipboardFile(file) { success ->
                            webView.post {
                                webView.evaluateJavascript(
                                    "window.completeNativeClipboardFile(${JSONObject.quote(id)}, $success);",
                                    null,
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            clipboardBuffers.remove(id)
            rejectClipboardFile(id, nativePhrase("无法读取剪贴板文件。", "The clipboard file could not be read."))
        }
    }

    @JavascriptInterface
    fun onClipboardFileFailed(id: String) {
        clipboardBuffers.remove(id)
        rejectClipboardFile(id, nativePhrase("无法读取剪贴板文件。", "The clipboard file could not be read."))
    }

    /** 接收 count 和 totalBytes 对应的整批超限事件，明确提示且不静默截断文件列表。 */
    @JavascriptInterface
    fun onClipboardSelectionRejected(count: Int, totalBytes: String) {
        // 仅提供固定业务错误文案，网页不能借此注入任意提示。
        val message = if (count > nativeClipboardFileMaxCount) {
            nativePhrase("每次最多选择 9 个附件。", "You can select up to 9 attachments at a time.")
        } else if ((totalBytes.toLongOrNull() ?: 0L) > NATIVE_ARTICLE_ATTACHMENT_BATCH_BYTES) {
            nativeArticleAttachmentBatchError()
        } else return
        webView.post { onClipboardFileRejected(message) }
    }

    @JavascriptInterface
    fun onFormatRequested(language: String, source: String) {
        webView.post { onFormatRequest(language, source) }
    }
}

internal data class NativeRichEditorInsertRequest(val nonce: Int, val html: String)

/** 分别记录编辑正文、插入请求和已应用外观，主题更新不替换正文或重复插入内容。 */
private data class NativeRichEditorViewState(
    var content: String,
    var lastInsertNonce: Int = -1,
    var appearance: NativeArticleWebAppearance? = null,
)

private suspend fun readNativeEditorContent(webView: WebView): String =
    suspendCancellableCoroutine { continuation ->
        webView.post {
            if (continuation.isActive) {
                webView.evaluateJavascript("window.readEditorContent ? window.readEditorContent() : null;") { raw ->
                    val value = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                    if (!continuation.isActive) return@evaluateJavascript
                    if (value == null) {
                        continuation.resumeWith(
                            Result.failure(
                                IllegalStateException(
                                    nativePhrase("编辑器尚未准备好，请稍后重试。", "The editor is not ready. Please try again."),
                                ),
                            ),
                        )
                    } else {
                        continuation.resumeWith(Result.success(sanitizeNativeArticleHtml(value)))
                    }
                }
            }
        }
    }

/** 通过本地页面编辑 content 并实时同步账号外观；回调处理正文/工具操作，媒体和系统文件始终由受控原生流程消费。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NativeRichEditorWebView(
    content: String,
    onContentChange: (String) -> Unit,
    onResourceRequest: (String) -> Unit,
    onAiRequest: ((String) -> Unit)? = null,
    onAttachmentRequest: (() -> Unit)? = null,
    onClipboardFile: (NativeUploadFile, (Boolean) -> Unit) -> Unit = { _, complete -> complete(false) },
    onClipboardFileRejected: (String) -> Unit = {},
    onSystemFiles: ((List<Uri>, DragAndDropPermissions?) -> Unit)? = null,
    editorSnapshot: NativeEditorSnapshot? = null,
    visualInsertRequest: NativeRichEditorInsertRequest?,
    onVisualInsertHandled: () -> Unit,
    mediaSession: NativeSession? = null,
    mediaAttachments: List<NativeArticleAttachment> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // 回调和附件身份采用实时值，重新组合不重建编辑页或覆盖用户选区。
    val scope = rememberCoroutineScope()
    val latestOnContentChange = rememberUpdatedState(onContentChange)
    val latestOnResourceRequest = rememberUpdatedState(onResourceRequest)
    val latestOnAiRequest = rememberUpdatedState(onAiRequest)
    val latestOnAttachmentRequest = rememberUpdatedState(onAttachmentRequest)
    val latestOnClipboardFile = rememberUpdatedState(onClipboardFile)
    val latestOnClipboardFileRejected = rememberUpdatedState(onClipboardFileRejected)
    val latestOnSystemFiles = rememberUpdatedState(onSystemFiles)
    val latestOnVisualInsertHandled = rememberUpdatedState(onVisualInsertHandled)
    val latestMediaSession = rememberUpdatedState(mediaSession)
    val latestMediaAttachments = rememberUpdatedState(mediaAttachments)
    val appearance = nativeArticleWebAppearance()
    val latestAppearance = rememberUpdatedState(appearance)
    var editorWebView by remember { mutableStateOf<WebView?>(null) }
    var linkDialogVisible by remember { mutableStateOf(false) }
    var linkValue by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    val initialContent = remember { sanitizeNativeArticleHtml(content) }
    val editorLocale = NativeI18n.locale
    val editorPage = remember(initialContent, editorLocale, onAiRequest != null, onAttachmentRequest != null) {
        buildNativeRichEditorHtml(
            initialContent,
            enableAttachments = onAttachmentRequest != null,
            enableAiAssistant = onAiRequest != null,
            appearance = appearance,
        )
    }
    val context = LocalContext.current
    val dropTarget = remember(context) {
        object : DragAndDropTarget {
            /** 消费 event 的真实系统内容 URI，在授予临时权限后交由上传流程读取。 */
            override fun onDrop(event: DragAndDropEvent): Boolean {
                // Compose 持有顶层拖放分发，不能仅监听其 AndroidView 内部的 WebView。
                val consume = latestOnSystemFiles.value ?: return false
                val androidEvent = event.toAndroidDragEvent()
                val uris = nativeEditorContentUris(androidEvent.clipData)
                if (uris.isEmpty()) return false
                val permissions = context.findActivity()?.requestDragAndDropPermissions(androidEvent)
                try {
                    consume(uris, permissions)
                } catch (error: Exception) {
                    // 同步交付失败时也释放框架授权；异步消费由接收方 finally 释放。
                    permissions?.release()
                    throw error
                }
                return true
            }
        }
    }
    Box(
        modifier = modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                // 标准文本文件也可能带 text/*；仅订阅拖动，真正落下时仍必须含 content URI。
                val description = event.toAndroidDragEvent().clipDescription
                latestOnSystemFiles.value != null && description != null && (
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) || description.hasMimeType("image/*") ||
                        description.hasMimeType("audio/*") || description.hasMimeType("video/*") || description.hasMimeType("application/*") ||
                        description.hasMimeType("text/*"))
            },
            target = dropTarget,
        ),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                nativeCreateWebView(context) { editorContext ->
                    NativeEditorSystemWebView(editorContext,
                        onSystemFiles = { uris ->
                            val consume = latestOnSystemFiles.value
                            if (consume == null) false else { consume(uris, null); true }
                        },
                        onRejected = { latestOnClipboardFileRejected.value(it) },
                    )
                }.apply {
                    editorWebView = this
                    tag = NativeRichEditorViewState(initialContent)
                    // 编辑器同样叠加在玻璃背景上，与文章正文共用软件合成保护，避免透明区域显示异常。
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    // 默认网络路径被客户端全部拦截，只有已确认的文章媒体由原生有界加载。
                    settings.blockNetworkLoads = false
                    webViewClient = object : NativeEditorAttachmentWebViewClient(context, { latestMediaSession.value }, { latestMediaAttachments.value }) {
                        /** 本地编辑页就绪后应用最新外观并安装代码高亮，不重载正文或加载外部脚本。 */
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // 初始加载期间账号配置也可能变化，不能把最初的主题留在页面里。
                            val editor = view ?: return
                            editor.applyNativeArticleAppearance(latestAppearance.value)
                            (editor.tag as? NativeRichEditorViewState)?.appearance = latestAppearance.value
                            editor.installNativeEditorSyntaxHighlight()
                        }
                    }
                    addJavascriptInterface(
                        NativeArticleEditorBridge(
                            webView = this,
                            onContentChange = { html -> latestOnContentChange.value(html) },
                            onResourceRequest = { selectedHtml -> latestOnResourceRequest.value(selectedHtml) },
                            onAiRequest = { selectedText -> latestOnAiRequest.value?.invoke(selectedText) },
                            onAttachmentRequest = { latestOnAttachmentRequest.value?.invoke() },
                            onClipboardFile = { file, complete -> latestOnClipboardFile.value(file, complete) },
                            onClipboardFileRejected = { message -> latestOnClipboardFileRejected.value(message) },
                            onLinkRequest = { currentHref ->
                                linkValue = currentHref
                                linkError = null
                                linkDialogVisible = true
                            },
                            onFormatRequest = { language, source ->
                                scope.launch {
                                    val result = runCatching { NativeCodeFormatter.format(context, source, language) }
                                        .getOrDefault(NativeCodeFormatResult.Failure.Unavailable)
                                    val (formatted, success, reason) = when (result) {
                                        is NativeCodeFormatResult.Formatted -> Triple(result.code, true, "")
                                        NativeCodeFormatResult.Failure.UnsupportedLanguage -> Triple("", false, "unsupported_language")
                                        NativeCodeFormatResult.Failure.InvalidSyntax -> Triple("", false, "invalid_syntax")
                                        NativeCodeFormatResult.Failure.TooLarge -> Triple("", false, "too_large")
                                        NativeCodeFormatResult.Failure.Unavailable -> Triple("", false, "unavailable")
                                    }
                                    editorWebView?.post {
                                        editorWebView?.evaluateJavascript(
                                            "window.applyFormattedCode(${JSONObject.quote(formatted)}, $success, ${JSONObject.quote(reason)});",
                                            null,
                                        )
                                    }
                                }
                            },
                        ),
                        "NativeEditor",
                    )
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    loadDataWithBaseURL("https://editor.invalid/", editorPage, "text/html", "UTF-8", null)
                }
            },
            update = { view ->
                val state = (view.tag as? NativeRichEditorViewState)
                    ?: NativeRichEditorViewState(content).also { view.tag = it }
                // 主题不参与页面键，也不经正文替换接口，避免清空选区和撤销分支。
                if (state.appearance != appearance) {
                    view.applyNativeArticleAppearance(appearance)
                    state.appearance = appearance
                }
                if (state.content != content) {
                    val sanitized = sanitizeNativeArticleHtml(content)
                    state.content = sanitized
                    view.evaluateJavascript(
                        "window.setEditorContent(${JSONObject.quote(sanitized)});",
                        null,
                    )
                }
                val insertRequest = visualInsertRequest
                if (insertRequest != null && state.lastInsertNonce != insertRequest.nonce) {
                    state.lastInsertNonce = insertRequest.nonce
                    view.evaluateJavascript(
                        "window.insertEditorHtml(${JSONObject.quote(insertRequest.html)});",
                    ) { latestOnVisualInsertHandled.value() }
                }
            },
        )
    }
    DisposableEffect(editorWebView, editorSnapshot) {
        val currentView = editorWebView
        if (currentView != null) editorSnapshot?.attach(currentView) { readNativeEditorContent(currentView) }
        onDispose {
            if (currentView != null) editorSnapshot?.detach(currentView)
        }
    }
    if (linkDialogVisible) {
        HlovetAlertDialog(
            onDismissRequest = { linkDialogVisible = false; linkError = null },
            icon = { Icon(Icons.Filled.Link, contentDescription = null) },
            title = { Text(nativePhrase("插入链接", "Insert link")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = linkValue,
                        onValueChange = { linkValue = it.take(2_000); linkError = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(nativePhrase("链接地址", "Link URL"), fontSize = 11.sp) },
                        placeholder = { Text("https://example.com", fontSize = 10.sp) },
                        isError = linkError != null,
                        supportingText = linkError?.let { message -> { Text(message, fontSize = 10.sp) } },
                        colors = HlovetUi.fieldColors(),
                    )
                    Text(
                        nativePhrase(
                            "未选择文字时，会使用链接地址作为显示文字。",
                            "When no text is selected, the URL is used as the link text.",
                        ),
                        color = HlovetUi.muted,
                        fontSize = 10.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = normalizeNativeEditorLink(linkValue)
                    if (normalized == null) {
                        linkError = nativePhrase(
                            "请输入 HTTP(S)、邮箱或站内链接。",
                            "Enter an HTTP(S), email, or internal link.",
                        )
                    } else {
                        editorWebView?.evaluateJavascript(
                            "window.applyEditorLink(${JSONObject.quote(normalized)});",
                            null,
                        )
                        linkDialogVisible = false
                        linkError = null
                    }
                }) { Text(nativePhrase("插入", "Insert"), color = HlovetUi.accent) }
            },
            dismissButton = {
                TextButton(onClick = { linkDialogVisible = false; linkError = null }) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }
}

/** 根据 content 和 appearance 生成本地编辑页；两个开关控制附件/AI 入口，外观不写入正文。 */
internal fun buildNativeRichEditorHtml(
    content: String,
    enableAttachments: Boolean = true,
    enableAiAssistant: Boolean = true,
    appearance: NativeArticleWebAppearance = nativeArticleWebAppearance(),
): String {
    // 正文先净化，工具栏文案随当前语言生成，附件传输始终受原生桥接限制。
    val safeContent = sanitizeNativeArticleHtml(content)
    val languageOptions = nativeCodeLanguageOptions().joinToString("") { option ->
        "<option value=\"${htmlEscape(option.value)}\">${htmlEscape(option.label)}</option>"
    }
    val editorPlaceholder = nativePhrase("开始输入正文…", "Start writing...")
    val resourceLabel = nativePhrase("积分资源", "Point resource")
    val bodyToolbarLabel = nativePhrase("正文格式工具", "Body formatting tools")
    val undoLabel = nativePhrase("撤销", "Undo")
    val redoLabel = nativePhrase("重做", "Redo")
    val bodyLevelLabel = nativePhrase("正文层级", "Body level")
    val bodyLabel = nativePhrase("正文", "Body")
    val headingOneLabel = nativePhrase("标题1", "Heading 1")
    val headingTwoLabel = nativePhrase("标题2", "Heading 2")
    val headingThreeLabel = nativePhrase("标题3", "Heading 3")
    val fontSizeLabel = nativePhrase("字号", "Font size")
    val boldLabel = nativePhrase("粗体", "Bold")
    val italicLabel = nativePhrase("斜体", "Italic")
    val underlineLabel = nativePhrase("下划线", "Underline")
    val strikeLabel = nativePhrase("删除线", "Strikethrough")
    val bulletListLabel = nativePhrase("无序列表", "Bulleted list")
    val numberedListLabel = nativePhrase("有序列表", "Numbered list")
    val listButtonLabel = nativePhrase("列表", "List")
    val taskListLabel = nativePhrase("任务列表", "Task list")
    val taskButtonLabel = nativePhrase("任务", "Task")
    val quoteLabel = nativePhrase("引用", "Quote")
    val codeLanguageLabel = nativePhrase("代码语言", "Code language")
    val codeBlockLabel = nativePhrase("代码块", "Code block")
    val insertCodeLabel = nativePhrase("插入代码块", "Insert code block")
    val codeButtonLabel = nativePhrase("代码", "Code")
    val formatCodeLabel = nativePhrase("格式化当前代码块", "Format current code block")
    val horizontalRuleLabel = nativePhrase("分割线", "Horizontal rule")
    val linkLabel = nativePhrase("插入链接", "Insert link")
    val linkButtonLabel = nativePhrase("链接", "Link")
    val unlinkLabel = nativePhrase("取消链接", "Remove link")
    val clearFormattingLabel = nativePhrase("清除格式", "Clear formatting")
    val insertResourceLabel = nativePhrase("插入积分资源", "Insert point resource")
    val removeResourceLabel = nativePhrase("移除积分资源", "Remove point resource")
    val removeResourceButtonLabel = nativePhrase("移除资源", "Remove resource")
    val attachmentLabel = nativePhrase("上传图片或文件", "Upload image or file")
    val attachmentButtonLabel = nativePhrase("附件", "Attachment")
    val aiAssistantLabel = nativePhrase("AI 写作助手", "AI writing assistant")
    val codePlaceholder = nativePhrase("代码", "Code")
    val formatSuccess = nativePhrase("已格式化", "Formatted")
    val formatFailure = nativePhrase("无法格式化", "Cannot format")
    val formatUnsupported = nativePhrase("当前语言暂不支持格式化", "This language is not supported")
    val formatInvalid = nativePhrase("代码语法无法解析", "The code could not be parsed")
    val formatTooLarge = nativePhrase("代码块超过 200 KB", "Code block exceeds 200 KB")
    val formatUnavailable = nativePhrase("格式化引擎暂时不可用", "Formatting engine unavailable")
    val supportedCodeLanguages = nativeCodeLanguageOptions().joinToString(",") { it.value }
    val attachmentToolbarButton = if (enableAttachments) {
        """<button type="button" id="upload-attachment" title="$attachmentLabel">$attachmentButtonLabel</button>"""
    } else {
        ""
    }
    val aiToolbarButton = if (enableAiAssistant) {
        """<button type="button" id="open-ai-assistant" title="$aiAssistantLabel">AI</button>"""
    } else {
        ""
    }
    val clipboardFileChunkBytes = nativeClipboardFileChunkChars / 4 * 3
    val clipboardFileMaxBytes = nativeClipboardFileMaxBytes
    // 编辑器样式、输入事件和桥接脚本作为同一离线页面交付给 WebView。
    return """
        <!doctype html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        ${appearance.stylesheet()}
        * { box-sizing: border-box; }
        html, body { margin: 0; min-height: 100%; background: transparent; color: var(--article-foreground); font: 14px/1.65 sans-serif; }
        /* 模糊由外层原生玻璃宿主完成，软件 WebView 内不重复建立 backdrop 合成层。 */
        .toolbar { position: sticky; top: 0; z-index: 2; display: flex; flex-wrap: wrap; align-items: center; gap: 4px; padding: 8px; background: var(--article-overlay); border: 1px solid var(--article-outline); border-radius: 10px 10px 0 0; }
        .toolbar button, .toolbar select { min-height: 32px; border: 0; border-radius: 7px; color: var(--article-foreground); background: var(--article-glass); padding: 0 9px; font: 13px sans-serif; }
        .toolbar option { color: var(--article-foreground); background: var(--article-surface); }
        .toolbar select { max-width: 118px; }
        .toolbar button:active { background: var(--article-accent-soft); }
        .toolbar button[aria-pressed=true] { color: var(--article-accent); background: var(--article-accent-soft); }
        #editor { min-height: 300px; padding: 12px; border: 1px solid var(--article-outline); border-top: 0; border-radius: 0 0 10px 10px; outline: none; overflow-wrap: anywhere; word-break: break-word; background-color: var(--article-glass); background-image: linear-gradient(var(--article-tint), var(--article-tint)); caret-color: var(--article-accent); }
        #editor:empty:before { content: '$editorPlaceholder'; color: var(--article-muted); }
        #editor a { color: var(--article-accent); }
        #editor hr { border: 0; border-top: 1px solid var(--article-outline); }
        /* 不依赖旧 WebView 不支持的 accent-color，任务框与阅读正文共用 12px 的主题颜色。 */
        #editor input[type=checkbox] { -webkit-appearance: none; appearance: none; border: 1px solid var(--article-outline); border-radius: 3px; background: var(--article-glass); }
        #editor input[type=checkbox]:checked { border-color: var(--article-accent); background: var(--article-accent); box-shadow: inset 0 0 0 3px var(--article-surface); }
        #editor h1, #editor h2, #editor h3 { line-height: 1.35; }
        #editor blockquote { margin: 1em 0; padding: 2px 12px; border-left: 3px solid var(--article-accent); background: var(--article-accent-soft); }
        #editor resource-block { display: block; margin: 1em 0; padding: 10px 12px; border: 1px solid var(--article-outline); border-radius: 8px; background: var(--article-glass); }
        #editor resource-block:before { content: '$resourceLabel'; display: block; margin-bottom: 5px; color: var(--article-accent); font-size: 12px; font-weight: 600; }
        #editor pre { margin: 1em 0; padding: 0; overflow: hidden; white-space: pre; border-radius: 8px; background: #17212b; color: #e6edf3; }
        .native-code-card { margin: 1em 0; overflow: hidden; border: 1px solid rgba(139, 167, 179, .36); border-radius: 8px; background: #17212b; }
        .native-code-card > pre { margin: 0; border: 0; border-radius: 0; }
        .native-code-card-header { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 8px; padding: 5px 7px 5px 10px; border-bottom: 1px solid rgba(139, 167, 179, .28); background: rgba(44, 61, 73, .92); color: #d6e3e8; font: 12px/1.2 sans-serif; }
        .native-code-card-title, .native-code-card-controls { display: inline-flex; min-width: 0; align-items: center; gap: 6px; }
        .native-code-card-title { flex: 1 1 auto; color: #e6edf3; white-space: nowrap; }
        .native-code-card-language { width: 106px; min-width: 0; height: 24px; padding: 2px 5px; border: 1px solid rgba(174, 205, 216, .42); border-radius: 5px; color: #e6edf3; background: rgba(30, 45, 55, .92); font: 11px/1.1 sans-serif; }
        .native-code-card-language option { color: var(--article-foreground); background: var(--article-surface); }
        .native-code-card-format { display: inline-grid; width: 24px; height: 24px; flex: 0 0 24px; place-items: center; border: 0; border-radius: 4px; color: #83d7e8; background: transparent; font: 15px/1 sans-serif; }
        .native-code-card-format:active, .native-code-card-format:focus-visible { background: rgba(131, 215, 232, .2); outline: none; }
        .native-code-card-status { color: #a8bac2; font-size: 10px; white-space: nowrap; }
        .native-code-card-status.success { color: #83d7a5; }
        .native-code-card-status.error { color: #ff9a91; }
        .native-code-card > pre > code { display: block; min-height: 76px; padding: 12px; overflow-x: auto; }
        #editor code { font: .9em monospace; }
        $nativeSyntaxHighlightCss
        #editor img, #editor video { max-width: 100%; height: auto; }
        #editor table { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; }
        #editor th, #editor td { border: 1px solid var(--article-outline); padding: 5px 7px; }
        #editor ul[data-type=taskList] { list-style: none; margin: .24em 0; padding-left: 0; }
        #editor li[data-type=taskItem] { display: grid; grid-template-columns: 12px minmax(0, 1fr); column-gap: 7px; align-items: start; min-width: 0; margin: .16em 0; list-style: none; }
        #editor li[data-type=taskItem] > label { display: flex; width: 12px; height: 1.65em; align-items: center; }
        #editor li[data-type=taskItem] > label > input[type=checkbox] { width: 12px; height: 12px; margin: 0; }
        #editor li[data-type=taskItem] > label > span { display: none; }
        #editor li[data-type=taskItem] > div { grid-column: 2; min-width: 0; }
        #editor li[data-type=taskItem] > div > p { margin: 0; }
        </style></head><body>
        <div class="toolbar" aria-label="$bodyToolbarLabel">
          <button type="button" data-command="undo" aria-pressed="false" title="$undoLabel">↶</button>
          <button type="button" data-command="redo" aria-pressed="false" title="$redoLabel">↷</button>
          <select id="block-type" aria-label="$bodyLevelLabel">
            <option value="p">$bodyLabel</option><option value="h1">$headingOneLabel</option><option value="h2">$headingTwoLabel</option><option value="h3">$headingThreeLabel</option>
          </select>
          <select id="font-size" aria-label="$fontSizeLabel">
            <option value="12px">12</option><option value="14px" selected>14</option><option value="16px">16</option><option value="18px">18</option><option value="20px">20</option>
          </select>
          <button type="button" data-command="bold" aria-pressed="false" title="$boldLabel"><b>B</b></button>
          <button type="button" data-command="italic" aria-pressed="false" title="$italicLabel"><i>I</i></button>
          <button type="button" data-command="underline" aria-pressed="false" title="$underlineLabel"><u>U</u></button>
          <button type="button" data-command="strikeThrough" aria-pressed="false" title="$strikeLabel"><s>S</s></button>
          <button type="button" data-command="formatBlock" data-value="h1" aria-pressed="false">H1</button>
          <button type="button" data-command="formatBlock" data-value="h2" aria-pressed="false">H2</button>
          <button type="button" data-command="formatBlock" data-value="h3" aria-pressed="false">H3</button>
          <button type="button" data-command="insertUnorderedList" aria-pressed="false" title="$bulletListLabel">• $listButtonLabel</button>
          <button type="button" data-command="insertOrderedList" aria-pressed="false" title="$numberedListLabel">1. $listButtonLabel</button>
          <button type="button" id="insert-task-list" title="$taskListLabel">☑ $taskButtonLabel</button>
          <button type="button" data-command="formatBlock" data-value="blockquote" aria-pressed="false" title="$quoteLabel">$quoteLabel</button>
          <button type="button" id="insert-code" title="$insertCodeLabel">$codeButtonLabel</button>
          <button type="button" data-command="insertHorizontalRule" title="$horizontalRuleLabel">$horizontalRuleLabel</button>
          <button type="button" data-command="createLink" title="$linkLabel">$linkButtonLabel</button>
          <button type="button" data-command="unlink" title="$unlinkLabel">$unlinkLabel</button>
          <button type="button" data-command="removeFormat" aria-pressed="false" title="$clearFormattingLabel">$clearFormattingLabel</button>
          <button type="button" id="insert-resource" title="$insertResourceLabel">$resourceLabel</button>
          <button type="button" id="remove-resource" title="$removeResourceLabel">$removeResourceButtonLabel</button>
          $attachmentToolbarButton
          $aiToolbarButton
        </div>
        <div id="editor" contenteditable="true" spellcheck="true">$safeContent</div>
        <script>
        $nativeArticleAppearanceBridgeScript
        (function() {
          var editor = document.getElementById('editor');
          var timer = 0;
          var savedRange = null;
          var linkTarget = null;
          var requestedFontSize = '14px';
          // 可视编辑统一维护有界历史，避免原生命令与直接 DOM 修改各自撤销。
          var editorHistory = [];
          var historyIndex = -1;
          var historyBytes = 0;
          var historyDepth = 0;
          var historyRestoring = false;
          var historyComposing = false;
          var historyDirty = false;
          var historyInputGroup = '';
          var historyInputTime = 0;
          // 恢复已保存任务的可编辑边界和复选框状态，不把 label 当作正文。
          function prepareTaskItems() {
            // data-checked 是保存后的状态来源，重新打开时同步到真实 checkbox 属性。
            editor.querySelectorAll('li[data-type="taskItem"]').forEach(function(item) {
              var label = item.querySelector(':scope > label');
              if (label) label.setAttribute('contenteditable', 'false');
              var checkbox = label && label.querySelector('input[type="checkbox"]');
              if (checkbox) checkbox.checked = item.getAttribute('data-checked') === 'true';
            });
          }
          prepareTaskItems();
          // 同步记录本次正文变更，原生桥接仍合并短时间内的回报；kind 仅用于连续输入分组。
          function report(kind, recordHistory) {
            // 历史不能跟随桥接延迟，否则连续点击工具会覆盖彼此的撤销点；纯通知路径不重新克隆正文。
            if (recordHistory !== false) {
              historyDirty = true;
              recordEditorHistory(kind || 'action');
            }
            window.clearTimeout(timer);
            timer = window.setTimeout(function() {
               var html = serializedEditorHtml();
              if (window.NativeEditor) window.NativeEditor.onContentChanged(html);
            }, 120);
          }
          function focusEditor() { if (savedRange) restoreSelection(); else editor.focus(); }
          function captureSelection() {
            var selection = window.getSelection();
            if (!selection || !selection.rangeCount) return null;
            var range = selection.getRangeAt(0);
            if (!editor.contains(range.commonAncestorContainer)) return null;
            savedRange = range.cloneRange();
            return range;
          }
          function selectedHtml() {
            var range = captureSelection();
            if (!range || range.collapsed) return '';
            var wrapper = document.createElement('div');
            wrapper.appendChild(range.cloneContents());
            if (window.HlovetSyntax) window.HlovetSyntax.stripEditorArtifacts(wrapper);
            return wrapper.innerHTML;
          }
          function selectedText() {
            var range = captureSelection();
            return range && !range.collapsed ? range.toString() : '';
          }
          function autoLinkAtCaret() {
            var selection = window.getSelection();
            if (!selection || !selection.rangeCount || !selection.isCollapsed) return false;
            var node = selection.anchorNode;
            if (!node || node.nodeType !== 3 || !editor.contains(node)) return false;
            var parent = node.parentElement;
            if (!parent || parent.closest('a, code, pre, resource-block')) return false;
            var offset = selection.anchorOffset;
            var before = String(node.nodeValue || '').slice(0, offset);
            var boundary = /\s/.test(before.slice(-1)) ? 1 : 0;
            var source = before.slice(0, before.length - boundary);
            var match = /((?:https?:\/\/|www\.)[^\s<>"']+|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?:[/?#][^\s<>"']*)?)$/i.exec(source);
            if (!match) return false;
            var raw = match[1];
            var label = raw.replace(/[.,!?;:)\]}>'"]+$/, '');
            if (!label) return false;
            var href = window.NativeEditor && typeof window.NativeEditor.normalizeAutoLinkCandidate === 'function'
              ? window.NativeEditor.normalizeAutoLinkCandidate(label)
              : '';
            if (!href) return false;
            var start = offset - boundary - raw.length;
            var suffix = node.splitText(start);
            var trailing = suffix.splitText(label.length);
            var link = document.createElement('a');
            link.setAttribute('href', href);
            suffix.parentNode.insertBefore(link, suffix);
            link.appendChild(suffix);
            var nextRange = document.createRange();
            nextRange.setStart(trailing, Math.min(raw.length - label.length + boundary, trailing.nodeValue.length));
            nextRange.collapse(true);
            selection.removeAllRanges();
            selection.addRange(nextRange);
            savedRange = nextRange.cloneRange();
            return true;
          }
          function restoreSelection() {
            if (!savedRange) return null;
            editor.focus();
            var selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(savedRange);
            return savedRange;
          }
          function selectionElement() {
            var selection = window.getSelection();
            if (!selection || !selection.rangeCount) return null;
            var node = selection.anchorNode;
            return node && (node.nodeType === 1 ? node : node.parentElement);
          }
          function currentBlock() {
            var element = selectionElement();
            var block = element && element.closest('p, h1, h2, h3, h4, h5, h6, blockquote, li');
            return block && editor.contains(block) ? block : null;
          }
          function currentFontSize() {
            var element = selectionElement();
            var sized = element && element.closest('[style*="font-size"]');
            if (sized && editor.contains(sized)) {
              var value = sized.style.fontSize;
              if (value) return value;
            }
            return '14px';
          }
          function updateToolbarState() {
            var block = currentBlock();
            var blockType = document.getElementById('block-type');
            var fontSize = document.getElementById('font-size');
            if (blockType) {
              var name = block ? block.tagName.toLowerCase() : 'p';
              blockType.value = /^h[1-3]$/.test(name) ? name : 'p';
            }
            if (fontSize) {
              var value = currentFontSize();
              fontSize.value = Array.prototype.some.call(fontSize.options, function(option) { return option.value === value; }) ? value : '14px';
            }
            document.querySelectorAll('[data-command]').forEach(function(button) {
              var command = button.getAttribute('data-command');
              var active = false;
              if (command === 'bold') active = document.queryCommandState('bold');
              if (command === 'italic') active = document.queryCommandState('italic');
              if (command === 'underline') active = document.queryCommandState('underline');
              if (command === 'strikeThrough') active = document.queryCommandState('strikeThrough');
              if (command === 'formatBlock') {
                var element = selectionElement();
                var value = button.getAttribute('data-value');
                active = value === 'blockquote' ? !!(element && element.closest('blockquote')) : !!(block && block.tagName.toLowerCase() === value);
              }
              if (command === 'insertUnorderedList' || command === 'insertOrderedList') {
                var list = selectedList();
                active = !!(list && list.getAttribute('data-type') !== 'taskList' && list.tagName.toLowerCase() === (command === 'insertOrderedList' ? 'ol' : 'ul'));
              }
              if (command === 'createLink') {
                var element = selectionElement();
                active = !!(element && element.closest('a[href]'));
              }
              button.setAttribute('aria-pressed', active ? 'true' : 'false');
            });
            var taskButton = document.getElementById('insert-task-list');
            var list = selectedList();
            taskButton.setAttribute('aria-pressed', list && list.getAttribute('data-type') === 'taskList' ? 'true' : 'false');
          }
          function normalizeFontSizeMarkup() {
            var fonts = editor.querySelectorAll('font[size="7"]');
            if (!fonts.length) return;
            // CSS mode emits an unportable named size. Normalize legacy font nodes
            // while preserving text-node selection, including newly typed input.
            var selection = window.getSelection();
            var range = selection && selection.rangeCount ? selection.getRangeAt(0) : null;
            var start = range && range.startContainer;
            var end = range && range.endContainer;
            var startOffset = range && range.startOffset;
            var endOffset = range && range.endOffset;
            fonts.forEach(function(font) {
              var span = document.createElement('span');
              span.style.fontSize = requestedFontSize;
              if (start === font) start = span;
              if (end === font) end = span;
              while (font.firstChild) span.appendChild(font.firstChild);
              font.replaceWith(span);
            });
            if (start && end && editor.contains(start) && editor.contains(end)) {
              var restored = document.createRange();
              restored.setStart(start, startOffset);
              restored.setEnd(end, endOffset);
              selection.removeAllRanges();
              selection.addRange(restored);
              savedRange = restored.cloneRange();
            }
          }
          function applyFontSize(size) {
            if (!/^(12|14|16|18|20)px$/.test(size)) return;
            focusEditor();
            requestedFontSize = size;
            document.execCommand('styleWithCSS', false, false);
            document.execCommand('fontSize', false, '7');
            normalizeFontSizeMarkup();
            captureSelection();
          }
          function clearFormatting() {
            document.execCommand('removeFormat', false, null);
            document.execCommand('formatBlock', false, 'p');
            captureSelection();
          }
          function requestBridge(method, value) {
            if (!window.NativeEditor || typeof window.NativeEditor[method] !== 'function') return;
            window.NativeEditor[method](value === undefined ? '' : value);
          }
           var supportedCodeLanguages = '$supportedCodeLanguages'.split(',');
           function codeLanguageFor(pre) {
             var code = pre && Array.prototype.find.call(pre.children, function(child) {
               return child.tagName && child.tagName.toLowerCase() === 'code';
             });
             var raw = (pre && pre.getAttribute('data-language')) || (code && code.className || '').replace(/^.*\blanguage-/i, '') || 'plaintext';
             raw = String(raw).toLowerCase();
             return supportedCodeLanguages.indexOf(raw) >= 0 ? raw : 'plaintext';
           }
           function unwrapNativeCodeCards(root) {
             if (!root) return;
             Array.prototype.forEach.call(root.querySelectorAll('.native-code-card'), function(card) {
               var pre = Array.prototype.find.call(card.children, function(child) {
                 return child.tagName && child.tagName.toLowerCase() === 'pre';
               });
               if (pre) card.replaceWith(pre); else card.remove();
             });
           }
           // 读取净正文，移除仅用于编辑的卡片、高亮和空 class，不写回实际编辑节点。
           function serializedEditorHtml() {
             // 与历史快照使用同一清理规则，避免异步高亮安装产生虚假的正文变化。
             normalizeFontSizeMarkup();
             var clone = editor.cloneNode(true);
             cleanHistoryClone(clone);
             return clone.innerHTML;
           }
          // 对 clone 去除编辑辅助结构，保留任务、资源及代码的业务属性。
          function cleanHistoryClone(clone) {
            // 高亮层从不参与内容或光标路径，代码原文节点不会被替换。
            unwrapNativeCodeCards(clone);
            if (window.HlovetSyntax) window.HlovetSyntax.stripEditorArtifacts(clone);
            clone.querySelectorAll('[class=""]').forEach(function(node) { node.removeAttribute('class'); });
          }
          // 返回 root 到 node 的子节点索引路径；不属于 root 时返回 null。
          function historyNodePath(root, node) {
            // 使用结构位置而非全文字符偏移，保留相邻任务、空段落和代码内的准确光标。
            var path = [];
            while (node && node !== root) {
              var parent = node.parentNode;
              if (!parent) return null;
              path.unshift(Array.prototype.indexOf.call(parent.childNodes, node));
              node = parent;
            }
            return node === root ? path : null;
          }
          // 按 path 查找 root 内的节点；结构不匹配时返回 null，不跳到其它段落。
          function historyNodeAt(root, path) {
            // 快照路径只在同一份净正文中使用，不根据显示文字猜测身份。
            var node = root;
            for (var index = 0; node && index < path.length; index++) node = node.childNodes[path[index]];
            return node || null;
          }
          // 复制当前净正文和双端选区；无正文选区时沿用 savedRange，没有时返回空书签。
          function captureEditorHistoryState() {
            // 先映射到克隆节点，再删除辅助层；不在用户正在输入的 DOM 中插入标记。
            normalizeFontSizeMarkup();
            var clone = editor.cloneNode(true);
            var selection = window.getSelection();
            var range = selection && selection.rangeCount ? selection.getRangeAt(0) : null;
            if (!range || !editor.contains(range.commonAncestorContainer)) range = savedRange;
            var points = [];
            var backward = !!(range && selection && selection.anchorNode === range.endContainer &&
              selection.anchorOffset === range.endOffset && !range.collapsed);
            if (range && editor.contains(range.commonAncestorContainer)) {
              [[range.startContainer, range.startOffset], [range.endContainer, range.endOffset]].forEach(function(point) {
                var node = historyNodeAt(clone, historyNodePath(editor, point[0]) || []);
                // 元素边界用临时注释定位，辅助兄弟节点移除后仍能计算正确的子节点位置。
                var marker = node.nodeType === 3 ? null : document.createComment('history-caret');
                points.push({ node: node, offset: point[1], marker: marker, next: node.childNodes[point[1]] || null });
              });
              points.forEach(function(point) { if (point.marker) point.node.insertBefore(point.marker, point.next); });
            }
            cleanHistoryClone(clone);
            // 先移除全部临时边界再计算路径，避免首端标记改变末端的结构索引。
            points.forEach(function(point) {
              if (!point.marker || !point.marker.parentNode) return;
              point.node = point.marker.parentNode;
              // nextSibling 也可能是另一端标记，直接计数前方非标记节点。
              point.offset = 0;
              for (var sibling = point.marker.previousSibling; sibling; sibling = sibling.previousSibling) {
                if (sibling !== points[0].marker && sibling !== points[1].marker) point.offset++;
              }
            });
            points.forEach(function(point) { if (point.marker) point.marker.remove(); });
            var bookmark = points.length === 2 ? points.map(function(point) {
              var path = historyNodePath(clone, point.node);
              return path ? { path: path, offset: point.offset } : null;
            }) : null;
            return { html: clone.innerHTML, selection: bookmark && bookmark[0] && bookmark[1] ? bookmark : null, backward: backward };
          }
          // 在修改前更新当前撤销点的选区，移动过光标时终止连续输入分组。
          function prepareEditorHistory(forceStaleCompositionClose) {
            // 组合输入保持一个事务，不能把中间候选字记录成多个撤销点；旧 WebView 的非组合输入可主动收口过期状态。
            if (historyRestoring || historyDepth) return;
            var state = captureEditorHistoryState();
            var current = editorHistory[historyIndex];
            if (!current) return;
            if (current.html !== state.html) {
              // 旧 WebView 可能漏发 compositionend；先把已完成的正文输入提交，再让当前输入单独成步。
              if (forceStaleCompositionClose && historyComposing) {
                historyComposing = false;
                historyInputGroup = '';
                recordEditorHistory('action');
              }
              return;
            }
            // 旧 WebView 可能在第一个非组合 beforeinput 前遗留 compositionstart，此时无需等待选区变化。
            if (forceStaleCompositionClose && historyComposing) {
              historyComposing = false;
              historyInputGroup = '';
            }
            if (JSON.stringify(current.selection) !== JSON.stringify(state.selection)) {
              historyInputGroup = '';
            }
            current.selection = state.selection;
            current.backward = state.backward;
          }
          // 把误插在段落内的 block 提到合法层级，保留两侧行内格式和实际选区。
          function normalizeEditorBlockNesting() {
            // execCommand 与 Range.insertNode 都可能把列表、代码或资源插进 p，HTML 回读会再次拆开。
            var blocks = Array.prototype.filter.call(editor.querySelectorAll('ul, ol, pre, resource-block, blockquote, table, hr, h1, h2, h3, p, .native-code-card'), function(node) {
              return !(node.tagName.toLowerCase() === 'pre' && node.parentElement.classList.contains('native-code-card')) &&
                !!node.parentElement.closest('p, h1, h2, h3, h4, h5, h6');
            });
            if (!blocks.length) return;
            var selection = window.getSelection();
            var range = selection && selection.rangeCount ? selection.getRangeAt(0) : null;
            var boundaries = range && editor.contains(range.commonAncestorContainer) ? [
              { node: range.startContainer, offset: range.startOffset }, { node: range.endContainer, offset: range.endOffset }
            ] : null;
            var backward = boundaries && !range.collapsed && selection.anchorNode === range.endContainer && selection.anchorOffset === range.endOffset;
            if (boundaries) boundaries.forEach(function(point) {
              point.next = point.node.childNodes[point.offset] || null;
              point.previous = point.offset > 0 ? point.node.childNodes[point.offset - 1] : null;
            });
            // 逐层拆分包住块节点的行内元素和段落，不重建整篇 DOM 或丢失代码控件监听。
            blocks.forEach(function(block) {
              var paragraph = block.parentElement.closest('p, h1, h2, h3, h4, h5, h6');
              if (!paragraph || !editor.contains(paragraph)) return;
              var outer = paragraph.parentNode;
              while (block.parentNode !== outer) {
                var parent = block.parentNode;
                var following = parent.cloneNode(false);
                while (block.nextSibling) following.appendChild(block.nextSibling);
                parent.parentNode.insertBefore(block, parent.nextSibling);
                if (following.childNodes.length) block.parentNode.insertBefore(following, block.nextSibling);
                if (!parent.childNodes.length) parent.remove();
              }
            });
            if (boundaries) restoreEditorBoundaryRange(boundaries, backward);
          }
          // 将 kind 对应的实际变更入栈；无变化不新增记录，新的操作移除旧的重做分支。
          function recordEditorHistory(kind) {
            // 只有业务正文参与历史体积；当前正文即使单独超限也保留，旧记录优先淘汰。
            if (historyRestoring || historyDepth || historyComposing || historyIndex < 0) return;
            normalizeEditorBlockNesting();
            var state = captureEditorHistoryState();
            if (editorHistory[historyIndex].html === state.html) {
              historyDirty = false;
              return;
            }
            var now = Date.now();
            var typing = /^(insertText|deleteContentBackward|deleteContentForward)$/.test(kind);
            var merge = typing && historyInputGroup === kind && now - historyInputTime < 800 &&
              historyIndex > 0 && historyIndex === editorHistory.length - 1;
            while (editorHistory.length > historyIndex + 1) historyBytes -= editorHistory.pop().html.length * 2;
            if (merge) historyBytes -= editorHistory.pop().html.length * 2;
            editorHistory.push(state);
            historyBytes += state.html.length * 2;
            historyIndex = editorHistory.length - 1;
            while (editorHistory.length > 1 && (editorHistory.length > 80 || historyBytes > 8 * 1024 * 1024)) {
              historyBytes -= editorHistory.shift().html.length * 2;
              historyIndex--;
            }
            historyInputGroup = typing ? kind : '';
            historyInputTime = now;
            historyDirty = false;
          }
          // 包装 handler 的单次结构化操作，把期间同步触发的原生 input 合并到同一步。
          function editorHistoryAction(handler) {
            // 请求弹窗但未修改正文时不会新增历史；异步回调由各自入口独立提交。
            return function() {
              prepareEditorHistory();
              historyDepth++;
              try { return handler.apply(this, arguments); }
              finally {
                historyDepth--;
                if (!historyDepth) recordEditorHistory('action');
              }
            };
          }
          // 映射 state 的净正文选区，再安装辅助层并按原节点恢复；没有书签时使用文末。
          function restoreHistorySelection(state) {
            // 保存边界相邻的业务节点，避免代码卡片包装和高亮插入把浏览器 Range 移走。
            var points = state.selection;
            var start = points && historyNodeAt(editor, points[0].path);
            var end = points && historyNodeAt(editor, points[1].path);
            var boundaries = start && end ? [start, end].map(function(node, index) {
              var offset = points[index].offset;
              return { node: node, offset: offset, next: node.childNodes[offset] || null,
                previous: offset > 0 ? node.childNodes[offset - 1] : null };
            }) : null;
            // 大正文没有代码块时无需重复扫描语法卡片，旧 WebView 撤销连续操作可保持及时响应。
            if (editor.querySelector('pre, .native-code-card')) {
              ensureNativeCodeCards(editor);
              if (window.HlovetSyntax) window.HlovetSyntax.refreshEditor(editor);
            }
            restoreEditorBoundaryRange(boundaries, state.backward);
          }
          // 根据仍存活的正文节点 boundaries 恢复双端选区，backward 保留反向选择。
          function restoreEditorBoundaryRange(boundaries, backward) {
            // 包装、解包后优先依靠相邻业务节点，不能沿用被 DOM 移动规则重置的旧 Range。
            var range = document.createRange();
            if (boundaries) {
              boundaries.forEach(function(point, index) {
                var method = index === 0 ? 'setStart' : 'setEnd';
                if (point.node.nodeType === 3) { range[method](point.node, Math.min(point.offset, point.node.length)); return; }
                var adjacent = point.next || point.previous;
                if (!editor.contains(point.node)) {
                  if (adjacent && editor.contains(adjacent)) range[index === 0 ? (point.next ? 'setStartBefore' : 'setStartAfter') : (point.next ? 'setEndBefore' : 'setEndAfter')](adjacent);
                  else range[method](editor, editor.childNodes.length);
                  return;
                }
                while (adjacent && adjacent.parentNode !== point.node) adjacent = adjacent.parentNode;
                var offset = adjacent ? Array.prototype.indexOf.call(point.node.childNodes, adjacent) + (point.next ? 0 : 1) : 0;
                range[method](point.node, offset);
              });
            } else { range.selectNodeContents(editor); range.collapse(false); }
            var selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);
            if (backward && selection.setBaseAndExtent) selection.setBaseAndExtent(range.endContainer, range.endOffset, range.startContainer, range.startOffset);
            savedRange = range.cloneRange();
          }
          // 按 direction 撤销或重做一步；越界时不调用浏览器的另一套原生历史。
          function finishEditorCompositionForHistory() {
            // 旧版 Android WebView 可能在硬件文本输入后遗漏 compositionend，工具栏操作仍需先收口正文。
            historyComposing = false;
            historyInputGroup = '';
          }
          function travelEditorHistory(direction) {
            // 未完成的输入先入栈，避免工具栏与快捷键走不同顺序。
            var wasComposing = historyComposing;
            if (wasComposing) finishEditorCompositionForHistory();
            // 已到历史边界且没有待提交输入时直接返回，避免大正文重复克隆造成旧设备超时。
            if (!wasComposing && historyInputGroup === '' && !historyDirty &&
                ((direction < 0 && historyIndex <= 0) || (direction > 0 && historyIndex >= editorHistory.length - 1))) return;
            // 只有组合输入、连续输入或未完成桥接修改才需要重新生成当前历史快照。
            if (historyComposing || historyInputGroup !== '' || historyDirty) recordEditorHistory('action');
            var next = historyIndex + direction;
            if (next < 0 || next >= editorHistory.length) return;
            historyRestoring = true;
            try {
              historyIndex = next;
              var state = editorHistory[historyIndex];
              linkTarget = null;
              formatTarget = null;
              editor.innerHTML = state.html;
              prepareTaskItems();
              editor.focus();
              restoreHistorySelection(state);
              updateToolbarState();
            } finally { historyRestoring = false; historyInputGroup = ''; }
            report(null, false);
          }
          // 外部正文替换建立新基线；来源切换或版本恢复后不能撤销回另一篇正文。
          function resetEditorHistory() {
            // 基线没有旧选区，首次输入前由 prepareEditorHistory 捕获真实位置。
            editorHistory = [captureEditorHistoryState()];
            historyIndex = 0;
            historyBytes = editorHistory[0].html.length * 2;
            historyDirty = false;
            historyInputGroup = '';
          }
           function ensureNativeCodeCard(pre) {
             if (!pre || pre.tagName.toLowerCase() !== 'pre' || !editor.contains(pre)) return;
             var wrapper = pre.parentElement && pre.parentElement.classList.contains('native-code-card') ? pre.parentElement : null;
             if (!wrapper) {
               wrapper = document.createElement('div');
               wrapper.className = 'native-code-card';
               var header = document.createElement('div');
               header.className = 'native-code-card-header';
               header.setAttribute('contenteditable', 'false');
               var title = document.createElement('span');
               title.className = 'native-code-card-title';
               title.textContent = '$codeBlockLabel';
               var controls = document.createElement('span');
               controls.className = 'native-code-card-controls';
               var select = document.createElement('select');
               select.className = 'native-code-card-language';
               select.setAttribute('aria-label', '$codeLanguageLabel');
               select.innerHTML = '$languageOptions';
               var format = document.createElement('button');
               format.type = 'button';
               format.className = 'native-code-card-format';
               format.setAttribute('aria-label', '$formatCodeLabel');
               format.setAttribute('title', '$formatCodeLabel');
               format.textContent = '✦';
               var status = document.createElement('span');
               status.className = 'native-code-card-status';
               status.setAttribute('aria-live', 'polite');
               controls.append(select, format, status);
               header.append(title, controls);
               wrapper.append(header);
               pre.parentNode.insertBefore(wrapper, pre);
               wrapper.append(pre);
               select.addEventListener('mousedown', function(event) { event.stopPropagation(); });
               select.addEventListener('change', editorHistoryAction(function() {
                 // 语言属性与高亮属于一次业务操作，撤销时同时还原控件和正文标记。
                 var language = select.value;
                 pre.setAttribute('data-language', language);
                 var code = Array.prototype.find.call(pre.children, function(child) {
                   return child.tagName && child.tagName.toLowerCase() === 'code';
                 });
                 if (code) code.className = 'language-' + language;
                 if (window.HlovetSyntax) window.HlovetSyntax.refreshEditor(editor, pre);
                 report();
               }));
               format.addEventListener('mousedown', function(event) { event.preventDefault(); });
               format.addEventListener('click', function() {
                 var code = Array.prototype.find.call(pre.children, function(child) {
                   return child.tagName && child.tagName.toLowerCase() === 'code';
                 });
                 if (!code || !window.NativeEditor || typeof window.NativeEditor.onFormatRequested !== 'function') {
                   showFormatStatus(false, 'missing_target', pre);
                   return;
                 }
                 formatTarget = pre;
                 window.NativeEditor.onFormatRequested(codeLanguageFor(pre), code.textContent || '');
               });
             }
             var select = wrapper.querySelector('.native-code-card-language');
             if (select) select.value = codeLanguageFor(pre);
           }
           function ensureNativeCodeCards(root) {
             var target = root || editor;
             Array.prototype.forEach.call(target.querySelectorAll('pre'), ensureNativeCodeCard);
           }
           window.ensureNativeCodeCards = function() { ensureNativeCodeCards(editor); };
          var clipboardTransferSequence = 0;
          var pendingClipboardTransfers = Object.create(null);
          var clipboardFileQueue = [];
          var clipboardFileQueueRunning = false;
          window.completeNativeClipboardFile = function(id, success) {
            var complete = pendingClipboardTransfers[id];
            if (!complete) return;
            delete pendingClipboardTransfers[id];
            complete(success === true);
          };
          function transferClipboardFile(file) {
            return new Promise(function(resolve) {
              var id = Date.now().toString(36) + '-' + (++clipboardTransferSequence).toString(36);
              if (!file || file.size > $clipboardFileMaxBytes || !window.NativeEditor ||
                  !window.NativeEditor.onClipboardFileStart || !window.NativeEditor.onClipboardFileChunk) {
                resolve(false);
                return;
              }
              pendingClipboardTransfers[id] = resolve;
              if (!window.NativeEditor.onClipboardFileStart(id, file.name || '', file.type || '', String(file.size))) {
                delete pendingClipboardTransfers[id];
                resolve(false);
                return;
              }
              var offset = 0;
              function readChunk() {
                var end = Math.min(offset + $clipboardFileChunkBytes, file.size);
                var reader = new FileReader();
                reader.onload = function() {
                  var dataUrl = String(reader.result || '');
                  var comma = dataUrl.indexOf(',');
                  if (comma < 0) {
                    window.NativeEditor.onClipboardFileFailed(id);
                    return;
                  }
                  var chunk = dataUrl.slice(comma + 1);
                  var last = end >= file.size;
                  window.NativeEditor.onClipboardFileChunk(id, chunk, last);
                  if (last) return;
                  offset = end;
                  window.setTimeout(readChunk, 0);
                };
                reader.onerror = function() { window.NativeEditor.onClipboardFileFailed(id); };
                reader.readAsDataURL(file.slice(offset, end));
              }
              readChunk();
            });
          }
          // 插入剪贴板 text/html；需要原生清洗的富文本异步回到插入入口，不合并其它操作。
          function insertClipboardTextOrHtml(text, html) {
            // 普通文本通过 input 记录历史，异步 Markdown/HTML 通过桥接插入单独记录。
            if (/^\s*[-*+]\s+\[[ xX]\]/m.test(text)) {
              captureSelection();
              requestBridge('onMarkdownPasteRequested', text.slice(0, 50000));
              return;
            }
            if (html && html.trim() && window.NativeEditor && typeof window.NativeEditor.onHtmlPasteRequested === 'function') {
              captureSelection();
              window.NativeEditor.onHtmlPasteRequested(html.slice(0, 200000), text.slice(0, 50000));
              return;
            }
            editorHistoryAction(function() {
              document.execCommand('insertText', false, text.slice(0, 50000));
              report();
            })();
            window.setTimeout(function() {
              if (autoLinkAtCaret()) report();
            }, 0);
          }
          function runClipboardFileQueue() {
            if (clipboardFileQueueRunning || !clipboardFileQueue.length) return;
            clipboardFileQueueRunning = true;
            var request = clipboardFileQueue.shift();
            var index = 0;
            function next() {
              if (index >= request.files.length) {
                clipboardFileQueueRunning = false;
                runClipboardFileQueue();
                return;
              }
              transferClipboardFile(request.files[index]).then(function(success) {
                if (!success) {
                  if (index === 0) insertClipboardTextOrHtml(request.text, request.html);
                  clipboardFileQueueRunning = false;
                  runClipboardFileQueue();
                  return;
                }
                index += 1;
                next();
              });
            }
            next();
          }
          function transferClipboardFiles(files, text, html) {
            // 整批校验后再排队，不能只上传前九个而让用户误以为全部成功。
            var totalBytes = files.reduce(function(total, file) { return total + file.size; }, 0);
            if (files.length > $nativeClipboardFileMaxCount || totalBytes > $NATIVE_ARTICLE_ATTACHMENT_BATCH_BYTES) {
              if (window.NativeEditor) window.NativeEditor.onClipboardSelectionRejected(files.length, String(totalBytes));
              return;
            }
            clipboardFileQueue.push({ files: files, text: text, html: html });
            runClipboardFileQueue();
          }
          document.querySelectorAll('select').forEach(function(select) {
            select.addEventListener('mousedown', captureSelection);
          });
          document.getElementById('block-type').addEventListener('change', editorHistoryAction(function(event) {
            // 段落转换与原生 input 使用同一事务，不能生成两个撤销点。
            focusEditor();
            document.execCommand('formatBlock', false, event.target.value);
            updateToolbarState();
            report();
          }));
          document.getElementById('font-size').addEventListener('change', editorHistoryAction(function(event) {
            // 字号转换完成后再提交含规范化 font 标记的正文。
            applyFontSize(event.target.value);
            updateToolbarState();
            report();
          }));
          document.querySelectorAll('[data-command]').forEach(function(button) {
            button.addEventListener('mousedown', function(event) { event.preventDefault(); });
            button.addEventListener('click', function() {
              // 撤销重做只操作统一历史，不再调用浏览器另一套命令历史。
              var command = button.getAttribute('data-command');
              if (command === 'undo' || command === 'redo') { travelEditorHistory(command === 'undo' ? -1 : 1); return; }
              editorHistoryAction(function() {
              focusEditor();
              var value = button.getAttribute('data-value');
              if (command === 'createLink') {
                var range = captureSelection();
                var node = range && (range.startContainer.nodeType === 1 ? range.startContainer : range.startContainer.parentElement);
                var anchor = node && node.closest('a[href]');
                linkTarget = anchor && editor.contains(anchor) ? anchor : null;
                requestBridge('onLinkRequested', anchor && editor.contains(anchor) ? anchor.getAttribute('href') || '' : '');
                return;
              }
              if (command === 'removeFormat') clearFormatting();
              else document.execCommand(command, false, value);
              updateToolbarState();
              report();
              })();
            });
          });
          document.addEventListener('selectionchange', function() {
            var selection = window.getSelection();
            if (selection && selection.rangeCount && editor.contains(selection.anchorNode)) {
              savedRange = selection.getRangeAt(0).cloneRange();
              updateToolbarState();
            }
          });
          function selectedList() {
            var selection = window.getSelection();
            var node = selection && selection.rangeCount ? selection.anchorNode : null;
            var element = node && (node.nodeType === 1 ? node : node.parentElement);
            var list = element && element.closest('ul, ol');
            return list && editor.contains(list) ? list : null;
          }
          // 将新任务 item 包装为与 PC 一致的复选框和正文结构，默认保持未完成。
          function setTaskItem(item) {
            // 复选框不参与正文编辑，避免回车和光标进入 label 内部。
            item.setAttribute('data-type', 'taskItem');
            item.setAttribute('data-checked', 'false');
            var label = document.createElement('label');
            label.setAttribute('contenteditable', 'false');
            var checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            var marker = document.createElement('span');
            label.appendChild(checkbox);
            label.appendChild(marker);
            var content = document.createElement('div');
            while (item.firstChild) content.appendChild(item.firstChild);
            item.appendChild(label);
            item.appendChild(content);
          }
          function clearTaskItem(item) {
            item.removeAttribute('data-type');
            item.removeAttribute('data-checked');
            var label = item.querySelector(':scope > label');
            if (label) label.remove();
            var content = item.querySelector(':scope > div');
            if (content) content.replaceWith.apply(content, Array.prototype.slice.call(content.childNodes));
          }
          document.getElementById('insert-task-list').addEventListener('mousedown', function(event) { event.preventDefault(); });
          document.getElementById('insert-task-list').addEventListener('click', editorHistoryAction(function() {
            // 浏览器建列表与任务节点包装合并为一次撤销，不能停在半成品普通列表。
            focusEditor();
            var list = selectedList();
            if (!list) {
              document.execCommand('insertUnorderedList', false, null);
              list = selectedList();
            }
            if (!list) return;
            if (list.getAttribute('data-type') === 'taskList') {
              list.removeAttribute('data-type');
              Array.prototype.forEach.call(list.children, clearTaskItem);
            } else {
              if (list.tagName.toLowerCase() === 'ol') {
                var unordered = document.createElement('ul');
                while (list.firstChild) unordered.appendChild(list.firstChild);
                list.replaceWith(unordered);
                list = unordered;
              }
              list.setAttribute('data-type', 'taskList');
              Array.prototype.forEach.call(list.children, function(item) {
                if (item.tagName.toLowerCase() === 'li') setTaskItem(item);
              });
            }
            report();
          }));
          function placeCaretAtEnd(node) {
            if (!node) return;
            var selection = window.getSelection();
            var range = document.createRange();
            range.selectNodeContents(node);
            range.collapse(false);
            selection.removeAllRanges();
            selection.addRange(range);
          }
          function convertTypedTaskShortcut() {
            var selection = window.getSelection();
            if (!selection || !selection.rangeCount || !selection.isCollapsed) return false;
            var anchor = selection.anchorNode;
            var element = anchor && (anchor.nodeType === 1 ? anchor : anchor.parentElement);
            var block = element && element.closest('p, li');
            if (!block || !editor.contains(block)) return false;
            var text = (block.textContent || '').replace(/\u00a0/g, ' ');
            var marker = /^(?:\s*[-*+]\s+)?\[([ xX])\]\s*$/.exec(text);
            if (!marker) return false;
            var listItem = block.tagName.toLowerCase() === 'li' ? block : block.closest('li');
            var list = listItem ? listItem.parentElement : null;
            if (list && !/^(ul|ol)$/i.test(list.tagName)) return false;
            if (list && list.children.length !== 1) return false;
            if (!list && !/^\s*[-*+]\s+\[[ xX]\]\s*$/.test(text)) return false;

            var item = listItem || document.createElement('li');
            item.textContent = '';
            setTaskItem(item);
            item.setAttribute('data-checked', marker[1].toLowerCase() === 'x' ? 'true' : 'false');
            var checkbox = item.querySelector(':scope > label > input[type="checkbox"]');
            if (checkbox) checkbox.checked = marker[1].toLowerCase() === 'x';
            var content = item.querySelector(':scope > div');
            if (!content) return false;
            var paragraph = document.createElement('p');
            paragraph.innerHTML = '<br>';
            content.appendChild(paragraph);

            if (!list) {
              list = document.createElement('ul');
              block.parentNode.replaceChild(list, block);
              list.appendChild(item);
            }
            list.setAttribute('data-type', 'taskList');
            placeCaretAtEnd(paragraph);
            return true;
          }
          function convertMarkdownBlockShortcut() {
            var selection = window.getSelection();
            if (!selection || !selection.rangeCount || !selection.isCollapsed) return false;
            var anchor = selection.anchorNode;
            var element = anchor && (anchor.nodeType === 1 ? anchor : anchor.parentElement);
            var block = element && element.closest('p');
            if (!block || !editor.contains(block) || block.closest('li, blockquote, pre, resource-block')) return false;
            if (block.childNodes.length !== 1 || block.firstChild.nodeType !== 3) return false;
            var range = selection.getRangeAt(0);
            var prefix = document.createRange();
            prefix.selectNodeContents(block);
            prefix.setEnd(range.startContainer, range.startOffset);
            var text = (block.textContent || '').replace(/\u00a0/g, ' ');
            if (prefix.toString().replace(/\u00a0/g, ' ') !== text) return false;

            var replacement = null;
            var caretTarget = null;
            var heading = /^(#{1,3})$/.exec(text);
            var unordered = /^[-*+]$/.test(text);
            var ordered = /^(\d+)\.$/.exec(text);
            var quote = text === '>';
            var rule = /^(?:---+|___+|\*\*\*+)$/.test(text);
            var fence = /^(?:\x60{3})([a-z0-9+#-]*)$/i.exec(text);

            if (heading) {
              replacement = document.createElement('h' + heading[1].length);
              replacement.innerHTML = '<br>';
              caretTarget = replacement;
            } else if (unordered || ordered) {
              replacement = document.createElement(unordered ? 'ul' : 'ol');
              if (ordered && Number(ordered[1]) !== 1) replacement.setAttribute('start', ordered[1]);
              var item = document.createElement('li');
              caretTarget = item;
              item.innerHTML = '<br>';
              replacement.appendChild(item);
            } else if (quote) {
              replacement = document.createElement('blockquote');
              caretTarget = document.createElement('p');
              caretTarget.innerHTML = '<br>';
              replacement.appendChild(caretTarget);
            } else if (rule) {
              replacement = document.createElement('hr');
              caretTarget = document.createElement('p');
              caretTarget.innerHTML = '<br>';
            } else if (fence) {
              replacement = document.createElement('pre');
              var code = document.createElement('code');
              var requestedLanguage = (fence[1] || 'plaintext').toLowerCase();
               var languageExists = supportedCodeLanguages.indexOf(requestedLanguage) >= 0;
              var language = languageExists ? requestedLanguage : 'plaintext';
              replacement.setAttribute('data-language', language);
              code.className = 'language-' + language;
              code.innerHTML = '<br>';
              replacement.appendChild(code);
              caretTarget = code;
            } else {
              return false;
            }

            block.parentNode.replaceChild(replacement, block);
            if (rule) replacement.parentNode.insertBefore(caretTarget, replacement.nextSibling);
            if (window.HlovetSyntax && replacement.tagName === 'PRE') {
              window.HlovetSyntax.refreshEditor(editor, replacement);
            }
            placeCaretAtEnd(caretTarget);
            return true;
          }
          function convertMarkdownInlineShortcut() {
            var selection = window.getSelection();
            if (!selection || !selection.rangeCount || !selection.isCollapsed) return false;
            var anchor = selection.anchorNode;
            var element = anchor && (anchor.nodeType === 1 ? anchor : anchor.parentElement);
            var block = element && element.closest('p');
            if (!block || !editor.contains(block) || block.closest('a, code, pre, li, resource-block')) return false;
            if (block.childNodes.length !== 1 || block.firstChild.nodeType !== 3) return false;
            var range = selection.getRangeAt(0);
            if (range.startContainer !== block.firstChild || range.startOffset !== block.firstChild.nodeValue.length) return false;
            var text = block.firstChild.nodeValue;
            var match = /^(\*\*|__|~~|\x60|\*|_)(\S(?:[\s\S]*?\S)?)(\1)$/.exec(text);
            if (!match) return false;
            var tag = match[1] === '~~' ? 's' : (match[1] === '\x60' ? 'code' : (
              match[1] === '**' || match[1] === '__' ? 'strong' : 'em'
            ));
            var mark = document.createElement(tag);
            mark.textContent = match[2];
            block.textContent = '';
            block.appendChild(mark);
            var caret = document.createRange();
            caret.setStartAfter(mark);
            caret.collapse(true);
            selection.removeAllRanges();
            selection.addRange(caret);
            savedRange = caret.cloneRange();
            return true;
          }
          function currentTaskItem() {
            var selection = window.getSelection();
            if (!selection || !selection.rangeCount) return null;
            var anchor = selection.anchorNode;
            var element = anchor && (anchor.nodeType === 1 ? anchor : anchor.parentElement);
            var item = element && element.closest('li[data-type="taskItem"]');
            return item && editor.contains(item) ? item : null;
          }
          function indentTaskItem(item) {
            var previous = item && item.previousElementSibling;
            if (!item || !previous || previous.tagName.toLowerCase() !== 'li') return false;
            var previousContent = previous.querySelector(':scope > div');
            if (!previousContent) return false;
            var nested = previousContent.querySelector(':scope > ul[data-type="taskList"]');
            if (!nested) {
              nested = document.createElement('ul');
              nested.setAttribute('data-type', 'taskList');
              previousContent.appendChild(nested);
            }
            nested.appendChild(item);
            return true;
          }
          function outdentTaskItem(item) {
            if (!item) return false;
            var nestedList = item.parentElement;
            if (!nestedList || nestedList.tagName.toLowerCase() !== 'ul' || nestedList.getAttribute('data-type') !== 'taskList') return false;
            var parentItem = nestedList.closest('li[data-type="taskItem"]');
            var outerList = parentItem && parentItem.parentElement;
            if (!parentItem || !outerList || !/^(ul|ol)$/i.test(outerList.tagName)) return false;
            outerList.insertBefore(item, parentItem.nextElementSibling);
            if (!nestedList.children.length) nestedList.remove();
            return true;
          }
          // 判断任务正文 content 是否为空；图片、代码和子任务仍属于有效内容。
          function isEmptyTaskContent(content) {
            // 空段落中的占位换行不算内容，不能误删附件、水平线或嵌套列表。
            return !content.textContent.replace(/[\s\u200b]/g, '') &&
              !content.querySelector('img, audio, video, pre, hr, table, ul, ol, resource-block');
          }
          // 将光标移到任务正文 content 的首段开头，并保存选区供后续工具栏恢复。
          function placeCaretAtTaskStart(content) {
            // 显式进入正文段落，不能停留在复选框或正文容器外部。
            var target = content.querySelector(':scope > p, :scope > h1, :scope > h2, :scope > h3') || content;
            var range = document.createRange();
            range.selectNodeContents(target);
            range.collapse(true);
            var selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);
            savedRange = range.cloneRange();
          }
          // 按当前任务内选区拆分或退出任务；返回 true 表示已消费普通回车。
          function handleTaskParagraph() {
            // 仅处理单个任务正文内的选区，跨任务选择和非任务内容交给浏览器处理。
            var item = currentTaskItem();
            var content = item && item.querySelector(':scope > div');
            var selection = window.getSelection();
            if (!content || !selection || !selection.rangeCount) return false;
            var range = selection.getRangeAt(0);
            if (!content.contains(range.startContainer) || !content.contains(range.endContainer)) return false;
            // 延续原有回车自动链接行为，再读取转换后的光标范围以免截断链接。
            if (range.collapsed) {
              autoLinkAtCaret();
              range = selection.getRangeAt(0);
            }
            range.deleteContents();

            // 嵌套空任务提升一级；顶层空任务退出，并保留两侧任务列表的原有次序。
            if (isEmptyTaskContent(content)) {
              if (outdentTaskItem(item)) {
                placeCaretAtTaskStart(content);
              } else {
                var list = item.parentElement;
                var following = list.cloneNode(false);
                while (item.nextSibling) following.appendChild(item.nextSibling);
                var paragraph = document.createElement('p');
                paragraph.innerHTML = '<br>';
                list.parentNode.insertBefore(paragraph, list.nextSibling);
                if (following.childNodes.length) paragraph.parentNode.insertBefore(following, paragraph.nextSibling);
                item.remove();
                if (!list.children.length) list.remove();
                placeCaretAtTaskStart(paragraph);
              }
              return true;
            }

            // 提取光标后内容到新任务，保留行内格式；新任务不继承上一项的完成状态。
            var tail = range.cloneRange();
            tail.setEnd(content, content.childNodes.length);
            var nextItem = document.createElement('li');
            nextItem.appendChild(tail.extractContents());
            setTaskItem(nextItem);
            var nextContent = nextItem.querySelector(':scope > div');
            if (!content.firstChild) content.innerHTML = '<p><br></p>';
            if (!nextContent.firstChild) nextContent.innerHTML = '<p><br></p>';
            item.parentNode.insertBefore(nextItem, item.nextSibling);
            placeCaretAtTaskStart(nextContent);
            return true;
          }
          ['insert-resource', 'remove-resource', 'upload-attachment', 'open-ai-assistant'].forEach(function(id) {
            var button = document.getElementById(id);
            if (button) button.addEventListener('mousedown', function(event) { event.preventDefault(); });
          });
          document.getElementById('insert-resource').addEventListener('click', function() {
            captureSelection();
            requestBridge('onResourceRequested', selectedHtml());
          });
          document.getElementById('remove-resource').addEventListener('click', editorHistoryAction(function() {
            // 资源解包只改变当前资源边界，历史保留其完整属性以便重做。
            var range = captureSelection();
            var node = range && (range.startContainer.nodeType === 1 ? range.startContainer : range.startContainer.parentElement);
            var resource = node && node.closest('resource-block');
            if (resource && editor.contains(resource)) {
              resource.replaceWith.apply(resource, Array.prototype.slice.call(resource.childNodes));
              report();
            }
          }));
          var attachmentButton = document.getElementById('upload-attachment');
          if (attachmentButton) attachmentButton.addEventListener('click', function() {
            captureSelection();
            requestBridge('onAttachmentRequested');
          });
          var aiButton = document.getElementById('open-ai-assistant');
          if (aiButton) aiButton.addEventListener('click', function() {
            captureSelection();
            requestBridge('onAiRequested', selectedText());
          });
          document.getElementById('insert-code').addEventListener('mousedown', function(event) { event.preventDefault(); });
          document.getElementById('insert-code').addEventListener('click', editorHistoryAction(function() {
            // 代码节点和卡片控件作为一次插入；辅助节点不写入历史正文。
            focusEditor();
            var selection = window.getSelection();
            var range = selection && selection.rangeCount ? selection.getRangeAt(0) : null;
             var language = 'plaintext';
            var pre = document.createElement('pre');
            pre.setAttribute('data-language', language);
            var code = document.createElement('code');
            code.className = 'language-' + language;
            if (range && !range.collapsed) code.appendChild(range.extractContents());
            else code.textContent = '$codePlaceholder';
            pre.appendChild(code);
            if (range) { range.insertNode(pre); range.setStartAfter(pre); range.collapse(true); selection.removeAllRanges(); selection.addRange(range); }
            else editor.appendChild(pre);
             ensureNativeCodeCard(pre);
            if (window.HlovetSyntax) window.HlovetSyntax.refreshEditor(editor, pre);
            report();
          }));
          var formatTarget = null;
           function showFormatStatus(success, reason, targetOverride) {
             var target = targetOverride || formatTarget;
             var status = target && target.parentElement && target.parentElement.querySelector('.native-code-card-status');
             if (!status) return;
             status.textContent = success ? '$formatSuccess' : (
              reason === 'unsupported_language' ? '$formatUnsupported' :
              reason === 'invalid_syntax' ? '$formatInvalid' :
              reason === 'too_large' ? '$formatTooLarge' :
              reason === 'unavailable' ? '$formatUnavailable' : '$formatFailure'
            );
             status.className = success ? 'native-code-card-status success' : 'native-code-card-status error';
            window.clearTimeout(status._timer);
            status._timer = window.setTimeout(function() {
              status.textContent = '';
               status.className = 'native-code-card-status';
            }, 3000);
          }
          editor.addEventListener('input', function(event) {
            // 连续普通输入按类型短时分组，粘贴、回车和结构化命令各自独立。
            normalizeFontSizeMarkup();
            if (event.inputType === 'insertFromPaste' || /[\s.,!?;:)\]}]/.test(event.data || '')) {
              autoLinkAtCaret();
            }
            report(event.inputType || 'action');
            updateToolbarState();
          });
          editor.addEventListener('compositionstart', function() {
            // 输入法开始前固定撤销点，过程中不保存尚未确认的候选字。
            prepareEditorHistory(false);
            historyInputGroup = '';
            historyComposing = true;
          });
          editor.addEventListener('compositionend', function() {
            // 一次中文组合输入只提交一步，后续 input 的相同正文不会重复入栈。
            historyComposing = false;
            report();
          });
          // 硬键盘与软键盘共用任务换段规则；组合输入和 Shift+Enter 仍由浏览器维护。
          editor.addEventListener('beforeinput', function(event) {
            // 浏览器菜单和输入法产生的历史命令也必须进入统一历史。
            if (event.inputType === 'historyUndo' || event.inputType === 'historyRedo') {
              if (event.cancelable) { event.preventDefault(); travelEditorHistory(event.inputType === 'historyUndo' ? -1 : 1); }
              return;
            }
            // Android 11 WebView 偶尔遗漏 compositionend；非组合输入且光标已移动时允许收口旧状态。
            var isCompositionInput = event.isComposing === true ||
              event.inputType === 'insertCompositionText' || event.inputType === 'deleteCompositionText';
            prepareEditorHistory(!isCompositionInput);
            // 软键盘可能不发送 keydown，因此直接拦截可取消的段落插入事件。
            if (event.inputType === 'insertParagraph' && event.cancelable && !event.isComposing &&
                editorHistoryAction(handleTaskParagraph)()) {
              event.preventDefault();
              report();
              updateToolbarState();
            }
          });
          editor.addEventListener('keydown', function(event) {
            // 组合输入的回车用于确认候选字，不能提前拆分用户尚未完成的内容。
            if (event.isComposing && !((event.ctrlKey || event.metaKey) && !event.altKey && /^(z|y)$/i.test(event.key))) return;
            // 硬件键盘快捷键与工具栏完全同序；普通字符只在 beforeinput/input 中记录。
            if ((event.ctrlKey || event.metaKey) && !event.altKey && /^(z|y)$/i.test(event.key)) {
              event.preventDefault();
              travelEditorHistory(event.key.toLowerCase() === 'y' || event.shiftKey ? 1 : -1);
              return;
            }
            if (event.key !== 'Enter' && event.key !== 'Tab' && event.key !== ' ') return;
            editorHistoryAction(function() {
            // 任务换段、缩进及 Markdown 快捷转换都按一次独立操作记录。
            if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey && !event.metaKey && !event.altKey && handleTaskParagraph()) {
              event.preventDefault();
              report();
              updateToolbarState();
              return;
            }
            if (event.key === 'Tab') {
              var item = currentTaskItem();
              var moved = event.shiftKey ? outdentTaskItem(item) : indentTaskItem(item);
              if (moved) {
                event.preventDefault();
                report();
              }
              return;
            }
            if (event.key === ' ') {
              if (convertTypedTaskShortcut() || convertMarkdownBlockShortcut()) {
                event.preventDefault();
                report();
                return;
              }
              convertMarkdownInlineShortcut();
            }
            if (event.key === ' ' || event.key === 'Enter') autoLinkAtCaret();
            })();
          });
          editor.addEventListener('change', editorHistoryAction(function(event) {
            // 保存业务标记与原生勾选属性，确保源码、预览和重新打开的状态一致。
            var checkbox = event.target;
            if (checkbox && checkbox.matches('li[data-type="taskItem"] > label > input[type="checkbox"]')) {
              checkbox.closest('li').setAttribute('data-checked', checkbox.checked ? 'true' : 'false');
              checkbox.toggleAttribute('checked', checkbox.checked);
            }
            report();
            updateToolbarState();
          }));
          editor.addEventListener('paste', function(event) {
            event.preventDefault();
             var clipboard = event.clipboardData || window.clipboardData;
             var text = clipboard.getData('text/plain');
             var html = clipboard.getData('text/html');
            var files = Array.from(clipboard.items || [])
              .filter(function(item) { return item.kind === 'file'; })
              .map(function(item) { return item.getAsFile(); })
              .filter(Boolean);
            if (files.length) {
              captureSelection();
              transferClipboardFiles(files, text, html);
              return;
            }
            insertClipboardTextOrHtml(text, html);
          });
          editor.addEventListener('drop', function(event) {
            var files = event.dataTransfer && event.dataTransfer.files;
            if (files && files.length && window.NativeEditor && window.NativeEditor.onClipboardFileStart) {
              event.preventDefault();
              captureSelection();
              transferClipboardFiles(Array.from(files), '', '');
              return;
            }
            event.preventDefault();
          });
          window.setEditorContent = function(value) {
            // 外部源码切换和回读后重新安装任务边界，并清空旧正文的撤销分支。
            window.clearTimeout(timer);
            savedRange = null;
            linkTarget = null;
            formatTarget = null;
            editor.innerHTML = value;
            prepareTaskItems();
             ensureNativeCodeCards(editor);
            if (window.HlovetSyntax) window.HlovetSyntax.refreshEditor(editor);
            resetEditorHistory();
            updateToolbarState();
          };
          window.syncEditorContent = function() {
            var html = serializedEditorHtml();
            if (window.NativeEditor) window.NativeEditor.onContentChanged(html);
            return html;
          };
          window.readEditorContent = function() {
            return serializedEditorHtml();
          };
          // 把验证后的 value 链接应用到先前选区；修改、插入均作为独立撤销步骤。
          window.applyEditorLink = editorHistoryAction(function(value) {
            // 弹窗期间正文可能已变化，失效链接目标不再复用。
            var range = restoreSelection();
            if (!value) return;
            if (linkTarget && editor.contains(linkTarget)) {
              linkTarget.setAttribute('href', value);
              linkTarget = null;
            } else if (range && range.collapsed) {
              var link = document.createElement('a');
              link.href = value;
              link.textContent = value;
              range.insertNode(link);
              range.setStartAfter(link);
              range.collapse(true);
              var selection = window.getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              savedRange = range.cloneRange();
            } else if (range) {
              document.execCommand('createLink', false, value);
            }
            report();
          });
          // 成功时用 value 替换仍存在的代码；失败 reason 只提示，不生成正文历史。
          window.applyFormattedCode = editorHistoryAction(function(value, success, reason) {
            // 撤销或外部载入已清空 formatTarget，迟到的格式化结果不能覆盖恢复后的正文。
             var target = formatTarget;
             if (!success || !target || !editor.contains(target)) {
               showFormatStatus(false, reason || 'missing_target', target);
               formatTarget = null;
              return;
            }
             var code = target.querySelector('code');
            if (!code) {
               showFormatStatus(false, 'missing_target', target);
              formatTarget = null;
              return;
            }
            code.textContent = value;
             if (window.HlovetSyntax) window.HlovetSyntax.refreshEditor(editor, target);
             showFormatStatus(true, '', target);
             formatTarget = null;
            report();
          });
          // 将已清洗的 value 插入保存选区，供资源、附件、AI 和粘贴共用一次历史事务。
          window.insertEditorHtml = editorHistoryAction(function(value) {
            // 先恢复正文选区；没有选区时追加，不把异步插入与此前键入合并。
            var range = restoreSelection();
            if (!range) {
              editor.insertAdjacentHTML('beforeend', value);
               ensureNativeCodeCards(editor);
              if (window.HlovetSyntax) window.HlovetSyntax.refreshEditor(editor);
              report();
              return;
            }
            range.deleteContents();
            var wrapper = document.createElement('div');
            wrapper.innerHTML = value;
            var fragment = document.createDocumentFragment();
            while (wrapper.firstChild) fragment.appendChild(wrapper.firstChild);
            range.insertNode(fragment);
            range.collapse(false);
            savedRange = range.cloneRange();
             ensureNativeCodeCards(editor);
            if (window.HlovetSyntax) window.HlovetSyntax.refreshEditor(editor);
            report();
          });
          resetEditorHistory();
        })();
        </script>
        </body></html>
    """.trimIndent()
}

internal val previewMarkdownParser: Parser by lazy {
    Parser.builder()
        .extensions(listOf(TablesExtension.create(), TaskListItemsExtension.create()))
        .build()
}

internal val previewMarkdownRenderer: HtmlRenderer by lazy {
    HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create(), TaskListItemsExtension.create()))
        .build()
}

/** 生成标题、摘要和正文的安全预览；appearance 只用于页面默认色，保留作者指定的 titleColor。 */
internal fun buildPreviewHtml(title: String, titleColor: String, summary: String, content: String, contentFormat: String, appearance: NativeArticleWebAppearance = nativeArticleWebAppearance()): String {
    // 先按格式转换并净化正文，标题颜色只接受合法颜色，账号外观不能覆盖作者的显式设置。
    val renderedBody = if (contentFormat == "html") content else previewMarkdownRenderer.render(previewMarkdownParser.parse(content))
    val body = sanitizeNativeArticleHtml(renderedBody)
    val safeTitleColor = normalizeNativeTitleColor(titleColor).orEmpty()
    val resourceLabel = nativePhrase("积分资源", "Point resource")
    val fallbackTitle = nativePhrase("未命名文章", "Untitled article")
    // 预览和版本详情共用此文档，使用编辑/阅读的同一组根样式变量。
    return """
        <!doctype html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        ${appearance.stylesheet()}
        * { box-sizing: border-box; }
        body { margin: 0; padding: 14px 12px 28px; color: var(--article-foreground); background: transparent; font-family: sans-serif; font-size: 16px; line-height: 1.72; overflow-wrap: anywhere; word-break: break-word; }
        h1 { font-size: 24px; line-height: 1.3; margin: 0 0 10px; }
        .summary { color: var(--article-muted); font-size: 14px; margin: 0 0 18px; }
        h2, h3, h4 { line-height: 1.4; margin-top: 1.3em; }
        p { margin: .7em 0; }
        a { color: var(--article-accent); }
        img, video { max-width: 100%; height: auto; }
        hr { border: 0; border-top: 1px solid var(--article-outline); margin: 1.2em 0; }
        s, del { text-decoration-thickness: 1px; }
        u { text-underline-offset: 2px; }
        pre { overflow-x: auto; padding: 12px; border-radius: 10px; background: #17212b; color: #e6edf3; white-space: pre; }
        code { font-family: monospace; font-size: .9em; }
        $nativeSyntaxHighlightCss
        :not(pre) > code { padding: 2px 5px; border-radius: 5px; background: var(--article-accent-soft); }
        blockquote { margin: 1em 0; padding: 1px 14px; border-left: 3px solid var(--article-accent); background: var(--article-accent-soft); }
        resource-block { display: block; margin: 1em 0; padding: 12px 14px; border: 1px solid var(--article-outline); border-radius: 10px; background: var(--article-glass); }
        resource-block::before { display: block; margin-bottom: 6px; color: var(--article-accent); content: '$resourceLabel'; font-size: 12px; font-weight: 600; }
        table { width: 100%; border-collapse: collapse; display: block; overflow-x: auto; }
        th, td { border: 1px solid var(--article-outline); padding: 6px 8px; text-align: left; }
        ul[data-type=taskList] { list-style: none; margin: .24em 0 .38em; padding-left: 0; }
        li[data-type=taskItem] { display: grid; grid-template-columns: 12px minmax(0, 1fr); column-gap: 7px; align-items: start; width: 100%; min-width: 0; margin: .08em 0; list-style: none; }
        li[data-type=taskItem] > label { grid-column: 1; display: flex; position: relative; width: 12px; height: 1.72em; min-width: 12px; align-items: center; margin: 0; padding: 0; }
        li[data-type=taskItem] > label > input[type=checkbox] { position: absolute; inset: 50% auto auto 0; width: 12px; height: 12px; margin: 0; opacity: 0; transform: translateY(-50%); }
        li[data-type=taskItem] > label > span { display: block; width: 12px; height: 12px; border: 1px solid var(--article-outline); border-radius: 3px; }
        li[data-type=taskItem][data-checked=true] > label > span { border-color: var(--article-accent); background: var(--article-accent); box-shadow: inset 0 0 0 3px var(--article-surface); }
        li[data-type=taskItem] > div { grid-column: 2; min-width: 0; width: 100%; margin: 0; }
        li[data-type=taskItem] > div > p { margin: 0; }
        li[data-type=taskItem] > input[type=checkbox] { grid-column: 1; width: 12px; height: 12px; margin: calc((1lh - 12px) / 2) 0 0; }
        input[type=checkbox] { accent-color: var(--article-accent); }
        </style></head><body>
        <h1${if (safeTitleColor.isBlank()) "" else " style=\"color:$safeTitleColor\""}>${htmlEscape(title.ifBlank { fallbackTitle })}</h1>
        ${if (summary.isBlank()) "" else "<p class=\"summary\">${htmlEscape(summary)}</p>"}
        $body
        <script>$nativeArticleAppearanceBridgeScript</script>
        </body></html>
    """.trimIndent()
}

internal fun buildNativeTaskListHtml(lines: List<String>): String {
    val items = lines.ifEmpty { listOf("") }.joinToString("") { line ->
        "<li data-type=\"taskItem\" data-checked=\"false\"><label><input type=\"checkbox\"><span></span></label><div><p>${htmlEscape(line)}</p></div></li>"
    }
    return "<ul data-type=\"taskList\">$items</ul>"
}

private fun htmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

internal fun removeNativeLink(value: TextFieldValue, contentFormat: String): TextFieldValue? {
    val (selectionStart, selectionEnd) = value.selectionBounds()
    val expression = if (contentFormat == "html") {
        Regex("<a\\b[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
    } else {
        Regex("\\[((?:\\\\.|[^\\]])*)\\]\\(((?:\\\\.|[^)])+)\\)")
    }
    val match = expression.findAll(value.text).firstOrNull { candidate ->
        val candidateStart = candidate.range.first
        val candidateEnd = candidate.range.last + 1
        if (selectionStart == selectionEnd) {
            selectionStart in candidateStart..candidateEnd
        } else {
            selectionStart >= candidateStart && selectionEnd <= candidateEnd
        }
    } ?: return null
    val label = match.groups[1] ?: return null
    val linkStart = match.range.first
    val linkEnd = match.range.last + 1
    val labelStart = label.range.first
    val mappedStart = (selectionStart - labelStart).coerceIn(0, label.value.length)
    val mappedEnd = (selectionEnd - labelStart).coerceIn(mappedStart, label.value.length)
    val updatedText = buildString(value.text.length - (linkEnd - linkStart) + label.value.length) {
        append(value.text, 0, linkStart)
        append(label.value)
        append(value.text, linkEnd, value.text.length)
    }
    return TextFieldValue(
        text = updatedText,
        selection = TextRange(linkStart + mappedStart, linkStart + mappedEnd),
    )
}

/** 从 context 读取 uri 的元数据与实际字节，maxImageBytes 为原图上限，remainingBytes 为当前批次剩余额度。 */
internal fun readUploadFile(context: Context, uri: Uri, maxImageBytes: Long, remainingBytes: Long = NATIVE_ARTICLE_ATTACHMENT_BATCH_BYTES): NativeUploadFile {
    // 供应商可能不提供大小；不依赖 reportedSize 的可信度，实际读取仍使用硬上限。
    var name = nativePhrase("附件", "Attachment")
    var reportedSize = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().ifBlank { name }
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) reportedSize = cursor.getLong(sizeIndex)
        }
    }
    val mimeType = nativeArticleAttachmentMimeType(name, context.contentResolver.getType(uri).orEmpty())
    val fileLimit = nativeArticleAttachmentLimitBytes(mimeType, maxImageBytes, prepared = false)
    check(remainingBytes > 0) { nativeArticleAttachmentBatchError() }
    val maxBytes = minOf(fileLimit, remainingBytes)
    val limitMessage = if (remainingBytes < fileLimit) nativeArticleAttachmentBatchError()
    else nativePhrase("$name 不能超过 ${fileLimit / 1024 / 1024}MB。", "$name cannot exceed ${fileLimit / 1024 / 1024} MB.")
    if (reportedSize > maxBytes) {
        throw IllegalStateException(limitMessage)
    }
    // 最多读到限制外一个字节即停止，防止未知大小或伪造元数据导致无界缓冲。
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { readNativeUploadBytes(it, maxBytes) }
            ?: throw IllegalStateException(nativePhrase("无法读取 $name。", "$name could not be read."))
    } catch (_: NativeUploadLimitExceededException) {
        throw IllegalStateException(limitMessage)
    }
    if (bytes.isEmpty()) throw IllegalStateException(nativePhrase("$name 是空文件。", "$name is empty."))
    // 返回已标准化 MIME 的文件，后续压缩和接口按同一类型判断。
    return NativeUploadFile(name = name, mimeType = mimeType, bytes = bytes)
}

private fun appendAttachmentReferences(
    originalContent: String,
    attachments: List<NativeArticleAttachment>,
    contentFormat: String,
): String {
    val references = attachments.joinToString("\n\n") { attachment ->
        val name = attachment.originalName.ifBlank { nativePhrase("附件", "Attachment") }
        val url = attachment.downloadUrl
        if (contentFormat == "html") {
            when (attachment.kind) {
                "image" -> "<p><img src=\"${htmlEscape(url)}\" alt=\"${htmlEscape(name)}\"></p>"
                "audio" -> "<p><audio controls src=\"${htmlEscape(url)}\"></audio></p>"
                "video" -> "<p><video controls src=\"${htmlEscape(url)}\"></video></p>"
                else -> "<p><a href=\"${htmlEscape(url)}\">${htmlEscape(name)}</a></p>"
            }
        } else {
            val markdownName = name.replace("[", "\\[").replace("]", "\\]")
            when (attachment.kind) {
                "image" -> "![$markdownName]($url)"
                "audio" -> "<audio controls src=\"${htmlEscape(url)}\"></audio>"
                "video" -> "<video controls src=\"${htmlEscape(url)}\"></video>"
                else -> "[$markdownName]($url)"
            }
        }
    }
    return listOf(originalContent.trimEnd(), references).filter(String::isNotBlank).joinToString("\n\n")
}

/** 从 content 移除 attachment 的准确 URL 引用，按 contentFormat 保留相邻正文和其它附件。 */
internal fun removeNativeAttachmentReference(
    content: String,
    attachment: NativeArticleAttachment,
    contentFormat: String,
): String {
    // HTML 用已有解析器处理编辑器规范化后的嵌套节点，不要求附件独占一个段落。
    val targetUrl = attachment.downloadUrl
    if (targetUrl.isBlank()) return content
    return if (contentFormat == "html") {
        val document = Jsoup.parseBodyFragment(content)
        document.outputSettings().prettyPrint(false)
        val references = document.select("a[href], img[src], audio[src], video[src], source[src]").filter { node ->
            node.attr(if (node.tagName() == "a") "href" else "src") == targetUrl
        }
        if (references.isEmpty()) return content
        for (reference in references) {
            val node = if (reference.tagName() == "source") reference.closest("audio, video") ?: reference else reference
            val paragraph = node.closest("p")
            node.remove()
            if (paragraph != null && paragraph.text().isBlank() && paragraph.children().isEmpty()) paragraph.remove()
        }
        document.body().html()
    } else {
        // Markdown 文件名允许转义中括号，URL 必须完整匹配，不能误删相似链接。
        val url = Regex.escape(targetUrl)
        val markdownLink = Regex("!?\\[(?:\\\\.|[^\\]\\\\])*\\]\\($url\\)")
        val mediaTag = Regex(
            "<(?:audio|video)\\b[^>]*(?:src|href)\\s*=\\s*[\\\"']$url[\\\"'][^>]*>[\\s\\S]*?</(?:audio|video)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        content.replace(markdownLink, "").replace(mediaTag, "")
    }
}

private fun statusForUpdate(status: String): String = when (status) {
    "published", "unpublished", "blocked" -> status
    else -> "draft"
}

private fun versionSourceLabel(source: String): String = when (source) {
    "autosave" -> nativePhrase("自动保存", "Autosave")
    "manual" -> nativePhrase("手动保存", "Manual save")
    "publish" -> nativePhrase("发布", "Publish")
    "restore" -> nativePhrase("恢复版本", "Restored version")
    else -> source.ifBlank { nativePhrase("版本", "Version") }
}

private fun versionFieldLabel(field: String): String = when (field) {
    "title" -> nativePhrase("标题", "Title")
    "summary" -> nativePhrase("摘要", "Summary")
    "content" -> nativePhrase("正文", "Body")
    "category" -> nativePhrase("分类", "Category")
    "tags" -> nativePhrase("标签", "Tags")
    "visibility" -> nativePhrase("可见范围", "Visibility")
    "status" -> nativePhrase("状态", "Status")
    "roleCodes" -> nativePhrase("可见角色", "Visible roles")
    "titleColor" -> nativePhrase("标题颜色", "Title color")
    else -> field
}

@Composable
private fun editorFieldColors() = HlovetUi.fieldColors()

@Composable
private fun editorChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = HlovetUi.accent,
    selectedLabelColor = HlovetUi.onAccent,
    containerColor = HlovetUi.surfaceLow,
    labelColor = HlovetUi.foreground,
)
