package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.lang.ref.WeakReference
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The observed side of the icon-matcher sampling must live only for the duration of one [match]
 * call. If a change stores the sampled frame in a field, the whole-frame [PixelImage] (~7.9 MiB at
 * 1920x1080) would be retained until the next match, which is exactly the regression this test
 * guards against.
 */
class LabyrinthCharacterIconMatcherLifetimeTest {

    @Test
    fun `match does not retain the observed frame after it returns`() {
        // A single synthetic template is enough to make the coarse and fine passes sample the
        // observed side. The pixel values do not matter here, only whether the frame is retained.
        val matcher = LabyrinthCharacterIconMatcher(
            listOf(
                LabyrinthBattleCharacterTemplate(
                    characterId = "lifetime",
                    displayName = "lifetime",
                    iconVariant = "1",
                    image = PixelImage(128, 100, IntArray(128 * 100)),
                ),
            ),
        )

        // matchThenDrop returns only a weak reference; once it returns, no strong reference to the
        // whole-frame PixelImage remains anywhere in this test.
        val weakFrame = matchThenDrop(matcher)

        // The matcher must not retain the frame once match() returned. System.gc() is best-effort,
        // so retry a bounded number of times, nudging a collection with a little garbage each round.
        repeat(100) {
            if (weakFrame.get() == null) return
            System.gc()
            System.runFinalization()
            IntArray(256 * 256) // allocate churn to nudge a full collection
            Thread.sleep(5)
        }
        assertNull("the matcher retained the whole frame after match() returned", weakFrame.get())
    }

    private fun matchThenDrop(matcher: LabyrinthCharacterIconMatcher): WeakReference<PixelImage> {
        val frame = PixelImage(1920, 1080, IntArray(1920 * 1080))
        val weak = WeakReference(frame)
        matcher.match(frame, EntryPixelRect(122, 243, 96, 96))
        return weak
    }
}
