package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDirectHitMechanics
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalAppliedDirectHit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A pinch berry changes what the next attack has to get through.
 *
 * It never saves anything from a knockout - it only fires on a survivor - so it does not belong with
 * the knockout assessment. What it does is move the health a second hit must cover, which is exactly
 * the arithmetic every patient line in this AI is built on. 394 of the battle tower's sets carry a
 * Sitrus Berry and none of them were modelled.
 */
class LocalPinchBerryTest {
    @Test
    fun `berry healing does not subtract from damage attributed to the hit`() {
        val applied = hitResult(item = "cobblemon:sitrus_berry", startHp = 0.8, damage = 0.5)
        assertEquals(0.5, applied.directDamageFraction, 1e-9)
        assertEquals(0.55, applied.state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction, 1e-9)
        assertEquals(0.1, hitResult("cobblemon:sitrus_berry", 0.6, 0.1).directDamageFraction, 1e-9,
            "The berry can heal more than the hit removed without making inflicted damage zero")
        assertEquals(0.4, hitResult("cobblemon:sitrus_berry", 0.4, 1.0).directDamageFraction, 1e-9,
            "Overkill still caps damage at the holder's pre-hit HP")
    }

    @Test
    fun `a sitrus berry restores a quarter once the hit brings its holder to half`() {
        val after = hit(item = "cobblemon:sitrus_berry", startHp = 0.8, damage = 0.5)
        assertEquals(0.55, after.hpFraction, 1e-6, "0.30 left, plus a quarter of maximum.")
        assertEquals(null, after.knownHeldItemId, "The berry is eaten.")
    }

    @Test
    fun `a berry does not fire while its holder is still above half`() {
        val after = hit(item = "cobblemon:sitrus_berry", startHp = 1.0, damage = 0.2)
        assertEquals(0.8, after.hpFraction, 1e-6, "Nothing triggered it.")
        assertEquals("cobblemon:sitrus_berry", after.knownHeldItemId, "So it is still held.")
    }

    @Test
    fun `a berry does not rescue a fainted holder`() {
        val after = hit(item = "cobblemon:sitrus_berry", startHp = 0.4, damage = 1.0)
        assertTrue(after.fainted, "A knockout is a knockout; the berry only reacts to surviving.")
    }

    @Test
    fun `an unheld berry changes nothing`() {
        val after = hit(item = null, startHp = 0.8, damage = 0.5)
        assertEquals(0.3, after.hpFraction, 1e-6, "Plain damage.")
    }

    private fun hit(item: String?, startHp: Double, damage: Double): BattlePokemonStateView =
        hitResult(item, startHp, damage).state.pokemon.single { it.side == BattleSide.OPPONENT }

    private fun hitResult(item: String?, startHp: Double, damage: Double): LocalAppliedDirectHit {
        val ally = mon(BattleSide.ALLY, null, 1.0)
        val opponent = mon(BattleSide.OPPONENT, item, startHp)
        val state = BattleStateView(
            battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 2,
            pokemon = listOf(ally, opponent), field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
            observedEvents = emptyList(), inferences = emptyList(),
        )
        val applied = LocalDirectHitMechanics.apply(
            state = state,
            actorId = ally.battlePokemonId,
            targetId = opponent.battlePokemonId,
            incomingDamageFraction = damage,
            effects = emptyList(),
            ignoreTargetAbility = false,
        )
        return applied
    }

    private fun mon(side: BattleSide, item: String?, hpFraction: Double) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
        speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = hpFraction,
        statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = item, fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(160, 120, 100, 100, 100, 100)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(150, 170), BattleIntegerRange(110, 130), BattleIntegerRange(90, 110),
                BattleIntegerRange(90, 110), BattleIntegerRange(90, 110), BattleIntegerRange(90, 110),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )
}
