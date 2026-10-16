package xyz.hlovet.portal.prototype

import java.util.Locale

// 文章附件沿用 /articles/:id/attachments 的限制，不复用聊天端普通文件的本地上限。
internal const val NATIVE_ARTICLE_ATTACHMENT_MAX_COUNT = 9
internal const val NATIVE_ARTICLE_ATTACHMENT_TOTAL_COUNT = 50
internal const val NATIVE_ARTICLE_ATTACHMENT_BATCH_BYTES = 50L * 1024L * 1024L
private const val articleImageBytes = 8L * 1024L * 1024L
private const val articleAudioBytes = 20L * 1024L * 1024L

// 扩展名只用于客户端筛选和补全供应商 MIME，真实文件签名仍由服务器验证。
private val articleAttachmentMimeTypes = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
    "mp3" to "audio/mpeg", "wav" to "audio/wav", "m4a" to "audio/mp4", "ogg" to "audio/ogg",
    "mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
    "pdf" to "application/pdf", "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
    "odp" to "application/vnd.oasis.opendocument.presentation", "txt" to "text/plain", "md" to "text/markdown",
    "csv" to "text/csv", "json" to "application/json", "xml" to "application/xml", "rtf" to "application/rtf",
    "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
    "gz" to "application/gzip", "tar" to "application/x-tar",
)

/** 根据 fileName 补全合法附件的 MIME；providerMime 用于区分 WebM 音频，未知格式抛出明确错误。 */
internal fun nativeArticleAttachmentMimeType(fileName: String, providerMime: String): String {
    // MIME 缺失不能屏蔽合法文档，脚本或不受支持格式也不能因通用 MIME 放行。
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mime = articleAttachmentMimeTypes[extension] ?: throw IllegalStateException(
        nativePhrase("不支持的文章附件格式：$fileName", "Unsupported article attachment format: $fileName"),
    )
    return if (extension == "webm" && providerMime.startsWith("audio/", ignoreCase = true)) "audio/webm" else mime
}

/** 验证 existingCount 个已存附件加上 selectedCount 个新附件，不允许超请求或单篇总量。 */
internal fun validateNativeArticleAttachmentCount(existingCount: Int, selectedCount: Int) {
    // 空选择由调用方当作取消处理；这里只检查确实准备上传的批次。
    check(selectedCount in 1..NATIVE_ARTICLE_ATTACHMENT_MAX_COUNT) {
        nativePhrase("每次最多选择 9 个附件。", "You can select up to 9 attachments at a time.")
    }
    check(existingCount.toLong() + selectedCount <= NATIVE_ARTICLE_ATTACHMENT_TOTAL_COUNT) {
        nativePhrase("单篇文章最多包含 50 个附件。", "An article can contain at most 50 attachments.")
    }
}

/** 返回 mimeType 对应字节上限；maxImageBytes 仅控制压缩前图片，prepared 表示接口实际上传版本。 */
internal fun nativeArticleAttachmentLimitBytes(mimeType: String, maxImageBytes: Long, prepared: Boolean): Long {
    // 保留站点的原图限制，同时压缩结果必须满足附件接口的 8MB 图片限制。
    return when {
        mimeType.startsWith("image/") -> if (prepared) articleImageBytes else maxImageBytes.coerceIn(1L, NATIVE_ARTICLE_ATTACHMENT_BATCH_BYTES)
        mimeType.startsWith("audio/") -> articleAudioBytes
        else -> NATIVE_ARTICLE_ATTACHMENT_BATCH_BYTES
    }
}

/** 校验 files 的格式、非空及按类型大小，并以 Long 汇总批次，防止读取或上传超过 50MB。 */
internal fun validateNativeArticleUpload(files: List<NativeUploadFile>, maxImageBytes: Long, prepared: Boolean) {
    // 文件签名不在客户端重复实现；逐个检查大小后再核对整批实际字节数。
    var total = 0L
    for (file in files) {
        val mime = nativeArticleAttachmentMimeType(file.name, file.mimeType)
        val limit = nativeArticleAttachmentLimitBytes(mime, maxImageBytes, prepared)
        check(file.bytes.isNotEmpty()) { nativePhrase("${file.name} 是空文件。", "${file.name} is empty.") }
        check(file.bytes.size.toLong() <= limit) {
            nativePhrase("${file.name} 不能超过 ${limit / 1024 / 1024}MB。", "${file.name} cannot exceed ${limit / 1024 / 1024} MB.")
        }
        total += file.bytes.size.toLong()
        check(total <= NATIVE_ARTICLE_ATTACHMENT_BATCH_BYTES) { nativeArticleAttachmentBatchError() }
    }
}

/** 返回文章附件批次总量超限提示，读取阶段与提交阶段使用同一口径。 */
internal fun nativeArticleAttachmentBatchError(): String {
    // 这里是所有文件合计限制，不误导用户为某一个文件的大小限制。
    return nativePhrase("每次附件的总大小不能超过 50MB。", "Attachments cannot exceed 50 MB in total per upload.")
}
