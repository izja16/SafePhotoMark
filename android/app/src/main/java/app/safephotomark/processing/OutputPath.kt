package app.safephotomark.processing

import kotlin.random.Random

object OutputPath {
    const val defaultSuffix: String = "_safephotomark"
    const val randomStemLength: Int = 12
    const val randomAlphabet: String = "abcdefghijkmnopqrstuvwxyz23456789"

    fun uniqueName(
        originalStem: String,
        ext: String,
        randomRename: Boolean,
        suffix: String = defaultSuffix,
        reserved: MutableSet<String>,
        exists: (String) -> Boolean
    ): String {
        if (randomRename) {
            return uniqueRandomName(ext, reserved, exists)
        }
        val base = "$originalStem$suffix"
        return uniquify("$base.$ext", base, ext, reserved, exists)
    }

    private fun uniqueRandomName(
        ext: String,
        reserved: MutableSet<String>,
        exists: (String) -> Boolean
    ): String {
        repeat(32) {
            val stem = randomStem()
            val candidate = "$stem.$ext"
            val key = reservationKey(candidate)
            if (key !in reserved && !exists(candidate)) {
                reserved.add(key)
                return candidate
            }
        }
        val fallback = randomStem()
        return uniquify("$fallback.$ext", fallback, ext, reserved, exists)
    }

    private fun uniquify(
        initial: String,
        stem: String,
        ext: String,
        reserved: MutableSet<String>,
        exists: (String) -> Boolean
    ): String {
        var candidate = initial
        var n = 2
        while (reservationKey(candidate) in reserved || exists(candidate)) {
            candidate = "$stem-$n.$ext"
            n += 1
        }
        reserved.add(reservationKey(candidate))
        return candidate
    }

    fun reservationKey(filename: String): String = filename

    fun randomStem(length: Int = randomStemLength, rng: Random = Random.Default): String {
        val alphabet = randomAlphabet
        return buildString(length) {
            repeat(length) {
                append(alphabet[rng.nextInt(alphabet.length)])
            }
        }
    }
}

object ImageExporterFormats {
    fun resolvedFormat(requested: OutputFormat, source: SourceImageKind): OutputFormat {
        return if (requested == OutputFormat.SAME_AS_SOURCE) {
            source.preferredSameAsSourceFormat
        } else {
            requested
        }
    }

    fun fileExtension(format: OutputFormat): String {
        return when (format) {
            OutputFormat.JPEG -> "jpg"
            OutputFormat.PNG -> "png"
            OutputFormat.HEIF -> "heic"
            OutputFormat.TIFF -> "tiff"
            OutputFormat.SAME_AS_SOURCE ->
                error("Output format must be resolved before asking for a file extension")
        }
    }

    fun mimeType(format: OutputFormat): String {
        return when (format) {
            OutputFormat.JPEG -> "image/jpeg"
            OutputFormat.PNG -> "image/png"
            OutputFormat.HEIF -> "image/heic"
            OutputFormat.TIFF -> "image/tiff"
            OutputFormat.SAME_AS_SOURCE ->
                error("Output format must be resolved before asking for a MIME type")
        }
    }
}
