package xyz.hlovet.portal.prototype

import org.junit.Assert.*
import org.junit.Test

/** 核对公共外观合并的范围及空背景语义，不把首页数据丢弃当作背景刷新。 */
class NativeSiteAppearanceTest {
    private val original = PreviewAppearance("cloud-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 0)

    /** 外观刷新保留 data 的列表引用和顺序，不修改原快照。 */
    @Test fun replacesOnlyAppearanceAndBackground() {
        // 引用一致性确保不会为外观变化重新创建内容列表或重置分页来源。
        val data = PreviewData(emptyList(), emptyList(), emptyList(), original, "https://example.invalid/old.png")
        val next = original.copy(themeId = "night-purple", cardAlpha = 57)
        val merged = NativeSiteAppearance(next, "https://example.invalid/new.png").applyTo(data)
        assertSame(data.articles, merged.articles)
        assertSame(data.topics, merged.topics)
        assertSame(data.collections, merged.collections)
        assertEquals(next, merged.appearance)
        assertEquals("https://example.invalid/new.png", merged.backgroundUrl)
        assertEquals("https://example.invalid/old.png", data.backgroundUrl)
        assertEquals(original, data.appearance)
    }

    /** 已确认不存在背景时必须清除旧 URL，不能把 null 当作保留旧值。 */
    @Test fun nullBackgroundRemovesThePreviousImage() {
        // 无活动图片且默认配置为空是有效状态，应使用客户端内置背景。
        val data = PreviewData(emptyList(), emptyList(), emptyList(), original, "https://example.invalid/old.png")
        assertNull(NativeSiteAppearance(original, null).applyTo(data).backgroundUrl)
    }
}
