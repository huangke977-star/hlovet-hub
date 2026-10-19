package xyz.hlovet.portal.prototype

import androidx.compose.ui.graphics.Color
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** 核对正文共享样式的数值编码、范围和首次加载，设备测试另行证明实时更新不重载正文。 */
class NativeArticleWebAppearanceTest {
    /** 颜色编码保留透明度且不受系统小数分隔符影响。 */
    @Test fun cssColorsUseInvariantDecimalSeparator() {
        // 暂时使用逗号小数地区，finally 恢复，避免影响同进程其它用例。
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("rgba(255, 0, 128, 0.502)", nativeArticleCssColor(Color(1f, 0f, 128f / 255, .5f)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** 浅暗模式取决于当前表面颜色，文字和弹层分别采用共享配置的颜色及透明度。 */
    @Test fun surfaceDeterminesSchemeWithoutLosingGlassOpacity() {
        // 用独立快照覆盖颜色和透明度，不修改全局账号外观。
        val base = nativeArticleWebAppearance()
        assertEquals("dark", base.copy(surface = Color(0xFF161B22)).properties()["color-scheme"])
        assertEquals("light", base.copy(surface = Color.White).properties()["color-scheme"])
        assertEquals("rgba(255, 255, 255, 0.000)", base.copy(glassTint = Color.White.copy(alpha = 0f)).properties()["--article-tint"])
        assertEquals(nativeArticleCssColor(base.overlay), base.properties()["--article-overlay"])
    }

    /** 编辑、阅读首屏均初始化传入的账号颜色，不等待异步桥接才修正浅色闪烁。 */
    @Test fun allDocumentsInitializeSameAppearanceOutsideBody() {
        // 使用不同于默认值的颜色验证实参，不以变量名存在代替主题实际生效。
        val appearance = nativeArticleWebAppearance().copy(foreground = Color(0xFFDFE8EC), surface = Color(0xFF171D25))
        val documents = listOf(buildNativeRichEditorHtml("<p>body</p>", appearance = appearance), buildNativeArticleBodyHtml("<p>body</p>", "html", appearance), buildPreviewHtml("Title", "", "Summary", "<p>body</p>", "html", appearance))
        documents.forEach { document ->
            assertTrue(document.substringBefore("</head>").contains(appearance.stylesheet()))
            assertFalse(document.substringAfter("<body>").substringBefore("<script>").contains("--article-foreground"))
        }
    }
}
