package jbro.cobblemon.ui.extended

import jbro.cobblemon.ui.extended.transition.BattleEntryKind
import jbro.cobblemon.ui.extended.transition.BattleEntryTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleEntryTimelineTest {
    @Test
    fun `a legendary flashes three times before the cover starts`() {
        val kind = BattleEntryKind.LEGENDARY
        val peaks = (0 until BattleEntryTimeline.flashEnd(kind).toInt()).map { BattleEntryTimeline.flash(kind, it.toLong()) }
        val rises = peaks.zipWithNext().count { (a, b) -> a == 0f && b > 0f } + if (peaks.first() > 0f) 1 else 0
        assertEquals(3, rises)
        val first = kind.flashStarts.first()
        assertEquals(1f, BattleEntryTimeline.flash(kind, first + kind.flashMillis / 2), 1e-3f)
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
    fun `a legendary runs about six seconds and the others about four, holding the cover with a pulse`() {
        for (kind in BattleEntryKind.entries) {
            // A battle that opens the moment the screen is white.
            val total = BattleEntryTimeline.finishedAt(kind, BattleEntryTimeline.readyAt(kind))!!
            val expected = if (kind == BattleEntryKind.LEGENDARY) 5700L..6400L else 3500L..4400L
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
    fun `a legendary's light floods the screen before the battle starts, then holds, cracks, waits and shatters`() {
        val kind = BattleEntryKind.LEGENDARY
        val start = BattleEntryTimeline.riseStart(kind)
        val ready = BattleEntryTimeline.readyAt(kind)
        assertEquals(0f, BattleEntryTimeline.beam(kind, start))
        assertTrue(BattleEntryTimeline.beam(kind, (start + ready) / 2) < .2f)
        assertEquals(1f, BattleEntryTimeline.beam(kind, ready), 1e-3f)
        // Until the battle opens the white just holds.
        assertEquals(0f, BattleEntryTimeline.crack(kind, ready + 5000, null))
        assertEquals(0f, BattleEntryTimeline.shatter(kind, ready + 5000, null))
        // A battle that opens at once: a second of white, cracks, half a second still, then the shatter.
        val hold = BattleEntryTimeline.WHITE_HOLD_MILLIS
        val crackEnd = ready + hold + BattleEntryTimeline.CRACK_MILLIS
        assertEquals(0f, BattleEntryTimeline.crack(kind, ready + hold, ready))
        assertEquals(1f, BattleEntryTimeline.crack(kind, crackEnd, ready), 1e-3f)
        assertEquals(0f, BattleEntryTimeline.shatter(kind, crackEnd + BattleEntryTimeline.STILL_MILLIS, ready))
        assertEquals(1f, BattleEntryTimeline.shatter(kind, crackEnd + BattleEntryTimeline.STILL_MILLIS + kind.fadeMillis,
            ready), 1e-3f)
        // A battle that opens late still gets its white to settle before the cracks.
        val late = ready + 2000
        assertEquals(late + BattleEntryTimeline.SETTLE_MILLIS, BattleEntryTimeline.crackStart(kind, late))
    }

    @Test
    fun `a legendary's slash crosses before its plates close`() {
        assertEquals(0f, BattleEntryTimeline.slash(0f))
        assertEquals(1f, BattleEntryTimeline.slash(.25f), 1e-3f)
        assertEquals(0f, BattleEntryTimeline.plates(.2f))
        assertTrue(BattleEntryTimeline.plates(.25f) < .3f)
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
    fun `only a legendary darkens, rings, shakes and brings bars`() {
        for (kind in listOf(BattleEntryKind.WILD, BattleEntryKind.TRAINER)) {
            for (elapsed in 0L..BattleEntryTimeline.coverEnd(kind) step 20) {
                assertEquals(0f, BattleEntryTimeline.dim(kind, elapsed))
                assertEquals(0f, BattleEntryTimeline.shake(kind, elapsed))
                assertEquals(0f, BattleEntryTimeline.bars(kind, elapsed))
                assertTrue(BattleEntryTimeline.rings(kind, elapsed).isEmpty())
            }
        }
        val legendary = BattleEntryKind.LEGENDARY
        val last = legendary.flashStarts.last()
        assertEquals(1f, BattleEntryTimeline.bars(legendary, last + 200), 1e-3f)
        assertEquals(0f, BattleEntryTimeline.bars(legendary, BattleEntryTimeline.readyAt(legendary)))
        assertTrue(BattleEntryTimeline.rings(legendary, last + 10).isNotEmpty())
        assertTrue(BattleEntryTimeline.shake(legendary, last + 10) > .9f)
        assertEquals(0f, BattleEntryTimeline.shake(legendary, BattleEntryTimeline.coverEnd(legendary)), 1e-3f)
    }

    @Test
    fun `the reveal clears the center first`() {
        val center = BattleEntryTimeline.pieceSize(1f, .3f, 0f)
        val corner = BattleEntryTimeline.pieceSize(1f, .3f, 1f)
        assertTrue(center < corner, "center $center, corner $corner")
    }
}
