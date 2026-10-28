package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** 经真实宿主和本地 API 验收隐私敏感操作，保留新账号与审计并恢复原加密会话。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalMainPrivacyTest {
    @get:Rule val network = NativeLoopbackNetworkRule()
    @get:Rule val compose = createEmptyComposeRule()
    /** 返回目标应用上下文，防止把测试账号写入其它包或来源。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var previousSession: NativeSession? = null
    private var previousLocale: String? = null
    private var previousStoredLocale: String? = null
    private var fixture: NativeSession? = null
    private var password = ""
    private var expectedEmail = ""
    private var totpSecret = ""
    private val checks = JSONArray()
    private val report = JSONObject().put("completed", false).put("productionTested", false)
        .put("testDataRemoved", false).put("interactiveLoginTested", false)

    /** 只在本地无真实邮件的测试策略下创建独立账号，保存原会话及语言偏好。 */
    @Before fun prepare() {
        // 必须先确认来源和加密配置，不能为使测试通过而修改验证码或生产策略。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        assumeTrue(InstrumentationRegistry.getArguments().getString("localCredentialEncryptionConfigured") == "true")
        val policy = NativeAuthApi.registrationPolicy()
        assumeTrue(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled && !policy.loginTurnstileEnabled)
        val security = nativeLocalSecurityPolicy()
        assumeTrue(!security.optBoolean("mailServiceEnabled") && !security.optBoolean("untrustedDeviceEmailVerificationEnabled"))
        previousSession = NativeSessionStore.load(context)
        previousLocale = NativeI18n.locale
        previousStoredLocale = context.getSharedPreferences("native-i18n", Context.MODE_PRIVATE).getString("locale", null)

        // 注册真实账号并预先完成无兴趣选择的引导，不替换服务端签发的凭据。
        val username = "native_privacy_" + UUID.randomUUID().toString().replace("-", "").take(12)
        password = "Native_${UUID.randomUUID()}_9!"
        val registered = NativeAuthApi.register(username, "Native privacy", "$username@android-fixture.invalid", password, null, null)
        check(registered is NativeAuthResult.Authenticated)
        fixture = registered.session
        expectedEmail = registered.session.email
        NativeDiscoveryApi.completeOnboarding(registered.session.accessToken, emptyList(), emptyList())
        check(NativeSessionStore.save(context, registered.session))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("fixtureId", registered.session.userId).put("fixtureUsername", username)
            .put("apiOrigin", BuildConfig.API_BASE_URL)
    }

    /** 关闭宿主后恢复原会话和语言；失败时撤回本轮注销申请但不删除账号、消息或审计。 */
    @After fun restore() {
        // 避免留下一周后会自动删除测试记录的待注销状态，恢复失败必须让用例失败。
        try { activity?.close(); activity = null } finally {
            try {
                fixture?.let { session ->
                    if (NativePrivacyApi.getOverview(session.accessToken).deletionPending) NativePrivacyApi.cancelDeletion(session.accessToken)
                    assertFalse(NativePrivacyApi.getOverview(session.accessToken).deletionPending)
                }
            } finally {
                // 不清空应用数据或覆盖正式来源会话，来源隔离文件由既有存储器负责。
                if (previousLocale != null) {
                    previousSession?.let { check(NativeSessionStore.save(context, it)) } ?: NativeSessionStore.clear(context)
                    val preferences = context.getSharedPreferences("native-i18n", Context.MODE_PRIVATE).edit()
                    if (previousStoredLocale == null) preferences.remove("locale") else preferences.putString("locale", previousStoredLocale)
                    check(preferences.commit())
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(requireNotNull(previousLocale)) }
                    report.put("originalSessionRestored", NativeSessionStore.load(context)?.userId == previousSession?.userId)
                }
                fixture?.let { report.put("checks", checks); writeReport() }
            }
        }
    }

    /** 验证错误、取消和成功延续，并通过数据库接口回读确认凭据、双因素与注销状态。 */
    @Test fun sensitiveActionsContinueInsideRealHostAndPreserveSession() {
        // 深链进入真实 MainActivity，不直接挂载隐私组件绕过全局会话和路由。
        openPrivacy()
        assertSession()
        record("real-host-opens-private-page-with-new-fixture-session")

        // 验证取消不会修改邮箱，错误密码在当前弹窗可见且没有跳走或退出账号。
        expectedEmail = "${requireNotNull(fixture).username}_changed@android-fixture.invalid"
        openEmailChange()
        action("关闭")
        waitClosed()
        assertEquals(requireNotNull(fixture).email, overview().email)
        assertSession()
        record("verification-cancel-leaves-email-and-host-session-unchanged")
        openEmailChange()
        action("当前密码")
        field("当前密码").performTextReplacement("Wrong_${UUID.randomUUID()}")
        action("继续")
        waitDialogError("当前密码不正确")
        assertEquals(requireNotNull(fixture).email, overview().email)
        assertSession()
        capture("wrong-password-stays-in-verification-dialog")
        record("incorrect-password-is-visible-without-mutation-or-host-logout")
        field("当前密码").performTextReplacement(password)
        action("继续")
        waitClosed()
        notice("邮箱修改成功。")
        assertEquals(expectedEmail, overview().email)
        assertSession()
        record("password-success-continues-email-change-and-shows-success")

        // 密码校验成功后必须直接进入六格绑定，不退回首页或再次要求登录。
        action("绑定双因素认证")
        action("当前密码")
        field("当前密码").performTextReplacement(password)
        action("继续")
        waitClosed()
        list().performScrollToNode(hasTestTag("privacy-totp-secret"))
        totpSecret = compose.onNodeWithTag("privacy-totp-secret").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertSession()
        record("password-verification-continues-to-authenticator-enrollment-in-host")
        list().performScrollToNode(hasTestTag("hlovet-code-input"))
        assertSixCells()
        compose.onNodeWithTag("hlovet-code-input").performTextReplacement(nativeLocalTotp(totpSecret))
        notice("双因素认证已启用", substring = true)
        assertTrue(overview().totpEnabled)
        assertSession()
        record("six-cell-enrollment-automatically-enables-real-totp")

        // TOTP 同样用于真实敏感操作，错误六位输入自动校验并留在弹窗中。
        val changedPassword = "Native_${UUID.randomUUID()}_Changed9!"
        action("修改密码")
        field("新密码").performTextReplacement(changedPassword)
        field("确认新密码").performTextReplacement(changedPassword)
        action("继续验证")
        action("双因素认证或恢复码")
        assertSixCells()
        compose.onNodeWithTag("hlovet-code-input").performTextReplacement("!!!!!!")
        assertEquals("", compose.onNodeWithTag("hlovet-code-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val valid = nativeLocalTotp(totpSecret)
        val invalid = ((valid.toInt() + 100_000) % 1_000_000).toString().padStart(6, '0')
        compose.onNodeWithTag("hlovet-code-input").performTextReplacement(invalid)
        waitDialogError("双因素验证码不正确")
        assertSession()
        record("six-cell-wrong-totp-reports-error-and-preserves-host-session")
        compose.onNodeWithTag("hlovet-code-input").performTextReplacement(nativeLocalTotp(totpSecret))
        waitClosed()
        notice("密码修改成功。")
        password = changedPassword
        assertSession()
        record("valid-totp-automatically-continues-password-change-with-success")

        // 解除使用密码验证，确认修改后的密码生效且当前原会话仍可执行受保护操作。
        action("解除双因素认证")
        action("当前密码")
        field("当前密码").performTextReplacement(password)
        action("继续")
        waitClosed()
        notice("双因素认证已关闭。")
        assertFalse(overview().totpEnabled)
        assertSession()
        record("new-password-verifies-totp-removal-without-clearing-current-session")

        // 注销先验证取消和错误不落库，再验证成功及撤回，保留完整审计而不删除账号。
        action("申请注销")
        action("关闭")
        waitClosed()
        assertFalse(overview().deletionPending)
        record("cancelled-deletion-verification-does-not-request-deletion")
        action("申请注销")
        action("当前密码")
        field("当前密码").performTextReplacement("Wrong_${UUID.randomUUID()}")
        action("继续")
        waitDialogError("当前密码不正确")
        assertFalse(overview().deletionPending)
        record("incorrect-password-cannot-request-deletion")
        field("当前密码").performTextReplacement(password)
        action("继续")
        waitClosed()
        notice("账号已进入 7 天注销冷静期。")
        assertTrue(overview().deletionPending)
        assertSession()
        record("verified-deletion-enters-cooling-off-with-success-and-session-intact")
        action("撤回注销")
        notice("已撤回注销申请。")
        assertFalse(overview().deletionPending)
        record("real-ui-withdraws-deletion-and-retains-fixture-account")

        // 关闭重开恢复真实原生页面，再回读安全审计及账号状态，不以成功提示代替持久化。
        activity?.close(); activity = null
        openPrivacy()
        assertSession()
        assertEquals(expectedEmail, overview().email)
        assertFalse(overview().totpEnabled)
        assertFalse(overview().deletionPending)
        val audit = NativePrivacyApi.listAudit(requireNotNull(fixture).accessToken, 100).map { it.action }
        for (action in listOf("email_changed", "password_changed", "totp_enabled", "totp_disabled")) assertTrue(action, audit.contains(action))
        report.put("auditActions", JSONArray(audit.distinct()))
        capture("reopened-privacy-with-session-and-final-state")
        record("host-reopen-keeps-session-and-persisted-credentials-totp-and-deletion-state")
        report.put("completed", true)
    }

    /** 打开隐私深链并等待真实账号凭据卡片出现。 */
    private fun openPrivacy() {
        // 未解析成功或被登录页拦截均应超时失败，不能自动切换成独立组件。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + "/profile/privacy")))
        compose.waitUntil(30_000) { compose.onAllNodesWithText("账号凭据").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 打开邮箱编辑并进入验证方式选择，使用 expectedEmail 作为待保存地址。 */
    private fun openEmailChange() {
        // 与真实用户一致先输入目标邮箱，只有验证通过后才允许提交服务端变更。
        action("修改邮箱")
        field("新邮箱").performTextReplacement(expectedEmail)
        action("继续验证")
        compose.onNodeWithText("选择验证方式").assertIsDisplayed()
    }

    /** 返回真实账号的隐私状态，不记录令牌、密码、TOTP 密钥或恢复码。 */
    private fun overview(): NativePrivacyOverview {
        // 独立 HTTP 回读用于核对页面操作的服务端结果。
        return NativePrivacyApi.getOverview(requireNotNull(fixture).accessToken)
    }

    /** 断言原加密会话仍为本轮账号、受保护接口可用且没有出现登录表单。 */
    private fun assertSession() {
        // 既检查宿主页面又检查凭据真实可用，避免只用存储文件存在证明登录成功。
        assertEquals(requireNotNull(fixture).userId, requireNotNull(NativeSessionStore.load(context)).userId)
        NativePrivacyApi.getSecurity(requireNotNull(fixture).accessToken)
        compose.onNodeWithTag("privacy-list").assertExists()
        compose.onNodeWithTag("native-login-account").assertDoesNotExist()
    }

    /** 返回隐私懒列表，确保定位到该页面而不是任意可滚动容器。 */
    private fun list(): SemanticsNodeInteraction {
        // 所有离屏卡片通过懒列表真实滚动进入组合。
        return compose.onNodeWithTag("privacy-list")
    }

    /** 点击 label 的唯一可操作控件；无弹窗时先使对应卡片进入视口。 */
    private fun action(label: String) {
        // 弹窗与页面中的相同文案必须按操作性匹配，不能点击说明文字。
        val matcher = hasText(label) and hasClickAction()
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) list().performScrollToNode(matcher)
        compose.onNode(matcher).assertIsDisplayed().performClick()
    }

    /** 返回 label 标识的编辑框，必要时滚动实际卡片使其可见。 */
    private fun field(label: String): SemanticsNodeInteraction {
        // 只返回支持文本输入的节点，排除卡片标题或按钮。
        val matcher = hasText(label) and hasSetTextAction()
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) list().performScrollToNode(matcher)
        return compose.onNode(matcher).assertIsDisplayed()
    }

    /** 等待验证弹窗完全关闭，不把局部成功提示当作后续流程已恢复。 */
    private fun waitClosed() {
        // 宿主意外导航走也会关闭弹窗，因此调用方仍需同时检查页面和会话。
        compose.waitUntil(30_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    /** 等待 containing 指定错误出现在仍打开的验证弹窗内。 */
    private fun waitDialogError(containing: String) {
        // 错误必须可见并属于当前验证窗口，不能以后台日志或页面外 Toast 替代。
        val matcher = hasText(containing, substring = true) and hasAnyAncestor(isDialog())
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(matcher).assertIsDisplayed()
    }

    /** 滚动并等待隐私页面显示 label 指定的结果提示；substring 支持附带恢复码的文案。 */
    private fun notice(label: String, substring: Boolean = false) {
        // 直到实际成功状态入组合再滚动，不能假定固定等待就完成网络请求。
        val matcher = hasTestTag("privacy-notice") and hasText(label, substring = substring)
        compose.waitUntil(30_000) {
            runCatching { list().performScrollToNode(matcher); compose.onNode(matcher).fetchSemanticsNode(); true }.getOrDefault(false)
        }
        compose.onNode(matcher).assertIsDisplayed()
    }

    /** 核对共享验证码组件显示六个固定输入格，输入完成后不需要再点确认。 */
    private fun assertSixCells() {
        // 组件真实可见与六格数量共同约束，避免只检查隐藏输入节点。
        compose.onNodeWithTag("hlovet-code-input").assertIsDisplayed()
        repeat(6) { compose.onAllNodesWithTag("hlovet-code-cell-$it", useUnmergedTree = true).assertCountEquals(1) }
    }

    /** 记录 name 对应已完成的检查点并立即落盘，保留失败发生前的真实进度。 */
    private fun record(name: String) {
        // 仅断言之后添加 passed，绝不预先填充成功清单。
        checks.put(JSONObject().put("name", name).put("passed", true))
        report.put("checks", checks)
        writeReport()
    }

    /** 写入不包含凭据的报告，账号和安全操作记录由本地服务保留。 */
    private fun writeReport() {
        // 只保存白名单业务信息，不导出加密会话或请求正文。
        val directory = File(context.filesDir, "native-local-main-privacy-evidence").apply { mkdirs() }
        File(directory, "report.json").writeText(report.toString(2))
    }

    /** 保存 name 指定的非敏感阶段整屏截图，不在显示 TOTP 密钥或恢复码时调用。 */
    private fun capture(name: String) {
        // 截图限定错误密码和最终恢复状态，不采集密码明文或安全绑定信息。
        val directory = File(context.filesDir, "native-local-main-privacy-evidence").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
