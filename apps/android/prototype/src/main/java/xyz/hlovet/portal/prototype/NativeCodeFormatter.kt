package xyz.hlovet.portal.prototype

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

internal sealed interface NativeCodeFormatResult {
    data class Formatted(val code: String) : NativeCodeFormatResult
    enum class Failure : NativeCodeFormatResult { UnsupportedLanguage, InvalidSyntax, TooLarge, Unavailable }
}

/** 通过离线格式化资产处理文章代码，不调用外部模型或执行用户代码。 */
internal object NativeCodeFormatter {
    const val maxSourceLength = 200_000
    private const val asset = "hlovet-code-format.min.js"
    @Volatile private var cachedScript: String? = null

    /** 使用本地脚本格式化 context 所在页面的 source/language；返回代码或明确的失败类型，不联网。 */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun format(context: Context, source: String, language: String): NativeCodeFormatResult {
        // 先限制输入规模，在后台读取可复用脚本，缺失资产时不创建引擎。
        if (source.length > maxSourceLength) return NativeCodeFormatResult.Failure.TooLarge
        // Android 11 自带 WebView 版本较旧，JSON 直接使用原生解析器，避免依赖现代脚本语法。
        if (language.trim().equals("json", ignoreCase = true)) return formatJsonLocally(source)
        val script = withContext(Dispatchers.IO) {
            cachedScript ?: synchronized(this@NativeCodeFormatter) {
                cachedScript ?: runCatching {
                    context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrNull()?.also { cachedScript = it }
            }
        } ?: return NativeCodeFormatResult.Failure.Unavailable

        // WebView 只能在主线程创建，并且需要实际 Activity 承载。
        return withContext(Dispatchers.Main.immediate) {
            var engine: WebView? = null
            var engineParent: ViewGroup? = null
            try {
                withTimeoutOrNull(20_000L) {
                    suspendCancellableCoroutine<NativeCodeFormatResult> { continuation ->
                        val activity = context.findActivity()
                        val contentRoot = activity?.findViewById<ViewGroup>(android.R.id.content)
                        if (activity == null || contentRoot == null) {
                            continuation.resume(NativeCodeFormatResult.Failure.Unavailable)
                            return@suspendCancellableCoroutine
                        }

                        // 按用户的格式化请求初始化引擎；禁网及不可见配置不影响正文编辑器。
                        val view = nativeCreateWebView(activity)
                        engine = view
                        engineParent = contentRoot
                        // Android 11 emulator WebView builds can crash their renderer
                        // when a detached WebView uses the default hardware layer.
                        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        view.alpha = 0f
                        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        view.layoutParams = FrameLayout.LayoutParams(1, 1)
                        view.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = false
                            allowFileAccess = false
                            allowContentAccess = false
                            blockNetworkLoads = true
                        }
                        // 本地桥只回传格式化结果，取消后不再恢复等待协程。
                        view.addJavascriptInterface(FormatResultBridge { result ->
                            view.post { if (continuation.isActive) continuation.resume(result) }
                        }, "NativeFormatResult")
                        view.webViewClient = object : WebViewClient() {
                            /** webView/url 完成本地页面加载后执行格式化资产，取消时不再处理。 */
                            override fun onPageFinished(webView: WebView?, url: String?) {
                                // 只运行应用内预置脚本，用户源码以 JSON 字符串传入而非直接执行。
                                if (!continuation.isActive) return
                                view.evaluateJavascript(script) {
                                    if (!continuation.isActive) return@evaluateJavascript
                                    view.evaluateJavascript(
                                        "HlovetCodeFormatter.format(${JSONObject.quote(source)}, ${JSONObject.quote(language)})" +
                                            ".then(function(result) { NativeFormatResult.complete(JSON.stringify(result)); })" +
                                            ".catch(function() { NativeFormatResult.complete('{\"error\":\"unavailable\"}'); });",
                                        null,
                                    )
                                }
                            }
                            /** 拒绝 view 发起的 url 导航，保持临时引擎离线。 */
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                // 格式化不需要跳转到任何站点。
                                return true
                            }
                        }
                        contentRoot.addView(view)
                        view.loadDataWithBaseURL("https://formatter.invalid/", "<html><body></body></html>", "text/html", "UTF-8", null)
                    }
                } ?: NativeCodeFormatResult.Failure.Unavailable
            } finally {
                // 成功、失败或取消都销毁临时视图，避免占用页面及浏览器资源。
                engineParent?.removeView(engine)
                engine?.apply {
                    stopLoading()
                    removeJavascriptInterface("NativeFormatResult")
                    destroy()
                }
            }
        }
    }

    /** 使用 Android 原生 JSON 解析和缩进输出，保留语法错误与成功结果的明确边界。 */
    private fun formatJsonLocally(source: String): NativeCodeFormatResult {
        // 先确认整个输入都被消费，避免 JSONTokener 只解析前缀而误报成功。
        val value = runCatching {
            val tokener = JSONTokener(source)
            val parsed = tokener.nextValue()
            check(tokener.nextClean() == '\u0000')
            parsed
        }.getOrNull() ?: return NativeCodeFormatResult.Failure.InvalidSyntax

        // 对象和数组使用统一的两格缩进；标量保持合法 JSON 文本表示。
        val formatted = runCatching {
            when (value) {
                is JSONObject -> value.toString(2)
                is JSONArray -> value.toString(2)
                JSONObject.NULL -> "null"
                is String -> JSONObject.quote(value)
                else -> value.toString()
            }
        }.getOrNull() ?: return NativeCodeFormatResult.Failure.InvalidSyntax
        return NativeCodeFormatResult.Formatted(formatted)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
    else -> null
}

private class FormatResultBridge(private val onResult: (NativeCodeFormatResult) -> Unit) {
    @JavascriptInterface
    fun complete(value: String) {
        val result = runCatching {
            val response = JSONObject(value)
            if (response.has("code")) NativeCodeFormatResult.Formatted(response.getString("code")) else when (response.optString("error")) {
                "unsupported_language" -> NativeCodeFormatResult.Failure.UnsupportedLanguage
                "invalid_syntax" -> NativeCodeFormatResult.Failure.InvalidSyntax
                "too_large" -> NativeCodeFormatResult.Failure.TooLarge
                else -> NativeCodeFormatResult.Failure.Unavailable
            }
        }.getOrDefault(NativeCodeFormatResult.Failure.Unavailable)
        onResult(result)
    }
}
