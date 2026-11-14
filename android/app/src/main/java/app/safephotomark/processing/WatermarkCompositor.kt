package app.safephotomark.processing

import kotlin.random.Random

object WatermarkCompositor {
    val sizePercentRange: ClosedFloatingPointRange<Double> = 5.0..80.0
    const val defaultSizePercent: Double = 20.0
    const val defaultOpacity: Double = 1.0

    /**
     * The central fraction of the image that random placement must avoid
     * (30% × 30%, i.e. 35%–65% on each axis).
     */
    const val excludedCenterFraction: Double = 0.30

    fun markSize(canvas: SizeD, percent: Double, aspect: Double): SizeD {
        val shorter = minOf(canvas.width, canvas.height)
        val clamped = minOf(maxOf(percent, sizePercentRange.start), sizePercentRange.endInclusive)
        var width = shorter * (clamped / 100.0)
        var height = width / maxOf(aspect, 0.0001)

        // The mark must stay fully inside the canvas, even at high percentages.
        if (width > canvas.width) {
            val scale = canvas.width / width
            width *= scale
            height *= scale
        }
        if (height > canvas.height) {
            val scale = canvas.height / height
            width *= scale
            height *= scale
        }
        return SizeD(width = maxOf(1.0, width), height = maxOf(1.0, height))
    }

    /**
     * Rect in **top-left** image coordinates (y grows downward, as in a photo).
     */
    fun placementRect(
        canvas: SizeD,
        mark: SizeD,
        position: WatermarkPosition,
        marginRatio: Double = 0.04
    ): RectD {
        val canvasWidth = canvas.width
        val canvasHeight = canvas.height
        val markWidth = minOf(mark.width, canvasWidth)
        val markHeight = minOf(mark.height, canvasHeight)
        val maxMargin = minOf(canvasWidth - markWidth, canvasHeight - markHeight) / 2.0
        val margin = maxOf(0.0, minOf(maxMargin, minOf(canvasWidth, canvasHeight) * marginRatio))

        fun aligned(xAlign: Double, yAlign: Double): RectD {
            val availableWidth = canvasWidth - markWidth - margin * 2
            val availableHeight = canvasHeight - markHeight - margin * 2
            val x = margin + maxOf(0.0, availableWidth) * xAlign
            val y = margin + maxOf(0.0, availableHeight) * yAlign
            return RectD(x, y, markWidth, markHeight)
        }

        return when (position) {
            WatermarkPosition.TOP_LEFT -> aligned(0.0, 0.0)
            WatermarkPosition.TOP_CENTER -> aligned(0.5, 0.0)
            WatermarkPosition.TOP_RIGHT -> aligned(1.0, 0.0)
            WatermarkPosition.MIDDLE_LEFT -> aligned(0.0, 0.5)
            WatermarkPosition.CENTER -> aligned(0.5, 0.5)
            WatermarkPosition.MIDDLE_RIGHT -> aligned(1.0, 0.5)
            WatermarkPosition.BOTTOM_LEFT -> aligned(0.0, 1.0)
            WatermarkPosition.BOTTOM_CENTER -> aligned(0.5, 1.0)
            WatermarkPosition.BOTTOM_RIGHT -> aligned(1.0, 1.0)
        }
    }

    /**
     * Resolves a watermark rectangle and the placement that produced it.
     * If [existing] is set, its center is reused (and clamped so the mark stays inside).
     * Otherwise a random position avoiding the central 30%, or a preset, is used.
     */
    fun resolvePlacement(
        canvas: SizeD,
        mark: SizeD,
        position: WatermarkPosition,
        random: Boolean,
        existing: WatermarkPlacement?,
        rng: Random
    ): Pair<RectD, WatermarkPlacement> {
        if (existing != null) {
            val rect = rectFrom(existing, canvas, mark)
            val placement = WatermarkPlacement(
                normalizedCenter = PointD(
                    x = rect.midX / maxOf(canvas.width, 1.0),
                    y = rect.midY / maxOf(canvas.height, 1.0)
                ),
                isManual = existing.isManual
            )
            return rect to placement
        }
        if (random) {
            val rect = randomRectAvoidingCenter(canvas, mark, rng)
            val placement = WatermarkPlacement(
                normalizedCenter = PointD(
                    x = rect.midX / maxOf(canvas.width, 1.0),
                    y = rect.midY / maxOf(canvas.height, 1.0)
                ),
                isManual = false
            )
            return rect to placement
        }
        val rect = placementRect(canvas, mark, position)
        val placement = WatermarkPlacement(
            normalizedCenter = PointD(
                x = rect.midX / maxOf(canvas.width, 1.0),
                y = rect.midY / maxOf(canvas.height, 1.0)
            ),
            isManual = false
        )
        return rect to placement
    }

    fun rectFrom(placement: WatermarkPlacement, canvas: SizeD, mark: SizeD): RectD {
        val center = clampedCenter(canvas, mark, placement.normalizedCenter)
        return RectD(
            x = center.x * canvas.width - mark.width / 2.0,
            y = center.y * canvas.height - mark.height / 2.0,
            width = mark.width,
            height = mark.height
        )
    }

    fun clampedCenter(canvas: SizeD, mark: SizeD, normalized: PointD): PointD {
        val width = maxOf(canvas.width, 1.0)
        val height = maxOf(canvas.height, 1.0)
        val halfX = (mark.width / 2.0) / width
        val halfY = (mark.height / 2.0) / height
        val minX = minOf(halfX, 1.0 - halfX)
        val maxX = maxOf(halfX, 1.0 - halfX)
        val minY = minOf(halfY, 1.0 - halfY)
        val maxY = maxOf(halfY, 1.0 - halfY)
        return PointD(
            x = minOf(maxOf(normalized.x, minX), maxX),
            y = minOf(maxOf(normalized.y, minY), maxY)
        )
    }

    /**
     * The watermark stays fully inside the canvas. Its center is randomized
     * anywhere except the central 30% × 30% region. If that constraint leaves
     * no room (a very large watermark), a valid in-canvas position is used.
     */
    fun randomRectAvoidingCenter(
        canvas: SizeD,
        mark: SizeD,
        rng: Random
    ): RectD {
        val markWidth = minOf(mark.width, canvas.width)
        val markHeight = minOf(mark.height, canvas.height)
        val halfW = markWidth / 2.0
        val halfH = markHeight / 2.0

        val validMinX = halfW
        val validMaxX = canvas.width - halfW
        val validMinY = halfH
        val validMaxY = canvas.height - halfH

        fun rectAt(centerX: Double, centerY: Double): RectD {
            return RectD(centerX - halfW, centerY - halfH, markWidth, markHeight)
        }

        fun randomInValidBox(): RectD {
            val cx = if (validMinX <= validMaxX) {
                randomInclusive(rng, validMinX, validMaxX)
            } else {
                canvas.width / 2.0
            }
            val cy = if (validMinY <= validMaxY) {
                randomInclusive(rng, validMinY, validMaxY)
            } else {
                canvas.height / 2.0
            }
            return rectAt(cx, cy)
        }

        if (validMinX > validMaxX || validMinY > validMaxY) {
            return rectAt(canvas.width / 2.0, canvas.height / 2.0)
        }

        val inset = excludedCenterFraction / 2.0
        val forbMinX = canvas.width * (0.5 - inset)
        val forbMaxX = canvas.width * (0.5 + inset)
        val forbMinY = canvas.height * (0.5 - inset)
        val forbMaxY = canvas.height * (0.5 + inset)

        val regions = mutableListOf<RectD>()

        fun appendRegion(x: Double, y: Double, width: Double, height: Double) {
            // Axes are judged independently: a zero-height but non-zero-width
            // region (a full-height watermark) must still be sampled, and vice versa.
            if (width >= 0.0 && height >= 0.0 && (width > 0.0 || height > 0.0)) {
                regions.add(RectD(x, y, width, height))
            }
        }

        fun measure(region: RectD): Double {
            val width = maxOf(0.0, region.width)
            val height = maxOf(0.0, region.height)
            if (width > 0.0 && height > 0.0) return width * height
            if (width > 0.0) return width
            if (height > 0.0) return height
            return 0.0
        }

        // Top and bottom strips span the full valid width.
        val topMaxY = minOf(validMaxY, forbMinY)
        appendRegion(validMinX, validMinY, validMaxX - validMinX, topMaxY - validMinY)
        val bottomMinY = maxOf(validMinY, forbMaxY)
        appendRegion(validMinX, bottomMinY, validMaxX - validMinX, validMaxY - bottomMinY)

        // Left and right regions only cover the vertical band of the forbidden box,
        // so corners are not counted twice. Include a zero-height mid band so a
        // full-height watermark can still avoid the center on X.
        val midMinY = maxOf(validMinY, forbMinY)
        val midMaxY = minOf(validMaxY, forbMaxY)
        if (midMaxY >= midMinY) {
            val leftMaxX = minOf(validMaxX, forbMinX)
            appendRegion(validMinX, midMinY, leftMaxX - validMinX, midMaxY - midMinY)
            val rightMinX = maxOf(validMinX, forbMaxX)
            appendRegion(rightMinX, midMinY, validMaxX - rightMinX, midMaxY - midMinY)
        }

        val weights = regions.map(::measure)
        val total = weights.sum()
        if (total <= 0.0) {
            return randomInValidBox()
        }

        var pick = randomExclusiveUpper(rng, total)
        for (index in regions.indices) {
            val region = regions[index]
            val weight = weights[index]
            if (pick < weight || index == regions.lastIndex) {
                val cx = if (region.width <= 0.0) region.midX else randomInclusive(rng, region.minX, region.maxX)
                val cy = if (region.height <= 0.0) region.midY else randomInclusive(rng, region.minY, region.maxY)
                return rectAt(cx, cy)
            }
            pick -= weight
        }
        return randomInValidBox()
    }

    /**
     * Whether a legal in-canvas placement exists whose center is outside the
     * central 30% × 30% exclusion. Used by tests and documentation of the fallback.
     */
    fun canAvoidExcludedCenter(canvas: SizeD, mark: SizeD): Boolean {
        val markWidth = minOf(mark.width, canvas.width)
        val markHeight = minOf(mark.height, canvas.height)
        val halfW = markWidth / 2.0
        val halfH = markHeight / 2.0
        val validMinX = halfW
        val validMaxX = canvas.width - halfW
        val validMinY = halfH
        val validMaxY = canvas.height - halfH
        if (validMinX > validMaxX || validMinY > validMaxY) return false

        val inset = excludedCenterFraction / 2.0
        val forbMinX = canvas.width * (0.5 - inset)
        val forbMaxX = canvas.width * (0.5 + inset)
        val forbMinY = canvas.height * (0.5 - inset)
        val forbMaxY = canvas.height * (0.5 + inset)
        val validXInsideForbidden = validMinX >= forbMinX && validMaxX <= forbMaxX
        val validYInsideForbidden = validMinY >= forbMinY && validMaxY <= forbMaxY
        return !(validXInsideForbidden && validYInsideForbidden)
    }

    /** Bitmap style used when flattening the base and drawing the watermark. */
    enum class ComposeCanvasStyle {
        OPAQUE_WHITE,
        PREMULTIPLIED_LAST;

        companion object {
            fun forOutputFormat(format: OutputFormat): ComposeCanvasStyle {
                return if (format == OutputFormat.JPEG) OPAQUE_WHITE else PREMULTIPLIED_LAST
            }
        }
    }

    /**
     * Closed range, inclusive of both ends, matching Swift's
     * `CGFloat.random(in: min ... max)`.
     */
    internal fun randomInclusive(rng: Random, min: Double, max: Double): Double {
        if (min >= max) return min
        // Map a 53-bit fraction onto the closed interval.
        val fraction = rng.nextLong().toULong() shr 11
        val unit = fraction.toDouble() / (1L shl 53).toDouble()
        return min + (max - min) * unit
    }

    /** Half-open range `0 ..< total`, matching Swift's `CGFloat.random(in: 0 ..< total)`. */
    internal fun randomExclusiveUpper(rng: Random, total: Double): Double {
        if (total <= 0.0) return 0.0
        var unit: Double
        do {
            val fraction = rng.nextLong().toULong() shr 11
            unit = fraction.toDouble() / (1L shl 53).toDouble()
        } while (unit >= 1.0)
        return unit * total
    }
}
