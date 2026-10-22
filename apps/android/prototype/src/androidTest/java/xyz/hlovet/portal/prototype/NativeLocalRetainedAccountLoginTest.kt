package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 通过原生登录界面恢复既有本地测试账号会话，不注册账号、不导出认证材料。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalRetainedAccountLoginTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var username: String
    private lateinit var password: String
    private var expectedUserId: Int = 0
    private var forceLocalLogin: Boolean = false
    private var activity: ActivityScenario<MainActivity>? = null

    /** 校验仅允许本地 API 和明确提供的既有测试账号参数，避免触及生产登录。 */
    @Before
    fun requireLocalAccountArguments() {
        // 登录操作只面向正在运行的本地 Docker API，不接受线上地址或空账号。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        val args = InstrumentationRegistry.getArguments()
        username = requireNotNull(args.getString("localUsername"))
        password = requireNotNull(args.getString("localPassword"))
        expectedUserId = requireNotNull(args.getString("expectedUserId")).toInt()
        forceLocalLogin = args.getBoolean("forceLocalLogin", false)
        check(username.matches(Regex("[A-Za-z0-9_]{3,32}")) && password.isNotBlank() && expectedUserId > 0)
    }

    /** 关闭登录页面，不改动已经安全保存的会话或服务端账号记录。 */
    @After
    fun closeActivity() {
        // Activity 只由当前仪器测试创建，结束时关闭界面但保留登录态供后续双端通话验证。
        activity?.close()
    }

    /** 经真实原生登录表单恢复会话，核对用户身份并保留不含令牌的验收报告。 */
    @Test
    fun existingAccountLoginPersistsEncryptedSession() {
        // 仅在本地专项恢复测试明确要求时清除应用私有会话，离线文章和其它应用数据不受影响。
        if (forceLocalLogin) NativeSessionStore.clear(context)
        // 从应用冷状态进入个人页，沿用用户实际可见的登录入口和表单。
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitForTag("native-navigation-4")
        compose.onNodeWithTag("native-navigation-4").performClick()
        var session = NativeSessionStore.load(context)
        if (forceLocalLogin || session?.userId != expectedUserId) {
            // 尚无目标会话时才经真实登录表单恢复，不覆盖设备上属于其他账号的现有会话。
            waitForTag("native-profile-login")
            compose.onNodeWithTag("native-profile-login").performClick()
            waitForTag("native-login-account")

            // 只在当前仪器进程内使用测试密码，登录完成后不将其写入报告或设备文件。
            compose.onNodeWithTag("native-login-account").performTextReplacement(username)
            compose.onNodeWithTag("native-login-password").performTextReplacement(password)
            compose.onNodeWithTag("native-login-submit").performScrollTo().performClick()
            compose.waitUntil(30_000) { NativeSessionStore.load(context)?.userId == expectedUserId }
            session = NativeSessionStore.load(context)
        }

        // 个人页显示的身份与加密会话一致，证明登录态已被应用恢复而非只出现成功提示。
        session = requireNotNull(session)
        assertEquals(expectedUserId, session.userId)
        assertEquals(username, session.username)
        waitForTag("native-navigation-4")
        compose.onNodeWithTag("native-navigation-4").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("native-profile-username", useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        // 个人页节点的文本可能因加载状态、语言或统计信息变化，不把完整展示文案当作登录成功条件。
        compose.onNodeWithTag("native-profile-username", useUnmergedTree = true).assertIsDisplayed()

        // 报告只记录测试账号标识和加密文件状态，不保存密码、访问令牌或刷新令牌。
        val sessionFile = NativeSessionStore.sessionFile(context)
        assertTrue(sessionFile.exists())
        val ciphertext = sessionFile.readText()
        assertTrue(ciphertext.isNotBlank())
        assertTrue(!ciphertext.contains(username) && !ciphertext.contains(session.accessToken))
        File(context.filesDir, "native-local-account-restore-login.json").writeText(
            JSONObject()
                .put("apiOrigin", BuildConfig.API_BASE_URL)
                .put("userId", session.userId)
                .put("username", session.username)
                .put("sessionEncrypted", true)
                .put("restoredThroughNativeProfile", true)
                .toString(2),
        )
    }

    /** 等待 tag 对应的真实登录控件出现，网络和 Compose 状态尚未就绪时不提前点击。 */
    private fun waitForTag(tag: String) {
        // 使用明确的原生控件标识等待页面状态，不依赖固定 sleep 或屏幕坐标。
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
