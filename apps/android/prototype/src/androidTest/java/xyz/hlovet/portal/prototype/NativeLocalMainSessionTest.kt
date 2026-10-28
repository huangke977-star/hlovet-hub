package xyz.hlovet.portal.prototype

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

/** 通过真实登录创建并保留本地测试账号，核对加密会话及页面重建后的恢复，不接触生产账号。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalMainSessionTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private var activity: ActivityScenario<MainActivity>? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocale: String? = null
    private var username = ""
    private var userId = 0
    private val checks = JSONArray()

    /** 检查本地来源、空会话与注册策略；跳过时仅报告公开布尔开关，避免把未执行当作通过。 */
    @Before fun requireUnusedLocalOriginAndPolicy() {
        // 仅在本地来源且没有现存会话时创建账号，不能覆盖其它专项保留的加密登录态。
        assumeTrue("Requires the exact real local API", BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        val file = NativeSessionStore.sessionFile(context)
        assumeTrue("Never overwrites an existing local-origin session",
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none { it.exists() })
        val policy = NativeAuthApi.registrationPolicy()
        // 报告准确的设备侧公开策略，以区分注册关闭、人机验证或邮箱验证造成的前置条件跳过。
        assumeTrue("本地注册策略不满足：open=${policy.registrationOpen}, email=${policy.emailVerificationEnabled}, registrationTurnstile=${policy.turnstileEnabled}, loginTurnstile=${policy.loginTurnstileEnabled}", policy.registrationOpen &&
            !policy.emailVerificationEnabled && !policy.turnstileEnabled && !policy.loginTurnstileEnabled)
        val security = nativeLocalSecurityPolicy()
        assumeTrue("Does not send real email", !security.optBoolean("mailServiceEnabled") &&
            !security.optBoolean("untrustedDeviceEmailVerificationEnabled"))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    @After fun stop() {
        try { activity?.close() }
        finally {
            if (userId > 0) writeReport()
            oldLocale?.let { locale ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) }
            }
        }
    }

    @Test fun realUiLoginPersistsEncryptedSessionAndSurvivesActivityRecreation() {
        username = "native_restore_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val password = "Native_${UUID.randomUUID()}_9!"
        val registration = NativeAuthApi.register(username, "Android session fixture",
            "$username@android-fixture.invalid", password, null, null)
        assertTrue(registration is NativeAuthResult.Authenticated)
        val registered = (registration as NativeAuthResult.Authenticated).session
        userId = registered.userId
        NativeAuthApi.logout(registered.refreshToken)
        record("registered retained fixture; registration session logged out")

        activity = ActivityScenario.launch(MainActivity::class.java)
        waitTag("native-navigation-4")
        compose.onNodeWithTag("native-navigation-4").performClick()
        waitTag("native-profile-login")
        compose.onNodeWithTag("native-profile-login").performClick()
        waitTag("native-login-account")
        compose.onNodeWithTag("native-login-account").performTextReplacement(username)
        compose.onNodeWithTag("native-login-password").performTextReplacement(password)
        compose.onNodeWithTag("native-login-submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { NativeSessionStore.load(context)?.userId == userId }
        record("password sign-in through real MainActivity persisted encrypted session")
        completeFixtureOnboarding()
        compose.onNodeWithTag("native-navigation-4").performClick()
        waitProfile()
        record("authenticated profile displays the newly registered username")

        val file = NativeSessionStore.sessionFile(context)
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        val ciphertext = file.readText()
        val session = requireNotNull(NativeSessionStore.load(context))
        assertEquals(userId, session.userId)
        assertEquals(username, session.username)
        assertFalse(ciphertext.contains(username))
        assertFalse(ciphertext.contains(password))
        assertFalse(ciphertext.contains(session.accessToken))
        record("stored file is encrypted and outside Android backup")

        requireNotNull(activity).recreate()
        waitTag("native-navigation-4")
        compose.onNodeWithTag("native-navigation-4").performClick()
        waitProfile()
        compose.onNodeWithTag("native-profile-login").assertDoesNotExist()
        compose.onNodeWithTag("native-login-account").assertDoesNotExist()
        assertEquals(userId, requireNotNull(NativeSessionStore.load(context)).userId)
        record("Activity recreation restores authenticated profile without a login form")
    }

    private fun completeFixtureOnboarding() {
        waitTag("native-onboarding-skip")
        compose.onNodeWithTag("native-onboarding-skip").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("native-onboarding-skip").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(NativeDiscoveryApi.getOnboarding(requireNotNull(NativeSessionStore.load(context)).accessToken).completed)
        record("interest onboarding skip persisted for the new fixture")
    }

    private fun waitProfile() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("native-profile-username") and hasText("@$username", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-profile-username", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun record(check: String) { checks.put(JSONObject().put("check", check).put("passed", true)); writeReport() }

    private fun writeReport() {
        val report = JSONObject()
            .put("apiOrigin", BuildConfig.API_BASE_URL).put("fixtureId", userId).put("fixtureUsername", username)
            .put("checks", checks).put("retainedForColdLaunch", NativeSessionStore.load(context)?.userId == userId)
            .toString(2)
        File(context.filesDir, "native-main-session-evidence.json").writeText(report)
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { path ->
            File(path).mkdirs()
            File(path, "native-main-session-evidence.json").writeText(report)
        }
    }
}
