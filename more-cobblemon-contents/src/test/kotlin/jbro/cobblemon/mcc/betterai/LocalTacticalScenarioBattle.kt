package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.outcome.ChanceEffectProjectionMode
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelection
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelector
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector
import jbro.cobblemon.mcc.betterai.state.PublicTurnProjection
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory
import jbro.cobblemon.mcc.betterai.state.RecursiveMoveUseKey
import jbro.cobblemon.mcc.betterai.state.RecursiveHistoryProjector
import kotlin.math.roundToInt
import kotlin.random.Random

internal data class LocalTacticalScenarioDefinition(
    val name: String,
    val cycleSetIds: List<String>,
    val offenseSetIds: List<String>,
    val seed: Int,
    /**
     * Singles unless a scenario says otherwise.
     *
     * The harness was hardcoded to singles, which meant the doubles half of the product had no
     * measurement at all - not a worse number, no number. Every rejection and every acceptance in
     * this plan was decided on one format. Nothing about the projector needed changing: it already
     * flattens a joint action into its components. What was missing was a board with two slots on it.
     */
    val format: BattleFormat = BattleFormat.SINGLE,
) {
    /** How many slots each side has on the field at once. */
    val activeSlots: Int get() = if (format == BattleFormat.DOUBLE) 2 else 1

    /** Doubles needs a fourth body, or a single knockout ends the battle with a slot still empty. */
    val teamSize: Int get() = if (format == BattleFormat.DOUBLE) 4 else 3
}

internal data class LocalTacticalScenarioTurn(
    val turn: Int,
    val cycleIdeal: String,
    val offenseIdeal: String,
    val cycleActual: String,
    val offenseActual: String,
    val result: String,
    /** The top of each side's ranking, with comparison values, for reading a battle back. */
    val cycleTop: String = "",
    val offenseTop: String = "",
    /** The ally side's chosen action id and its ranking's action ids, best first. */
    val cycleActualId: String = "",
    val cycleRankedIds: List<String> = emptyList(),
)

internal data class LocalTacticalScenarioReport(
    val definition: LocalTacticalScenarioDefinition,
    val turns: List<LocalTacticalScenarioTurn>,
    val winner: String?,
    val stalled: Boolean,
    val cycleStatusMoves: Int,
    val cycleVoluntarySwitches: Int,
    val offenseStatusMoves: Int,
    val offenseVoluntarySwitches: Int,
    val publicEvidenceCounts: Map<BattleObservedEventKind, Int> = emptyMap(),
    /** Each team's HP left at the end, in whole Pokemon (a full team of four is 4.0). */
    val cycleRemainingHp: Double = 0.0,
    val offenseRemainingHp: Double = 0.0,
    /** Plain mistakes by kind; see the battle's countBlunders. */
    val cycleBlunders: Map<String, Int> = emptyMap(),
    val offenseBlunders: Map<String, Int> = emptyMap(),
) {
    fun documentationLog(): String = buildString {
        appendLine("SCENARIO=${definition.name} seed=${definition.seed}")
        appendLine("cycle=${definition.cycleSetIds.joinToString(",")}")
        appendLine("offense=${definition.offenseSetIds.joinToString(",")}")
        turns.forEach { turn ->
            appendLine(
                "T${turn.turn}|ideal_cycle=${turn.cycleIdeal}|ideal_offense=${turn.offenseIdeal}|" +
                    "actual_cycle=${turn.cycleActual}|actual_offense=${turn.offenseActual}|result=${turn.result}",
            )
        }
        appendLine(
            "END winner=${winner ?: "draw"} stalled=$stalled " +
                "cycle_status=$cycleStatusMoves cycle_switches=$cycleVoluntarySwitches " +
                "offense_status=$offenseStatusMoves offense_switches=$offenseVoluntarySwitches",
        )
    }
}

/** A puzzle's starting position, keyed by (side, team index): the first member of each side is in front. */
internal data class LocalScenarioStart(
    val hp: Map<Pair<BattleSide, Int>, Double> = emptyMap(),
    val status: Map<Pair<BattleSide, Int>, String> = emptyMap(),
    val stages: Map<Pair<BattleSide, Int>, Map<String, Int>> = emptyMap(),
    val revealAll: Boolean = true,
)

/**
 * A fixed policy that plays a side in place of its brain, as a yardstick for how much the brain adds: any legal action
 * at random, or always the attack with the largest expected damage (a switch only when nothing else is legal).
 */
internal enum class LocalScenarioPolicy { RANDOM, GREEDY }

/** One decision of a replayed battle played as [actionId] by [side] at [turn], the rest drawn from [rolloutSeed]. */
internal data class LocalScenarioFork(val turn: Int, val side: BattleSide, val actionId: String, val rolloutSeed: Long)

/** Focused 3v3 executor that reuses the production public single-turn projector. */
internal object LocalTacticalScenarioBattle {
    fun run(
        definition: LocalTacticalScenarioDefinition,
        maximumTurns: Int = 15,
        cycleTuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        offenseTuning: LocalDecisionTuning = cycleTuning,
        cycleDifficulty: BattleDifficultyProfile = BattleDifficultyProfiles.STANDARD,
        offenseDifficulty: BattleDifficultyProfile = cycleDifficulty,
        /**
         * When supplied, every decision context the battle builds is appended here.
         *
         * Replaying real positions is the only cheap way to ask whether a deeper search decides
         * anything differently. Win rate cannot answer it: separating a ten point edge needs hundreds
         * of battles per arm, and a Boss decision is allowed three seconds.
         */
        recordedContexts: MutableList<BattleDecisionContext>? = null,
        recordedDecisions: MutableList<LocalScenarioDecisionTrace>? = null,
        /** The search budget both sides get; null is the shipped one. */
        lookaheadBudget: ((BattleTrainerTier) -> LocalLookaheadBudget)? = null,
        /** Replays the battle up to one decision, plays the given action there, and draws the rest afresh. */
        fork: LocalScenarioFork? = null,
        /** A position to start from instead of a fresh lead: HP, stages and status per team member, all revealed. */
        start: LocalScenarioStart? = null,
        /** Sides played by a fixed policy instead of their brain's choice. */
        policies: Map<BattleSide, LocalScenarioPolicy> = emptyMap(),
    ): LocalTacticalScenarioReport = Battle(
        definition, cycleTuning, offenseTuning, cycleDifficulty, offenseDifficulty, recordedContexts, recordedDecisions,
        lookaheadBudget, fork, start, policies,
    ).run(maximumTurns)

    private class Battle(
        private val definition: LocalTacticalScenarioDefinition,
        cycleTuning: LocalDecisionTuning,
        offenseTuning: LocalDecisionTuning,
        cycleDifficulty: BattleDifficultyProfile,
        offenseDifficulty: BattleDifficultyProfile,
        private val recordedContexts: MutableList<BattleDecisionContext>?,
        private val recordedDecisions: MutableList<LocalScenarioDecisionTrace>?,
        private val lookaheadBudget: ((BattleTrainerTier) -> LocalLookaheadBudget)?,
        private val fork: LocalScenarioFork?,
        private val start: LocalScenarioStart?,
        private val policies: Map<BattleSide, LocalScenarioPolicy>,
    ) {
        /** The fixed policies' own draws, apart from the battle's so the rolls stay comparable. */
        private val policyRandom = Random(definition.seed.toLong() * 31 + 7)
        private val difficulties = mapOf(
            BattleSide.ALLY to cycleDifficulty,
            BattleSide.OPPONENT to offenseDifficulty,
        )
        private val tunings = mapOf(
            BattleSide.ALLY to cycleTuning,
            BattleSide.OPPONENT to offenseTuning,
        )
        private var random = Random(definition.seed)
        private val roster = LocalTacticalSimulationRoster.loadAll()
        private val battleId = UUID(random.nextLong(), random.nextLong())
        private val templates = linkedMapOf<UUID, LocalTacticalSimulationEntry>()
        private val cycleIds = createTeam(definition.cycleSetIds)
        private val offenseIds = createTeam(definition.offenseSetIds)
        private val revealedPokemonIds = (
            cycleIds.take(definition.activeSlots) + offenseIds.take(definition.activeSlots)
            ).toCollection(linkedSetOf())
        private val revealedMoveIds = mutableMapOf<UUID, MutableSet<String>>()
        private val revealedAbilityIds = hashSetOf<UUID>()
        private val selectors = BattleSide.entries.associateWith { CapturingWeightedSelector() }
        private val actualBrains = BattleSide.entries.associateWith { side ->
            if (lookaheadBudget == null) LocalTacticalBrain(selectors.getValue(side), tunings.getValue(side))
            else LocalTacticalBrain(selectors.getValue(side), tunings.getValue(side), lookaheadBudget)
        }
        private val profiles = BattleSide.entries.associateWith { side ->
            BattleTrainerProfile(
                skillLevel = 2,
                personality = BattleTrainerProfile.champion().personality,
                difficulty = difficulties.getValue(side),
            )
        }
        private val strategies = mapOf(
            BattleSide.ALLY to strategy(
                "cycle",
                setOf(BattleStrategyObjective.PIVOTING, BattleStrategyObjective.STATUS_PRESSURE, BattleStrategyObjective.PRESERVE_CORE),
            ),
            BattleSide.OPPONENT to strategy(
                "offense",
                setOf(BattleStrategyObjective.BALANCED_PRESSURE, BattleStrategyObjective.SETUP_SWEEP, BattleStrategyObjective.SPEED_CONTROL),
            ),
        )
        private val actualSessions = BattleSide.entries.associateWith { side ->
            actualBrains.getValue(side).openSession(openContext(side))
        }
        private val memories = BattleSide.entries.associateWith { ScenarioMemory() }
        private val publicEvidence = LocalScenarioPublicEvidence()
        private var history = RecursiveActionHistory()
        private var state = initialState().let { initial -> start?.let { applyStart(initial, it) } ?: initial }

        /** The puzzle's position: each side's members in team order, the first of them in front. */
        private fun applyStart(initial: BattleStateView, start: LocalScenarioStart): BattleStateView {
            fun member(side: BattleSide, index: Int) = (if (side == BattleSide.ALLY) cycleIds else offenseIds)[index]
            val adjusted = initial.pokemon.map { pokemon ->
                val side = pokemon.side
                val index = (if (side == BattleSide.ALLY) cycleIds else offenseIds).indexOf(pokemon.battlePokemonId)
                val hp = start.hp[side to index] ?: pokemon.hpFraction
                pokemon.copyState(
                    hpFraction = hp,
                    // Zero HP starts it knocked out: a position later in the battle.
                    fainted = pokemon.fainted || hp <= 0.0,
                    statusId = start.status[side to index] ?: pokemon.statusId,
                    statStages = start.stages[side to index] ?: pokemon.statStages,
                )
            }
            if (start.revealAll) {
                revealedPokemonIds += cycleIds + offenseIds
                templates.forEach { (id, template) -> revealedMoveIds.getOrPut(id, ::linkedSetOf).addAll(template.moves.map { it.id }) }
            }
            check(member(BattleSide.ALLY, 0) in adjusted.filter { it.activeSlot != null }.map { it.battlePokemonId })
            return initial.copyState(pokemon = adjusted)
        }
        private var cycleStatusMoves = 0
        private var offenseStatusMoves = 0
        private var cycleVoluntarySwitches = 0
        private var offenseVoluntarySwitches = 0
        private val cycleBlunders = linkedMapOf<String, Int>()
        private val offenseBlunders = linkedMapOf<String, Int>()

        fun run(maximumTurns: Int): LocalTacticalScenarioReport {
            val turns = mutableListOf<LocalTacticalScenarioTurn>()
            for (ignored in 0 until maximumTurns) {
                forceReplacement(BattleSide.ALLY)
                forceReplacement(BattleSide.OPPONENT)
                if (ended()) break

                val turn = state.turn
                val cycleCandidates = candidates(BattleSide.ALLY)
                val offenseCandidates = candidates(BattleSide.OPPONENT)
                val cycleActual = choose(BattleSide.ALLY, cycleCandidates)
                val cycleIdeal = selectors.getValue(BattleSide.ALLY).ideal()
                val cycleTop = topLabel(selectors.getValue(BattleSide.ALLY))
                // The ranking as the selector could draw from it: rule exclusions left out.
                val cycleRankedIds = selectors.getValue(BattleSide.ALLY).let { selector ->
                    selector.lastRanked.map { it.outcome.candidate.actionId }.filter { it !in selector.lastExclusions }
                }
                val offenseActual = choose(BattleSide.OPPONENT, offenseCandidates)
                val offenseIdeal = selectors.getValue(BattleSide.OPPONENT).ideal()
                val offenseTop = topLabel(selectors.getValue(BattleSide.OPPONENT))
                val cycleCanonical = toCanonical(cycleActual, BattleSide.ALLY)
                val offenseCanonical = toCanonical(offenseActual, BattleSide.OPPONENT)
                val before = state
                val source = BattleDecisionContext(
                    requestId = UUID(random.nextLong(), random.nextLong()),
                    state = before,
                    candidates = listOf(cycleCanonical),
                    deadlineEpochMillis = Long.MAX_VALUE,
                    publicActionCatalog = mechanicsCatalog(),
                )
                val projections = PublicSingleTurnProjector.project(
                    before,
                    cycleCanonical,
                    offenseCanonical,
                    source,
                    history,
                    chanceEffectMode = ChanceEffectProjectionMode.BRANCH_STATE,
                )
                val outcome = sample(projections)
                state = outcome.state
                publicEvidence.recordTurn(
                    turn = turn,
                    before = before,
                    after = state,
                    allyAction = cycleCanonical,
                    opponentAction = offenseCanonical,
                    outcome = outcome,
                )
                history = RecursiveHistoryProjector.project(
                    previous = history,
                    stateBefore = before,
                    outcome = outcome,
                    allyAction = cycleCanonical,
                    opponentAction = offenseCanonical,
                    publicActionCatalog = mechanicsCatalog(),
                )
                outcome.executedMoveIdsByPokemon.forEach { (pokemonId, moveId) ->
                    revealedMoveIds.getOrPut(pokemonId, ::linkedSetOf).add(moveId)
                }
                revealActives()
                acceptActual(BattleSide.ALLY, cycleCanonical, before, state, outcome)
                acceptActual(BattleSide.OPPONENT, offenseCanonical, before, state, outcome)
                turns += LocalTacticalScenarioTurn(
                    turn = turn,
                    cycleIdeal = actionLabel(cycleIdeal),
                    offenseIdeal = actionLabel(offenseIdeal),
                    cycleActual = actionLabel(cycleActual),
                    offenseActual = actionLabel(offenseActual),
                    result = resultSummary(before, state, cycleCanonical, offenseCanonical, outcome),
                    cycleTop = cycleTop,
                    offenseTop = offenseTop,
                    cycleActualId = cycleActual.actionId,
                    cycleRankedIds = cycleRankedIds,
                )
                if (ended()) break
            }
            val winner = when {
                living(BattleSide.ALLY) > 0 && living(BattleSide.OPPONENT) == 0 -> "cycle"
                living(BattleSide.OPPONENT) > 0 && living(BattleSide.ALLY) == 0 -> "offense"
                else -> null
            }
            return LocalTacticalScenarioReport(
                definition = definition,
                turns = turns,
                winner = winner,
                stalled = winner == null && turns.size >= maximumTurns,
                cycleStatusMoves = cycleStatusMoves,
                cycleVoluntarySwitches = cycleVoluntarySwitches,
                offenseStatusMoves = offenseStatusMoves,
                offenseVoluntarySwitches = offenseVoluntarySwitches,
                publicEvidenceCounts = publicEvidence.counts(),
                cycleRemainingHp = remainingHp(BattleSide.ALLY),
                offenseRemainingHp = remainingHp(BattleSide.OPPONENT),
                cycleBlunders = cycleBlunders.toMap(),
                offenseBlunders = offenseBlunders.toMap(),
            )
        }

        private fun remainingHp(side: BattleSide): Double = state.pokemon
            .filter { it.side == side && !it.fainted }.sumOf { it.hpFraction.coerceAtLeast(0.0) }

        private fun createTeam(setIds: List<String>): List<UUID> {
            require(setIds.size == definition.teamSize) {
                "${definition.format} needs ${definition.teamSize} members, got ${setIds.size}"
            }
            val selected = setIds.map { id ->
                roster.entries.singleOrNull { it.setId == id }
                    ?: LocalTacticalSimulationRoster.loadTournament().entries.single { it.setId == id }
            }
            require(selected.map { it.speciesId }.distinct().size == selected.size)
            require(selected.map { it.heldItemId }.distinct().size == selected.size)
            // The counter is the number already registered, not that plus the position in this team:
            // adding both double-counted, which happened to stay unique at three members a side and
            // collided at four. A doubles battle then had two Pokemon claiming the same identity.
            return selected.map { template ->
                UUID(definition.seed.toLong(), (templates.size + 1).toLong()).also { templates[it] = template }
            }
        }

        private fun initialState(): BattleStateView {
            var initial = BattleStateView(
                battleId = battleId,
                format = definition.format,
                turn = 1,
                pokemon = cycleIds.mapIndexed { index, id -> initialPokemon(id, BattleSide.ALLY, index) } +
                    offenseIds.mapIndexed { index, id -> initialPokemon(id, BattleSide.OPPONENT, index) },
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { definition.teamSize },
                observedEvents = emptyList(),
                inferences = emptyList(),
            )
            initial.pokemon.filter { it.activeSlot != null }.map { it.battlePokemonId }.forEach { activeId ->
                initial = LocalEntryAbilityProjector.project(initial, activeId)
            }
            return initial
        }

        private fun initialPokemon(id: UUID, side: BattleSide, index: Int): BattlePokemonStateView {
            val template = templates.getValue(id)
            return BattlePokemonStateView(
                battlePokemonId = id,
                side = side,
                activeSlot = index.takeIf { it < definition.activeSlots },
                speciesId = template.speciesId,
                formId = template.formId,
                level = LEVEL,
                hpFraction = 1.0,
                statusId = null,
                statStages = emptyMap(),
                knownMoveIds = template.moves.mapTo(linkedSetOf()) { it.id },
                knownAbilityId = template.abilityId,
                knownHeldItemId = template.heldItemId,
                fainted = false,
                knownTypeIds = template.typeIds,
                combatStats = if (side == BattleSide.ALLY) template.stats.exactView() else template.stats.mechanicsView(),
            )
        }

        private fun candidates(side: BattleSide): List<BattleActionCandidate> {
            val view = perspective(side)
            val catalog = decisionCatalog(side)
            // The decision catalog is already a current-PP snapshot. Preserve the other constraints,
            // but do not subtract the battle's historical uses a second time.
            val actions = PublicFutureActionFactory.actions(view, BattleSide.ALLY, catalog,
                perspectiveHistory(side).copy(moveUses = emptyMap()))
            require(actions.isNotEmpty()) { "No legal actions for $side on turn ${state.turn}" }
            return actions
        }

        private fun choose(
            side: BattleSide,
            candidates: List<BattleActionCandidate>,
        ): BattleActionCandidate {
            val brain = actualBrains.getValue(side)
            val session = actualSessions.getValue(side)
            val view = perspective(side)
            val context = BattleDecisionContext(
                requestId = UUID(random.nextLong(), random.nextLong()),
                state = view,
                candidates = candidates,
                deadlineEpochMillis = Long.MAX_VALUE,
                memory = memories.getValue(side).view(state.turn),
                publicActionCatalog = inferredCatalog(side, view, decisionCatalog(side)),
            )
            recordedContexts?.add(context)
            val started = if (recordedDecisions != null) System.nanoTime() else 0L
            val decision = brain.decide(session, context).toCompletableFuture().join()
            recordedDecisions?.add(LocalScenarioDecisionTrace(
                turn = state.turn,
                side = side.name,
                actionId = decision.actionId,
                elapsedNanos = System.nanoTime() - started,
                source = "LOCAL_BRAIN_DIRECT",
                tags = decision.tags.sorted(),
            ))
            if (fork != null && fork.turn == state.turn && fork.side == side && candidates.none { it.actionId.startsWith("forced:") }) {
                // Everything before this point replays the recorded battle; from here the draws are the rollout's own.
                random = Random(fork.rolloutSeed)
                return candidates.single { it.actionId == fork.actionId }
            }
            policies[side]?.let { policy -> return policyChoice(policy, context) }
            return candidates.single { it.actionId == decision.actionId }
        }

        private fun policyChoice(policy: LocalScenarioPolicy, context: BattleDecisionContext): BattleActionCandidate {
            val candidates = context.candidates
            if (policy == LocalScenarioPolicy.RANDOM) return candidates[policyRandom.nextInt(candidates.size)]
            val calculated = jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator.calculate(context)
            fun expected(candidate: BattleActionCandidate): Double {
                val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
                return parts.sumOf { part ->
                    val facts = part.facts ?: return@sumOf 0.0
                    val range = facts.standardDamageFractionRange ?: return@sumOf 0.0
                    (range.minimum + range.maximum) / 2.0 * (facts.baseAccuracyProbability ?: 1.0)
                }
            }
            val best = calculated.candidates.maxByOrNull(::expected)
            val chosenId = if (best != null && expected(best) > 0.0) best.actionId else candidates[policyRandom.nextInt(candidates.size)].actionId
            return candidates.single { it.actionId == chosenId }
        }

        /**
         * Fills every empty slot on a side, one decision each.
         *
         * Singles only ever has one, so this used to ask "is anyone out?" and send a replacement to
         * slot zero. Doubles can lose either slot, or both in the same turn, and each vacancy is its
         * own choice - so the question is now per slot, and it repeats until the side is either full
         * or out of bodies.
         */
        private fun forceReplacement(side: BattleSide) {
            repeat(definition.activeSlots) { forceOneReplacement(side) }
        }

        private fun forceOneReplacement(side: BattleSide) {
            val occupied = state.pokemon.filter {
                it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }.mapTo(mutableSetOf()) { it.activeSlot }
            val emptySlot = (0 until definition.activeSlots).firstOrNull { it !in occupied } ?: return
            val benched = state.pokemon.count {
                it.side == side && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0
            }
            if (benched == 0) return
            val view = perspective(side)
            val candidates = view.pokemon.filter {
                it.side == BattleSide.ALLY && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0
            }.map { target ->
                BattleActionCandidate(
                    actionId = "forced:${target.battlePokemonId}",
                    kind = BattleActionKind.SWITCH,
                    actorSlot = emptySlot,
                    switchPokemonId = target.battlePokemonId,
                )
            }
            val selected = choose(side, candidates)
            val canonical = toCanonical(selected, side)
            state = LocalSwitchStateProjector.project(state, side, canonical)
            publicEvidence.recordReplacement(
                turn = state.turn,
                incomingPokemonId = requireNotNull(canonical.switchPokemonId),
                actorSlot = requireNotNull(canonical.actorSlot),
            )
            memories.getValue(side).accept(
                state.turn,
                canonical,
                executed = true,
                madeProgress = true,
                forcedSwitch = true,
            )
            revealedPokemonIds += requireNotNull(canonical.switchPokemonId)
        }

        private fun perspective(viewer: BattleSide): BattleStateView = BattleStateView(
            battleId = state.battleId,
            format = state.format,
            turn = state.turn,
            pokemon = state.pokemon.map { pokemon -> perspectivePokemon(pokemon, viewer) },
            field = perspectiveField(state.field, viewer),
            remainingPokemonBySide = if (viewer == BattleSide.ALLY) {
                state.remainingPokemonBySide
            } else {
                mapOf(
                    BattleSide.ALLY to state.remainingPokemonBySide.getValue(BattleSide.OPPONENT),
                    BattleSide.OPPONENT to state.remainingPokemonBySide.getValue(BattleSide.ALLY),
                )
            },
            observedEvents = publicEvidence.events(),
            inferences = publicEvidence.inferences(state.pokemon, viewer),
        )

        private fun perspectivePokemon(pokemon: BattlePokemonStateView, viewer: BattleSide): BattlePokemonStateView {
            val own = pokemon.side == viewer
            val public = own || pokemon.battlePokemonId in revealedPokemonIds || pokemon.activeSlot != null
            val template = templates.getValue(pokemon.battlePokemonId)
            return BattlePokemonStateView(
                battlePokemonId = pokemon.battlePokemonId,
                side = perspectiveSide(pokemon.side, viewer),
                activeSlot = pokemon.activeSlot,
                speciesId = if (public) pokemon.speciesId else UNKNOWN_SPECIES,
                formId = pokemon.formId.takeIf { public },
                level = pokemon.level.takeIf { public },
                hpFraction = pokemon.hpFraction,
                statusId = pokemon.statusId.takeIf { public },
                statStages = if (public) pokemon.statStages else emptyMap(),
                knownMoveIds = if (own) template.moves.mapTo(linkedSetOf()) { it.id }
                    else revealedMoveIds[pokemon.battlePokemonId].orEmpty(),
                knownAbilityId = template.abilityId.takeIf { own || pokemon.battlePokemonId in revealedAbilityIds },
                knownHeldItemId = pokemon.knownHeldItemId.takeIf { own },
                fainted = pokemon.fainted,
                knownTypeIds = template.typeIds.takeIf { public }.orEmpty(),
                combatStats = when {
                    own -> template.stats.exactView()
                    public -> template.stats.publicView()
                    else -> null
                },
                actionConstraints = pokemon.actionConstraints,
            )
        }

        private val inferenceMoveDetails by lazy {
            templates.values.flatMap { it.moves }.associate { PublicIds.canonical(it.id) to LocalTacticalSimulationMoveLibrary.details(it) }
        }
        private val inferenceLedgers = BattleSide.entries.associateWith {
            BattleOpponentMoveInferenceLedger { moveId -> inferenceMoveDetails[PublicIds.canonical(moveId)] }
        }

        /**
         * The catalog with the opponent move slots a trainer of this tier is given, as the live battle actor
         * gives the local Brain: without them a Boss saw only the moves already used against it.
         */
        private fun inferredCatalog(
            viewer: BattleSide,
            view: BattleStateView,
            catalog: BattlePublicActionCatalogView,
        ): BattlePublicActionCatalogView {
            val actual = state.pokemon.filter { it.side != viewer }
                .associate { pokemon -> pokemon.battlePokemonId to templates.getValue(pokemon.battlePokemonId).moves.mapTo(linkedSetOf()) { it.id } }
            return catalog.withOpponentMoveInferences(
                inferenceLedgers.getValue(viewer).update(view, catalog, difficulties.getValue(viewer).tier, actual))
        }

        private fun decisionCatalog(viewer: BattleSide): BattlePublicActionCatalogView =
            BattlePublicActionCatalogView(
                templates.map { (id, template) ->
                    val own = state.pokemon.single { it.battlePokemonId == id }.side == viewer
                    val revealed = revealedMoveIds[id].orEmpty()
                    val moves = template.moves.filter { own || it.id in revealed }.map { move ->
                        BattlePublicMoveOptionView(
                            moveId = move.id,
                            details = LocalTacticalSimulationMoveLibrary.details(move).let { details ->
                                val used = history.moveUses[RecursiveMoveUseKey(id, move.id)] ?: 0
                                details.copy(currentPp = (details.currentPp - used).coerceAtLeast(0))
                            },
                            knowledge = if (own) BattlePublicMoveKnowledge.EXACT_OWN
                            else BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                        )
                    }
                    BattlePokemonActionCatalogView(id, moves, moveSetComplete = own || moves.size == 4)
                },
            )

        private fun mechanicsCatalog(): BattlePublicActionCatalogView = BattlePublicActionCatalogView(
            templates.map { (id, template) ->
                BattlePokemonActionCatalogView(
                    id,
                    template.moves.map { move ->
                        BattlePublicMoveOptionView(
                            move.id,
                            LocalTacticalSimulationMoveLibrary.details(move),
                            BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                        )
                    },
                    moveSetComplete = true,
                )
            },
        )

        private fun perspectiveHistory(viewer: BattleSide): RecursiveActionHistory = if (viewer == BattleSide.ALLY) {
            history
        } else {
            history.copy(
                allySwitchedLastTurn = history.opponentSwitchedLastTurn,
                opponentSwitchedLastTurn = history.allySwitchedLastTurn,
            )
        }

        /**
         * Rewrites an action taken from a side's own perspective into the shared board's terms.
         *
         * Each brain decides on a view where it is always `ALLY`, so the opponent's targets point at
         * the wrong side of the real board. In doubles the decision is a joint action, and its
         * components carry the targets - so the composite has to be rebuilt around rewritten
         * components rather than returned untouched, which is what happened before and would have
         * sent every opposing attack into its own team.
         */
        private fun toCanonical(action: BattleActionCandidate, side: BattleSide): BattleActionCandidate {
            if (action.kind == BattleActionKind.COMPOSITE) {
                val components = action.componentActions.map { toCanonical(it, side) }
                return BattleActionCandidate(
                    actionId = action.actionId,
                    kind = BattleActionKind.COMPOSITE,
                    componentActionIds = action.componentActionIds,
                    componentActions = components,
                    tags = action.tags,
                )
            }
            if (side == BattleSide.ALLY || action.kind !in setOf(BattleActionKind.USE_MOVE, BattleActionKind.SWITCH)) {
                return action
            }
            return BattleActionCandidate(
                actionId = action.actionId,
                kind = action.kind,
                actorSlot = action.actorSlot,
                moveSlot = action.moveSlot,
                moveId = action.moveId,
                targets = action.targets.map { BattleTargetSlot(opposite(it.side), it.slot) },
                switchPokemonId = action.switchPokemonId,
                moveDetails = action.moveDetails,
                tags = action.tags,
            )
        }

        private fun sample(projections: List<PublicTurnProjection>): PublicTurnProjection {
            require(projections.isNotEmpty())
            val total = projections.sumOf { it.probability }
            var draw = random.nextDouble(total)
            projections.forEach { projection ->
                draw -= projection.probability
                if (draw <= 0.0) return projection
            }
            return projections.last()
        }

        private fun acceptActual(
            side: BattleSide,
            action: BattleActionCandidate,
            before: BattleStateView,
            after: BattleStateView,
            outcome: PublicTurnProjection,
        ) {
            // A joint action is not a thing that happened; its components are. The tallies and the
            // memory are per action, so the composite is opened here rather than counted as one move.
            if (action.kind == BattleActionKind.COMPOSITE) {
                action.componentActions.forEach { acceptActual(side, it, before, after, outcome) }
                return
            }
            val executed = when (action.kind) {
                BattleActionKind.USE_MOVE -> side in outcome.executedSides
                BattleActionKind.SWITCH -> true
                else -> true
            }
            val statusMove = action.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS
            val actorBefore = before.pokemon.firstOrNull { it.side == side && it.activeSlot == action.actorSlot }
            val actorAfter = actorBefore?.let { actor ->
                after.pokemon.firstOrNull { it.battlePokemonId == actor.battlePokemonId }
            }
            val targetsBefore = action.targets.mapNotNull { target ->
                before.pokemon.firstOrNull { it.side == target.side && it.activeSlot == target.slot }
            }
            val targetsChanged = targetsBefore.any { targetBefore ->
                val targetAfter = after.pokemon.firstOrNull { it.battlePokemonId == targetBefore.battlePokemonId }
                    ?: return@any false
                targetAfter.hpFraction < targetBefore.hpFraction - EPSILON ||
                    targetAfter.statusId != targetBefore.statusId ||
                    targetAfter.statStages != targetBefore.statStages ||
                    targetAfter.fainted != targetBefore.fainted
            }
            val actorChanged = actorBefore != null && actorAfter != null && (
                actorAfter.hpFraction > actorBefore.hpFraction + EPSILON ||
                    actorAfter.statusId != actorBefore.statusId ||
                    actorAfter.statStages != actorBefore.statStages
                )
            val madeProgress = when (action.kind) {
                BattleActionKind.SWITCH -> action.switchPokemonId?.let { switchedId ->
                    after.pokemon.any { it.battlePokemonId == switchedId && it.activeSlot != null }
                } == true
                BattleActionKind.USE_MOVE -> executed && (targetsChanged || actorChanged || before.field != after.field)
                else -> executed
            }
            memories.getValue(side).accept(state.turn - 1, action, executed, madeProgress)
            if (executed && statusMove) {
                if (side == BattleSide.ALLY) cycleStatusMoves++ else offenseStatusMoves++
            }
            if (action.kind == BattleActionKind.SWITCH) {
                if (side == BattleSide.ALLY) cycleVoluntarySwitches++ else offenseVoluntarySwitches++
            }
            countBlunders(side, action, statusMove, actorAfter, targetsBefore, after)
            if (executed && !statusMove) revealAbsorbingAbilities(action, targetsBefore, after)
        }

        /**
         * An ability that made a hit do nothing is public from then on, as the battle message announces it:
         * the live observation adapter reads "[from] ability: Volt Absorb". Without this a Boss clicked
         * Thunderbolt into the same Volt Absorb four turns running.
         */
        private fun revealAbsorbingAbilities(
            action: BattleActionCandidate,
            targetsBefore: List<BattlePokemonStateView>,
            after: BattleStateView,
        ) {
            val type = action.moveDetails?.typeId ?: return
            for (target in targetsBefore) {
                val now = after.pokemon.firstOrNull { it.battlePokemonId == target.battlePokemonId } ?: continue
                if (now.activeSlot != target.activeSlot || now.hpFraction < target.hpFraction - EPSILON) continue
                val ability = templates.getValue(target.battlePokemonId).abilityId ?: continue
                val chart = jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness
                if (chart.multiplier(type, target.knownTypeIds) > 0.0 &&
                    chart.multiplierAgainst(type, target.knownTypeIds, ability, moveId = action.moveId) == 0.0) {
                    revealedAbilityIds += target.battlePokemonId
                }
            }
        }

        /**
         * Plain mistakes, counted per side: an attack into a type immunity of a Pokemon that stayed in (not a
         * switch it failed to predict), a status move at a target that already has a status, and a setup move
         * whose user was knocked out that same turn.
         */
        private fun countBlunders(
            side: BattleSide,
            action: BattleActionCandidate,
            statusMove: Boolean,
            actorAfter: BattlePokemonStateView?,
            targetsBefore: List<BattlePokemonStateView>,
            after: BattleStateView,
        ) {
            val tally = if (side == BattleSide.ALLY) cycleBlunders else offenseBlunders
            fun stayed(target: BattlePokemonStateView) =
                after.pokemon.firstOrNull { it.side == target.side && it.activeSlot == target.activeSlot }?.battlePokemonId == target.battlePokemonId
            val foes = targetsBefore.filter { it.side != side && !it.fainted && it.hpFraction > 0.0 && stayed(it) }
            val details = action.moveDetails ?: return
            if (action.kind != BattleActionKind.USE_MOVE) return
            if (!statusMove) {
                val type = details.typeId
                if (type != null && foes.any { jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness.multiplier(type, it.knownTypeIds) == 0.0 }) {
                    tally.merge("immune_attack", 1, Int::plus)
                }
                return
            }
            val inflicts = details.effects?.effects.orEmpty().any {
                it.kind == BattleMoveEffectKind.STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET && (it.probability ?: 1.0) >= 1.0
            }
            if (inflicts && foes.any { it.statusId != null }) tally.merge("status_on_statused", 1, Int::plus)
            if (BattleStatusMoveCategories.isPureSelfSetup(details) && actorAfter?.fainted == true) tally.merge("setup_then_fainted", 1, Int::plus)
        }

        private fun revealActives() {
            state.pokemon.filter { it.activeSlot != null }.forEach { revealedPokemonIds += it.battlePokemonId }
        }

        private fun resultSummary(
            before: BattleStateView,
            after: BattleStateView,
            cycleAction: BattleActionCandidate,
            offenseAction: BattleActionCandidate,
            outcome: PublicTurnProjection,
        ): String {
            val parts = mutableListOf<String>()
            if (outcome.order.isNotEmpty()) {
                parts += "order=" + outcome.order.joinToString(">") { if (it == BattleSide.ALLY) "cycle" else "offense" }
            }
            listOf(BattleSide.ALLY to cycleAction, BattleSide.OPPONENT to offenseAction).flatMap { (side, action) ->
                val taken = if (action.kind == BattleActionKind.COMPOSITE) action.componentActions else listOf(action)
                taken.map { side to it }
            }.forEach { (side, action) ->
                if (action.kind == BattleActionKind.USE_MOVE) {
                    val actorId = before.pokemon.firstOrNull { it.side == side && it.activeSlot == action.actorSlot }?.battlePokemonId
                    if (actorId !in outcome.executedMoveIdsByPokemon) parts += "${sideLabel(side)}:${moveLabel(action.moveId)} 미실행"
                }
            }
            before.pokemon.forEach { old ->
                val next = after.pokemon.single { it.battlePokemonId == old.battlePokemonId }
                val name = "${sideLabel(old.side)}:${speciesLabel(old.speciesId)}"
                if (old.activeSlot != next.activeSlot && next.activeSlot != null) parts += "$name 등장"
                if (next.hpFraction < old.hpFraction - EPSILON) {
                    parts += "$name HP ${percent(old.hpFraction)}→${percent(next.hpFraction)}"
                } else if (next.hpFraction > old.hpFraction + EPSILON) {
                    parts += "$name 회복 ${percent(old.hpFraction)}→${percent(next.hpFraction)}"
                }
                if (old.statusId != next.statusId && next.statusId != null) parts += "$name ${canonical(next.statusId)}"
                if (old.statStages != next.statStages) parts += "$name 랭크 ${next.statStages}"
                if (old.knownHeldItemId != null && next.knownHeldItemId == null) {
                    parts += "$name ${canonical(old.knownHeldItemId)} 소모"
                }
                if (old.formId != next.formId) {
                    parts += "$name 폼 ${speciesLabel(old.formId ?: old.speciesId)}→${speciesLabel(next.formId ?: next.speciesId)}"
                }
                if (!old.fainted && next.fainted) parts += "$name 기절"
            }
            return parts.ifEmpty { listOf("상태 변화 없음") }.joinToString("; ")
        }

        private fun topLabel(selector: CapturingWeightedSelector): String = selector.lastRanked.take(TOP_LABELS).joinToString(" | ") {
            val excluded = selector.lastExclusions[it.outcome.candidate.actionId]?.let { reason -> "[$reason]" }.orEmpty()
            "${actionLabel(it.outcome.candidate)} %.1f(look %.1f)$excluded".format(it.comparisonValue, it.lookaheadUtility)
        }

        private fun actionLabel(action: BattleActionCandidate): String = when (action.kind) {
            BattleActionKind.COMPOSITE -> action.componentActions.joinToString("+") { actionLabel(it) }
            BattleActionKind.USE_MOVE -> moveLabel(action.moveId)
            BattleActionKind.SWITCH -> "교체→${speciesLabel(templates.getValue(requireNotNull(action.switchPokemonId)).speciesId)}"
            BattleActionKind.WAIT -> "대기"
            else -> action.kind.name.lowercase()
        }

        private fun living(side: BattleSide): Int = state.pokemon.count {
            it.side == side && !it.fainted && it.hpFraction > 0.0
        }

        private fun ended(): Boolean = living(BattleSide.ALLY) == 0 || living(BattleSide.OPPONENT) == 0

        private fun openContext(side: BattleSide) = BattleBrainOpenContext(
            battleId = battleId,
            format = definition.format,
            knowledgePolicy = BattleKnowledgePolicy.FAIR_INFERENCE,
            strategy = strategies.getValue(side),
            trainerProfile = profiles.getValue(side),
            trainerPersonaId = "scenario_${side.name.lowercase()}_${definition.seed}",
        )

        private fun strategy(id: String, objectives: Set<BattleStrategyObjective>) = BattleStrategyBrief(
            strategyId = "more_cobblemon_contents:scenario_$id",
            displayNameKey = "scenario.$id.name",
            descriptionKey = "scenario.$id.description",
            aiSummary = if (id == "cycle") {
                "Preserve the defensive core, spread status, recover efficiently, and pivot when the public matchup improves."
            } else {
                "Create immediate damage pressure, use credible setup windows, and preserve priority for cleanup."
            },
            objectives = objectives,
        )
    }

    private class ScenarioMemory {
        private var lastSwitchTurn: Int? = null
        private var switches = 0
        private var switchPressure = 0.0
        private var lastMoveId: String? = null
        private var sameMoveRepeats = 0
        private var nonProgress = 0

        fun view(turn: Int) = BattleTacticalMemoryView(
            turnsSinceLastSwitch = lastSwitchTurn?.let { (turn - it).coerceAtLeast(0) },
            switchesThisBattle = switches,
            switchPressure = switchPressure,
            lastMoveId = lastMoveId,
            sameMoveRepeatCount = sameMoveRepeats,
            nonProgressControlStreak = nonProgress,
        )

        fun accept(
            turn: Int,
            action: BattleActionCandidate,
            executed: Boolean,
            madeProgress: Boolean,
            forcedSwitch: Boolean = false,
        ) {
            if (!executed) return
            if (action.kind == BattleActionKind.SWITCH) {
                switches++
                if (!forcedSwitch) switchPressure = (switchPressure + 1.0).coerceAtMost(4.0)
                lastSwitchTurn = turn
            } else if (action.moveId != null) {
                switchPressure = (switchPressure - 1.0).coerceAtLeast(0.0)
            }
            val moveId = action.moveId
            if (moveId != null) {
                sameMoveRepeats = if (moveId == lastMoveId) sameMoveRepeats + 1 else 1
                lastMoveId = moveId
            }
            nonProgress = if (action.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS && !madeProgress) {
                nonProgress + 1
            } else {
                0
            }
        }
    }

    private class CapturingWeightedSelector : LocalActionSelector {
        private val delegate = LocalWeightedActionSelector()
        private var lastIdeal: LocalBattleActionRank? = null

        override fun choose(
            ranked: List<LocalBattleActionRank>,
            seed: Long,
            context: LocalActionMixingContext,
        ): LocalActionSelection {
            lastIdeal = ranked.first()
            lastRanked = ranked
            lastExclusions = context.ruleExclusions
            return delegate.choose(ranked, seed, context)
        }

        var lastRanked: List<LocalBattleActionRank> = emptyList()
            private set
        var lastExclusions: Map<String, String> = emptyMap()
            private set

        fun ideal(): BattleActionCandidate = requireNotNull(lastIdeal).outcome.candidate
    }

    private fun perspectiveField(field: BattleFieldStateView, viewer: BattleSide): BattleFieldStateView {
        if (viewer == BattleSide.ALLY) return field
        return BattleFieldStateView(
            weather = field.weather,
            terrain = field.terrain,
            roomEffects = field.roomEffects,
            globalEffects = field.globalEffects,
            sideConditions = mapOf(
                BattleSide.ALLY to field.sideConditions.getValue(BattleSide.OPPONENT),
                BattleSide.OPPONENT to field.sideConditions.getValue(BattleSide.ALLY),
            ),
        )
    }

    private fun perspectiveSide(side: BattleSide, viewer: BattleSide): BattleSide =
        if (viewer == BattleSide.ALLY) side else opposite(side)

    private fun opposite(side: BattleSide): BattleSide =
        if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY

    private fun sideLabel(side: BattleSide) = if (side == BattleSide.ALLY) "cycle" else "offense"
    private fun speciesLabel(speciesId: String) = speciesId.substringAfter(':')
    private fun moveLabel(moveId: String?) = moveId?.substringAfter(':') ?: "-"
    private fun canonical(value: String?) = value.orEmpty().substringAfter(':').lowercase().filter(Char::isLetterOrDigit)
    private fun percent(value: Double) = "${(value * 100.0).roundToInt()}%"

    private fun LocalTacticalSimulationStats.exactView() = BattleCombatStatRangesView.exact(
        maxHp,
        attack,
        defence,
        specialAttack,
        specialDefence,
        speed,
    )

    private fun LocalTacticalSimulationStats.mechanicsView() = BattleCombatStatRangesView(
        maxHp = BattleIntegerRange(maxHp, maxHp),
        attack = BattleIntegerRange(attack, attack),
        defence = BattleIntegerRange(defence, defence),
        specialAttack = BattleIntegerRange(specialAttack, specialAttack),
        specialDefence = BattleIntegerRange(specialDefence, specialDefence),
        speed = BattleIntegerRange(speed, speed),
        knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
    )

    private fun LocalTacticalSimulationStats.publicView() = BattleCombatStatRangesView(
        maxHp = publicHealthRange(maxHp),
        attack = publicRange(attack),
        defence = publicRange(defence),
        specialAttack = publicRange(specialAttack),
        specialDefence = publicRange(specialDefence),
        speed = publicRange(speed),
        knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
    )

    /**
     * The width production actually gives, not a comfortable stand-in for it.
     *
     * `Cobblemon173PublicStatHypothesis` refuses the opponent's IVs, EVs and nature, so a public
     * non-HP stat spans a zero-IV zero-EV hindering spread up to a maxed helping one. At level 50 that
     * is about 0.72x to 1.30x of a typical value - roughly twice the +-15% this harness used to
     * assume, which quietly measured every knob against an opponent whose stats were known twice as
     * precisely as the real game allows.
     *
     * The harness only knows final stats, so the production formula is applied as the ratio it
     * produces rather than re-derived from a base stat it does not have.
     */
    private fun publicRange(value: Int) = BattleIntegerRange(
        (value * 0.72).roundToInt().coerceAtLeast(1),
        (value * 1.30).roundToInt().coerceAtLeast(1),
    )

    /** Health takes no nature modifier, so its public range is far tighter than the others. */
    private fun publicHealthRange(value: Int) = BattleIntegerRange(
        (value * 0.87).roundToInt().coerceAtLeast(1),
        (value * 1.13).roundToInt().coerceAtLeast(1),
    )

    private const val LEVEL = 50
    private const val TOP_LABELS = 4
    private const val UNKNOWN_SPECIES = "cobblemon:unknown"
    private const val EPSILON = 1e-9
}
