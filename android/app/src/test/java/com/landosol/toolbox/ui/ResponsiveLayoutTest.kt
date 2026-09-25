package com.landosol.toolbox.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLayoutTest {
    @Test
    fun `short landscape uses compact typography and side navigation`() {
        assertTrue(isCompactLandscape(widthDp = 960, heightDp = 540))
        assertTrue(shouldUseNavigationRail(widthDp = 960, heightDp = 540))
    }

    @Test
    fun `portrait keeps standard typography and bottom navigation`() {
        assertFalse(isCompactLandscape(widthDp = 411, heightDp = 891))
        assertFalse(shouldUseNavigationRail(widthDp = 411, heightDp = 891))
    }

    @Test
    fun `tall landscape keeps standard typography but uses side navigation`() {
        assertFalse(isCompactLandscape(widthDp = 1280, heightDp = 800))
        assertTrue(shouldUseNavigationRail(widthDp = 1280, heightDp = 800))
    }
}
