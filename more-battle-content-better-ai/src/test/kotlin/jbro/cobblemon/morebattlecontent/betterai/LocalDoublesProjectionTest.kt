package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Measures the scoring path a double battle actually takes.
 *
 * `PublicBattleTacticalCalculator` resolves a defender by taking the single active opponent, so with
 * two of them on the field an untargeted or spread move has no resolvable target and the Showdown
 * projection is skipped. Doubles therefore mixes projected and unprojected candidates on the same
 * turn as a matter of course - which is exactly the condition that made the old scorer compare raw
 * move power against HP fractions and prefer the resisted move 83% of the time.
 *
 * Every whole-battle number this module reports comes from a singles harness, so none of it covers
 * this. These tests build fixtures that carry public combat stats, because without them the
 * projection cannot run in either format and a comparison between the two would measure nothing.
 */
class LocalDoublesProjectionTest {
    @Test
    fun `a second active opponent removes the damage projection from an untargeted move`() {
        val singles = PublicBattleTacticalCalculator
            .calculate(context(opponentPartner = false, targeted = false))
            .candidates.single().facts
        val doubles = PublicBattleTacticalCalculator
            .calculate(context(opponentPartner = true, targeted = false))
            .candidates.single().facts
        val doublesTargeted = PublicBattleTacticalCalculator
            .calculate(context(opponentPartner = true, targeted = true))
            .candidates.single().facts

        val report = buildString {
            appendLine("=".repeat(96))
            appendLine("DOUBLES PROJECTION COVERAGE")
            appendLine("=".repeat(96))
            listOf(
                "singles, untargeted" to singles,
                "doubles, untargeted" to doubles,
                "doubles, explicit target" to doublesTargeted,
            ).forEach { (label, facts) ->
                appendLine(
                    String.format(
                        "  %-26s damageRange=%-14s typeMultiplier=%s",
                        label,
                        facts?.standardDamageFractionRange?.let {
                            String.format("%.3f-%.3f", it.minimum, it.maximum)
                        } ?: "none",
                        facts?.typeChartMultiplier?.toString() ?: "unknown",
                    ),
                )
            }
            appendLine()
            appendLine("An untargeted move loses its projection the moment a second opponent is present.")
            appendLine("Naming a target restores it, so the gap is target resolution, not the format.")
        }
        println(report)

        assertNotNull(singles?.standardDamageFractionRange, report)
        assertNull(doubles?.standardDamageFractionRange, report)
        assertNotNull(doublesTargeted?.standardDamageFractionRange, report)
    }

    @Test
    fun `mixed projected and unprojected candidates rank by effectiveness in doubles`() {
        var legacyWrong = 0
        var currentWrong = 0
        MATCHUPS.forEach { (strong, weak, defender) ->
            if (picksResisted(strong, weak, defender, LocalDecisionTuning.LEGACY)) legacyWrong++
            if (picksResisted(strong, weak, defender, LocalDecisionTuning.CURRENT)) currentWrong++
        }

        val report = buildString {
            appendLine("=".repeat(96))
            appendLine("RESISTED-MOVE MEASUREMENT IN DOUBLES  matchups=${MATCHUPS.size}")
            appendLine("=".repeat(96))
            appendLine("The super-effective move names a target, so it projects. The resisted move is a")
            appendLine("spread move, so it does not. That is an ordinary doubles turn, and it is the exact")
            appendLine("shape that put two different scales into one comparison.")
            appendLine()
            appendLine(String.format("  legacy (pre-fix)   resisted-pick rate=%6.1f%%  (%d/%d)",
                legacyWrong * 100.0 / MATCHUPS.size, legacyWrong, MATCHUPS.size))
            appendLine(String.format("  current (fixed)    resisted-pick rate=%6.1f%%  (%d/%d)",
                currentWrong * 100.0 / MATCHUPS.size, currentWrong, MATCHUPS.size))
        }
        println(report)

        assertTrue(currentWrong <= legacyWrong, report)
        assertTrue(currentWrong == 0, report)
    }

    private fun picksResisted(
        strongType: String,
        weakType: String,
        defenderTypes: Set<String>,
        tuning: LocalDecisionTuning,
    ): Boolean {
        val state = state(opponentPartner = true, defenderTypes = defenderTypes)
        val candidates = listOf(
            move("super_effective", 70.0, strongType, BattleMoveTargetPattern.SELECTED_OPPONENT, targeted = true),
            move("resisted", 120.0, weakType, BattleMoveTargetPattern.ALL_OPPONENTS, targeted = false),
        )
        val breakdown = LocalDecisionInstrumentation.inspect(
            context = BattleDecisionContext(
                requestId = UUID.randomUUID(),
                state = state,
                candidates = candidates,
                deadlineEpochMillis = Long.MAX_VALUE,
                memory = BattleTacticalMemoryView.empty(),
            ),
            tuning = tuning,
        )
        return breakdown.chosenByRanking?.actionId == "resisted"
    }

    private fun context(opponentPartner: Boolean, targeted: Boolean) = BattleDecisionContext(
        requestId = UUID.randomUUID(),
        state = state(opponentPartner, setOf("rock")),
        candidates = listOf(
            move("hit", 90.0, "water", BattleMoveTargetPattern.SELECTED_OPPONENT, targeted),
        ),
        deadlineEpochMillis = Long.MAX_VALUE,
        memory = BattleTacticalMemoryView.empty(),
    )

    private fun move(
        id: String,
        power: Double,
        typeId: String,
        targetPattern: BattleMoveTargetPattern,
        targeted: Boolean,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = if (targeted) listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)) else emptyList(),
        moveDetails = BattleMoveCandidateView(
            typeId = typeId,
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = power,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = targetPattern,
        ),
    )

    private fun state(opponentPartner: Boolean, defenderTypes: Set<String>): BattleStateView {
        // Active slot count has to match the format on both sides, or BattleStateView rejects the
        // state. The variable under test is the number of *opponents*, so the ally side simply
        // mirrors the format.
        val pokemon = buildList {
            add(pokemon(BattleSide.ALLY, 0, setOf("normal")))
            add(pokemon(BattleSide.OPPONENT, 0, defenderTypes))
            if (opponentPartner) {
                add(pokemon(BattleSide.ALLY, 1, setOf("normal")))
                add(pokemon(BattleSide.OPPONENT, 1, defenderTypes))
            }
        }
        return BattleStateView(
            battleId = UUID.randomUUID(),
            format = if (opponentPartner) BattleFormat.DOUBLE else BattleFormat.SINGLE,
            turn = 3,
            pokemon = pokemon,
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { side ->
                pokemon.count { it.side == side }
            },
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
    }

    /** Carries public combat stats, without which no projection can run in any format. */
    private fun pokemon(side: BattleSide, slot: Int, types: Set<String>) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(),
        side = side,
        activeSlot = slot,
        speciesId = "showdown:probe",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = types,
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(160, 160),
            attack = BattleIntegerRange(120, 120),
            defence = BattleIntegerRange(100, 100),
            specialAttack = BattleIntegerRange(120, 120),
            specialDefence = BattleIntegerRange(100, 100),
            speed = BattleIntegerRange(100, 100),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
    )

    private companion object {
        /** Same pairs the singles probe uses, already verified super-effective versus resisted. */
        val MATCHUPS = listOf(
            Triple("water", "fire", setOf("rock", "ground")),
            Triple("electric", "fighting", setOf("water", "flying")),
            Triple("ground", "normal", setOf("steel", "rock")),
            Triple("ice", "water", setOf("dragon", "flying")),
            Triple("fighting", "normal", setOf("rock", "steel")),
            Triple("fire", "grass", setOf("bug", "steel")),
            Triple("psychic", "fighting", setOf("poison", "fighting")),
            Triple("flying", "ground", setOf("grass", "fighting")),
        )
    }
}
