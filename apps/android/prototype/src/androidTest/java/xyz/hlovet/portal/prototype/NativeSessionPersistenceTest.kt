package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** All credentials and files belong to an isolated fixture context, never the installed account. */
@RunWith(AndroidJUnit4::class)
class NativeSessionPersistenceTest {
    private lateinit var app: Context
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var prefix: String
    private val preferences = mutableSetOf<String>()
    private val firstOrigin = "https://session.example/api"
    private val secondOrigin = "https://other-session.example/api"
    private val fixture = NativeSession(
        accessToken = "fixture-access-only", refreshToken = "fixture-refresh-only", userId = 19,
        username = "fixture-user", nickname = "Fixture user", email = "fixture@example.invalid",
        profileBio = "Retained profile", avatarUrl = "https://session.example/avatar.png", locale = "en-US",
        isSuperAdmin = true, isAdministrator = true,
        appearance = PreviewAppearance("custom", "#237B65", "#102030", "#405060", "#FFFFFF", 62, 24, "#EDF8FF", 35),
    )

    @Before fun prepare() {
        app = InstrumentationRegistry.getInstrumentation().targetContext
        prefix = "session-origin-test-${UUID.randomUUID()}-"
        root = File(app.cacheDir, prefix).apply { mkdirs() }
        context = isolatedContext()
    }

    @After fun stop() {
        preferences.forEach { app.deleteSharedPreferences(it) }
        root.deleteRecursively()
    }

    @Test fun allSessionAndAppearanceFieldsRoundTripWithANewContext() {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        assertEquals(fixture, NativeSessionStore.load(isolatedContext(), firstOrigin))
    }

    @Test fun ciphertextIsKeptOutsideBackupAndContainsNoPlaintextCredentials() {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        val file = NativeSessionStore.sessionFile(context, firstOrigin)
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        val payload = file.readText()
        listOf(fixture.accessToken, fixture.refreshToken, fixture.username, fixture.email, firstOrigin).forEach {
            assertFalse(payload.contains(it))
        }
        assertFalse(file.name.contains("session.example"))
    }

    @Test fun changingServersDoesNotRestoreOrOverwriteTheOriginalSession() {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        assertNull(NativeSessionStore.load(context, secondOrigin))
        val second = fixture.copy(userId = 23, username = "other-user", accessToken = "other-access")
        assertTrue(NativeSessionStore.save(context, second, secondOrigin))
        assertEquals(second, NativeSessionStore.load(context, secondOrigin))
        assertEquals(fixture, NativeSessionStore.load(context, firstOrigin))
    }

    @Test fun protocolAndPortAreIndependentOrigins() {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        assertNull(NativeSessionStore.load(context, "http://session.example/api"))
        assertNull(NativeSessionStore.load(context, "https://session.example:8443/api"))
        assertEquals(fixture, NativeSessionStore.load(context, "https://SESSION.example:443/other-api/"))
    }

    @Test fun copyingCiphertextToAnotherOriginCannotAuthenticateIt() {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        val original = NativeSessionStore.sessionFile(context, firstOrigin)
        val destination = NativeSessionStore.sessionFile(context, secondOrigin)
        original.copyTo(destination, overwrite = true)
        assertNull(NativeSessionStore.load(context, secondOrigin))
        assertTrue(destination.exists())
        assertEquals(fixture, NativeSessionStore.load(context, firstOrigin))
    }

    @Test fun changingOneByteOfCiphertextCannotRestoreAnAccount() {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        val file = NativeSessionStore.sessionFile(context, firstOrigin)
        val parts = file.readText().split('.', limit = 2)
        val bytes = Base64.decode(parts[1], Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        file.writeText(parts[0] + "." + Base64.encodeToString(bytes, Base64.NO_WRAP))
        assertNull(NativeSessionStore.load(context, firstOrigin))
        assertTrue(file.exists())
    }

    @Test fun clearingOneOriginPreservesTheOtherOriginAndDeviceTrust() {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        assertTrue(NativeSessionStore.save(context, fixture.copy(userId = 23), secondOrigin))
        val origin = requireNotNull(nativeDeviceCookieOrigin(java.net.URI(firstOrigin)))
        val device = NativeTrustedDeviceCredential("fixture_device_" + "a".repeat(32), System.currentTimeMillis() + 60_000, true)
        NativeTrustedDeviceStore(context).save(origin, device)
        NativeSessionStore.clear(context, firstOrigin)
        assertNull(NativeSessionStore.load(context, firstOrigin))
        assertEquals(23, NativeSessionStore.load(context, secondOrigin)?.userId)
        assertEquals(device, NativeTrustedDeviceStore(context).load(origin))
    }

    @Test fun validLegacyCiphertextIsNeverAutomaticallyRestoredOrDeleted() {
        val legacy = saveLegacySession()
        assertNull(NativeSessionStore.load(context, firstOrigin))
        assertNull(NativeSessionStore.load(context, secondOrigin))
        assertEquals(legacy, legacyPreferences().getString("payload", null))
        assertTrue(NativeSessionStore.consumeLegacyReauthenticationNotice(context, firstOrigin))
        assertFalse(NativeSessionStore.consumeLegacyReauthenticationNotice(isolatedContext(), firstOrigin))
        assertFalse(NativeSessionStore.consumeLegacyReauthenticationNotice(context, secondOrigin))
        assertEquals(legacy, legacyPreferences().getString("payload", null))
    }

    @Test fun newLoginDoesNotDeleteLegacyRecordOrShowLegacyNoticeForAValidSession() {
        val legacy = saveLegacySession()
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        assertEquals(fixture, NativeSessionStore.load(context, firstOrigin))
        assertFalse(NativeSessionStore.consumeLegacyReauthenticationNotice(context, firstOrigin))
        assertEquals(legacy, legacyPreferences().getString("payload", null))
    }

    @Test fun corruptRecordFailsClosedWithoutDeletingUnrelatedData() {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        val file = NativeSessionStore.sessionFile(context, firstOrigin)
        file.writeText("invalid-record")
        assertNull(NativeSessionStore.load(context, firstOrigin))
        assertTrue(file.exists())
        assertTrue(NativeSessionStore.save(context, fixture, secondOrigin))
        assertEquals(fixture, NativeSessionStore.load(context, secondOrigin))
    }

    @Test fun invalidOriginsDoNotSaveLoadOrClearExistingSessions() {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        listOf("", "not a url", "file:///private", "https://user:password@session.example/api", "https://session.example:70000/api").forEach {
            assertFalse(NativeSessionStore.save(context, fixture, it))
            assertNull(NativeSessionStore.load(context, it))
            NativeSessionStore.clear(context, it)
        }
        assertEquals(fixture, NativeSessionStore.load(context, firstOrigin))
    }

    @Test fun parallelOriginsCanBeSavedAndReloadedWithoutKeystoreRaces() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 12).map { index ->
                executor.submit<Boolean> {
                    NativeSessionStore.save(context, fixture.copy(userId = index + 1), "https://session-$index.example/api")
                }
            }
            jobs.forEach { assertTrue(it.get(15, TimeUnit.SECONDS)) }
            repeat(12) { index ->
                assertEquals(index + 1, NativeSessionStore.load(context, "https://session-$index.example/api")?.userId)
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun storageFailureIsReportedInsteadOfPretendingTheLoginWasSaved() {
        val blocked = File(root, "blocked-no-backup").apply { writeText("not a directory") }
        val brokenContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = blocked }
        assertFalse(NativeSessionStore.save(brokenContext, fixture, firstOrigin))
        assertNull(NativeSessionStore.load(brokenContext, firstOrigin))
        assertEquals("not a directory", blocked.readText())
    }

    private fun isolatedContext(): Context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            preferences.add(isolatedName)
            return app.getSharedPreferences(isolatedName, mode)
        }
    }

    private fun legacyPreferences() = context.getSharedPreferences("native-session", Context.MODE_PRIVATE)

    private fun saveLegacySession(): String {
        assertTrue(NativeSessionStore.save(context, fixture, firstOrigin))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("hlovet.native.session", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val value = JSONObject().put("accessToken", fixture.accessToken).put("refreshToken", fixture.refreshToken)
            .put("username", fixture.username).toString().toByteArray(Charsets.UTF_8)
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(value), Base64.NO_WRAP)
        assertTrue(legacyPreferences().edit().putString("payload", payload).commit())
        NativeSessionStore.clear(context, firstOrigin)
        return payload
    }
}
