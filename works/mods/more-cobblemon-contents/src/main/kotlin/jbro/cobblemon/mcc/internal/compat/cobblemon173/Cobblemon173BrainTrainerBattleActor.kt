package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.ai.BattleAI
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.entity.PosableEntity
import com.cobblemon.mod.common.battles.ShowdownActionRequest
import com.cobblemon.mod.common.battles.ShowdownActionResponse
import com.cobblemon.mod.common.battles.actor.TrainerBattleActor
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleBrain
import jbro.cobblemon.mcc.internal.ai.BattleBrainCloseResult
import jbro.cobblemon.mcc.internal.ai.BattleBrainDefaults
import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleBrainSession
import jbro.cobblemon.mcc.internal.ai.BattleDecision
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleDecisionValidationStatus
import jbro.cobblemon.mcc.internal.ai.BattleExactOwnTeamView
import jbro.cobblemon.mcc.internal.ai.BattleDecisionValidator
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleKnowledgePolicy
import jbro.cobblemon.mcc.internal.ai.BattleLocalOpponentStatSpreadView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleStrategyBrief
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.BattleBrainDecisionCoordinator
import jbro.cobblemon.mcc.internal.ai.BattleBrainEndpoint
import jbro.cobblemon.mcc.internal.ai.BattleDecisionFallbackChain
import jbro.cobblemon.mcc.internal.ai.BattleBrainExecutors
import jbro.cobblemon.mcc.internal.ai.BattleDecisionDiagnostics
import jbro.cobblemon.mcc.internal.ai.BattleDecisionResolution
import jbro.cobblemon.mcc.internal.ai.BattleDecisionSource
import jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryLedger
import jbro.cobblemon.mcc.internal.ai.BattleTacticalRunMemoryStore
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveInferenceLedger
import jbro.cobblemon.mcc.internal.ai.attemptBattleDecisionCompletion
import jbro.cobblemon.mcc.internal.ai.attemptBattleDecisionSetup
import jbro.cobblemon.mcc.internal.ai.prepareBattleDecisionFallback
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/** Owns one combined asynchronous Brain decision for each Cobblemon 1.7.3 trainer request. */
internal class Cobblemon173BrainTrainerBattleActor(
    private val server: MinecraftServer,
    private val trainerEntity: LivingEntity,
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
    /** Runs as each choice is asked for, before the brain answers it (a trainer's lines at its last Pokemon). */
    private val onChoiceRequestedHook: () -> Unit = {},
    /**
     * The trainer is a Cobblemon NPC the battle started on its own (a wild trainer), not a body MCC spawned and clears:
     * at the end its Pokemon are called back and the battle leaves its list, as Cobblemon's own NPC actor does.
     */
    private val cobblemonNpc: Boolean = false,
) : TrainerBattleActor(trainerName, actorId, pokemonList, baselineAi), EntityBackedBattleActor<LivingEntity> {
    override val entity: LivingEntity = trainerEntity
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
        isMaxMove = jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax::isMaxMove,
        moveDetails = Cobblemon173ActionCandidateAdapter::publicMoveDetails,
    )
    private val tacticalMemory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BattleTacticalMemoryLedger(openContext())
    }

    // A visible trainer (Cobblemon's NPC) takes the result as Cobblemon's own NPC trainers do.
    override fun win(winners: List<BattleActor>, losers: List<BattleActor>) {
        super.win(winners, losers)
        (trainerEntity as? PosableEntity)?.playAnimation("win", emptyList())
    }

    override fun lose(winners: List<BattleActor>, losers: List<BattleActor>) {
        super.lose(winners, losers)
        (trainerEntity as? PosableEntity)?.playAnimation("lose", emptyList())
    }

    override fun sendUpdate(packet: com.cobblemon.mod.common.api.net.NetworkPacket<*>) {
        super.sendUpdate(packet)
        reaskAfterRejection(packet)
        if (!cobblemonNpc || packet !is com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket) return
        val npc = trainerEntity as? com.cobblemon.mod.common.entity.npc.NPCEntity ?: return
        val battleId = battle.battleId
        if (npc.isAlive) pokemonList.mapNotNull { it.entity }.forEach { it.recallWithAnimation() }
        npc.entityData.set(com.cobblemon.mod.common.entity.npc.NPCEntity.BATTLE_IDS, npc.battleIds - battleId)
    }

    /** Rejected choices since the last turn's own request; past [MAX_REASKS] the baseline answers instead. */
    @Volatile private var rejections = 0
    @Volatile private var reaskOnNextRequest = false

    /**
     * Showdown turns a choice down (a switch out of a trap it only hinted at as `maybeTrapped`, which Cobblemon drops
     * when it reads the request) with `|error|` and a fresh `|request|`. Cobblemon sets `mustChoose` and sends
     * [com.cobblemon.mod.common.net.messages.client.battle.BattleMadeInvalidChoicePacket], which only a player's client
     * acts on: an AI actor is never asked again and the battle waits forever (Cobblemon 1.8.1, read from its bytecode
     * 2026-10-13). The new request is stored by the next dispatched instruction, after its own
     * [com.cobblemon.mod.common.net.messages.client.battle.BattleQueueRequestPacket], so the question goes behind it.
     */
    private fun reaskAfterRejection(packet: com.cobblemon.mod.common.api.net.NetworkPacket<*>) {
        when (packet) {
            is com.cobblemon.mod.common.net.messages.client.battle.BattleMakeChoicePacket -> rejections = 0
            is com.cobblemon.mod.common.net.messages.client.battle.BattleMadeInvalidChoicePacket -> {
                rejections++
                reaskOnNextRequest = true
                MoreCobblemonContents.LOGGER.warn("Battle {}: Showdown rejected the AI's choice ({} in a row); asking again",
                    battle.battleId, rejections)
            }
            is com.cobblemon.mod.common.net.messages.client.battle.BattleQueueRequestPacket -> {
                if (!reaskOnNextRequest) return
                reaskOnNextRequest = false
                battle.dispatchGo {
                    val current = request
                    if (battle.ended || current == null || !mustChoose || responses.isNotEmpty() || pendingRequest.get() != null) return@dispatchGo
                    if (rejections <= MAX_REASKS) onChoiceRequested()
                    else if (pendingRequest.compareAndSet(null, current)) submitBaselineOrEmergency(current, null)
                }
            }
        }
    }

    override fun onChoiceRequested() {
        val decisionStartedAtNanos = System.nanoTime()
        onChoiceRequestedHook()
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
            MoreCobblemonContents.LOGGER.error(
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
                    MoreCobblemonContents.LOGGER.error(
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
                ).withDynamaxInBattle(Cobblemon173BattleRuleHooks.dynamaxAllowed(battle.battleId))
                // The primary Brain retains the public-only context. Only the local fallback receives
                // the already-normalized tier budget; the full live moveset never enters either context.
                var localEndpoint = endpoint(localBrain, localSession)
                val localContext = if (localEndpoint == null) context else {
                    val opponentSpreads = if (trainerProfile.difficulty.tier in setOf(
                            BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS,
                        )) {
                        actualOpponentStatSpreadsByPreviewSlot()
                    } else emptyMap()
                    // The tiers that read the hidden set read the preview's Pokemon not yet seen too, on the board the
                    // rules read them on (LocalSwitchRules.previewFoes, behind at full HP): seen alone, they had no move
                    // and hit nothing in any reading of the fight still to come (self-play, 2026-10-22).
                    val previewRead = if (trainerProfile.difficulty.tier in setOf(BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS))
                        jbro.cobblemon.mcc.betterai.matchup.LocalSwitchRules.previewSetReading(context) else null
                    val previewFoes = previewRead?.first?.pokemon.orEmpty().filter { foe -> state.pokemon.none { it.battlePokemonId == foe.battlePokemonId } }
                    // Capture game-owned values here. Only the immutable board/catalog and copied collections
                    // enter the worker; the worker must not call battle/observationAdapter/liveShowdownState.
                    val moveIds = actualOpponentMoveIds() + actualPreviewMoveIds(previewFoes)
                    val transformed = observationAdapter.transformedPokemon().toSet()
                    val illusionTypes = actualIllusionTypeIds(state)
                    val tier = trainerProfile.difficulty.tier
                    val stageContext = context.copy(localOpponentStatSpreads = opponentSpreads)
                    val inference = opponentMoveInference
                    localEndpoint = localEndpoint.withPreparedContext { captured ->
                        jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex.withDecisionSnapshot {
                            jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope.with {
                                captured.copy(publicActionCatalog = publicCatalog.withOpponentMoveInferences(
                                    inference.update(
                                        previewRead?.first ?: state,
                                        previewRead?.second ?: publicCatalog,
                                        tier,
                                        moveIds,
                                        transformed,
                                        jbro.cobblemon.mcc.betterai.matchup.LocalOwnStageValue(stageContext, tier),
                                        illusionTypes,
                                    ),
                                ))
                            }
                        }
                    }
                    stageContext.withShowdownState(liveShowdownState())
                }
                val decision = fallbackChain.decide(
                    endpoint(primaryBrain, primarySession),
                    localEndpoint,
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
                    startedAtNanos = decisionStartedAtNanos,
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

    /**
     * The live battle as Showdown holds it, for the local Brain's native search. Showdown's context belongs to the
     * server thread; a request that arrives elsewhere waits briefly for it, and goes without on a timeout.
     */
    private fun liveShowdownState(): String? {
        if (server.isSameThread) return Cobblemon173ShowdownStateCapture.capture(battle.battleId)
        return try {
            server.submit(java.util.function.Supplier { Cobblemon173ShowdownStateCapture.capture(battle.battleId) })
                .get(LIVE_STATE_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            MoreCobblemonContents.LOGGER.warn("Battle {} Showdown state was not read in time", battle.battleId)
            null
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
                MoreCobblemonContents.LOGGER.warn(
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
                    BattleDecisionSource.PRIMARY_BRAIN -> jbro.cobblemon.mcc.internal.ai.BattlePlanOwner.PRIMARY_BRAIN
                    BattleDecisionSource.LOCAL_BRAIN -> jbro.cobblemon.mcc.internal.ai.BattlePlanOwner.LOCAL_BRAIN
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

        MoreCobblemonContents.LOGGER.error(
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
    /**
     * An opposing Pokemon behind an Illusion: its own form's types (the hidden set's tiers read them), only while its shown
     * types are still the disguise's form types. A public type change (Soak, Terastallization) is its current typing, kept.
     */
    private fun actualIllusionTypeIds(state: jbro.cobblemon.mcc.internal.ai.BattleStateView): Map<UUID, Set<String>> =
        compatibilityCallOrNull { battle.getActor(opponentActorId) }?.activePokemon.orEmpty().mapNotNull { active ->
            val pokemon = active.battlePokemon ?: return@mapNotNull null
            val disguise = active.illusion ?: return@mapNotNull null
            val shown = state.pokemon.firstOrNull { it.battlePokemonId == pokemon.uuid } ?: return@mapNotNull null
            val disguiseTypes = disguise.effectedPokemon.form.types.mapTo(hashSetOf()) { it.name.lowercase() }
            if (shown.knownTeraTypeId != null || shown.knownTypeIds.mapTo(hashSetOf()) { it.lowercase() } != disguiseTypes) return@mapNotNull null
            pokemon.uuid to pokemon.effectedPokemon.form.types.mapTo(linkedSetOf()) { it.name }
        }.toMap()

    private fun actualOpponentMoveIds(): Map<UUID, Set<String>> =
        compatibilityCallOrNull { battle.getActor(opponentActorId) }?.pokemonList.orEmpty().associate { pokemon ->
            pokemon.uuid to pokemon.moveSet.getMoves().mapTo(linkedSetOf()) { move -> move.name }
        }

    /** The real moves of the preview's Pokemon not yet seen, by the ids the rules' board gives them; a unique species and form only. */
    private fun actualPreviewMoveIds(foes: List<jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView>): Map<UUID, Set<String>> {
        if (foes.isEmpty()) return emptyMap()
        val actual = compatibilityCallOrNull { battle.getActor(opponentActorId) }?.pokemonList.orEmpty()
        return foes.mapNotNull { foe ->
            actual.singleOrNull { pokemon ->
                pokemon.effectedPokemon.species.resourceIdentifier.toString().equals(foe.speciesId, true) &&
                    (foe.formId == null || pokemon.effectedPokemon.form.name.equals(foe.formId, true))
            }?.let { foe.battlePokemonId to it.moveSet.getMoves().mapTo(linkedSetOf()) { move -> move.name } }
        }.toMap()
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
            MoreCobblemonContents.LOGGER.error(
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
        failures: List<jbro.cobblemon.mcc.internal.ai.BattleDecisionFailure>,
        actionKinds: List<BattleActionKind> = emptyList(),
        diagnosticTags: Set<String> = emptySet(),
        decisionStartedAtNanos: Long,
    ) {
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
            (System.nanoTime() - decisionStartedAtNanos).coerceAtLeast(0L),
        )
        MoreCobblemonContents.LOGGER.info(
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
        const val LIVE_STATE_WAIT_MILLIS = 2_000L
        const val MAX_REASKS = 2
    }
}

object Cobblemon173BrainDecisionTiming {
    fun deadline(now: Long, unbounded: Boolean): Long = when {
        unbounded -> Long.MAX_VALUE
        now > Long.MAX_VALUE - BattleBrainDefaults.DECISION_TIMEOUT_MILLIS -> Long.MAX_VALUE
        else -> now + BattleBrainDefaults.DECISION_TIMEOUT_MILLIS
    }
}
