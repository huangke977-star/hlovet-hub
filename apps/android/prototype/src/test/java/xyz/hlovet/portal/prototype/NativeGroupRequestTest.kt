package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证群聊列表、详情和搜索回包不会写入失效目标。 */
class NativeGroupRequestTest {
    /** 当前群聊请求仍有效时允许写回。 */
    @Test
    fun currentGroupRequestCanUpdate() {
        assertTrue(isCurrentGroupRequest(4, 4, 12, 12, "", "", "token-a", "token-a"))
    }

    /** 切换群聊、搜索词或请求代际后，旧回包必须被丢弃。 */
    @Test
    fun staleGroupRequestCannotUpdate() {
        assertFalse(isCurrentGroupRequest(3, 4, 12, 13, "old", "new", "token-a", "token-a"))
    }

    /** 账号变化后，旧账号群聊结果不能显示在新会话中。 */
    @Test
    fun changedGroupAccountCannotUpdate() {
        assertFalse(isCurrentGroupRequest(4, 4, null, null, "", "", "token-a", "token-b"))
    }
}
