package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppearanceValidationTest {
    @Test
    fun acceptsOnlySixDigitHashColors() {
        assertTrue(isValidNativeAppearanceColor("#2B2530"))
        assertTrue(isValidNativeAppearanceColor("#aBc123"))
        assertFalse(isValidNativeAppearanceColor("2B2530"))
        assertFalse(isValidNativeAppearanceColor("#2B253"))
        assertFalse(isValidNativeAppearanceColor("#2B25300"))
    }

    @Test
    fun rejectsCssNamesAndInjectionText() {
        assertFalse(isValidNativeAppearanceColor("red"))
        assertFalse(isValidNativeAppearanceColor("transparent"))
        assertFalse(isValidNativeAppearanceColor("#000000; background:black"))
        assertFalse(isValidNativeAppearanceColor(" #000000"))
        assertFalse(isValidNativeAppearanceColor(""))
    }

    @Test
    fun preservesFullyTransparentGlassTintFromSiteSettings() {
        assertEquals(0f, normalizedGlassTintAlpha(0), 0f)
        assertEquals(0.42f, normalizedGlassTintAlpha(42), 0f)
        assertEquals(1f, normalizedGlassTintAlpha(125), 0f)
    }
}
