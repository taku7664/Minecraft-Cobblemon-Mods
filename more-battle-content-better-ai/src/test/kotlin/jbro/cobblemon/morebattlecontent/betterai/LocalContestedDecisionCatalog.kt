package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView

internal data class LocalContestedDecision(
    val name: String,
    val expected: String,
    val context: BattleDecisionContext,
)

/**
 * The decisions whose outcome moved when the scoring units were unified.
 *
 * Each one is a position where the legacy and current tunings disagree, kept in one place so the
 * disagreement is measured rather than settled by whichever assertion happened to be written first.
 */
internal object LocalContestedDecisionCatalog {
    fun all(t: LocalTacticalBrainSimulationTest): List<LocalContestedDecision> = listOf(
        secureKnockoutVersusImmunitySwitch(t),
        rotationAfterRecentSwitch(t),
        statusVersusSmallDamage(t),
    )

    /** Guaranteed knockout available at 8% opponent HP, versus switching to a type immunity. */
    private fun secureKnockoutVersusImmunitySwitch(t: LocalTacticalBrainSimulationTest): LocalContestedDecision {
        val immuneBenchId = UUID.randomUUID()
        return LocalContestedDecision(
            name = "secure knockout vs immunity switch",
            expected = "secure_finish",
            context = t.contextOf(
                candidates = listOf(
                    t.move(
                        id = "secure_finish",
                        power = 40.0,
                        typeId = "water",
                        facts = t.guaranteedDamageFacts(0.10),
                    ),
                    t.switch("switch_to_immunity", immuneBenchId),
                ),
                turn = 6,
                allyHp = 0.15,
                opponentHp = 0.08,
                allyTypes = setOf("water"),
                opponentTypes = setOf("electric"),
                allyBench = mapOf(
                    immuneBenchId to LocalTacticalBrainSimulationTest.BenchPokemon(1.0, setOf("ground")),
                ),
            ),
        )
    }

    /** Low-HP attacker with damage available, one turn after it was itself switched in. */
    private fun rotationAfterRecentSwitch(t: LocalTacticalBrainSimulationTest): LocalContestedDecision {
        val thirdPokemonId = UUID.randomUUID()
        return LocalContestedDecision(
            name = "rotation on the turn after a voluntary switch",
            expected = "available_damage",
            context = t.contextOf(
                candidates = listOf(
                    t.move(
                        id = "available_damage",
                        power = 60.0,
                        typeId = "normal",
                        facts = t.damageFacts(0.20),
                    ),
                    t.switch("rotate_to_third", thirdPokemonId),
                ),
                turn = 9,
                allyHp = 0.15,
                allyTypes = setOf("fire"),
                opponentTypes = setOf("ground"),
                allyBench = mapOf(
                    thirdPokemonId to LocalTacticalBrainSimulationTest.BenchPokemon(1.0, setOf("flying")),
                ),
                memory = BattleTacticalMemoryView(
                    turnsSinceLastSwitch = 1,
                    switchesThisBattle = 3,
                ),
            ),
        )
    }

    /** Paralysis versus a small but real attack, at high HP. */
    private fun statusVersusSmallDamage(t: LocalTacticalBrainSimulationTest): LocalContestedDecision =
        LocalContestedDecision(
            name = "status vs small damage at high hp",
            expected = "small_damage",
            context = t.contextOf(
                candidates = listOf(
                    t.move(
                        id = "small_damage",
                        power = 40.0,
                        typeId = "normal",
                        facts = t.damageFacts(0.12),
                    ),
                    t.statusMove(id = "paralyse"),
                ),
                turn = 4,
                allyHp = 0.9,
                allyTypes = setOf("normal"),
                opponentTypes = setOf("normal"),
            ),
        )
}
