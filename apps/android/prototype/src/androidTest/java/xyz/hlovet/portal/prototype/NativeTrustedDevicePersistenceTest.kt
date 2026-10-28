package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证可信设备 Cookie 的加密持久化、来源隔离和真实 HTTP 回环行为，不访问生产站点。 */
@RunWith(AndroidJUnit4::class)
class NativeTrustedDevicePersistenceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var app: Context
    private lateinit var preferencesPrefix: String
    private val preferences = mutableSetOf<String>()
    private var previousHandler: CookieHandler? = null
    private val uri = URI("https://portal.example/api")
    private val origin = requireNotNull(nativeDeviceCookieOrigin(uri))
    private val token = "fixture_device_token_" + "a".repeat(32)
    private val credential = NativeTrustedDeviceCredential(token, System.currentTimeMillis() + 60_000, true)

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext
        preferencesPrefix = "trusted-device-test-${UUID.randomUUID()}-"
        root = File(app.cacheDir, preferencesPrefix).apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = preferencesPrefix + name
                preferences.add(isolatedName)
                return app.getSharedPreferences(isolatedName, mode)
            }
        }
        previousHandler = CookieHandler.getDefault()
        CookieHandler.setDefault(null)
    }

    @After
    fun tearDown() {
        CookieHandler.setDefault(previousHandler)
        preferences.forEach { app.deleteSharedPreferences(it) }
        root.deleteRecursively()
    }

    @Test
    fun credentialRoundTripsThroughKeystoreEncryptionWithoutPlaintext() {
        val store = NativeTrustedDeviceStore(context)
        store.save(origin, credential)
        val file = store.credentialFile(origin)
        assertTrue(file.exists())
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertFalse(file.readText().contains(token))
        assertFalse(file.readText().contains("expiresAtMillis"))
        assertEquals(credential, NativeTrustedDeviceStore(context).load(origin))
    }

    @Test
    fun recreatingHandlerRestoresTrustAndClearingSessionDoesNotEraseIt() {
        val first = NativeTrustedDeviceCookieHandler(uri, NativeTrustedDeviceStore(context))
        first.put(uri, mapOf("Set-Cookie" to listOf(cookie(secure = true))))
        NativeSessionStore.save(context, NativeSession("fixture-access", "fixture-refresh", 1, "fixture", "Fixture",
            PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46)))
        assertEquals(1, NativeSessionStore.load(context)?.userId)
        NativeSessionStore.clear(context)
        assertNull(NativeSessionStore.load(context))
        val recreated = NativeTrustedDeviceCookieHandler(uri, NativeTrustedDeviceStore(context))
        assertEquals("$NATIVE_TRUSTED_DEVICE_COOKIE=$token", header(recreated, uri))
    }

    @Test
    fun corruptCiphertextIsDiscardedWithoutCrashing() {
        val store = NativeTrustedDeviceStore(context)
        store.save(origin, credential)
        store.credentialFile(origin).writeText("invalid.ciphertext")
        assertNull(store.load(origin))
        assertFalse(store.credentialFile(origin).exists())
        assertNull(header(NativeTrustedDeviceCookieHandler(uri, store), uri))
    }

    @Test
    fun encryptedRecordCannotBeMovedToAnotherOrigin() {
        val store = NativeTrustedDeviceStore(context)
        store.save(origin, credential)
        val otherOrigin = requireNotNull(nativeDeviceCookieOrigin(URI("https://other.example/api")))
        store.credentialFile(origin).copyTo(store.credentialFile(otherOrigin))
        assertNull(store.load(otherOrigin))
        assertFalse(store.credentialFile(otherOrigin).exists())
        assertEquals(credential, store.load(origin))
    }

    @Test
    fun expiryAndServerDeletionRemoveDiskState() {
        var clock = 1_000L
        val store = NativeTrustedDeviceStore(context)
        val handler = NativeTrustedDeviceCookieHandler(uri, store) { clock }
        handler.put(uri, mapOf("Set-Cookie" to listOf(cookie(secure = true, maxAge = 2))))
        clock += 2_000
        assertNull(header(handler, uri))
        assertNull(store.load(origin))
        handler.put(uri, mapOf("Set-Cookie" to listOf(cookie(secure = true))))
        handler.put(uri, mapOf("Set-Cookie" to listOf("$NATIVE_TRUSTED_DEVICE_COOKIE=; Path=/; Max-Age=0")))
        assertNull(store.load(origin))
        assertNull(header(NativeTrustedDeviceCookieHandler(uri, store) { clock }, uri))
    }

    @Test
    fun concurrentStoresCanSaveAndRestoreIndependentOrigins() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            (1..12).map { index -> executor.submit {
                val site = "https://site-$index.example:443/"
                val expected = credential.copy(value = "fixture_$index" + "b".repeat(32))
                NativeTrustedDeviceStore(context).save(site, expected)
                assertEquals(expected, NativeTrustedDeviceStore(context).load(site))
            } }.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun installerRetainsLiveHandlerAndIsolatesSwitchedServers() {
        NativeTrustedDeviceCookies.install(context, uri.toString())
        val original = CookieHandler.getDefault()
        original.put(uri, mapOf("Set-Cookie" to listOf(cookie(secure = true))))
        NativeTrustedDeviceCookies.install(context, "https://portal.example:443/other")
        assertSame(original, CookieHandler.getDefault())
        NativeTrustedDeviceCookies.install(context, "https://other.example/api")
        assertNull(header(CookieHandler.getDefault(), URI("https://other.example/api")))
        assertNull(header(CookieHandler.getDefault(), uri))
        NativeTrustedDeviceCookies.install(context, uri.toString())
        assertEquals("$NATIVE_TRUSTED_DEVICE_COOKIE=$token", header(CookieHandler.getDefault(), uri))
    }

    @Test
    fun realHttpCookieIsSentAfterHandlerRecreationAndNeverToAnotherPort() {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                val url = server.url("/api/auth/login")
                NativeTrustedDeviceCookies.install(context, url.toString())
                server.enqueue(MockResponse().addHeader("Set-Cookie", cookie(secure = false)).setBody("{}"))
                request(url.toString())
                assertNull(server.takeRequest().getHeader("Cookie"))
                CookieHandler.setDefault(null)
                NativeTrustedDeviceCookies.install(context, url.toString())
                server.enqueue(MockResponse().setBody("{}"))
                request(server.url("/api/auth/refresh").toString())
                assertEquals("$NATIVE_TRUSTED_DEVICE_COOKIE=$token", server.takeRequest().getHeader("Cookie"))
                other.enqueue(MockResponse().setBody("{}"))
                request(other.url("/api/auth/login").toString())
                assertNull(other.takeRequest().getHeader("Cookie"))
            }
        }
    }

    @Test
    fun realHttpDeletionPreventsResendingAfterRestart() {
        MockWebServer().use { server ->
            val url = server.url("/api/auth/login")
            NativeTrustedDeviceCookies.install(context, url.toString())
            server.enqueue(MockResponse().addHeader("Set-Cookie", cookie(secure = false)).setBody("{}"))
            request(url.toString())
            server.takeRequest()
            server.enqueue(MockResponse().addHeader("Set-Cookie", "$NATIVE_TRUSTED_DEVICE_COOKIE=; Path=/; Max-Age=0").setBody("{}"))
            request(url.toString())
            assertEquals("$NATIVE_TRUSTED_DEVICE_COOKIE=$token", server.takeRequest().getHeader("Cookie"))
            CookieHandler.setDefault(null)
            NativeTrustedDeviceCookies.install(context, url.toString())
            server.enqueue(MockResponse().setBody("{}"))
            request(url.toString())
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test
    fun realHttpSecureCookieIsNotAcceptedOverPlainHttp() {
        MockWebServer().use { server ->
            val url = server.url("/api/auth/login")
            NativeTrustedDeviceCookies.install(context, url.toString())
            server.enqueue(MockResponse().addHeader("Set-Cookie", cookie(secure = true)).setBody("{}"))
            request(url.toString())
            server.takeRequest()
            server.enqueue(MockResponse().setBody("{}"))
            request(url.toString())
            assertNull(server.takeRequest().getHeader("Cookie"))
            val site = requireNotNull(nativeDeviceCookieOrigin(url.toUri()))
            assertNull(NativeTrustedDeviceStore(context).load(site))
        }
    }

    @Test
    fun googleStyleRedirectCapturesCookieBeforeExternalNavigation() {
        MockWebServer().use { server ->
            val url = server.url("/api/auth/google/start?native=android")
            NativeTrustedDeviceCookies.install(context, url.toString())
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://accounts.google.com/fixture")
                .addHeader("Set-Cookie", cookie(secure = false)))
            assertEquals(302, request(url.toString()))
            server.takeRequest()
            CookieHandler.setDefault(null)
            NativeTrustedDeviceCookies.install(context, url.toString())
            server.enqueue(MockResponse().setBody("{}"))
            request(server.url("/api/auth/oauth/result").toString())
            assertEquals("$NATIVE_TRUSTED_DEVICE_COOKIE=$token", server.takeRequest().getHeader("Cookie"))
        }
    }

    private fun cookie(secure: Boolean, maxAge: Int = 60) =
        "$NATIVE_TRUSTED_DEVICE_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=$maxAge" + if (secure) "; Secure" else ""

    private fun header(handler: CookieHandler, target: URI) = handler.get(target, emptyMap())["Cookie"]?.single()

    private fun request(url: String): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val status = connection.responseCode
            connection.inputStream.use { it.readBytes() }
            status
        } finally {
            connection.disconnect()
        }
    }
}
