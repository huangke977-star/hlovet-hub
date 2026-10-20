package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用实际主题、卡片和弹窗验证原生内容色继承，不用正文 WebView 的颜色代替原生组件。 */
@RunWith(AndroidJUnit4::class)
class NativeThemeContentColorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private lateinit var previous: PreviewAppearance
    private var previousBackground = Color.White
    private var previousSecondary = Color.White
    private val dark = PreviewAppearance("custom", "#76D2E1", "#E5EDF2", "#A8BAC4", "#171E27", 68, 12, "#203A46", 30)
    private val light = PreviewAppearance("custom", "#216F49", "#253B32", "#536C60", "#F3FAF6", 68, 12, "#C7E9D5", 30)
    private val prefix = InstrumentationRegistry.getArguments().getString("evidencePrefix").orEmpty()
        .takeIf { it.matches(Regex("[a-z0-9-]{1,40}")) } ?: "current"

    /** 保存当前进程的全部账号外观令牌，不读取或修改真实会话和服务端配置。 */
    @Before fun rememberAppearance() {
        // 按账号字段保存透明度，并额外保留由 themeId 派生的两项颜色。
        compose.runOnUiThread {
            previous = PreviewAppearance("custom", hex(HlovetUi.accent), hex(HlovetUi.foreground), hex(HlovetUi.muted), hex(HlovetUi.surface),
                (HlovetUi.glassFallback.alpha * 100).roundToInt(), HlovetUi.glassBlur, hex(HlovetUi.glassTint), (HlovetUi.glassTint.alpha * 100).roundToInt())
            previousBackground = HlovetUi.background
            previousSecondary = HlovetUi.secondaryAccent
        }
    }

    /** 卸载当前真实组件并恢复原令牌，不把隔离测试的暗色配置留给后续页面。 */
    @After fun restoreAppearance() {
        // 先停止组合，再恢复共享状态，避免旧弹层跨用例更新。
        host.dispose()
        compose.runOnUiThread {
            HlovetUi.applyAppearance(previous)
            HlovetUi.background = previousBackground
            HlovetUi.secondaryAccent = previousSecondary
        }
    }

    /** 默认根文字和无显式 tint 图标应随账号从暗色切到浅色，不依赖外层 Surface。 */
    @Test fun rootTextAndIconFollowAppearanceWithoutSurface() {
        // 使用真实主题，但故意不放 Material Surface，复现主页面与裸布局的继承路径。
        compose.runOnUiThread { HlovetUi.applyAppearance(dark) }
        host.setContent {
            HlovetTheme {
                Column(Modifier.fillMaxSize().background(HlovetUi.surface).testTag("native-content-fixture")) {
                    Text("Root content", Modifier.testTag("native-content-root"))
                    Icon(Icons.Filled.Star, null, Modifier.size(32.dp).testTag("native-content-root-icon"))
                }
            }
        }
        capture("root-dark", "native-content-fixture")
        assertTextColor("native-content-root", dark.foreground)
        assertIconColor("native-content-root-icon", dark.foreground)
        // 原组件保持挂载，验证账号色板更新触发重组，而不是靠重建页面掩盖陈旧默认值。
        compose.runOnIdle { HlovetUi.applyAppearance(light) }
        assertTextColor("native-content-root", light.foreground)
        assertIconColor("native-content-root-icon", light.foreground)
        capture("root-light", "native-content-fixture")
    }

    /** 半透明卡片采用账号前景色，但显式指定的辅助、强调和错误色不能被默认值覆盖。 */
    @Test fun glassCardOwnsDefaultColorButPreservesExplicitOverrides() {
        // 故意在卡片外设置错误的继承色，验证卡片本身有稳定的默认内容色。
        compose.runOnUiThread { HlovetUi.applyAppearance(dark) }
        host.setContent {
            HlovetTheme {
                Column(Modifier.fillMaxSize().background(HlovetUi.surface).testTag("native-content-fixture")) {
                    CompositionLocalProvider(LocalContentColor provides Color.Yellow) {
                        GlassCard(Modifier.padding(16.dp)) {
                            Text("Card content", Modifier.testTag("native-content-card"))
                            Icon(Icons.Filled.Star, null, Modifier.size(32.dp).testTag("native-content-card-icon"))
                            Text("Secondary content", Modifier.testTag("native-content-muted"), color = HlovetUi.muted)
                            Text("Accent content", Modifier.testTag("native-content-accent"), color = HlovetUi.accent)
                            Text("Explicit error", Modifier.testTag("native-content-error"), color = Color.Magenta)
                        }
                    }
                }
            }
        }
        capture("card-dark", "native-content-fixture")
        for (appearance in listOf(dark, light)) {
            // 每次读取实际文字布局和已绘制图标，不以 Material 色板字段等于配置作为替代。
            compose.runOnIdle { HlovetUi.applyAppearance(appearance) }
            assertTextColor("native-content-card", appearance.foreground)
            assertIconColor("native-content-card-icon", appearance.foreground)
            assertTextColor("native-content-muted", appearance.muted)
            assertTextColor("native-content-accent", appearance.accent)
            assertTextColor("native-content-error", "#FF00FF")
        }
        capture("card-light", "native-content-fixture")
    }

    /** 统一警告弹窗仍读取账号主文字色，主题更新不破坏弹窗已明确配置的颜色。 */
    @Test fun sharedDialogRetainsItsExplicitAccountColors() {
        // 弹窗使用真实独立窗口，不将卡片的通过结果代替弹层验证。
        compose.runOnUiThread { HlovetUi.applyAppearance(dark) }
        host.setContent {
            HlovetTheme {
                HlovetAlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    modifier = Modifier.testTag("native-content-dialog"),
                    title = { Text("Dialog title", Modifier.testTag("native-content-dialog-title")) },
                    text = { Text("Dialog body", Modifier.testTag("native-content-dialog-body")) },
                )
            }
        }
        for (appearance in listOf(dark, light)) {
            // 保持同一弹窗实例，确认共享主题变化不会留下旧文字色。
            compose.runOnIdle { HlovetUi.applyAppearance(appearance) }
            assertTextColor("native-content-dialog-title", appearance.foreground)
            assertTextColor("native-content-dialog-body", appearance.foreground)
        }
        capture("dialog-light", "native-content-dialog")
    }

    /** 从 tag 对应 Text 的真实布局读取最终颜色，与服务端 hex 配置比较。 */
    private fun assertTextColor(tag: String, hex: String) {
        // GetTextLayoutResult 包含 Text 合并继承值后的样式，能暴露默认黑字而非只看主题令牌。
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$tag layout count", 1, layouts.size)
        assertEquals(tag, android.graphics.Color.parseColor(hex), layouts.single().layoutInput.style.color.toArgb())
    }

    /** 从 tag 对应图标的实际像素确认默认 tint 已生效，允许边缘抗锯齿。 */
    private fun assertIconColor(tag: String, hex: String) {
        // 只统计接近不透明目标色的内部像素，背景色和边缘混色不能满足断言。
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val expected = android.graphics.Color.parseColor(hex)
        var matched = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) >= 245 &&
                abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(expected)) <= 3 &&
                abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(expected)) <= 3 &&
                abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(expected)) <= 3) matched++
        }
        assertTrue("$tag expected tint pixels=$matched", matched > image.width * image.height / 20)
    }

    /** 将 tag 的真实绘制内容保存为 name 截图，仅用于本机核对，不包含账号凭据。 */
    private fun capture(name: String, tag: String) {
        // 使用 Compose 的真实绘制截图，避开系统时钟和窗口动画对颜色判读的影响。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(context.filesDir, "native_content_$prefix-$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    /** 将 color 编码为账号配置使用的六位颜色，不混入独立透明度字段。 */
    private fun hex(color: Color): String {
        // 仅在保存原进程令牌时使用，不解析任意 CSS 或替代服务端校验。
        return "#%06X".format(color.toArgb() and 0xFFFFFF)
    }
}
