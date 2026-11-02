package com.remoteapp.host.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidationTest {
    @Test
    fun normalizedCoordinatesMustBeFiniteAndInRange() {
        assertTrue(InputValidation.isNormalized(0f, 1f))
        assertFalse(InputValidation.isNormalized(-0.01f, 0.5f))
        assertFalse(InputValidation.isNormalized(Float.NaN, 0.5f))
        assertFalse(InputValidation.isNormalized(Float.POSITIVE_INFINITY, 0.5f))
    }

    @Test
    fun durationIsBoundedForAccessibilityGestures() {
        assertEquals(80L, InputValidation.durationMs(1))
        assertEquals(450L, InputValidation.durationMs(450))
        assertEquals(10_000L, InputValidation.durationMs(50_000))
    }
}
