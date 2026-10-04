package jbro.cobblemon.ui.extended

import jbro.cobblemon.ui.extended.transition.BattleEntryKind
import jbro.cobblemon.ui.extended.transition.BattleEntryTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleEntryTimelineTest {
    @Test
    fun `a legendary zooms three times instead of flashing before the cover starts`() {
        val kind = BattleEntryKind.LEGENDARY
        assertEquals(3, kind.flashStarts.size)
        for (start in kind.flashStarts) assertTrue(BattleEntryTimeline.zooms(kind, start).contains(0f))
        assertTrue(BattleEntryTimeline.zooms(kind, kind.flashStarts.first() - 1).isEmpty())
        for (elapsed in 0L..BattleEntryTimeline.flashEnd(kind)) assertEquals(0f, BattleEntryTimeline.flash(kind, elapsed))
        val first = kind.flashStarts.first()
        // The world darkens before the first flash.
        assertTrue(BattleEntryTimeline.dim(kind, first) >= 1f - 1e-3f)
        assertEquals(0f, BattleEntryTimeline.cover(kind, BattleEntryTimeline.flashEnd(kind)))
        assertEquals(0f, BattleEntryTimeline.flash(kind, BattleEntryTimeline.flashEnd(kind)))
    }

    @Test
    fun `the cover finishes after the flashes and the cover time`() {
        for (kind in BattleEntryKind.entries) {
            val end = BattleEntryTimeline.coverEnd(kind)
            assertFalse(BattleEntryTimeline.covered(kind, end - 1))
            assertTrue(BattleEntryTimeline.covered(kind, end))
        }
    }

    @Test
    fun `the center leads and the corners follow`() {
        assertTrue(BattleEntryTimeline.piece(.3f, 0f) > BattleEntryTimeline.piece(.3f, 1f))
        assertEquals(0f, BattleEntryTimeline.piece(0f, 0f))
        // Every piece is in place once the cover completes, and every piece is gone once the reveal does.
        for (distance in listOf(0f, .5f, 1f)) {
            assertEquals(1f, BattleEntryTimeline.pieceSize(1f, 0f, distance))
            assertEquals(0f, BattleEntryTimeline.pieceSize(1f, 1f, distance))
        }
    }

    @Test
    fun `a legendary runs about seven and a half seconds and the others about four, holding the cover with a pulse`() {
        for (kind in BattleEntryKind.entries) {
            // A battle that opens the moment the screen is white.
            val total = BattleEntryTimeline.finishedAt(kind, BattleEntryTimeline.readyAt(kind))!!
            val expected = if (kind == BattleEntryKind.LEGENDARY) 7200L..8000L else 3500L..4400L
            assertTrue(total in expected, "${kind.id} runs $total ms")
            assertFalse(BattleEntryTimeline.ready(kind, BattleEntryTimeline.readyAt(kind) - 1))
            assertTrue(BattleEntryTimeline.ready(kind, BattleEntryTimeline.readyAt(kind)))
            assertEquals(0f, BattleEntryTimeline.pulse(kind, BattleEntryTimeline.coverEnd(kind) - 1))
            val hold = (BattleEntryTimeline.coverEnd(kind)..BattleEntryTimeline.readyAt(kind) step 10)
                .map { BattleEntryTimeline.pulse(kind, it) }
            assertTrue(hold.max() > .6f, "${kind.id} pulses up to ${hold.max()}")
        }
    }

    @Test
    fun `a legendary's focus lines flood the screen before the battle starts, then it holds, cracks, waits and shatters`() {
        val kind = BattleEntryKind.LEGENDARY
        val start = BattleEntryTimeline.riseStart(kind)
        val ready = BattleEntryTimeline.readyAt(kind)
        assertEquals(0f, BattleEntryTimeline.flood(kind, start))
        assertTrue(BattleEntryTimeline.flood(kind, (start + ready) / 2) < .3f)
        assertEquals(1f, BattleEntryTimeline.flood(kind, ready), 1e-3f)
        // Until the battle opens the white just holds.
        assertEquals(0f, BattleEntryTimeline.crack(kind, ready + 5000, null))
        assertEquals(0f, BattleEntryTimeline.shatter(kind, ready + 5000, null))
        // A battle that opens at once: a second of white, cracks, half a second still, then the shatter.
        val hold = BattleEntryTimeline.WHITE_HOLD_MILLIS
        val crackEnd = ready + hold + BattleEntryTimeline.CRACK_MILLIS
        assertEquals(0f, BattleEntryTimeline.crack(kind, ready + hold, ready))
        assertEquals(1f, BattleEntryTimeline.crack(kind, crackEnd, ready), 1e-3f)
        // Then, after a pause, the fine cracks; then the stillness and the shatter.
        val fineStart = crackEnd + BattleEntryTimeline.CRACK_GAP_MILLIS
        val fineEnd = fineStart + BattleEntryTimeline.FINE_CRACK_MILLIS
        assertEquals(0f, BattleEntryTimeline.fineCrack(kind, fineStart, ready))
        assertEquals(1f, BattleEntryTimeline.fineCrack(kind, fineEnd, ready), 1e-3f)
        assertEquals(0f, BattleEntryTimeline.shatter(kind, fineEnd + BattleEntryTimeline.STILL_MILLIS, ready))
        assertEquals(1f, BattleEntryTimeline.shatter(kind, fineEnd + BattleEntryTimeline.STILL_MILLIS + kind.fadeMillis,
            ready), 1e-3f)
        // A battle that opens late still gets its white to settle before the cracks.
        val late = ready + 2000
        assertEquals(late + BattleEntryTimeline.SETTLE_MILLIS, BattleEntryTimeline.crackStart(kind, late))
    }

    @Test
    fun `a legendary's plates close fast and settle as they meet`() {
        assertEquals(0f, BattleEntryTimeline.plates(0f))
        assertTrue(BattleEntryTimeline.plates(.5f) > .8f)
        assertEquals(1f, BattleEntryTimeline.plates(1f), 1e-3f)
    }

    @Test
    fun `the covered screen turns white before the battle starts, and only then drops the cover and fades`() {
        for (kind in listOf(BattleEntryKind.WILD, BattleEntryKind.TRAINER)) {
            val start = BattleEntryTimeline.riseStart(kind)
            val ready = BattleEntryTimeline.readyAt(kind)
            assertEquals(0f, BattleEntryTimeline.white(kind, start, null))
            assertEquals(1f, BattleEntryTimeline.white(kind, ready, null), 1e-2f)
            // The cover stays whole under the rising white and goes once the white is full.
            assertFalse(BattleEntryTimeline.patternGone(kind, ready - 1))
            assertTrue(BattleEntryTimeline.patternGone(kind, ready))
            // The white waits for the battle, then settles a moment and fades.
            assertEquals(1f, BattleEntryTimeline.white(kind, ready + 3000, null))
            val opened = ready + 800
            val fade = opened + BattleEntryTimeline.SETTLE_MILLIS
            assertEquals(1f, BattleEntryTimeline.white(kind, fade, opened))
            assertTrue(BattleEntryTimeline.white(kind, fade + kind.fadeMillis / 2, opened) in .2f..0.8f)
            assertFalse(BattleEntryTimeline.revealed(kind, fade + kind.fadeMillis - 1, opened))
            assertTrue(BattleEntryTimeline.revealed(kind, fade + kind.fadeMillis, opened))
        }
    }

    @Test
    fun `only a legendary darkens, zooms, shakes and brings bars`() {
        for (kind in listOf(BattleEntryKind.WILD, BattleEntryKind.TRAINER)) {
            for (elapsed in 0L..BattleEntryTimeline.coverEnd(kind) step 20) {
                assertEquals(0f, BattleEntryTimeline.dim(kind, elapsed))
                assertEquals(0f, BattleEntryTimeline.shake(kind, elapsed))
                assertEquals(0f, BattleEntryTimeline.bars(kind, elapsed))
                assertTrue(BattleEntryTimeline.zooms(kind, elapsed).isEmpty())
            }
        }
        val legendary = BattleEntryKind.LEGENDARY
        val last = legendary.flashStarts.last()
        assertEquals(1f, BattleEntryTimeline.bars(legendary, last + 200), 1e-3f)
        assertEquals(0f, BattleEntryTimeline.bars(legendary, BattleEntryTimeline.readyAt(legendary)))
        assertTrue(BattleEntryTimeline.zooms(legendary, last + 10).isNotEmpty())
        assertTrue(BattleEntryTimeline.shake(legendary, last + 10) > .9f)
        assertEquals(0f, BattleEntryTimeline.shake(legendary, BattleEntryTimeline.coverEnd(legendary)), 1e-3f)
    }

    @Test
    fun `the reveal clears the center first`() {
        val center = BattleEntryTimeline.pieceSize(1f, .3f, 0f)
        val corner = BattleEntryTimeline.pieceSize(1f, .3f, 1f)
        assertTrue(center < corner, "center $center, corner $corner")
    }

    @Test
    fun `any whiteout hands a fully white screen to any fade-in`() {
        for (kind in BattleEntryKind.entries) {
            val ready = BattleEntryTimeline.readyAt(kind)
            // The whiteout ends full, and the fade-in starts full until the battle opens.
            assertEquals(1f, BattleEntryTimeline.rise(kind, ready))
            assertEquals(1f, BattleEntryTimeline.flood(kind, ready), 1e-3f)
            assertEquals(1f, BattleEntryTimeline.white(kind, ready, null))
            assertEquals(0f, BattleEntryTimeline.shatter(kind, ready, null))
            assertTrue(BattleEntryTimeline.patternGone(kind, ready))
        }
    }

    @Test
    fun `variants only swap stages whose timing the kind already keeps`() {
        for (kind in BattleEntryKind.entries) {
            assertEquals(kind.stages, kind.variants.first())
            for (variant in kind.variants) {
                // Moods, zooms and shatters read the kind's own stages for their timing, so variants keep them.
                assertEquals(kind.stages.mood, variant.mood)
                assertEquals(kind.stages.fadeIn == jbro.cobblemon.ui.extended.transition.EntryFadeIn.SHATTER,
                    variant.fadeIn == jbro.cobblemon.ui.extended.transition.EntryFadeIn.SHATTER)
                assertEquals(kind.stages.intro == jbro.cobblemon.ui.extended.transition.EntryIntro.SCREEN_ZOOM,
                    variant.intro == jbro.cobblemon.ui.extended.transition.EntryIntro.SCREEN_ZOOM)
            }
        }
    }

    @Test
    fun `an opening waits for the battle, then eases from shut to open`() {
        val kind = BattleEntryKind.WILD
        val ready = BattleEntryTimeline.readyAt(kind)
        assertEquals(0f, BattleEntryTimeline.opening(kind, ready + 5000, null))
        val start = ready + BattleEntryTimeline.SETTLE_MILLIS
        assertEquals(0f, BattleEntryTimeline.opening(kind, start, ready))
        assertEquals(1f, BattleEntryTimeline.opening(kind, start + kind.fadeMillis, ready))
        // Every opening fade-in finishes when the white fade would.
        for (fadeIn in listOf(jbro.cobblemon.ui.extended.transition.EntryFadeIn.IRIS_OPEN,
                jbro.cobblemon.ui.extended.transition.EntryFadeIn.SPLIT_OPEN)) {
            assertEquals(BattleEntryTimeline.finishedAt(kind, ready), BattleEntryTimeline.finishedAt(kind, ready, fadeIn))
        }
        assertEquals(0f, BattleEntryTimeline.mosaic(kind, 0))
        // The mosaic keeps coarsening under the cover until the white starts to rise.
        assertTrue(BattleEntryTimeline.mosaic(kind, BattleEntryTimeline.coverEnd(kind)) < 1f)
        assertEquals(1f, BattleEntryTimeline.mosaic(kind, BattleEntryTimeline.riseStart(kind)))
        assertEquals(0f, BattleEntryTimeline.spin(kind, 0))
        assertEquals(null, BattleEntryTimeline.spin(kind, BattleEntryTimeline.coverEnd(kind) + 1))
    }
}
