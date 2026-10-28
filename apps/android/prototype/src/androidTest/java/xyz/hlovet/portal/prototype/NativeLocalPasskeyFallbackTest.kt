package xyz.hlovet.portal.prototype

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** 在真实 MainActivity 中验证设备没有 Passkey 时的可理解失败回退，不伪造凭据成功。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalPasskeyFallbackTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var previousSession: NativeSession? = null
    private var previousLocale: String? = null

    /** 仅在本地 API 和测试设备上运行，保存当前会话以免登录失败分支覆盖原状态。 */
    @Before fun prepare() {
        // Passkey 选项接口是公开登录入口，仍限制为本地来源，避免误触生产认证。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        previousSession = NativeSessionStore.load(context)
        previousLocale = NativeI18n.locale
        NativeSessionStore.clear(context)
        // 宿主启动时会读取持久化语言；测试必须同时写入偏好，不能只修改进程内状态。
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyAndPersist(context, "zh-CN") }
    }

    /** 关闭宿主并恢复原会话、语言和不含凭据的测试报告。 */
    @After fun restore() {
        // 失败分支不应把用户原本的本地登录态留在清空状态。
        activity?.close()
        previousSession?.let { NativeSessionStore.save(context, it) }
            ?: NativeSessionStore.clear(context)
        previousLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyAndPersist(context, locale) } }
    }

    /** 点击真实 Passkey 登录入口，确认无凭据或取消后仍停留在登录页并显示回退提示。 */
    @Test fun unavailablePasskeyReturnsToPasswordLoginWithVisibleFallback() {
        // 从首页进入个人页和登录弹窗，沿用用户实际可见入口，不直接挂载登录组件。
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitTag("native-navigation-4")
        compose.onNodeWithTag("native-navigation-4").performClick()
        waitTag("native-profile-login")
        compose.onNodeWithTag("native-profile-login").performClick()
        waitTag("native-login-account")

        // 设备凭据页由系统提供方接管；无凭据时应由应用捕获 GetCredentialException。
        compose.onNode(hasText("使用通行密钥登录") and hasClickAction()).performClick()
        val fallback = hasText("当前设备没有可用的通行密钥", substring = true) or
            hasText("当前设备的 Google Play 服务不支持通行密钥", substring = true) or
            hasText("通行密钥验证失败", substring = true)
        compose.waitUntil(30_000) {
            compose.onAllNodes(fallback, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("native-login-account").assertIsDisplayed()
        assertTrue(NativeSessionStore.load(context) == null)
        File(context.filesDir, "native-local-passkey-fallback.json").writeText(
            JSONObject().put("apiOrigin", BuildConfig.API_BASE_URL).put("sessionCreated", false)
                .put("passwordLoginStillAvailable", true).put("credentialSuccessFaked", false).toString(2),
        )
    }

    /** 等待真实宿主控件出现，避免固定坐标或屏幕截图代替页面状态。 */
    private fun waitTag(tag: String) {
        // 登录请求和系统凭据页均为异步操作，只有语义节点稳定后才能继续。
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
