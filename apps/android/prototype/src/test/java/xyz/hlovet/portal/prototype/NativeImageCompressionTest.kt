package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImageCompressionTest {
    private fun file(mimeType: String, size: Int) = NativeUploadFile(
        name = "image",
        mimeType = mimeType,
        bytes = ByteArray(size),
    )

    @Test
    fun compressesLargeLossyImagesOnly() {
        assertTrue(isNativeImageCompressionCandidate(file("image/jpeg", 1_572_864)))
        assertTrue(isNativeImageCompressionCandidate(file("image/webp", 2_000_000)))
        assertFalse(isNativeImageCompressionCandidate(file("image/jpeg", 1_572_863)))
        assertFalse(isNativeImageCompressionCandidate(file("application/pdf", 2_000_000)))
    }

    @Test
    fun preservesFormatsThatShouldNotBeLossilyReencoded() {
        assertFalse(isNativeImageCompressionCandidate(file("image/png", 2_000_000)))
        assertFalse(isNativeImageCompressionCandidate(file("image/gif", 2_000_000)))
        assertFalse(isNativeImageCompressionCandidate(file("image/svg+xml", 2_000_000)))
    }

    @Test
    fun invalidImageBytesFallBackToOriginalFile() {
        val original = file("image/jpeg", 2_000_000)

        assertTrue(compressNativeImageForUpload(original).bytes.contentEquals(original.bytes))
    }
}
