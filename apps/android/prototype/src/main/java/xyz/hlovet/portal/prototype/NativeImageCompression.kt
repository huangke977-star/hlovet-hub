package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

internal const val NATIVE_IMAGE_COMPRESSION_THRESHOLD_BYTES = 1_572_864
internal const val NATIVE_IMAGE_COMPRESSION_MAX_DIMENSION = 2_400
private const val NATIVE_IMAGE_COMPRESSION_QUALITY = 84

internal fun isNativeImageCompressionCandidate(
    file: NativeUploadFile,
    thresholdBytes: Int = NATIVE_IMAGE_COMPRESSION_THRESHOLD_BYTES,
): Boolean {
    if (file.bytes.size < thresholdBytes) return false
    if (!file.mimeType.startsWith("image/", ignoreCase = true)) return false
    return file.mimeType.lowercase(Locale.ROOT) !in setOf("image/png", "image/gif", "image/svg+xml")
}

/** Mirrors the web editor's lossy-image upload policy without changing PNG/GIF/SVG files. */
internal fun compressNativeImageForUpload(
    file: NativeUploadFile,
    maxDimension: Int = NATIVE_IMAGE_COMPRESSION_MAX_DIMENSION,
): NativeUploadFile {
    if (!isNativeImageCompressionCandidate(file) || maxDimension < 1) return file

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size, bounds)
    } catch (_: OutOfMemoryError) {
        return file
    } catch (_: RuntimeException) {
        return file
    }
    if (bounds.outWidth < 1 || bounds.outHeight < 1) return file

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension * 2) {
        sampleSize *= 2
    }
    val decoded = try {
        BitmapFactory.decodeByteArray(
            file.bytes,
            0,
            file.bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return file
    } catch (_: OutOfMemoryError) {
        return file
    } catch (_: RuntimeException) {
        return file
    }

    val oriented = applyNativeExifOrientation(decoded, nativeExifOrientation(file.bytes))
    if (oriented !== decoded) decoded.recycle()
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(oriented.width, oriented.height).toFloat())
    val resized = try {
        if (scale < 1f) {
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt().coerceAtLeast(1),
                (oriented.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            oriented
        }
    } catch (_: OutOfMemoryError) {
        oriented
    } catch (_: RuntimeException) {
        oriented
    }

    return try {
        val webp = file.mimeType.equals("image/webp", ignoreCase = true)
        val format = if (webp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else if (webp) {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val mimeType = if (webp) "image/webp" else "image/jpeg"
        val output = ByteArrayOutputStream()
        if (!resized.compress(format, NATIVE_IMAGE_COMPRESSION_QUALITY, output)) return file
        val compressed = output.toByteArray()
        if (compressed.size >= file.bytes.size) file else file.copy(mimeType = mimeType, bytes = compressed)
    } catch (_: RuntimeException) {
        file
    } catch (_: OutOfMemoryError) {
        file
    } finally {
        if (resized !== oriented) resized.recycle()
        oriented.recycle()
    }
}

private fun nativeExifOrientation(bytes: ByteArray): Int = runCatching {
    ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun applyNativeExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return bitmap
    }
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}
