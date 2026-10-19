package xyz.hlovet.portal.prototype

import android.webkit.WebView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

/** 保存正文 WebView 的共享外观快照；只生成根节点样式，不接触正文、选区或编辑历史。 */
internal data class NativeArticleWebAppearance(
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val surface: Color,
    val glassSurface: Color,
    val glassTint: Color,
    val overlay: Color,
    val outline: Color,
    val accentSoft: Color,
) {
    /** 返回受控 CSS 属性映射；颜色来自共享令牌，模糊交由原生玻璃宿主，避免旧 WebView 重复合成。 */
    fun properties(): Map<String, String> {
        // 不接受任意 CSS 字符串，避免账号配置经过页面插值变成可执行内容。
        return linkedMapOf(
            "--article-foreground" to nativeArticleCssColor(foreground),
            "--article-muted" to nativeArticleCssColor(muted),
            "--article-accent" to nativeArticleCssColor(accent),
            "--article-surface" to nativeArticleCssColor(surface),
            "--article-glass" to nativeArticleCssColor(glassSurface),
            "--article-tint" to nativeArticleCssColor(glassTint),
            "--article-overlay" to nativeArticleCssColor(overlay),
            "--article-outline" to nativeArticleCssColor(outline),
            "--article-accent-soft" to nativeArticleCssColor(accentSoft),
            "color-scheme" to if (surface.luminance() < .5f) "dark" else "light",
        )
    }

    /** 生成首次加载的根节点 CSS，避免加载完成前闪现固定浅色正文。 */
    fun stylesheet(): String {
        // 外观只属于文档根节点，正文序列化不会携带账号主题。
        return ":root { ${properties().entries.joinToString(" ") { "${it.key}: ${it.value};" }} }"
    }

    /** 生成在线更新脚本；页面未就绪时不写入，加载完成回调会重新应用最新快照。 */
    fun updateScript(): String {
        // JSON 编码仅传递已限定的样式值，不执行正文或重新加载文档。
        return "window.applyNativeArticleAppearance && window.applyNativeArticleAppearance(${JSONObject(properties())});"
    }
}

/** 从当前账号的共享令牌读取外观；在 Compose 中读取这些状态后，配置变化会触发局部更新。 */
internal fun nativeArticleWebAppearance(): NativeArticleWebAppearance {
    // 与原生输入框、弹窗共用玻璃底色和最低不透明度，不另建一套浅色调色板。
    return NativeArticleWebAppearance(HlovetUi.foreground, HlovetUi.muted, HlovetUi.accent, HlovetUi.surface,
        HlovetUi.glassFallback, HlovetUi.glassTint, HlovetUi.menuSurface, HlovetUi.outline, HlovetUi.accentSoft)
}

/** 将 color 转成与系统语言无关的 CSS RGBA，保留玻璃透明度。 */
internal fun nativeArticleCssColor(color: Color): String {
    // CSS 小数必须始终使用句点，不能受中文、德文等系统区域格式影响。
    return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.3f)",
        (color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt(), color.alpha)
}

/** 只把 appearance 写入现有页面根节点；调用方负责在主线程和页面就绪后应用。 */
internal fun WebView.applyNativeArticleAppearance(appearance: NativeArticleWebAppearance) {
    // 不调用正文替换或 loadData，切换主题不能制造撤销步骤或重置滚动位置。
    evaluateJavascript(appearance.updateScript(), null)
}

/** 编辑和阅读共用的本地样式桥，只修改受控根属性，不绑定输入、焦点或历史事件。 */
internal val nativeArticleAppearanceBridgeScript = """
    // 外观更新仅修改根样式，正文 DOM 和用户选区保持原样。
    window.applyNativeArticleAppearance = function(properties) {
      Object.keys(properties).forEach(function(name) {
        document.documentElement.style.setProperty(name, properties[name]);
      });
    };
""".trimIndent()
