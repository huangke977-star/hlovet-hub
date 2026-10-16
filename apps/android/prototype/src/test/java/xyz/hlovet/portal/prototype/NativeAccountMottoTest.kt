package xyz.hlovet.portal.prototype

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NativeAccountMottoTest {
    @Test
    fun usesTheCompletePcMottoCatalog() {
        assertEquals(32 * 32, nativeAccountMottoVariantCount())
    }

    @Test
    fun sameAccountKeepsTheSameMotto() {
        try {
            NativeI18n.applyLocale("zh-CN")
            val first = nativeAccountMotto(7, "nice3", "2026-01-02T03:04:05.000Z")
            assertEquals(first, nativeAccountMotto(7, "nice3", "2026-01-02T03:04:05.000Z"))
            assertNotEquals(first, nativeAccountMotto(8, "nice3", "2026-01-02T03:04:05.000Z"))
        } finally {
            NativeI18n.applyLocale("zh-CN")
        }
    }

    @Test
    fun localeChangesOnlyTheRenderedLanguage() {
        try {
            NativeI18n.applyLocale("zh-CN")
            val chinese = nativeAccountMotto(7, "nice3", "2026-01-02T03:04:05.000Z")
            NativeI18n.applyLocale("en-US")
            val english = nativeAccountMotto(7, "nice3", "2026-01-02T03:04:05.000Z")
            assertNotEquals(chinese, english)
            assertEquals(".", english.takeLast(1))
        } finally {
            NativeI18n.applyLocale("zh-CN")
        }
    }

    @Test
    fun membershipDurationUsesTheAccountJoinTime() {
        try {
            NativeI18n.applyLocale("zh-CN")
            val joined = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).parse("2026-01-01T00:00:00.000Z")!!.time
            val elapsed = 1000L * (86400L + 3600L + 60L + 1L)
            assertEquals("1 天 1 小时 1 分 1 秒", nativeMemberDuration("2026-01-01T00:00:00.000Z", joined + elapsed))
            NativeI18n.applyLocale("en-US")
            assertEquals("1d 1h 1m 1s", nativeMemberDuration("2026-01-01T00:00:00.000Z", joined + elapsed))
        } finally {
            NativeI18n.applyLocale("zh-CN")
        }
    }
}
