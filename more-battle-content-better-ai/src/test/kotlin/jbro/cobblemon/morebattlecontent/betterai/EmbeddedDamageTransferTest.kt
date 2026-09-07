package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDirectHitMechanics
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedDamageTransferTest {
    @Test
    fun `native drain and recoil transfer damage HP rather than target HP fractions`(@TempDir directory: Path) {
        val result = EmbeddedShowdownOracle.damageTransfer(directory)
        val cases = result.getAsJsonArray("cases").map { it.asJsonObject }
        assertEquals(8, cases.size)
        cases.forEach { sample ->
            val id = sample["id"].asString
            val dealt = sample["targetBefore"].asInt - sample["targetAfter"].asInt
            val actorMax = sample["actorMax"].asInt
            val targetMax = sample["targetMax"].asInt
            assertNotEquals(actorMax, targetMax, id)
            assertTrue(dealt > 0, id)
            val expectedHp = (dealt.toDouble() * sample["numerator"].asInt / sample["denominator"].asInt).roundToInt().coerceAtLeast(1)
            val before = sample["actorBefore"].asInt
            val after = sample["actorAfter"].asInt
            val drain = sample["kind"].asString == "drain"
            assertEquals(if (drain) (before + expectedHp).coerceAtMost(actorMax) else (before - expectedHp).coerceAtLeast(0), after, id)
            assertEquals(if (drain) "-heal" else "-damage", sample["selfEvent"].asString, id)
            val actorId = UUID.randomUUID()
            val targetId = UUID.randomUUID()
            fun pokemon(uuid: UUID, side: BattleSide, hp: Int, maximum: Int) = BattlePokemonStateView(
                uuid, side, 0, "test", null, 50, hp.toDouble() / maximum, null, emptyMap(), emptySet(), null, null, false,
                combatStats = BattleCombatStatRangesView(BattleIntegerRange(maximum, maximum),
                    BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
                    BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
                    if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
            val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1,
                listOf(pokemon(actorId, BattleSide.ALLY, before, actorMax),
                    pokemon(targetId, BattleSide.OPPONENT, sample["targetBefore"].asInt, targetMax)),
                BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
            val ratio = sample["numerator"].asDouble / sample["denominator"].asDouble
            val effect = BattleMoveEffectView(
                kind = if (drain) BattleMoveEffectKind.DRAIN_FRACTION else BattleMoveEffectKind.RECOIL_FRACTION,
                target = BattleMoveEffectTarget.USER, probability = 1.0, fractionRange = BattleFractionRange(ratio, ratio))
            val projected = LocalDirectHitMechanics.apply(state, actorId, targetId, dealt.toDouble() / targetMax, listOf(effect), false)
            assertEquals(after.toDouble() / actorMax, projected.state.pokemon.single { it.battlePokemonId == actorId }.hpFraction, 1e-12, id)
        }
        println("DAMAGE_TRANSFER cases=${cases.size} engine=${result["engineSha256"].asString}")
    }
}
