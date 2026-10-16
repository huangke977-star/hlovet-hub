package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePortalIntentTest {
    @Test
    fun portalIntentWaitsForSessionRestore() {
        assertFalse(canConsumeNativePortalIntent("http://10.0.2.2/admin/articles", false))
        assertTrue(canConsumeNativePortalIntent("http://10.0.2.2/admin/articles", true))
    }

    @Test
    fun emptyPortalIntentIsNeverConsumed() {
        assertFalse(canConsumeNativePortalIntent(null, true))
        assertFalse(canConsumeNativePortalIntent("", true))
        assertFalse(canConsumeNativePortalIntent("   ", true))
    }
}
