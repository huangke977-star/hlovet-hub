package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证信息流请求代际只接受最新条件的回包。 */
class NativeRequestGenerationTest {
    /** 新请求开始后，之前请求的回包必须被识别为过期。 */
    @Test
    fun newerRequestInvalidatesPreviousGeneration() {
        val generation = NativeRequestGeneration()
        val first = generation.begin()
        val second = generation.begin()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    /** 没有后续请求时，当前请求仍然可以提交列表结果。 */
    @Test
    fun currentRequestRemainsValid() {
        val generation = NativeRequestGeneration()
        val request = generation.begin()

        assertTrue(generation.isCurrent(request))
    }
}
