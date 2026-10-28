package xyz.hlovet.portal.prototype

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 验证认证响应到 Android 登录状态的转换契约，覆盖成功、二次验证和 Google 关联分支。 */
class NativeAuthResponseParserTest {
    /** 解析普通登录成功响应，并保留用户资料、外观和管理员标志。 */
    @Test
    fun parsesAuthenticatedSession() {
        // 组装服务端成功响应，确认 Android 使用访问令牌建立完整本地会话。
        val response = JSONObject().apply {
            put("accessToken", "access-token")
            put("refreshToken", "refresh-token")
            put("user", JSONObject().apply {
                put("id", 42)
                put("username", "reader")
                put("nickname", "Reader")
                put("email", "reader@example.com")
                put("profileBio", "bio")
                put("avatarUrl", "https://example.com/avatar.png")
                put("locale", "en-US")
                put("isAdministrator", true)
                put("appearance", JSONObject().apply {
                    put("themeId", "sakura-mist")
                    put("cardAlpha", 64)
                    put("glassBlur", 24)
                })
            })
        }

        // 解析结果必须直接进入 Authenticated，而不是误判为二次验证。
        val result = parseNativeAuthResponse(response) as NativeAuthResult.Authenticated

        // 校验登录令牌、用户身份、语言、权限和外观配置都没有丢失。
        assertEquals("access-token", result.session.accessToken)
        assertEquals("refresh-token", result.session.refreshToken)
        assertEquals(42, result.session.userId)
        assertEquals("reader", result.session.username)
        assertEquals("Reader", result.session.nickname)
        assertEquals("reader@example.com", result.session.email)
        assertEquals("en-US", result.session.locale)
        assertTrue(result.session.isAdministrator)
        assertEquals("sakura-mist", result.session.appearance.themeId)
        assertEquals(64, result.session.appearance.cardAlpha)
        assertEquals(24, result.session.appearance.glassBlur)
    }

    /** 解析新设备验证响应，并将异常的重发间隔收敛到至少一秒。 */
    @Test
    fun parsesDeviceVerificationAndNormalizesRetryInterval() {
        // 服务端返回负数间隔时，客户端仍需保留可用的重发倒计时。
        val response = JSONObject().apply {
            put("deviceVerificationRequired", true)
            put("challengeToken", "device-challenge")
            put("emailHint", "r***@example.com")
            put("retryAfterSeconds", -5)
        }

        // 解析结果必须携带挑战值，供验证码提交和重发接口继续使用。
        val result = parseNativeAuthResponse(response) as NativeAuthResult.DeviceVerification

        // 校验倒计时边界和邮箱提示。
        assertEquals("device-challenge", result.challengeToken)
        assertEquals("r***@example.com", result.emailHint)
        assertEquals(1, result.retryAfterSeconds)
    }

    /** 解析 TOTP 二次验证响应，并确保挑战值被保留下来。 */
    @Test
    fun parsesTotpVerification() {
        // 构造需要 TOTP 的登录响应。
        val response = JSONObject().apply {
            put("totpVerificationRequired", true)
            put("challengeToken", "totp-challenge")
        }

        // TOTP 分支只能返回挑战状态，不能提前创建会话。
        val result = parseNativeAuthResponse(response) as NativeAuthResult.TotpVerification

        // 验证后续提交所需的挑战值。
        assertEquals("totp-challenge", result.challengeToken)
    }

    /** 解析 Google 同邮箱关联响应，并准确保留可用验证方式。 */
    @Test
    fun parsesGoogleLinkMethods() {
        // 组装服务端要求用户确认绑定已有账号的响应。
        val response = JSONObject().apply {
            put("oauthLinkRequired", true)
            put("pendingToken", "pending-link")
            put("email", "reader@example.com")
            put("methods", JSONObject().apply {
                put("passkey", true)
                put("email", true)
                put("totp", false)
                put("password", true)
            })
        }

        // 关联状态必须优先于普通会话解析。
        val result = parseNativeAuthResponse(response) as NativeAuthResult.GoogleLinkRequired

        // 验证 UI 能据此只展示服务端实际支持的验证方式。
        assertEquals("pending-link", result.pendingToken)
        assertEquals("reader@example.com", result.email)
        assertTrue(result.methods.passkey)
        assertTrue(result.methods.email)
        assertFalse(result.methods.totp)
        assertTrue(result.methods.password)
    }

    /** 拒绝缺少挑战值的设备、TOTP 和 Google 关联响应，避免进入不可提交状态。 */
    @Test
    fun rejectsVerificationResponsesWithoutTokens() {
        // 分别检查三种需要继续验证的响应是否都被拒绝。
        assertMissingToken(
            JSONObject().put("deviceVerificationRequired", true),
            "设备验证挑战值",
        )
        assertMissingToken(
            JSONObject().put("totpVerificationRequired", true),
            "双因素验证挑战值",
        )
        assertMissingToken(
            JSONObject().put("oauthLinkRequired", true),
            "Google 关联令牌",
        )
    }

    /** 拒绝缺少访问令牌或用户对象的伪成功响应，防止保存半会话。 */
    @Test
    fun rejectsIncompleteAuthenticatedResponse() {
        // 访问令牌缺失时不能建立本地登录态。
        assertMissingToken(
            JSONObject().put("user", JSONObject().put("id", 1).put("username", "reader")),
            "访问令牌",
        )

        // 用户对象缺失时也必须失败，而不是使用默认用户覆盖真实状态。
        try {
            parseNativeAuthResponse(JSONObject().put("accessToken", "access-token"))
            fail("缺少用户对象时应拒绝认证响应")
        } catch (exception: IllegalStateException) {
            assertEquals("登录响应缺少用户信息", exception.message)
        }
    }

    /** 区分普通 Google 登录回调和已登录账号的 Google 关联回调，防止回调首帧闪出错误页面。 */
    @Test
    fun oauthCallbackOpensLoginOnlyForOrdinarySignIn() {
        // 普通登录成功或失败都必须立即交给认证层消费。
        assertTrue(shouldShowNativeLoginForOAuth("result-token", null, "/dashboard"))
        assertTrue(shouldShowNativeLoginForOAuth(null, "access_denied", "/dashboard"))

        // 已登录集成页的回调由集成页面消费，不能重新打开密码登录弹窗。
        assertFalse(shouldShowNativeLoginForOAuth("result-token", null, "/profile/integrations"))
        assertFalse(shouldShowNativeLoginForOAuth(null, "access_denied", "/profile/integrations"))
        assertFalse(shouldShowNativeLoginForOAuth(null, null, "/dashboard"))
    }

    /** 验证指定响应会因缺少令牌失败，并核对错误信息便于定位服务端契约问题。 */
    private fun assertMissingToken(response: JSONObject, label: String) {
        // 执行解析并要求抛出明确的契约异常。
        try {
            parseNativeAuthResponse(response)
            fail("缺少${label}时应拒绝认证响应")
        } catch (exception: IllegalStateException) {
            // 错误必须指出具体缺失字段，避免页面只显示笼统的登录失败。
            assertEquals("登录响应缺少$label", exception.message)
        }
    }
}
