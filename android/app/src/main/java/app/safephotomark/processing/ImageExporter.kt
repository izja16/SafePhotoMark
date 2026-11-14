package app.safephotomark.processing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

object ImageExporter {
    fun resolvedFormat(requested: OutputFormat, source: SourceImageKind): OutputFormat =
        ImageExporterFormats.resolvedFormat(requested, source)

    fun fileExtension(format: OutputFormat): String = ImageExporterFormats.fileExtension(format)

    fun mimeType(format: OutputFormat): String = ImageExporterFormats.mimeType(format)

    /**
     * Write a brand-new file. When [stripMetadata] is true, no source properties
     * are copied: no EXIF, IPTC, XMP, GPS, MakerNote, thumbnails, or timed
     * metadata. The pixels have already been baked upright.
     *
     * When [stripMetadata] is false, descriptive metadata is copied as far as
     * Android allows, but orientation is forced to “up” and embedded thumbnails
     * are dropped so an unwatermarked preview cannot leak.
     *
     * The file is written to a temporary name first and then copied into the
     * destination stream, so a failed finalize cannot leave a half-written
     * document that also consumes the uniquified destination name.
     */
    fun export(
        image: Bitmap,
        destStream: OutputStream,
        format: OutputFormat,
        quality: Double,
        stripMetadata: Boolean,
        source: Uri?,
        resolver: ContentResolver?,
        cacheDir: File
    ) {
        require(format != OutputFormat.SAME_AS_SOURCE) {
            "Output format must be resolved before export"
        }

        var pixels = image
        var recycleFlat = false
        if (format == OutputFormat.JPEG && !BitmapComposer.isOpaque(image)) {
            pixels = BitmapComposer.flattenOpaqueWhite(image)
            recycleFlat = pixels !== image
        }

        if (!cacheDir.exists()) cacheDir.mkdirs()
        val temp = File(cacheDir, ".safephotomark-tmp-${java.util.UUID.randomUUID()}")
        try {
            writeToFile(pixels, temp, format, quality)
            if (!stripMetadata && source != null && resolver != null &&
                (format == OutputFormat.JPEG || format == OutputFormat.HEIF)
            ) {
                MetadataCopier.copyPreservedMetadata(resolver, source, temp)
            }
            FileInputStream(temp).use { input ->
                input.copyTo(destStream)
                destStream.flush()
            }
        } catch (e: ImagePipelineException) {
            throw e
        } catch (e: Exception) {
            throw ImagePipelineException(ImagePipelineError.FINALIZE_FAILED, e.message)
        } finally {
            if (recycleFlat && !pixels.isRecycled) pixels.recycle()
            if (temp.exists()) temp.delete()
        }
    }

    fun writeToFile(image: Bitmap, dest: File, format: OutputFormat, quality: Double) {
        when (format) {
            OutputFormat.JPEG -> {
                val q = (minOf(maxOf(quality, 0.5), 1.0) * 100.0).toInt().coerceIn(50, 100)
                FileOutputStream(dest).use { stream ->
                    if (!image.compress(Bitmap.CompressFormat.JPEG, q, stream)) {
                        throw ImagePipelineException(ImagePipelineError.FINALIZE_FAILED)
                    }
                }
            }
            OutputFormat.PNG -> {
                FileOutputStream(dest).use { stream ->
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw ImagePipelineException(ImagePipelineError.FINALIZE_FAILED)
                    }
                }
            }
            OutputFormat.HEIF -> {
                HeifEncoder.write(image, dest, quality)
            }
            OutputFormat.TIFF -> {
                val hasAlpha = !BitmapComposer.isOpaque(image)
                FileOutputStream(dest).use { stream ->
                    TiffCodec.write(image, stream, hasAlpha)
                }
            }
            OutputFormat.SAME_AS_SOURCE -> error("resolved first")
        }
        if (!dest.exists() || dest.length() == 0L) {
            throw ImagePipelineException(ImagePipelineError.FINALIZE_FAILED)
        }
    }
}
