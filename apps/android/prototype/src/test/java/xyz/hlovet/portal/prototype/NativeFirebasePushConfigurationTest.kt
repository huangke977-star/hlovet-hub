package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 FCM 可选配置的启用边界，避免无配置构建误初始化推送 SDK。 */
class NativeFirebasePushConfigurationTest {
    /** 空配置必须关闭推送能力，避免应用启动时访问 Firebase。 */
    @Test
    fun emptyConfigurationDisablesPush() {
        // 使用默认构建的空参数，确认缺少配置时能力明确关闭。
        assertFalse(isNativeFirebaseConfigured("", "", "", ""))
    }

    /** 只缺少任意一个标识时仍必须关闭推送能力。 */
    @Test
    fun partialConfigurationDisablesPush() {
        // 模拟管理员只填写部分参数，不能允许半配置进入 SDK 初始化。
        assertFalse(isNativeFirebaseConfigured("api-key", "project", "", "sender"))
    }

    /** 四个标识完整时允许构建启用推送能力。 */
    @Test
    fun completeConfigurationEnablesPush() {
        // 完整参数只验证配置门槛，不发起网络请求也不读取真实 Firebase 密钥。
        assertTrue(isNativeFirebaseConfigured("api-key", "project", "application", "sender"))
    }
}
