package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** 在真实 MainActivity 与本地 API 中验证账号外观保存、原生标题、正文首屏和页面重建，不覆盖已有账号配置。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class NativeLocalArticleAppearanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** 只取得本次应用的测试驱动，不操作其他模拟器或应用。 */
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    /** 私有会话与截图仅访问当前原生测试包的数据目录。 */
    private val context get() = instrumentation.targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var reader: NativeSession? = null
    private var fixture: NativeSession? = null
    private var previousLocale: String? = null
    private var articleId = 0
    private val marker = "native_appearance_" + UUID.randomUUID().toString().take(8)
    private val checks = JSONArray()
    private val report = JSONObject().put("origin", BuildConfig.API_BASE_URL).put("marker", marker).put("testDataRemoved", false)
    private val dark = PreviewAppearance("custom", "#76D2E1", "#E5EDF2", "#A8BAC4", "#171E27", 68, 12, "#203A46", 30)
    private val light = PreviewAppearance("custom", "#216F49", "#253B32", "#536C60", "#F3FAF6", 68, 12, "#C7E9D5", 30)

    /** 在任何写入之前确认精确本地来源、已保留读者及现有注册策略，不绕过二次验证。 */
    @Before fun prepare() {
        // 真实写入不能落到生产或隔离假服务，也不临时关闭验证和邮件策略。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = NativeSessionStore.load(context)
        // 没有本地会话的设备只跳过真实数据库写入，不能把缺少前置条件当作产品失败。
        assumeTrue("Requires a retained local test session", stored != null)
        val current = requireNotNull(stored)
        // 新安装实例只接受真实 MainActivity 或其它本地专项保留的读者，不注入或复制其它设备的会话。
        val retained = runCatching { JSONObject(File(context.filesDir, "native-main-session-evidence.json").readText()) }.getOrNull()
        val verifiedReader = retained != null && retained.optString("apiOrigin") == BuildConfig.API_BASE_URL &&
            retained.optInt("fixtureId") == current.userId && retained.optString("fixtureUsername") == current.username &&
            retained.optBoolean("retainedForColdLaunch") && current.username.startsWith("native_restore_")
        // 允许由最近真实本地专项保留的同一读者继续验收，但不接受没有专项报告或非本地来源的会话。
        val preservedByLocalSocial = readPreservedLocalReader("native-local-social-report.json", current)
        val preservedByLocalIntegrations = readPreservedLocalReader("native-local-integrations-report.json", current)
        // 本地注册夹具统一使用 native_restore_ 前缀；报告可能来自上一个合法专项的不同读者，因此不把编号绑定为固定值。
        check(current.username.startsWith("native_restore_") || verifiedReader || preservedByLocalSocial || preservedByLocalIntegrations)
        reader = NativeAuthApi.refresh(current.refreshToken)
        check(NativeSessionStore.save(context, requireNotNull(reader)))
        val policy = NativeAuthApi.registrationPolicy()
        check(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled && !policy.loginTurnstileEnabled)
        val security = nativeLocalSecurityPolicy()
        check(!security.optBoolean("mailServiceEnabled") && !security.optBoolean("untrustedDeviceEmailVerificationEnabled"))
        previousLocale = NativeI18n.locale
        instrumentation.runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("readerId", current.userId)
    }

    /** 读取本地真实专项的会话保留证明，避免测试依赖已经过期的固定账号编号。 */
    private fun readPreservedLocalReader(fileName: String, stored: NativeSession): Boolean {
        // 只信任应用私有目录中的报告，并同时核对本地来源、用户 ID 和保留标记。
        val report = runCatching { JSONObject(File(context.filesDir, fileName).readText()) }.getOrNull() ?: return false
        return (report.optString("apiOrigin").ifBlank { report.optString("apiBaseUrl") }) == BuildConfig.API_BASE_URL &&
            report.optInt("readerId") == stored.userId && report.optBoolean("readerSessionPreserved")
    }

    /** 恢复原读者与语言，注销测试会话但不删除账号、文章或外观记录。 */
    @After fun restore() {
        // 先关闭页面以停止自动刷新，再恢复已有加密会话，避免迟到回包覆盖读者。
        val original = reader ?: return
        val latest = NativeSessionStore.load(context)?.takeIf { it.userId == fixture?.userId } ?: fixture
        try {
            closePage()
        } finally {
            check(NativeSessionStore.save(context, original))
            instrumentation.runOnMainSync {
                HlovetUi.applyAppearance(original.appearance)
                previousLocale?.let { NativeI18n.applyLocale(it) }
            }
            report.put("readerSessionRestored", NativeSessionStore.load(context)?.userId == original.userId)
            latest?.let { session -> report.put("fixtureLoggedOut", runCatching { NativeAuthApi.logout(session.refreshToken); true }.getOrDefault(false)) }
            writeReport()
        }
    }

    /** 从密码登录到实际外观表单保存，核对编辑、弹窗预览、私有阅读和重建后的共同色板。 */
    @Test fun realAccountAppearancePersistsAcrossFullEditorPreviewAndReader() {
        // 独立注册只写测试账号，预设暗色用于验证首次真实登录就读取服务端外观。
        val password = "Local!${UUID.randomUUID()}"
        // 昵称遵守服务端 24 字符限制，完整追踪标记只用于用户名、邮箱和报告。
        val nickname = "Appearance ${marker.takeLast(8)}"
        fixture = authenticated(NativeAuthApi.register(marker, nickname, "$marker@example.invalid", password, null, null))
        val initial = requireNotNull(fixture)
        report.put("fixtureId", initial.userId).put("fixtureUsername", marker)
        writeReport()
        NativeDiscoveryApi.completeOnboarding(initial.accessToken, emptyList(), emptyList())
        assertEquals(dark, NativeProfileApi.updateAppearance(initial.accessToken, dark).appearance)
        val body = "<p>$marker retained account appearance</p><p><a href=\"https://example.com\">Appearance link</a></p>" +
            buildNativeTaskListHtml(listOf("Appearance task")) + "<pre data-language=\"json\"><code>{\"retained\":true}</code></pre>"
        val draft = NativeArticleApi.createArticle(initial.accessToken, NativeDraftInput(marker, "Retained full-page appearance verification", body, "html", "随笔", "", visibility = "private"))
        articleId = draft.id
        report.put("articleId", articleId).put("articleSlug", draft.slug)
        val published = NativeArticleApi.publish(initial.accessToken, articleId)
        assertEquals("private", published.visibility)
        assertEquals(403, otherReaderStatus(published.slug))
        NativeAuthApi.logout(initial.refreshToken)
        record("independent-private-article-and-dark-account-config-retained-reader-denied")

        // 登录必须经过生产页面密码输入与提交，不把 API 注册成功当作界面登录成功。
        closePage()
        NativeSessionStore.clear(context)
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitTag("native-navigation-4")
        compose.onNodeWithTag("native-navigation-4").performClick()
        waitTag("native-profile-login")
        compose.onNodeWithTag("native-profile-login").performClick()
        waitTag("native-login-account")
        compose.onNodeWithTag("native-login-account").performTextReplacement(marker)
        compose.onNodeWithTag("native-login-password").performTextReplacement(password)
        compose.onNodeWithTag("native-login-submit").performScrollTo().performNativeStableClick(compose)
        compose.waitUntil(60_000) { NativeSessionStore.load(context)?.userId == initial.userId }
        fixture = requireNotNull(NativeSessionStore.load(context))
        assertEquals(dark, requireNotNull(fixture).appearance)
        record("real-password-login-loaded-saved-dark-appearance")

        // 暗色必须在完整编辑页面和独立预览窗口生效，并且任务与代码仍能显示。
        verifyEditorAndPreview(articleId, dark, "dark")
        verifyReader(published.slug, dark, "dark")

        // 真实个人资料表单更换五种颜色，使用保存按钮确认服务端和本地会话同步。
        openRoute("/profile/edit")
        verifyProfileColors(dark, "dark")
        // 外观卡片处于懒加载长列表，先通过真实滚动使卡片进入组合，再操作其输入框。
        compose.waitUntil(45_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("主题外观"))
        replaceColor("强调色", light.accent)
        replaceColor("卡片底色", light.surface)
        replaceColor("主文字", light.foreground)
        replaceColor("辅助文字", light.muted)
        replaceColor("磨砂颜色", light.glassTint)
        compose.onNodeWithText("保存外观设置").performScrollTo().performNativeStableClick(compose)
        var lastRead = 0L
        compose.waitUntil(45_000) {
            if (SystemClock.elapsedRealtime() - lastRead > 500) {
                lastRead = SystemClock.elapsedRealtime()
                val account = NativeProfileApi.loadAccount(requireNotNull(fixture).accessToken)
                account.appearance == light && NativeSessionStore.load(context)?.appearance == light
            } else false
        }
        fixture = requireNotNull(NativeSessionStore.load(context))
        record("real-profile-form-saved-custom-colors-to-api-and-encrypted-session")

        // 页面重建后重新从生产路由进入正文，不能只验证共享令牌在内存中被改过。
        requireNotNull(activity).recreate()
        compose.waitUntil(45_000) { NativeSessionStore.load(context)?.appearance == light }
        // Activity 重建不承诺恢复编辑子页；通过同一真实深链重新进入，验证磁盘会话中的色板。
        openRoute("/profile/edit")
        verifyProfileColors(light, "light")
        verifyEditorAndPreview(articleId, light, "light")
        verifyReader(published.slug, light, "light")
        val persisted = NativeArticleApi.getMyArticle(requireNotNull(fixture).accessToken, articleId)
        assertEquals(published.content, persisted.content)
        assertFalse(persisted.content.contains("--article-foreground"))
        assertEquals(light, NativeProfileApi.loadAccount(requireNotNull(fixture).accessToken).appearance)
        record("activity-recreation-and-all-three-real-surfaces-preserve-server-body-and-account-config")
    }

    /** 将 value 对应的表单颜色填入 label 字段，通过实际可见输入语义操作。 */
    private fun replaceColor(label: String, value: String) {
        // 外观卡片位于真实长页面，滚到字段后输入，不直接改组件状态。
        val field = compose.onNode(hasSetTextAction() and hasText(label))
        compose.waitUntil(45_000) { compose.onAllNodes(hasSetTextAction() and hasText(label)).fetchSemanticsNodes().isNotEmpty() }
        field.performScrollTo().performTextReplacement(value)
    }

    /** 从 id 打开完整编辑页和预览窗口，检查 appearance 并保存 prefix 区分的证据。 */
    private fun verifyEditorAndPreview(id: Int, appearance: PreviewAppearance, prefix: String) {
        // 编辑器由实际文章读取加载，预览通过真实图标打开，不直接挂载组件。
        openRoute("/articles/edit/$id")
        val editor = waitWeb("!!document.getElementById('editor') && document.getElementById('editor').textContent.indexOf('$marker') >= 0")
        assertAppearance(editor, appearance)
        assertEquals("12px", stringResult(editor, "getComputedStyle(document.querySelector('#editor input[type=checkbox]')).width"))
        capture("$prefix-editor", editor)
        record("$prefix-full-editor-uses-real-account-colors-and-12px-task")
        compose.onNodeWithContentDescription("预览").performClick()
        val preview = waitWeb("!!document.querySelector('h1') && document.body.textContent.indexOf('$marker') >= 0 && !document.getElementById('editor')")
        assertAppearance(preview, appearance)
        assertNativeTextColor("文章预览", appearance.foreground)
        assertEquals("1", evaluate(preview, "document.querySelectorAll('li > label > input').length"))
        assertOnScreen(preview)
        capture("$prefix-preview", preview)
        record("$prefix-real-preview-dialog-uses-account-colors-within-screen")
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("关闭预览").fetchSemanticsNodes().isEmpty() }
    }

    /** 打开 slug 对应的真实私有文章并核对正文及原生标题，不用写作预览代替阅读验收。 */
    private fun verifyReader(slug: String, appearance: PreviewAppearance, prefix: String) {
        // 真实文章详情读取仍使用当前作者权限，读者的拒绝已在创建时独立检查。
        openRoute("/articles/$slug")
        val readerWeb = waitWeb("document.body.textContent.indexOf('$marker') >= 0 && !document.getElementById('editor') && !document.querySelector('h1')")
        assertAppearance(readerWeb, appearance)
        // 原生作者、摘要与评论标题分别覆盖卡片内和卡片外的默认内容色继承路径。
        assertNativeTextColor("Appearance ${marker.takeLast(8)}", appearance.foreground)
        assertNativeTextColor("Retained full-page appearance verification", appearance.foreground)
        assertNativeTextColor("评论与回复", appearance.foreground)
        assertEquals("1", evaluate(readerWeb, "document.querySelectorAll('li[data-type=taskItem]').length"))
        capture("$prefix-reader", readerWeb)
        record("$prefix-real-private-article-reader-uses-account-colors-and-retains-task")
    }

    /** 在真实个人资料页检查 appearance 的默认原生文字色，并保存 prefix 区分的整页截图。 */
    private fun verifyProfileColors(appearance: PreviewAppearance, prefix: String) {
        // 不滚动到正文替代原生验证，首屏作者和卡片标题必须直接继承账号主文字颜色。
        val nickname = "Appearance ${marker.takeLast(8)}"
        compose.waitUntil(45_000) { compose.onAllNodesWithText("编辑个人资料").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        // Activity 重建可能恢复表单的旧滚动位置；实际滚回首屏后核对展示文字，排除同值输入框。
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitUntil(45_000) { compose.onAllNodes(hasText(nickname) and !hasSetTextAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        assertNativeTextColor(nickname, appearance.foreground)
        assertNativeTextColor("个人资料", appearance.foreground)
        captureScreen("$prefix-profile")
        record("$prefix-real-profile-native-titles-follow-account-foreground")
    }

    /** 读取实际 text 的 TextLayoutResult，核对继承后的原生文字色而不是只比较主题令牌。 */
    private fun assertNativeTextColor(text: String, expectedHex: String) {
        // 可点击作者行会合并头像与名称语义；只读取独立文字节点，避免误取头像显式强调色。
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text) and !hasSetTextAction(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$text layout count", 1, layouts.size)
        assertEquals(text, android.graphics.Color.parseColor(expectedHex), layouts.single().layoutInput.style.color.toArgb())
    }

    /** 用 appearance 的服务端配置核对 view 的实际 CSS 和正文横向边界。 */
    private fun assertAppearance(view: WebView, appearance: PreviewAppearance) {
        // 读取计算样式而不是比较模板字符串，确保真正挂载的文档已应用配置。
        compose.waitUntil(30_000) { stringResult(view, "getComputedStyle(document.body).color") == rgb(appearance.foreground) }
        assertEquals(rgb(appearance.accent), stringResult(view, "getComputedStyle(document.querySelector('a')).color"))
        assertEquals(if (appearance == dark) "dark" else "light", stringResult(view, "getComputedStyle(document.documentElement).colorScheme"))
        assertEquals("true", evaluate(view, "Math.ceil(document.documentElement.scrollWidth) <= Math.ceil(innerWidth)"))
        assertEquals(appearance, requireNotNull(NativeSessionStore.load(context)).appearance)
    }

    /** 将合法 hex 颜色转换为浏览器计算样式使用的 RGB 字符串。 */
    private fun rgb(hex: String): String {
        // 来源为本测试定义的固定六位十六进制颜色，不解析用户任意 CSS。
        val color = hex.removePrefix("#").toLong(16)
        return "rgb(${color shr 16 and 255}, ${color shr 8 and 255}, ${color and 255})"
    }

    /** 用原读者身份读取 slug，返回服务端真实状态码，网络错误不能冒充权限拒绝。 */
    private fun otherReaderStatus(slug: String): Int {
        // 精确本地来源已在准备阶段校验；仅查询本轮私有文章，只读取权限错误而不读取成功正文。
        val encoded = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
        val connection = URL(BuildConfig.API_BASE_URL + "/articles/visible/$encoded").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Authorization", "Bearer ${requireNotNull(reader).accessToken}")
            // 私有文章对已登录非作者按当前服务端契约返回 403，必须同时核对阅读权限错误而非任意失败。
            val status = connection.responseCode
            report.put("otherReaderHttpStatus", status)
            if (status == 403) {
                val error = JSONObject(requireNotNull(connection.errorStream).bufferedReader().use { it.readText() })
                assertEquals("当前账号没有阅读这篇文章的权限。", error.getString("message"))
            }
            status
        } finally {
            connection.disconnect()
        }
    }

    /** 根据 predicate 找到本应用当前真实窗口中的 WebView，超时表示页面尚未就绪。 */
    private fun waitWeb(predicate: String): WebView {
        // 预览使用独立 Dialog 窗口，需要扫描本应用附着视图而非只看 Activity 根节点。
        var selected: WebView? = null
        compose.waitUntil(60_000) {
            val views = mutableListOf<WebView>()
            instrumentation.runOnMainSync { WindowInspector.getGlobalWindowViews().forEach { collectWebViews(it, views) } }
            selected = views.firstOrNull { evaluate(it, predicate) == "true" }
            selected != null
        }
        return requireNotNull(selected)
    }

    /** 将 root 内现有 WebView 加入 target，不创建视图或访问其他应用。 */
    private fun collectWebViews(root: View, target: MutableList<WebView>) {
        // 仅遍历实际窗口后代，正文组件的假服务或替代宿主不能成为候选。
        if (root is WebView) target.add(root)
        else if (root is ViewGroup) for (index in 0 until root.childCount) collectWebViews(root.getChildAt(index), target)
    }

    /** 执行 view 的只读 script，返回 evaluateJavascript 原始 JSON 回包。 */
    private fun evaluate(view: WebView, script: String): String {
        // 主线程只发起读取，等待留在测试线程，不阻塞 WebView 回调。
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result = it; done.countDown() } }
        check(done.await(15, TimeUnit.SECONDS)) { "Appearance read timed out" }
        return result
    }

    /** 读取 view 的 script 字符串结果，不使用手工删引号处理转义。 */
    private fun stringResult(view: WebView, script: String): String {
        // JSON 数组包装复用标准解析器，空值必须导致断言失败而不是变成空字符串。
        return JSONArray("[${evaluate(view, script)}]").getString(0)
    }

    /** 验证 view 的完整预览区域位于当前应用的可见窗口内。 */
    private fun assertOnScreen(view: WebView) {
        // 使用实际屏幕坐标，不把网页缩放后的 CSS 宽度当成弹窗高度证据。
        instrumentation.runOnMainSync {
            val visible = Rect()
            assertTrue(view.getLocalVisibleRect(visible))
            assertEquals(Rect(0, 0, view.width, view.height), visible)
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            assertTrue(frame.contains(Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)))
        }
    }

    /** 用真实深链打开 route；上一页关闭后才创建新页面，保留当前加密会话。 */
    private fun openRoute(route: String) {
        // 路由交由生产 MainActivity 消费，不直接调用内部页面回调。
        closePage()
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + route)))
    }

    /** 关闭当前 Activity 并清理测试引用，不结束应用会话。 */
    private fun closePage() {
        // 先让生产页面正常销毁，以取消页面内请求而非强杀应用掩盖状态问题。
        activity?.close()
        activity = null
    }

    /** 等待 tag 对应的真实页面节点，不把网络就绪当成界面就绪。 */
    private fun waitTag(tag: String) {
        // 页面切换允许短暂无根状态，但有界超时会明确失败。
        compose.waitUntil(45_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待 view 提交画面后保存 name 对应的屏幕和计算样式，不保存任何会话凭据。 */
    private fun capture(name: String, view: WebView) {
        // WebView 脚本回包早于视觉帧，必须确认浏览器已提交后才截屏。
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                /** requestId 对应当前截图的浏览器帧；回调仅通知测试继续。 */
                override fun onComplete(requestId: Long) {
                    // 不替换正文或设置焦点，避免截图流程改变待验证页面。
                    view.invalidate()
                    done.countDown()
                }
            })
        }
        check(done.await(15, TimeUnit.SECONDS))
        // 原生画面与正文 CSS 分别留证；截图必须等待实际合成稳定，不能只依赖脚本回包。
        captureScreen(name)
        File(context.filesDir, "$marker-$name.json").writeText(evaluate(view,
            "({foreground:getComputedStyle(document.body).color,accent:getComputedStyle(document.querySelector('a')).color,root:document.documentElement.getAttribute('style'),width:innerWidth,scrollWidth:document.documentElement.scrollWidth})"))
    }

    /** 等待当前原生窗口像素稳定后保存 name 截图，支持无 WebView 的真实个人资料页。 */
    private fun captureScreen(name: String) {
        // 系统窗口动画不受 Compose 时钟控制，连续稳定帧才可作为颜色与布局的人工验证证据。
        assertNoSystemOverlay()
        compose.waitForIdle()
        var previous: Bitmap? = null
        var lastCapture = 0L
        var stableSince = SystemClock.uptimeMillis()
        try {
            // 独立窗口有系统动画，合成像素连续稳定后才记录屏幕，不能只依赖 Compose 空闲。
            compose.waitUntil(8_000) {
                val now = SystemClock.uptimeMillis()
                if (now - lastCapture < 100) return@waitUntil false
                lastCapture = now
                val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val unchanged = previous?.sameAs(image) == true
                previous?.recycle()
                previous = image
                if (!unchanged) stableSince = now
                unchanged && now - stableSince >= 250
            }
            // 组件语义可能在系统无响应遮罩后继续执行，不能把被遮挡的画面记为视觉通过。
            assertNoSystemOverlay()
            File(context.filesDir, "$marker-$name.png").outputStream().use {
                check(requireNotNull(previous).compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            previous?.recycle()
        }
    }

    /** 拒绝系统无响应、崩溃或其它应用覆盖当前窗口，不自动关闭弹层掩盖环境故障。 */
    private fun assertNoSystemOverlay() {
        // 同时读取当前前台包与系统弹层文字，不能仅依赖应用内部 Compose 空闲状态。
        val device = UiDevice.getInstance(instrumentation)
        assertFalse("System ANR overlays the application", device.hasObject(By.textContains("isn't responding")))
        assertFalse("System crash overlays the application", device.hasObject(By.textContains("keeps stopping")))
        assertEquals("Screenshot must belong to the foreground application", context.packageName, device.currentPackageName)
    }

    /** 从 result 中取得已认证会话，其余结果必须失败而不是绕过认证步骤。 */
    private fun authenticated(result: NativeAuthResult): NativeSession {
        // 本地既有策略已在准备阶段核对，出现新挑战时必须按失败保留证据。
        assertTrue(result is NativeAuthResult.Authenticated)
        return (result as NativeAuthResult.Authenticated).session
    }

    /** 将已经通过的 check 追加到报告，不预填后续成功状态。 */
    private fun record(check: String) {
        // 失败时报告仍只包含实际完成的检查点。
        checks.put(JSONObject().put("check", check).put("passed", true))
        writeReport()
    }

    /** 保存本轮非敏感进度，账号和文章保留用于人工回查。 */
    private fun writeReport() {
        // 报告不包含密码、令牌或加密会话正文。
        report.put("checks", checks)
        File(context.filesDir, "$marker-report.json").writeText(report.toString(2))
    }
}
