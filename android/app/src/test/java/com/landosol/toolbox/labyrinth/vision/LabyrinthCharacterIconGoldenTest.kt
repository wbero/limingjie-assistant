package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Bit-exact regression net for [LabyrinthCharacterIconMatcher].
 *
 * The battle-team card pass is the single most expensive stage of a labyrinth run (about 44% of all
 * recognition time, measured on two 2026-09-23 runs), and it is called a dozen times per failed card
 * because of [LabyrinthCharacterIconMatcher.refineMemberGeometry]. That makes it the next place
 * where "make it faster without changing a single decision" is easy to get wrong, so it gets the
 * same treatment as the shared template matcher: pin the exact bits of every number it produces.
 *
 * What is pinned, using the real icon pack and real team pages rather than synthetic shapes (the
 * existing team tests build their own pixels, which never exercises the 801-template coarse pass):
 *
 *  * The whole [LabyrinthCharacterIconMatcher.match] result over two different team-page frames, a
 *    fixed grid of rects, four masks and three passes (unrestricted, attribute-filtered, and
 *    roster-filtered) -- so a switch of frame, rectangle or mask is pinned, not just one path.
 *  * One forced [LabyrinthCharacterIconMatcher.refineMemberGeometry] run per frame and rect, which
 *    is the 12-crop recovery path that only failed cards pay for.
 *  * Every number goes through [java.lang.Double.doubleToRawLongBits] before hashing, because
 *    comparing with `==` or formatted text would hide a one-ULP difference, and a one-ULP
 *    difference is exactly what flips a score across `minimumConfidence` or a `maxByOrNull` tie.
 *
 * The baseline lives in `src/test/resources/character-icon-golden.txt`. When that file is absent
 * the test records the current numbers instead of comparing, which is how the baseline was
 * created: run it once on the untouched code, then keep the file in the repository so every later
 * run compares against it. To intentionally accept new numbers, delete the file and run again.
 *
 * Wall time for one `match()` is printed to stdout on every run but is deliberately *not* part of
 * the baseline: it is machine-dependent and would make the net fail for the wrong reason.
 */
class LabyrinthCharacterIconGoldenTest {

    @Test
    fun `icon matcher scores stay bit identical to the recorded baseline`() {
        val root = locateProjectRoot()
        val packRoot = File(root, ICON_PACK_ROOT)
        val frameFile = File(root, FRAME_PATH)
        assumeTrue("no battle-team harness frame available", frameFile.isFile)
        assumeTrue("icon pack missing at $packRoot", packRoot.isDirectory)

        val entries = decodeIconEntries(File(packRoot, "icons.json"))
        assumeTrue("icon pack index is empty", entries.size > MIN_TEMPLATES)

        // Order matters: the matcher sorts with a stable sort, so ties keep the template order the
        // loader produced. Same order as AndroidLabyrinthBattleTeamTemplateLoader.
        //
        // Six of the 801 indexed icons are .webp, which the JDK's ImageIO cannot decode (Android's
        // BitmapFactory can, which is why production loads all 801). They are skipped rather than
        // pulled in through a decoder dependency: the matcher treats every template identically, so
        // a net over 795 real icons plus a real team page still covers every branch that matters.
        var skippedIcons = 0
        val templates = entries.mapNotNull { entry ->
            val image = try {
                readImage(File(packRoot, entry.file))
            } catch (undecodable: IllegalArgumentException) {
                skippedIcons++
                return@mapNotNull null
            }
            LabyrinthBattleCharacterTemplate(
                characterId = entry.ownerId,
                displayName = entry.ownerId,
                iconVariant = entry.variant,
                image = image,
            )
        }
        val frame = readImage(frameFile)
        // A second, different team-page frame plus mask variants prove the matcher keys its shared
        // sampling on the current frame/rect/mask rather than serving a stale frame.
        val secondFrame = readImage(File(root, SECOND_FRAME_PATH))
        val matcher = LabyrinthCharacterIconMatcher(templates)
        // A labyrinth run's team pages only ever show the roles that joined that run, so a roster
        // subset is the production configuration, not an edge case.
        val roster = templates.asSequence().map { it.characterId }.take(ROSTER_SIZE).toSet()

        val passes = listOf(
            Pass(roster = null, attribute = null),
            Pass(roster = null, attribute = LabyrinthCharacterAttribute.FIRE),
            Pass(roster = roster, attribute = null),
        )
        val masks = listOf(
            LabyrinthCharacterIconMask(),
            LabyrinthCharacterIconMask(ignoreRightEdge = true),
            LabyrinthCharacterIconMask(ignoreTopLeft = true),
            LabyrinthCharacterIconMask(minYRatio = 0.2, maxYRatio = 0.9),
        )
        val frames = listOf(frame, secondFrame)

        var hash = FNV_OFFSET_BASIS
        var calls = 0L
        frames.forEach { currentFrame ->
            masks.forEach { mask ->
                RECTS.forEach { rect ->
                    passes.forEach { pass ->
                        val result = matcher.match(
                            frame = currentFrame,
                            iconRect = rect,
                            mask = mask,
                            requiredAttribute = pass.attribute,
                            rosterCharacterIds = pass.roster,
                        )
                        hash = mix(hash, fingerprint(result))
                        calls++
                    }
                }
            }
        }

        // The recovery path is only reached by failed cards, so force it: both branches of
        // reconcileGeometryMatches (kept / replaced) then get pinned for each frame and rect.
        frames.forEach { currentFrame ->
            RECTS.forEach { rect ->
                val initial = matcher.match(frame = currentFrame, iconRect = rect)
                val refined = matcher.refineMemberGeometry(
                    frame = currentFrame,
                    iconRect = rect,
                    initial = initial,
                    force = true,
                )
                hash = mix(hash, fingerprint(refined))
                calls++
            }
        }

        val record = "frame=${FRAME_PATH.substringAfterLast('/')}" +
            " secondFrame=${SECOND_FRAME_PATH.substringAfterLast('/')} templates=${templates.size}" +
            " skippedIcons=$skippedIcons rosterSize=${roster.size} rects=${RECTS.size}" +
            " passes=${passes.size} masks=${masks.size} calls=$calls" +
            " digest=${java.lang.Long.toHexString(hash)}"

        val golden = File(root, GOLDEN_PATH)
        if (!golden.isFile) {
            golden.parentFile?.mkdirs()
            golden.writeText("$record\n")
            println("Recorded a new $GOLDEN_PATH baseline from the current code.")
            reportTiming(matcher, frame, templates.size)
            return
        }
        assertEquals(
            "LabyrinthCharacterIconMatcher no longer produces identical bits. If the change is " +
                "intentional, delete $GOLDEN_PATH and re-run to record a new baseline.",
            golden.readText().trim(),
            record.trim(),
        )
        reportTiming(matcher, frame, templates.size)
    }

    /**
     * Wall time of one full `match()` against the real 801-icon pack. Printed, never asserted: it
     * is the number that says how much of `battleTeam` this class actually accounts for.
     */
    private fun reportTiming(matcher: LabyrinthCharacterIconMatcher, frame: PixelImage, templateCount: Int) {
        val rect = TIMING_RECT
        repeat(TIMING_WARMUP) { matcher.match(frame = frame, iconRect = rect) }
        val startedAt = System.nanoTime()
        repeat(TIMING_REPEATS) { matcher.match(frame = frame, iconRect = rect) }
        val perCallMs = (System.nanoTime() - startedAt) / 1_000_000.0 / TIMING_REPEATS
        println(
            "HARNESS_TIMING iconMatcherPerMatchMs=" + String.format(java.util.Locale.ROOT, "%.3f", perCallMs) +
                " templates=$templateCount",
        )
    }

    /** Everything a caller can observe about a match, in the matcher's own bit precision. */
    private fun fingerprint(match: LabyrinthCharacterIconMatch): Long {
        var hash = FNV_OFFSET_BASIS
        hash = mix(hash, match.characterId.hashCode().toLong())
        hash = mix(hash, rawBits(match.confidence))
        hash = mix(hash, rawBits(match.rivalConfidence))
        hash = mix(hash, rawBits(match.rivalMargin))
        hash = mix(hash, if (match.trusted) 1L else 0L)
        hash = mix(hash, match.suspectedCharacterId.hashCode().toLong())
        hash = mix(hash, match.candidates.size.toLong())
        match.candidates.forEach { candidate ->
            hash = mix(hash, candidate.characterId.hashCode().toLong())
            hash = mix(hash, candidate.iconVariant.hashCode().toLong())
            hash = mix(hash, rawBits(candidate.confidence))
        }
        return hash
    }

    private fun decodeIconEntries(file: File): List<IconEntry> =
        ICON_JSON.decodeFromString<IconDocument>(file.readText()).characterIcons

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(
            imageIo.getMethod("read", File::class.java).invoke(null, file),
        ) { "Cannot read $file" }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun locateProjectRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            if (File(current, "android/app/build.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }

    private data class Pass(
        val roster: Set<String>?,
        val attribute: LabyrinthCharacterAttribute?,
    )

    @Serializable
    private data class IconEntry(
        val ownerId: String,
        val variant: String = "",
        val file: String,
    )

    @Serializable
    private data class IconDocument(
        val characterIcons: List<IconEntry> = emptyList(),
    )

    private companion object {
        const val GOLDEN_PATH = "android/app/src/test/resources/character-icon-golden.txt"
        const val ICON_PACK_ROOT = "android/app/src/main/assets/resource-packs/cn-bilibili"
        const val FRAME_PATH = "android/app/src/test/resources/labyrinth/battle-team-roster-20260914.jpg"
        const val SECOND_FRAME_PATH = "android/app/src/test/resources/labyrinth/boss-team-editor-20260909.jpg"

        const val MIN_TEMPLATES = 100
        const val ROSTER_SIZE = 30
        /**
         * One warm-up plus three timed calls could not tell a real few-percent change from noise.
         * Five warm-ups (JIT steady state) and twenty timed calls cost about 150 ms and make the
         * reported per-call time reproducible to well under a percent.
         */
        const val TIMING_WARMUP = 5
        const val TIMING_REPEATS = 20

        val ICON_JSON = Json { ignoreUnknownKeys = true }

        /** Real card slots measured on the harness frame, at the sizes the recognizer reports. */
        val RECTS = listOf(
            EntryPixelRect(122, 243, 96, 96),
            EntryPixelRect(334, 665, 96, 96),
            EntryPixelRect(758, 454, 104, 104),
        )

        val TIMING_RECT = EntryPixelRect(122, 243, 96, 96)

        /** FNV-1a 64, mixed with the raw bit pattern of every observed number. */
        const val FNV_OFFSET_BASIS = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L

        fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME

        fun rawBits(value: Double): Long = java.lang.Double.doubleToRawLongBits(value)
    }
}
