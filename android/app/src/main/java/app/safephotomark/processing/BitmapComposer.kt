package app.safephotomark.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

object BitmapComposer {
    fun compose(
        base: Bitmap,
        watermark: Bitmap,
        rectTopLeft: RectD,
        opacity: Double,
        format: OutputFormat
    ): Bitmap {
        return compose(
            base = base,
            watermark = watermark,
            rectTopLeft = rectTopLeft,
            opacity = opacity,
            canvasStyle = WatermarkCompositor.ComposeCanvasStyle.forOutputFormat(format)
        )
    }

    fun compose(
        base: Bitmap,
        watermark: Bitmap,
        rectTopLeft: RectD,
        opacity: Double,
        canvasStyle: WatermarkCompositor.ComposeCanvasStyle
    ): Bitmap {
        val width = base.width
        val height = base.height
        if (width <= 0 || height <= 0) {
            throw ImagePipelineException(ImagePipelineError.CANNOT_CONVERT_COLOR_SPACE)
        }
        val config = Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(width, height, config)
        // Bitmap is already treated as sRGB on the compose path; the decoder
        // converts wide-gamut / 16-bit sources to 8-bit sRGB first.
        val canvas = Canvas(out)
        if (canvasStyle == WatermarkCompositor.ComposeCanvasStyle.OPAQUE_WHITE) {
            canvas.drawColor(Color.WHITE)
        } else {
            canvas.drawColor(Color.TRANSPARENT)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(base, 0f, 0f, paint)

        // Android bitmaps use top-left origin, matching rectTopLeft already.
        val dest = RectF(
            rectTopLeft.x.toFloat(),
            rectTopLeft.y.toFloat(),
            rectTopLeft.maxX.toFloat(),
            rectTopLeft.maxY.toFloat()
        )
        val markPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        markPaint.alpha = (minOf(maxOf(opacity, 0.0), 1.0) * 255.0).toInt().coerceIn(0, 255)
        canvas.drawBitmap(watermark, null, dest, markPaint)

        if (canvasStyle == WatermarkCompositor.ComposeCanvasStyle.OPAQUE_WHITE) {
            // JPEG has no alpha; force every pixel opaque in case the source
            // bitmap carried an alpha channel under the white fill.
            stripAlphaToOpaque(out)
        }
        return out
    }

    private fun stripAlphaToOpaque(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var changed = false
            for (i in row.indices) {
                val p = row[i]
                if (p ushr 24 != 0xFF) {
                    row[i] = p or 0xFF000000.toInt()
                    changed = true
                }
            }
            if (changed) {
                bitmap.setPixels(row, 0, w, 0, y, w, 1)
            }
        }
    }

    fun flattenOpaqueWhite(image: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(image, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        stripAlphaToOpaque(out)
        return out
    }

    fun isOpaque(image: Bitmap): Boolean {
        if (!image.hasAlpha()) return true
        val w = image.width
        val h = image.height
        val row = IntArray(w)
        for (y in 0 until h) {
            image.getPixels(row, 0, w, 0, y, w, 1)
            for (p in row) {
                if (p ushr 24 != 0xFF) return false
            }
        }
        return true
    }
}
