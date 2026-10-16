package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.CookieHandler
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Real account writes are restricted to newly registered fixtures on the local Docker API. */
@RunWith(AndroidJUnit4::class)
class NativeLocalPrivacyDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(false)
    private var contentSet = false
    private var oldLocale = "zh-CN"
    private lateinit var session: NativeSession
    private var password = ""
    private var scenario = ""
    private val results = JSONArray()
    private val requests = CopyOnWriteArrayList<String>()
    private var previousCookies: CookieHandler? = null
    private var requestStartedAt = 0L

    @Before fun prepare() {
        assumeTrue("Requires an explicitly local API build", BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        assumeTrue("Requires verified local credential encryption",
            InstrumentationRegistry.getArguments().getString("localCredentialEncryptionConfigured") == "true")
        val policy = NativeAuthApi.registrationPolicy()
        assumeTrue("Does not change verification policies", policy.registrationOpen &&
            !policy.emailVerificationEnabled && !policy.turnstileEnabled && !policy.loginTurnstileEnabled)
        val security = nativeLocalSecurityPolicy()
        assumeTrue("Does not send real emails", !security.optBoolean("mailServiceEnabled") &&
            !security.optBoolean("untrustedDeviceEmailVerificationEnabled"))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        val username = "native_ui_" + UUID.randomUUID().toString().replace("-", "").take(12)
        password = "Native_${UUID.randomUUID()}_9!"
        val result = NativeAuthApi.register(username, "Android UI fixture", "$username@android-fixture.invalid", password, null, null)
        check(result is NativeAuthResult.Authenticated)
        session = result.session
        requestStartedAt = SystemClock.elapsedRealtime()
        previousCookies = CookieHandler.getDefault()
        CookieHandler.setDefault(object : CookieHandler() {
            override fun get(uri: URI, headers: Map<String, List<String>>): Map<String, List<String>> {
                requests.add("${SystemClock.elapsedRealtime() - requestStartedAt} ms request ${uri.path}")
                return previousCookies?.get(uri, headers).orEmpty()
            }
            override fun put(uri: URI, headers: Map<String, List<String>>) {
                requests.add("${SystemClock.elapsedRealtime() - requestStartedAt} ms response ${uri.path}")
                previousCookies?.put(uri, headers)
            }
        })
    }

    @After fun stop() {
        if (!::session.isInitialized) return
        try {
            if (contentSet) {
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
            }
        } finally {
            CookieHandler.setDefault(previousCookies)
            writeReport()
            runCatching { NativeAuthApi.logout(session.refreshToken) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
        }
    }

    @Test fun passwordVerifiedEmailAndPasswordChangesCloseDialogAndPersist() {
        scenario = "privacy-credentials-ui"
        show()
        val email = "${session.username}_changed@android-fixture.invalid"
        step("email action opens verification choice") {
            action("修改邮箱")
            field("新邮箱").performTextReplacement(email)
            action("继续验证")
            waitText("选择验证方式")
            action("当前密码")
        }
        step("wrong password is visible inside the active verification dialog") {
            field("当前密码").performTextReplacement("Wrong_${UUID.randomUUID()}")
            action("继续")
            compose.waitUntil(20_000) {
                compose.onAllNodes(hasText("current password is incorrect", substring = true, ignoreCase = true)
                    and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(session.email, NativePrivacyApi.getOverview(session.accessToken).email)
        }
        step("correct password closes verification and persists email") {
            field("当前密码").performTextReplacement(password)
            action("继续")
            waitClosed()
            notice("邮箱修改成功。").assertIsDisplayed()
            assertEquals(email, NativePrivacyApi.getOverview(session.accessToken).email)
        }
        val changed = "Native_${UUID.randomUUID()}_Changed9!"
        step("password change verifies identity and shows success") {
            action("修改密码")
            field("新密码").performTextReplacement(changed)
            field("确认新密码").performTextReplacement(changed)
            action("继续验证")
            waitText("选择验证方式")
            action("当前密码")
            field("当前密码").performTextReplacement(password)
            action("继续")
            waitClosed()
            notice("密码修改成功。").assertIsDisplayed()
        }
        step("changed password authenticates and audit records persist") {
            val authenticated = NativeAuthApi.login(session.username, changed)
            assertTrue(authenticated is NativeAuthResult.Authenticated)
            val account = (authenticated as NativeAuthResult.Authenticated).session
            assertEquals(session.userId, account.userId)
            val audit = NativePrivacyApi.listAudit(account.accessToken).map { it.action }
            assertTrue(audit.contains("email_changed"))
            assertTrue(audit.contains("password_changed"))
            NativeAuthApi.logout(account.refreshToken)
        }
    }

    @Test fun totpEnrollmentAndRecoveryRemovalUseSixCellUiWithRealApi() {
        scenario = "privacy-totp-ui"
        show()
        step("password verification continues to authenticator setup") {
            action("绑定双因素认证")
            waitText("选择验证方式")
            action("当前密码")
            field("当前密码").performTextReplacement(password)
            action("继续")
            waitClosed()
            root().performScrollToNode(hasTestTag("privacy-totp-secret"))
        }
        val secret = compose.onNodeWithTag("privacy-totp-secret").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        step("six-cell authenticator input automatically enables TOTP") {
            root().performScrollToNode(hasTestTag("hlovet-code-input"))
            assertSixCells()
            compose.onNodeWithTag("hlovet-code-input").performTextReplacement(nativeLocalTotp(secret))
            val feedback = notice("双因素认证已启用", substring = true).fetchSemanticsNode().boundsInRoot
            assertTrue("TOTP completion feedback must be visible", feedback.width > 0f && feedback.height > 0f)
            assertTrue(NativePrivacyApi.getOverview(session.accessToken).totpEnabled)
        }
        val recoveryText = compose.onNodeWithTag("privacy-notice").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        val codes = Regex("[A-Z0-9]{6}").findAll(recoveryText).map { it.value }.toList()
        assertTrue("Expected six-character recovery codes", codes.isNotEmpty())
        val recovery = requireNotNull(codes.firstOrNull { it.any(Char::isLetter) })
        step("six-cell alphanumeric recovery input automatically disables TOTP") {
            action("解除双因素认证")
            waitText("选择验证方式")
            action("双因素认证或恢复码")
            assertSixCells()
            compose.onNodeWithTag("hlovet-code-input").performTextReplacement(recovery.lowercase())
            waitClosed()
            notice("双因素认证已关闭。").assertIsDisplayed()
            assertFalse(NativePrivacyApi.getOverview(session.accessToken).totpEnabled)
            val audit = NativePrivacyApi.listAudit(session.accessToken).map { it.action }
            assertTrue(audit.contains("totp_enabled"))
            assertTrue(audit.contains("totp_disabled"))
        }
    }

    private fun show() {
        contentSet = true
        compose.setContent { HlovetTheme { if (mounted.value) NativePrivacyScreen(session, {}) } }
        // Privacy is entered after the application's Compose host has started.
        compose.runOnIdle { mounted.value = true }
        step("privacy page initially loads the real account") {
            try { waitText("账号凭据") }
            catch (error: Throwable) {
                runCatching {
                    val directory = reportDirectory()
                    File(directory, "$scenario-initial-view.txt").writeText(
                        compose.onAllNodes(isRoot()).printToString(maxDepth = 20) +
                            "\nRequests:\n" + requests.joinToString("\n"))
                    File(directory, "$scenario-initial-threads.txt").writeText(
                        Thread.getAllStackTraces().entries
                            .filter { (thread, stack) -> thread.name == "main" || thread.name.startsWith("DefaultDispatcher") ||
                                stack.any { it.className.startsWith("xyz.hlovet.portal") ||
                                it.className.contains("HttpURLConnection") } }
                            .joinToString("\n\n") { (thread, stack) ->
                                "${thread.name}: ${thread.state}\n" + stack.joinToString("\n")
                            })
                    val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    try {
                        File(directory, "$scenario-initial-view.png").outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { image.recycle() }
                }
                throw error
            }
        }
    }

    private fun root() = compose.onNodeWithTag("privacy-list")
    private fun action(label: String) {
        val matcher = hasText(label) and hasClickAction()
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) root().performScrollToNode(matcher)
        compose.onNode(matcher).assertIsDisplayed().performClick()
    }
    private fun field(label: String): SemanticsNodeInteraction {
        val matcher = hasText(label) and hasSetTextAction()
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) root().performScrollToNode(matcher)
        return compose.onNode(matcher).assertIsDisplayed()
    }
    private fun waitText(label: String) = compose.waitUntil(20_000) {
        compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitClosed() = compose.waitUntil(20_000) {
        compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
    }
    private fun notice(label: String, substring: Boolean = false): SemanticsNodeInteraction {
        val matcher = hasTestTag("privacy-notice") and hasText(label, substring = substring)
        compose.waitUntil(20_000) {
            runCatching { root().performScrollToNode(matcher); compose.onNode(matcher).fetchSemanticsNode(); true }.getOrDefault(false)
        }
        return compose.onNode(matcher)
    }
    private fun assertSixCells() {
        repeat(6) { compose.onAllNodesWithTag("hlovet-code-cell-$it").assertCountEquals(1) }
    }
    private fun step(label: String, block: () -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            block()
            results.put(JSONObject().put("label", label).put("passed", true)
                .put("durationMillis", SystemClock.elapsedRealtime() - startedAt))
        } catch (error: Throwable) {
            results.put(JSONObject().put("label", label).put("passed", false)
                .put("durationMillis", SystemClock.elapsedRealtime() - startedAt).put("failureType", error.javaClass.simpleName))
            throw error
        }
    }
    private fun writeReport() {
        if (scenario.isBlank()) return
        val directory = reportDirectory()
        File(directory, "$scenario.json").writeText(JSONObject().put("origin", BuildConfig.API_BASE_URL)
            .put("fixtureId", session.userId).put("fixtureUsername", session.username).put("fixtureRetained", true)
            .put("scope", "Real Compose privacy screen and local APIs; not MainActivity routing or HTTPS login challenges")
            .put("results", results).toString(2))
    }
    private fun reportDirectory(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)).absolutePath
        return File(path, "local-privacy-ui").apply { mkdirs() }
    }
}
