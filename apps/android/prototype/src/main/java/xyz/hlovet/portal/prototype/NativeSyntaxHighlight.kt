package xyz.hlovet.portal.prototype

import android.content.Context
import android.webkit.WebView

internal const val nativeSyntaxHighlightCss = """
pre code.hljs { display: block; padding: 0; color: #c9d1d9; background: transparent; }
.hljs-doctag, .hljs-keyword, .hljs-meta .hljs-keyword, .hljs-template-tag, .hljs-template-variable, .hljs-type, .hljs-variable.language_ { color: #ff7b72; }
.hljs-title, .hljs-title.class_, .hljs-title.class_.inherited__, .hljs-title.function_ { color: #d2a8ff; }
.hljs-attr, .hljs-attribute, .hljs-literal, .hljs-meta, .hljs-number, .hljs-operator, .hljs-selector-attr, .hljs-selector-class, .hljs-selector-id, .hljs-variable { color: #79c0ff; }
.hljs-meta .hljs-string, .hljs-regexp, .hljs-string { color: #a5d6ff; }
.hljs-built_in, .hljs-symbol { color: #ffa657; }
.hljs-code, .hljs-comment, .hljs-formula { color: #8b949e; }
.hljs-name, .hljs-quote, .hljs-selector-pseudo, .hljs-selector-tag { color: #7ee787; }
.hljs-subst { color: #c9d1d9; }
.hljs-section { color: #58a6ff; font-weight: 700; }
.hljs-bullet { color: #f2cc60; }
.hljs-emphasis { color: #c9d1d9; font-style: italic; }
.hljs-strong { color: #c9d1d9; font-weight: 700; }
.hljs-addition { color: #aff5b4; background-color: #033a16; }
.hljs-deletion { color: #ffdcd7; background-color: #67060c; }
pre.hlovet-code-editor { position: relative; }
pre.hlovet-code-editor > code.hlovet-code-source { position: relative; z-index: 1; display: block; min-height: 1.65em; color: transparent; caret-color: #f0f6fc; background: transparent; }
pre.hlovet-code-editor > .hlovet-code-highlight { position: absolute; z-index: 0; top: 12px; left: 12px; min-width: calc(100% - 24px); margin: 0; padding: 0; pointer-events: none; user-select: none; white-space: pre; font: .9em monospace; }
"""

private const val nativeSyntaxHighlightAsset = "hlovet-highlight.min.js"

@Volatile
private var cachedNativeSyntaxHighlightScript: String? = null

private fun nativeSyntaxHighlightScript(context: Context): String {
    cachedNativeSyntaxHighlightScript?.let { return it }
    return synchronized(nativeSyntaxHighlightAsset) {
        cachedNativeSyntaxHighlightScript ?: runCatching {
            context.assets.open(nativeSyntaxHighlightAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("").also { cachedNativeSyntaxHighlightScript = it }
    }
}

internal fun WebView.installNativeReadOnlySyntaxHighlight(onComplete: (() -> Unit)? = null) {
    val script = nativeSyntaxHighlightScript(context)
    if (script.isBlank()) {
        onComplete?.invoke()
        return
    }
    evaluateJavascript(script) {
        evaluateJavascript("window.HlovetSyntax && window.HlovetSyntax.highlightReadOnly(document);") {
            onComplete?.invoke()
        }
    }
}

internal fun WebView.installNativeEditorSyntaxHighlight() {
    val script = nativeSyntaxHighlightScript(context)
    if (script.isBlank()) return
    evaluateJavascript(script) {
        evaluateJavascript(
            "window.ensureNativeCodeCards && window.ensureNativeCodeCards();",
        ) {
            evaluateJavascript(
                "window.HlovetSyntax && window.HlovetSyntax.initEditor(document.getElementById('editor'));",
                null,
            )
        }
    }
}
