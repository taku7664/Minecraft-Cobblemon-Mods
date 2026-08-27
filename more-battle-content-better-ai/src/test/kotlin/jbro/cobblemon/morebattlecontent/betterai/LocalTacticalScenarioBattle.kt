package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.outcome.ChanceEffectProjectionMode
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelection
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.morebattlecontent.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveHistoryProjector
import kotlin.math.roundToInt
import kotlin.random.Random

internal data class LocalTacticalScenarioDefinition(
    val name: String,
    val cycleSetIds: List<String>,
    val offenseSetIds: List<String>,
    val seed: Int,
)

internal data class LocalTacticalScenarioTurn(
    val turn: Int,
    val cycleIdeal: String,
    val offenseIdeal: String,
    val cycleActual: String,
    val offenseActual: String,
    val result: String,
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
    ): LocalTacticalScenarioReport = Battle(
        definition, cycleTuning, offenseTuning, cycleDifficulty, offenseDifficulty, recordedContexts,
    ).run(maximumTurns)

    private class Battle(
        private val definition: LocalTacticalScenarioDefinition,
        cycleTuning: LocalDecisionTuning,
        offenseTuning: LocalDecisionTuning,
        cycleDifficulty: BattleDifficultyProfile,
        offenseDifficulty: BattleDifficultyProfile,
        private val recordedContexts: MutableList<BattleDecisionContext>?,
    ) {
        private val difficulties = mapOf(
            BattleSide.ALLY to cycleDifficulty,
            BattleSide.OPPONENT to offenseDifficulty,
        )
        private val tunings = mapOf(
            BattleSide.ALLY to cycleTuning,
            BattleSide.OPPONENT to offenseTuning,
        )
        private val random = Random(definition.seed)
        private val roster = LocalTacticalSimulationRoster.loadAll()
        private val battleId = UUID(random.nextLong(), random.nextLong())
        private val templates = linkedMapOf<UUID, LocalTacticalSimulationEntry>()
        private val cycleIds = createTeam(definition.cycleSetIds)
        private val offenseIds = createTeam(definition.offenseSetIds)
        private val revealedPokemonIds = linkedSetOf(cycleIds.first(), offenseIds.first())
        private val revealedMoveIds = mutableMapOf<UUID, MutableSet<String>>()
        private val selectors = BattleSide.entries.associateWith { CapturingWeightedSelector() }
        private val actualBrains = BattleSide.entries.associateWith { side ->
            LocalTacticalBrain(selectors.getValue(side), tunings.getValue(side))
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
        private var state = initialState()
        private var cycleStatusMoves = 0
        private var offenseStatusMoves = 0
        private var cycleVoluntarySwitches = 0
        private var offenseVoluntarySwitches = 0

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
                val offenseActual = choose(BattleSide.OPPONENT, offenseCandidates)
                val offenseIdeal = selectors.getValue(BattleSide.OPPONENT).ideal()
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
            )
        }

        private fun createTeam(setIds: List<String>): List<UUID> {
            require(setIds.size == 3)
            val selected = setIds.map { id -> roster.entries.single { it.setId == id } }
            require(selected.map { it.speciesId }.distinct().size == selected.size)
            require(selected.map { it.heldItemId }.distinct().size == selected.size)
            return selected.mapIndexed { index, template ->
                UUID(definition.seed.toLong(), (templates.size + index + 1).toLong()).also { templates[it] = template }
            }
        }

        private fun initialState(): BattleStateView {
            var initial = BattleStateView(
                battleId = battleId,
                format = BattleFormat.SINGLE,
                turn = 1,
                pokemon = cycleIds.mapIndexed { index, id -> initialPokemon(id, BattleSide.ALLY, index == 0) } +
                    offenseIds.mapIndexed { index, id -> initialPokemon(id, BattleSide.OPPONENT, index == 0) },
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 3),
                observedEvents = emptyList(),
                inferences = emptyList(),
            )
            initial.pokemon.filter { it.activeSlot != null }.map { it.battlePokemonId }.forEach { activeId ->
                initial = LocalEntryAbilityProjector.project(initial, activeId)
            }
            return initial
        }

        private fun initialPokemon(id: UUID, side: BattleSide, active: Boolean): BattlePokemonStateView {
            val template = templates.getValue(id)
            return BattlePokemonStateView(
                battlePokemonId = id,
                side = side,
                activeSlot = if (active) 0 else null,
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
            val actions = PublicFutureActionFactory.actions(view, BattleSide.ALLY, catalog, perspectiveHistory(side))
            require(actions.isNotEmpty()) { "No legal actions for $side on turn ${state.turn}" }
            return actions
        }

        private fun choose(
            side: BattleSide,
            candidates: List<BattleActionCandidate>,
        ): BattleActionCandidate {
            val brain = actualBrains.getValue(side)
            val session = actualSessions.getValue(side)
            val context = BattleDecisionContext(
                requestId = UUID(random.nextLong(), random.nextLong()),
                state = perspective(side),
                candidates = candidates,
                deadlineEpochMillis = Long.MAX_VALUE,
                memory = memories.getValue(side).view(state.turn),
                publicActionCatalog = decisionCatalog(side),
            )
            recordedContexts?.add(context)
            val decision = brain.decide(session, context).toCompletableFuture().join()
            return candidates.single { it.actionId == decision.actionId }
        }

        private fun forceReplacement(side: BattleSide) {
            val hasActive = state.pokemon.any {
                it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }
            if (hasActive || living(side) == 0) return
            val view = perspective(side)
            val candidates = view.pokemon.filter {
                it.side == BattleSide.ALLY && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0
            }.map { target ->
                BattleActionCandidate(
                    actionId = "forced:${target.battlePokemonId}",
                    kind = BattleActionKind.SWITCH,
                    actorSlot = 0,
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
                knownAbilityId = template.abilityId.takeIf { own },
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

        private fun decisionCatalog(viewer: BattleSide): BattlePublicActionCatalogView =
            BattlePublicActionCatalogView(
                templates.map { (id, template) ->
                    val own = state.pokemon.single { it.battlePokemonId == id }.side == viewer
                    val revealed = revealedMoveIds[id].orEmpty()
                    val moves = template.moves.filter { own || it.id in revealed }.map { move ->
                        BattlePublicMoveOptionView(
                            moveId = move.id,
                            details = LocalTacticalSimulationMoveLibrary.details(move),
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

        private fun toCanonical(action: BattleActionCandidate, side: BattleSide): BattleActionCandidate {
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
            listOf(BattleSide.ALLY to cycleAction, BattleSide.OPPONENT to offenseAction).forEach { (side, action) ->
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

        private fun actionLabel(action: BattleActionCandidate): String = when (action.kind) {
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
            format = BattleFormat.SINGLE,
            knowledgePolicy = BattleKnowledgePolicy.FAIR_INFERENCE,
            strategy = strategies.getValue(side),
            trainerProfile = profiles.getValue(side),
            trainerPersonaId = "scenario_${side.name.lowercase()}_${definition.seed}",
        )

        private fun strategy(id: String, objectives: Set<BattleStrategyObjective>) = BattleStrategyBrief(
            strategyId = "cobblemon_more_battle_content:scenario_$id",
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
            return delegate.choose(ranked, seed, context)
        }

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
    private const val UNKNOWN_SPECIES = "cobblemon:unknown"
    private const val EPSILON = 1e-9
}
