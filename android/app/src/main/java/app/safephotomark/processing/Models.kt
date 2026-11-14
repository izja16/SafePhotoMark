package app.safephotomark.processing

enum class OutputFormat {
    JPEG, PNG, HEIF, TIFF, SAME_AS_SOURCE;

    val usesLossyQuality: Boolean
        get() = this == JPEG || this == HEIF || this == SAME_AS_SOURCE
}

enum class WatermarkMode {
    MANUAL, RANDOM, FIXED;

    val placesRandomly: Boolean get() = this == RANDOM
}

enum class WatermarkPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    MIDDLE_LEFT, CENTER, MIDDLE_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

/**
 * Per-photo watermark placement. Coordinates are normalized (0...1) in
 * top-left image space, with y growing downward. The stored point is the
 * watermark's center.
 */
data class WatermarkPlacement(
    val centerX: Double,
    val centerY: Double,
    val isManual: Boolean
) {
    val normalizedCenter: PointD get() = PointD(centerX, centerY)

    constructor(normalizedCenter: PointD, isManual: Boolean) : this(
        centerX = normalizedCenter.x,
        centerY = normalizedCenter.y,
        isManual = isManual
    )
}

enum class PhotoStatusKind { WAITING, PROCESSING, SUCCEEDED, FAILED }

data class PhotoStatus(
    val kind: PhotoStatusKind,
    val outputName: String? = null,
    val message: String? = null
) {
    companion object {
        val Waiting = PhotoStatus(PhotoStatusKind.WAITING)
        val Processing = PhotoStatus(PhotoStatusKind.PROCESSING)
        fun succeeded(outputName: String) = PhotoStatus(PhotoStatusKind.SUCCEEDED, outputName = outputName)
        fun failed(message: String) = PhotoStatus(PhotoStatusKind.FAILED, message = message)
    }
}

enum class SourceImageKind {
    JPEG, PNG, HEIF, TIFF, GIF, BMP, WEBP, OTHER;

    /**
     * Mapping used by the “same as source” output option.
     * GIF / BMP / unknown → JPEG; WebP → PNG (this app does not write WebP,
     * so a possible alpha channel can be kept).
     */
    val preferredSameAsSourceFormat: OutputFormat
        get() = when (this) {
            JPEG -> OutputFormat.JPEG
            PNG -> OutputFormat.PNG
            HEIF -> OutputFormat.HEIF
            TIFF -> OutputFormat.TIFF
            WEBP -> OutputFormat.PNG
            GIF, BMP, OTHER -> OutputFormat.JPEG
        }
}

class ImagePipelineException(val kind: ImagePipelineError, message: String? = null) : Exception(message)

enum class ImagePipelineError {
    CANNOT_OPEN_SOURCE,
    EMPTY_SOURCE,
    CANNOT_CREATE_IMAGE,
    CANNOT_CONVERT_COLOR_SPACE,
    CANNOT_CREATE_DESTINATION,
    FINALIZE_FAILED,
    CANNOT_WRITE,
    WATERMARK_INVALID,
    CANCELLED,
    UNSUPPORTED_FORMAT,
    HEIF_ENCODE_UNAVAILABLE,
    TARGET_EXISTS,
    REFUSE_OVERWRITE,
    OUTPUT_FOLDER_MISSING,
    WATERMARK_MISSING
}

data class ExportSettings(
    val sizePercent: Double,
    val position: WatermarkPosition,
    val watermarkMode: WatermarkMode,
    val opacity: Double,
    val outputFormat: OutputFormat,
    val quality: Double,
    val stripMetadata: Boolean,
    val randomRename: Boolean
)

data class DecodedImage(
    val bitmap: android.graphics.Bitmap,
    val kind: SourceImageKind,
    val sourceMime: String
)

object SupportedTypes {
    val imageExtensions: Set<String> = setOf(
        "jpg", "jpeg", "jpe", "jfif",
        "png",
        "heic", "heif", "hif",
        "tif", "tiff",
        "gif",
        "bmp", "dib",
        "webp"
    )

    val watermarkExtensions: Set<String> = setOf("png", "svg")

    val imageMimeTypes: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/heic",
        "image/heif",
        "image/heic-sequence",
        "image/heif-sequence",
        "image/tiff",
        "image/gif",
        "image/bmp",
        "image/x-ms-bmp",
        "image/webp"
    )

    val watermarkMimeTypes: Set<String> = setOf(
        "image/png",
        "image/svg+xml",
        "text/xml",
        "application/xml"
    )

    fun isSupportedImage(filename: String?, mime: String? = null): Boolean {
        val ext = filename?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in imageExtensions) return true
        val lowered = mime?.lowercase().orEmpty()
        if (lowered in imageMimeTypes) return true
        return lowered.startsWith("image/") && ext != "svg" && !lowered.contains("svg")
    }

    fun isWatermarkFile(filename: String?, mime: String? = null): Boolean {
        val ext = filename?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in watermarkExtensions) return true
        val lowered = mime?.lowercase().orEmpty()
        return lowered in watermarkMimeTypes && (ext == "svg" || ext == "png" || lowered.contains("svg") || lowered == "image/png")
    }
}

data class PointD(val x: Double, val y: Double)

data class SizeD(val width: Double, val height: Double) {
    val isEmpty: Boolean get() = width <= 0.0 || height <= 0.0
}

data class RectD(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX: Double get() = x
    val minY: Double get() = y
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height
    val midX: Double get() = x + width / 2.0
    val midY: Double get() = y + height / 2.0
}
