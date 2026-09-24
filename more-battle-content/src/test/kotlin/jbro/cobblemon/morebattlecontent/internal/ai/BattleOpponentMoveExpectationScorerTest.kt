package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleOpponentMoveExpectationScorerTest {
    @Test
    fun `same type attack bonus is part of expected attack strength`() {
        val stab = move("fairy", power = 80.0)
        val coverage = move("rock", power = 100.0)

        assertTrue(score(stab) > score(coverage))
    }

    @Test
    fun `secondary stat changes contribute their actual probability`() {
        val plain = move("fairy", power = 90.0)
        val tenPercentDrop = move("fairy", power = 90.0, effects = listOf(
            BattleMoveEffectView(
                BattleMoveEffectKind.STAT_STAGE,
                BattleMoveEffectTarget.SELECTED_TARGET,
                probability = 0.10,
                statStages = mapOf("attack" to -1),
            ),
        ))
        val guaranteedDrop = move("fairy", power = 90.0, effects = listOf(
            BattleMoveEffectView(
                BattleMoveEffectKind.STAT_STAGE,
                BattleMoveEffectTarget.SELECTED_TARGET,
                probability = 1.0,
                statStages = mapOf("attack" to -1),
            ),
        ))

        assertTrue(score(plain) < score(tenPercentDrop))
        assertTrue(score(tenPercentDrop) < score(guaranteedDrop))
    }

    @Test
    fun `self drawbacks reduce expected attack strength`() {
        val plain = move("fairy", power = 100.0)
        val drawback = move("fairy", power = 100.0, effects = listOf(
            BattleMoveEffectView(
                BattleMoveEffectKind.STAT_STAGE,
                BattleMoveEffectTarget.USER,
                statStages = mapOf("specialattack" to -2),
            ),
        ))

        assertTrue(score(drawback) < score(plain))
    }

    @Test
    fun `public attack ranges prefer the matching damage category`() {
        val physical = move("normal", category = BattleMoveDamageCategory.PHYSICAL, power = 100.0)
        val special = move("normal", category = BattleMoveDamageCategory.SPECIAL, power = 100.0)
        val physicalPokemon = pokemon().copyWithStats(attack = 180, specialAttack = 80)

        assertTrue(BattleOpponentMoveExpectationScorer.score(physicalPokemon, physical, BattleFormat.SINGLE) >
            BattleOpponentMoveExpectationScorer.score(physicalPokemon, special, BattleFormat.SINGLE))
    }

    @Test
    fun `spread attacks gain expected value only in doubles`() {
        val spread = move("fairy", power = 80.0, target = BattleMoveTargetPattern.ALL_OPPONENTS)
        val selected = move("fairy", power = 90.0)

        assertTrue(BattleOpponentMoveExpectationScorer.score(pokemon(), spread, BattleFormat.SINGLE) <
            BattleOpponentMoveExpectationScorer.score(pokemon(), selected, BattleFormat.SINGLE))
        assertTrue(BattleOpponentMoveExpectationScorer.score(pokemon(), spread, BattleFormat.DOUBLE) >
            BattleOpponentMoveExpectationScorer.score(pokemon(), selected, BattleFormat.DOUBLE))
    }

    private fun score(move: BattleMoveCandidateView): Double =
        BattleOpponentMoveExpectationScorer.score(pokemon(), move, BattleFormat.SINGLE)

    private fun move(
        type: String,
        category: BattleMoveDamageCategory = BattleMoveDamageCategory.SPECIAL,
        power: Double,
        target: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        effects: List<BattleMoveEffectView> = emptyList(),
    ) = BattleMoveCandidateView(
        type,
        category,
        power,
        100.0,
        0,
        16,
        target,
        effects.takeIf(List<*>::isNotEmpty)?.let {
            BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, it, scriptedBehavior = false)
        },
    )

    private fun pokemon() = BattlePokemonStateView(
        UUID.randomUUID(), BattleSide.OPPONENT, 0, "probe", null, 50, 1.0, null,
        emptyMap(), emptySet(), null, null, false, setOf("fairy"),
    )

    private fun BattlePokemonStateView.copyWithStats(attack: Int, specialAttack: Int) = BattlePokemonStateView(
        battlePokemonId, side, activeSlot, speciesId, formId, level, hpFraction, statusId, statStages,
        knownMoveIds, knownAbilityId, knownHeldItemId, fainted, knownTypeIds,
        BattleCombatStatRangesView(
            BattleIntegerRange(150, 150),
            BattleIntegerRange(attack, attack),
            BattleIntegerRange(100, 100),
            BattleIntegerRange(specialAttack, specialAttack),
            BattleIntegerRange(100, 100),
            BattleIntegerRange(100, 100),
            BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
    )
}
