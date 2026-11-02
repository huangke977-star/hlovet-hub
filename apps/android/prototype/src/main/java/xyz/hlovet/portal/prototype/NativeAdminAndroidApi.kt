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

    /** 上传 Android 安装包并返回服务端保存的版本记录；网络失败时返回跟随当前界面的本地化提示。 */
    fun upload(accessToken: String, file: NativeUploadFile, versionName: String, versionCode: Int, channel: String, notes: String, activate: Boolean): NativeAndroidRelease {
        // 以 multipart 发送安装包和版本元数据，保持服务端现有上传协议不变。
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
            parseObjectResponse(connection, nativePhrase("安装包上传失败", "Package upload failed")) .toRelease()
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

    /** 调用 Android 版本管理接口并解析 JSON；失败时保留 HTTP 状态供页面反馈。 */
    private fun request(path: String, accessToken: String?, method: String = "GET", body: JSONObject? = null): Any {
        // 统一设置认证和 JSON 请求头，避免列表、激活、删除接口各自维护一套连接逻辑。
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
            parseResponse(connection, nativePhrase("安装包请求失败", "Package request failed"))
        } finally {
            connection.disconnect()
        }
    }

    /** 将响应限定为 JSON 对象，防止错误的数据形状进入版本记录解析。 */
    private fun parseObjectResponse(connection: HttpURLConnection, fallback: String): JSONObject {
        // 上传成功必须返回对象，数组或空响应都属于协议错误。
        val result = parseResponse(connection, fallback)
        return result as? JSONObject ?: throw IllegalStateException(nativePhrase("服务器返回的安装包数据格式不正确。", "The server returned an invalid package response."))
    }

    /** 读取 HTTP 响应并按根节点形状解析 JSON；非 2xx 响应优先使用服务端消息。 */
    private fun parseResponse(connection: HttpURLConnection, fallback: String): Any {
        // 先选择成功或错误流，再统一处理状态码和响应体，避免吞掉服务端诊断信息。
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
