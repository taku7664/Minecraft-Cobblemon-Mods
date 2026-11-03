package jbro.cobblemon.policy.legend

import java.util.UUID
import jbro.cobblemon.policy.legend.LegendSpawnLog.Entry
import jbro.cobblemon.policy.legend.LegendSpawnLog.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegendRestoreTest {
    private val owner = UUID.randomUUID()
    private val names = mapOf("reshiram" to "레시라무", "tapukoko" to "카푸꼬꼬꼭", "typenull" to "타입:널", "zeraora" to "제라오라")
    private val now = 100L * 24 * 60 * 60 * 1000

    private fun entry(legend: String, state: State, ageHours: Long = 1, restores: Int = 0) =
        Entry(UUID.randomUUID(), owner, legend, false, now - ageHours * 60 * 60 * 1000, state, restores, x = 10, z = 20)

    private fun decide(text: String, vararg entries: Entry) = LegendRestore.decide(text, entries.toList(), names, now)

    /** The inquiries that reached operators on the live server. */
    @Test fun `a real report restores the despawned Legend`() {
        val despawned = entry("reshiram", State.DESPAWNED)
        assertEquals(LegendRestore.Decision.Restore(despawned), decide("전설 30초만에 사라지는거 너무빨라요 ㅠㅠㅠ 화장실갔다오는사이에 사라짐", despawned))
        assertEquals(LegendRestore.Decision.Restore(despawned), decide("레시라무 디스폰됐어요", despawned))
        val typeNull = entry("typenull", State.DESPAWNED)
        assertEquals(LegendRestore.Decision.Restore(typeNull), decide("타입널 어디갔어", typeNull))
    }

    @Test fun `words alone never spawn anything`() {
        assertEquals(LegendRestore.Decision.None, decide("전설 사라졌어요"))
        assertEquals(LegendRestore.Decision.None, decide("전설 사라졌어요", entry("reshiram", State.ENDED)))
        assertEquals(LegendRestore.Decision.None, decide("전설 사라졌어요", entry("reshiram", State.RESTORED)))
    }

    @Test fun `only an inquiry about a vanished Legend counts`() {
        val despawned = entry("reshiram", State.DESPAWNED)
        assertEquals(LegendRestore.Decision.None, decide("제 킹크랩이 사라졌어요", despawned))
        assertEquals(LegendRestore.Decision.None, decide("전설 어떻게 잡아요", despawned))
    }

    @Test fun `naming another Legend does not restore this one`() {
        assertEquals(LegendRestore.Decision.None, decide("타입:널 사라졌어요", entry("reshiram", State.DESPAWNED)))
        val koko = entry("tapukoko", State.DESPAWNED, ageHours = 2)
        val reshiram = entry("reshiram", State.DESPAWNED, ageHours = 1)
        assertEquals(LegendRestore.Decision.Restore(koko), decide("카푸꼬꼬꼭 사라졌어요", reshiram, koko))
    }

    @Test fun `the newest despawn comes first and old or repeated ones stop`() {
        val older = entry("tapukoko", State.DESPAWNED, ageHours = 3)
        val newer = entry("reshiram", State.DESPAWNED, ageHours = 1)
        assertEquals(LegendRestore.Decision.Restore(newer), decide("전설 사라졌어요", older, newer))
        assertEquals(LegendRestore.Decision.None, decide("전설 사라졌어요", entry("reshiram", State.DESPAWNED, ageHours = 25)))
        assertEquals(LegendRestore.Decision.None, decide("전설 사라졌어요", entry("reshiram", State.DESPAWNED, restores = 2)))
        assertTrue(decide("전설 사라졌어요", entry("reshiram", State.DESPAWNED, restores = 1)) is LegendRestore.Decision.Restore)
    }

    /** The inquiry that got a catch-record lookup for its approval button on the live server. */
    @Test fun `a named Legend without a record is put to an operator`() {
        assertEquals("zeraora", LegendRestore.proposable("어제 야생에서 나타난 제라오라를 못잡았는데 사라졌다 당장 리스폰시키도록", emptyList(), names, now))
    }

    @Test fun `no spawn is proposed without one vanished Legend named`() {
        assertEquals(null, LegendRestore.proposable("전설이 사라졌어요", emptyList(), names, now))
        assertEquals(null, LegendRestore.proposable("제라오라 어떻게 잡아요", emptyList(), names, now))
        assertEquals(null, LegendRestore.proposable("제라오라랑 레시라무 사라졌어요", emptyList(), names, now))
    }

    @Test fun `a caught or fainted Legend is not proposed again`() {
        assertEquals(null, LegendRestore.proposable("제라오라 사라졌어요", listOf(entry("zeraora", State.ENDED)), names, now))
        assertEquals("zeraora", LegendRestore.proposable("제라오라 사라졌어요", listOf(entry("reshiram", State.ENDED)), names, now))
    }

    @Test fun `a Legend still in the world is pointed at instead of spawned twice`() {
        val alive = entry("reshiram", State.ALIVE)
        assertEquals(LegendRestore.Decision.StillThere(alive), decide("전설 사라졌어요", alive))
    }
}
