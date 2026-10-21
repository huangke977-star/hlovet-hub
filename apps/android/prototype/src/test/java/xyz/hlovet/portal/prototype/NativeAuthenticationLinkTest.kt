package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 核对认证回调的协议、配置主机和令牌边界，不将语法可解析视为系统域名认证。 */
class NativeAuthenticationLinkTest {
    /** 自定义协议与实际配置的 HTTPS 主机均保留 Google 回调契约及大小写归一化。 */
    @Test
    fun googleCallbacksSupportNativeAndVerifiedPortalLinks() {
        // 英文页面和大小写变化不改变主机身份，未配置的 www 不在此成功范围。
        val expected = NativeAuthenticationLink.Google("oauth_token")
        assertEquals(expected, parseNativeAuthenticationLink("hlovet-native://auth/google?result=oauth_token"))
        assertEquals(expected, parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?result=oauth_token"))
        assertEquals(expected, parseNativeAuthenticationLink("https://5200918.XYZ/en/native-auth/google/?result=oauth_token"))
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

    /** 拒绝未配置别名、相似域名、明文来源和带凭据或非标准端口的认证回调。 */
    @Test
    fun nonPortalAndLookalikeOriginsCannotSupplyAuthenticationTokens() {
        // 这些地址不能因为名称相近或自动添加 www 而取得传入认证令牌的资格。
        listOf(
            "http://5200918.xyz/native-auth/google?result=token",
            "https://www.5200918.xyz/native-auth/google?result=token",
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

    /** 主机白名单只包含构建配置的实际站点，正常路径可用而相似域名仍拒绝。 */
    @Test
    fun configuredPortalHostStillUsesTheSameAuthenticationPathContract() {
        // 默认测试构建使用主域名；别名不应被自动声明或扩大认证回调范围。
        assertEquals(setOf("5200918.xyz"), nativePortalHostnames())
        assertEquals(
            NativeAuthenticationLink.Google("oauth_token"),
            parseNativeAuthenticationLink("https://5200918.xyz/native-auth/google?result=oauth_token"),
        )
        assertNull(parseNativeAuthenticationLink("https://5200918.xyz.example.com/native-auth/google?result=oauth_token"))
    }
}
