package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证阅读正文的实际视口高度，不用铺满父容器的测试布局掩盖高密度设备上的截断。 */
@RunWith(AndroidJUnit4::class)
class NativeArticleBodyLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private lateinit var webView: WebView

    /** 先解除测试组合再销毁本用例 WebView，不清理用户缓存或登录信息。 */
    @After
    fun tearDown() {
        // 页面解除后才销毁原生视图，避免组合树仍引用已销毁的 WebView。
        host.dispose()
        if (::webView.isInitialized) compose.runOnIdle { webView.destroy() }
    }

    /** 两行 HTML 正文的末行必须完整落在视口内，不能只验证首行能出现。 */
    @Test
    fun shortHtmlFitsTheMeasuredViewportOnDenseScreens() {
        // 使用真实文章常见的两行段落，强制换行使断言不依赖具体字体宽度。
        openBody("<p>离线正文第一行<br>离线正文第二行完整显示</p>", "html")
        assertShortBodyFits("short-html")
    }

    /** Markdown 普通段落与任务列表使用同一高度测量，最后一项不应被截断。 */
    @Test
    fun markdownTaskRowsFitTheMeasuredViewport() {
        // 任务列表转换后仍按完整文档尺寸布局，不因来源格式变化缩短视口。
        openBody("正文段落\n\n- [ ] 第一项\n- [x] 第二项\n\n正文结尾", "markdown")
        assertShortBodyFits("markdown-tasks")
    }

    /** 长正文维持 620dp 上限并能够内部滚动到底，不把整篇文章撑满外部列表。 */
    @Test
    fun longHtmlUsesTheCappedScrollableViewport() {
        // 构造超出上限的正文并等待布局稳定，再验证实际像素高度和滚动能力。
        openBody((1..60).joinToString("") { "<p>第 $it 行长正文</p>" }, "html")
        compose.waitUntil(10_000) {
            val metrics = metrics()
            metrics.getDouble("viewport") >= 615 && metrics.getDouble("content") > metrics.getDouble("viewport")
        }
        var nativeHeightDp = 0f
        compose.runOnIdle { nativeHeightDp = webView.height / webView.resources.displayMetrics.density }
        assertTrue("The long body viewport must remain 620dp: $nativeHeightDp", nativeHeightDp in 619f..621f)
        evaluate("window.scrollTo(0, document.body.scrollHeight)")
        compose.waitUntil(10_000) { metrics().getDouble("lastBottom") <= metrics().getDouble("viewport") + 2 }
        saveEvidence("long-html")
    }

    /** 挂载指定 content/contentFormat 的离线阅读组件，返回前等待本地文档完成加载。 */
    private fun openBody(content: String, contentFormat: String) {
        // 用真实列表的非铺满高度布局，所有内容为本地文本，不访问外部服务。
        host.setContent {
            HlovetTheme {
                Column(Modifier.fillMaxSize()) {
                    NativeArticleBodyWebView(
                        content = content,
                        contentFormat = contentFormat,
                        accessToken = null,
                        cacheOwner = "layout-test",
                        offlineOnly = true,
                        onPreviewImage = {},
                        onOpenAttachment = {},
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }
        compose.runOnIdle { webView = requireNotNull(findWebView(compose.activity.window.decorView)) }
        compose.waitUntil(10_000) { evaluate("document.readyState === 'complete' && !!document.querySelector('p')") == "true" }
    }

    /** 核对短正文末尾完全可见；name 用于保留对应截图与实际布局数值。 */
    private fun assertShortBodyFits(name: String) {
        // 截断场景会留下明确失败证据，不能靠宽松的首行或内容存在断言通过。
        var fits = false
        try {
            compose.waitUntil(10_000) {
                val metrics = metrics()
                fits = metrics.getDouble("lastBottom") <= metrics.getDouble("viewport") + 1
                fits
            }
        } finally {
            saveEvidence(name)
        }
        assertTrue("The last paragraph must fit without clipping: ${metrics()}", fits)
    }

    /** 返回 CSS 视口、完整文档和末段边界，用同一坐标系核对是否截断。 */
    private fun metrics(): JSONObject {
        // evaluateJavascript 直接返回对象，避免把 CSS px 当作 Android 物理 px。
        return JSONObject(evaluate("""
            ({viewport: window.innerHeight,
              content: document.body.scrollHeight,
              lastBottom: Array.from(document.querySelectorAll('p')).pop().getBoundingClientRect().bottom,
              pixelRatio: window.devicePixelRatio})
        """.trimIndent()))
    }

    /** 在 UI 线程执行 script 并返回 JavaScript JSON 结果；超时明确失败。 */
    private fun evaluate(script: String): String {
        // WebView 回调由主线程完成，等待发生在测试线程，不阻塞页面测量。
        val completed = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread { webView.evaluateJavascript(script) { result = it; completed.countDown() } }
        assertTrue("WebView layout callback timed out", completed.await(5, TimeUnit.SECONDS))
        return result
    }

    /** 在 view 的子树查找本用例正文 WebView；未挂载时返回 null。 */
    private fun findWebView(view: View): WebView? {
        // 仅遍历测试 Activity 的视图，不从全局引用读取其它页面。
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    /** 将 name 对应的截图及布局指标写入仪器测试附加输出，不包含凭据。 */
    private fun saveEvidence(name: String) {
        // 证据写入测试报告目录，不修改实际离线库、草稿或会话文件。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)).absolutePath
        val directory = File(output, "article-layout-evidence").apply { mkdirs() }
        File(directory, "$name.json").writeText(metrics().toString(2))
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
