package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeChatListPositionTest {
    @Test
    fun messageIndexAccountsForTheOlderMessagesRow() {
        assertEquals(0, nativeChatListHeaderOffset(false))
        assertEquals(1, nativeChatListHeaderOffset(true))
        assertEquals(0, nativeChatListItemIndex(0, false))
        assertEquals(1, nativeChatListItemIndex(0, true))
        assertEquals(8, nativeChatListItemIndex(7, true))
    }

    @Test
    fun negativeMessageIndexNeverProducesANegativeLazyListIndex() {
        assertEquals(0, nativeChatListItemIndex(-1, false))
        assertEquals(0, nativeChatListItemIndex(-1, true))
    }
}
