package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证系统凭据页结束后的宿主返回保护及快速重试边界。 */
class NativeCredentialReturnStateTest {
    /** 凭据页打开和结束期间均拦截宿主返回，结束后只保留短暂保护。 */
    @Test
    fun promptLifecycleBlocksBackDuringAndImmediatelyAfterPrompt() {
        val started = NativeCredentialReturnState().promptStarted()
        assertTrue(started.blocksHostBack())

        val finished = started.promptFinished()
        assertTrue(finished.returnBackGuard)
        assertTrue(finished.blocksHostBack())

        assertFalse(finished.releaseReturnGuard(finished.version).blocksHostBack())
    }

    /** 新一轮凭据请求会使旧延迟回调失效，避免快速重试时提前放开返回键。 */
    @Test
    fun staleReleaseCannotClearTheNewPromptGuard() {
        val firstFinished = NativeCredentialReturnState()
            .promptStarted()
            .promptFinished()
        val secondFinished = firstFinished
            .promptStarted()
            .promptFinished()

        val afterStaleRelease = secondFinished.releaseReturnGuard(firstFinished.version)
        assertTrue(afterStaleRelease.blocksHostBack())
        assertFalse(afterStaleRelease.releaseReturnGuard(secondFinished.version).blocksHostBack())
    }
}
