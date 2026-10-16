package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeVerificationCodeTest {
    @Test
    fun preservesLeadingZeroesAndFiltersNonAsciiDigits() {
        assertEquals("001234", normalizeNativeVerificationCode("00 12-34 56", false))
        assertEquals("1234", normalizeNativeVerificationCode("\uFF11\uFF12\u0661\u0662 12 34", false))
    }

    @Test
    fun recoveryCodesAllowAsciiLettersAndUseUppercase() {
        assertEquals("AB12CD", normalizeNativeVerificationCode("ab 12-cd!9", true))
        assertEquals("", normalizeNativeVerificationCode("\u00DF\u4E2D\uFF21", true))
    }

    @Test
    fun authenticatorCodesNeverKeepLetters() {
        assertEquals("012345", normalizeNativeVerificationCode("a0b1c2d3e4f5", false))
    }
}
