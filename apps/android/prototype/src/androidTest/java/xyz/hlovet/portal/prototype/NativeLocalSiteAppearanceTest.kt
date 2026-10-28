package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** 经真实 MainActivity、本地 API 和数据库验收站点外观；恢复配置，保留背景夹具与验收记录。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalSiteAppearanceTest {
    @get:Rule val network = NativeLoopbackNetworkRule()
    @get:Rule val compose = createEmptyComposeRule()
    /** 返回当前仪器运行器，避免使用其它应用或测试包的上下文。 */
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    /** 返回目标应用上下文，会话和证据按目标包及 API 来源隔离。 */
    private val context get() = instrumentation.targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var previousSession: NativeSession? = null
    private var previousLocale: String? = null
    private var previousStoredLocale: String? = null
    private var originalSettings: NativeAdminSiteSettings? = null
    private var originalBackgroundId: Int? = null
    private var root: NativeSession? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("apiOrigin", BuildConfig.API_BASE_URL)
        .put("superAdminInteractiveLoginTested", false).put("authorization", "short-lived-local-container-signed-jwt")
        .put("testDataRemoved", false).put("completed", false).put("settingsRestored", false)

    /** 只接受本地来源和真实超级管理员短期授权，备份原配置与来源隔离会话。 */
    @Before fun prepare() {
        // 保护必须先于读凭据；该测试不能在生产或隔离 HTTP 夹具上运行。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val file = File(context.filesDir, "native-local-site-appearance-token.json")
        val token = try { JSONObject(file.readText()).getString("accessToken") } finally { file.delete() }
        val profile = NativeProfileApi.loadAccount(token)
        val public = NativeProfileApi.loadPublic(token, profile.username)
        check(public.id == 7 && profile.username == "android_parity_admin" && public.isSuperAdmin)
        previousSession = NativeSessionStore.load(context)
        previousLocale = NativeI18n.locale
        previousStoredLocale = context.getSharedPreferences("native-i18n", android.content.Context.MODE_PRIVATE).getString("locale", null)
        originalSettings = NativeAdminSettingsApi.get(token)
        originalBackgroundId = NativeAdminSettingsApi.listBackgrounds(token).firstOrNull { it.isActive }?.id
        root = NativeSession(token, "", public.id, profile.username, profile.nickname, profile.appearance,
            email = profile.email, locale = "zh-CN", isSuperAdmin = true, isAdministrator = true)
        report.put("superAdminId", public.id).put("originalBackgroundId", originalBackgroundId)
        check(NativeSessionStore.save(context, requireNotNull(root)))
        instrumentation.runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 无论断言是否成功都恢复原站点配置、活动背景和会话；恢复失败必须保留失败结果。 */
    @After fun restore() {
        // 先停宿主再还原服务端，防止旧请求重写恢复后的配置。
        try { activity?.close(); activity = null } finally {
            try {
                val token = root?.accessToken
                if (token != null && originalSettings != null) {
                    val restored = NativeAdminSettingsApi.update(token, requireNotNull(originalSettings))
                    if (originalBackgroundId != null) NativeAdminSettingsApi.activateBackground(token, requireNotNull(originalBackgroundId))
                    else NativeAdminSettingsApi.clearActiveBackground(token)
                    assertEquals(requireNotNull(originalSettings).copy(updatedAt = restored.updatedAt), restored)
                    assertEquals(originalBackgroundId, NativeAdminSettingsApi.listBackgrounds(token).firstOrNull { it.isActive }?.id)
                    report.put("settingsRestored", true)
                }
            } finally {
                if (previousLocale != null) {
                    previousSession?.let { check(NativeSessionStore.save(context, it)) } ?: NativeSessionStore.clear(context)
                    // 宿主会写入语言偏好，需连同原先没有偏好的情况一起恢复。
                    val preferences = context.getSharedPreferences("native-i18n", android.content.Context.MODE_PRIVATE).edit()
                    if (previousStoredLocale == null) preferences.remove("locale") else preferences.putString("locale", previousStoredLocale)
                    check(preferences.commit())
                    instrumentation.runOnMainSync { NativeI18n.applyLocale(requireNotNull(previousLocale)) }
                }
                val directory = File(context.filesDir, "native-local-site-appearance-evidence").apply { mkdirs() }
                File(directory, "report.json").writeText(report.put("checks", checks).toString(2))
            }
        }
    }

    /** 真实保存全局配色及启用背景后立即更新宿主，草稿不丢失且重开仍读取数据库最终值。 */
    @Test fun realSettingsAndBackgroundWritesSynchronizeWithoutDiscardingDraft() {
        // 创建独立背景文件并保留，避免操作已有上传资源或伪造媒体读取。
        val session = requireNotNull(root)
        val marker = "native_site_${UUID.randomUUID().toString().take(8)}"
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes = ByteArrayOutputStream()
        try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
        val uploaded = NativeAdminSettingsApi.uploadBackgrounds(session.accessToken,
            listOf(NativeUploadFile("$marker.png", "image/png", bytes.toByteArray()))).single()
        report.put("backgroundId", uploaded.id).put("marker", marker)
        record("background-uploaded-through-real-api-and-retained")

        // 保存使用页面的真实表单与按钮，服务端回读和宿主缓存分别断言。
        openSettings()
        compose.waitUntil(30_000) { NativeOfflineCache.loadPreview(context, "user:7")?.appearance?.themeId == requireNotNull(originalSettings).defaultTheme.themeId }
        val listId = list().fetchSemanticsNode().id
        find(hasTestTag("site-default-theme")).performClick()
        val theme = if (requireNotNull(originalSettings).defaultTheme.themeId == "night-purple") "cloud-blue" else "night-purple"
        compose.onNode(hasText(if (theme == "night-purple") "夜紫灰蓝" else "浅云蓝白") and hasAnyAncestor(isPopup())).performClick()
        field("站点名称").performTextReplacement(marker)
        waitForKeyboard()
        find(hasText("保存全部设置") and hasClickAction()).performNativeStableClick(compose)
        compose.waitUntil(30_000) { NativeOfflineCache.loadPreview(context, "user:7")?.appearance?.themeId == theme }
        val persisted = NativeAdminSettingsApi.get(session.accessToken)
        assertEquals(marker, persisted.siteName)
        assertEquals(theme, persisted.defaultTheme.themeId)
        assertEquals(listId, list().fetchSemanticsNode().id)
        assertEquals(session.appearance.accent.toColor(), compose.runOnIdle { HlovetUi.accent })
        record("real-ui-save-persists-to-database-and-updates-host-cache-without-overriding-personal-theme")

        // 额外输入不保存，然后启用新图片；全局同步不允许重载并清空该草稿。
        field("站点名称").performTextReplacement("$marker unsaved")
        find(hasText("使用") and hasClickAction() and hasAnyAncestor(hasText(uploaded.originalName)))
            .performNativeStableClick(compose)
        val expectedUrl = NativeAdminSettingsApi.resolveUrl(uploaded.url)
        compose.waitUntil(30_000) { NativeOfflineCache.loadPreview(context, "user:7")?.backgroundUrl == expectedUrl }
        compose.waitUntil(15_000) {
            val screen = instrumentation.uiAutomation.takeScreenshot()
            try { android.graphics.Color.blue(screen.getPixel(3, screen.height / 2)) - android.graphics.Color.red(screen.getPixel(3, screen.height / 2)) > 80 }
            finally { screen.recycle() }
        }
        assertEquals(uploaded.id, NativeAdminSettingsApi.listBackgrounds(session.accessToken).single { it.isActive }.id)
        assertEquals(listId, list().fetchSemanticsNode().id)
        field("站点名称").assertTextContains("$marker unsaved")
        capture("real-active-background-with-draft")
        record("real-background-activation-updates-screen-pixels-and-preserves-unsaved-draft")

        // Activity 关闭后重开从真实接口读取已保存名称和活动背景，不把未保存内容误写进数据库。
        activity?.close(); activity = null
        openSettings()
        field("站点名称").assertTextContains(marker)
        assertFalse(field("站点名称").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.contains("unsaved"))
        compose.waitUntil(20_000) { NativeOfflineCache.loadPreview(context, "user:7")?.backgroundUrl == expectedUrl }
        capture("real-reopened-settings")
        record("activity-reopen-loads-persisted-settings-and-background-not-the-unsaved-draft")
        report.put("completed", true)
    }

    /** 经站点真实深链打开设置，等待管理员表单完成读取。 */
    private fun openSettings() {
        // 不直接挂载组件，保留宿主来源、会话恢复和页面分发逻辑。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + "/admin/settings")))
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("站点名称") and hasSetTextAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 返回宿主当前设置列表，用于稳定节点身份与真实滚动验证。 */
    private fun list(): SemanticsNodeInteraction {
        // 与其它管理页区分，不以任意可滚动节点代替设置页。
        return compose.onNodeWithTag("admin-site-settings-list")
    }

    /** 滚动并返回 matcher 唯一匹配的实际可操作节点。 */
    private fun find(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // 懒列表中的字段必须先进入组合，不能直接执行离屏事件。
        list().performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    /** 返回 label 标识的输入字段，排除卡片说明文字。 */
    private fun field(label: String): SemanticsNodeInteraction {
        // 编辑只使用真实输入行为，不赋值页面内部状态。
        return find(hasText(label) and hasSetTextAction())
    }

    /** 等待 IME 和窗口尺寸稳定，避免懒列表在键盘动画后移走保存按钮。 */
    private fun waitForKeyboard() {
        // 保持键盘显示；连续稳定超过 300 毫秒后才滚动到末尾。
        var previous = 0 to 0
        var stableSince = 0L
        compose.waitUntil(10_000) {
            var current = 0 to 0
            requireNotNull(activity).onActivity { host ->
                val view = host.window.decorView
                current = view.height to (ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (current != previous || current.second == 0) stableSince = now
            previous = current
            current.second > 0 && now - stableSince >= 300
        }
    }

    /** 将接收字符串的 RGB 颜色转换为 Compose 色值，便于断言个人主题未被全局默认覆盖。 */
    private fun String.toColor(): Color {
        // 账号颜色已由现有 API 解析器规范化，异常必须让测试失败。
        return Color(android.graphics.Color.parseColor(this))
    }

    /** 把 name 对应的成功检查点写入不含凭据的报告。 */
    private fun record(name: String) {
        // 每个检查点必须在实际断言之后记录，不预先填充通过状态。
        checks.put(JSONObject().put("name", name).put("passed", true))
    }

    /** 保存 name 对应真实整屏截图，供毛玻璃、背景和页面状态复核。 */
    private fun capture(name: String) {
        // 不保存网络请求正文、账号密码或短期管理员令牌。
        val directory = File(context.filesDir, "native-local-site-appearance-evidence").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
