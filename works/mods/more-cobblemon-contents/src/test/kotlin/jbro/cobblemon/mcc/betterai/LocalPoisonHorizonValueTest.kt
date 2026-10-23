package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.evaluation.*
import jbro.cobblemon.mcc.betterai.matchup.*
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalPoisonHorizonValueTest {
    @Test fun `poison and toxic use integer ticks and accuracy once`() {
        assertEquals(36.0 / 146 * 100, read("psn")!!.score(LocalDecisionTuning.CURRENT), 1e-10)
        assertEquals(27.0 / 146 * 100 * 0.9, read("tox", accuracy = 0.9)!!.score(LocalDecisionTuning.CURRENT), 1e-10)
    }
    @Test fun `residual loss is capped by remaining HP with a separate KO price`() {
        val r = read("tox", hp = 10.0 / 146)!!
        assertEquals(10.0 / 146 * 100 + LocalDecisionTuning.CURRENT.knockoutMaterialScore,
            r.score(LocalDecisionTuning.CURRENT), 1e-10)
    }
    @Test fun `magic guard does not lose HP and poison heal keeps its future healing evaluation`() {
        assertEquals(0.0, read("tox", ability = "magicguard")!!.score(LocalDecisionTuning.CURRENT), 1e-10)
        assertNull(read("tox", hp = 0.5, ability = "poisonheal"))
        assertNull(read("tox", hp = 1.0, ability = "poisonheal"))
    }
    @Test fun `immunity and already statused candidates add no poison value`() {
        assertEquals(0.0, read("tox", lands = false)!!.score(LocalDecisionTuning.CURRENT))
    }
    @Test fun `immediate cure stat abilities unknown ability and disabled approximation keep full evaluation`() {
        assertNull(read("tox", item = "lumberry"))
        assertNull(read("tox", ability = "guts"))
        assertNull(read("tox", ability = "shedskin"))
        assertNull(read("tox", ability = "soulheart"))
        assertNull(read("tox", ability = null))
        assertNull(read("tox", tuning = LocalDecisionTuning.CURRENT.copy(poisonEvaluationTurns = null)))
    }
    @Test fun `leftovers is projected before each residual without exceeding the HP bar`() {
        assertEquals(27.0 / 146 * 100, read("tox", hp = 0.5, item = "leftovers")!!.score(LocalDecisionTuning.CURRENT), 1e-10)
    }

    private fun read(status: String, hp: Double = 1.0, ability: String? = "pressure", item: String? = null,
        lands: Boolean = true, accuracy: Double = 1.0,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT.copy(poisonEvaluationTurns = 2)): LocalDebuffValue.Reading? {
        val target = BattlePokemonStateView(UUID(0, 1), BattleSide.OPPONENT, 0, "mew", null, 50, hp, null,
            emptyMap(), emptySet(), ability, item, false, knownTypeIds = setOf("psychic"),
            combatStats = BattleCombatStatRangesView(BattleIntegerRange(146, 146), BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
        val ally = BattlePokemonStateView(UUID(0, 2), BattleSide.ALLY, 0, "mew", null, 50, 1.0, null,
            emptyMap(), emptySet(), "pressure", null, false)
        val state = BattleStateView(UUID(0, 3), BattleFormat.SINGLE, 1, listOf(target, ally), BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
        val action = BattleActionCandidate("poison", BattleActionKind.USE_MOVE, 0, 0, "toxic")
        val context = BattleDecisionContext(UUID(0, 4), state, listOf(action), Long.MAX_VALUE)
        return LocalPoisonHorizonValue.read(action, context, target, status, lands, accuracy, 0.0, tuning)
    }
}
