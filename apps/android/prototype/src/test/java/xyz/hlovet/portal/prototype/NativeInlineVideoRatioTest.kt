package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

/** 核对视频真实画幅和未就绪占位边界，画面是否裁切仍由设备像素检查验收。 */
class NativeInlineVideoRatioTest {
    /** 横屏按实际宽高返回比例，不把所有视频固定为同一尺寸。 */
    @Test fun landscapeUsesActualDimensions() {
        // 宽屏与方形媒体分别保持自身画幅。
        assertEquals(16f / 9f, nativeInlineVideoRatio(1920, 1080), .001f)
        assertEquals(1f, nativeInlineVideoRatio(512, 512), .001f)
    }

    /** 竖屏比例保留，供窗口在可用空间内等比留边。 */
    @Test fun portraitUsesActualDimensions() {
        // 手机录像不能继续套用横屏比例而拉伸或裁掉上下内容。
        assertEquals(2f / 3f, nativeInlineVideoRatio(160, 240), .001f)
    }

    /** 未读取或异常宽高仅影响占位，不产生除零或负尺寸。 */
    @Test fun unknownDimensionsUseLandscapePlaceholder() {
        // 读取阶段尚无可靠元数据时使用稳定的默认画幅。
        assertEquals(16f / 9f, nativeInlineVideoRatio(0, 240), .001f)
        assertEquals(16f / 9f, nativeInlineVideoRatio(160, 0), .001f)
        assertEquals(16f / 9f, nativeInlineVideoRatio(-1, 240), .001f)
    }
}
