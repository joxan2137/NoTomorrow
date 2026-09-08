package app.notomorrow.util

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * The pixel maths only — decoding needs a real `Bitmap`, so the rest of
 * [ImageDownscaler] belongs to the instrumented suite.
 */
class ImageDownscalerSizeTest {

    @Test
    fun `a 4032 by 3024 photo floors to exactly 1024 by 768`() {
        assertEquals(1024 to 768, ImageDownscaler.targetSize(4032, 3024))
        assertEquals(768 to 1024, ImageDownscaler.targetSize(3024, 4032))
    }

    @Test
    fun `an already small image is untouched`() {
        assertEquals(800 to 600, ImageDownscaler.targetSize(800, 600))
        assertEquals(1024 to 1024, ImageDownscaler.targetSize(1024, 1024))
    }

    @Test
    fun `degenerate sizes are rejected`() {
        assertNull(ImageDownscaler.targetSize(0, 10))
        assertNull(ImageDownscaler.targetSize(10, 0))
        assertNull(ImageDownscaler.targetSize(20_000, 3))   // the short edge would floor to 0
    }

    @Test
    fun `sample size is the largest power of two that still covers the target`() {
        assertEquals(2, ImageDownscaler.sampleSize(4032, 3024, 1024, 768))
        assertEquals(1, ImageDownscaler.sampleSize(800, 600, 800, 600))
        assertEquals(4, ImageDownscaler.sampleSize(8000, 6000, 1024, 768))
    }
}
