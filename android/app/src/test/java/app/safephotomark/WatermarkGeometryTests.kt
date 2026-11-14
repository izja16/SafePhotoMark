package app.safephotomark

import app.safephotomark.processing.SizeD
import app.safephotomark.processing.WatermarkCompositor
import app.safephotomark.processing.WatermarkPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WatermarkGeometryTests {
    @Test
    fun tenThousandRandomPlacementsStayInCanvasAndAvoidCenter() {
        val cases = listOf(
            Triple(SizeD(4032.0, 3024.0), 20.0, 0.2), // full-height watermark from the review
            Triple(SizeD(4032.0, 3024.0), 20.0, 1.5),
            Triple(SizeD(4032.0, 3024.0), 5.0, 1.0),
            Triple(SizeD(4032.0, 3024.0), 50.0, 1.0),
            Triple(SizeD(8064.0, 6048.0), 20.0, 0.2),
            Triple(SizeD(1000.0, 1000.0), 20.0, 1.0),
            Triple(SizeD(1200.0, 800.0), 80.0, 3.0)
        )
        for ((canvas, percent, aspect) in cases) {
            assertPlacements(canvas, percent, aspect, iterations = 10_000)
        }
    }

    @Test
    fun fullHeightCaseCanAvoidCenterOnX() {
        val canvas = SizeD(4032.0, 3024.0)
        val mark = WatermarkCompositor.markSize(canvas, 20.0, 0.2)
        assertEquals(canvas.height, mark.height, 0.01)
        assertTrue(WatermarkCompositor.canAvoidExcludedCenter(canvas, mark))
    }

    @Test
    fun nineGridMarginsCollapseWhenMarkFillsCanvas() {
        val canvas = SizeD(1000.0, 1000.0)
        val huge = SizeD(990.0, 990.0)
        for (position in WatermarkPosition.entries) {
            val rect = WatermarkCompositor.placementRect(canvas, huge, position)
            assertTrue("minX ${rect.minX}", rect.minX >= -0.001)
            assertTrue("minY ${rect.minY}", rect.minY >= -0.001)
            assertTrue("maxX ${rect.maxX}", rect.maxX <= canvas.width + 0.001)
            assertTrue("maxY ${rect.maxY}", rect.maxY <= canvas.height + 0.001)
        }
    }

    private fun assertPlacements(canvas: SizeD, percent: Double, aspect: Double, iterations: Int) {
        val mark = WatermarkCompositor.markSize(canvas, percent, aspect)
        val canAvoid = WatermarkCompositor.canAvoidExcludedCenter(canvas, mark)
        val rng = Random.Default
        var insideForbidden = 0

        repeat(iterations) {
            val rect = WatermarkCompositor.randomRectAvoidingCenter(canvas, mark, rng)
            assertTrue("out of canvas minX $rect $canvas $percent $aspect", rect.minX >= -0.001)
            assertTrue("out of canvas minY", rect.minY >= -0.001)
            assertTrue("out of canvas maxX", rect.maxX <= canvas.width + 0.001)
            assertTrue("out of canvas maxY", rect.maxY <= canvas.height + 0.001)

            val nx = rect.midX / canvas.width
            val ny = rect.midY / canvas.height
            val lo = 0.35
            val hi = 0.65
            val inForbidden = nx > lo && nx < hi && ny > lo && ny < hi
            if (inForbidden) insideForbidden += 1
        }

        if (canAvoid) {
            assertEquals(
                "center entered the excluded 30%×30% region $insideForbidden times for canvas $canvas, percent $percent, aspect $aspect",
                0,
                insideForbidden
            )
        }
    }
}
