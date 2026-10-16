package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeImageCompressionTest {
    @Test
    fun largeJpegIsResizedAndReencodedSmallerOnDevice() {
        val width = 2_700
        val height = 1_800
        val pixels = IntArray(width * height) { index ->
            var value = index * 1_103_515_245 + 12_345
            value = value xor (value ushr 16)
            val red = value and 0xff
            val green = (value ushr 8) and 0xff
            val blue = (value ushr 16) and 0xff
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val source = ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        assertTrue("Generated JPEG must exercise the compression threshold", source.size >= NATIVE_IMAGE_COMPRESSION_THRESHOLD_BYTES)

        val compressed = compressNativeImageForUpload(NativeUploadFile("photo.jpg", "image/jpeg", source))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(compressed.bytes, 0, compressed.bytes.size, bounds)

        assertTrue("Compressed image must be smaller", compressed.bytes.size < source.size)
        assertTrue("Compressed image must preserve JPEG MIME", compressed.mimeType == "image/jpeg")
        assertTrue("Longest edge must be capped", maxOf(bounds.outWidth, bounds.outHeight) <= NATIVE_IMAGE_COMPRESSION_MAX_DIMENSION)
    }

    @Test
    fun compressedJpegNormalizesExifRotation() {
        val width = 1_600
        val height = 1_000
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff3567ab.toInt())
        }
        val source = ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val orientedSourceWithExif = withExifOrientation(source, ExifInterface.ORIENTATION_ROTATE_90)
        val orientedSource = orientedSourceWithExif.copyOf(
            maxOf(orientedSourceWithExif.size, NATIVE_IMAGE_COMPRESSION_THRESHOLD_BYTES + 1),
        )
        assertTrue(
            "Generated JPEG must exercise the compression threshold",
            orientedSource.size >= NATIVE_IMAGE_COMPRESSION_THRESHOLD_BYTES,
        )
        val compressed = compressNativeImageForUpload(NativeUploadFile("rotated.jpg", "image/jpeg", orientedSource))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(compressed.bytes, 0, compressed.bytes.size, bounds)
        val exif = ExifInterface(ByteArrayInputStream(compressed.bytes))

        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface(ByteArrayInputStream(orientedSource))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
        )
        assertTrue("Compressed pixels should be smaller", compressed.bytes.size < orientedSource.size)
        assertEquals(1_000, bounds.outWidth)
        assertEquals(1_600, bounds.outHeight)
        val normalizedOrientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        assertTrue(
            "Compressed JPEG must not retain a rotated EXIF orientation: $normalizedOrientation",
            normalizedOrientation == 0 || normalizedOrientation == ExifInterface.ORIENTATION_NORMAL,
        )
    }

    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val app1Payload = byteArrayOf(
            0x45, 0x78, 0x69, 0x66, 0, 0,
            0x49, 0x49, 42, 0, 8, 0, 0, 0,
            1, 0,
            0x12, 0x01, 3, 0, 1, 0, 0, 0,
            orientation.toByte(), 0, 0, 0,
            0, 0, 0, 0,
        )
        val segment = byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 0x22) + app1Payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }
}
