package xyz.hlovet.portal.prototype

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeUploadReaderTest {
    @Test
    fun acceptsTheExactLimitWithoutTruncating() {
        val bytes = ByteArray(20_000) { (it % 127).toByte() }
        assertArrayEquals(bytes, readNativeUploadBytes(ByteArrayInputStream(bytes), bytes.size.toLong()))
    }

    @Test
    fun returnsEmptyBytesForAnEmptyDocument() {
        assertArrayEquals(byteArrayOf(), readNativeUploadBytes(ByteArrayInputStream(byteArrayOf()), 1L))
    }

    @Test
    fun stopsUnknownLengthStreamsAtTheLimitPlusOneByte() {
        var readCount = 0
        val input = object : InputStream() {
            override fun read(): Int { readCount++; return 42 }
        }
        assertThrows(NativeUploadLimitExceededException::class.java) { readNativeUploadBytes(input, 20_000L) }
        assertEquals(20_001, readCount)
    }

    @Test
    fun supportsShortStreamReadsWithoutLosingData() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 2))
        }
        assertArrayEquals(bytes, readNativeUploadBytes(input, bytes.size.toLong()))
    }

    @Test
    fun rejectsInvalidLimitsBeforeReading() {
        listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1L).forEach { limit ->
            assertThrows(IllegalArgumentException::class.java) {
                readNativeUploadBytes(ByteArrayInputStream(byteArrayOf(1)), limit)
            }
        }
    }
}
