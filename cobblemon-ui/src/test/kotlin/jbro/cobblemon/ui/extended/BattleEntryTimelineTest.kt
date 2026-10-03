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
    fun `each transition runs about five seconds, holding the covered screen with a pulse`() {
        for (kind in BattleEntryKind.entries) {
            val total = BattleEntryTimeline.readyAt(kind) + (kind.revealMillis * BattleEntryTimeline.WHITE_RISE).toLong() +
                kind.fadeMillis
            assertTrue(total in 4500L..5500L, "${kind.id} runs $total ms")
            assertFalse(BattleEntryTimeline.ready(kind, BattleEntryTimeline.readyAt(kind) - 1))
            assertTrue(BattleEntryTimeline.ready(kind, BattleEntryTimeline.readyAt(kind)))
            assertEquals(0f, BattleEntryTimeline.pulse(kind, BattleEntryTimeline.coverEnd(kind) - 1))
            val hold = (BattleEntryTimeline.coverEnd(kind)..BattleEntryTimeline.readyAt(kind) step 10)
                .map { BattleEntryTimeline.pulse(kind, it) }
            assertTrue(hold.max() > .6f, "${kind.id} pulses up to ${hold.max()}")
        }
    }

    @Test
    fun `the reveal turns the screen white then fades it slowly`() {
        for (kind in BattleEntryKind.entries) {
            val rise = (kind.revealMillis * BattleEntryTimeline.WHITE_RISE).toLong()
            assertEquals(0f, BattleEntryTimeline.white(kind, 0))
            assertEquals(1f, BattleEntryTimeline.white(kind, rise), 1e-2f)
            // The pattern is gone by the time the white is full, and the white fades over its own, longer time.
            assertEquals(1f, BattleEntryTimeline.reveal(kind, rise))
            assertTrue(BattleEntryTimeline.white(kind, rise + kind.fadeMillis / 2) in .2f..0.8f)
            assertFalse(BattleEntryTimeline.revealed(kind, rise + kind.fadeMillis - 1))
            assertTrue(BattleEntryTimeline.revealed(kind, rise + kind.fadeMillis))
        }
    }

    @Test
    fun `only a legendary darkens, rings, shakes and brings bars`() {
        for (kind in listOf(BattleEntryKind.WILD, BattleEntryKind.TRAINER)) {
            for (elapsed in 0L..BattleEntryTimeline.coverEnd(kind) step 20) {
                assertEquals(0f, BattleEntryTimeline.dim(kind, elapsed))
                assertEquals(0f, BattleEntryTimeline.shake(kind, elapsed))
                assertEquals(0f, BattleEntryTimeline.bars(kind, elapsed, null))
                assertTrue(BattleEntryTimeline.rings(kind, elapsed).isEmpty())
            }
        }
        val legendary = BattleEntryKind.LEGENDARY
        val last = legendary.flashStarts.last()
        assertEquals(1f, BattleEntryTimeline.bars(legendary, last + 200, null), 1e-3f)
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
