package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdminSettingsValidationTest {
    @Test
    fun backgroundLimitMatchesServerLimit() {
        assertTrue(nativeBackgroundUploadAllowed(30L * 1024L * 1024L))
        assertFalse(nativeBackgroundUploadAllowed(30L * 1024L * 1024L + 1L))
    }

    @Test
    fun emptyBackgroundFilesAreRejected() {
        assertFalse(nativeBackgroundUploadAllowed(0L))
        assertFalse(nativeBackgroundUploadAllowed(-1L))
    }

    @Test
    fun pickerUsesServerSizeLimitForEverySelectedFile() {
        val selected = (1..NATIVE_BACKGROUND_MAX_FILES).toList()
        val readIds = mutableListOf<Int>()
        val files = readNativeBackgroundUploads(selected) { id, maxBytes ->
            assertEquals(30L * 1024L * 1024L, maxBytes)
            readIds.add(id)
            NativeUploadFile("$id.png", "image/png", byteArrayOf(id.toByte()))
        }
        assertEquals(selected, readIds)
        assertEquals(selected.size, files.size)
    }

    @Test
    fun invalidBatchIsRejectedBeforeReadingAnyFileInsteadOfTruncating() {
        listOf(emptyList(), (1..NATIVE_BACKGROUND_MAX_FILES + 1).toList()).forEach { selected ->
            assertThrows(IllegalArgumentException::class.java) {
                readNativeBackgroundUploads(selected) { _, _ -> error("Must not read an invalid batch") }
            }
        }
    }
}
