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
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
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
    fun `five thousand three on three battles attack without switch-only stalls`() {
        SEEDS.forEach { seed ->
            val summary = VirtualLeague(Random(seed), seed).run(BATTLES_PER_SEED)
            val report = summary.report()
            println(report)

            assertEquals(0, summary.stalledBattles, report)
            assertTrue(summary.attackDecisionRate >= MIN_ATTACK_DECISION_RATE, report)
            assertTrue(summary.voluntarySwitchRate <= MAX_VOLUNTARY_SWITCH_RATE, report)
            assertTrue(summary.maximumConsecutiveVoluntarySwitches <= MAX_CONSECUTIVE_VOLUNTARY_SWITCHES, report)
            assertTrue(summary.sidesWithoutAnyAttack <= MAX_SIDES_WITHOUT_ATTACK, report)
        }
    }

    private class VirtualLeague(
        private val random: Random,
        private val seed: Int,
    ) {
        fun run(battles: Int): LeagueSummary {
            val summary = LeagueSummary(battles, seed)
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
            return BattleResult(index, completedTurn, stalled, winner, left.actions, right.actions, trace)
        }

        private fun team(label: String, battleId: UUID, brain: LocalTacticalBrain): TeamState {
            val templates = TEMPLATES.shuffled(random).take(TEAM_SIZE)
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
                    moveId = "cobblemon:${move.id}",
                    targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                    moveDetails = BattleMoveCandidateView(
                        typeId = move.typeId,
                        damageCategory = move.category,
                        power = move.power,
                        accuracy = move.accuracy,
                        priority = 0,
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
        val template: FighterTemplate,
        var hpFraction: Double,
    ) {
        val fainted: Boolean get() = hpFraction <= 0.0

        fun view(side: BattleSide, active: Boolean, own: Boolean, reveal: Boolean) = BattlePokemonStateView(
            battlePokemonId = id,
            side = side,
            activeSlot = if (active) 0 else null,
            speciesId = template.speciesId,
            formId = null,
            level = LEVEL,
            hpFraction = hpFraction,
            statusId = null,
            statStages = emptyMap(),
            knownMoveIds = if (own) template.moves.mapTo(linkedSetOf()) { "cobblemon:${it.id}" } else emptySet(),
            knownAbilityId = null,
            knownHeldItemId = null,
            fainted = fainted,
            knownTypeIds = if (reveal) setOf(template.typeId) else emptySet(),
            combatStats = when {
                own -> template.stats.exactView()
                active -> template.stats.publicView()
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
        private var leftWins: Int = 0
        private var rightWins: Int = 0
        private var draws: Int = 0
        private var worstTrace: List<String> = emptyList()
        private var worstSwitchStreak: Int = -1

        val attackDecisionRate: Double
            get() = attacks.toDouble() / (attacks + voluntarySwitches).coerceAtLeast(1)
        val voluntarySwitchRate: Double
            get() = voluntarySwitches.toDouble() / (attacks + voluntarySwitches).coerceAtLeast(1)

        fun accept(result: BattleResult) {
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

    private data class FighterTemplate(
        val speciesId: String,
        val typeId: String,
        val stats: Stats,
        val moves: List<Move>,
    )

    private data class Stats(
        val maxHp: Int,
        val attack: Int,
        val defence: Int,
        val specialAttack: Int,
        val specialDefence: Int,
        val speed: Int,
    ) {
        fun exactView() = BattleCombatStatRangesView.exact(
            maxHp,
            attack,
            defence,
            specialAttack,
            specialDefence,
            speed,
        )

        fun publicView() = BattleCombatStatRangesView(
            maxHp = publicRange(maxHp),
            attack = publicRange(attack),
            defence = publicRange(defence),
            specialAttack = publicRange(specialAttack),
            specialDefence = publicRange(specialDefence),
            speed = publicRange(speed),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        )

        private fun publicRange(value: Int) = BattleIntegerRange(
            minimum = (value * PUBLIC_STAT_MIN).roundToInt().coerceAtLeast(1),
            maximum = (value * PUBLIC_STAT_MAX).roundToInt().coerceAtLeast(1),
        )
    }

    private data class Move(
        val id: String,
        val typeId: String,
        val power: Double,
        val category: BattleMoveDamageCategory,
        val accuracy: Double = 100.0,
    )

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

        val TEMPLATES = listOf(
            FighterTemplate(
                "showdown:blaze", "fire", Stats(170, 105, 100, 150, 110, 135),
                listOf(Move("flame_burst", "fire", 90.0, BattleMoveDamageCategory.SPECIAL), Move("energy_ball", "grass", 75.0, BattleMoveDamageCategory.SPECIAL)),
            ),
            FighterTemplate(
                "showdown:torrent", "water", Stats(190, 110, 125, 140, 130, 100),
                listOf(Move("surf", "water", 90.0, BattleMoveDamageCategory.SPECIAL), Move("ice_beam", "ice", 75.0, BattleMoveDamageCategory.SPECIAL)),
            ),
            FighterTemplate(
                "showdown:grove", "grass", Stats(185, 120, 120, 135, 125, 95),
                listOf(Move("leaf_storm", "grass", 90.0, BattleMoveDamageCategory.SPECIAL), Move("earth_power", "ground", 75.0, BattleMoveDamageCategory.SPECIAL)),
            ),
            FighterTemplate(
                "showdown:volt", "electric", Stats(165, 110, 95, 150, 105, 145),
                listOf(Move("thunderbolt", "electric", 90.0, BattleMoveDamageCategory.SPECIAL), Move("air_slash", "flying", 75.0, BattleMoveDamageCategory.SPECIAL, 95.0)),
            ),
            FighterTemplate(
                "showdown:quake", "ground", Stats(205, 155, 135, 90, 105, 85),
                listOf(Move("earthquake", "ground", 90.0, BattleMoveDamageCategory.PHYSICAL), Move("rock_slide", "rock", 75.0, BattleMoveDamageCategory.PHYSICAL, 90.0)),
            ),
            FighterTemplate(
                "showdown:crag", "rock", Stats(200, 150, 155, 80, 105, 75),
                listOf(Move("stone_edge", "rock", 90.0, BattleMoveDamageCategory.PHYSICAL, 85.0), Move("brick_break", "fighting", 75.0, BattleMoveDamageCategory.PHYSICAL)),
            ),
            FighterTemplate(
                "showdown:brawl", "fighting", Stats(185, 155, 115, 80, 105, 115),
                listOf(Move("close_combat", "fighting", 90.0, BattleMoveDamageCategory.PHYSICAL), Move("night_slash", "dark", 75.0, BattleMoveDamageCategory.PHYSICAL)),
            ),
            FighterTemplate(
                "showdown:mind", "psychic", Stats(170, 85, 95, 155, 130, 125),
                listOf(Move("psychic", "psychic", 90.0, BattleMoveDamageCategory.SPECIAL), Move("moonblast", "fairy", 75.0, BattleMoveDamageCategory.SPECIAL)),
            ),
            FighterTemplate(
                "showdown:frost", "ice", Stats(175, 120, 100, 145, 110, 115),
                listOf(Move("ice_beam", "ice", 90.0, BattleMoveDamageCategory.SPECIAL), Move("water_pulse", "water", 75.0, BattleMoveDamageCategory.SPECIAL)),
            ),
            FighterTemplate(
                "showdown:wing", "flying", Stats(175, 145, 100, 95, 105, 140),
                listOf(Move("brave_bird", "flying", 90.0, BattleMoveDamageCategory.PHYSICAL), Move("fire_fang", "fire", 75.0, BattleMoveDamageCategory.PHYSICAL, 95.0)),
            ),
            FighterTemplate(
                "showdown:shade", "dark", Stats(180, 150, 105, 90, 115, 125),
                listOf(Move("crunch", "dark", 90.0, BattleMoveDamageCategory.PHYSICAL), Move("poison_jab", "poison", 75.0, BattleMoveDamageCategory.PHYSICAL)),
            ),
            FighterTemplate(
                "showdown:charm", "fairy", Stats(180, 90, 105, 150, 135, 110),
                listOf(Move("moonblast", "fairy", 90.0, BattleMoveDamageCategory.SPECIAL), Move("psyshock", "psychic", 75.0, BattleMoveDamageCategory.SPECIAL)),
            ),
        )
    }
}
