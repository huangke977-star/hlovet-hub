package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeAndroidRelease(
    val id: Int,
    val versionName: String,
    val versionCode: Int,
    val channel: String,
    val originalName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val apkUrl: String,
    val releaseNotes: List<String>,
    val isActive: Boolean,
    val uploadedBy: String,
    val createdAt: String,
    val updatedAt: String,
)

internal object NativeAdminAndroidApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    /** Public install pages can read the active package without an administrator session. */
    fun latest(): NativeAndroidRelease? {
        val response = request("/android-releases/latest", null) as JSONObject
        return response.optJSONObject("release")?.toRelease()
    }

    fun list(accessToken: String): List<NativeAndroidRelease> = request("/android-releases", accessToken).toReleases()

    fun upload(accessToken: String, file: NativeUploadFile, versionName: String, versionCode: Int, channel: String, notes: String, activate: Boolean): NativeAndroidRelease {
        val boundary = "----HlovetAndroidRelease${System.currentTimeMillis()}"
        val connection = (URL(baseUrl + "/android-releases").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                writePart(output, boundary, "versionName", versionName)
                writePart(output, boundary, "versionCode", versionCode.toString())
                writePart(output, boundary, "channel", channel.ifBlank { "stable" })
                writePart(output, boundary, "releaseNotes", notes)
                writePart(output, boundary, "activate", activate.toString())
                val safeName = file.name.replace("\"", "").replace("\r", "").replace("\n", "")
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray())
                output.write("Content-Type: application/vnd.android.package-archive\r\n\r\n".toByteArray())
                output.write(file.bytes)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            parseObjectResponse(connection, "安装包上传失败") .toRelease()
        } finally {
            connection.disconnect()
        }
    }

    fun activate(accessToken: String, id: Int): NativeAndroidRelease = request("/android-releases/$id/activate", accessToken, "PATCH", JSONObject()).toRelease()

    fun delete(accessToken: String, id: Int) {
        request("/android-releases/$id", accessToken, "DELETE", null)
    }

    fun resolveUrl(path: String): String = if (path.startsWith("http")) path else baseUrl + "/" + path.trimStart('/')

    private fun writePart(output: BufferedOutputStream, boundary: String, name: String, value: String) {
        output.write("--$boundary\r\n".toByteArray())
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray())
    }

    private fun request(path: String, accessToken: String?, method: String = "GET", body: JSONObject? = null): Any {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            parseResponse(connection, "安装包请求失败")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseObjectResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        val result = parseResponse(connection, fallback)
        return result as? JSONObject ?: throw IllegalStateException("服务器返回的安装包数据格式不正确。")
    }

    private fun parseResponse(connection: HttpURLConnection, fallback: String): Any {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            throw IllegalStateException(message.ifBlank { "$fallback（HTTP $status）。" })
        }
        return when {
            body.trimStart().startsWith("[") -> JSONArray(body)
            body.isBlank() -> JSONObject()
            else -> JSONObject(body)
        }
    }

    private fun Any.toRelease(): NativeAndroidRelease = (this as JSONObject).toRelease()

    private fun JSONObject.toRelease(): NativeAndroidRelease {
        val uploader = optJSONObject("uploadedBy")
        return NativeAndroidRelease(
            id = optInt("id"),
            versionName = optString("versionName"),
            versionCode = optInt("versionCode"),
            channel = optString("channel", "stable"),
            originalName = optString("originalName"),
            fileName = optString("fileName"),
            sizeBytes = optLong("sizeBytes"),
            sha256 = optString("sha256"),
            apkUrl = optString("apkUrl"),
            releaseNotes = optJSONArray("releaseNotes").toStrings(),
            isActive = optBoolean("isActive"),
            uploadedBy = uploader?.optString("username").orEmpty(),
            createdAt = optString("createdAt"),
            updatedAt = optString("updatedAt"),
        )
    }

    private fun JSONArray?.toStrings(): List<String> = buildList {
        if (this@toStrings == null) return@buildList
        for (index in 0 until length()) add(optString(index))
    }

    private fun Any.toReleases(): List<NativeAndroidRelease> = (this as JSONArray).toReleases()
    private fun JSONArray.toReleases(): List<NativeAndroidRelease> = buildList { for (index in 0 until length()) add(optJSONObject(index)?.toRelease() ?: continue) }
}
