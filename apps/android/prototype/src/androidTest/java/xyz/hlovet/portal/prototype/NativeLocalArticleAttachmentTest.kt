package xyz.hlovet.portal.prototype

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用已保留的本地账号在 Docker API 验证文章媒体附件，报告不包含密码或令牌。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalArticleAttachmentTest {
    /** 上传图片、文本和音频并保存重读，另外准备真实系统选择器的文件；全部测试记录保留。 */
    @Test fun uploadSaveDownloadAndPreparePickerWithLocalSession() {
        // 双重检查精确来源和已有测试账号，不能执行线上或任意已登录用户的写操作。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stored = requireNotNull(NativeSessionStore.load(context)) { "Local test session is missing" }
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        val session = NativeAuthApi.refresh(stored.refreshToken)
        check(session.userId == stored.userId)
        check(NativeSessionStore.save(context, session))
        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val title = "native_attachment_20261014_$suffix"
        val input = NativeDraftInput(title, "Local native attachment test", "<p>Native attachment base body</p>", "html", "随笔", "", "", "public", emptyList())
        val article = NativeArticleApi.createArticle(session.accessToken, input)

        // 小型合法图片和 WAV 不调用外部服务，文本包含容易识别的测试标记。
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(22, 163, 74)) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val files = listOf(
            NativeUploadFile("native_image_$suffix.png", "image/png", png),
            NativeUploadFile("native_notes_$suffix.txt", "text/plain", "Native attachment text $suffix".toByteArray()),
            NativeUploadFile("native_voice_$suffix.wav", "audio/wav", wav()),
        )
        val uploaded = NativeArticleApi.uploadAttachments(session.accessToken, article.id, files)
        assertEquals(3, uploaded.size)
        assertEquals(setOf("image", "file", "audio"), uploaded.map { it.kind }.toSet())
        val refs = uploaded.joinToString("") { item ->
            when (item.kind) {
                "image" -> "<p><img src=\"${item.downloadUrl}\" alt=\"${item.originalName}\"></p>"
                "audio" -> "<p><audio controls src=\"${item.downloadUrl}\"></audio></p>"
                else -> "<p><a href=\"${item.downloadUrl}\">${item.originalName}</a></p>"
            }
        }
        // 保存回包与再次读取都必须携带附件，防止界面刚上传的列表被自动保存清空。
        val saved = NativeArticleApi.updateArticle(session.accessToken, article.id, input.copy(content = input.content + refs))
        assertEquals(uploaded.map { it.id }, saved.attachments.map { it.id })
        val reloaded = NativeArticleApi.getMyArticle(session.accessToken, article.id)
        assertEquals(uploaded.map { it.id }, reloaded.attachments.map { it.id })
        for (item in uploaded) assertTrue(reloaded.content.contains(item.downloadUrl))

        // 私有下载必须携带会话，逐文件核对真实字节而不是只检查列表返回。
        val checks = JSONArray()
        for (index in files.indices) {
            assertArrayEquals(files[index].bytes, download(uploaded[index].downloadUrl, session.accessToken, 200))
            download(uploaded[index].downloadUrl, null, 404)
            checks.put(JSONObject().put("check", "download-${uploaded[index].kind}-authenticated-and-anonymous").put("passed", true))
        }

        // 留下两个命名明确的系统文档，供下一步 MainActivity 中真实选择器使用。
        val pickerName = "native_picker_$suffix.txt"
        val pickerUri = mediaDocument(pickerName, "text/plain", "Native picker body $suffix".toByteArray())
        val imageName = "native_picker_$suffix.png"
        val imageUri = mediaDocument(imageName, "image/png", png)
        checks.put(JSONObject().put("check", "upload-save-reload-three-attachment-kinds").put("passed", true))
        val report = JSONObject().put("apiBaseUrl", BuildConfig.API_BASE_URL).put("userId", session.userId)
            .put("username", session.username).put("articleId", article.id).put("title", title)
            .put("attachments", JSONArray(uploaded.map { item -> JSONObject().put("id", item.id).put("kind", item.kind).put("name", item.originalName) }))
            .put("pickerTextName", pickerName).put("pickerTextUri", pickerUri).put("pickerImageName", imageName).put("pickerImageUri", imageUri)
            .put("checks", checks)
        File(context.filesDir, "local-article-attachment-report.json").writeText(report.toString(2))
    }

    /** 返回合法的单声道 PCM WAV，测试音频头和下载完整性，不生成可感知噪声。 */
    private fun wav(): ByteArray {
        // 标准 44 字节头加四个静音样本，文件体积远小于音频上传限额。
        val buffer = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(44).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(8).putLong(0)
        return buffer.array()
    }

    /** 按 path 下载文件，token 为 null 表示未认证；核对 expectedStatus 后返回成功响应的原始字节。 */
    private fun download(path: String, token: String?, expectedStatus: Int): ByteArray {
        // 路径必须解析到本地 API，防止测试令牌被任意附件 URL 带到外部来源。
        val uri = URI(BuildConfig.API_BASE_URL + "/").resolve(path)
        check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 3001)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return try {
            assertEquals(expectedStatus, connection.responseCode)
            if (expectedStatus == 200) connection.inputStream.use { it.readBytes() } else byteArrayOf()
        } finally {
            connection.disconnect()
        }
    }

    /** 将 name、mime 和 bytes 存入独立系统下载目录，返回 URI 字符串供手动选择器验证使用。 */
    private fun mediaDocument(name: String, mime: String, bytes: ByteArray): String {
        // 插入新条目不覆盖其它文件，提交完成后解除 pending 让系统选择器可见。
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Hlovet-local-tests")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri.toString()
    }
}
