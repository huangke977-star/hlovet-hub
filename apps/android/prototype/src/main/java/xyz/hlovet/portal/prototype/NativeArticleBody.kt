package xyz.hlovet.portal.prototype

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Tag
import java.io.FileInputStream
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale

private val nativeArticleBodyParser: Parser by lazy {
    Parser.builder()
        .extensions(listOf(TablesExtension.create(), TaskListItemsExtension.create()))
        .build()
}

private val nativeArticleBodyRenderer: HtmlRenderer by lazy {
    HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create(), TaskListItemsExtension.create()))
        .build()
}

private val nativeArticleAttachmentExtensions = setOf(
    "7z", "aac", "avi", "csv", "doc", "docx", "flac", "gif", "jpg", "jpeg", "m4a", "mkv",
    "mov", "mp3", "mp4", "pdf", "png", "ppt", "pptx", "rar", "svg", "txt", "wav", "webm", "webp",
    "xls", "xlsx", "zip",
)

private val nativeArticleBodyMaxViewportHeight = 620.dp

internal data class NativeArticleImageTarget(
    val url: String,
    val alt: String,
)

internal data class NativeArticleAttachmentTarget(
    val url: String,
    val fileName: String,
)

/** 按 contentFormat 渲染净化后的 content，并用 appearance 初始化正文外观；主题不写入文章数据。 */
internal fun buildNativeArticleBodyHtml(content: String, contentFormat: String, appearance: NativeArticleWebAppearance = nativeArticleWebAppearance()): String {
    // 媒体路径仍按既有受控来源解析，外观变化不改变内容净化和附件访问边界。
    val document = nativeArticleBodyDocument(content, contentFormat)
    document.outputSettings().prettyPrint(false)
    document.body().select("img[src], audio[src], video[src], a[href], resource-block").forEach { element ->
        listOf("src", "href").forEach { attribute ->
            if (!element.hasAttr(attribute)) return@forEach
            val value = element.attr(attribute).trim()
            if (value.startsWith("/")) element.attr(attribute, nativeMediaUrl(value))
        }
    }
    val body = document.body().html()
    // 编辑、预览和阅读复用同一组颜色变量，代码高亮保持独立的高对比度深色表面。
    return """
        <!doctype html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        ${appearance.stylesheet()}
        * { box-sizing: border-box; }
        html, body { margin: 0; padding: 0; background: transparent; }
        body { color: var(--article-foreground); font-family: sans-serif; font-size: 16px; line-height: 1.72; overflow-wrap: anywhere; word-break: break-word; }
        p { margin: .7em 0; }
        h1, h2, h3, h4, h5, h6 { line-height: 1.4; margin: 1.15em 0 .55em; }
        h1:first-child, h2:first-child, h3:first-child { margin-top: 0; }
        a { color: var(--article-accent); }
        img, audio, video { display: block; max-width: 100%; height: auto; margin: 10px 0; }
        img { border-radius: 8px; }
        hr { border: 0; border-top: 1px solid var(--article-outline); margin: 1.2em 0; }
        s, del { text-decoration-thickness: 1px; }
        u { text-underline-offset: 2px; }
        pre { overflow-x: auto; padding: 12px; border-radius: 10px; background: #17212b; color: #e6edf3; white-space: pre; }
        code { font-family: monospace; font-size: .9em; }
        $nativeSyntaxHighlightCss
        :not(pre) > code { padding: 2px 5px; border-radius: 5px; background: var(--article-accent-soft); }
        blockquote { margin: 1em 0; padding: 1px 14px; border-left: 3px solid var(--article-accent); background: var(--article-accent-soft); }
        resource-block { display: block; margin: 1em 0; padding: 12px 14px; border: 1px solid var(--article-outline); border-radius: 10px; background: var(--article-glass); }
        resource-block::before { display: block; margin-bottom: 6px; color: var(--article-accent); content: '积分资源'; font-size: 12px; font-weight: 600; }
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
        </style></head><body>$body
        <script>
        $nativeArticleAppearanceBridgeScript
        (function() {
          function measure() {
            var height = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight, 48);
            if (window.NativeArticleBody && window.NativeArticleBody.onHeight) window.NativeArticleBody.onHeight(height);
          }
          window.__nativeMeasure = measure;
          document.addEventListener('click', function(event) {
            var image = event.target && event.target.closest ? event.target.closest('img') : null;
            if (image && window.NativeArticleBody && window.NativeArticleBody.onImageClicked) {
              event.preventDefault();
              window.NativeArticleBody.onImageClicked(image.currentSrc || image.src || '', image.alt || '');
              return;
            }
            var anchor = event.target && event.target.closest ? event.target.closest('a[href]') : null;
            var href = anchor && (anchor.href || anchor.getAttribute('href') || '');
            if (anchor && href && window.NativeArticleBody && window.NativeArticleBody.isAttachmentUrl &&
                window.NativeArticleBody.isAttachmentUrl(href) && window.NativeArticleBody.onAttachmentClicked) {
              event.preventDefault();
              window.NativeArticleBody.onAttachmentClicked(href, anchor.textContent || '');
            }
          });
          window.addEventListener('load', measure);
          window.setTimeout(measure, 100);
          window.setTimeout(measure, 600);
        })();
        </script></body></html>
    """.trimIndent()
}

internal fun nativeArticleMediaPaths(content: String, contentFormat: String): List<String> {
    return nativeArticleBodyDocument(content, contentFormat)
        .select("img[src], audio[src], video[src], a[href]")
        .mapNotNull { element ->
            val attribute = if (element.normalName() == "a") "href" else "src"
            val value = element.attr(attribute).trim()
            if (!isNativeArticleSafeUrl(value)) return@mapNotNull null
            if (element.normalName() == "a" && !isNativeArticleAttachmentHref(value)) return@mapNotNull null
            value
        }
        .distinct()
}

internal fun nativeOfflineArticleMediaPaths(article: RemoteArticle): List<String> {
    val segments = article.contentSegments.ifEmpty {
        listOf(RemoteArticleSegment(type = article.contentFormat, content = article.content))
    }
    return (article.images + segments.filter { it.type != "resource" }.flatMap { segment ->
        nativeArticleMediaPaths(segment.content.orEmpty(), segment.type)
    }).distinct()
}

internal fun nativeDetectedArticleContentFormat(content: String): String =
    if (Regex("""(?is)<(?:p|div|h[1-6]|ul|ol|pre|table|img|blockquote|section|article)\b""").containsMatchIn(content)) {
        "html"
    } else {
        "markdown"
    }

private fun nativeArticleBodyDocument(content: String, contentFormat: String): org.jsoup.nodes.Document {
    val rendered = if (contentFormat.equals("html", ignoreCase = true)) {
        content
    } else {
        nativeArticleBodyRenderer.render(nativeArticleBodyParser.parse(content))
    }
    return Jsoup.parseBodyFragment(sanitizeNativeArticleHtml(rendered)).also(::normalizeNativeArticleTaskLists)
}

private fun isNativeArticleAttachmentHref(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    val path = uri.path.orEmpty().lowercase(Locale.ROOT)
    val baseUri = runCatching { URI(BuildConfig.API_BASE_URL) }.getOrNull()
    val isTrustedUrl = when {
        uri.scheme.isNullOrBlank() -> value.startsWith("/") && !value.startsWith("//")
        uri.scheme.equals("https", ignoreCase = true) -> uri.host.equals(baseUri?.host, ignoreCase = true)
        uri.scheme.equals("http", ignoreCase = true) ->
            uri.host.equals(baseUri?.host, ignoreCase = true) && baseUri?.scheme.equals("http", ignoreCase = true)
        else -> false
    }
    if (!isTrustedUrl) return false
    val extension = path.substringAfterLast('.', "")
    return extension in nativeArticleAttachmentExtensions ||
        path.contains("/attachment/") ||
        path.contains("/attachments/") ||
        path.contains("/file/") ||
        path.contains("/files/")
}

internal fun normalizeNativeArticleTaskLists(document: org.jsoup.nodes.Document) {
    document.select("ul").forEach { list ->
        val taskItems = list.children().filter { item ->
            item.normalName() == "li" && item.children().any { child ->
                child.normalName() == "input" && child.attr("type").equals("checkbox", ignoreCase = true)
            }
        }
        if (taskItems.isEmpty()) return@forEach

        list.attr("data-type", "taskList")
        taskItems.forEach { item ->
            val checkbox = item.children().firstOrNull { child ->
                child.normalName() == "input" && child.attr("type").equals("checkbox", ignoreCase = true)
            } ?: return@forEach
            val checked = checkbox.hasAttr("checked")
            checkbox.remove()

            val label = Element(Tag.valueOf("label"), "")
            label.appendElement("input").attr("type", "checkbox").also { input ->
                if (checked) input.attr("checked", "")
            }
            label.appendElement("span")

            val paragraph = Element(Tag.valueOf("p"), "")
            item.childNodes().toList().forEach { node ->
                node.remove()
                paragraph.appendChild(node)
            }
            paragraph.html(paragraph.html().trimStart())

            item.attr("data-type", "taskItem")
                .attr("data-checked", checked.toString())
                .empty()
                .appendChild(label)
                .appendChild(Element(Tag.valueOf("div"), "").appendChild(paragraph))
        }
    }
}

/**
 * 渲染 content/contentFormat 指定的安全正文，并按 CSS 视口尺寸设置原生阅读高度。
 * accessToken/cacheOwner 用于受保护媒体及所属账号缓存；offlineOnly 禁止缓存缺失时联网。
 * onPreviewImage/onOpenAttachment 交给原生预览及下载流程，modifier 控制外部布局；账号外观只更新根样式。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NativeArticleBodyWebView(
    content: String,
    contentFormat: String,
    accessToken: String?,
    cacheOwner: String,
    offlineOnly: Boolean = false,
    onPreviewImage: (NativeArticleImageTarget) -> Unit,
    onOpenAttachment: (NativeArticleAttachmentTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 回调保持最新引用；页面内容、账号或令牌变化时重新初始化高度及媒体读取来源。
    val context = LocalContext.current
    val latestPreview = rememberUpdatedState(onPreviewImage)
    val latestAttachment = rememberUpdatedState(onOpenAttachment)
    val appearance = nativeArticleWebAppearance()
    val latestAppearance = rememberUpdatedState(appearance)
    var bodyHeightCssPx by remember(content, contentFormat, accessToken, cacheOwner) { mutableIntStateOf(80) }
    val html = remember(content, contentFormat) { buildNativeArticleBodyHtml(content, contentFormat, appearance) }
    val pageKey = remember(html, accessToken, cacheOwner, offlineOnly) { "${html.hashCode()}:$cacheOwner:${accessToken.orEmpty().hashCode()}:$offlineOnly" }
    // width=device-width 的 CSS px 对应 dp，而非物理 px；再除密度会截断高密度屏的正文。
    val bodyHeight = bodyHeightCssPx.dp.coerceIn(48.dp, nativeArticleBodyMaxViewportHeight)

    /** 给已就绪的 view 应用当前外观并记录快照，补偿页面加载期间发生的账号配置变化。 */
    fun applyAppearance(view: WebView) {
        // 与页面键独立，不能因切换配色重新加载媒体、重置高度或滚动位置。
        view.applyNativeArticleAppearance(latestAppearance.value)
        (view.tag as? NativeArticleBodyViewState)?.appearance = latestAppearance.value
    }

    // 短正文完整展开，长正文保留统一上限及 WebView 内部滚动。
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .height(bodyHeight),
        factory = { viewContext ->
            nativeCreateWebView(viewContext).apply {
                tag = NativeArticleBodyViewState(pageKey)
                // 软件合成避开部分 Android 11 WebView 与玻璃背景叠加时的 RenderThread 崩溃。
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.setSupportZoom(false)
                isVerticalScrollBarEnabled = true
                overScrollMode = WebView.OVER_SCROLL_NEVER
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // 正文内部滑动优先由 WebView 处理，结束后恢复外部文章列表的触摸接收。
                setOnTouchListener { touchedView, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE,
                        -> touchedView.parent?.requestDisallowInterceptTouchEvent(true)
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL,
                        -> touchedView.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                // 本地桥只汇报文档尺寸和用户点击，正文仍经过清洗及受控媒体拦截。
                addJavascriptInterface(
                    NativeArticleBodyBridge(
                        webView = this,
                        reportHeight = { height -> bodyHeightCssPx = height },
                        onPreviewImage = { target -> latestPreview.value(target) },
                        onOpenAttachment = { target -> latestAttachment.value(target) },
                    ),
                    "NativeArticleBody",
                )
                webViewClient = nativeArticleBodyWebViewClient(
                    context = context,
                    accessToken = accessToken,
                    cacheOwner = cacheOwner,
                    offlineOnly = offlineOnly,
                    onHeight = { height -> bodyHeightCssPx = height },
                    onReady = ::applyAppearance,
                )
                loadDataWithBaseURL("https://article.invalid/", html, "text/html", "UTF-8", null)
            }
        },
        update = { view ->
            // 页面键改变后重新安装媒体权限及离线策略，不沿用旧账号的读取规则。
            val state = view.tag as? NativeArticleBodyViewState
            if (state?.pageKey != pageKey) {
                view.tag = NativeArticleBodyViewState(pageKey)
                view.webViewClient = nativeArticleBodyWebViewClient(
                    context = context,
                    accessToken = accessToken,
                    cacheOwner = cacheOwner,
                    offlineOnly = offlineOnly,
                    onHeight = { height -> bodyHeightCssPx = height },
                    onReady = ::applyAppearance,
                )
                view.loadDataWithBaseURL("https://article.invalid/", html, "text/html", "UTF-8", null)
            } else if (state.appearance != appearance) {
                // 仅主题变化时使用现有文档，保持阅读位置和已加载媒体。
                applyAppearance(view)
            }
        },
    )
}

/** 为正文视图分别记录内容/权限页面键和样式快照，外观不能参与页面重载判断。 */
private data class NativeArticleBodyViewState(val pageKey: String, var appearance: NativeArticleWebAppearance? = null)

private class NativeArticleBodyBridge(
    private val webView: WebView,
    private val reportHeight: (Int) -> Unit,
    private val onPreviewImage: (NativeArticleImageTarget) -> Unit,
    private val onOpenAttachment: (NativeArticleAttachmentTarget) -> Unit,
) {
    @JavascriptInterface
    fun onHeight(value: Int) {
        webView.post { reportHeight(value.coerceIn(48, 12000)) }
    }

    @JavascriptInterface
    fun onImageClicked(url: String, alt: String) {
        webView.post { onPreviewImage(NativeArticleImageTarget(url, alt)) }
    }

    @JavascriptInterface
    fun isAttachmentUrl(url: String): Boolean = isNativeArticleAttachmentHref(url)

    @JavascriptInterface
    fun onAttachmentClicked(url: String, fileName: String) {
        if (!isNativeArticleAttachmentHref(url)) return
        val normalizedName = fileName.trim().ifBlank {
            url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "attachment" }
        }
        webView.post { onOpenAttachment(NativeArticleAttachmentTarget(url, normalizedName)) }
    }
}

/** 创建正文媒体客户端；身份/离线参数限制媒体来源，onHeight 汇报高度，onReady 同步已加载文档外观。 */
internal fun nativeArticleBodyWebViewClient(
    context: Context,
    accessToken: String?,
    cacheOwner: String,
    offlineOnly: Boolean,
    onHeight: (Int) -> Unit,
    onReady: (WebView) -> Unit = {},
): WebViewClient = object : WebViewClient() {
    /** 本地正文完成后先同步最新样式，再安装高亮并重新测量实际高度。 */
    override fun onPageFinished(view: WebView?, url: String?) {
        // 主题桥不更改正文，仍沿用原有本地高亮和尺寸回报流程。
        val webView = view ?: return
        onReady(webView)
        /** 测量渲染后的 CSS 高度并交给 onHeight，解析失败时保留已有高度。 */
        fun measure() {
            // CSS 像素到原生视口的转换由正文组件统一处理。
            webView.evaluateJavascript(
                """
                (function() {
                  var height = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight, 48);
                  if (window.NativeArticleBody && window.NativeArticleBody.onHeight) window.NativeArticleBody.onHeight(height);
                  return String(height);
                })();
                """.trimIndent(),
            ) { raw ->
                raw?.trim()?.trim('"')?.toFloatOrNull()?.roundToInt()?.let { onHeight(it.coerceIn(48, 12000)) }
            }
        }
        webView.installNativeReadOnlySyntaxHighlight {
            measure()
            webView.postDelayed(::measure, 180)
            webView.postDelayed(::measure, 700)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("mailto:")) return true
        if (isNativeArticleAttachmentHref(url)) return true
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            true
        }.getOrDefault(true)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val file = runCatching {
            if (offlineOnly) {
                NativeOfflineCache.loadCachedMediaFile(
                    context = context,
                    url = url,
                    cacheOwner = cacheOwner,
                )
            } else {
                NativeOfflineCache.loadMediaFile(
                    context = context,
                    url = url,
                    accessToken = accessToken,
                    cacheOwner = cacheOwner,
                    originalName = url.substringBefore('?').substringAfterLast('/').ifBlank { "article-media" },
                    mimeType = guessNativeArticleMimeType(url),
                    maxBytes = 8L * 1024L * 1024L,
                )
            }
        }.getOrNull() ?: return if (offlineOnly) {
            WebResourceResponse("text/plain", "UTF-8", 404, "Not cached", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        } else null
        return runCatching {
            WebResourceResponse(guessNativeArticleMimeType(url), null, FileInputStream(file))
        }.getOrNull()
    }
}

/** 根据 url 或原文件名推断系统媒体类型；未知后缀返回二进制类型，不将查询参数或片段当作后缀。 */
internal fun guessNativeArticleMimeType(url: String): String {
    // 正文链接只提供名称而没有服务器 MIME 元数据，普通文件必须与图片/音视频一样可匹配查看器。
    return when (url.trim().substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        else -> "application/octet-stream"
    }
}

@Composable
internal fun NativeArticleImagePreviewDialog(
    target: NativeArticleImageTarget,
    accessToken: String?,
    cacheOwner: String,
    offlineOnly: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(target.url, accessToken, cacheOwner) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(target.url, accessToken, cacheOwner) {
        bitmap = withContext(Dispatchers.IO) {
            NativeOfflineCache.loadBitmap(
                context = context,
                url = target.url,
                accessToken = accessToken,
                cacheOwner = cacheOwner,
                offlineOnly = offlineOnly,
            )
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            shape = HlovetUi.cardShape,
            color = HlovetUi.surfaceLow,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        target.alt.ifBlank { nativePhrase("文章图片", "Article image") },
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = nativePhrase("关闭图片预览", "Close image preview"),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 620.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = target.alt,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(nativePhrase("正在读取图片…", "Loading image..."), color = HlovetUi.muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
