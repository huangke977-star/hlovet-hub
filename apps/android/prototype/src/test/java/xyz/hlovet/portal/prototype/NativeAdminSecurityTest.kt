package xyz.hlovet.portal.prototype

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAdminSecurityTest {
    private fun config() = with(NativeAdminSecurityApi) { JSONObject("""{"smtpPort":587,"loginFailureTurnstileThreshold":3,"encryptionConfigured":true}""").toConfig() }

    @Test fun portRejectsInvalidRawValuesInsteadOfCoercingThem() {
        listOf("", "0", "-1", "65536", "587x", "1.5", " 587", "99999999999999999999").forEach {
            assertNotNull(it, nativeSecurityDraftError(config(), it, "3", "", ""))
        }
        listOf("1", "587", "65535").forEach { assertNull(nativeSecurityDraftError(config(), it, "3", "", "")) }
    }

    @Test fun thresholdRejectsOutOfRangeAndMixedInput() {
        listOf("", "0", "-3", "6", "3x", "99999999999999999999").forEach {
            assertNotNull(nativeSecurityDraftError(config(), "587", it, "", ""))
        }
        listOf("1", "5").forEach { assertNull(nativeSecurityDraftError(config(), "587", it, "", "")) }
    }

    @Test fun requiredCredentialsAndEncryptionAreCheckedWithoutDiscardingDraft() {
        val smtp = config().copy(smtpEnabled = true, smtpHost = "fixture.invalid", smtpUsername = "fixture", smtpFromEmail = "fixture@example.invalid")
        assertNotNull(nativeSecurityDraftError(smtp, "587", "3", "", ""))
        assertNull(nativeSecurityDraftError(smtp.copy(smtpPasswordConfigured = true), "587", "3", "", ""))
        assertNull(nativeSecurityDraftError(smtp, "587", "3", "fixture-secret", ""))
        assertNotNull(nativeSecurityDraftError(config().copy(encryptionConfigured = false), "587", "3", "fixture-secret", ""))
        assertNotNull(nativeSecurityDraftError(config().copy(turnstileLoginEnabled = true), "587", "3", "", "fixture-secret"))
        assertNull(nativeSecurityDraftError(config().copy(turnstileLoginEnabled = true, turnstileSiteKey = "fixture-site", turnstileSecretConfigured = true), "587", "3", "", ""))
    }

    @Test fun nullableConfigurationAndRecordsNeverDisplayLiteralNullOrSecrets() {
        with(NativeAdminSecurityApi) {
            val config = JSONObject("""{"smtpHost":null,"smtpFromEmail":null,"turnstileSiteKey":null,"smtpPasswordEncrypted":"not-for-client"}""").toConfig()
            assertEquals("", config.smtpHost)
            assertEquals("", config.smtpFromEmail)
            assertEquals("", config.turnstileSiteKey)
            val page = JSONObject("""{"items":[{"id":9,"lastError":null,"sentAt":null,"verifiedAt":null,"user":{"nickname":null,"username":null}},null,"invalid"],"total":1,"page":2,"totalPages":3}""").toRecordPage()
            assertEquals(1, page.items.size)
            assertEquals("", page.items.single().lastError)
            assertEquals("", page.items.single().sentAt)
            assertEquals("", page.items.single().userNickname)
            assertEquals(2, page.page)
        }
    }

    @Test fun updateOmitsBlankSecretsAndUnsetSenderWithoutTouchingGoogleSettings() {
        val update = NativeSecurityAdminUpdate(false, "", 587, false, "", "Fixture", "", false, false, false, "", false, false, false, 3)
        with(NativeAdminSecurityApi) {
            val body = update.toJson()
            assertFalse(body.has("smtpPassword"))
            assertFalse(body.has("turnstileSecret"))
            assertFalse(body.has("smtpFromEmail"))
            assertFalse(body.keys().asSequence().any { it.startsWith("google") })
            val withSecrets = update.copy(smtpPassword = "fixture-smtp", turnstileSecret = "fixture-turnstile", smtpFromEmail = "fixture@example.invalid").toJson()
            assertEquals("fixture-smtp", withSecrets.getString("smtpPassword"))
            assertEquals("fixture-turnstile", withSecrets.getString("turnstileSecret"))
            assertEquals("fixture@example.invalid", withSecrets.getString("smtpFromEmail"))
        }
    }
}
