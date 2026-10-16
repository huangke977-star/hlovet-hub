package xyz.hlovet.portal.prototype

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal data class NativeP22BrowserCheckResult(
    val id: String,
    val label: String,
    val labelEn: String,
    val status: String,
    val detail: String,
    val detailEn: String,
    val latencyMs: Long,
)

internal data class NativeP22BrowserAcceptanceResult(
    val generatedAt: String,
    val passed: Boolean,
    val contract: List<NativeP22BrowserCheckResult>,
    val pages: List<NativeP22BrowserCheckResult>,
)

private data class NativeP22CheckSpec(
    val id: String,
    val path: String,
    val label: String,
    val labelEn: String,
)

internal data class NativeP22PageMetrics(
    val scrollWidth: Int,
    val viewportWidth: Int,
    val failedImages: Int,
    val scriptErrors: List<String>,
)

/** 管理员主动发起的站点质量检查，分别记录 HTTP 契约和隔离浏览器页面结果。 */
internal object NativeP22BrowserAcceptance {
    private val contractChecks = listOf(
        NativeP22CheckSpec("api-health", "/api/health", "API 存活", "API liveness"),
        NativeP22CheckSpec("api-readiness", "/api/health/ready", "API 就绪", "API readiness"),
        NativeP22CheckSpec("sitemap", "/api/distribution/sitemap", "站点地图", "Sitemap"),
        NativeP22CheckSpec("rss", "/api/distribution/feeds/site.rss", "RSS", "RSS"),
        NativeP22CheckSpec("home", "/", "中文首页", "Chinese home"),
        NativeP22CheckSpec("english-home", "/en", "英文首页", "English home"),
        NativeP22CheckSpec("manifest", "/manifest.webmanifest", "中文 Manifest", "Chinese manifest"),
        NativeP22CheckSpec("english-manifest", "/en/manifest.webmanifest", "英文 Manifest", "English manifest"),
        NativeP22CheckSpec("service-worker", "/sw.js", "Service Worker", "Service Worker"),
    )

    private val publicPages = listOf(
        NativeP22CheckSpec("home", "/", "首页", "Home"),
        NativeP22CheckSpec("english-home", "/en", "英文首页", "English home"),
        NativeP22CheckSpec("articles", "/articles", "文章列表", "Articles"),
        NativeP22CheckSpec("english-articles", "/en/articles", "英文文章列表", "English articles"),
        NativeP22CheckSpec("topics", "/topics", "专题列表", "Topics"),
        NativeP22CheckSpec("english-topics", "/en/topics", "英文专题列表", "English topics"),
        NativeP22CheckSpec("collections", "/articles/collections", "合集列表", "Collections"),
        NativeP22CheckSpec("english-collections", "/en/articles/collections", "英文合集列表", "English collections"),
    )

    suspend fun run(context: Context): NativeP22BrowserAcceptanceResult {
        val contract = withContext(Dispatchers.IO) {
            contractChecks.map { checkHttp(it) }
        }
        val pages = withContext(Dispatchers.Main.immediate) {
            val results = mutableListOf<NativeP22BrowserCheckResult>()
            publicPages.forEach { spec ->
                results += checkPage(context, spec, 1440, 900, "桌面", "desktop")
                results += checkPage(context, spec, 390, 844, "移动", "mobile")
            }
            results
        }
        val all = contract + pages
        return NativeP22BrowserAcceptanceResult(
            generatedAt = nowIso(),
            passed = all.all { it.status == "passed" },
            contract = contract,
            pages = pages,
        )
    }

    private fun checkHttp(spec: NativeP22CheckSpec): NativeP22BrowserCheckResult {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            val connection = (URL(nativePortalBaseUrl() + spec.path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/html,application/json,application/rss+xml,*/*")
                setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            }
            try {
                val status = connection.responseCode
                (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { input -> input.skip(1) }
                val latency = SystemClock.elapsedRealtime() - startedAt
                if (status in 200..299) {
                    passed(spec, "HTTP $status", "HTTP $status", latency)
                } else {
                    failed(spec, "HTTP $status", "HTTP $status", latency)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            val message = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            failed(spec, "请求失败：$message", "Request failed: $message", SystemClock.elapsedRealtime() - startedAt)
        }
    }

    /**
     * 在 context 的 Activity 中检查 spec 页面，viewportWidth/Height 指定 CSS 视口。
     * viewportLabel/En 用于双语报告；返回成功、问题或超时结果，结束时销毁临时视图。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun checkPage(
        context: Context,
        spec: NativeP22CheckSpec,
        viewportWidth: Int,
        viewportHeight: Int,
        viewportLabel: String,
        viewportLabelEn: String,
    ): NativeP22BrowserCheckResult {
        // 每个页面和视口组成独立结果；缺少 Activity 时明确失败，不伪造检查通过。
        val id = "${spec.id}-${viewportLabelEn}"
        val label = "${spec.label}（$viewportLabel）"
        val labelEn = "${spec.labelEn} ($viewportLabelEn)"
        val startedAt = SystemClock.elapsedRealtime()
        val activity = context.findActivity()
        val contentRoot = activity?.findViewById<ViewGroup>(android.R.id.content)
        if (activity == null || contentRoot == null) {
            return failed(
                NativeP22CheckSpec(id, spec.path, label, labelEn),
                "无法获取页面容器",
                "Could not access the page container",
                SystemClock.elapsedRealtime() - startedAt,
            )
        }
        var activeWebView: WebView? = null
        return withTimeoutOrNull(45_000L) {
            try {
                suspendCancellableCoroutine { continuation ->
                    // 只有管理员执行检查时才启动引擎，临时视图禁用文件访问且不接受用户交互。
                    val density = activity.resources.displayMetrics.density
                    val errors = mutableListOf<String>()
                    var completed = false
                    val webView = nativeCreateWebView(activity).apply {
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        alpha = 0f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        setBackgroundColor(Color.TRANSPARENT)
                        layoutParams = FrameLayout.LayoutParams(
                            (viewportWidth * density).roundToInt().coerceAtLeast(1),
                            (viewportHeight * density).roundToInt().coerceAtLeast(1),
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            useWideViewPort = false
                            loadWithOverviewMode = false
                        }
                    }
                    activeWebView = webView

                    /** 只发布一次 result，已取消的检查不恢复等待协程。 */
                    fun finish(result: NativeP22BrowserCheckResult) {
                        // 脚本及网络回调可能先后结束，同一检查只记录一个最终结果。
                        if (completed) return
                        completed = true
                        if (continuation.isActive) continuation.resume(result)
                    }

                    // 汇总页面溢出、失败图片和脚本错误；实测结果决定检查状态。
                    webView.addJavascriptInterface(PageMetricsBridge { metrics ->
                        webView.post {
                            val issues = buildList {
                                if (nativeP22HasPageOverflow(metrics, viewportWidth)) add(
                                    "横向溢出 ${metrics.scrollWidth}px（目标视口 ${viewportWidth}px；实测 ${metrics.viewportWidth}px）" to
                                        "Horizontal overflow ${metrics.scrollWidth}px (target viewport ${viewportWidth}px; measured ${metrics.viewportWidth}px)",
                                )
                                if (metrics.failedImages > 0) add("${metrics.failedImages} 张图片加载失败" to "${metrics.failedImages} images failed to load")
                                metrics.scriptErrors.take(3).forEach { add("浏览器错误：$it" to "Browser error: $it") }
                                synchronized(errors) { errors.take(3).forEach { add("浏览器错误：$it" to "Browser error: $it") } }
                            }.distinct()
                            val latency = SystemClock.elapsedRealtime() - startedAt
                            if (issues.isEmpty()) {
                                finish(passed(NativeP22CheckSpec(id, spec.path, label, labelEn), "加载正常，${latency} ms · 视口 ${metrics.viewportWidth}px / 页面 ${metrics.scrollWidth}px", "Loaded in ${latency} ms · viewport ${metrics.viewportWidth}px / page ${metrics.scrollWidth}px", latency))
                            } else {
                                finish(failed(NativeP22CheckSpec(id, spec.path, label, labelEn), issues.joinToString("；") { it.first }, issues.joinToString("; ") { it.second }, latency))
                            }
                        }
                    }, "NativeP22")
                    webView.webChromeClient = object : WebChromeClient() {
                        /** 记录 message 中的浏览器错误，返回 true 表示已接收控制台事件。 */
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            // 限制错误数量，防止异常页面占用大量报告内存。
                            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                synchronized(errors) {
                                    if (errors.size < 8) errors += message.message()
                                }
                            }
                            return true
                        }
                    }
                    webView.webViewClient = object : WebViewClient() {
                        /** 记录 view 的 request 主文档对应 response 错误，不把子资源状态当页面状态。 */
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                            // HTTP 错误进入本次页面结果，保留有限的诊断条数。
                            if (request?.isForMainFrame == true) {
                                synchronized(errors) {
                                    if (errors.size < 8) errors += "HTTP ${response?.statusCode ?: 0}"
                                }
                            }
                        }

                        /** 记录 view 的主文档 request 网络 error，无法加载时不能报告正常。 */
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            // 仅主文档错误进入页面加载结果。
                            if (request?.isForMainFrame == true) {
                                synchronized(errors) {
                                    if (errors.size < 8) errors += error?.description?.toString().orEmpty().ifBlank { "页面加载失败" }
                                }
                            }
                        }

                        /** view/url 加载完成后采集页面指标，结果由本地桥返回。 */
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // 指标脚本读取页面尺寸和资源状态，不修改站点业务数据。
                            view?.evaluateJavascript(PAGE_METRICS_SCRIPT, null)
                        }
                    }
                    // 挂载视图才能完成真实页面布局，不以 HTTP 成功替代渲染验证。
                    contentRoot.addView(webView)
                    webView.loadUrl(nativePortalBaseUrl() + spec.path)
                }
            } finally {
                // 超时、取消或正常完成都解除桥并销毁视图，避免干扰原生界面。
                activeWebView?.let { view ->
                    runCatching { contentRoot.removeView(view) }
                    view.stopLoading()
                    view.removeJavascriptInterface("NativeP22")
                    view.destroy()
                }
                activeWebView = null
            }
        } ?: failed(
            NativeP22CheckSpec(id, spec.path, label, labelEn),
            "页面加载超时",
            "Page load timed out",
            SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private fun passed(spec: NativeP22CheckSpec, detail: String, detailEn: String, latencyMs: Long) = NativeP22BrowserCheckResult(spec.id, spec.label, spec.labelEn, "passed", detail, detailEn, latencyMs)
    private fun failed(spec: NativeP22CheckSpec, detail: String, detailEn: String, latencyMs: Long) = NativeP22BrowserCheckResult(spec.id, spec.label, spec.labelEn, "failed", detail, detailEn, latencyMs)

    private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
}

private class PageMetricsBridge(private val onComplete: (NativeP22PageMetrics) -> Unit) {
    @JavascriptInterface
    fun complete(payload: String) {
        val parsed = nativeP22ParsePageMetrics(payload) ?: NativeP22PageMetrics(0, 0, 0, listOf("页面指标解析失败"))
        onComplete(parsed)
    }
}

internal fun nativeP22ParsePageMetrics(payload: String): NativeP22PageMetrics? = runCatching {
    val json = JSONObject(payload)
    NativeP22PageMetrics(
        scrollWidth = json.optInt("scrollWidth"),
        viewportWidth = json.optInt("viewportWidth"),
        failedImages = json.optInt("failedImages"),
        scriptErrors = buildList {
            val values = json.optJSONArray("scriptErrors") ?: return@buildList
            for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(::add)
        },
    )
}.getOrNull()

// WebView may zoom out and expand its layout viewport to fit overflowing content.
internal fun nativeP22HasPageOverflow(metrics: NativeP22PageMetrics, requestedViewportWidth: Int): Boolean =
    metrics.scrollWidth > minOf(metrics.viewportWidth, requestedViewportWidth) + 1

private val PAGE_METRICS_SCRIPT = """
    (function () {
      var errors = [];
      window.addEventListener('error', function (event) {
        if (event && event.message) errors.push(String(event.message));
      });
      window.addEventListener('unhandledrejection', function () {
        errors.push('unhandled promise rejection');
      });
      window.setTimeout(function () {
        var images = Array.prototype.slice.call(document.images || []);
        var failedImages = images.filter(function (image) {
          return image.complete && image.currentSrc && image.naturalWidth === 0;
        }).length;
        var documentElement = document.documentElement;
        var body = document.body;
        var scrollWidth = Math.max(
          documentElement ? documentElement.scrollWidth : 0,
          body ? body.scrollWidth : 0
        );
        window.NativeP22.complete(JSON.stringify({
          scrollWidth: scrollWidth,
          viewportWidth: window.innerWidth || 0,
          failedImages: failedImages,
          scriptErrors: errors.slice(0, 8)
        }));
      }, 1100);
    })();
""".trimIndent()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
    else -> null
}
