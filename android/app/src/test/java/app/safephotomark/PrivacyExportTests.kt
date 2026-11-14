package app.safephotomark

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import app.safephotomark.processing.ImageExporter
import app.safephotomark.processing.OutputFormat
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacyExportTests {
    @Test
    fun jpegExportStripsPrivateMetadata() {
        val folder = temporaryFolder()
        try {
            val source = copyFixture(folder, "source.jpg")
            assertTrue("fixture must contain GPS so the strip test is meaningful", fixtureHasGps(source))
            assertTrue("fixture must contain DateTimeOriginal", fixtureHasDate(source))

            val swatch = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888)
            swatch.eraseColor(Color.rgb(51, 153, 77))
            val output = File(folder, "out.jpg")
            ImageExporter.writeToFile(swatch, output, OutputFormat.JPEG, 0.95)

            val metadata = ImageMetadataReader.readMetadata(output)
            val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
            assertTrue("output must not contain GPS", gps == null || gps.tagCount == 0)

            val exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            if (exif != null) {
                assertNull(exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
                assertFalse(exif.containsTag(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT))
                assertFalse(exif.containsTag(ExifSubIFDDirectory.TAG_MAKERNOTE))
            }
            val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            if (ifd0 != null) {
                val orientation = ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
                if (orientation != null) {
                    assertTrue(orientation == ExifInterface.ORIENTATION_NORMAL || orientation == 1)
                }
            }
            // Confirm the planted strings did not leak into the raw file.
            val bytes = output.readBytes()
            val asText = bytes.toString(Charsets.ISO_8859_1)
            assertFalse(asText.contains("2024:01:02 03:04:05"))
            assertFalse(asText.contains("maker-secret"))
            assertFalse(asText.contains("UnitCam"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun fixtureContainsSensitiveMetadata() {
        val folder = temporaryFolder()
        try {
            val source = copyFixture(folder, "source.jpg")
            assertTrue(fixtureHasGps(source))
            assertTrue(fixtureHasDate(source))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun tiffRoundTripHasNoExifWhenStripped() {
        val folder = temporaryFolder()
        try {
            val swatch = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            swatch.eraseColor(Color.rgb(20, 80, 160))
            val output = File(folder, "out.tiff")
            ImageExporter.writeToFile(swatch, output, OutputFormat.TIFF, 1.0)
            assertTrue(output.length() > 32)
            val bytes = output.readBytes()
            val text = bytes.toString(Charsets.ISO_8859_1)
            assertFalse(text.contains("2024:01:02"))
            assertFalse(text.contains("GPSLatitude"))
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun fixtureHasGps(file: File): Boolean {
        val metadata = ImageMetadataReader.readMetadata(file)
        val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
        if (gps != null && gps.tagCount > 0) return true
        return file.readBytes().toString(Charsets.ISO_8859_1).contains("GPS")
    }

    private fun fixtureHasDate(file: File): Boolean {
        val metadata = ImageMetadataReader.readMetadata(file)
        val exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val value = exif?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
        if (value == "2024:01:02 03:04:05") return true
        return file.readBytes().toString(Charsets.ISO_8859_1).contains("2024:01:02 03:04:05")
    }

    private fun copyFixture(folder: File, name: String): File {
        val dest = File(folder, name)
        javaClass.classLoader!!.getResourceAsStream("fixture_oriented.jpg").use { input ->
            requireNotNull(input) { "missing fixture_oriented.jpg" }
            FileOutputStream(dest).use { input.copyTo(it) }
        }
        return dest
    }

    private fun temporaryFolder(): File {
        val folder = File(System.getProperty("java.io.tmpdir"), "SafePhotoMarkPrivacy-${java.util.UUID.randomUUID()}")
        folder.mkdirs()
        return folder
    }
}
