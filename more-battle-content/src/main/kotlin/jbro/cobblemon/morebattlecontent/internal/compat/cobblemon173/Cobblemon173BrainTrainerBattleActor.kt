package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.ai.BattleAI
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.battles.ShowdownActionRequest
import com.cobblemon.mod.common.battles.ShowdownActionResponse
import com.cobblemon.mod.common.battles.actor.TrainerBattleActor
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrain
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainCloseResult
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainDefaults
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSession
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecision
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionValidationStatus
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactOwnTeamView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionValidator
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnowledgePolicy
import jbro.cobblemon.morebattlecontent.api.ai.BattleLocalOpponentStatSpreadView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.internal.ai.BattleBrainDecisionCoordinator
import jbro.cobblemon.morebattlecontent.internal.ai.BattleBrainEndpoint
import jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionFallbackChain
import jbro.cobblemon.morebattlecontent.internal.ai.BattleBrainExecutors
import jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionDiagnostics
import jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionResolution
import jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionSource
import jbro.cobblemon.morebattlecontent.internal.ai.BattleTacticalMemoryLedger
import jbro.cobblemon.morebattlecontent.internal.ai.BattleTacticalRunMemoryStore
import jbro.cobblemon.morebattlecontent.internal.ai.BattleOpponentMoveInferenceLedger
import jbro.cobblemon.morebattlecontent.internal.ai.attemptBattleDecisionCompletion
import jbro.cobblemon.morebattlecontent.internal.ai.attemptBattleDecisionSetup
import jbro.cobblemon.morebattlecontent.internal.ai.prepareBattleDecisionFallback
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3

/** Owns one combined asynchronous Brain decision for each Cobblemon 1.7.3 trainer request. */
internal class Cobblemon173BrainTrainerBattleActor(
    private val server: MinecraftServer,
    private val trainerEntity: ArmorStand,
    trainerName: String,
    actorId: UUID,
    pokemonList: List<BattlePokemon>,
    private val battleFormat: BattleFormat,
    private val opponentActorId: UUID,
    initialOpponentPokemonCount: Int,
    private val baselineAi: BattleAI,
    private val mechanicPolicy: () -> Cobblemon173MechanicPolicy,
    private val strategyBrief: BattleStrategyBrief? = null,
    private val trainerProfile: BattleTrainerProfile = BattleTrainerProfile.balanced(),
    private val learningScopeId: UUID? = null,
    private val trainerPersonaId: String? = null,
    private val primaryBrain: BattleBrain? = null,
    localBrain: BattleBrain? = null,
    private val knowledgePolicy: BattleKnowledgePolicy = BattleKnowledgePolicy.FAIR_INFERENCE,
    private val opponentTeamPreview: BattleOpponentTeamPreviewView? = null,
    private val exactOwnTeam: BattleExactOwnTeamView = Cobblemon173ExactOwnTeamView.from(pokemonList),
    private val unboundedDecisionTime: Boolean = false,
) : TrainerBattleActor(trainerName, actorId, pokemonList, baselineAi), EntityBackedBattleActor<ArmorStand> {
    override val entity: ArmorStand = trainerEntity
    override val initialPos: Vec3 = trainerEntity.position()

    private val localBrain = localBrain?.takeUnless { it === primaryBrain }
    private val observationAdapter = Cobblemon173ShowdownObservationAdapter(
        opponentActorId = opponentActorId,
        initialOpponentPokemonCount = initialOpponentPokemonCount,
    )
    private val pendingRequest = AtomicReference<ShowdownActionRequest?>()
    private val pendingDecision = AtomicReference<java.util.concurrent.CompletableFuture<BattleDecisionResolution>?>()
    private val closeResult = AtomicReference<BattleBrainCloseResult?>()
    private val primarySession = AtomicReference<BattleBrainSession?>()
    private val localSession = AtomicReference<BattleBrainSession?>()
    private val opponentMoveInference = BattleOpponentMoveInferenceLedger(
        Cobblemon173ActionCandidateAdapter::publicMoveDetails,
    )
    private val tacticalMemory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BattleTacticalMemoryLedger(openContext())
    }

    override fun onChoiceRequested() {
        val currentRequest = request ?: return
        if (!pendingRequest.compareAndSet(null, currentRequest)) return

        val preparation = try {
            Cobblemon173ActionCandidateAdapter.prepare(this, mechanicPolicy())
        } catch (exception: Exception) {
            logFailure("action preparation", exception)
            submitBaselineOrEmergency(currentRequest, null)
            return
        } catch (error: LinkageError) {
            logFailure("action preparation", error)
            submitBaselineOrEmergency(currentRequest, null)
            return
        }
        if (preparation.status == Cobblemon173ActionPreparationStatus.WAITING) {
            submitPreparedResponses(currentRequest, preparation.responsesFor("wait").orEmpty())
            return
        }
        if (preparation.status != Cobblemon173ActionPreparationStatus.READY || preparation.candidates.isEmpty()) {
            MoreBattleContent.LOGGER.error(
                "Unable to prepare battle {} Brain candidates: {}",
                battle.battleId,
                preparation.status,
            )
            submitBaselineOrEmergency(currentRequest, preparation)
            return
        }

        val preparedDecision = attemptBattleDecisionSetup(
            setup = {
                val ownCurrentPp = currentRequest.active.orEmpty().mapIndexedNotNull { slot, moveset ->
                    activePokemon.getOrNull(slot)?.battlePokemon?.uuid?.let { id ->
                        id to moveset.moves.associate { it.id to it.pp }
                    }
                }.toMap()
                observationAdapter.attach(battle)
                val state = observationAdapter.snapshot(this, ownCurrentPp)
                if (state.format != battleFormat || preparation.format != battleFormat) {
                    MoreBattleContent.LOGGER.error(
                        "Battle {} format changed while preparing a Brain turn",
                        battle.battleId,
                    )
                    submitBaselineOrEmergency(currentRequest, preparation)
                    return
                }
                tacticalMemory.observe(state)

                val publicCatalog = Cobblemon173PublicActionCatalog.from(
                    state,
                    observationAdapter.publicPpSpent(),
                    ownCurrentPp,
                    transformedPokemon = observationAdapter.transformedPokemon(),
                    originalMoveIds = observationAdapter.originalMoveIds(this),
                    originalPpSpent = observationAdapter.originalPpSpent(),
                )
                val context = BattleDecisionContext(
                    requestId = UUID.randomUUID(),
                    state = state,
                    candidates = preparation.candidates,
                    deadlineEpochMillis = Cobblemon173BrainDecisionTiming.deadline(
                        System.currentTimeMillis(),
                        unboundedDecisionTime,
                    ),
                    memory = tacticalMemory.view(state.turn),
                    publicActionCatalog = publicCatalog,
                ).copy(
                    opponentTeamPreview = opponentTeamPreview,
                    exactOwnTeam = exactOwnTeam,
                )
                // The primary Brain retains the public-only context. Only the local fallback receives
                // the already-normalized tier budget; the full live moveset never enters either context.
                val localContext = if (localBrain == null) {
                    context
                } else {
                    context.copy(
                        publicActionCatalog = publicCatalog.withOpponentMoveInferences(
                            opponentMoveInference.update(
                                state,
                                publicCatalog,
                                trainerProfile.difficulty.tier,
                                actualOpponentMoveIds(),
                                observationAdapter.transformedPokemon(),
                            ),
                        ),
                        localOpponentStatSpreads = if (trainerProfile.difficulty.tier in setOf(
                                BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS,
                            )) {
                            actualOpponentStatSpreadsByPreviewSlot()
                        } else emptyMap(),
                    )
                }
                val decision = fallbackChain.decide(
                    endpoint(primaryBrain, primarySession),
                    endpoint(localBrain, localSession),
                    context,
                    localContext,
                    enforceTimeout = !unboundedDecisionTime,
                ).toCompletableFuture()
                pendingDecision.set(decision)
                if (closeResult.get() != null && pendingDecision.compareAndSet(decision, null)) {
                    decision.cancel(true)
                }
                PreparedBrainDecision(
                    context = context,
                    startedAtNanos = System.nanoTime(),
                    pending = decision,
                )
            },
            recover = { failure ->
                logFailure("Brain decision preparation", failure)
                submitBaselineOrEmergency(currentRequest, preparation)
            },
        ) ?: return
        preparedDecision.pending.whenComplete { resolution, throwable ->
            pendingDecision.compareAndSet(preparedDecision.pending, null)
            server.execute {
                completeOnServerThread(
                    expectedRequest = currentRequest,
                    preparation = preparation,
                    context = preparedDecision.context,
                    resolution = resolution,
                    throwable = throwable,
                    decisionStartedAtNanos = preparedDecision.startedAtNanos,
                )
            }
        }
    }

    fun closeBrains(result: BattleBrainCloseResult) {
        if (!closeResult.compareAndSet(null, result)) return
        runManagedCleanupActions(
            { pendingDecision.getAndSet(null)?.cancel(true) },
            { BattleTacticalRunMemoryStore.record(learningScopeId, tacticalMemory.view(result.turns).tendencies) },
            { closeSessionReference(primaryBrain, primarySession, result) },
            { closeSessionReference(localBrain, localSession, result) },
        )
    }

    private fun completeOnServerThread(
        expectedRequest: ShowdownActionRequest,
        preparation: Cobblemon173ActionPreparation,
        context: BattleDecisionContext,
        resolution: BattleDecisionResolution?,
        throwable: Throwable?,
        decisionStartedAtNanos: Long,
    ) {
        if (battle.ended || request !== expectedRequest) {
            pendingRequest.compareAndSet(expectedRequest, null)
            if (!battle.ended && request != null) onChoiceRequested()
            return
        }

        val selectedResponses = if (throwable == null && resolution?.decision != null) {
            val status = BattleDecisionValidator.validate(context, resolution.decision, System.currentTimeMillis())
            if (status == BattleDecisionValidationStatus.VALID) {
                preparation.responsesFor(resolution.decision.actionId)
            } else {
                MoreBattleContent.LOGGER.warn(
                    "Discarding stale Brain response for battle {} at submission: {}",
                    battle.battleId,
                    status,
                )
                null
            }
        } else {
            null
        }

        if (selectedResponses != null) {
            var selectedResolution: BattleDecisionResolution? = null
            var selectedDecision: BattleDecision? = null
            var selectedCandidate: BattleActionCandidate? = null
            val prepared = attemptBattleDecisionCompletion(
                complete = {
                    selectedResolution = requireNotNull(resolution)
                    selectedDecision = requireNotNull(selectedResolution?.decision)
                    selectedCandidate = context.candidates.single { it.actionId == selectedDecision?.actionId }
                    logDecisionResolution(
                        context = context,
                        source = requireNotNull(selectedResolution).source,
                        failures = requireNotNull(selectedResolution).failures,
                        actionKinds = requireNotNull(selectedCandidate).diagnosticActionKinds(),
                        diagnosticTags = requireNotNull(selectedDecision).tags,
                        decisionStartedAtNanos = decisionStartedAtNanos,
                    )
                },
                recover = { failure ->
                    logFailure("Brain decision finalization", failure)
                    submitBaselineOrEmergency(expectedRequest, preparation, context, resolution)
                },
            )
            if (!prepared) return
            val finalizedResolution = requireNotNull(selectedResolution)
            val finalizedDecision = requireNotNull(selectedDecision)
            val finalizedCandidate = requireNotNull(selectedCandidate)
            val submitted = submitPreparedResponses(expectedRequest, selectedResponses)
            if (submitted) {
                val planOwner = when (finalizedResolution.source) {
                    BattleDecisionSource.PRIMARY_BRAIN -> jbro.cobblemon.morebattlecontent.api.ai.BattlePlanOwner.PRIMARY_BRAIN
                    BattleDecisionSource.LOCAL_BRAIN -> jbro.cobblemon.morebattlecontent.api.ai.BattlePlanOwner.LOCAL_BRAIN
                    else -> null
                }
                attemptBattleDecisionCompletion(
                    complete = {
                        tacticalMemory.accept(
                            context.state,
                            finalizedCandidate,
                            finalizedDecision.advice,
                            planOwner,
                        )
                    },
                    recover = { failure -> logFailure("Brain decision bookkeeping", failure) },
                )
            }
        } else {
            if (throwable != null) logFailure("Brain decision completion", throwable)
            if (throwable == null && resolution != null) {
                logDecisionResolution(
                    context = context,
                    source = BattleDecisionSource.BASELINE_REQUIRED,
                    failures = resolution.failures,
                    decisionStartedAtNanos = decisionStartedAtNanos,
                )
            }
            submitBaselineOrEmergency(expectedRequest, preparation, context, resolution)
        }
    }

    private fun submitBaselineOrEmergency(
        expectedRequest: ShowdownActionRequest,
        preparation: Cobblemon173ActionPreparation?,
        context: BattleDecisionContext? = null,
        resolution: BattleDecisionResolution? = null,
    ) {
        if (request !== expectedRequest || battle.ended) {
            pendingRequest.compareAndSet(expectedRequest, null)
            if (!battle.ended && request != null) onChoiceRequested()
            return
        }
        val baseline = Cobblemon173BaselineTurnAdapter.choose(this, baselineAi)
        if (
            baseline.status == Cobblemon173BaselineTurnStatus.READY ||
            baseline.status == Cobblemon173BaselineTurnStatus.NO_ACTION_REQUIRED
        ) {
            submitPreparedResponses(expectedRequest, baseline.responses)
            return
        }

        MoreBattleContent.LOGGER.error(
            "Cobblemon baseline failed for battle {}: {}; using emergency action",
            battle.battleId,
            baseline.status,
        )
        val emergencyResponses = prepareBattleDecisionFallback(
            preferred = {
                when {
                    preparation != null && context != null && preparation.candidates.isNotEmpty() -> {
                        val emergency = BattleDecisionResolution.emergency(context, resolution?.failures.orEmpty())
                        preparation.responsesFor(requireNotNull(emergency.decision).actionId)
                    }
                    preparation != null && preparation.candidates.isNotEmpty() ->
                        preparation.responsesFor(preparation.candidates.first().actionId)
                    else -> null
                }
            },
            lastResort = { emergencyPasses(expectedRequest) },
            report = { failure -> logFailure("emergency response preparation", failure) },
        )
        if (emergencyResponses == null) {
            pendingRequest.compareAndSet(expectedRequest, null)
            compatibilityCallOrNull { pokemonList }.orEmpty().forEach { pokemon ->
                compatibilityCallOrNull { pokemon.willBeSwitchedIn = false }
            }
            compatibilityCallOrElse(
                fallback = { failure -> logFailure("unrecoverable Brain turn termination", failure) },
                action = { Cobblemon173ManagedBattleTermination.end(battle.battleId) },
            )
            return
        }
        submitPreparedResponses(expectedRequest, emergencyResponses)
    }

    private fun submitPreparedResponses(
        expectedRequest: ShowdownActionRequest,
        responses: List<ShowdownActionResponse>,
    ): Boolean {
        if (request !== expectedRequest || battle.ended) {
            pendingRequest.compareAndSet(expectedRequest, null)
            if (!battle.ended && request != null) onChoiceRequested()
            return false
        }
        return try {
            setActionResponses(responses)
            true
        } catch (exception: Exception) {
            recoverFailedSubmission(expectedRequest, exception)
            false
        } catch (error: LinkageError) {
            recoverFailedSubmission(expectedRequest, error)
            false
        } finally {
            pendingRequest.compareAndSet(expectedRequest, null)
            compatibilityCallOrNull { pokemonList }.orEmpty().forEach { pokemon ->
                compatibilityCallOrNull { pokemon.willBeSwitchedIn = false }
            }
        }
    }

    private fun recoverFailedSubmission(expectedRequest: ShowdownActionRequest, failure: Throwable) {
        logFailure("action submission", failure)
        compatibilityCallOrElse(
            fallback = { passFailure ->
                logFailure("emergency pass submission", passFailure)
            },
            action = {
                setActionResponses(emergencyPasses(expectedRequest))
            },
        )
    }

    private fun emergencyPasses(request: ShowdownActionRequest): List<ShowdownActionResponse> {
        return Cobblemon173ActionCandidateAdapter.passResponses(request, activePokemon)
    }

    private fun endpoint(
        brain: BattleBrain?,
        reference: AtomicReference<BattleBrainSession?>,
    ): BattleBrainEndpoint? {
        brain ?: return null
        return BattleBrainEndpoint(brain) { session(brain, reference) }
    }

    private fun session(
        brain: BattleBrain,
        reference: AtomicReference<BattleBrainSession?>,
    ): BattleBrainSession = reference.get() ?: synchronized(reference) {
        reference.get() ?: run {
            check(closeResult.get() == null) { "Battle Brain sessions are already closed" }
            val opened = brain.openSession(
                openContext(),
            )
            val closed = closeResult.get()
            if (closed == null) {
                opened.also(reference::set)
            } else {
                closeSafely(brain, opened, closed)
                error("Battle ended while a Brain session was opening")
            }
        }
    }

    private fun openContext() = BattleBrainOpenContext(
        battleId = battle.battleId,
        format = battleFormat,
        knowledgePolicy = knowledgePolicy,
        strategy = strategyBrief,
        trainerProfile = trainerProfile,
        learningScopeId = learningScopeId,
        trainerPersonaId = trainerPersonaId,
    )

    private fun closeSessionReference(
        brain: BattleBrain?,
        reference: AtomicReference<BattleBrainSession?>,
        result: BattleBrainCloseResult,
    ) {
        synchronized(reference) {
            reference.getAndSet(null)?.let { closeSafely(brain, it, result) }
        }
    }

    private fun closeSafely(brain: BattleBrain?, session: BattleBrainSession, result: BattleBrainCloseResult) {
        brain ?: return
        try {
            brain.closeSession(session, result)
        } catch (exception: Exception) {
            logFailure("Brain session close", exception)
        } catch (error: LinkageError) {
            logFailure("Brain session close", error)
        }
    }

    /** Read once at the normalization boundary; callers receive only the tier-approved slots. */
    private fun actualOpponentMoveIds(): Map<UUID, Set<String>> =
        compatibilityCallOrNull { battle.getActor(opponentActorId) }?.pokemonList.orEmpty().associate { pokemon ->
            pokemon.uuid to pokemon.moveSet.getMoves().mapTo(linkedSetOf()) { move -> move.name }
        }

    /** Match only unique public species/form identities; ambiguity must never assign a private spread to the wrong slot. */
    private fun actualOpponentStatSpreadsByPreviewSlot(): Map<Int, BattleLocalOpponentStatSpreadView> {
        val preview = opponentTeamPreview ?: return emptyMap()
        val actual = compatibilityCallOrNull { battle.getActor(opponentActorId) }?.pokemonList.orEmpty()
        val exactById = actual.takeIf { it.isNotEmpty() }?.let(Cobblemon173ExactOwnTeamView::from)
            ?.builds?.associateBy { it.battlePokemonId }.orEmpty()
        return preview.pokemon.mapNotNull { slot ->
            val candidates = actual.filter { pokemon ->
                pokemon.effectedPokemon.species.resourceIdentifier.toString().equals(slot.speciesId, true) &&
                    (slot.formId == null || pokemon.effectedPokemon.form.name.equals(slot.formId, true))
            }
            val duplicateSlots = preview.pokemon.count { other ->
                other.speciesId.equals(slot.speciesId, true) && other.formId.equals(slot.formId, true)
            }
            if (candidates.size != 1 || duplicateSlots != 1) null else {
                exactById[candidates.single().uuid]?.let { build ->
                    slot.previewSlotId to BattleLocalOpponentStatSpreadView(build.ivs, build.evs)
                }
            }
        }.toMap()
    }

    private fun logFailure(operation: String, throwable: Throwable) {
        compatibilityCallOrNull {
            MoreBattleContent.LOGGER.error(
                "Battle {} {} failed: {}",
                compatibilityCallOrNull { battle.battleId },
                operation,
                throwable.javaClass.name,
            )
        }
    }

    private fun logDecisionResolution(
        context: BattleDecisionContext,
        source: BattleDecisionSource,
        failures: List<jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionFailure>,
        actionKinds: List<BattleActionKind> = emptyList(),
        diagnosticTags: Set<String> = emptySet(),
        decisionStartedAtNanos: Long,
    ) {
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
            (System.nanoTime() - decisionStartedAtNanos).coerceAtLeast(0L),
        )
        MoreBattleContent.LOGGER.info(
            "Battle {} turn {} Brain decision resolved: {}",
            battle.battleId,
            context.state.turn,
            BattleDecisionDiagnostics.summary(
                source = source,
                candidateCount = context.candidates.size,
                elapsedMillis = elapsedMillis,
                actionKinds = actionKinds,
                diagnosticTags = diagnosticTags,
                failures = failures,
            ),
        )
    }

    private data class PreparedBrainDecision(
        val context: BattleDecisionContext,
        val startedAtNanos: Long,
        val pending: java.util.concurrent.CompletableFuture<BattleDecisionResolution>,
    )

    private fun BattleActionCandidate.diagnosticActionKinds(): List<BattleActionKind> =
        if (kind == BattleActionKind.COMPOSITE) componentActions.map { it.kind } else listOf(kind)

    private companion object {
        val coordinator = BattleBrainDecisionCoordinator(
            scheduler = BattleBrainExecutors.deadlineScheduler(),
            brainExecutor = BattleBrainExecutors.worker(),
        )
        val fallbackChain = BattleDecisionFallbackChain(coordinator)
    }
}

internal object Cobblemon173BrainDecisionTiming {
    fun deadline(now: Long, unbounded: Boolean): Long = when {
        unbounded -> Long.MAX_VALUE
        now > Long.MAX_VALUE - BattleBrainDefaults.DECISION_TIMEOUT_MILLIS -> Long.MAX_VALUE
        else -> now + BattleBrainDefaults.DECISION_TIMEOUT_MILLIS
    }
}
