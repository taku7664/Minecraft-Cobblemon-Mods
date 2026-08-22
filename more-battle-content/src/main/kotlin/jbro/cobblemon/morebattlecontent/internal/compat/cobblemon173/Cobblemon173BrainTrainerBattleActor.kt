package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.ai.BattleAI
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.battles.ShowdownActionRequest
import com.cobblemon.mod.common.battles.ShowdownActionResponse
import com.cobblemon.mod.common.battles.actor.TrainerBattleActor
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import java.util.concurrent.Executors
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
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionValidationStatus
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionValidator
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnowledgePolicy
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.internal.ai.BattleBrainDecisionCoordinator
import jbro.cobblemon.morebattlecontent.internal.ai.BattleBrainEndpoint
import jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionFallbackChain
import jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionDiagnostics
import jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionResolution
import jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionSource
import jbro.cobblemon.morebattlecontent.internal.ai.BattleTacticalMemoryLedger
import jbro.cobblemon.morebattlecontent.internal.ai.BattleTacticalRunMemoryStore
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
) : TrainerBattleActor(trainerName, actorId, pokemonList, baselineAi), EntityBackedBattleActor<ArmorStand> {
    override val entity: ArmorStand = trainerEntity
    override val initialPos: Vec3 = trainerEntity.position()

    private val localBrain = localBrain?.takeUnless { it === primaryBrain }
    private val observationAdapter = Cobblemon173ShowdownObservationAdapter(
        opponentActorId = opponentActorId,
        initialOpponentPokemonCount = initialOpponentPokemonCount,
    )
    private val pendingRequest = AtomicReference<ShowdownActionRequest?>()
    private val closeResult = AtomicReference<BattleBrainCloseResult?>()
    private val primarySession = AtomicReference<BattleBrainSession?>()
    private val localSession = AtomicReference<BattleBrainSession?>()
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

        val state = try {
            observationAdapter.attach(battle)
            observationAdapter.snapshot(this)
        } catch (exception: Exception) {
            logFailure("public observation", exception)
            submitBaselineOrEmergency(currentRequest, preparation)
            return
        }
        if (state.format != battleFormat || preparation.format != battleFormat) {
            MoreBattleContent.LOGGER.error("Battle {} format changed while preparing a Brain turn", battle.battleId)
            submitBaselineOrEmergency(currentRequest, preparation)
            return
        }
        tacticalMemory.observe(state)

        val now = System.currentTimeMillis()
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = state,
            candidates = preparation.candidates,
            deadlineEpochMillis = safeDeadline(now),
            memory = tacticalMemory.view(state.turn),
            publicActionCatalog = Cobblemon173PublicActionCatalog.from(state),
        )
        val primaryEndpoint = endpoint(primaryBrain, primarySession)
        val localEndpoint = endpoint(localBrain, localSession)
        val decisionStartedAtNanos = System.nanoTime()
        val pendingDecision = try {
            fallbackChain.decide(primaryEndpoint, localEndpoint, context)
        } catch (exception: Exception) {
            logFailure("Brain decision start", exception)
            submitBaselineOrEmergency(currentRequest, preparation)
            return
        }
        pendingDecision.whenComplete { resolution, throwable ->
            server.execute {
                completeOnServerThread(
                    expectedRequest = currentRequest,
                    preparation = preparation,
                    context = context,
                    resolution = resolution,
                    throwable = throwable,
                    decisionStartedAtNanos = decisionStartedAtNanos,
                )
            }
        }
    }

    fun closeBrains(result: BattleBrainCloseResult) {
        if (!closeResult.compareAndSet(null, result)) return
        BattleTacticalRunMemoryStore.record(learningScopeId, tacticalMemory.view(result.turns).tendencies)
        closeSessionReference(primaryBrain, primarySession, result)
        closeSessionReference(localBrain, localSession, result)
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
            val selectedResolution = requireNotNull(resolution)
            val selectedDecision = requireNotNull(selectedResolution.decision)
            val selectedCandidate = context.candidates.single { it.actionId == selectedDecision.actionId }
            logDecisionResolution(
                context = context,
                source = selectedResolution.source,
                failures = selectedResolution.failures,
                actionKinds = selectedCandidate.diagnosticActionKinds(),
                decisionStartedAtNanos = decisionStartedAtNanos,
            )
            val submitted = submitPreparedResponses(expectedRequest, selectedResponses)
            if (submitted) {
                val planOwner = when (selectedResolution.source) {
                    BattleDecisionSource.PRIMARY_BRAIN -> jbro.cobblemon.morebattlecontent.api.ai.BattlePlanOwner.PRIMARY_BRAIN
                    BattleDecisionSource.LOCAL_BRAIN -> jbro.cobblemon.morebattlecontent.api.ai.BattlePlanOwner.LOCAL_BRAIN
                    else -> null
                }
                tacticalMemory.accept(context.state, selectedCandidate, selectedDecision.advice, planOwner)
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
        val emergencyResponses = if (preparation != null && context != null && preparation.candidates.isNotEmpty()) {
            val emergency = BattleDecisionResolution.emergency(context, resolution?.failures.orEmpty())
            preparation.responsesFor(requireNotNull(emergency.decision).actionId)
        } else if (preparation != null && preparation.candidates.isNotEmpty()) {
            preparation.responsesFor(preparation.candidates.first().actionId)
        } else {
            null
        }
        submitPreparedResponses(expectedRequest, emergencyResponses ?: emergencyPasses(expectedRequest))
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
            logFailure("action submission", exception)
            try {
                setActionResponses(emergencyPasses(expectedRequest))
            } catch (passException: Exception) {
                logFailure("emergency pass submission", passException)
            }
            false
        } finally {
            pokemonList.forEach { it.willBeSwitchedIn = false }
            pendingRequest.compareAndSet(expectedRequest, null)
        }
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
        }
    }

    private fun safeDeadline(now: Long): Long =
        if (now > Long.MAX_VALUE - BattleBrainDefaults.DECISION_TIMEOUT_MILLIS) {
            Long.MAX_VALUE
        } else {
            now + BattleBrainDefaults.DECISION_TIMEOUT_MILLIS
        }

    private fun logFailure(operation: String, throwable: Throwable) {
        MoreBattleContent.LOGGER.error(
            "Battle {} {} failed: {}",
            runCatching { battle.battleId }.getOrNull(),
            operation,
            throwable.javaClass.name,
        )
    }

    private fun logDecisionResolution(
        context: BattleDecisionContext,
        source: BattleDecisionSource,
        failures: List<jbro.cobblemon.morebattlecontent.internal.ai.BattleDecisionFailure>,
        actionKinds: List<BattleActionKind> = emptyList(),
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
                failures = failures,
            ),
        )
    }

    private fun BattleActionCandidate.diagnosticActionKinds(): List<BattleActionKind> =
        if (kind == BattleActionKind.COMPOSITE) componentActions.map { it.kind } else listOf(kind)

    private companion object {
        val coordinator = BattleBrainDecisionCoordinator(
            scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "mbc-brain-deadline").apply { isDaemon = true }
            },
            brainExecutor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "mbc-brain-worker").apply { isDaemon = true }
            },
        )
        val fallbackChain = BattleDecisionFallbackChain(coordinator)
    }
}
