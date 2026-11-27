package com.maktabah.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupedCardDecorationTest {

    @Test
    fun collapsedHeaderHeightPreservesFullCompactRadius() {
        val radius = GroupedCardDecoration.COMPACT_HEADER_CORNER_RADIUS_DP
        val collapsedScale = GroupedCardDecoration.radiusScale(radius, radius, 46f)
        val expandedScale = GroupedCardDecoration.radiusScale(radius, radius, 136f)
        assertEquals(1f, collapsedScale, 0.0001f)
        assertEquals(1f, expandedScale, 0.0001f)
        assertEquals(radius * collapsedScale, radius * expandedScale, 0.0001f)
    }

    @Test
    fun collapsedLibraryHeightPreservesFullDefaultRadius() {
        val radius = GroupedCardDecoration.DEFAULT_CORNER_RADIUS_DP
        val collapsedScale = GroupedCardDecoration.radiusScale(radius, radius, 48f)
        val expandedScale = GroupedCardDecoration.radiusScale(radius, radius, 96f)
        assertEquals(1f, collapsedScale, 0.0001f)
        assertEquals(1f, expandedScale, 0.0001f)
        assertEquals(radius * collapsedScale, radius * expandedScale, 0.0001f)
    }

    @Test
    fun clampsProportionallyWhenHeightIsSmallerThanCombinedRadii() {
        val bothScale = GroupedCardDecoration.radiusScale(
            topRadius = 24f,
            bottomRadius = 24f,
            height = 24f,
        )
        assertEquals(12f, 24f * bothScale, 0.0001f)

        val singleScale = GroupedCardDecoration.radiusScale(
            topRadius = 24f,
            bottomRadius = 0f,
            height = 12f,
        )
        assertEquals(12f, 24f * singleScale, 0.0001f)
    }
}
