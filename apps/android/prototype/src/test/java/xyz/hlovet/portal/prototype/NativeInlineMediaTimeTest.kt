package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

/** 核对播放器的进度文案边界，不把格式测试当作真实解码验收。 */
class NativeInlineMediaTimeTest {
    /** 未知或负进度按零显示，避免网络失败时出现负时间。 */
    @Test fun zeroAndNegativeProgress() {
        // 非法进度不改变媒体状态，只归一化显示。
        assertEquals("0:00", nativeInlineMediaTime(0))
        assertEquals("0:00", nativeInlineMediaTime(-1000))
    }

    /** 秒数补齐两位，毫秒不向上取整。 */
    @Test fun secondsArePaddedAndMillisecondsTruncated() {
        // 短音频的进度保持稳定，最后不足一秒不提前显示下一秒。
        assertEquals("0:09", nativeInlineMediaTime(9999))
        assertEquals("1:05", nativeInlineMediaTime(65000))
    }

    /** 长音视频保留累计分钟，不在一小时后回到零。 */
    @Test fun longMediaRetainsTotalMinutes() {
        // 阅读长录像时分秒值仍与原生播放器位置一致。
        assertEquals("61:01", nativeInlineMediaTime(3661000))
    }
}
