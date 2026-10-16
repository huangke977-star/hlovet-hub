package xyz.hlovet.portal.prototype

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTrustedDeviceCookieTest {
    private val api = URI("https://portal.example/api")
    private val token = "a".repeat(43)
    private var clock = 1_000_000L
    private val persistence = MemoryPersistence()

    @Test
    fun originNormalizesHostCaseAndDefaultPortWithoutIncludingPath() {
        assertEquals("https://portal.example:443/", nativeDeviceCookieOrigin(URI("HTTPS://PORTAL.EXAMPLE/api?x=1")))
        assertEquals(nativeDeviceCookieOrigin(api), nativeDeviceCookieOrigin(URI("https://portal.example:443/other")))
        assertEquals("http://localhost:3001/", nativeDeviceCookieOrigin(URI("http://localhost:3001/api")))
        assertNotNull(nativeDeviceCookieOrigin(URI("https://[::1]:443/api")))
    }

    @Test
    fun invalidOriginsDoNotAcceptCredentials() {
        listOf("file:///api", "ftp://portal.example/api", "/api", "https://user@portal.example/api",
            "https://portal.example:0/api", "https://portal.example:65536/api").forEach {
            assertNull(it, nativeDeviceCookieOrigin(URI(it)))
        }
    }

    @Test
    fun persistentServerCookieSurvivesNewHandlerAndAllApiPaths() {
        accept(handler(), "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax; Secure; Max-Age=31536000")
        assertEquals(token, persistence.values.values.single().value)
        val restored = handler()
        assertEquals(cookieHeader(), read(restored, URI("https://portal.example/api/auth/login")))
        assertEquals(cookieHeader(), read(restored, URI("https://portal.example/api/auth/google/start?native=android")))
    }

    @Test
    fun cookieCannotLeakAcrossSchemeHostOrPort() {
        val handler = handler()
        accept(handler)
        listOf("http://portal.example/api", "https://other.example/api", "https://portal.example:444/api",
            "https://sub.portal.example/api", "https://portal.example.evil/api").forEach {
            assertTrue(it, handler.get(URI(it), emptyMap()).isEmpty())
        }
    }

    @Test
    fun responseFromAnotherOriginCannotRotateOrDeleteCookie() {
        val handler = handler()
        accept(handler)
        handler.put(URI("https://other.example/api"), mapOf("Set-Cookie" to listOf("$NATIVE_TRUSTED_DEVICE_COOKIE=; Max-Age=0")))
        assertEquals(cookieHeader(), read(handler))
    }

    @Test
    fun expiredCookieIsDeletedFromMemoryAndPersistence() {
        val handler = handler()
        accept(handler, "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Path=/; Max-Age=2")
        clock += 2_000
        assertNull(read(handler))
        assertTrue(persistence.values.isEmpty())
        assertNull(read(handler()))
    }

    @Test
    fun alreadyExpiredPersistedCookieIsNotRestored() {
        persistence.values[origin()] = NativeTrustedDeviceCredential(token, clock, true)
        assertNull(read(handler()))
        assertTrue(persistence.values.isEmpty())
    }

    @Test
    fun maxAgeIsCappedToOneYear() {
        accept(handler(), "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Max-Age=9223372036854775807")
        assertEquals(clock + 31_536_000_000L, persistence.values.values.single().expiresAtMillis)
    }

    @Test
    fun serverDeletionRemovesPersistedCredential() {
        val handler = handler()
        accept(handler)
        accept(handler, "$NATIVE_TRUSTED_DEVICE_COOKIE=; Path=/; Max-Age=0")
        assertNull(read(handler))
        assertTrue(persistence.values.isEmpty())
        assertNull(read(handler()))
    }

    @Test
    fun rotationUsesOnlyNewestCookieAfterRestart() {
        val handler = handler()
        accept(handler)
        val replacement = "b".repeat(43)
        accept(handler, "$NATIVE_TRUSTED_DEVICE_COOKIE=$replacement; Max-Age=60")
        assertEquals("$NATIVE_TRUSTED_DEVICE_COOKIE=$replacement", read(handler()))
    }

    @Test
    fun invalidCookieValuesCannotReplaceValidCredential() {
        val handler = handler()
        accept(handler)
        listOf("short", "x".repeat(129), "invalid%20value", "quoted:invalid").forEach {
            accept(handler, "$NATIVE_TRUSTED_DEVICE_COOKIE=$it; Path=/; Max-Age=60")
            assertEquals(cookieHeader(), read(handler))
        }
    }

    @Test
    fun invalidPersistedTokenIsDiscarded() {
        persistence.values[origin()] = NativeTrustedDeviceCredential("invalid", clock + 60_000, true)
        assertNull(read(handler()))
        assertTrue(persistence.values.isEmpty())
    }

    @Test
    fun pathAndDomainRestrictionsAreEnforced() {
        val handler = handler()
        listOf("Path=/api", "Domain=example", "Domain=other.example", "Domain=sub.portal.example").forEach {
            accept(handler, "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; $it; Max-Age=60")
            assertNull(it, read(handler))
        }
        accept(handler, "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Path=/; Domain=.PORTAL.EXAMPLE; Max-Age=60")
        assertEquals(cookieHeader(), read(handler))
    }

    @Test
    fun sessionCookieReplacesOldPersistentCookieButDoesNotSurviveRestart() {
        val handler = handler()
        accept(handler)
        accept(handler, "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Path=/; Secure")
        assertEquals(cookieHeader(), read(handler))
        assertTrue(persistence.values.isEmpty())
        assertNull(read(handler()))
    }

    @Test
    fun secureCookieIsRejectedOnHttpOrigin() {
        val http = URI("http://localhost:3001/api")
        val handler = handler(http)
        accept(handler, "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Path=/; Secure; Max-Age=60", http)
        assertNull(read(handler, http))
        assertTrue(persistence.values.isEmpty())
    }

    @Test
    fun insecureCookieWorksOnlyOnConfiguredHttpOrigin() {
        val http = URI("http://localhost:3001/api")
        accept(handler(http), "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Path=/; Max-Age=60", http)
        assertEquals(cookieHeader(), read(handler(http), http))
        assertNull(read(handler(), api))
    }

    @Test
    fun securePersistedCookieCannotBeLoadedForHttp() {
        val http = URI("http://localhost:3001/api")
        persistence.values[requireNotNull(nativeDeviceCookieOrigin(http))] = NativeTrustedDeviceCredential(token, clock + 60_000, true)
        assertNull(read(handler(http), http))
        assertTrue(persistence.values.isEmpty())
    }

    @Test
    fun unrelatedAndMalformedHeadersAreIgnoredWithoutDroppingTrust() {
        val handler = handler()
        accept(handler)
        handler.put(api, mapOf("X-Other" to listOf("$NATIVE_TRUSTED_DEVICE_COOKIE=; Max-Age=0"),
            "Set-Cookie" to listOf("analytics=tracking; Max-Age=60", "malformed")))
        assertEquals(cookieHeader(), read(handler))
    }

    @Test
    fun caseInsensitiveHeaderAndMultipleCookiesAreSupported() {
        val handler = handler()
        handler.put(api, mapOf("set-cookie" to listOf("other=x; Path=/", "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Max-Age=60")))
        assertEquals(cookieHeader(), read(handler))
    }

    private fun origin() = requireNotNull(nativeDeviceCookieOrigin(api))
    private fun handler(uri: URI = api) = NativeTrustedDeviceCookieHandler(uri, persistence) { clock }
    private fun accept(handler: NativeTrustedDeviceCookieHandler,
        header: String = "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Path=/; Secure; Max-Age=60", uri: URI = api) {
        handler.put(uri, mapOf("Set-Cookie" to listOf(header)))
    }
    private fun read(handler: NativeTrustedDeviceCookieHandler, uri: URI = api) = handler.get(uri, emptyMap())["Cookie"]?.single()
    private fun cookieHeader() = "$NATIVE_TRUSTED_DEVICE_COOKIE=$token"

    private class MemoryPersistence : NativeTrustedDevicePersistence {
        val values = mutableMapOf<String, NativeTrustedDeviceCredential>()
        override fun load(origin: String) = values[origin]
        override fun save(origin: String, credential: NativeTrustedDeviceCredential?) {
            if (credential == null) values.remove(origin) else values[origin] = credential
        }
    }
}
