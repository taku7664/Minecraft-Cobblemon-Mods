package jbro.cobblemon.mcc.internal.ai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `a drop on the stat the move attacks with costs more than one on its defences`() {
        fun physical(vararg drops: Pair<String, Int>) = move("fighting", category = BattleMoveDamageCategory.PHYSICAL, power = 120.0,
            effects = listOf(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, statStages = mapOf(*drops))))
        val plain = score(move("fighting", category = BattleMoveDamageCategory.PHYSICAL, power = 120.0))
        val closeCombat = score(physical("def" to -1, "spd" to -1))
        val superpower = score(physical("atk" to -1, "def" to -1))
        val draco = score(move("dragon", power = 120.0, effects = listOf(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE,
            BattleMoveEffectTarget.USER, statStages = mapOf("specialattack" to -2)))))

        // Defences: 5% a stage. The attacking stat: its real multiplier over two uses (-1 keeps 5/6, -2 3/4).
        assertEquals(plain * 0.90, closeCombat, 1e-6)
        assertEquals(plain * (5.0 / 6.0 - 0.05), superpower, 1e-6)
        assertEquals(score(move("dragon", power = 120.0)) * 0.75, draco, 1e-6)
        assertTrue(closeCombat > superpower)
        // A drop on the target is 10% of the damage a stage: a 60-power spread Speed drop stays below Close Combat.
        val bulldoze = move("ground", category = BattleMoveDamageCategory.PHYSICAL, power = 60.0, target = BattleMoveTargetPattern.ALL_OPPONENTS,
            effects = listOf(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.SELECTED_TARGET, statStages = mapOf("spe" to -1))))
        assertEquals(60.0 * 1.5 * 1.1, BattleOpponentMoveExpectationScorer.score(pokemon(), bulldoze, BattleFormat.DOUBLE), 1e-6)
        assertTrue(BattleOpponentMoveExpectationScorer.score(pokemon(), physical("def" to -1, "spd" to -1), BattleFormat.DOUBLE) >
            BattleOpponentMoveExpectationScorer.score(pokemon(), bulldoze, BattleFormat.DOUBLE))
    }

    @Test
    fun `an attack a set cannot count on scores nothing and a delayed one half`() {
        val plain = move("fighting", category = BattleMoveDamageCategory.PHYSICAL, power = 150.0)
        assertEquals(0.0, BattleOpponentMoveExpectationScorer.score(pokemon(), plain, BattleFormat.SINGLE, "focuspunch"), 1e-9)
        val terrain = move("steel", category = BattleMoveDamageCategory.PHYSICAL, power = 130.0, requirements = listOf(
            BattleMoveRequirementView(BattleMoveRequirementKind.TERRAIN_PRESENT)))
        assertEquals(0.0, score(terrain), 1e-9)
        val delayed = move("psychic", power = 120.0, flags = listOf("futuremove"))
        assertEquals(score(move("psychic", power = 120.0)) / 2.0, score(delayed), 1e-9)
        // Blizzard's weather only raises its accuracy: no cut.
        val weather = move("ice", power = 110.0, requirements = listOf(BattleMoveRequirementView(BattleMoveRequirementKind.WEATHER_ANY_OF, setOf("snow"))))
        assertEquals(score(move("ice", power = 110.0)), score(weather), 1e-9)
    }

    @Test
    fun `own stat changes take the board's reading in place of the set-level values`() {
        val scaleShot = move("dragon", category = BattleMoveDamageCategory.PHYSICAL, power = 25.0, effects = listOf(
            BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, statStages = mapOf("speed" to 1, "defence" to -1))))
        val plain = move("dragon", category = BattleMoveDamageCategory.PHYSICAL, power = 25.0)
        fun board(change: Double?) = BattleOwnStageValue { _, _, _ -> change }
        // The Speed turns exchanges: a quarter of its damage more.
        assertEquals(score(plain) * 1.25, BattleOpponentMoveExpectationScorer.score(pokemon(), scaleShot, BattleFormat.SINGLE, "scaleshot", board(0.25)), 1e-9)
        // It turns nothing: no more than the plain hit.
        assertEquals(score(plain), BattleOpponentMoveExpectationScorer.score(pokemon(), scaleShot, BattleFormat.SINGLE, "scaleshot", board(0.0)), 1e-9)
        // No board reading: the set-level values.
        assertEquals(score(scaleShot), BattleOpponentMoveExpectationScorer.score(pokemon(), scaleShot, BattleFormat.SINGLE, "scaleshot", board(null)), 1e-9)
    }

    @Test
    fun `a multi-hit attack counts its average hits`() {
        val twice = move("flying", category = BattleMoveDamageCategory.PHYSICAL, power = 40.0, effects = listOf(
            BattleMoveEffectView(BattleMoveEffectKind.MULTI_HIT, BattleMoveEffectTarget.SELECTED_TARGET, amountRange = BattleIntegerRange(2, 2))))
        assertEquals(score(move("flying", category = BattleMoveDamageCategory.PHYSICAL, power = 80.0)), score(twice), 1e-9)
    }

    @Test
    fun `Tera Blast is STAB in a publicly Terastallized user's Tera type`() {
        val terastallized = BattlePokemonStateView(
            java.util.UUID.randomUUID(), BattleSide.OPPONENT, 0, "probe", null, 50, 1.0, null,
            emptyMap<String, Int>(), emptySet<String>(), null, null, false, setOf("fire"), knownVolatileEffectIds = emptySet(), knownBaseStabTypeIds = setOf("fairy"), knownTeraTypeId = "fire",
        )
        val blast = move("normal", power = 80.0)
        assertEquals(1.5 * BattleOpponentMoveExpectationScorer.score(terastallized, blast, BattleFormat.SINGLE),
            BattleOpponentMoveExpectationScorer.score(terastallized, blast, BattleFormat.SINGLE, "terablast"), 1e-6)
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
        requirements: List<BattleMoveRequirementView> = emptyList(),
        flags: List<String> = emptyList(),
    ) = BattleMoveCandidateView(
        type,
        category,
        power,
        100.0,
        0,
        16,
        target,
        if (effects.isEmpty() && requirements.isEmpty() && flags.isEmpty()) null else
            BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, effects, scriptedBehavior = false,
                requirements = requirements, mechanicFlags = flags.toSet()),
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
