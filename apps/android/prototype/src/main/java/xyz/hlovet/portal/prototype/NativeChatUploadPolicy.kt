package xyz.hlovet.portal.prototype

internal enum class NativeChatAttachmentKind {
    Image,
    Audio,
    Video,
    File,
}

internal const val NATIVE_CHAT_MAX_IMAGE_BYTES: Long = 8L * 1024L * 1024L
internal const val NATIVE_CHAT_MAX_AUDIO_BYTES: Long = 20L * 1024L * 1024L
internal const val NATIVE_CHAT_MAX_VIDEO_BYTES: Long = 50L * 1024L * 1024L
internal const val NATIVE_CHAT_MAX_FILE_BYTES: Long = 20L * 1024L * 1024L
internal const val NATIVE_CHAT_MAX_BATCH_BYTES: Long = 50L * 1024L * 1024L
internal const val NATIVE_CHAT_MAX_ATTACHMENTS: Int = 9

internal fun nativeAttachmentSelectionExceedsLimit(existingCount: Int, selectedCount: Int): Boolean =
    existingCount.coerceAtLeast(0) + selectedCount.coerceAtLeast(0) > NATIVE_CHAT_MAX_ATTACHMENTS

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp")
private val audioExtensions = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "amr", "opus")
private val videoExtensions = setOf("mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp")
private val blockedExtensions = setOf(
    "bat", "cmd", "com", "cpl", "exe", "hta", "jar", "js", "jse", "msi",
    "msp", "pif", "ps1", "scr", "sh", "vbe", "vbs", "wsf", "wsh",
)

internal fun nativeChatAttachmentIsBlocked(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").trim().lowercase() in blockedExtensions

internal fun nativeChatAttachmentBlockedError(name: String): String =
    nativePhrase("不允许发送可执行文件或脚本：$name", "Executable files or scripts cannot be sent: $name")

internal fun nativeChatAttachmentKind(mimeType: String, fileName: String): NativeChatAttachmentKind {
    val mime = mimeType.trim().lowercase()
    return when {
        mime.startsWith("image/") -> NativeChatAttachmentKind.Image
        mime.startsWith("audio/") -> NativeChatAttachmentKind.Audio
        mime.startsWith("video/") -> NativeChatAttachmentKind.Video
        fileName.substringAfterLast('.', "").trim().lowercase() in imageExtensions -> NativeChatAttachmentKind.Image
        fileName.substringAfterLast('.', "").trim().lowercase() in audioExtensions -> NativeChatAttachmentKind.Audio
        fileName.substringAfterLast('.', "").trim().lowercase() in videoExtensions -> NativeChatAttachmentKind.Video
        else -> NativeChatAttachmentKind.File
    }
}

internal fun nativeChatAttachmentLimitBytes(mimeType: String, fileName: String): Long = when (
    nativeChatAttachmentKind(mimeType, fileName)
) {
    NativeChatAttachmentKind.Image -> NATIVE_CHAT_MAX_IMAGE_BYTES
    NativeChatAttachmentKind.Audio -> NATIVE_CHAT_MAX_AUDIO_BYTES
    NativeChatAttachmentKind.Video -> NATIVE_CHAT_MAX_VIDEO_BYTES
    NativeChatAttachmentKind.File -> NATIVE_CHAT_MAX_FILE_BYTES
}

internal fun nativeChatAttachmentSizeError(name: String, mimeType: String, fileName: String = name): String {
    return when (nativeChatAttachmentKind(mimeType, fileName)) {
        NativeChatAttachmentKind.Image -> nativePhrase("单张图片不能超过 8MB：$name", "Each image cannot exceed 8 MB: $name")
        NativeChatAttachmentKind.Audio -> nativePhrase("单个音频不能超过 20MB：$name", "Each audio file cannot exceed 20 MB: $name")
        NativeChatAttachmentKind.Video -> nativePhrase("单个视频不能超过 50MB：$name", "Each video cannot exceed 50 MB: $name")
        NativeChatAttachmentKind.File -> nativePhrase("单个普通文件不能超过 20MB：$name", "Each file cannot exceed 20 MB: $name")
    }
}
