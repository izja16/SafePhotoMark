package app.safephotomark.processing

object SourceKind {
    fun kind(filename: String?, mime: String? = null, header: ByteArray? = null): SourceImageKind {
        header?.let { fromMagic(it) }?.let { return it }
        val lowered = mime?.lowercase().orEmpty()
        when {
            lowered == "image/jpeg" || lowered == "image/jpg" -> return SourceImageKind.JPEG
            lowered == "image/png" -> return SourceImageKind.PNG
            lowered.contains("heic") || lowered.contains("heif") -> return SourceImageKind.HEIF
            lowered == "image/tiff" || lowered == "image/tif" -> return SourceImageKind.TIFF
            lowered == "image/gif" -> return SourceImageKind.GIF
            lowered == "image/bmp" || lowered == "image/x-ms-bmp" -> return SourceImageKind.BMP
            lowered == "image/webp" -> return SourceImageKind.WEBP
        }
        return when (filename?.substringAfterLast('.', "")?.lowercase()) {
            "jpg", "jpeg", "jpe", "jfif" -> SourceImageKind.JPEG
            "png" -> SourceImageKind.PNG
            "heic", "heif", "hif" -> SourceImageKind.HEIF
            "tif", "tiff" -> SourceImageKind.TIFF
            "gif" -> SourceImageKind.GIF
            "bmp", "dib" -> SourceImageKind.BMP
            "webp" -> SourceImageKind.WEBP
            else -> SourceImageKind.OTHER
        }
    }

    fun fromMagic(header: ByteArray): SourceImageKind? {
        if (header.size >= 3 &&
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
        ) return SourceImageKind.JPEG
        if (header.size >= 8 &&
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
        ) return SourceImageKind.PNG
        if (header.size >= 6 &&
            header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte()
        ) return SourceImageKind.GIF
        if (header.size >= 2 && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
            return SourceImageKind.BMP
        }
        if (header.size >= 4) {
            val ii = header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 42.toByte() && header[3] == 0.toByte()
            val mm = header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() &&
                header[2] == 0.toByte() && header[3] == 42.toByte()
            if (ii || mm) return SourceImageKind.TIFF
        }
        if (header.size >= 12 &&
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
        ) return SourceImageKind.WEBP
        if (header.size >= 12 && isHeifBrand(header)) return SourceImageKind.HEIF
        return null
    }

    private fun isHeifBrand(header: ByteArray): Boolean {
        // box size (4) + 'ftyp' (4) + major brand (4)
        if (header.size < 12) return false
        if (header[4] != 'f'.code.toByte() || header[5] != 't'.code.toByte() ||
            header[6] != 'y'.code.toByte() || header[7] != 'p'.code.toByte()
        ) return false
        val brands = ArrayList<String>()
        fun four(offset: Int): String? {
            if (offset + 4 > header.size) return null
            return String(header, offset, 4, Charsets.US_ASCII)
        }
        four(8)?.let { brands.add(it) }
        var offset = 16
        while (offset + 4 <= header.size) {
            four(offset)?.let { brands.add(it) }
            offset += 4
        }
        val heif = setOf(
            "heic", "heix", "hevc", "hevx",
            "heim", "heis", "hevm", "hevs",
            "mif1", "msf1", "heif"
        )
        return brands.any { it.lowercase() in heif }
    }
}
