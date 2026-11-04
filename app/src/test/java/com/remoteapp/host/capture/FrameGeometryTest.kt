package com.remoteapp.host.capture

import org.junit.Assert.*
import org.junit.Test

class FrameGeometryTest {
    @Test
    fun normalizedPointsMapToSourcePixelsAtEveryResolution() {
        for ((width, height) in listOf(720 to 1280, 1080 to 2400, 1440 to 3200, 2208 to 1840, 3840 to 2160)) {
            val (encodedWidth, encodedHeight) = FrameGeometry.encodedSize(width, height)
            val geometry = FrameGeometry(width, height, encodedWidth, encodedHeight, 0, 1)
            assertEquals(0 to 0, geometry.point(0f, 0f))
            assertEquals(width - 1 to height - 1, geometry.point(1f, 1f))
            assertEquals(width / 2 to height / 2, geometry.point(.5f, .5f))
        }
    }

    @Test
    fun scaledPortraitAndLandscapeMaintainAspectAndPixelBudget() {
        for ((width, height) in listOf(1440 to 3200, 3200 to 1440, 2208 to 1840, 721 to 1601)) {
            val (w, h) = FrameGeometry.encodedSize(width, height)
            assertTrue(w <= 1920 && h <= 1920)
            assertEquals(width.toDouble() / height, w.toDouble() / h, .002)
        }
        assertEquals(720 to 1280, FrameGeometry.encodedSize(720, 1280))
    }

    @Test
    fun rotationDoesNotSubtractSystemBarsOrUseEncodedCoordinates() {
        val portrait = FrameGeometry(1440, 3200, 864, 1920, 0, 10)
        val landscape = FrameGeometry(3200, 1440, 1920, 864, 90, 11)
        assertEquals(360 to 800, portrait.point(.25f, .25f))
        assertEquals(800 to 360, landscape.point(.25f, .25f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDisplayDimensionsAreRejected() {
        FrameGeometry.encodedSize(0, 1920)
    }
}
