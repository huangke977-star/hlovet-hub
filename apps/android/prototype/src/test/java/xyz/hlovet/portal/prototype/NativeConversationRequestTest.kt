package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证聊天搜索回包不会跨会话、查询词、账号或请求代际写回。 */
class NativeConversationRequestTest {
    /** 当前会话和查询仍有效时允许搜索结果更新。 */
    @Test
    fun currentConversationSearchCanUpdate() {
        assertTrue(isCurrentConversationSearchRequest(3, 3, 8, 8, "hello", "hello", "token-a", "token-a"))
    }

    /** 切换查询或会话后，旧搜索结果必须被丢弃。 */
    @Test
    fun staleConversationSearchCannotUpdate() {
        assertFalse(isCurrentConversationSearchRequest(2, 3, 8, 9, "hello", "world", "token-a", "token-a"))
    }

    /** 账号变化后，旧账号搜索结果不能显示在新会话中。 */
    @Test
    fun changedConversationAccountCannotUpdate() {
        assertFalse(isCurrentConversationSearchRequest(3, 3, 8, 8, "hello", "hello", "token-a", "token-b"))
    }
}
