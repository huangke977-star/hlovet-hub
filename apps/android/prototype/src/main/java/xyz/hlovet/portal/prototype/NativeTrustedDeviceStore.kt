package xyz.hlovet.portal.prototype

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.CookieHandler
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object NativeTrustedDeviceCookies {
    @Synchronized
    fun install(context: Context, apiBaseUrl: String) {
        val uri = URI(apiBaseUrl)
        val current = CookieHandler.getDefault()
        if (current is NativeTrustedDeviceCookieHandler && current.origin == nativeDeviceCookieOrigin(uri)) return
        CookieHandler.setDefault(NativeTrustedDeviceCookieHandler(uri, NativeTrustedDeviceStore(context.applicationContext)))
    }
}

/** Keystore encryption and no-backup storage keep device trust on this installation. */
internal class NativeTrustedDeviceStore(context: Context) : NativeTrustedDevicePersistence {
    private val directory = File(context.noBackupFilesDir, "native-device-credentials")

    override fun load(origin: String): NativeTrustedDeviceCredential? = synchronized(STORAGE_LOCK) { runCatching {
        val file = AtomicFile(credentialFile(origin))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@runCatching null
        val parts = file.openRead().bufferedReader().use { it.readText() }.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
        val value = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        NativeTrustedDeviceCredential(value.getString("value"), value.getLong("expiresAtMillis"), value.getBoolean("secure"))
    }.getOrElse {
        AtomicFile(credentialFile(origin)).delete()
        null
    } }

    override fun save(origin: String, credential: NativeTrustedDeviceCredential?) = synchronized(STORAGE_LOCK) {
        val file = AtomicFile(credentialFile(origin))
        if (credential == null) {
            file.delete()
            return@synchronized
        }
        runCatching {
            val value = JSONObject().put("value", credential.value).put("expiresAtMillis", credential.expiresAtMillis)
                .put("secure", credential.secure).toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(cipher.doFinal(value), Base64.NO_WRAP)
            check(directory.exists() || directory.mkdirs())
            val stream = file.startWrite()
            try {
                stream.write(payload.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }.onFailure { file.delete() }
        Unit
    }

    internal fun credentialFile(origin: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".bin")
    }

    private fun key(): SecretKey = synchronized(STORAGE_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(false).build())
        generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "hlovet.native.trusted-device"
        val STORAGE_LOCK = Any()
    }
}
