package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabyrinthEventOcrCacheDiagnosticsTest {
    private val crop = EntryPixelRect(150, 95, 1620, 750)
    private val key = LabyrinthEventOcrCache.Key(0L, crop)

    @Test
    fun `expiry reports why recognized text disappears before the six second click retry`() {
        val cache = LabyrinthEventOcrCache()
        val request = requireNotNull(cache.begin(key, 0L))
        cache.complete(request, "石板事件", 800L)
        assertEquals("石板事件", cache.lookup(key, 5_799L).text)
        val expired = cache.lookup(key, 5_800L)
        assertNull(expired.text)
        assertEquals("EXPIRED", expired.diagnostics.status)
        assertEquals(5_000L, expired.diagnostics.ageMillis)
        assertEquals(800L, expired.diagnostics.lastRequestDurationMillis)
        assertEquals(request, expired.diagnostics.cachedRequestId)
        assertEquals("EXPIRED", cache.lookup(key, 6_000L).diagnostics.status)
    }

    @Test
    fun `fresh text distinguishes a changed crop from a changed frame fingerprint`() {
        val cache = LabyrinthEventOcrCache()
        cache.complete(requireNotNull(cache.begin(key, 0L)), "石板事件", 100L)
        val movedCrop = crop.copy(left = 151)
        val moved = cache.lookup(key.copy(rect = movedCrop), 200L)
        assertNull(moved.text)
        assertEquals("CROP_CHANGED", moved.diagnostics.status)
        assertEquals(crop, moved.diagnostics.cachedCropRect)
        assertEquals("HIT", cache.lookup(key.copy(fingerprint = 255L), 200L).diagnostics.status)
        val changed = cache.lookup(key.copy(fingerprint = 511L), 200L)
        assertNull(changed.text)
        assertEquals("FINGERPRINT_CHANGED", changed.diagnostics.status)
        assertEquals(9, changed.diagnostics.fingerprintDistance)
    }

    @Test
    fun `empty OCR and obsolete callbacks remain distinguishable from a pending request`() {
        val cache = LabyrinthEventOcrCache()
        val first = requireNotNull(cache.begin(key, 0L))
        val pending = cache.lookup(key, 100L).diagnostics
        assertEquals("NO_CACHED_TEXT", pending.status)
        assertEquals(first, pending.pendingRequestId)
        assertEquals(100L, pending.pendingAgeMillis)
        val second = requireNotNull(cache.begin(key, 5_000L))
        cache.complete(first, "过期请求返回的文字", 5_100L)
        assertNull(cache.lookup(key, 5_100L).text)
        assertEquals(second, cache.lookup(key, 5_100L).diagnostics.pendingRequestId)
        cache.complete(second, "   ", 5_200L)
        val empty = cache.lookup(key, 5_201L).diagnostics
        assertNull(empty.pendingRequestId)
        assertEquals(second, empty.lastCompletedRequestId)
        assertEquals(0, empty.lastResultTextLength)
        assertEquals("EMPTY_TEXT", empty.lastResultStatus)
        cache.complete(requireNotNull(cache.begin(key, 5_201L)), null, 5_202L)
        assertEquals("FAILED_OR_UNAVAILABLE", cache.lookup(key, 5_202L).diagnostics.lastResultStatus)
        cache.clear()
        assertNull(cache.lookup(key, 5_202L).diagnostics.lastCompletedRequestId)
    }
}
