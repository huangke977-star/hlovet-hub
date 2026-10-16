package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAiConfigurationTest {
    @Test fun pricesKeepAllSixDecimalPlaces() {
        assertEquals(1234567L, parseNativeAiPrice("1.234567"))
        assertEquals(1L, parseNativeAiPrice("0.000001"))
        assertEquals("1.234567", formatNativeAiPrice(1234567L))
        assertEquals("0", formatNativeAiPrice(0))
    }

    @Test fun priceSanitizerKeepsOnlyOneDecimalPoint() {
        assertEquals("1.234567", sanitizeNativeAiPrice("1.23.456789"))
        assertEquals("0.123456", sanitizeNativeAiPrice("USD 0.1234567"))
        assertEquals("", sanitizeNativeAiPrice("USD"))
    }

    @Test fun incompleteAndOverflowingPricesDoNotReplaceTheSavedValue() {
        assertNull(parseNativeAiPrice("."))
        assertNull(parseNativeAiPrice(""))
        assertNull(parseNativeAiPrice("999999999999999999999"))
    }

    @Test fun changingProviderUpdatesOnlyKnownDefaultUrls() {
        assertEquals("https://api.deepseek.com/v1", nativeAiProviderBaseUrl("deepseek", "https://api.openai.com/v1/"))
        assertEquals("https://api.anthropic.com", nativeAiProviderBaseUrl("anthropic", ""))
        assertEquals("https://relay.example/v1", nativeAiProviderBaseUrl("deepseek", "https://relay.example/v1"))
    }
}
