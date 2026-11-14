package app.safephotomark.util

import android.content.Context
import app.safephotomark.R
import app.safephotomark.processing.ImagePipelineError
import app.safephotomark.processing.ImagePipelineException

fun Context.pipelineErrorMessage(error: Throwable): String {
    val kind = (error as? ImagePipelineException)?.kind
    val fallback = error.message
    return when (kind) {
        ImagePipelineError.CANNOT_OPEN_SOURCE -> getString(R.string.error_cannot_open_source)
        ImagePipelineError.EMPTY_SOURCE -> getString(R.string.error_empty_source)
        ImagePipelineError.CANNOT_CREATE_IMAGE -> getString(R.string.error_cannot_create_image)
        ImagePipelineError.CANNOT_CONVERT_COLOR_SPACE -> getString(R.string.error_cannot_convert_color_space)
        ImagePipelineError.CANNOT_CREATE_DESTINATION -> getString(R.string.error_cannot_create_destination)
        ImagePipelineError.FINALIZE_FAILED -> getString(R.string.error_finalize_failed)
        ImagePipelineError.CANNOT_WRITE -> fallback ?: getString(R.string.error_finalize_failed)
        ImagePipelineError.WATERMARK_INVALID -> getString(R.string.error_watermark_invalid)
        ImagePipelineError.CANCELLED -> getString(R.string.error_cancelled)
        ImagePipelineError.UNSUPPORTED_FORMAT -> getString(R.string.error_unsupported_format)
        ImagePipelineError.HEIF_ENCODE_UNAVAILABLE -> getString(R.string.error_heif_encode)
        ImagePipelineError.TARGET_EXISTS -> getString(R.string.error_target_exists)
        ImagePipelineError.REFUSE_OVERWRITE -> getString(R.string.refuse_overwrite)
        ImagePipelineError.OUTPUT_FOLDER_MISSING -> getString(R.string.error_output_folder_missing)
        ImagePipelineError.WATERMARK_MISSING -> getString(R.string.error_watermark_missing)
        null -> fallback ?: error::class.java.simpleName
    }
}
