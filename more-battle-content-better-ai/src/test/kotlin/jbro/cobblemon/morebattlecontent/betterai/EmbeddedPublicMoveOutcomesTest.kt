package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class EmbeddedPublicMoveOutcomesTest {
    private val source = UUID(0, 1)
    private val target = UUID(0, 2)
    private fun parse(line: String) = EmbeddedPublicMoveOutcomes.read(line, 3, 7) {
        when (it) { "p1a: source" -> source; "p2a: target" -> target; else -> null }
    }

    @Test
    fun `target-only outcomes never acquire the previous attacker move or secret cause`() {
        mapOf("-crit" to BattleMoveOutcomeKind.CRITICAL_HIT, "-supereffective" to BattleMoveOutcomeKind.SUPER_EFFECTIVE,
            "-resisted" to BattleMoveOutcomeKind.RESISTED, "-immune" to BattleMoveOutcomeKind.IMMUNE).forEach { (message, kind) ->
            val event = parse("|$message|p2a: target|[from] ability: Levitate")!!
            assertEquals(kind, event.moveOutcome!!.kind)
            assertEquals(listOf(target), event.targetPokemonIds)
            assertNull(event.actorPokemonId)
            assertNull(event.moveOutcome!!.moveId)
            assertNull(event.moveOutcome!!.publicEffectId)
            assertNull(event.hpFractionDelta)
        }
        assertEquals(5, parse("|-hitcount|p2a: target|5")!!.moveOutcome!!.hitCount)
        for (bad in listOf("0", "-1", "invalid")) assertNull(parse("|-hitcount|p2a: target|$bad"))
    }

    @Test
    fun `miss fail block and cant preserve only their explicit argument roles`() {
        val miss = parse("|move|p1a: source|Thunderbolt|p2a: target|[miss]")!!
        assertEquals(source, miss.actorPokemonId)
        assertEquals(listOf(target), miss.targetPokemonIds)
        assertEquals("thunderbolt", miss.moveOutcome!!.moveId)
        val fail = parse("|-fail|p2a: target|move: Toxic")!!
        assertNull(fail.actorPokemonId)
        assertEquals("toxic", fail.moveOutcome!!.moveId)
        val block = parse("|-block|p2a: target|move: Protect|Thunderbolt|p1a: source")!!
        assertEquals(source, block.actorPokemonId)
        assertEquals("protect", block.moveOutcome!!.publicEffectId)
        assertEquals("thunderbolt", block.moveOutcome!!.moveId)
        val cant = parse("|cant|p1a: source|recharge")!!
        assertEquals("recharge", cant.moveOutcome!!.publicEffectId)
        assertNull(cant.moveOutcome!!.moveId)
        assertNull(parse("|-block|p2a: target|move: Protect|[from] anything")!!.moveOutcome!!.moveId)
        assertTrue(parse("|-miss|unknown|unknown")!!.targetPokemonIds.isEmpty())
    }

    @Test
    fun `miss dedup retains explicit move and respects turn target and intervening events`() {
        val first = parse("|move|p1a: source|Thunderbolt|p2a: target|[miss]")!!
        val second = parse("|-miss|p1a: source|p2a: target")!!
        assertTrue(EmbeddedPublicMoveOutcomes.duplicatesMiss(first, second))
        assertFalse(EmbeddedPublicMoveOutcomes.duplicatesMiss(second, first))
        assertFalse(EmbeddedPublicMoveOutcomes.duplicatesMiss(first, parse("|-miss|p1a: source|unknown")!!))
        assertFalse(EmbeddedPublicMoveOutcomes.duplicatesMiss(first,
            EmbeddedPublicMoveOutcomes.read("|-miss|p1a: source|p2a: target", 4, 8) {
                if (it == "p1a: source") source else target
            }!!))
        assertFalse(EmbeddedPublicMoveOutcomes.duplicatesMiss(
            BattleObservedEventView(8, 3, BattleObservedEventKind.MOVE_USED, source, publicValueId = "tackle"), second))
    }

    @Test
    fun `substitute damage and protect start are not Pokemon hp changes or actual blocks`() {
        val substitute = parse("|-activate|p2a: target|move: Substitute|[damage]")!!
        assertEquals(BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED, substitute.moveOutcome!!.kind)
        assertNull(substitute.actorPokemonId)
        assertNull(substitute.hpFractionDelta)
        val protect = parse("|-singleturn|p2a: target|move: Protect")!!
        assertEquals(BattleMoveOutcomeKind.PROTECTION_STARTED, protect.moveOutcome!!.kind)
        assertNull(protect.moveOutcome!!.moveId)
        assertNull(parse("|-activate|p2a: target|ability: Mummy"))
        assertNull(parse("|-singleturn|p2a: target|move: Roost"))
    }
}
