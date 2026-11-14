package app.safephotomark.processing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Decode the first frame, bake EXIF/TIFF orientation into upright pixels,
 * then redraw into a fresh 8-bit sRGB bitmap so no source metadata or camera
 * ICC travels with the pixels. GIF and multi-frame HEICS export the first
 * frame only.
 */
object PhotoDecoder {
    fun sourceKind(resolver: ContentResolver, uri: Uri, displayName: String? = null): SourceImageKind {
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val header = runCatching { readHeader(resolver, uri, 64) }.getOrNull()
        return SourceKind.kind(displayName ?: uri.lastPathSegment, mime, header)
    }

    fun pixelArea(resolver: ContentResolver, uri: Uri, displayName: String? = null): Int? {
        return try {
            if (sourceKind(resolver, uri, displayName) == SourceImageKind.TIFF) {
                openStream(resolver, uri).use { stream ->
                    val bitmap = TiffCodec.decode(stream)
                    val area = bitmap.width * bitmap.height
                    if (!bitmap.isRecycled) bitmap.recycle()
                    area
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream(resolver, uri).use { BitmapFactory.decodeStream(it, null, options) }
                val w = options.outWidth
                val h = options.outHeight
                if (w > 0 && h > 0) w * h else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String? = null,
        maxPixelSize: Int? = null
    ): DecodedImage {
        val raw = decodeRaw(resolver, uri, displayName, maxPixelSize)
        val clean = flattenToSrgb(raw.bitmap)
        if (clean !== raw.bitmap && !raw.bitmap.isRecycled) {
            raw.bitmap.recycle()
        }
        return raw.copy(bitmap = clean)
    }

    /**
     * Decode the first frame and bake orientation. The export path skips the
     * extra sRGB flatten because composition already redraws into a fresh
     * sRGB bitmap; callers that need a metadata-free preview should use [decode].
     */
    fun decodeRaw(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String? = null,
        maxPixelSize: Int? = null
    ): DecodedImage {
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val header = runCatching { readHeader(resolver, uri, 64) }.getOrNull()
        val kind = SourceKind.kind(displayName ?: uri.lastPathSegment, mime, header)

        val bitmap = when (kind) {
            SourceImageKind.TIFF -> {
                openStream(resolver, uri).use { TiffCodec.decode(it, maxPixelSize) }
            }
            else -> decodeWithPlatform(resolver, uri, kind, maxPixelSize)
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            throw ImagePipelineException(ImagePipelineError.EMPTY_SOURCE)
        }
        val software = ensureSoftwareArgb(bitmap)
        return DecodedImage(software, kind, mime ?: mimeFor(kind))
    }

    private fun decodeWithPlatform(
        resolver: ContentResolver,
        uri: Uri,
        kind: SourceImageKind,
        maxPixelSize: Int?
    ): Bitmap {
        if (kind == SourceImageKind.HEIF && Build.VERSION.SDK_INT < 28) {
            throw ImagePipelineException(ImagePipelineError.UNSUPPORTED_FORMAT)
        }
        return if (Build.VERSION.SDK_INT >= 28) {
            decodeWithImageDecoder(resolver, uri, maxPixelSize)
        } else {
            decodeWithBitmapFactory(resolver, uri, maxPixelSize)
        }
    }

    private fun decodeWithImageDecoder(
        resolver: ContentResolver,
        uri: Uri,
        maxPixelSize: Int?
    ): Bitmap {
        if (Build.VERSION.SDK_INT < 28) {
            return decodeWithBitmapFactory(resolver, uri, maxPixelSize)
        }
        return try {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (Build.VERSION.SDK_INT >= 28) {
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
                if (maxPixelSize != null) {
                    val longEdge = maxOf(info.size.width, info.size.height)
                    if (longEdge > maxPixelSize && longEdge > 0) {
                        val scale = maxPixelSize.toFloat() / longEdge.toFloat()
                        val w = maxOf(1, (info.size.width * scale).toInt())
                        val h = maxOf(1, (info.size.height * scale).toInt())
                        decoder.setTargetSize(w, h)
                    }
                }
            }
        } catch (e: ImagePipelineException) {
            throw e
        } catch (e: Exception) {
            throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE, e.message)
        }
    }

    private fun decodeWithBitmapFactory(
        resolver: ContentResolver,
        uri: Uri,
        maxPixelSize: Int?
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPixelSize)
        }
        val raw = openStream(resolver, uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        val oriented = applyExifOrientation(raw, readExifOrientation(resolver, uri))
        if (oriented !== raw && !raw.isRecycled) raw.recycle()
        if (maxPixelSize != null) {
            val longEdge = maxOf(oriented.width, oriented.height)
            if (longEdge > maxPixelSize) {
                val scaled = TiffCodec.downsample(oriented, maxPixelSize)
                if (scaled !== oriented && !oriented.isRecycled) oriented.recycle()
                return scaled
            }
        }
        return oriented
    }

    fun flattenToSrgb(image: Bitmap): Bitmap {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_IMAGE)
        }
        val out = if (Build.VERSION.SDK_INT >= 26) {
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888,
                true,
                ColorSpace.get(ColorSpace.Named.SRGB)
            )
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(image, 0f, 0f, paint)
        return out
    }

    fun resample(image: Bitmap, width: Int, height: Int): Bitmap {
        val w = maxOf(1, width)
        val h = maxOf(1, height)
        if (image.width == w && image.height == h) return image
        return Bitmap.createScaledBitmap(image, w, h, true)
    }

    fun applyExifOrientation(image: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_UNDEFINED ||
            orientation == ExifInterface.ORIENTATION_NORMAL
        ) {
            return image
        }
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
            else -> return image
        }
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
    }

    private fun readExifOrientation(resolver: ContentResolver, uri: Uri): Int {
        return try {
            openStream(resolver, uri).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun ensureSoftwareArgb(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable.not()) {
            // Immutable software bitmaps are fine for compose/export.
            if (Build.VERSION.SDK_INT < 26 || bitmap.colorSpace == null ||
                bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
            ) {
                return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else copyArgb(bitmap)
            }
        }
        return copyArgb(bitmap)
    }

    private fun copyArgb(bitmap: Bitmap): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: flattenToSrgb(bitmap)
        if (Build.VERSION.SDK_INT >= 26) {
            // copy() keeps the color space; flatten if it is not sRGB.
            val space = copy.colorSpace
            if (space != null && space != ColorSpace.get(ColorSpace.Named.SRGB)) {
                val flat = flattenToSrgb(copy)
                if (copy !== bitmap && !copy.isRecycled) copy.recycle()
                return flat
            }
        }
        return copy
    }

    private fun sampleSize(width: Int, height: Int, maxPixelSize: Int?): Int {
        if (maxPixelSize == null) return 1
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / (sample * 2) >= maxPixelSize) {
            sample *= 2
        }
        return sample
    }

    private fun openStream(resolver: ContentResolver, uri: Uri): InputStream {
        return resolver.openInputStream(uri)
            ?: throw ImagePipelineException(ImagePipelineError.CANNOT_OPEN_SOURCE)
    }

    private fun readHeader(resolver: ContentResolver, uri: Uri, length: Int): ByteArray {
        openStream(resolver, uri).use { stream ->
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = stream.read(buf, read, length - read)
                if (n <= 0) break
                read += n
            }
            return if (read == length) buf else buf.copyOf(read)
        }
    }

    private fun mimeFor(kind: SourceImageKind): String = when (kind) {
        SourceImageKind.JPEG -> "image/jpeg"
        SourceImageKind.PNG -> "image/png"
        SourceImageKind.HEIF -> "image/heic"
        SourceImageKind.TIFF -> "image/tiff"
        SourceImageKind.GIF -> "image/gif"
        SourceImageKind.BMP -> "image/bmp"
        SourceImageKind.WEBP -> "image/webp"
        SourceImageKind.OTHER -> "image/*"
    }
}

object ThumbnailLoader {
    const val defaultMaxPixel = 96

    fun load(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String? = null,
        maxPixel: Int = defaultMaxPixel
    ): Bitmap? {
        return try {
            PhotoDecoder.decode(resolver, uri, displayName, maxPixel).bitmap
        } catch (_: Exception) {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                val sample = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    var s = 1
                    val longEdge = maxOf(options.outWidth, options.outHeight)
                    while (longEdge / (s * 2) >= maxPixel && longEdge > 0) s *= 2
                    inSampleSize = s
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, sample) }
            } catch (_: FileNotFoundException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
