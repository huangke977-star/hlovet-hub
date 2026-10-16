package xyz.hlovet.portal.prototype

import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal fun nativeLocalSecurityPolicy(): JSONObject {
    check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
    val connection = URI(BuildConfig.API_BASE_URL + "/auth/security-policy").toURL().openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        check(connection.responseCode == 200)
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
    }
}

internal fun nativeLocalTotp(secret: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val bytes = ArrayList<Byte>()
    var bits = 0
    var accumulator = 0
    secret.uppercase().trimEnd('=').forEach { character ->
        val value = alphabet.indexOf(character)
        require(value >= 0)
        accumulator = (accumulator shl 5) or value
        bits += 5
        if (bits >= 8) {
            bits -= 8
            bytes.add((accumulator shr bits).toByte())
        }
    }
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(bytes.toByteArray(), "HmacSHA1"))
    val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 30_000).array())
    val offset = digest.last().toInt() and 15
    val value = ByteBuffer.wrap(digest, offset, 4).int and 0x7fffffff
    return (value % 1_000_000).toString().padStart(6, '0')
}
