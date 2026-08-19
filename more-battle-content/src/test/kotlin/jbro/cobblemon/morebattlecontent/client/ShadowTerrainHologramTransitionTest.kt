package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.presentation.BattleArenaHologramProjection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowTerrainHologramTransitionTest {
    @Test
    fun `effect fades in and out while stale hide cannot clear a newer battle`() {
        val transition = ShadowTerrainHologramTransition(fadeDurationNanos = 600L)
        val first = projection(UUID.randomUUID(), centerX = 4.0)
        val second = projection(UUID.randomUUID(), centerX = 14.0)

        transition.show(first, nowNanos = 100L)
        assertEquals(0F, transition.snapshot(100L)!!.strength)
        assertEquals(0F, transition.snapshot(100L)!!.effectAgeSeconds)
        assertEquals(0.5F, transition.snapshot(400L)!!.strength)
        assertEquals(1F, transition.snapshot(700L)!!.strength)

        transition.show(second, nowNanos = 800L)
        transition.hide(first.battleId, nowNanos = 900L)
        assertSame(second, transition.snapshot(900L)!!.projection)

        transition.hide(second.battleId, nowNanos = 1_400L)
        assertTrue(transition.snapshot(1_700L)!!.strength in 0.49F..0.51F)
        assertNull(transition.snapshot(2_000L))
    }

    @Test
    fun `clear drops transition immediately`() {
        val transition = ShadowTerrainHologramTransition(fadeDurationNanos = 600L)
        transition.show(
            projection(UUID.randomUUID()),
            nowNanos = 0L,
        )

        transition.clear()

        assertFalse(transition.isRetained())
        assertNull(transition.snapshot(300L))
    }

    @Test
    fun `same battle updates projection without moving its captured arena center`() {
        val transition = ShadowTerrainHologramTransition(fadeDurationNanos = 600L)
        val battleId = UUID.randomUUID()
        val initial = projection(battleId, centerX = 4.0)
        val initialCenter = ShadowTerrainArenaCenter(4.0, 64.0, 0.0)

        transition.show(initial, nowNanos = 0L)
        transition.show(
            initial.copy(centerX = 15.0, opponentDirectionX = -1.0),
            nowNanos = 700L,
        )

        val snapshot = transition.snapshot(700L)!!
        assertEquals(initialCenter, snapshot.arenaCenter)
        assertEquals(ShadowTerrainArenaDirection(1.0, 0.0), snapshot.arenaDirection)
        assertEquals(0.0000007F, snapshot.effectAgeSeconds, 0.00000001F)
    }

    @Test
    fun `projection direction is copied into the arena snapshot`() {
        val transition = ShadowTerrainHologramTransition(fadeDurationNanos = 600L)
        val projection = projection(UUID.randomUUID(), directionX = 0.0, directionZ = 1.0)
        transition.show(
            projection,
            nowNanos = 0L,
        )

        assertEquals(ShadowTerrainArenaDirection(0.0, 1.0), transition.snapshot(0L)!!.arenaDirection)
    }

    @Test
    fun `same battle updates do not restart the arena startup clock`() {
        val transition = ShadowTerrainHologramTransition(fadeDurationNanos = 600_000_000L)
        val battleId = UUID.randomUUID()
        val initial = projection(battleId, centerX = 4.0)

        transition.show(initial, nowNanos = 1_000_000_000L)
        transition.show(initial.copy(centerX = 9.0), nowNanos = 2_000_000_000L)

        assertEquals(1.5F, transition.snapshot(2_500_000_000L)!!.effectAgeSeconds, 0.0001F)
    }

    private fun projection(
        battleId: UUID,
        centerX: Double = 4.0,
        directionX: Double = 1.0,
        directionZ: Double = 0.0,
    ) = BattleArenaHologramProjection(
        battleId = battleId,
        centerX = centerX,
        centerY = 64.0,
        centerZ = 0.0,
        opponentDirectionX = directionX,
        opponentDirectionZ = directionZ,
    )
}
