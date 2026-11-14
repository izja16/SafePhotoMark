package app.safephotomark

import app.safephotomark.processing.ExportCoordinator
import app.safephotomark.processing.ImageExporterFormats
import app.safephotomark.processing.MetadataCopier
import app.safephotomark.processing.OutputFormat
import app.safephotomark.processing.OutputPath
import app.safephotomark.processing.SourceImageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OutputPathTests {
    @Test
    fun preferredNameUsesSafePhotoMarkSuffix() {
        val folder = temporaryFolder()
        try {
            val reserved = mutableSetOf<String>()
            val existing = folder.list()?.toSet() ?: emptySet()
            val name = OutputPath.uniqueName(
                originalStem = "vacation",
                ext = "jpg",
                randomRename = false,
                reserved = reserved,
                exists = { existing.contains(it) || File(folder, it).exists() }
            )
            assertEquals("vacation_safephotomark.jpg", name)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun uniquifyAppendsNumericSuffix() {
        val folder = temporaryFolder()
        try {
            File(folder, "vacation_safephotomark.jpg").writeText("")
            val reserved = mutableSetOf<String>()
            val name = OutputPath.uniqueName(
                originalStem = "vacation",
                ext = "jpg",
                randomRename = false,
                reserved = reserved,
                exists = { File(folder, it).exists() }
            )
            assertEquals("vacation_safephotomark-2.jpg", name)
            File(folder, name).writeText("")
            val third = OutputPath.uniqueName(
                originalStem = "vacation",
                ext = "jpg",
                randomRename = false,
                reserved = reserved,
                exists = { File(folder, it).exists() }
            )
            assertEquals("vacation_safephotomark-3.jpg", third)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun randomRenameDoesNotUseOriginalStem() {
        val folder = temporaryFolder()
        try {
            val reserved = mutableSetOf<String>()
            val name = OutputPath.uniqueName(
                originalStem = "vacation-photo",
                ext = "png",
                randomRename = true,
                reserved = reserved,
                exists = { File(folder, it).exists() }
            )
            val stem = name.substringBeforeLast('.')
            assertEquals("png", name.substringAfterLast('.'))
            assertEquals(12, stem.length)
            assertFalse(stem.contains("vacation"))
            val allowed = OutputPath.randomAlphabet.toSet()
            assertTrue(stem.toSet().all { it in allowed })
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun reservedSetPreventsDuplicateNamesWithoutTouchingDisk() {
        val reserved = mutableSetOf<String>()
        val first = OutputPath.uniqueName("vacation", "jpg", false, reserved = reserved, exists = { false })
        assertEquals("vacation_safephotomark.jpg", first)
        val second = OutputPath.uniqueName("vacation", "jpg", false, reserved = reserved, exists = { false })
        assertEquals("vacation_safephotomark-2.jpg", second)
        assertEquals(2, reserved.size)
    }

    private fun temporaryFolder(): File {
        val folder = File(System.getProperty("java.io.tmpdir"), "SafePhotoMarkTests-${java.util.UUID.randomUUID()}")
        folder.mkdirs()
        return folder
    }
}

class FormatAndMetadataUtilityTests {
    @Test
    fun sameAsSourceMappingMatchesMac() {
        assertEquals(OutputFormat.JPEG, SourceImageKind.JPEG.preferredSameAsSourceFormat)
        assertEquals(OutputFormat.PNG, SourceImageKind.PNG.preferredSameAsSourceFormat)
        assertEquals(OutputFormat.HEIF, SourceImageKind.HEIF.preferredSameAsSourceFormat)
        assertEquals(OutputFormat.TIFF, SourceImageKind.TIFF.preferredSameAsSourceFormat)
        assertEquals(OutputFormat.PNG, SourceImageKind.WEBP.preferredSameAsSourceFormat)
        assertEquals(OutputFormat.JPEG, SourceImageKind.GIF.preferredSameAsSourceFormat)
        assertEquals(OutputFormat.JPEG, SourceImageKind.BMP.preferredSameAsSourceFormat)
        assertEquals(OutputFormat.JPEG, SourceImageKind.OTHER.preferredSameAsSourceFormat)
        assertEquals(OutputFormat.JPEG, ImageExporterFormats.resolvedFormat(OutputFormat.SAME_AS_SOURCE, SourceImageKind.GIF))
        assertEquals(OutputFormat.PNG, ImageExporterFormats.resolvedFormat(OutputFormat.SAME_AS_SOURCE, SourceImageKind.WEBP))
        assertEquals(OutputFormat.TIFF, ImageExporterFormats.resolvedFormat(OutputFormat.TIFF, SourceImageKind.JPEG))
    }

    @Test
    fun filteredMetadataDropsGeometryThumbsAndKeepsGpsWhenPreserving() {
        assertTrue(MetadataCopier.shouldDropTag("ThumbnailImage"))
        assertTrue(MetadataCopier.shouldDropTag("ThumbnailOffset"))
        assertTrue(MetadataCopier.shouldDropTag("Orientation"))
        assertTrue(MetadataCopier.shouldDropTag("ImageWidth"))
        assertFalse(MetadataCopier.shouldDropTag("GPSLatitude"))
        assertFalse(MetadataCopier.shouldDropTag("DateTimeOriginal"))
        assertFalse(MetadataCopier.shouldDropTag("Artist"))
    }

    @Test
    fun recommendedConcurrencyIsConservativeOn16GBClassMachines() {
        val gig = 1024L * 1024L * 1024L
        assertEquals(2, ExportCoordinator.recommendedConcurrency(16 * gig))
        assertEquals(2, ExportCoordinator.recommendedConcurrency(8 * gig))
        assertEquals(3, ExportCoordinator.recommendedConcurrency(24 * gig))
        assertEquals(4, ExportCoordinator.recommendedConcurrency(32 * gig))
        assertEquals(4, ExportCoordinator.recommendedConcurrency(64 * gig))
    }
}
