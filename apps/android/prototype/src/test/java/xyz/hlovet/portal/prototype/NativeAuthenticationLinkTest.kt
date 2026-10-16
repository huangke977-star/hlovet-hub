package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAuthenticationLinkTest {
    @Test
    fun googleCallbacksSupportNativeAndVerifiedPortalLinks() {
        val expected = NativeAuthenticationLink.Google("oauth_token")
        assertEquals(expected, parseNativeAuthenticationLink("hlovet-native://auth/google?result=oauth_token"))
        assertEquals(expected, parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?result=oauth_token"))
        assertEquals(expected, parseNativeAuthenticationLink("https://WWW.5200918.XYZ/en/native-auth/google/?result=oauth_token"))
    }

    @Test
    fun resetCallbacksSupportNativeAndEmailedWebFallbackLinks() {
        val expected = NativeAuthenticationLink.PasswordReset("reset_token")
        assertEquals(expected, parseNativeAuthenticationLink("hlovet-native://auth/password-reset?token=reset_token"))
        assertEquals(expected, parseNativeAuthenticationLink("https://5200918.xyz/forgot-password?token=reset_token"))
        assertEquals(expected, parseNativeAuthenticationLink("https://5200918.xyz/en/forgot-password/?token=reset_token"))
    }

    @Test
    fun ordinaryRecoveryPageDoesNotStartAResetWithoutATokenParameter() {
        assertNull(parseNativeAuthenticationLink("https://5200918.xyz/forgot-password"))
        assertNull(parseNativeAuthenticationLink("https://5200918.xyz/forgot-password?from=profile"))
    }

    @Test
    fun emptyCallbackTokensArePreservedForAnExplicitError() {
        assertEquals(NativeAuthenticationLink.Google(""), parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google"))
        assertEquals(NativeAuthenticationLink.PasswordReset(""), parseNativeAuthenticationLink("hlovet-native://auth/password-reset"))
        assertEquals(NativeAuthenticationLink.PasswordReset(""), parseNativeAuthenticationLink("https://5200918.xyz/forgot-password?token="))
    }

    @Test
    fun googleCallbackPreservesProviderErrorForTheNativeLoginScreen() {
        assertEquals(
            NativeAuthenticationLink.Google("", "access_denied"),
            parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?error=access_denied"),
        )
        assertEquals(
            NativeAuthenticationLink.Google("", "用户取消了 Google 登录"),
            parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?error_description=%E7%94%A8%E6%88%B7%E5%8F%96%E6%B6%88%E4%BA%86%20Google%20%E7%99%BB%E5%BD%95"),
        )
    }

    @Test
    fun tokensAreDecodedOnlyOnceAndWhitespaceIsTrimmed() {
        assertEquals(NativeAuthenticationLink.Google("a+b=c%2F"), parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?result=%20a%2Bb%3Dc%252F%20"))
    }

    @Test
    fun googleCallbackPreservesTheNativeReturnTargetOnlyForSafePaths() {
        assertEquals(
            NativeAuthenticationLink.Google("oauth_token", returnTo = "/profile/integrations"),
            parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?result=oauth_token&from=%2Fprofile%2Fintegrations"),
        )
        assertEquals(
            NativeAuthenticationLink.Google("oauth_token"),
            parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?result=oauth_token&from=https%3A%2F%2Fevil.example"),
        )
    }

    @Test
    fun nonPortalAndLookalikeOriginsCannotSupplyAuthenticationTokens() {
        listOf(
            "http://5200918.xyz/native-auth/google?result=token",
            "https://5200918.xyz.example.com/native-auth/google?result=token",
            "https://example.com/native-auth/google?result=token",
            "https://example.com@5200918.xyz/native-auth/google?result=token",
            "https://5200918.xyz:444/native-auth/google?result=token",
            "hlovet-native://other/google?result=token",
            "https://5200918.xyz/native-auth/google/other?result=token",
        ).forEach { assertNull(it, parseNativeAuthenticationLink(it)) }
    }

    @Test
    fun invalidUrisAndUnrelatedPortalLinksAreIgnored() {
        assertNull(parseNativeAuthenticationLink(null))
        assertNull(parseNativeAuthenticationLink("not a URI"))
        assertNull(parseNativeAuthenticationLink("https://5200918.xyz/articles/example?token=token"))
        assertNull(parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?result=%ZZ"))
    }

    @Test
    fun configuredPortalHostStillUsesTheSameAuthenticationPathContract() {
        // The default debug build points at 5200918.xyz. This assertion protects
        // the shared host allow-list from regressing to a substring match.
        assertEquals(setOf("5200918.xyz", "www.5200918.xyz"), nativePortalHostnames())
        assertEquals(
            NativeAuthenticationLink.Google("oauth_token"),
            parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?result=oauth_token"),
        )
        assertNull(parseNativeAuthenticationLink("https://5200918.xyz.example.com/native-auth/google?result=oauth_token"))
    }
}
