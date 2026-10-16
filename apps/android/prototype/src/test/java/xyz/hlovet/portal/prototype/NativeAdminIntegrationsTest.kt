package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAdminIntegrationsTest {
    private val config = NativeAdminGoogleOAuthConfig(true, true, "fixture-client", true, "https://example.invalid/callback", "database", true, "")

    @Test fun googleRequiresEncryptionAndEnabledCredentials() {
        assertNull(nativeGoogleOAuthDraftError(config, config.toDraft(), ""))
        assertNotNull(nativeGoogleOAuthDraftError(config.copy(encryptionConfigured = false), config.toDraft(), ""))
        assertNotNull(nativeGoogleOAuthDraftError(config.copy(clientSecretConfigured = false), config.toDraft(), ""))
        assertNotNull(nativeGoogleOAuthDraftError(config, config.toDraft().copy(clientId = ""), ""))
        assertNotNull(nativeGoogleOAuthDraftError(config, config.toDraft().copy(redirectUri = ""), ""))
        assertNull(nativeGoogleOAuthDraftError(config.copy(clientSecretConfigured = false), config.toDraft(), "fixture-new-secret"))
        assertNull(nativeGoogleOAuthDraftError(config, NativeAdminGoogleOAuthDraft(false, "", ""), ""))
    }

    @Test fun googleRedirectAllowsHttpsAndLocalhostOnlyWhenEnabled() {
        listOf("https://example.invalid/callback", "http://localhost:3000/callback").forEach { assertNull(nativeGoogleOAuthDraftError(config, config.toDraft().copy(redirectUri = it), "")) }
        listOf("http://example.invalid/callback", "javascript:alert(1)", "ftp://example.invalid/callback", "https://", "https://example.invalid:99999/path").forEach { assertNotNull(it, nativeGoogleOAuthDraftError(config, config.toDraft().copy(redirectUri = it), "")) }
    }

    @Test fun googleLengthLimitsRejectRatherThanTruncate() {
        assertNotNull(nativeGoogleOAuthDraftError(config, config.toDraft().copy(clientId = "x".repeat(256)), ""))
        assertNotNull(nativeGoogleOAuthDraftError(config, config.toDraft(), "x".repeat(501)))
        assertNotNull(nativeGoogleOAuthDraftError(config, config.toDraft().copy(redirectUri = "https://example.invalid/" + "x".repeat(512)), ""))
    }

    @Test fun webhookValidationMatchesServerLimitsAndEvents() {
        val events = listOf("integration.test")
        val secret = "x".repeat(16)
        assertNull(nativeWebhookDraftError("Fixture", "https://example.invalid/hook", secret, events))
        assertNotNull(nativeWebhookDraftError("", "https://example.invalid/hook", secret, events))
        assertNotNull(nativeWebhookDraftError("x".repeat(121), "https://example.invalid/hook", secret, events))
        listOf("http://localhost:3000/hook", "javascript:alert(1)", "https://", "https://example.invalid/" + "x".repeat(512)).forEach { assertNotNull(nativeWebhookDraftError("Fixture", it, secret, events)) }
        listOf(15, 256).forEach { assertNotNull(nativeWebhookDraftError("Fixture", "https://example.invalid/hook", "x".repeat(it), events)) }
        assertNotNull(nativeWebhookDraftError("Fixture", "https://example.invalid/hook", secret, emptyList()))
        assertNotNull(nativeWebhookDraftError("Fixture", "https://example.invalid/hook", secret, listOf("unsupported")))
        assertNull(nativeWebhookDraftError("x".repeat(120), "https://example.invalid/hook", "x".repeat(255), nativeIntegrationEvents))
    }

    @Test fun nullableFieldsDoNotBecomeLiteralNullOrExposeSecrets() {
        with(NativeAdminIntegrationsApi) {
            val google = JSONObject("""{"enabled":false,"encryptionConfigured":true,"clientId":null,"redirectUri":null,"clientSecretEncrypted":"not-for-client"}""").toGoogleOAuth()
            assertEquals("", google.clientId); assertEquals("", google.redirectUri)
            val webhook = JSONObject("""{"id":1,"name":null,"url":null,"lastError":null,"events":["integration.test",null,9]}""").toWebhook()
            assertEquals("", webhook.name); assertEquals("", webhook.lastError)
            assertEquals(listOf("integration.test"), webhook.events)
        }
    }

    @Test fun allOneHundredDeliveriesAndTimestampsAreParsed() {
        with(NativeAdminIntegrationsApi) {
            val array = JSONArray().apply { (1..100).forEach { put(JSONObject().put("id", it).put("endpointId", 1).put("nextAttemptAt", JSONObject.NULL).put("deliveredAt", "2026-10-13T01:00:00Z").put("lastError", JSONObject.NULL)) } }
            val records = array.toDeliveries()
            assertEquals(100, records.size); assertEquals(100, records.last().id)
            assertEquals("", records.last().nextAttemptAt); assertEquals("", records.last().lastError)
            assertEquals("2026-10-13T01:00:00Z", records.last().deliveredAt)
        }
    }

    @Test fun invalidConfigurationAndWebhookObjectsAreRejected() {
        with(NativeAdminIntegrationsApi) {
            assertThrows(IllegalStateException::class.java) { JSONObject().toGoogleOAuth() }
            assertThrows(IllegalStateException::class.java) { JSONObject().toWebhook() }
        }
    }
}
