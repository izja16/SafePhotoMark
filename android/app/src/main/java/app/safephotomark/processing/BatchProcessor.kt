package app.safephotomark.processing

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.OutputStream
import kotlin.random.Random

object BatchProcessor {
    /**
     * Process a single photo. Cancellation is checked before decode, before
     * compose, and before write. A file that has already been written is not
     * rolled back if the batch is cancelled afterwards.
     */
    fun processOne(
        resolver: ContentResolver,
        source: Uri,
        sourceName: String,
        destStream: OutputStream,
        format: OutputFormat,
        watermark: WatermarkPayload,
        settings: ExportSettings,
        placement: WatermarkPlacement?,
        cacheDir: File,
        isCancelled: () -> Boolean = { false }
    ): WatermarkPlacement {
        fun throwIfCancelled() {
            if (isCancelled()) {
                throw ImagePipelineException(ImagePipelineError.CANCELLED)
            }
        }

        throwIfCancelled()
        val decoded = PhotoDecoder.decodeRaw(resolver, source, sourceName)

        try {
            throwIfCancelled()
            val canvas = SizeD(decoded.bitmap.width.toDouble(), decoded.bitmap.height.toDouble())
            val markSize = WatermarkCompositor.markSize(
                canvas = canvas,
                percent = settings.sizePercent,
                aspect = watermark.aspectRatio
            )
            val markImage = watermark.bitmap(markSize.width)

            val (rect, resolvedPlacement) = WatermarkCompositor.resolvePlacement(
                canvas = canvas,
                mark = markSize,
                position = settings.position,
                random = settings.watermarkMode.placesRandomly,
                existing = placement,
                rng = Random.Default
            )

            throwIfCancelled()
            val composed = BitmapComposer.compose(
                base = decoded.bitmap,
                watermark = markImage,
                rectTopLeft = rect,
                opacity = settings.opacity,
                format = format
            )
            try {
                throwIfCancelled()
                ImageExporter.export(
                    image = composed,
                    destStream = destStream,
                    format = format,
                    quality = settings.quality,
                    stripMetadata = settings.stripMetadata,
                    source = source,
                    resolver = resolver,
                    cacheDir = cacheDir
                )
            } finally {
                if (composed !== decoded.bitmap && !composed.isRecycled) composed.recycle()
            }
            return resolvedPlacement
        } finally {
            if (!decoded.bitmap.isRecycled) decoded.bitmap.recycle()
        }
    }
}
