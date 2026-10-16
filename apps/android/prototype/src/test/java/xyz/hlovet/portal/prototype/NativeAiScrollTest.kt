package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAiScrollTest {
    @Test
    fun emptyListFollowsLatest() {
        assertTrue(shouldFollowNativeAiMessages(null, 0))
    }

    @Test
    fun lastVisibleMessageFollowsLatest() {
        assertTrue(shouldFollowNativeAiMessages(4, 5))
        assertTrue(shouldFollowNativeAiMessages(5, 5))
    }

    @Test
    fun historyAboveBottomDoesNotFollowLatest() {
        assertFalse(shouldFollowNativeAiMessages(2, 5))
    }
}
