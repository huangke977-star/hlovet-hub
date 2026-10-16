package xyz.hlovet.portal.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real writes only create and update newly registered accounts on the local Docker API. */
@RunWith(AndroidJUnit4::class)
class NativeLocalAccountApiTest {
    private val results = JSONArray()
    private var fixtureId = 0
    private var fixtureUsername = ""
    private var scenario = ""

    @Before
    fun requireLocalApiAndExistingPolicy() {
        assumeTrue("Requires an explicitly local API build", BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        val policy = NativeAuthApi.registrationPolicy()
        assumeTrue("Does not modify registration or verification settings", policy.registrationOpen &&
            !policy.emailVerificationEnabled && !policy.turnstileEnabled && !policy.loginTurnstileEnabled)
        val securityPolicy = nativeLocalSecurityPolicy()
        assumeTrue("Does not send real emails", !securityPolicy.optBoolean("mailServiceEnabled") &&
            !securityPolicy.optBoolean("untrustedDeviceEmailVerificationEnabled"))
    }

    @Test
    fun registrationProfileCredentialChangesAndLogoutUseRealLocalApi() {
        scenario = "account-credentials"
        val password = "Native_${UUID.randomUUID()}_9!"
        val session = register(password)
        var account = step("real password login") { authenticated(NativeAuthApi.login(fixtureUsername, password)) }
        assertEquals(session.userId, account.userId)
        val refreshed = step("refresh token rotation") { NativeAuthApi.refresh(account.refreshToken) }
        assertEquals(session.userId, refreshed.userId)
        account = refreshed
        step("account and privacy read") {
            assertEquals(fixtureUsername, NativeProfileApi.loadAccount(account.accessToken).username)
            assertEquals(session.email, NativePrivacyApi.getOverview(account.accessToken).email)
        }
        step("profile write and persisted reload") {
            val nickname = "Android API checked"
            val bio = "Retained local fixture for registration, profile and privacy validation."
            NativeProfileApi.updateProfile(account.accessToken, nickname, session.email, bio)
            val saved = NativeProfileApi.loadAccount(account.accessToken)
            assertEquals(nickname, saved.nickname)
            assertEquals(bio, saved.profileBio)
        }
        val updatedUsername = fixtureUsername + "_u"
        step("username write and persisted reload") {
            NativeProfileApi.updateUsername(account.accessToken, updatedUsername)
            assertEquals(updatedUsername, NativeProfileApi.loadAccount(account.accessToken).username)
            fixtureUsername = updatedUsername
        }
        step("locale write and persisted reload") {
            NativeProfileApi.updateLocale(account.accessToken, "en-US")
            assertEquals("en-US", NativeProfileApi.loadAccount(account.accessToken).locale)
            NativeProfileApi.updateLocale(account.accessToken, "zh-CN")
            assertEquals("zh-CN", NativeProfileApi.loadAccount(account.accessToken).locale)
        }
        step("security preferences honor unavailable mail service") {
            val saved = NativePrivacyApi.updateSecurityPreferences(account.accessToken, true, true, true)
            assertTrue(saved.loginAlertsEnabled)
            assertFalse(saved.emailAlertsEnabled)
            val reloaded = NativePrivacyApi.getSecurity(account.accessToken)
            assertTrue(reloaded.loginAlertsEnabled)
            assertFalse(reloaded.emailAlertsEnabled)
            assertTrue(reloaded.newDeviceAlertsEnabled)
        }
        step("wrong secondary password is rejected") {
            rejected("current password is incorrect") { NativePrivacyApi.verifySensitivePassword(account.accessToken, "email_change", "Wrong_${UUID.randomUUID()}") }
        }
        val email = "$updatedUsername@android-fixture.invalid"
        step("email change without verified grant is rejected") {
            rejected("security verification has expired") { NativePrivacyApi.changeEmail(account.accessToken, "invalid_grant_" + "a".repeat(32), email) }
            assertEquals(session.email, NativeProfileApi.loadAccount(account.accessToken).email)
        }
        step("secondary password grant cannot authorize another action") {
            val grant = NativePrivacyApi.verifySensitivePassword(account.accessToken, "password_change", password)
            rejected("security verification does not match") { NativePrivacyApi.changeEmail(account.accessToken, grant, email) }
            assertEquals(session.email, NativeProfileApi.loadAccount(account.accessToken).email)
        }
        step("verified email change and persisted reload") {
            val grant = NativePrivacyApi.verifySensitivePassword(account.accessToken, "email_change", password)
            assertEquals(email, NativePrivacyApi.changeEmail(account.accessToken, grant, email))
            assertEquals(email, NativeProfileApi.loadAccount(account.accessToken).email)
            rejected("security verification has expired") { NativePrivacyApi.changeEmail(account.accessToken, grant, session.email) }
        }
        val newPassword = "Native_${UUID.randomUUID()}_Changed9!"
        step("verified password change") {
            val grant = NativePrivacyApi.verifySensitivePassword(account.accessToken, "password_change", password)
            NativePrivacyApi.changePassword(account.accessToken, grant, newPassword)
        }
        step("old password rejected and new password login succeeds") {
            rejected("Invalid credentials") { NativeAuthApi.login(fixtureUsername, password) }
            account = authenticated(NativeAuthApi.login(fixtureUsername, newPassword))
            assertEquals(session.userId, account.userId)
        }
        step("account session and privacy audit records exist") {
            assertTrue(NativeAuthApi.listSessions(account.accessToken).any { it.current })
            val actions = NativePrivacyApi.listAudit(account.accessToken).map { it.action }
            assertTrue(actions.contains("email_changed"))
            assertTrue(actions.contains("password_changed"))
        }
        step("logout revokes refresh token") {
            NativeAuthApi.logout(account.refreshToken)
            rejected("refresh token") { NativeAuthApi.refresh(account.refreshToken) }
        }
        writeReport()
    }

    @Test
    fun totpEnrollmentRecoveryAndDisableUseRealLocalApi() {
        assumeTrue("Requires verified server credential encryption; does not change the key",
            InstrumentationRegistry.getArguments().getString("localCredentialEncryptionConfigured") == "true")
        scenario = "account-totp"
        val password = "Native_${UUID.randomUUID()}_9!"
        val session = register(password)
        val access = session.accessToken
        step("TOTP is initially disabled") { assertFalse(NativePrivacyApi.getOverview(access).totpEnabled) }
        val enrollment = step("password verification permits TOTP enrollment") {
            val grant = NativePrivacyApi.verifySensitivePassword(access, "totp_enrollment", password)
            NativePrivacyApi.beginTotpEnrollment(access, grant)
        }
        assertTrue(enrollment.otpAuthUri.startsWith("otpauth://totp/"))
        val codes = step("TOTP confirmation generates six-character recovery codes") {
            NativePrivacyApi.confirmTotp(access, nativeLocalTotp(enrollment.secret)).also { values ->
                assertTrue(values.isNotEmpty())
                assertTrue(values.all { it.matches(Regex("[A-Z0-9]{6}")) })
                assertEquals(values.size, values.distinct().size)
                assertTrue(NativePrivacyApi.getOverview(access).totpEnabled)
            }
        }
        step("TOTP secondary verification issues an action grant") {
            val grant = NativePrivacyApi.verifySensitiveTotp(access, "email_change", nativeLocalTotp(enrollment.secret))
            assertTrue(grant.length >= 20)
        }
        step("alphanumeric recovery code authorizes only one use") {
            val code = requireNotNull(codes.firstOrNull { it.any(Char::isLetter) })
            val grant = NativePrivacyApi.verifySensitiveTotp(access, "email_change", code.lowercase())
            assertTrue(grant.length >= 20)
            rejected("authenticator code is incorrect") { NativePrivacyApi.verifySensitiveTotp(access, "email_change", code) }
        }
        step("wrong password cannot disable TOTP") {
            rejected("current password is incorrect") { NativePrivacyApi.disableTotpWithPassword(access, "Wrong_${UUID.randomUUID()}") }
            assertTrue(NativePrivacyApi.getOverview(access).totpEnabled)
        }
        step("password verification disables TOTP and updates audit") {
            NativePrivacyApi.disableTotpWithPassword(access, password)
            assertFalse(NativePrivacyApi.getOverview(access).totpEnabled)
            val actions = NativePrivacyApi.listAudit(access).map { it.action }
            assertTrue(actions.contains("totp_enabled"))
            assertTrue(actions.contains("totp_disabled"))
        }
        step("normal login works after TOTP removal") {
            val login = authenticated(NativeAuthApi.login(fixtureUsername, password))
            assertEquals(session.userId, login.userId)
            NativeAuthApi.logout(login.refreshToken)
            NativeAuthApi.logout(session.refreshToken)
        }
        writeReport()
    }

    private fun register(password: String): NativeSession = step("real registration creates retained local fixture") {
        fixtureUsername = "native_api_" + UUID.randomUUID().toString().replace("-", "").take(12)
        authenticated(NativeAuthApi.register(fixtureUsername, "Android API fixture",
            "$fixtureUsername@android-fixture.invalid", password, null, null)).also {
            fixtureId = it.userId
            assertTrue(fixtureId > 0)
            assertFalse(it.isAdministrator)
            assertFalse(it.isSuperAdmin)
        }
    }

    private fun authenticated(result: NativeAuthResult): NativeSession {
        assertTrue("Expected a real authenticated response", result is NativeAuthResult.Authenticated)
        return (result as NativeAuthResult.Authenticated).session
    }

    private fun rejected(messageFragment: String, block: () -> Any?) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("Expected server rejection", failure)
        assertTrue("Expected a server validation error, not a network failure",
            failure is IllegalStateException || failure is NativeAuthHttpException)
        assertTrue("Expected the specific server validation error",
            failure?.message.orEmpty().contains(messageFragment, ignoreCase = true))
    }

    private fun <T> step(label: String, block: () -> T): T = try {
        block().also { results.put(JSONObject().put("label", label).put("passed", true)) }
    } catch (failure: Throwable) {
        results.put(JSONObject().put("label", label).put("passed", false).put("failureType", failure.javaClass.simpleName))
        writeReport()
        throw failure
    }

    private fun writeReport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)).absolutePath
        val directory = File(path, "local-account-api").apply { mkdirs() }
        File(directory, "$scenario.json").writeText(JSONObject().put("origin", BuildConfig.API_BASE_URL)
            .put("fixtureId", fixtureId).put("fixtureUsername", fixtureUsername).put("fixtureRetained", true)
            .put("scope", "Real native API clients; not Compose UI acceptance or new-device trust verification")
            .put("results", results).toString(2))
    }

}
