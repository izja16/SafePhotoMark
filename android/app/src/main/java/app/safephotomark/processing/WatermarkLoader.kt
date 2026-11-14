package app.safephotomark.processing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class WatermarkKind { RASTER, SVG }

/**
 * Thread-safe payload used on the processing queue. Rasterizations are cached
 * by target width (small LRU). SVG rasterization is serialized because
 * AndroidSVG documents are not documented as thread-safe.
 */
class WatermarkPayload(
    val kind: WatermarkKind,
    val raster: Bitmap?,
    val rasterBytes: ByteArray? = null,
    val svgData: ByteArray? = null,
    val intrinsicSize: SizeD
) {
    private val lock = ReentrantLock()
    private val cache = ArrayDeque<Pair<Int, Bitmap>>()
    private val cacheLimit = 3

    val aspectRatio: Double
        get() {
            val size = intrinsicSize
            if (size.height <= 0.0) return 1.0
            return size.width / size.height
        }

    fun bitmap(targetWidth: Double): Bitmap {
        val widthKey = maxOf(1, targetWidth.toInt())
        lock.withLock {
            val index = cache.indexOfFirst { it.first == widthKey }
            if (index >= 0) {
                val hit = cache.removeAt(index)
                cache.addFirst(hit)
                return hit.second
            }
        }
        val produced = rasterize(widthKey.toDouble())
        lock.withLock {
            cache.removeAll { it.first == widthKey }
            cache.addFirst(widthKey to produced)
            while (cache.size > cacheLimit) {
                cache.removeLast()
            }
        }
        return produced
    }

    private fun rasterize(targetWidth: Double): Bitmap {
        when (kind) {
            WatermarkKind.RASTER -> {
                val raster = raster ?: throw ImagePipelineException(ImagePipelineError.WATERMARK_INVALID)
                val width = maxOf(1.0, targetWidth)
                if (width >= raster.width.toDouble()) return raster
                val height = maxOf(1, (width / maxOf(aspectRatio, 0.0001)).toInt())
                return PhotoDecoder.resample(raster, maxOf(1, width.toInt()), height)
            }
            WatermarkKind.SVG -> {
                lock.withLock {
                    val widthKey = maxOf(1, targetWidth.toInt())
                    cache.firstOrNull { it.first == widthKey }?.let { return it.second }
                    val data = svgData ?: throw ImagePipelineException(ImagePipelineError.WATERMARK_INVALID)
                    val svg = SVG.getFromInputStream(ByteArrayInputStream(data))
                    val width = maxOf(1.0, targetWidth)
                    val height = maxOf(1.0, width / maxOf(aspectRatio, 0.0001))
                    return rasterizeSvg(svg, width, height)
                }
            }
        }
    }
}

data class LoadedWatermark(
    val uri: Uri,
    val filename: String,
    val payload: WatermarkPayload,
    val preview: Bitmap?
) {
    val kind: WatermarkKind get() = payload.kind
    val aspectRatio: Double get() = payload.aspectRatio
    val intrinsicSize: SizeD get() = payload.intrinsicSize

    fun bitmapForDisplay(widthPx: Float): Bitmap? {
        val pixels = maxOf(1f, widthPx)
        val capped = if (kind == WatermarkKind.RASTER) {
            minOf((payload.raster?.width ?: pixels.toInt()).toFloat(), pixels)
        } else {
            pixels
        }
        return try {
            payload.bitmap(capped.toDouble())
        } catch (_: Exception) {
            preview
        }
    }
}

object WatermarkLoader {
    fun load(resolver: ContentResolver, uri: Uri, displayName: String? = null): LoadedWatermark {
        val name = displayName ?: uri.lastPathSegment ?: "watermark"
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImagePipelineException(ImagePipelineError.CANNOT_OPEN_SOURCE)
        val isSvg = SupportedTypes.isWatermarkFile(name, mime) &&
            (name.substringAfterLast('.', "").lowercase() == "svg" ||
                (mime?.contains("svg", ignoreCase = true) == true) ||
                looksLikeSvg(bytes))

        if (isSvg) {
            val svg = loadSvg(uri, bytes)
            val previewWidth = 256.0
            val aspect = if (svg.documentHeight == 0f) 1.0 else {
                intrinsicSvgSize(svg).let { it.width / maxOf(it.height, 0.0001) }
            }
            val previewHeight = previewWidth / aspect
            val preview = rasterizeSvg(svg, previewWidth, previewHeight)
            val payload = WatermarkPayload(
                kind = WatermarkKind.SVG,
                raster = null,
                svgData = bytes,
                intrinsicSize = intrinsicSvgSize(svg)
            )
            return LoadedWatermark(uri, name, payload, preview)
        }

        // Raster PNG (or any bitmap the decoder can read). Always flatten to
        // a clean sRGB bitmap so a same-path replace cannot reuse stale pixels.
        val decoded = decodeRaster(bytes, name)
        val size = SizeD(decoded.width.toDouble(), decoded.height.toDouble())
        val payload = WatermarkPayload(
            kind = WatermarkKind.RASTER,
            raster = decoded,
            rasterBytes = bytes,
            intrinsicSize = size
        )
        return LoadedWatermark(uri, name, payload, decoded)
    }

    private fun decodeRaster(bytes: ByteArray, name: String): Bitmap {
        val kind = SourceKind.kind(name, "image/png", bytes)
        if (kind == SourceImageKind.TIFF) {
            return TiffCodec.decode(ByteArrayInputStream(bytes))
        }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw ImagePipelineException(ImagePipelineError.WATERMARK_INVALID)
        return PhotoDecoder.flattenToSrgb(bitmap)
    }

    private fun loadSvg(uri: Uri, data: ByteArray): SVG {
        val digest = sha256(data)
        val key = uri.toString() to digest
        SvgDocumentCache.value(key)?.let { return it }
        val svg = try {
            SVG.getFromInputStream(ByteArrayInputStream(data))
        } catch (e: Exception) {
            throw ImagePipelineException(ImagePipelineError.WATERMARK_INVALID, e.message)
        }
        val size = intrinsicSvgSize(svg)
        if (size.width <= 0.0 || size.height <= 0.0) {
            throw ImagePipelineException(ImagePipelineError.WATERMARK_INVALID)
        }
        SvgDocumentCache.store(key, svg)
        return svg
    }

    private fun looksLikeSvg(bytes: ByteArray): Boolean {
        val head = bytes.decodeToString(0, minOf(bytes.size, 256)).lowercase()
        return head.contains("<svg")
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

internal fun intrinsicSvgSize(svg: SVG): SizeD {
    val viewBox = svg.documentViewBox
    val docW = svg.documentWidth
    val docH = svg.documentHeight
    val width = when {
        docW > 0f -> docW.toDouble()
        viewBox != null && viewBox.width() > 0f -> viewBox.width().toDouble()
        else -> 0.0
    }
    val height = when {
        docH > 0f -> docH.toDouble()
        viewBox != null && viewBox.height() > 0f -> viewBox.height().toDouble()
        else -> 0.0
    }
    return SizeD(width, height)
}

internal fun rasterizeSvg(svg: SVG, width: Double, height: Double): Bitmap {
    val pixelWidth = maxOf(1, kotlin.math.ceil(width).toInt())
    val pixelHeight = maxOf(1, kotlin.math.ceil(height).toInt())
    val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    try {
        svg.setDocumentWidth(pixelWidth.toFloat())
        svg.setDocumentHeight(pixelHeight.toFloat())
        svg.renderToCanvas(canvas)
    } catch (e: Exception) {
        throw ImagePipelineException(ImagePipelineError.WATERMARK_INVALID, e.message)
    }
    return bitmap
}

private object SvgDocumentCache {
    private val lock = ReentrantLock()
    private val items = LinkedHashMap<WatermarkLoaderKey, SVG>()

    fun value(key: WatermarkLoaderKey): SVG? = lock.withLock { items[key] }

    fun store(key: WatermarkLoaderKey, svg: SVG) = lock.withLock {
        items.entries.removeAll { it.key.path == key.path }
        items[key] = svg
        while (items.size > 8) {
            val stale = items.keys.firstOrNull { it != key } ?: break
            items.remove(stale)
        }
    }
}

private typealias WatermarkLoaderKey = Pair<String, String>

private val WatermarkLoaderKey.path: String get() = first
