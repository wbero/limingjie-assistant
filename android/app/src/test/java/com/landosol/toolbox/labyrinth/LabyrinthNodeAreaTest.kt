package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabyrinthNodeAreaTest {
    @Test fun `every node id decodes to the area in its ten-thousands digit`() {
        assertEquals(2, labyrinthNodeAreaOf(20303))
        assertEquals(3, labyrinthNodeAreaOf(30601))
        assertEquals(4, labyrinthNodeAreaOf(40801))
        assertEquals(5, labyrinthNodeAreaOf(50701))
    }

    @Test fun `ids outside the five areas decode to null`() {
        assertNull(labyrinthNodeAreaOf(999))
        assertNull(labyrinthNodeAreaOf(0))
        assertNull(labyrinthNodeAreaOf(60001))
        assertNull(labyrinthNodeAreaOf(null))
    }
}
