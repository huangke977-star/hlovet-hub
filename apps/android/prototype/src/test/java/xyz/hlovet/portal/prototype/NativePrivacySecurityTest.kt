package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePrivacySecurityTest {
    @Test
    fun mapsKnownSecurityEventsToLocalizedLabels() {
        try {
            NativeI18n.applyLocale("zh-CN")
            assertEquals("新设备登录", nativeSecurityEventLabel("new_device"))
            assertEquals("密码已修改", nativeSecurityEventLabel("password_changed"))

            NativeI18n.applyLocale("en-US")
            assertEquals("New-device sign-in", nativeSecurityEventLabel("new_device"))
            assertEquals("Password changed", nativeSecurityEventLabel("password_changed"))
        } finally {
            NativeI18n.applyLocale("zh-CN")
        }
    }

    @Test
    fun preservesServerSummaryForUnknownEvents() {
        assertEquals("A new security signal", nativeSecurityEventLabel("future_event", "A new security signal"))
        assertEquals("安全事件", nativeSecurityEventLabel("future_event"))
    }
}
