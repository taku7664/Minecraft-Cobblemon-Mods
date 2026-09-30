package jbro.cobblemon.mcc.league.trainer

import jbro.cobblemon.mcc.league.trainer.WildTrainerMoveCategory.PHYSICAL
import jbro.cobblemon.mcc.league.trainer.WildTrainerMoveCategory.SPECIAL
import jbro.cobblemon.mcc.league.trainer.WildTrainerMoveCategory.STATUS
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WildTrainerBuildTest {
    private val charizard = WildTrainerMon(
        types = listOf("fire", "flying"),
        baseStats = mapOf(
            WildTrainerStat.HP to 78, WildTrainerStat.ATTACK to 84, WildTrainerStat.DEFENCE to 78,
            WildTrainerStat.SPECIAL_ATTACK to 109, WildTrainerStat.SPECIAL_DEFENCE to 85, WildTrainerStat.SPEED to 100,
        ),
        canEvolve = false,
    )
    private val known = listOf(
        WildTrainerMove("scratch", "normal", PHYSICAL, 40.0, 100.0),
        WildTrainerMove("growl", "normal", STATUS, 0.0, 100.0),
        WildTrainerMove("smokescreen", "normal", STATUS, 0.0, 100.0),
        WildTrainerMove("ember", "fire", SPECIAL, 40.0, 100.0),
        WildTrainerMove("flamethrower", "fire", SPECIAL, 90.0, 100.0),
        WildTrainerMove("airslash", "flying", SPECIAL, 75.0, 95.0),
        WildTrainerMove("dragonbreath", "dragon", SPECIAL, 60.0, 100.0),
        WildTrainerMove("scaryface", "normal", STATUS, 0.0, 100.0),
        WildTrainerMove("slash", "normal", PHYSICAL, 70.0, 100.0),
    )
    private val taught = listOf(
        WildTrainerMove("focusblast", "fighting", SPECIAL, 120.0, 70.0),
        WildTrainerMove("solarbeam", "grass", SPECIAL, 120.0, 100.0),
        WildTrainerMove("roost", "flying", STATUS, 0.0, -1.0),
        WildTrainerMove("hyperbeam", "normal", SPECIAL, 150.0, 90.0),
    )
    private val allCaps = listOf(10, 20, 30, 40, 50, 60, 70, 85, 100)

    @Test
    fun `a trainer knows at most four moves, never a banned one, and always an attack`() {
        val random = Random(1)
        WildTrainerTier.entries.forEach { tier ->
            allCaps.forEach { cap ->
                repeat(50) {
                    val quality = WildTrainerQuality.of(tier, cap)
                    val moves = WildTrainerBuild.moves(charizard, known + WildTrainerMove("doubleteam", "normal", STATUS, 0.0, -1.0), taught, quality, random)
                    assertTrue(moves.size in 1..4, moves.toString())
                    assertEquals(moves.size, moves.toSet().size, moves.toString())
                    assertTrue(moves.none { it in WildTrainerBuild.bannedMoves }, moves.toString())
                    assertTrue(moves.any { name -> (known + taught).first { it.name == name }.category != STATUS }, moves.toString())
                    val status = moves.count { name -> (known + taught).first { it.name == name }.category == STATUS }
                    assertTrue(status <= quality.maxStatusMoves, moves.toString())
                }
            }
        }
    }

    @Test
    fun `a thoughtful trainer leads with its strongest STAB and covers another type`() {
        val sharp = WildTrainerQuality.of(WildTrainerTier.ACE, 100).copy(smartMoves = 1.0, tmMoves = 0.0)
        val moves = WildTrainerBuild.moves(charizard, known, taught, sharp, Random(3))
        assertEquals("flamethrower", moves.first())
        assertTrue("airslash" in moves || "dragonbreath" in moves, moves.toString())
        assertTrue("growl" !in moves && "scratch" !in moves, moves.toString())
    }

    @Test
    fun `early trainers never learn TM moves and taught moves respect the power cap`() {
        val random = Random(5)
        repeat(200) {
            val early = WildTrainerBuild.moves(charizard, known, taught, WildTrainerQuality.of(WildTrainerTier.NORMAL, 15), random)
            assertTrue(early.none { name -> taught.any { it.name == name } }, early.toString())
            val mid = WildTrainerQuality.of(WildTrainerTier.NORMAL, 40)
            val moves = WildTrainerBuild.moves(charizard, known, taught, mid.copy(tmMoves = 1.0), random)
            assertTrue("focusblast" !in moves && "solarbeam" !in moves, moves.toString())
        }
    }

    @Test
    fun `better raised trainers stand higher in every phase and tier`() {
        WildTrainerTier.entries.forEach { tier ->
            val qualities = allCaps.map { WildTrainerQuality.of(tier, it) }
            qualities.zipWithNext().forEach { (before, after) ->
                assertTrue(after.ivs.first >= before.ivs.first && after.ivs.last >= before.ivs.last)
                assertTrue(after.evTotal >= before.evTotal && after.skill >= before.skill && after.itemChance >= before.itemChance)
            }
        }
        allCaps.forEach { cap ->
            val normal = WildTrainerQuality.of(WildTrainerTier.NORMAL, cap)
            val ace = WildTrainerQuality.of(WildTrainerTier.ACE, cap)
            assertTrue(ace.ivs.first > normal.ivs.first && ace.evTotal > normal.evTotal && ace.skill > normal.skill)
            assertTrue(ace.skill <= 4 && ace.ivs.last <= 31 && ace.evTotal <= 510)
        }
    }

    @Test
    fun `effort values stay legal and go to the stats the Pokemon fights with`() {
        WildTrainerTier.entries.forEach { tier ->
            allCaps.forEach { cap ->
                val quality = WildTrainerQuality.of(tier, cap)
                val evs = WildTrainerBuild.evs(charizard, quality)
                assertTrue(evs.values.sum() <= quality.evTotal && evs.values.all { it in 1..252 }, evs.toString())
                if (quality.evTotal > 0) assertEquals(evs.maxBy { it.value }.key, WildTrainerStat.SPECIAL_ATTACK)
            }
        }
        val ivs = WildTrainerBuild.ivs(WildTrainerQuality.of(WildTrainerTier.NORMAL, 15), Random(2))
        assertTrue(ivs.size == 6 && ivs.values.all { it in 0..15 })
    }

    @Test
    fun `a fitted nature suits the Pokemon and early normals keep theirs`() {
        val random = Random(4)
        repeat(100) {
            assertNull(WildTrainerBuild.nature(charizard, WildTrainerQuality.of(WildTrainerTier.NORMAL, 15), random))
            val nature = WildTrainerBuild.nature(charizard, WildTrainerQuality.of(WildTrainerTier.ACE, 100).copy(fittedNature = 1.0), random)
            assertTrue(nature in setOf("timid", "modest"), nature)
        }
    }

    @Test
    fun `no party holds a banned item or the same item twice`() {
        val random = Random(6)
        WildTrainerTier.entries.forEach { tier ->
            allCaps.forEach { cap ->
                repeat(40) {
                    val taken = HashSet<String>()
                    repeat(6) {
                        val item = WildTrainerBuild.item(charizard, "fire", tier, cap, taken, random)
                        if (item != null) {
                            assertTrue(item !in WildTrainerBuild.bannedItems, item)
                            assertTrue(taken.add(item), "$item twice at $tier $cap")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `bigger parties stand lower so a full party is no harder than a lone ace`() {
        WildTrainerTier.entries.forEach { tier ->
            listOf(15, 40, 70, 100).forEach { cap ->
                val gaps = (1..6).map { WildTrainerParty.averageGap(cap, tier, it) }
                assertTrue(gaps.zipWithNext().all { (a, b) -> b > a }, "$tier $cap $gaps")
            }
            val random = Random(8)
            repeat(200) {
                val size = WildTrainerParty.size(50, tier, random)
                assertTrue(size in 1..6)
                val levels = WildTrainerParty.levels(50, tier, size, random)
                assertEquals(size, levels.size)
                assertTrue(levels.all { it in 1..50 })
            }
        }
        assertTrue(WildTrainerParty.averageGap(100, WildTrainerTier.NORMAL, 6) > WildTrainerParty.averageGap(100, WildTrainerTier.ACE, 6))
        assertEquals(listOf(1, 1, 1), WildTrainerParty.levels(1, WildTrainerTier.NORMAL, 3, Random(0)))
    }
}
