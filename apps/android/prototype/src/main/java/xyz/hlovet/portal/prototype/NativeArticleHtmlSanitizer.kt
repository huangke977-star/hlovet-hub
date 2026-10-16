package xyz.hlovet.portal.prototype

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private val nativeArticleTags = setOf(
    "p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "s", "strike", "del", "u",
    "blockquote", "ul", "ol", "li", "pre", "code", "a", "img", "audio", "video", "hr", "table",
    "thead", "tbody", "tr", "th", "td", "div", "span", "label", "input", "resource-block",
)

private val nativeArticleAttributes = mapOf(
    "a" to setOf("href", "target", "rel"),
    "img" to setOf("src", "alt", "title"),
    "audio" to setOf("src", "controls", "preload"),
    "video" to setOf("src", "controls", "preload", "poster"),
    "ul" to setOf("data-type"),
    "ol" to setOf("start"),
    "li" to setOf("class", "data-type", "data-checked"),
    "input" to setOf("type", "checked"),
    "span" to setOf("style"),
    "pre" to setOf("data-language"),
    "code" to setOf("class"),
    "resource-block" to setOf("data-points"),
)

private val nativeArticleLanguages = setOf(
    "plaintext", "html", "css", "javascript", "typescript", "json", "sql", "python", "bash",
    "java", "go", "c", "cpp", "csharp", "php", "rust", "xml", "yaml", "markdown",
)

private val nativeUnsafeContainerTags = setOf("script", "style", "iframe", "object", "embed", "svg", "math", "template")
private val safeFontSize = Regex("^\\s*font-size\\s*:\\s*(?:\\d+(?:\\.\\d+)?(?:px|rem|em|%)|small|medium|large)\\s*;?\\s*$", RegexOption.IGNORE_CASE)
private val safeCodeClass = Regex("language-([a-z0-9+#-]+)", RegexOption.IGNORE_CASE)

internal fun sanitizeNativeArticleHtml(source: String): String {
    val document = Jsoup.parseBodyFragment(source)
    document.outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.html)
    document.body().childNodes().toList().forEach(::sanitizeNativeArticleNode)
    return document.body().html().trim()
}

private fun sanitizeNativeArticleNode(node: Node) {
    if (node !is Element) return
    node.childNodes().toList().forEach(::sanitizeNativeArticleNode)
    val tag = node.normalName()
    if (tag in nativeUnsafeContainerTags) {
        node.remove()
        return
    }
    if (tag !in nativeArticleTags) {
        node.unwrap()
        return
    }

    val supportedAttributes = nativeArticleAttributes[tag].orEmpty()
    node.attributes().asList().forEach { attribute ->
        val name = attribute.key.lowercase()
        val value = attribute.value.trim()
        val keep = name in supportedAttributes && when (name) {
            "href", "src" -> isNativeArticleSafeUrl(value)
            "style" -> safeFontSize.matches(value)
            "class" -> tag != "code" || safeCodeClass.matches(value)
            "type" -> tag != "input" || value.equals("checkbox", ignoreCase = true)
            else -> true
        }
        if (!keep) node.removeAttr(attribute.key)
    }

    if (tag == "input" && !node.attr("type").equals("checkbox", ignoreCase = true)) {
        node.remove()
        return
    }
    if (tag == "li" && node.attr("data-type") == "taskItem") {
        val checkbox = node.selectFirst("input[type=checkbox]")
        val checked = node.attr("data-checked").equals("true", ignoreCase = true) || checkbox?.hasAttr("checked") == true
        node.attr("data-checked", checked.toString())
        if (checked) checkbox?.attr("checked", "") else checkbox?.removeAttr("checked")
    }
    if (tag == "pre") {
        val code = node.children().firstOrNull { it.normalName() == "code" }
        val classLanguage = code?.attr("class")?.let(safeCodeClass::matchEntire)?.groupValues?.getOrNull(1)
        val rawLanguage = node.attr("data-language").ifBlank { classLanguage.orEmpty() }
            .replaceFirst(Regex("^language-", RegexOption.IGNORE_CASE), "")
            .lowercase()
        val language = rawLanguage.takeIf(nativeArticleLanguages::contains) ?: "plaintext"
        node.attr("data-language", language)
        code?.attr("class", "language-$language")
    }
}

internal fun isNativeArticleSafeUrl(value: String): Boolean =
    (value.startsWith("/") && !value.startsWith("//")) ||
        value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("mailto:", ignoreCase = true)

/**
 * Converts the same sanitized subset that the Android editor can render back
 * into Markdown. This is intentionally conservative: unsupported media keeps
 * its safe HTML representation instead of being silently discarded.
 */
internal fun nativeHtmlToMarkdown(source: String): String {
    val document = Jsoup.parseBodyFragment(sanitizeNativeArticleHtml(source))
    document.outputSettings().prettyPrint(false)
    return nativeMarkdownBlocks(document.body()).trim()
}

private fun nativeMarkdownBlocks(parent: Element): String =
    parent.childNodes().joinToString("") { node -> nativeMarkdownBlock(node) }

private fun nativeMarkdownBlock(node: Node): String = when (node) {
    is TextNode -> nativeMarkdownEscape(node.wholeText)
    is Element -> when (node.normalName()) {
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            "${"#".repeat(node.normalName().drop(1).toInt())} ${nativeMarkdownInline(node).trim()}\n\n"
        }
        "p" -> "${nativeMarkdownInline(node).trim()}\n\n"
        "blockquote" -> {
            val body = nativeMarkdownBlocks(node).trim()
            if (body.isBlank()) "" else body.lines().joinToString("\n") { line -> "> $line" } + "\n\n"
        }
        "ul", "ol" -> nativeMarkdownList(node) + "\n\n"
        "pre" -> {
            val code = node.children().toList().firstOrNull { it.normalName() == "code" }
            val language = node.attr("data-language").ifBlank {
                code?.classNames()?.firstOrNull { it.startsWith("language-") }?.removePrefix("language-").orEmpty()
            }.ifBlank { "plaintext" }
            val value = code?.wholeText() ?: node.wholeText()
            "```$language\n${value.trimEnd()}\n```\n\n"
        }
        "hr" -> "---\n\n"
        "table" -> nativeMarkdownTable(node) + "\n\n"
        "resource-block" -> {
            val points = node.attr("data-points").ifBlank { "10" }
            ":::resource{points=$points}\n${nativeMarkdownBlocks(node).trim()}\n:::\n\n"
        }
        "div" -> nativeMarkdownBlocks(node) + if (node.children().any { it.normalName() in nativeMarkdownBlockTags }) "\n" else ""
        "audio", "video" -> nativeMarkdownRawElement(node) + "\n\n"
        "br" -> "  \n"
        else -> nativeMarkdownInline(node)
    }
    else -> ""
}

private val nativeMarkdownBlockTags = setOf(
    "h1", "h2", "h3", "h4", "h5", "h6", "p", "blockquote", "ul", "ol", "pre", "hr", "table", "resource-block",
)

private fun nativeMarkdownInline(parent: Element): String =
    parent.childNodes().joinToString("") { node ->
        when (node) {
            is TextNode -> nativeMarkdownEscape(node.wholeText)
            is Element -> when (node.normalName()) {
                "strong", "b" -> "**${nativeMarkdownInline(node)}**"
                "em", "i" -> "*${nativeMarkdownInline(node)}*"
                "s", "strike", "del" -> "~~${nativeMarkdownInline(node)}~~"
                "u" -> "<u>${nativeMarkdownInline(node)}</u>"
                "code" -> "`${node.wholeText().replace("`", "\\`")}`"
                "a" -> {
                    val href = node.attr("href")
                    val label = nativeMarkdownInline(node).ifBlank { nativeMarkdownEscape(href) }
                    if (href.isBlank()) label else "[$label](${nativeMarkdownUrl(href)})"
                }
                "img" -> "![${nativeMarkdownEscape(node.attr("alt"))}](${nativeMarkdownUrl(node.attr("src"))})"
                "br" -> "  \n"
                "ul", "ol", "pre", "blockquote", "p", "div" -> nativeMarkdownBlock(node)
                "audio", "video" -> nativeMarkdownRawElement(node)
                else -> nativeMarkdownInline(node)
            }
            else -> ""
        }
    }

private fun nativeMarkdownList(list: Element, indent: String = ""): String = buildString {
    val orderedStart = list.attr("start").toIntOrNull()?.takeIf { it > 0 } ?: 1
    list.children().toList().filter { it.normalName() == "li" }.forEachIndexed { index, item ->
        val task = item.attr("data-type") == "taskItem"
        val checkbox = item.children().toList().firstOrNull { it.normalName() == "label" }
            ?.children()?.toList()?.firstOrNull { it.normalName() == "input" && it.attr("type").equals("checkbox", ignoreCase = true) }
        val checked = item.attr("data-checked").equals("true", ignoreCase = true) || checkbox?.hasAttr("checked") == true
        val contentContainer = item.children().toList().firstOrNull { it.normalName() == "div" }
        val nestedLists = buildList {
            item.children().toList().filter { it.normalName() == "ul" || it.normalName() == "ol" }.forEach(::add)
            contentContainer?.children()?.toList()?.filter { it.normalName() == "ul" || it.normalName() == "ol" }?.forEach(::add)
        }.distinct()
        val contentNodes = (contentContainer?.childNodes() ?: item.childNodes()).filterNot { child ->
            child is Element && (child.normalName() == "label" || child.normalName() == "ul" || child.normalName() == "ol")
        }
        val content = contentNodes.joinToString("") { child ->
            when (child) {
                is Element -> nativeMarkdownBlock(child)
                is TextNode -> nativeMarkdownEscape(child.wholeText)
                else -> ""
            }
        }.trim()
        val listIndex = orderedStart + index
        val listMarker = if (task) "- [${if (checked) "x" else " "}] " else if (list.normalName() == "ol") "$listIndex. " else "- "
        append(indent).append(listMarker).append(content.ifBlank { " " }).append('\n')
        nestedLists.forEach { nested -> append(nativeMarkdownList(nested, "$indent  ")).append('\n') }
    }
}.toString().trimEnd()

private fun nativeMarkdownTable(table: Element): String {
    val rows = table.children().toList().flatMap { section ->
        when (section.normalName()) {
            "thead", "tbody" -> section.children().toList().filter { it.normalName() == "tr" }
            "tr" -> listOf(section)
            else -> emptyList()
        }
    }
    if (rows.isEmpty()) return ""
    val values = rows.map { row ->
        row.children().toList().filter { it.normalName() == "th" || it.normalName() == "td" }.map { cell ->
            nativeMarkdownInline(cell).replace("|", "\\|").replace(Regex("\\s+"), " ").trim()
        }
    }
    val width = values.maxOfOrNull { it.size } ?: 0
    if (width == 0) return ""
    val normalized = values.map { it + List(width - it.size) { "" } }
    return buildString {
        append('|').append(normalized.first().joinToString("|", prefix = "", postfix = "|"))
        append('\n').append('|').append(List(width) { "---" }.joinToString("|", prefix = "", postfix = "|"))
        normalized.drop(1).forEach { row -> append('\n').append('|').append(row.joinToString("|", prefix = "", postfix = "|")) }
    }
}

private fun nativeMarkdownRawElement(element: Element): String {
    val tag = element.normalName()
    val attrs = element.attributes().asList().filter { it.key in setOf("src", "poster", "controls", "preload") }
        .joinToString(" ") { attribute -> "${attribute.key}=\"${nativeMarkdownEscape(attribute.value)}\"" }
    return "<$tag${if (attrs.isBlank()) "" else " $attrs"}></$tag>"
}

private fun nativeMarkdownUrl(value: String): String = value.replace(")", "\\)").replace("(", "\\(")

private fun nativeMarkdownEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("`", "\\`")
    .replace("*", "\\*")
    .replace("_", "\\_")
    .replace("[", "\\[")
    .replace("]", "\\]")
