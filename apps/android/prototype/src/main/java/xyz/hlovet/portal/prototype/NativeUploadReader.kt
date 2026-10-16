package xyz.hlovet.portal.prototype

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal class NativeUploadLimitExceededException(maxBytes: Long) :
    IllegalStateException("File exceeds the $maxBytes byte limit.")

internal fun readNativeUploadBytes(input: InputStream, maxBytes: Long): ByteArray {
    require(maxBytes in 1L..Int.MAX_VALUE.toLong())
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream(minOf(maxBytes, buffer.size.toLong()).toInt())
    var total = 0L
    while (true) {
        // Read at most one byte beyond the limit, including for unknown-size documents.
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1L).toInt())
        if (count < 0) break
        if (count == 0) {
            val next = input.read()
            if (next < 0) break
            total++
            if (total > maxBytes) throw NativeUploadLimitExceededException(maxBytes)
            output.write(next)
        } else {
            total += count
            if (total > maxBytes) throw NativeUploadLimitExceededException(maxBytes)
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}
