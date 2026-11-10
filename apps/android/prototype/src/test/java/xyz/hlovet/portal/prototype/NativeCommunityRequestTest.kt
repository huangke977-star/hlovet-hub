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

    /** 当前公告、请求代际和账号均一致时，公告详情及其附属操作允许写回。 */
    @Test
    fun currentAnnouncementDetailCanUpdate() {
        assertTrue(isCurrentAnnouncementDetailRequest(7, 7, 31, 31, "token-a", "token-a"))
    }

    /** 快速切换到另一条公告后，旧公告详情不能覆盖当前目标。 */
    @Test
    fun changedAnnouncementCannotUpdateDetail() {
        assertFalse(isCurrentAnnouncementDetailRequest(7, 7, 31, 32, "token-a", "token-a"))
    }

    /** 公告账号变化后，旧账号的详情、附件或阅读结果必须被丢弃。 */
    @Test
    fun changedAnnouncementAccountCannotUpdateDetail() {
        assertFalse(isCurrentAnnouncementDetailRequest(7, 7, 31, 31, "token-a", "token-b"))
    }
}
