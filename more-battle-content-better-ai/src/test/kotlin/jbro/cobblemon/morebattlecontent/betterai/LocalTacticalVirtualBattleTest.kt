package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSession
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnowledgePolicy
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

class LocalTacticalVirtualBattleTest {
    @Test
    fun `five thousand randomized complete preset battles attack without switch-only stalls`() {
        SEEDS.forEach { seed ->
            val summary = VirtualLeague(Random(seed), seed).run(BATTLES_PER_SEED)
            val report = summary.report()
            println(report)

            assertEquals(0, summary.stalledBattles, report)
            assertTrue(summary.attackDecisionRate >= MIN_ATTACK_DECISION_RATE, report)
            assertTrue(summary.voluntarySwitchRate <= MAX_VOLUNTARY_SWITCH_RATE, report)
            assertTrue(summary.maximumConsecutiveVoluntarySwitches <= MAX_CONSECUTIVE_VOLUNTARY_SWITCHES, report)
            assertTrue(summary.sidesWithoutAnyAttack <= MAX_SIDES_WITHOUT_ATTACK, report)
            assertTrue(summary.distinctSetsUsed >= minOf(MIN_DISTINCT_SETS_USED, summary.rosterPresetCount), report)
            assertTrue(summary.distinctSpeciesUsed >= minOf(MIN_DISTINCT_SPECIES_USED, summary.rosterSpeciesCount), report)
            assertTrue(summary.uniqueTeamRate >= MIN_UNIQUE_TEAM_RATE, report)
            assertEquals(0, summary.adjacentRepeatedTeams, report)
        }
    }

    private class VirtualLeague(
        private val random: Random,
        private val seed: Int,
    ) {
        private val roster = LocalTacticalSimulationRoster.load()

        fun run(battles: Int): LeagueSummary {
            val summary = LeagueSummary(
                battles = battles,
                seed = seed,
                rosterPresetCount = roster.entries.size,
                rosterSpeciesCount = roster.entries.map { it.speciesId }.distinct().size,
            )
            repeat(battles) { index ->
                summary.accept(runBattle(index))
            }
            return summary
        }

        private fun runBattle(index: Int): BattleResult {
            val battleId = UUID(random.nextLong(), random.nextLong())
            val brain = LocalTacticalBrain()
            val left = team("left", battleId, brain)
            val right = team("right", battleId, brain)
            val trace = mutableListOf<String>()
            var completedTurn = 0

            for (turn in 1..MAX_TURNS) {
                completedTurn = turn
                if (!prepareActive(left, right, turn, trace) || !prepareActive(right, left, turn, trace)) break

                val leftChoice = choose(left, right, turn)
                val rightChoice = choose(right, left, turn)
                left.memory.accept(turn, leftChoice.candidate)
                right.memory.accept(turn, rightChoice.candidate)
                left.actions.accept(leftChoice.candidate)
                right.actions.accept(rightChoice.candidate)

                applySwitch(left, leftChoice, trace, turn)
                applySwitch(right, rightChoice, trace, turn)

                val speedTieWinner = if (random.nextBoolean()) left.label else right.label
                val attacks = listOf(left to leftChoice, right to rightChoice)
                    .filter { (_, choice) -> choice.candidate.kind == BattleActionKind.USE_MOVE }
                    .sortedWith(
                        compareByDescending<Pair<TeamState, Choice>> { (team, _) -> team.active.template.stats.speed }
                            .thenBy { (team, _) -> if (team.label == speedTieWinner) 0 else 1 },
                    )
                attacks.forEach { (attacker, choice) ->
                    val defender = if (attacker === left) right else left
                    applyMove(attacker, defender, choice, turn, trace)
                }

                if (!left.hasLivingPokemon() || !right.hasLivingPokemon()) break
            }

            val stalled = left.hasLivingPokemon() && right.hasLivingPokemon() && completedTurn >= MAX_TURNS
            val winner = when {
                left.hasLivingPokemon() && !right.hasLivingPokemon() -> "left"
                right.hasLivingPokemon() && !left.hasLivingPokemon() -> "right"
                else -> null
            }
            return BattleResult(
                index = index,
                turns = completedTurn,
                stalled = stalled,
                winner = winner,
                left = left.actions,
                right = right.actions,
                leftSetIds = left.fighters.map { it.template.setId },
                rightSetIds = right.fighters.map { it.template.setId },
                leftSpeciesIds = left.fighters.map { it.template.speciesId },
                rightSpeciesIds = right.fighters.map { it.template.speciesId },
                trace = trace,
            )
        }

        private fun team(label: String, battleId: UUID, brain: LocalTacticalBrain): TeamState {
            val templates = roster.randomTeam(random, TEAM_SIZE)
            val fighters = templates.map { template ->
                Fighter(
                    id = UUID(random.nextLong(), random.nextLong()),
                    template = template,
                    hpFraction = 1.0,
                )
            }.toMutableList()
            val session = brain.openSession(
                BattleBrainOpenContext(
                    battleId = battleId,
                    format = BattleFormat.SINGLE,
                    knowledgePolicy = BattleKnowledgePolicy.FAIR_INFERENCE,
                ),
            )
            return TeamState(label, battleId, brain, session, fighters)
        }

        private fun prepareActive(
            team: TeamState,
            opponent: TeamState,
            turn: Int,
            trace: MutableList<String>,
        ): Boolean {
            if (!team.hasLivingPokemon()) return false
            if (!team.active.fainted) return true
            val choice = choose(team, opponent, turn, forcedSwitchOnly = true)
            team.memory.accept(turn, choice.candidate)
            team.actions.acceptForcedSwitch()
            team.activeIndex = team.fighters.indexOfFirst { it.id == choice.candidate.switchPokemonId }
            trace += "t$turn ${team.label} forced -> ${team.active.template.speciesId}"
            return true
        }

        private fun choose(
            team: TeamState,
            opponent: TeamState,
            turn: Int,
            forcedSwitchOnly: Boolean = false,
        ): Choice {
            val candidates = candidates(team, forcedSwitchOnly)
            val context = context(team, opponent, turn, candidates)
            val decision = team.brain.decide(team.session, context).toCompletableFuture().join()
            return Choice(
                actorId = team.active.id,
                candidate = candidates.single { it.actionId == decision.actionId },
            )
        }

        private fun candidates(team: TeamState, forcedSwitchOnly: Boolean): List<BattleActionCandidate> {
            val switches = team.fighters.filter { !it.fainted && it.id != team.active.id }.map { target ->
                BattleActionCandidate(
                    actionId = "switch:${target.id}",
                    kind = BattleActionKind.SWITCH,
                    actorSlot = 0,
                    switchPokemonId = target.id,
                )
            }
            if (forcedSwitchOnly) return switches
            val moves = team.active.template.moves.mapIndexed { slot, move ->
                BattleActionCandidate(
                    actionId = "move:${team.active.id}:${move.id}",
                    kind = BattleActionKind.USE_MOVE,
                    actorSlot = 0,
                    moveSlot = slot,
                    moveId = move.id,
                    targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                    moveDetails = BattleMoveCandidateView(
                        typeId = move.typeId,
                        damageCategory = move.category,
                        power = move.power,
                        accuracy = move.accuracy,
                        priority = move.priority,
                        currentPp = 10,
                        targetPattern = BattleMoveTargetPattern.SELECTED,
                    ),
                )
            }
            return moves + switches
        }

        private fun context(
            team: TeamState,
            opponent: TeamState,
            turn: Int,
            candidates: List<BattleActionCandidate>,
        ) = BattleDecisionContext(
            requestId = UUID(random.nextLong(), random.nextLong()),
            state = state(team, opponent, turn),
            candidates = candidates,
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = team.memory.view(turn),
        )

        private fun state(team: TeamState, opponent: TeamState, turn: Int): BattleStateView {
            val ownViews = team.fighters.mapIndexed { index, fighter ->
                fighter.view(
                    side = BattleSide.ALLY,
                    active = index == team.activeIndex && !fighter.fainted,
                    own = true,
                    reveal = true,
                )
            }
            val opponentViews = opponent.fighters.mapIndexed { index, fighter ->
                fighter.view(
                    side = BattleSide.OPPONENT,
                    active = index == opponent.activeIndex && !fighter.fainted,
                    own = false,
                    reveal = index == opponent.activeIndex || fighter.fainted,
                )
            }
            return BattleStateView(
                battleId = team.battleId,
                format = BattleFormat.SINGLE,
                turn = turn,
                pokemon = ownViews + opponentViews,
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = mapOf(
                    BattleSide.ALLY to team.fighters.count { !it.fainted },
                    BattleSide.OPPONENT to opponent.fighters.count { !it.fainted },
                ),
                observedEvents = emptyList(),
                inferences = emptyList(),
            )
        }

        private fun applySwitch(team: TeamState, choice: Choice, trace: MutableList<String>, turn: Int) {
            if (choice.candidate.kind != BattleActionKind.SWITCH) return
            team.activeIndex = team.fighters.indexOfFirst { it.id == choice.candidate.switchPokemonId }
            trace += "t$turn ${team.label} switch -> ${team.active.template.speciesId}"
        }

        private fun applyMove(
            attacker: TeamState,
            defender: TeamState,
            choice: Choice,
            turn: Int,
            trace: MutableList<String>,
        ) {
            if (attacker.active.fainted || attacker.active.id != choice.actorId || !defender.hasLivingPokemon()) return
            val rawContext = context(attacker, defender, turn, listOf(choice.candidate))
            val calculated = PublicBattleTacticalCalculator.calculate(rawContext).candidates.single()
            val facts = requireNotNull(calculated.facts)
            val accuracy = requireNotNull(facts.baseAccuracyProbability)
            if (random.nextDouble() > accuracy) {
                trace += "t$turn ${attacker.label} ${calculated.moveId} miss"
                return
            }
            val damageRange = requireNotNull(facts.standardDamageFractionRange) {
                "Virtual battle move must have a standard damage projection: ${calculated.moveId}"
            }
            val damage = if (damageRange.minimum == damageRange.maximum) {
                damageRange.minimum
            } else {
                random.nextDouble(damageRange.minimum, damageRange.maximum)
            }
            defender.active.hpFraction = (defender.active.hpFraction - damage).coerceAtLeast(0.0)
            trace += "t$turn ${attacker.label} ${calculated.moveId} damage=${"%.3f".format(damage)} " +
                "target=${defender.active.template.speciesId} hp=${"%.3f".format(defender.active.hpFraction)}"
        }
    }

    private class TeamState(
        val label: String,
        val battleId: UUID,
        val brain: LocalTacticalBrain,
        val session: BattleBrainSession,
        val fighters: MutableList<Fighter>,
        var activeIndex: Int = 0,
        val memory: SimulatedMemory = SimulatedMemory(),
        val actions: SideActions = SideActions(),
    ) {
        val active: Fighter get() = fighters[activeIndex]
        fun hasLivingPokemon(): Boolean = fighters.any { !it.fainted }
    }

    private data class Choice(
        val actorId: UUID,
        val candidate: BattleActionCandidate,
    )

    private data class Fighter(
        val id: UUID,
        val template: LocalTacticalSimulationEntry,
        var hpFraction: Double,
    ) {
        val fainted: Boolean get() = hpFraction <= 0.0

        fun view(side: BattleSide, active: Boolean, own: Boolean, reveal: Boolean) = BattlePokemonStateView(
            battlePokemonId = id,
            side = side,
            activeSlot = if (active) 0 else null,
            speciesId = template.speciesId,
            formId = template.formId,
            level = LEVEL,
            hpFraction = hpFraction,
            statusId = null,
            statStages = emptyMap(),
            knownMoveIds = if (own) template.moves.mapTo(linkedSetOf()) { it.id } else emptySet(),
            knownAbilityId = template.abilityId.takeIf { own },
            knownHeldItemId = template.heldItemId.takeIf { own },
            fainted = fainted,
            knownTypeIds = if (reveal) template.typeIds else emptySet(),
            combatStats = when {
                own -> template.stats.exactView()
                active -> template.stats.publicView(PUBLIC_STAT_MIN, PUBLIC_STAT_MAX)
                else -> null
            },
        )
    }

    private class SimulatedMemory {
        private var lastSwitchTurn: Int? = null
        private var switchesThisBattle: Int = 0
        private var lastMoveId: String? = null
        private var sameMoveRepeatCount: Int = 0

        fun view(turn: Int) = BattleTacticalMemoryView(
            turnsSinceLastSwitch = lastSwitchTurn?.let { (turn - it).coerceAtLeast(0) },
            switchesThisBattle = switchesThisBattle,
            lastMoveId = lastMoveId,
            sameMoveRepeatCount = sameMoveRepeatCount,
        )

        fun accept(turn: Int, candidate: BattleActionCandidate) {
            if (candidate.kind == BattleActionKind.SWITCH) {
                switchesThisBattle += 1
                lastSwitchTurn = turn
            }
            val moveId = candidate.moveId
            if (moveId == null) {
                lastMoveId = null
                sameMoveRepeatCount = 0
            } else if (moveId == lastMoveId) {
                sameMoveRepeatCount += 1
            } else {
                lastMoveId = moveId
                sameMoveRepeatCount = 1
            }
        }
    }

    private class SideActions {
        var attacks: Int = 0
            private set
        var voluntarySwitches: Int = 0
            private set
        var forcedSwitches: Int = 0
            private set
        var maximumConsecutiveVoluntarySwitches: Int = 0
            private set
        private var currentVoluntarySwitchStreak: Int = 0

        fun accept(candidate: BattleActionCandidate) {
            when (candidate.kind) {
                BattleActionKind.USE_MOVE -> {
                    attacks += 1
                    currentVoluntarySwitchStreak = 0
                }

                BattleActionKind.SWITCH -> {
                    voluntarySwitches += 1
                    currentVoluntarySwitchStreak += 1
                    maximumConsecutiveVoluntarySwitches = maxOf(
                        maximumConsecutiveVoluntarySwitches,
                        currentVoluntarySwitchStreak,
                    )
                }

                else -> currentVoluntarySwitchStreak = 0
            }
        }

        fun acceptForcedSwitch() {
            forcedSwitches += 1
            currentVoluntarySwitchStreak = 0
        }
    }

    private data class BattleResult(
        val index: Int,
        val turns: Int,
        val stalled: Boolean,
        val winner: String?,
        val left: SideActions,
        val right: SideActions,
        val leftSetIds: List<String>,
        val rightSetIds: List<String>,
        val leftSpeciesIds: List<String>,
        val rightSpeciesIds: List<String>,
        val trace: List<String>,
    ) {
        val voluntarySwitches: Int get() = left.voluntarySwitches + right.voluntarySwitches
        val attacks: Int get() = left.attacks + right.attacks
        val maximumConsecutiveVoluntarySwitches: Int
            get() = maxOf(left.maximumConsecutiveVoluntarySwitches, right.maximumConsecutiveVoluntarySwitches)
    }

    private class LeagueSummary(
        private val battles: Int,
        private val seed: Int,
        val rosterPresetCount: Int,
        val rosterSpeciesCount: Int,
    ) {
        var totalTurns: Int = 0
            private set
        var maximumTurns: Int = 0
            private set
        var stalledBattles: Int = 0
            private set
        var attacks: Int = 0
            private set
        var voluntarySwitches: Int = 0
            private set
        var forcedSwitches: Int = 0
            private set
        var maximumConsecutiveVoluntarySwitches: Int = 0
            private set
        var sidesWithoutAnyAttack: Int = 0
            private set
        var adjacentRepeatedTeams: Int = 0
            private set
        private var sameTeamMatchups: Int = 0
        private var leftWins: Int = 0
        private var rightWins: Int = 0
        private var draws: Int = 0
        private var worstTrace: List<String> = emptyList()
        private var worstSwitchStreak: Int = -1
        private val usedSetIds = linkedSetOf<String>()
        private val usedSpeciesIds = linkedSetOf<String>()
        private val uniqueTeamSignatures = linkedSetOf<String>()
        private var previousLeftTeam: String? = null
        private var previousRightTeam: String? = null

        val attackDecisionRate: Double
            get() = attacks.toDouble() / (attacks + voluntarySwitches).coerceAtLeast(1)
        val voluntarySwitchRate: Double
            get() = voluntarySwitches.toDouble() / (attacks + voluntarySwitches).coerceAtLeast(1)
        val distinctSetsUsed: Int get() = usedSetIds.size
        val distinctSpeciesUsed: Int get() = usedSpeciesIds.size
        val uniqueTeamRate: Double get() = uniqueTeamSignatures.size.toDouble() / (battles * 2).coerceAtLeast(1)

        fun accept(result: BattleResult) {
            val leftTeam = result.leftSetIds.joinToString("|")
            val rightTeam = result.rightSetIds.joinToString("|")
            if (leftTeam == previousLeftTeam) adjacentRepeatedTeams += 1
            if (rightTeam == previousRightTeam) adjacentRepeatedTeams += 1
            if (leftTeam == rightTeam) sameTeamMatchups += 1
            previousLeftTeam = leftTeam
            previousRightTeam = rightTeam
            uniqueTeamSignatures += leftTeam
            uniqueTeamSignatures += rightTeam
            usedSetIds += result.leftSetIds
            usedSetIds += result.rightSetIds
            usedSpeciesIds += result.leftSpeciesIds
            usedSpeciesIds += result.rightSpeciesIds
            totalTurns += result.turns
            maximumTurns = maxOf(maximumTurns, result.turns)
            if (result.stalled) stalledBattles += 1
            attacks += result.attacks
            voluntarySwitches += result.voluntarySwitches
            forcedSwitches += result.left.forcedSwitches + result.right.forcedSwitches
            maximumConsecutiveVoluntarySwitches = maxOf(
                maximumConsecutiveVoluntarySwitches,
                result.maximumConsecutiveVoluntarySwitches,
            )
            if (result.left.attacks == 0) sidesWithoutAnyAttack += 1
            if (result.right.attacks == 0) sidesWithoutAnyAttack += 1
            when (result.winner) {
                "left" -> leftWins += 1
                "right" -> rightWins += 1
                else -> draws += 1
            }
            if (result.maximumConsecutiveVoluntarySwitches > worstSwitchStreak) {
                worstSwitchStreak = result.maximumConsecutiveVoluntarySwitches
                worstTrace = result.trace
            }
        }

        fun report(): String = buildString {
            appendLine("LOCAL_BRAIN_VIRTUAL_LEAGUE")
            appendLine("battles=$battles seed=$seed")
            appendLine(
                "roster_presets=$rosterPresetCount roster_species=$rosterSpeciesCount " +
                    "sets_used=$distinctSetsUsed species_used=$distinctSpeciesUsed",
            )
            appendLine(
                "team_draws=${battles * 2} unique_teams=${uniqueTeamSignatures.size} " +
                    "unique_team_rate=${"%.4f".format(uniqueTeamRate)} adjacent_repeats=$adjacentRepeatedTeams " +
                    "same_team_matchups=$sameTeamMatchups",
            )
            appendLine("wins_left=$leftWins wins_right=$rightWins draws=$draws stalled=$stalledBattles")
            appendLine("turns_total=$totalTurns turns_average=${"%.2f".format(totalTurns.toDouble() / battles)} turns_max=$maximumTurns")
            appendLine("attack_decisions=$attacks attack_rate=${"%.4f".format(attackDecisionRate)}")
            appendLine("voluntary_switches=$voluntarySwitches voluntary_switch_rate=${"%.4f".format(voluntarySwitchRate)}")
            appendLine("forced_switches=$forcedSwitches max_consecutive_voluntary_switches=$maximumConsecutiveVoluntarySwitches")
            appendLine("sides_without_any_attack=$sidesWithoutAnyAttack")
            appendLine("worst_switch_trace:")
            worstTrace.take(MAX_TRACE_LINES).forEach(::appendLine)
        }
    }

    private companion object {
        val SEEDS = listOf(8_192_026, 731_993, 2_026_081, 73_193, 19_937)
        const val BATTLES_PER_SEED = 1_000
        const val TEAM_SIZE = 3
        const val LEVEL = 50
        const val MAX_TURNS = 100
        const val MAX_TRACE_LINES = 24
        const val PUBLIC_STAT_MIN = 0.85
        const val PUBLIC_STAT_MAX = 1.15
        const val MIN_ATTACK_DECISION_RATE = 0.70
        const val MAX_VOLUNTARY_SWITCH_RATE = 0.30
        const val MAX_CONSECUTIVE_VOLUNTARY_SWITCHES = 2
        const val MAX_SIDES_WITHOUT_ATTACK = 10
        const val MIN_DISTINCT_SETS_USED = 100
        const val MIN_DISTINCT_SPECIES_USED = 50
        const val MIN_UNIQUE_TEAM_RATE = 0.99
    }
}

private fun LocalTacticalSimulationStats.exactView() = BattleCombatStatRangesView.exact(
    maxHp,
    attack,
    defence,
    specialAttack,
    specialDefence,
    speed,
)

private fun LocalTacticalSimulationStats.publicView(minimumScale: Double, maximumScale: Double) =
    BattleCombatStatRangesView(
        maxHp = publicRange(maxHp, minimumScale, maximumScale),
        attack = publicRange(attack, minimumScale, maximumScale),
        defence = publicRange(defence, minimumScale, maximumScale),
        specialAttack = publicRange(specialAttack, minimumScale, maximumScale),
        specialDefence = publicRange(specialDefence, minimumScale, maximumScale),
        speed = publicRange(speed, minimumScale, maximumScale),
        knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
    )

private fun publicRange(value: Int, minimumScale: Double, maximumScale: Double) = BattleIntegerRange(
    minimum = (value * minimumScale).roundToInt().coerceAtLeast(1),
    maximum = (value * maximumScale).roundToInt().coerceAtLeast(1),
)
