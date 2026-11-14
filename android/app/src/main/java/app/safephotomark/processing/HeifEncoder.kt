package app.safephotomark.processing

import android.graphics.Bitmap
import android.os.Build
import androidx.heifwriter.HeifWriter
import java.io.File

object HeifEncoder {
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= 28

    /**
     * Encode a single-frame HEIF file. Throws [ImagePipelineError.HEIF_ENCODE_UNAVAILABLE]
     * when the device/API cannot encode HEIF. Never silently falls back to JPEG.
     */
    fun write(bitmap: Bitmap, dest: File, quality: Double) {
        if (!isAvailable()) {
            throw ImagePipelineException(ImagePipelineError.HEIF_ENCODE_UNAVAILABLE)
        }
        val q = (minOf(maxOf(quality, 0.5), 1.0) * 100.0).toInt().coerceIn(50, 100)
        var writer: HeifWriter? = null
        try {
            writer = HeifWriter.Builder(
                dest.absolutePath,
                bitmap.width,
                bitmap.height,
                HeifWriter.INPUT_MODE_BITMAP
            )
                .setQuality(q)
                .setMaxImages(1)
                .build()
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(0)
        } catch (e: ImagePipelineException) {
            throw e
        } catch (e: Exception) {
            throw ImagePipelineException(ImagePipelineError.HEIF_ENCODE_UNAVAILABLE, e.message)
        } finally {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
        }
        if (!dest.exists() || dest.length() == 0L) {
            throw ImagePipelineException(ImagePipelineError.HEIF_ENCODE_UNAVAILABLE)
        }
    }
}
