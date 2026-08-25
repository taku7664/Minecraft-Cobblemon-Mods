package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalImmediateTurnScorer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalImmediateTurnScorerTest {
    @Test
    fun `scores only the current turn material rank status and speed changes`() {
        val before = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, hp = 1.0, speed = 80),
            opponent = pokemon(OPPONENT_ID, BattleSide.OPPONENT, hp = 1.0, speed = 100),
        )
        val after = state(
            ally = pokemon(
                ALLY_ID,
                BattleSide.ALLY,
                hp = 0.60,
                speed = 80,
                stages = mapOf("attack" to 2, "speed" to 2),
            ),
            opponent = pokemon(
                OPPONENT_ID,
                BattleSide.OPPONENT,
                hp = 0.50,
                speed = 100,
                status = "par",
                stages = mapOf("defense" to -1),
            ),
        )

        val score = LocalImmediateTurnScorer.score(before, after)

        assertEquals(0.10, score.materialDelta, 1e-9)
        assertTrue(score.stageDelta > 0.0)
        assertTrue(score.statusDelta > 0.0)
        assertTrue(score.speedControlDelta > 0.0)
        assertEquals(
            score.materialDelta + score.stageDelta + score.statusDelta + score.speedControlDelta + score.fieldDelta,
            score.total,
            1e-9,
        )
    }

    @Test
    fun `knockout receives more value than hp damage alone`() {
        val before = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, hp = 1.0, speed = 100),
            opponent = pokemon(OPPONENT_ID, BattleSide.OPPONENT, hp = 0.40, speed = 80),
            opponentRemaining = 1,
        )
        val damaged = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, hp = 1.0, speed = 100),
            opponent = pokemon(OPPONENT_ID, BattleSide.OPPONENT, hp = 0.10, speed = 80),
            opponentRemaining = 1,
        )
        val knockedOut = state(
            ally = pokemon(ALLY_ID, BattleSide.ALLY, hp = 1.0, speed = 100),
            opponent = pokemon(OPPONENT_ID, BattleSide.OPPONENT, hp = 0.0, speed = 80, fainted = true),
            opponentRemaining = 0,
        )

        assertTrue(
            LocalImmediateTurnScorer.score(before, knockedOut).total >
                LocalImmediateTurnScorer.score(before, damaged).total + 1.0,
        )
    }

    @Test
    fun `fractional knockout bonus is proportional to possible knockout rolls`() {
        // Board value of removing a Pokemon, which is exactly what being alive is worth in
        // sideMaterial: 2.0, not the 2.5 that fell out of dividing a ranking-layer score constant by
        // a hard-coded exchange rate.
        assertEquals(
            2.0 * (6.0 / 16.0),
            LocalImmediateTurnScorer.expectedKnockoutBonus(BattleSide.ALLY, 6.0 / 16.0),
            1e-9,
        )
        assertEquals(
            -2.0 * (6.0 / 16.0),
            LocalImmediateTurnScorer.expectedKnockoutBonus(BattleSide.OPPONENT, 6.0 / 16.0),
            1e-9,
        )
    }

    private fun state(
        ally: BattlePokemonStateView,
        opponent: BattlePokemonStateView,
        opponentRemaining: Int = 1,
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(ally, opponent),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to opponentRemaining),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        hp: Double,
        speed: Int,
        status: String? = null,
        stages: Map<String, Int> = emptyMap(),
        fainted: Boolean = false,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = if (fainted) null else 0,
        speciesId = "showdown:test",
        formId = null,
        level = 50,
        hpFraction = hp,
        statusId = status,
        statStages = stages,
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = fainted,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(100, 100, 100, 100, 100, speed)
        } else {
            BattleCombatStatRangesView(
                maxHp = BattleIntegerRange(100, 100),
                attack = BattleIntegerRange(100, 100),
                defence = BattleIntegerRange(100, 100),
                specialAttack = BattleIntegerRange(100, 100),
                specialDefence = BattleIntegerRange(100, 100),
                speed = BattleIntegerRange(speed, speed),
                knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
    }
}
