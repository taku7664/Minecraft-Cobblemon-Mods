package jbro.cobblemon.ui.extended

import jbro.cobblemon.ui.extended.transition.BattleEntryKind
import jbro.cobblemon.ui.extended.transition.BattleEntryTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleEntryTimelineTest {
    @Test
    fun `a legendary flashes twice before the cover starts`() {
        val kind = BattleEntryKind.LEGENDARY
        val peaks = (0 until BattleEntryTimeline.flashEnd(kind).toInt()).map { BattleEntryTimeline.flash(kind, it.toLong()) }
        val rises = peaks.zipWithNext().count { (a, b) -> a == 0f && b > 0f } + if (peaks.first() > 0f) 1 else 0
        assertEquals(2, rises)
        assertEquals(1f, BattleEntryTimeline.flash(kind, BattleEntryTimeline.FLASH_MILLIS / 2), 1e-3f)
        assertEquals(0f, BattleEntryTimeline.cover(kind, BattleEntryTimeline.flashEnd(kind)))
        assertEquals(0f, BattleEntryTimeline.flash(kind, BattleEntryTimeline.flashEnd(kind)))
    }

    @Test
    fun `the cover finishes after the flashes and the cover time`() {
        for (kind in BattleEntryKind.entries) {
            val end = BattleEntryTimeline.flashEnd(kind) + kind.coverMillis
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
    fun `the reveal clears the center first`() {
        val center = BattleEntryTimeline.pieceSize(1f, .3f, 0f)
        val corner = BattleEntryTimeline.pieceSize(1f, .3f, 1f)
        assertTrue(center < corner, "center $center, corner $corner")
    }
}
