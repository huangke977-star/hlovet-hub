package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证收件箱回包不会跨账号、会话或请求代际写入页面。 */
class NativeInboxRequestTest {
    /** 当前账号、会话和请求仍一致时允许回包更新。 */
    @Test
    fun currentInboxRequestCanUpdate() {
        val request = NativeInboxRequest(7, "account-a", "token-a")

        assertTrue(isCurrentInboxRequest(request, 7, "account-a", "token-a"))
    }

    /** 新刷新或新分页开始后，旧请求回包必须被丢弃。 */
    @Test
    fun staleInboxRequestCannotUpdate() {
        val request = NativeInboxRequest(6, "account-a", "token-a")

        assertFalse(isCurrentInboxRequest(request, 7, "account-a", "token-a"))
    }

    /** 账号或刷新令牌变化后，旧账号通知不能显示在当前收件箱中。 */
    @Test
    fun changedInboxAccountCannotUpdate() {
        val request = NativeInboxRequest(7, "account-a", "token-a")

        assertFalse(isCurrentInboxRequest(request, 7, "account-b", "token-a"))
        assertFalse(isCurrentInboxRequest(request, 7, "account-a", "token-b"))
    }
}
