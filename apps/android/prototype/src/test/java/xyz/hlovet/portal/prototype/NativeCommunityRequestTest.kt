package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证社区详情回包不会跨请求代际或账号写回。 */
class NativeCommunityRequestTest {
    /** 当前详情请求属于当前账号时允许更新弹窗。 */
    @Test
    fun currentDetailRequestCanUpdate() {
        assertTrue(isCurrentCommunityDetailRequest(5, 5, "token-a", "token-a"))
    }

    /** 关闭详情或重新打开其它记录后，旧回包必须被丢弃。 */
    @Test
    fun staleDetailRequestCannotUpdate() {
        assertFalse(isCurrentCommunityDetailRequest(4, 5, "token-a", "token-a"))
    }

    /** 账号切换后，旧账号详情不能显示在新账号页面。 */
    @Test
    fun changedAccountCannotUpdateDetail() {
        assertFalse(isCurrentCommunityDetailRequest(5, 5, "token-a", "token-b"))
    }
}
