package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PvpArenaPoolTest {
    @Test
    fun `pool reuses idle arenas and grows by exactly one at a new concurrency high water mark`() {
        val pool = PvpArenaPool(initialArenaCount = 0, spacingBlocks = 2_048)
        val firstMatch = UUID(0, 1)
        val secondMatch = UUID(0, 2)
        val thirdMatch = UUID(0, 3)

        val first = pool.acquire(firstMatch)
        val second = pool.acquire(secondMatch)
        assertEquals(2, pool.arenaCount)
        assertEquals(0, first.index)
        assertEquals(1, second.index)
        assertEquals(2_048, second.centerX - first.centerX)

        pool.release(firstMatch)
        val reused = pool.acquire(thirdMatch)
        assertEquals(first.index, reused.index)
        assertEquals(2, pool.arenaCount)
        assertNull(pool.leaseFor(firstMatch))
        assertEquals(reused, pool.leaseFor(thirdMatch))
    }

    @Test
    fun `restored high water mark does not preallocate an occupied lease`() {
        val pool = PvpArenaPool(initialArenaCount = 3)

        assertEquals(3, pool.arenaCount)
        assertEquals(0, pool.acquire(UUID(0, 10)).index)
    }
}
