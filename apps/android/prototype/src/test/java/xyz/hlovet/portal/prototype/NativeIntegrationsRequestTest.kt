package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证外部集成列表读取只允许当前请求代际写回。 */
class NativeIntegrationsRequestTest {
    /** 当前账号、读取代际和列表版本一致时允许写回。 */
    @Test
    fun currentReadCanUpdateLists() {
        assertTrue(isCurrentIntegrationsListRequest(4, 4, "token-a", "token-a", 2, 2))
    }

    /** 连续刷新后，旧读取代际不能覆盖新列表。 */
    @Test
    fun staleReadGenerationCannotUpdateLists() {
        assertFalse(isCurrentIntegrationsListRequest(3, 4, "token-a", "token-a", 2, 2))
    }

    /** 写操作成功后，写操作前开始的读取必须失效。 */
    @Test
    fun readBeforeMutationCannotUpdateLists() {
        assertFalse(isCurrentIntegrationsListRequest(4, 4, "token-a", "token-a", 2, 3))
    }
}
