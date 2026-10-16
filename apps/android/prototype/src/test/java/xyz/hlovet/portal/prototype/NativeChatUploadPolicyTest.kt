package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeChatUploadPolicyTest {
    @Test
    fun usesThePcLimitsForMimeTypes() {
        assertEquals(NATIVE_CHAT_MAX_IMAGE_BYTES, nativeChatAttachmentLimitBytes("image/png", "attachment.bin"))
        assertEquals(NATIVE_CHAT_MAX_AUDIO_BYTES, nativeChatAttachmentLimitBytes("audio/mpeg", "attachment.bin"))
        assertEquals(NATIVE_CHAT_MAX_VIDEO_BYTES, nativeChatAttachmentLimitBytes("video/mp4", "attachment.bin"))
        assertEquals(NATIVE_CHAT_MAX_FILE_BYTES, nativeChatAttachmentLimitBytes("application/pdf", "attachment.bin"))
    }

    @Test
    fun fallsBackToTheFileExtensionWhenMimeTypeIsMissing() {
        assertEquals(NativeChatAttachmentKind.Image, nativeChatAttachmentKind("", "PHOTO.JPEG"))
        assertEquals(NativeChatAttachmentKind.Audio, nativeChatAttachmentKind("application/octet-stream", "voice.m4a"))
        assertEquals(NativeChatAttachmentKind.Video, nativeChatAttachmentKind("", "clip.webm"))
        assertEquals(NativeChatAttachmentKind.File, nativeChatAttachmentKind("", "archive.zip"))
    }

    @Test
    fun blocksTheSameExecutableAndScriptExtensionsAsThePcClient() {
        listOf("payload.exe", "run.JS", "deploy.ps1", "tool.SH", "macro.vbs").forEach { name ->
            assertEquals(true, nativeChatAttachmentIsBlocked(name))
        }
        assertEquals(false, nativeChatAttachmentIsBlocked("report.pdf"))
        assertEquals(false, nativeChatAttachmentIsBlocked("photo.jpg"))
    }

    @Test
    fun exposesTheExactPerTypeBoundaries() {
        assertEquals(8L * 1024L * 1024L, nativeChatAttachmentLimitBytes("image/jpeg", "photo.jpg"))
        assertEquals(20L * 1024L * 1024L, nativeChatAttachmentLimitBytes("audio/ogg", "sound.ogg"))
        assertEquals(50L * 1024L * 1024L, nativeChatAttachmentLimitBytes("video/quicktime", "movie.mov"))
        assertEquals(20L * 1024L * 1024L, nativeChatAttachmentLimitBytes("", "document.pdf"))
    }

    @Test
    fun keepsBatchAndCountLimitsInSyncWithThePcClient() {
        assertEquals(50L * 1024L * 1024L, NATIVE_CHAT_MAX_BATCH_BYTES)
        assertEquals(9, NATIVE_CHAT_MAX_ATTACHMENTS)
    }

    @Test
    fun rejectsOnlySelectionsThatWouldExceedTheAttachmentCount() {
        assertEquals(false, nativeAttachmentSelectionExceedsLimit(0, 9))
        assertEquals(true, nativeAttachmentSelectionExceedsLimit(0, 10))
        assertEquals(true, nativeAttachmentSelectionExceedsLimit(8, 2))
        assertEquals(false, nativeAttachmentSelectionExceedsLimit(9, 0))
    }
}
