package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证公开主页分页回包只允许写入当前用户和当前会话。 */
class NativePublicProfileRequestTest {
    /** 相同请求代际、用户和令牌可以更新当前主页。 */
    @Test
    fun currentRequestCanUpdateProfile() {
        assertTrue(
            isCurrentPublicProfileRequest(
                requestId = 3,
                activeRequestId = 3,
                requestUsername = "author",
                activeUsername = "author",
                requestAccessToken = "token-a",
                activeAccessToken = "token-a",
            ),
        )
    }

    /** 旧请求代际不能覆盖同一用户重新发起的分页结果。 */
    @Test
    fun staleRequestGenerationCannotUpdateProfile() {
        assertFalse(
            isCurrentPublicProfileRequest(
                requestId = 2,
                activeRequestId = 3,
                requestUsername = "author",
                activeUsername = "author",
                requestAccessToken = "token-a",
                activeAccessToken = "token-a",
            ),
        )
    }

    /** 用户或登录令牌变化后，旧主页回包必须被丢弃。 */
    @Test
    fun changedIdentityCannotUpdateProfile() {
        assertFalse(
            isCurrentPublicProfileRequest(
                requestId = 3,
                activeRequestId = 3,
                requestUsername = "author",
                activeUsername = "reader",
                requestAccessToken = "token-a",
                activeAccessToken = "token-b",
            ),
        )
    }
}
