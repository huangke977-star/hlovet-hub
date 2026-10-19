package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用生产编辑/阅读组件验证账号配色，真实 WebView 保留正文、选区、历史和阅读位置。 */
@RunWith(AndroidJUnit4::class)
class NativeArticleAppearanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    /** 三个真实正文组件分别验证，写作预览不能由阅读组件的通过结果代替。 */
    private enum class ArticleSurface { EDITOR, READER, PREVIEW }
    private lateinit var web: WebView
    private lateinit var previous: PreviewAppearance
    private var previousBackground = Color.White
    private var previousSecondary = Color.White
    private val contentChanges = AtomicInteger()
    private val lastContent = AtomicReference<String>()
    private val light = PreviewAppearance("cloud-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFF3F6", 46)
    private val dark = PreviewAppearance("custom", "#76D2E1", "#E5EDF2", "#A8BAC4", "#171E27", 68, 12, "#203A46", 30)
    private val custom = PreviewAppearance("custom", "#216F49", "#253B32", "#536C60", "#F3FAF6", 76, 0, "#C7E9D5", 0)

    /** 把 color 编码为账号配置采用的十六进制颜色。 */
    private fun hex(color: Color): String {
        // 透明度由配置的独立百分比保存，不混入六位颜色字段。
        return "#%06X".format(color.toArgb() and 0xFFFFFF)
    }

    /** 记录当前共享配置，测试结束后还原，不改动真实账号或服务端设置。 */
    @Before fun rememberAppearance() {
        // 这些值仅来自进程内共享令牌，测试不访问账号配置接口。
        compose.runOnUiThread {
            previous = PreviewAppearance("custom", hex(HlovetUi.accent), hex(HlovetUi.foreground), hex(HlovetUi.muted), hex(HlovetUi.surface),
                (HlovetUi.glassFallback.alpha * 100).roundToInt(), HlovetUi.glassBlur, hex(HlovetUi.glassTint), (HlovetUi.glassTint.alpha * 100).roundToInt())
            previousBackground = HlovetUi.background
            previousSecondary = HlovetUi.secondaryAccent
        }
    }

    /** 还原进程内主题，避免隔离测试影响后续用例的默认外观。 */
    @After fun restoreAppearance() {
        // 不写偏好或远端，额外还原按 themeId 派生的背景及辅助强调色。
        host.dispose()
        compose.runOnUiThread {
            if (::web.isInitialized) web.destroy()
            HlovetUi.applyAppearance(previous)
            HlovetUi.background = previousBackground
            HlovetUi.secondaryAccent = previousSecondary
        }
    }

    /** 查找 root 内唯一正文 WebView；null 表示 Compose 尚未挂载生产视图。 */
    private fun findWebView(root: View): WebView? {
        // 不扫描其它 Activity 或模拟器应用。
        if (root is WebView) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) findWebView(root.getChildAt(index))?.let { return it }
        return null
    }

    /** 在现有文档执行 script 并等待真实回包，不能把异步提交当作执行成功。 */
    private fun evaluate(script: String): String {
        // WebView 主线程和测试线程通过一次性信号交接结果。
        val done = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("Appearance script timed out", done.await(15, TimeUnit.SECONDS))
        return result
    }

    /** 读取 script 返回的 JSON 对象，供样式、选区和位置断言使用。 */
    private fun objectResult(script: String): JSONObject {
        // evaluateJavascript 已返回 JSON 对象文本，不对 HTML 做字符串截取。
        return JSONObject(evaluate(script))
    }

    /** 打开 html 对应的 surface 生产组件；initial 为首屏账号外观，测试不加载真实用户文章。 */
    private fun show(html: String, initial: PreviewAppearance, surface: ArticleSurface = ArticleSurface.EDITOR) {
        // 让真实共享状态驱动 Compose 重组和 AndroidView.update，不直接调用样式桥冒充同步。
        compose.runOnUiThread { HlovetUi.applyAppearance(initial) }
        host.setContent {
            var content by remember { mutableStateOf(html) }
            HlovetTheme {
                Box(Modifier.fillMaxSize().background(HlovetUi.surface)) {
                    when (surface) {
                        ArticleSurface.READER -> NativeArticleBodyWebView(content, "html", null, "appearance-fixture", true, {}, {}, Modifier.fillMaxSize())
                        ArticleSurface.PREVIEW -> NativePreviewWebView("Appearance article", "#CC3344", "Article summary", content, "html", modifier = Modifier.fillMaxSize())
                        ArticleSurface.EDITOR -> NativeRichEditorWebView(content, { content = it; lastContent.set(it); contentChanges.incrementAndGet() }, {},
                            visualInsertRequest = null, onVisualInsertHandled = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.runOnIdle { web = requireNotNull(findWebView(compose.activity.window.decorView)) }
        compose.waitUntil(30_000) { evaluate("typeof window.applyNativeArticleAppearance === 'function'") == "true" }
        compose.waitUntil(30_000) { evaluate("!!window.HlovetSyntax") == "true" }
        if (surface == ArticleSurface.EDITOR) compose.waitUntil(30_000) { evaluate("typeof window.readEditorContent === 'function'") == "true" }
        if (surface == ArticleSurface.EDITOR && html.contains("<pre")) compose.waitUntil(30_000) { evaluate("!!document.querySelector('.native-code-card-language')") == "true" }
    }

    /** 切换到 appearance 并等待真实正文颜色更新，不主动重载或替换文档。 */
    private fun switchTo(appearance: PreviewAppearance) {
        // 用账号外观应用入口触发共享状态更新，覆盖颜色、玻璃和浅暗控件模式。
        compose.runOnUiThread { HlovetUi.applyAppearance(appearance) }
        val color = HlovetUi.foreground
        val expected = "rgb(${(color.red * 255).roundToInt()}, ${(color.green * 255).roundToInt()}, ${(color.blue * 255).roundToInt()})"
        compose.waitUntil(15_000) { evaluate("getComputedStyle(document.body).color") == JSONObject.quote(expected) }
        compose.waitForIdle()
    }

    /** 等待浏览器提交当前文档画面，再触发原生绘制；DOM 就绪不能代替真实屏幕已绘制。 */
    private fun awaitVisualFrame() {
        // Android 11 的旧内核可能先完成脚本再绘制，必须等待视觉提交而非固定睡眠。
        val done = CountDownLatch(1)
        compose.runOnUiThread {
            web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                /** requestId 对应本次视觉检查，回调后请求原生刷新并释放等待。 */
                override fun onComplete(requestId: Long) {
                    // 不修改 DOM、焦点或历史，只确保已提交帧进入视图绘制。
                    web.invalidate()
                    done.countDown()
                }
            })
        }
        assertTrue("WebView visual commit timed out", done.await(15, TimeUnit.SECONDS))
        compose.waitForIdle()
    }

    /** 返回可比较的正文、DOM 标记、焦点和原生选区快照。 */
    private fun editorState(): JSONObject {
        // selection 的节点引用另存于网页，正文相同但页面被重建仍会断言失败。
        return objectResult("""(function(){var s=window.getSelection();return {body:window.readEditorContent(), marker:window.appearanceMarker,
            sameNode:s.anchorNode===window.appearanceNode, start:s.anchorOffset, end:s.focusOffset,
            active:document.activeElement.id, scroll:window.scrollY};})()""")
    }

    /** 将 name 截图和当前 CSS 保存到应用私有证据目录，并验证编辑区底色的实际像素。 */
    private fun evidence(name: String, editor: Boolean) {
        // 截图来自真实系统合成；编辑区采样避开文字、边框和滚动条。
        awaitVisualFrame()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-article-appearance-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.json").writeText(objectResult("({foreground:getComputedStyle(document.body).color, root:document.documentElement.getAttribute('style'), scheme:getComputedStyle(document.documentElement).colorScheme})").toString(2))
        if (editor) {
            val bounds = objectResult("(function(){var r=document.getElementById('editor').getBoundingClientRect();return {x:r.right-8,y:r.top+60,width:innerWidth};})()")
            val screen = IntArray(2)
            compose.runOnUiThread { web.getLocationOnScreen(screen) }
            val scale = web.width / bounds.getDouble("width")
            val actual = bitmap.getPixel(screen[0] + (bounds.getDouble("x") * scale).toInt(), screen[1] + (bounds.getDouble("y") * scale).toInt())
            val expected = ColorUtils.compositeColors(HlovetUi.glassTint.toArgb(), ColorUtils.compositeColors(HlovetUi.glassFallback.toArgb(), HlovetUi.surface.toArgb()))
            listOf(16, 8, 0).forEach { shift -> assertTrue("$name background pixel differs: $actual vs $expected", kotlin.math.abs((actual shr shift and 255) - (expected shr shift and 255)) <= 4) }
        }
        bitmap.recycle()
    }

    /** 暗色首屏必须使用账号前景、表面、强调色和玻璃值，代码表面继续保证高亮对比。 */
    @Test fun darkEditorInitialStylesAndPixelsFollowAccount() {
        // 首屏含普通正文、链接、任务和代码，覆盖控件与正文的共同主题来源。
        show("<p>Account appearance <a href=\"https://example.com\">link</a></p>${buildNativeTaskListHtml(listOf("Task"))}<pre data-language=\"json\"><code>{}</code></pre>", dark)
        val styles = objectResult("""(function(){return {foreground:getComputedStyle(document.body).color, toolbar:getComputedStyle(document.querySelector('.toolbar')).backgroundColor,
            caret:getComputedStyle(document.getElementById('editor')).caretColor, scheme:getComputedStyle(document.documentElement).colorScheme,
            checkbox:getComputedStyle(document.querySelector('input[type=checkbox]')).width,
            code:getComputedStyle(document.querySelector('pre')).backgroundColor};})()""")
        assertEquals("rgb(229, 237, 242)", styles.getString("foreground"))
        assertEquals("rgba(23, 30, 39, 0.94)", styles.getString("toolbar"))
        assertEquals("rgb(118, 210, 225)", styles.getString("caret"))
        assertEquals("dark", styles.getString("scheme"))
        assertEquals("12px", styles.getString("checkbox")); assertEquals("rgb(23, 33, 43)", styles.getString("code"))
        // 真实点击任务框，旧内核也必须使用账号强调色，而不是浏览器默认蓝色。
        evaluate("document.querySelector('input[type=checkbox]').click();")
        assertEquals(JSONObject.quote("rgb(118, 210, 225)"), evaluate("getComputedStyle(document.querySelector('input[type=checkbox]')).backgroundColor"))
        evidence("editor-dark-initial", true)
    }

    /** 动态切换浅色、自定义和暗色不改变正文/反向选区，也不清空切换前已有的重做分支。 */
    @Test fun liveEditorAppearancePreservesSelectionAndUndoRedo() {
        // 先通过生产工具栏产生一次真实编辑并撤销，建立有内容的重做分支。
        show("<p>alpha beta</p>", light)
        evaluate("""var p=document.querySelector('#editor p');document.getElementById('editor').focus();window.getSelection().setBaseAndExtent(p.firstChild,0,p.firstChild,5);document.dispatchEvent(new Event('selectionchange'));document.querySelector('[data-command=bold]').click();""")
        val bold = JSONTokener(evaluate("window.readEditorContent()")).nextValue() as String
        assertTrue(bold.contains("<b>alpha</b>") || bold.contains("<strong>alpha</strong>"))
        evaluate("document.querySelector('[data-command=undo]').click();")
        compose.waitUntil(10_000) { lastContent.get() == "<p>alpha beta</p>" }
        evaluate("""window.appearanceMarker='retained';window.appearanceNode=document.querySelector('#editor p').firstChild;document.getElementById('editor').focus();window.getSelection().setBaseAndExtent(window.appearanceNode,8,window.appearanceNode,2);document.dispatchEvent(new Event('selectionchange'));""")
        val before = editorState()
        val calls = contentChanges.get()
        val originalWeb = web
        evidence("editor-light", true)
        switchTo(custom); assertEquals(before.toString(), editorState().toString()); evidence("editor-custom", true)
        switchTo(dark); assertEquals(before.toString(), editorState().toString()); evidence("editor-dark-live", true)
        compose.runOnIdle { assertSame(originalWeb, findWebView(compose.activity.window.decorView)) }
        assertEquals("Theme must not emit content changes", calls, contentChanges.get())
        // 主题更新不应产生历史项或清空旧重做，点击后仍恢复同一粗体正文。
        evaluate("document.querySelector('[data-command=redo]').click();")
        assertEquals(bold, JSONTokener(evaluate("window.readEditorContent()")).nextValue())
        evaluate("document.querySelector('[data-command=undo]').click();")
        assertEquals("<p>alpha beta</p>", JSONTokener(evaluate("window.readEditorContent()")).nextValue())
    }

    /** 长正文外观变化不能重载文档或改变编辑滚动位置，作者已支持的行内字号必须保留。 */
    @Test fun editorKeepsScrollAndAuthoredInlineFormatting() {
        // 净化规则只允许行内字号；不为测试额外开放原本不支持的任意 CSS。
        val html = "<p><span style=\"font-size: 22px\">Authored size</span></p>" + (1..70).joinToString("") { "<p>Line $it for scroll retention</p>" }
        show(html, light)
        assertEquals(JSONObject.quote("22px"), evaluate("getComputedStyle(document.querySelector('#editor span')).fontSize"))
        evaluate("window.appearanceMarker='scroll';window.scrollTo(0,450);")
        val before = objectResult("({body:window.readEditorContent(),scroll:scrollY,marker:window.appearanceMarker})")
        assertTrue(before.getInt("scroll") > 0)
        switchTo(dark)
        assertEquals(before.toString(), objectResult("({body:window.readEditorContent(),scroll:scrollY,marker:window.appearanceMarker})").toString())
        assertEquals(JSONObject.quote("22px"), evaluate("getComputedStyle(document.querySelector('#editor span')).fontSize"))
    }

    /** 阅读使用同一账号主题，动态切换不改变正文和长文章内部阅读位置。 */
    @Test fun readerUpdatesPaletteWithoutReloadOrScrollReset() {
        // 无媒体网络请求，内容与离线策略保持原样，覆盖阅读文档独立于编辑文档的调用链。
        val html = "<p>Preview</p><blockquote>Quote</blockquote><table><tr><td>Cell</td></tr></table>${buildNativeTaskListHtml(listOf("Task"))}" + (1..60).joinToString("") { "<p>Preview line $it</p>" }
        show(html, dark, ArticleSurface.READER)
        assertEquals(JSONObject.quote("rgb(229, 237, 242)"), evaluate("getComputedStyle(document.body).color"))
        evidence("preview-dark", false)
        evaluate("window.appearanceMarker='preview';window.scrollTo(0,300);")
        val before = evaluate("JSON.stringify({body:document.body.innerHTML,scroll:scrollY,marker:window.appearanceMarker})")
        switchTo(custom)
        assertEquals(before, evaluate("JSON.stringify({body:document.body.innerHTML,scroll:scrollY,marker:window.appearanceMarker})"))
        val styles = objectResult("""({foreground:getComputedStyle(document.body).color,link:getComputedStyle(document.querySelector('blockquote')).borderLeftColor,
            border:getComputedStyle(document.querySelector('td')).borderColor,scheme:getComputedStyle(document.documentElement).colorScheme})""")
        assertEquals("rgb(37, 59, 50)", styles.getString("foreground")); assertEquals("rgb(33, 111, 73)", styles.getString("link"))
        assertEquals("rgba(83, 108, 96, 0.24)", styles.getString("border")); assertEquals("light", styles.getString("scheme"))
        evidence("preview-custom", false)
        switchTo(light)
        assertEquals(before, evaluate("JSON.stringify({body:document.body.innerHTML,scroll:scrollY,marker:window.appearanceMarker})"))
        evidence("preview-light", false)
    }

    /** 写作/版本预览独立验证标题、摘要和正文颜色，配色重组不得重载此第三套文档。 */
    @Test fun writePreviewRetainsDocumentAndAuthorTitleColor() {
        // 使用长内容验证实际预览内部滚动，不用阅读组件代替写作预览。
        show((1..65).joinToString("") { "<p>Draft preview line $it</p>" }, dark, ArticleSurface.PREVIEW)
        assertEquals(JSONObject.quote("rgb(229, 237, 242)"), evaluate("getComputedStyle(document.body).color"))
        assertEquals(JSONObject.quote("rgb(168, 186, 196)"), evaluate("getComputedStyle(document.querySelector('.summary')).color"))
        evidence("write-preview-dark", false)
        evaluate("window.appearanceMarker='write-preview';window.scrollTo(0,350);")
        val before = evaluate("JSON.stringify({body:document.body.innerHTML,scroll:scrollY,marker:window.appearanceMarker})")
        assertTrue(objectResult("({scroll:scrollY})").getInt("scroll") > 0)
        switchTo(light)
        assertEquals(before, evaluate("JSON.stringify({body:document.body.innerHTML,scroll:scrollY,marker:window.appearanceMarker})"))
        assertEquals(JSONObject.quote("rgb(82, 97, 111)"), evaluate("getComputedStyle(document.querySelector('.summary')).color"))
        assertEquals(JSONObject.quote("rgb(204, 51, 68)"), evaluate("getComputedStyle(document.querySelector('h1')).color"))
        evidence("write-preview-light", false)
    }
}
