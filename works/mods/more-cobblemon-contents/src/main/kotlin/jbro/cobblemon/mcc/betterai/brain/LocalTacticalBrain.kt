package jbro.cobblemon.mcc.betterai.brain

import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.slf4j.LoggerFactory
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleBrain
import jbro.cobblemon.mcc.internal.ai.BattleLeadChoiceContext
import jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds
import jbro.cobblemon.mcc.internal.ai.BattleBrainCloseResult
import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleBrainSession
import jbro.cobblemon.mcc.internal.ai.BattleCandidateFactsView
import jbro.cobblemon.mcc.internal.ai.BattleDecision
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattlePlanIntent
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStrategyBrief
import jbro.cobblemon.mcc.internal.ai.BattleStrategyObjective
import jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalSituationalEvaluator
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.policy.LocalActionChoiceSeed
import jbro.cobblemon.mcc.betterai.policy.LocalActionKindWeights
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelection
import jbro.cobblemon.mcc.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelector
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.policy.LocalBattleMind
import jbro.cobblemon.mcc.betterai.policy.LocalRootDecisionPolicy
import jbro.cobblemon.mcc.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.mcc.betterai.policy.forPlanOwner
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadDecisionSignature
import jbro.cobblemon.mcc.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluation
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionState
import jbro.cobblemon.mcc.betterai.search.NativeRulePriorities
import jbro.cobblemon.mcc.betterai.evaluation.LocalOpponentThreat
import jbro.cobblemon.mcc.betterai.matchup.LocalAceScore
import jbro.cobblemon.mcc.betterai.matchup.LocalRuleWeights
import jbro.cobblemon.mcc.betterai.matchup.LocalGimmickReserve
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalOpponentIntentPredictor
import jbro.cobblemon.mcc.betterai.matchup.LocalPublicFailureTriage
import jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate
import jbro.cobblemon.mcc.betterai.matchup.LocalStatusMoveTriage
import jbro.cobblemon.mcc.betterai.matchup.LocalSwitchRules
import jbro.cobblemon.mcc.betterai.policy.LocalLeadChoice
import kotlin.math.roundToInt

private const val WEAKER_CHOICE_MARGIN = 0.05
private const val THREAT_TIME_LIMIT_NANOS = 300_000_000L
private const val DOUBLES_PROTECT_PARTNERS = 2
private val MEGA_MECHANIC_IDS = setOf("mega", "megaevolution")
private const val MEGA_TIE_TOLERANCE = 1e-6
/** Matchup scores for the choosing rules; an unfinished table rules nothing out. */
private const val RULE_SCORES_TIME_LIMIT_NANOS = 1_000_000_000L
private val logger = LoggerFactory.getLogger(LocalTacticalBrain::class.java)

internal fun interface NativeInitialDecisionSource {
    fun evaluate(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        budget: LocalLookaheadBudget,
        sessionState: NativeProductSessionState?,
        rules: NativeRulePriorities,
    ): NativeInitialProductDecisionEvaluation
}

private val defaultNativeInitialDecisionEvaluator = NativeInitialProductDecisionEvaluator()

private fun nativeFailureMessage(
    evaluation: NativeInitialProductDecisionEvaluation,
    context: BattleDecisionContext,
) : String =
    buildString {
        append("Native product decision failed: ")
        append(evaluation.status.name)
        evaluation.reconciliationStatus?.let { append(" reconcile=").append(it.name) }
        evaluation.searchStatus?.let { append(" search=").append(it.name) }
        evaluation.failedRunStatus?.let { append(" run=").append(it.name) }
        evaluation.failedRunDetail?.let { append(" detail=").append(it) }
        evaluation.failedWorldId?.let { append(" world=").append(it.take(120)) }
        if (evaluation.planIssues.isNotEmpty()) {
            append(" issues=")
            append(evaluation.planIssues.joinToString(",") { issue ->
                issue.code.name + (issue.detailCode?.let { "/$it" } ?: "")
            })
            if (evaluation.planIssues.any { it.code.name == "ROSTER_COMPILATION_FAILED" }) {
                append(" public_roster=")
                append("turn:").append(context.state.turn)
                append(";remaining:").append(context.state.remainingPokemonBySide[BattleSide.OPPONENT])
                append(";selection:").append(context.opponentTeamPreview?.selectionSize)
                append(";seen:").append(context.state.pokemon.asSequence()
                    .filter { it.side == BattleSide.OPPONENT }
                    .joinToString("|") { "${it.speciesId}/${it.formId}@${it.activeSlot}" })
                append(";events:").append(context.state.observedEvents.size).append(':')
                append(context.state.observedEvents.asSequence().take(16).joinToString("|") {
                    "${it.kind}@${it.turn}#${it.actorPokemonId?.toString()?.take(8) ?: "-"}"
                })
            }
        }
    }

internal class LocalTacticalBrain(
    private val actionSelector: LocalActionSelector = LocalWeightedActionSelector(),
    private val tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    private val lookaheadBudget: (BattleTrainerTier) -> LocalLookaheadBudget = LocalLookaheadBudgetPolicy::forTier,
    private val nativeInitialDecision: NativeInitialDecisionSource =
        NativeInitialDecisionSource { context, profile, localTuning, budget, sessionState, rules ->
            defaultNativeInitialDecisionEvaluator.evaluate(context, profile, localTuning, budget, sessionState, rules)
        },
    /**
     * Decide on the one-turn ranking alone, with the rules' credits, vetoes and the weighted draw it always had, and
     * search nothing, native or legacy. The live server's 2-vCPU host took 6 to 27 s a decision with the search
     * (2026-10-11 release); this decides at once. Off keeps the search.
     */
    private val searchless: Boolean = false,
    /** With [searchless], singles at Advanced and Boss go through [jbro.cobblemon.mcc.betterai.matchup.LocalQuickDecider]. */
    private val quickDecider: Boolean = true,
    /**
     * With [searchless], singles at every tier play the status moves the ranking cannot price out in the
     * native engine ([jbro.cobblemon.mcc.betterai.search.LocalNativeStatusValue]); false leaves them at the generic value.
     */
    private val nativeStatus: Boolean = true,
    /** Receives every candidate's value at each step of a searchless decision (diagnostics only; null in the game). */
    private val stageTrace: ((LocalDecisionStages) -> Unit)? = null,
) : BattleBrain {
    override fun openSession(context: BattleBrainOpenContext): BattleBrainSession =
        Session(
            UUID.randomUUID(),
            context.battleId,
            context.trainerPersonaId,
            context.strategy,
            context.trainerProfile,
        )

    override fun decide(
        session: BattleBrainSession,
        context: BattleDecisionContext,
    ): CompletionStage<BattleDecision> = LocalActiveTuning.with(tuning) { decideWithTuning(session, context) }

    private fun decideWithTuning(
        session: BattleBrainSession,
        context: BattleDecisionContext,
    ): CompletionStage<BattleDecision> {
        val decisionStartedAtNanos = System.nanoTime()
        val active = session as? Session
        val profile = active?.trainerProfile ?: BattleTrainerProfile.balanced()
        val strategy = active?.strategy.takeUnless {
            profile.difficulty.tier == BattleTrainerTier.INTRODUCTORY
        }
        // One clock for the whole decision: the native search, a legacy fallback after it and the
        // preparation before both share it, so a failed native search cannot hand the legacy one a fresh window.
        val context = context.copy(deadlineEpochMillis = LocalLookaheadBudgetPolicy.deadline(
            startMillis = System.currentTimeMillis(),
            externalDeadlineMillis = context.deadlineEpochMillis,
            budgetMillis = lookaheadBudget(profile.difficulty.tier).timeMillis,
        )).let { timed -> active?.let { withSilentEntries(it, timed, profile.difficulty.tier) } ?: timed }
            .let(::withSettledAbilities)
        // An opponent's unrevealed status moves stay unnamed: no tier guesses them (2026-10-12, user decision).
        val assumedContext = LocalOpponentStatAssumption.applyToPublicState(context, profile.difficulty.tier)
        val calculatedContext = PublicBattleTacticalCalculator.calculate(assumedContext, observedBoard = true)
            .forPlanOwner(jbro.cobblemon.mcc.internal.ai.BattlePlanOwner.LOCAL_BRAIN)
        val difficultyContext = if (profile.difficulty.tier == BattleTrainerTier.INTRODUCTORY) {
            calculatedContext.withoutActivePlan()
        } else {
            calculatedContext
        }
        active?.repeats?.observe(difficultyContext.state)
        // Assessed before ranking, not after, because the risk budget it resolves belongs in the
        // scoring rather than only in the draw at the end.
        //
        // `riskBudget` is the trainer's personality risk, shifted by a style offset derived stably
        // from their persona id, shifted again by how far ahead or behind they are. All three were
        // being computed and then handed only to the weighted selector - which was measured to have
        // almost nothing to tilt, so three separate trainers played identically in 40 of 40 recorded
        // positions. Carrying it as the effective personality means every consumer of
        // `personality.riskTolerance` sees the resolved value without a new parameter on any of them.
        val battleId = active?.battleId ?: calculatedContext.state.battleId
        val mind = LocalBattleMind.assess(
            trainerPersonaId = active?.trainerPersonaId,
            battleId = battleId,
            context = difficultyContext,
            profile = profile,
        )
        val decidingProfile = profile.copy(
            personality = profile.personality.copy(riskTolerance = mind.riskBudget),
        )
        // Built once per decision on Advanced and Boss (the rules and the opponent-intent prediction read it),
        // then shared: the search asks for mixing contexts from its own threads.
        fun pairTable(position: BattleDecisionContext): jbro.cobblemon.mcc.betterai.matchup.MatchupScores? {
            val started = System.nanoTime()
            return LocalMatchupScoreCalculator.calculate(
                position,
                shouldContinue = { System.nanoTime() - started < RULE_SCORES_TIME_LIMIT_NANOS },
                recovery = tuning.matchupRecovery,
            ).takeIf { it.complete }
        }
        // The board the rules read staying on (singles): the Pokemon in front Mega Evolved when one of its moves can
        // Mega Evolve it this turn, since staying and attacking does. On the base form's table a lead Garchomp that can
        // Mega Evolve read as losing to a Hippowdon it beats as a Mega, and the switching rules took it out (2026-10-12).
        val rulesContext: BattleDecisionContext by lazy {
            if (difficultyContext.state.format != BattleFormat.SINGLE) difficultyContext
            else difficultyContext.candidates.asSequence().filter { it.kind == BattleActionKind.USE_MOVE }
                .map { jbro.cobblemon.mcc.betterai.mechanics.LocalMechanicActivationProjector.forMegaCandidate(difficultyContext, BattleSide.ALLY, it) }
                .firstOrNull { it !== difficultyContext } ?: difficultyContext
        }
        val baseRuleScores by lazy { pairTable(difficultyContext) }
        val ruleScores by lazy { if (rulesContext === difficultyContext) baseRuleScores else pairTable(rulesContext) }
        // A stat raise is judged on the board it is used on: the Mega's with a Mega Evolution, the base form's without.
        // On the base form's a Mega Garchomp's Swords Dance read as a 19% win against a Hippowdon it beats outright.
        val megaSetupViews = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Pair<BattleDecisionContext, jbro.cobblemon.mcc.betterai.matchup.MatchupScores>>>()
        fun setupView(candidate: jbro.cobblemon.mcc.internal.ai.BattleActionCandidate): Pair<BattleDecisionContext, jbro.cobblemon.mcc.betterai.matchup.MatchupScores>? {
            val mega = jbro.cobblemon.mcc.betterai.mechanics.LocalMechanicActivationProjector.forMegaCandidate(difficultyContext, BattleSide.ALLY, candidate)
            if (mega === difficultyContext) return baseRuleScores?.let { difficultyContext to it }
            if (rulesContext !== difficultyContext) return ruleScores?.let { rulesContext to it }
            return megaSetupViews.computeIfAbsent(candidate.actionId) {
                val started = System.nanoTime()
                LocalMatchupScoreCalculator.calculate(
                    mega,
                    shouldContinue = { System.nanoTime() - started < RULE_SCORES_TIME_LIMIT_NANOS },
                    recovery = tuning.matchupRecovery,
                ).takeIf { it.complete }.let { table -> java.util.Optional.ofNullable(table?.let { mega to it }) }
            }.orElse(null)
        }
        val setupGateVerdicts = java.util.concurrent.ConcurrentHashMap<String, LocalSetupGate.Verdict>()
        // Candidates the matchup-score rules rule out, with the selector's exclusion reason. The search
        // spends nothing on them either.
        val rulesApply = profile.difficulty.tier == BattleTrainerTier.ADVANCED || profile.difficulty.tier == BattleTrainerTier.BOSS
        // Switches are on offer in almost every decision, so with the switching rules the table is built for most.
        val switchJudgement by lazy {
            val switching = difficultyContext.candidates.any { candidate ->
                (if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate))
                    .any { it.kind == BattleActionKind.SWITCH }
            }
            if (!rulesApply || !switching) LocalSwitchRules.Judgement.NONE
            else ruleScores?.let { LocalSwitchRules.judge(difficultyContext.candidates, rulesContext, it, profile.difficulty.tier) }
                ?: LocalSwitchRules.Judgement.NONE
        }
        val publicFailures = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        fun ruleExclusions(candidates: List<BattleActionCandidate>): Map<String, String> {
            if (!rulesApply) return emptyMap()
            // A turn with nothing but failing moves keeps them, as the search does.
            val failing = candidates.filter { candidate ->
                publicFailures.getOrPut(candidate.actionId) { LocalPublicFailureTriage.fails(candidate, difficultyContext) }
            }.takeIf { it.size < candidates.size }.orEmpty()
            val plainExclusions = switchJudgement.exclusions.filterKeys { id -> candidates.any { it.actionId == id } } +
                failing.associate { it.actionId to LocalPublicFailureTriage.REASON }
            val judged = candidates.filter {
                LocalSetupGate.raisesOwnStats(it) || LocalStatusMoveTriage.judgeable(it, difficultyContext)
            }
            if (judged.isEmpty()) return plainExclusions
            val scores = ruleScores ?: return plainExclusions
            return plainExclusions + judged.mapNotNull { candidate ->
                val gated = LocalSetupGate.raisesOwnStats(candidate) && !LocalSetupGate.passes(candidate) { part ->
                    setupGateVerdicts.getOrPut(part.actionId) {
                        setupView(part)?.let { (view, table) -> LocalSetupGate.evaluate(part, view, table, tier = profile.difficulty.tier) }
                            ?: LocalSetupGate.Verdict(true, emptyList())
                    }
                }
                when {
                    gated -> candidate.actionId to LocalSetupGate.REASON
                    LocalStatusMoveTriage.wasted(candidate, rulesContext, scores) -> candidate.actionId to LocalStatusMoveTriage.REASON
                    else -> null
                }
            }.toMap()
        }
        fun mixingContext(
            ranked: List<LocalBattleActionRank>,
            authoritativeSimulationScores: Boolean = false,
        ): LocalActionMixingContext {
            return LocalActionMixingContext(
                personality = profile.personality,
                memory = difficultyContext.memory,
                style = mind.trainerStyle,
                riskBudget = mind.riskBudget,
                decisionRegretBand = decidingProfile.difficulty.decisionRegretBand,
                decisionShortlistWidth = decidingProfile.difficulty.decisionShortlistWidth,
                uncertainConditionalActionIds = ranked.asSequence()
                    .filter {
                        LocalTacticalSituationalEvaluator.pendingDamagingMoveRiskPenalty(
                            it.outcome.candidate,
                            difficultyContext,
                        ) > 0.0
                    }
                    .map { it.outcome.candidate.actionId }
                    .toSet(),
                alreadyBoostedSetupActionIds = ranked.asSequence()
                    .filter {
                        LocalTacticalSituationalEvaluator.alreadyBoostedSelfSetup(
                            it.outcome.candidate,
                            difficultyContext,
                        )
                    }
                    .map { it.outcome.candidate.actionId }
                    .toSet(),
                overcommittedSetupActionIds = ranked.asSequence()
                    .filter {
                        LocalTacticalSituationalEvaluator.overcommittedSelfSetup(
                            it.outcome.candidate,
                            difficultyContext,
                        )
                    }
                    .map { it.outcome.candidate.actionId }
                    .toSet(),
                ruleExclusions = ruleExclusions(ranked.map { it.outcome.candidate }),
                sacrificeSwitchIds = if (authoritativeSimulationScores) emptySet() else switchJudgement.sacrifices,
                tuning = tuning,
                authoritativeSimulationScores = authoritativeSimulationScores,
            )
        }
        val perspectivePokemonIds = calculatedContext.state.pokemon.asSequence()
            .filter { it.side == BattleSide.ALLY }
            .map { it.battlePokemonId }
            .toList()
        val aiTestDecision = active?.trainerPersonaId
            ?.startsWith(BattleBrainContentIds.AI_TEST_PERSONA_PREFIX) == true
        if (aiTestDecision) {
            AiTestDecisionSnapshot.write(AiTestDecisionSnapshot(
                battleId = battleId,
                turn = context.state.turn,
                trainerPersonaId = active?.trainerPersonaId,
                trainerProfile = profile,
                strategy = active?.strategy,
                nativeContinuation = active?.nativeProductState != null,
                capturedAtEpochMillis = System.currentTimeMillis(),
                context = context,
            ))?.let { path -> logger.info("[BetterAI Trace] battle={} turn={} phase=snapshot path={}", battleId, context.state.turn, path) }
        }
        val decisionTrace = AiTestDecisionTrace.forTestPersona(
            active?.trainerPersonaId, difficultyContext, decisionStartedAtNanos,
        )
        val configuredBudget = LocalLookaheadBudgetPolicy.forFormat(
            lookaheadBudget(profile.difficulty.tier), calculatedContext.state.format,
        )
        // The legacy search only: native nodes are full Showdown turns and keep their own bound.
        val phaseBudget = if (profile.difficulty.tier == BattleTrainerTier.ADVANCED || profile.difficulty.tier == BattleTrainerTier.BOSS) {
            LocalLookaheadBudgetPolicy.forPosition(configuredBudget, calculatedContext.state)
        } else configuredBudget
        // AI test battles play on the same clock as real ones; they only add the snapshot and the trace.
        val budget = phaseBudget
        val nativeBudget = LocalLookaheadBudgetPolicy.forNativePosition(configuredBudget, calculatedContext.state)
        val continuingNative = active?.nativeProductState != null
        // The ace keeps the once-per-battle mechanics: worked out once, and again when a new opponent is seen.
        val aceScores = if (!rulesApply || active == null) emptyMap() else {
            val seen = difficultyContext.state.pokemon.filter { it.side == BattleSide.OPPONENT }.mapTo(hashSetOf()) { it.battlePokemonId }
            if (active.aceScores.isEmpty() || !active.aceOpponentIds.containsAll(seen)) {
                active.aceScores = LocalAceScore.calculate(difficultyContext)
                active.aceOpponentIds = seen
            }
            active.aceScores
        }
        // The reserve prices the future use the search cannot see, on both paths. The legacy path additionally prices
        // the doubled HP this turn; native Showdown already plays that HP and must not receive it twice.
        val gimmickReserveAdjustments by lazy { if (!tuning.mechanicReserve) emptyMap()
            else LocalGimmickReserve.adjustments(difficultyContext.candidates, difficultyContext, aceScores) }
        val baseOpponentIntents by lazy {
            if (!rulesApply) emptyList() else ruleScores?.let {
                LocalOpponentIntentPredictor.predict(
                    rulesContext, it, tuning.scoredSwitchIntent, tuning.doublesSwitchModel, profile.difficulty.tier,
                )
            }.orEmpty()
        }
        val dynamaxGuardReading by lazy {
            if (!tuning.mechanicReserve) jbro.cobblemon.mcc.betterai.matchup.LocalDynamaxGuard.Reading(emptyMap())
            else jbro.cobblemon.mcc.betterai.matchup.LocalDynamaxGuard.read(
                difficultyContext.candidates, difficultyContext, tuning,
                // Match protectTurnAdjustments: public matchup scores, scored switches, no invented status moves.
                intents = if (difficultyContext.state.format != BattleFormat.DOUBLE) null else runCatching {
                    LocalOpponentIntentPredictor.predict(
                        difficultyContext,
                        jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.calculate(difficultyContext),
                        scoredSwitches = true,
                    )
                }.getOrNull(),
            )
        }
        val gimmickAdjustments by lazy {
            (gimmickReserveAdjustments.asSequence() + dynamaxGuardReading.adjustments.asSequence())
                .groupBy({ it.key }, { it.value }).mapValues { (_, values) -> values.sum() }
        }
        // A stat raise is valued by whether its user still wins the one-on-one after it (yes or no), times the sweep bonus
        // (LocalSetupGate.score; singles). Its ranking value is moved that far toward this value.
        // A waiting opponent that turns into the raised Pokemon on entry takes the raise with it (LocalEntryCopyRisk).
        val copyRisk by lazy { try {
            jbro.cobblemon.mcc.betterai.search.LocalEntryCopyRisk.chance(context, profile.difficulty.tier)
        } catch (e: RuntimeException) {
            logger.error("[BetterAI] The engine could not read an entry copy; raises are valued without it", e)
            0.0
        } }
        val setupScores by lazy { if (!rulesApply || tuning.setupSweepCredit <= 0.0 ||
            difficultyContext.state.format != jbro.cobblemon.mcc.internal.ai.BattleFormat.SINGLE) emptyMap()
        else difficultyContext.candidates.filter(LocalSetupGate::raisesOwnStats).mapNotNull { candidate ->
            setupView(candidate)?.let { (view, table) -> LocalSetupGate.score(candidate, view, table, tuning.knockoutMaterialScore, copyRisk = copyRisk) }
                ?.let { candidate.actionId to it.value }
        }.toMap() }
        // An attack a known heal undoes, and a heal the opponent's hit undoes, go nowhere.
        // A screen earns the hits it halves while it lasts (LocalScreenCredit).
        val screenAdjustments by lazy { if (!rulesApply) emptyMap() else ruleScores?.let { scores ->
            jbro.cobblemon.mcc.betterai.matchup.LocalScreenCredit.adjustments(difficultyContext.candidates, rulesContext, scores)
        }.orEmpty() }
        // A debuff is worth what it takes off its target's exchanges with this whole team (LocalDebuffValue; singles).
        val debuffReadings by lazy { if (!rulesApply || difficultyContext.state.format != jbro.cobblemon.mcc.internal.ai.BattleFormat.SINGLE) emptyMap()
            else ruleScores?.let { scores ->
                difficultyContext.candidates.mapNotNull { candidate ->
                    jbro.cobblemon.mcc.betterai.matchup.LocalDebuffValue.read(candidate, rulesContext, scores, tuning.matchupRecovery)
                        ?.let { candidate.actionId to it }
                }.toMap()
            }.orEmpty() }
        // Stealth Rock, Spikes and Encore earn what comes after the engine's two turns (LocalHazardEncoreCredit).
        val hazardEncoreAdjustments by lazy { if (!rulesApply) emptyMap() else ruleScores?.let { scores ->
            jbro.cobblemon.mcc.betterai.matchup.LocalHazardEncoreCredit.adjustments(difficultyContext.candidates, rulesContext, scores)
        }.orEmpty() }
        val healRaceAdjustments by lazy { if (!rulesApply) emptyMap() else ruleScores?.let { scores ->
            jbro.cobblemon.mcc.betterai.matchup.LocalHealRace.adjustments(difficultyContext.candidates, rulesContext, scores, tuning)
        }.orEmpty() }
        // The rules steer the native search too: what they rule out is not searched, and the ace, the
        // AI's answer to the opponent's sweeper and the opponent's answer to the AI's weigh more.
        val nativeRules = if (!rulesApply) NativeRulePriorities.NONE else NativeRulePriorities(
            excludedActionIds = ruleExclusions(difficultyContext.candidates).keys,
            weights = LocalRuleWeights.weights(difficultyContext.state, profile.difficulty.tier, aceScores, ruleScores),
            scores = ruleScores,
        )
        val nativeInitial = if (searchless) {
            NativeInitialProductDecisionEvaluation(NativeInitialProductDecisionStatus.NOT_APPLICABLE)
        } else nativeInitialDecision.evaluate(
            difficultyContext,
            decidingProfile,
            tuning,
            nativeBudget,
            active?.nativeProductState,
            nativeRules,
        )
        decisionTrace?.nativeSearch(nativeInitial, nativeBudget.nativePlies ?: profile.difficulty.lookaheadPlies, nativeBudget)
        var nativeFallbackStatus: NativeInitialProductDecisionStatus? = null
        // Roots that reconciled with the current board survive a failed native search. The legacy
        // choice made this turn becomes their pending action, so the next turn can continue natively
        // instead of losing native search for the rest of the battle.
        var retainedNativeState: NativeProductSessionState? = null
        when (nativeInitial.status) {
            NativeInitialProductDecisionStatus.AVAILABLE -> {
                // The search values the turns it played. What it cannot see - the battle so far, the trainer, the rules'
                // longer view - is added as the legacy path adds it. Predicted switches and doubles Protect credit are
                // left out: the search's worst reply already holds the opponent's switch and its hits.
                val oneTurn = nativeInitial.depthCompleted < 2
                // Read from the calculated candidates, which carry the facts (a heal's share) the rules look at.
                val calculated = difficultyContext.candidates.associateBy { it.actionId }
                val adjusted = LocalBattleActionPolicy.sort(nativeInitial.ranked.map { rank ->
                    val id = rank.outcome.candidate.actionId
                    val extra = LocalTacticalScorer.simulationBlindAdjustment(
                        calculated[id] ?: rank.outcome.candidate, difficultyContext, strategy, decidingProfile, tuning, oneTurn,
                    ) + (switchJudgement.adjustments[id] ?: 0.0) + (gimmickReserveAdjustments[id] ?: 0.0) +
                        (healRaceAdjustments[id] ?: 0.0) + (screenAdjustments[id] ?: 0.0) + (hazardEncoreAdjustments[id] ?: 0.0)
                    if (extra == 0.0) rank else rank.copy(comparisonValue = rank.comparisonValue + extra)
                })
                // Only the re-entry veto: the setup one reads the HP a stay keeps, which native ranks do not carry.
                val ranked = LocalRootDecisionPolicy.refine(adjusted, difficultyContext, preserveSetup = false).ranked
                val nativeSearchStatus = requireNotNull(nativeInitial.searchStatus)
                val seed = LocalActionChoiceSeed.derive(
                    battleId = battleId,
                    turn = calculatedContext.state.turn,
                    ranked = ranked,
                    perspectivePokemonIds = perspectivePokemonIds,
                )
                val selection = actionSelector.choose(
                    ranked,
                    seed,
                    mixingContext(ranked, authoritativeSimulationScores = true),
                )
                val selected = selection.rank
                decisionTrace?.resolved(if (continuingNative) "native_continuation" else "native_initial", ranked, selection)
                active?.nativeProductState = requireNotNull(nativeInitial.sessionState) {
                    "An available native product decision must preserve its reusable roots"
                }.withPendingOwnAction(selected.outcome.candidate)
                val confidence = (0.35 + selection.probability * 0.6).coerceIn(0.35, 0.99)
                return CompletableFuture.completedFuture(
                    BattleDecision(
                        requestId = context.requestId,
                        actionId = selected.outcome.candidate.actionId,
                        confidence = confidence,
                        advice = LocalBattleMind.advice(selected, difficultyContext, strategy, profile),
                        tags = buildSet {
                            addAll(setOf(
                                "local_tactical_v4",
                                "tuning_${tuning.id}",
                                "mixed_top40",
                                "contextual_human_mix",
                                "persistent_intent",
                                "evidence_gated_mixup",
                                "position_risk_budget",
                                "choice_pool_${selection.shortlistSize}",
                                "choice_seed_${selection.seed.toULong().toString(16)}",
                                "difficulty_${profile.difficulty.tier.name.lowercase()}",
                                "lookahead_requested_${if (tuning.doublesSingleTurn && context.state.format == BattleFormat.DOUBLE) 1
                                    else nativeBudget.nativePlies ?: profile.difficulty.lookaheadPlies}",
                                "lookahead_turns_${nativeInitial.depthCompleted}",
                                "lookahead_nodes_${nativeInitial.nodesVisited}",
                                "native_search_${nativeSearchStatus.name.lowercase(Locale.ROOT)}",
                            ))
                            add(if (continuingNative) {
                                "native_showdown_continuation"
                            } else {
                                "native_showdown_initial"
                            })
                            if (nativeInitial.rebuilt) add("native_showdown_rebuilt")
                            if (nativeInitial.truncated) add("lookahead_truncated")
                            addAll(decisionDiagnostics(calculatedContext, selected))
                        },
                    ),
                )
            }
            NativeInitialProductDecisionStatus.PLANNING_FAILED,
            NativeInitialProductDecisionStatus.RECONCILIATION_FAILED,
            NativeInitialProductDecisionStatus.SEARCH_FAILED,
            -> {
                nativeFallbackStatus = nativeInitial.status
                active?.nativeProductState = null
                retainedNativeState = nativeInitial.retainedSessionState
                decisionTrace?.nativeFallback(nativeInitial.status.name, nativeInitial.planIssues.joinToString(",") {
                    it.code.name + (it.detailCode?.let { detail -> "/$detail" } ?: "")
                })
                // No legacy lookahead after a native failure: it reads the same board more coarsely. The decision
                // takes the one-turn policy's best below, and the failure is logged loudly so it gets fixed.
                logger.error("Native product decision failed; taking the one-turn policy's best (no legacy fallback): {}",
                    nativeFailureMessage(nativeInitial, difficultyContext))
            }
            NativeInitialProductDecisionStatus.NOT_APPLICABLE -> nativeInitial.failedRunDetail?.let { detail ->
                // The board could not be rebuilt and no live battle came with the request (Showdown out of reach).
                logger.warn("Native search not applicable ({}); this decision uses the legacy lookahead", detail)
            }
        }
        difficultyContext.candidates.singleOrNull()?.let {
            val selected = LocalBattleActionPolicy.rank(
                difficultyContext,
                strategy,
                decidingProfile,
                tuning,
            ).single()
            decisionTrace?.resolved("single_legal", listOf(selected), LocalActionSelection(selected, 0L, 1, 1.0))
            retainedNativeState?.let { active?.nativeProductState = it.withPendingOwnAction(selected.outcome.candidate) }
            return CompletableFuture.completedFuture(
                BattleDecision(
                    requestId = context.requestId,
                    actionId = selected.outcome.candidate.actionId,
                    confidence = 1.0,
                    advice = LocalBattleMind.advice(selected, difficultyContext, strategy, profile),
                    tags = buildSet {
                        add("local_tactical_v4")
                        add("tuning_${tuning.id}")
                        add("single_legal_action")
                        add("difficulty_${profile.difficulty.tier.name.lowercase()}")
                        nativeFallbackStatus?.let { add("native_fallback_${it.name.lowercase(Locale.ROOT)}") }
                        if (retainedNativeState != null) add("native_session_retained")
                    },
                ),
            )
        }
        nativeFallbackStatus?.let { failed ->
            val ranked = LocalBattleActionPolicy.rank(difficultyContext, strategy, decidingProfile, tuning)
            val selected = ranked.first()
            decisionTrace?.resolved("native_failed_policy", ranked, LocalActionSelection(selected, 0L, 1, 1.0))
            retainedNativeState?.let { active?.nativeProductState = it.withPendingOwnAction(selected.outcome.candidate) }
            return CompletableFuture.completedFuture(
                BattleDecision(
                    requestId = context.requestId,
                    actionId = selected.outcome.candidate.actionId,
                    confidence = 0.35,
                    advice = LocalBattleMind.advice(selected, difficultyContext, strategy, profile),
                    tags = buildSet {
                        add("local_tactical_v4")
                        add("tuning_${tuning.id}")
                        add("native_failed_policy_choice")
                        add("difficulty_${profile.difficulty.tier.name.lowercase()}")
                        add("native_fallback_${failed.name.lowercase(Locale.ROOT)}")
                        if (retainedNativeState != null) add("native_session_retained")
                    },
                ),
            )
        }
        val baseRanked = LocalBattleActionPolicy.rank(difficultyContext, strategy, decidingProfile, tuning)
        // Every tier: a generic value is a guess at any level, and a lower tier is meant to play weaker, not senselessly.
        val statusPricing = if (!searchless || !nativeStatus || difficultyContext.state.format != BattleFormat.SINGLE) null
            else jbro.cobblemon.mcc.betterai.search.LocalNativeStatusValue.adjustments(baseRanked, difficultyContext, decidingProfile, tuning)
        // Where the rules run, the moves they price (hazards, Encore, debuffs) take the rules' value, not the engine's
        // two-turn reading; below that tier the engine's reading is all they have.
        // A singles Protect's turn, played against the opponent's likely replies instead of a waiting opponent: what a
        // contact move into it and the opponent's own raise or switch bring (LocalSinglesProtectReturn.turnEnd).
        val protectTurns = if (!searchless || !nativeStatus || difficultyContext.state.format != BattleFormat.SINGLE) emptyMap()
            else protectTurnAdjustments(difficultyContext, decidingProfile, tuning)
        val nativeStatusAdjustments = protectTurns + statusPricing?.adjustments.orEmpty().let { engine ->
            if (!rulesApply) engine else engine.filterKeys { id ->
                val candidate = difficultyContext.candidates.firstOrNull { it.actionId == id } ?: return@filterKeys true
                !jbro.cobblemon.mcc.betterai.matchup.LocalHazardEncoreCredit.prices(candidate) &&
                    !jbro.cobblemon.mcc.betterai.matchup.LocalDebuffValue.isDebuff(candidate) &&
                    !jbro.cobblemon.mcc.betterai.matchup.LocalTrickRoomCredit.prices(candidate) &&
                    !jbro.cobblemon.mcc.betterai.matchup.LocalPerishTrapCredit.prices(candidate)
            }
        }
        val threatStartedAtNanos = System.nanoTime()
        val rootRanked = baseRanked
        // A Boss remembers what the opponent did the last times the same Pokemon faced each other, as a player would.
        val readRepeats = tuning.readOpponentRepeats || profile.difficulty.tier == BattleTrainerTier.BOSS
        // Read from the same table as the rules; the AI's threat weights play no part in it.
        val opponentIntents = baseOpponentIntents.let { intents ->
            if (readRepeats && active != null) active.repeats.apply(intents, difficultyContext.state) else intents
        }
        val doublesProtectReading = if (!searchless || !nativeStatus || difficultyContext.state.format != BattleFormat.DOUBLE) null
            else doublesProtectTurnAdjustments(baseRanked, difficultyContext, decidingProfile, opponentIntents, tuning)
        doublesProtectReading?.takeIf { it.candidates > 0 }?.let { decisionTrace?.doublesProtect(it) }
        val lookahead = if (searchless) null else LocalRecursiveLookaheadEvaluator.evaluate(
            rootRanked,
            difficultyContext,
            decidingProfile,
            tuning,
            strategy = strategy,
            rootChoicePool = if (!tuning.revalidateRootChoicePool) null else { tentative ->
                val refined = LocalRootDecisionPolicy.refine(tentative, difficultyContext).ranked
                LocalWeightedActionSelector().shortlist(refined, mixingContext(refined))
                    .mapTo(linkedSetOf()) { it.outcome.candidate.actionId }
            },
            budget = budget,
            opponentThreatWeights = LocalOpponentThreat.weights(
                difficultyContext,
                profile.difficulty.tier,
                shouldContinue = { System.nanoTime() - threatStartedAtNanos < THREAT_TIME_LIMIT_NANOS },
            ),
            excludedActionIds = ruleExclusions(rootRanked.map { it.outcome.candidate }).keys,
            // Read from the same table as the rules; the AI's threat weights play no part in it.
            opponentIntents = opponentIntents,
            decisionSignature = if (actionSelector !is LocalWeightedActionSelector) null else { tentative ->
                val refined = LocalRootDecisionPolicy.refine(tentative, difficultyContext).ranked
                val tentativeSeed = LocalActionChoiceSeed.derive(
                    battleId = battleId,
                    turn = calculatedContext.state.turn,
                    ranked = refined,
                    perspectivePokemonIds = perspectivePokemonIds,
                )
                val tentativeSelection = actionSelector.choose(
                    refined,
                    tentativeSeed,
                    mixingContext(refined),
                )
                LocalLookaheadDecisionSignature(
                    topActionId = refined.first().outcome.candidate.actionId,
                    selectedActionId = tentativeSelection.rank.outcome.candidate.actionId,
                    shortlistSize = tentativeSelection.shortlistSize,
                )
            },
        )
        lookahead?.let { decisionTrace?.legacySearch(it, profile.difficulty.lookaheadPlies, budget) }
        val searchedRanked = lookahead?.ranked ?: rootRanked
        // The switching and mechanic rules' credits and debits, added to what the search made of each candidate.
        // An attack aimed at a Pokemon the opponent keeps switching out is priced against the one coming in.
        val expectedSwitches = when {
            !rulesApply -> emptyMap()
            // The predicted switches themselves, the repeats already folded in when they are read (singles, where
            // the switch model was fitted).
            tuning.predictedSwitchShare > 0.0 && (difficultyContext.state.format == jbro.cobblemon.mcc.internal.ai.BattleFormat.SINGLE ||
                tuning.doublesSwitchModel) ->
                opponentIntents.associate { intent ->
                    intent.pokemonId to intent.options.filter { it.kind == jbro.cobblemon.mcc.betterai.matchup.IntentKind.SWITCH && it.switchInId != null }
                        .groupBy { requireNotNull(it.switchInId) }.mapValues { (_, same) -> same.sumOf { it.probability } * tuning.predictedSwitchShare }
                }.filterValues { it.isNotEmpty() }
            readRepeats && active != null -> active.repeats.expectedSwitches(difficultyContext.state)
            else -> emptyMap()
        }
        val predictionAdjustments = if (expectedSwitches.isEmpty()) emptyMap() else {
            ruleScores?.let { scores ->
                jbro.cobblemon.mcc.betterai.matchup.LocalSwitchPrediction.adjustments(
                    difficultyContext.candidates, rulesContext, scores, expectedSwitches, tuning)
            }.orEmpty()
        }
        // A doubles Protect is credited for the hits the opponents are predicted to aim at its user.
        val protectCredits = if (!rulesApply) emptyMap() else ruleScores?.let { scores ->
            jbro.cobblemon.mcc.betterai.matchup.LocalProtectCredit.adjustments(
                difficultyContext.candidates, rulesContext, scores, opponentIntents, tuning)
        }.orEmpty()
        // A complete engine value replaces the predicted-damage credit for that exact joint action. Missing values keep
        // the credit, including doubles mid-battle reconstruction failures when no live Showdown capture is available.
        val protectAdjustments = mergeDoublesProtectCredits(protectCredits, doublesProtectReading?.values.orEmpty())
        val setupAdjustments = setupScores.mapNotNull { (id, value) ->
            searchedRanked.firstOrNull { it.outcome.candidate.actionId == id }?.let { id to tuning.setupSweepCredit * (value - it.comparisonValue) }
        }.toMap()
        // A debuff's ranking value becomes what it takes off, in knockout units (the setup score's scale).
        val debuffAdjustments = debuffReadings.mapNotNull { (id, reading) ->
            // A debuff that fails publicly (the target already has a status) keeps its failing rank.
            searchedRanked.firstOrNull { it.outcome.candidate.actionId == id }?.takeUnless { it.outcome.publiclyInert }?.let {
                id to (reading.gain * tuning.knockoutMaterialScore - it.comparisonValue)
            }
        }.toMap()
        // Trick Room's value becomes how much turning the speed order over changes both teams' matchups (LocalTrickRoomCredit).
        val trickRoomValue by lazy { ruleScores?.let { jbro.cobblemon.mcc.betterai.matchup.LocalTrickRoomCredit.value(rulesContext, it, tuning) } }
        val trickRoomAdjustments = if (!rulesApply) emptyMap() else searchedRanked.mapNotNull { rank ->
            val candidate = rank.outcome.candidate
            if (rank.outcome.publiclyInert || !jbro.cobblemon.mcc.betterai.matchup.LocalTrickRoomCredit.prices(candidate)) null
            else trickRoomValue?.let { candidate.actionId to (it - rank.comparisonValue) }
        }.toMap()
        // Perish Song is worth whoever cannot leave the field before the count ends (LocalPerishTrapCredit). A song with
        // Mega Evolution reads the Mega board (a Mega Gengar's Shadow Tag), one without it the board as it is.
        val perishTrapValues = if (!rulesApply) emptyMap() else ruleScores?.let { scores ->
            searchedRanked.mapNotNull { rank ->
                val candidate = rank.outcome.candidate
                if (rank.outcome.publiclyInert || !jbro.cobblemon.mcc.betterai.matchup.LocalPerishTrapCredit.prices(candidate)) return@mapNotNull null
                val board = if (candidate.mechanic != null) rulesContext else difficultyContext
                jbro.cobblemon.mcc.betterai.matchup.LocalPerishTrapCredit.value(candidate, board, scores, tuning)?.let { candidate.actionId to it }
            }.toMap()
        }.orEmpty()
        val perishTrapAdjustments = perishTrapValues.mapNotNull { (id, value) ->
            searchedRanked.firstOrNull { it.outcome.candidate.actionId == id }?.let { id to (value - it.comparisonValue) }
        }.toMap()
        // What the rules found worth playing among the status moves: the quick decider does not read staying for one as
        // staying for nothing.
        val stayCredits = perishTrapValues + trickRoomAdjustments.keys.mapNotNull { id -> trickRoomValue?.let { id to it } }
        // A Pokemon on its last Perish Song count faints this turn unless it leaves (LocalPerishCount).
        val perishAdjustments = if (!rulesApply) emptyMap() else
            jbro.cobblemon.mcc.betterai.matchup.LocalPerishCount.adjustments(difficultyContext.candidates, rulesContext, tuning)
        // A knockout is worth the Pokemon that falls, either side, against the board's mean (LocalKnockoutWorth).
        val knockoutWorthAdjustments = if (!rulesApply) emptyMap() else ruleScores?.let { scores ->
            jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth.adjustments(
                difficultyContext.candidates, difficultyContext, rulesContext.state, scores, tuning)
        }.orEmpty()
        // An attack that lowers its user's stats risks the drop against whoever faces it next (LocalSelfDropRisk).
        val selfDropAdjustments = if (!rulesApply) emptyMap() else ruleScores?.let { scores ->
            jbro.cobblemon.mcc.betterai.matchup.LocalSelfDropRisk.adjustments(
                difficultyContext.candidates, difficultyContext, rulesContext, scores, opponentIntents, tuning,
            ).mapValues { it.value.adjustment }
        }.orEmpty()
        val ruleAdjustments = (switchJudgement.adjustments.keys + gimmickAdjustments.keys + predictionAdjustments.keys +
            knockoutWorthAdjustments.keys + selfDropAdjustments.keys +
            setupAdjustments.keys + protectAdjustments.keys + healRaceAdjustments.keys + screenAdjustments.keys +
            hazardEncoreAdjustments.keys + debuffAdjustments.keys + nativeStatusAdjustments.keys + perishAdjustments.keys +
            trickRoomAdjustments.keys + perishTrapAdjustments.keys).associateWith {
            (switchJudgement.adjustments[it] ?: 0.0) + (gimmickAdjustments[it] ?: 0.0) + (predictionAdjustments[it] ?: 0.0) +
                (knockoutWorthAdjustments[it] ?: 0.0) + (selfDropAdjustments[it] ?: 0.0) +
                (setupAdjustments[it] ?: 0.0) + (protectAdjustments[it] ?: 0.0) + (healRaceAdjustments[it] ?: 0.0) +
                (screenAdjustments[it] ?: 0.0) + (hazardEncoreAdjustments[it] ?: 0.0) + (debuffAdjustments[it] ?: 0.0) +
                (nativeStatusAdjustments[it] ?: 0.0) + (perishAdjustments[it] ?: 0.0) + (trickRoomAdjustments[it] ?: 0.0) +
                (perishTrapAdjustments[it] ?: 0.0)
        }
        if (dynamaxGuardReading.targetFallbacks > 0) {
            decisionTrace?.dynamaxGuardFallback(
                dynamaxGuardReading.targetFallbacks,
                dynamaxGuardReading.targetAssessments,
            )
        }
        val ruleAdjusted = ruleAdjustments.takeIf { it.isNotEmpty() }?.let { adjustments ->
            LocalBattleActionPolicy.sort(searchedRanked.map { rank ->
                adjustments[rank.outcome.candidate.actionId]?.let { rank.copy(comparisonValue = rank.comparisonValue + it) } ?: rank
            })
        } ?: searchedRanked
        // Each kind of action's global weight (singles): a status move's value is shrunk toward worse by
        // statusMoveWeight, so it has to beat an attack by more before it is chosen.
        val switchAdjusted = if (difficultyContext.state.format != BattleFormat.SINGLE) ruleAdjusted
            else LocalActionKindWeights.apply(ruleAdjusted, tuning)
        // A certain flinch that goes first frees the turn: it is worth its hit plus the best other action, still there
        // next turn (LocalFreeTurnCredit). Read after every other value, since it is priced against them.
        val freeTurnAdjustments = if (!rulesApply) emptyMap() else jbro.cobblemon.mcc.betterai.matchup.LocalFreeTurnCredit.adjustments(
            switchAdjusted, difficultyContext, expectedSwitches, ruleExclusions(switchAdjusted.map { it.outcome.candidate }).keys, tuning)
        val freeTurnAdjusted = if (freeTurnAdjustments.isEmpty()) switchAdjusted else LocalBattleActionPolicy.sort(switchAdjusted.map { rank ->
            freeTurnAdjustments[rank.outcome.candidate.actionId]?.let { rank.copy(comparisonValue = rank.comparisonValue + it) } ?: rank
        })
        val rootDecision = LocalRootDecisionPolicy.refine(freeTurnAdjusted, difficultyContext)
        val ranked = rootDecision.ranked
        val seed = LocalActionChoiceSeed.derive(
            battleId = battleId,
            turn = calculatedContext.state.turn,
            ranked = ranked,
            perspectivePokemonIds = perspectivePokemonIds,
        )
        // Without the search, singles go through the quick decider on top of the ranking.
        val quickRuns = searchless && quickDecider && difficultyContext.state.format == BattleFormat.SINGLE
        val quickExcluded by lazy { ruleExclusions(ranked.map { it.outcome.candidate }).keys }
        fun quickVerdict(choice: LocalBattleActionRank, from: List<LocalBattleActionRank>, allow: (String) -> Boolean) = ruleScores?.let { scores ->
            jbro.cobblemon.mcc.betterai.matchup.LocalQuickDecider.decide(
                from, choice, rulesContext, scores, profile.difficulty.tier, quickExcluded, seed, predictionAdjustments,
                expectedSwitches, debuffReadings.mapValues { it.value.gain }, allow, ::setupView, stayCredits,
            )
        }
        // At the tiers with the rules its preferences are points before the draw, each candidate read as the one chosen:
        // the draw still decides, so a choice the ranking put well ahead keeps its place. A verdict against the choice
        // (a weak attack, a switch without a reason) takes the points off that choice; giving them to the strongest
        // attack instead credited it in nearly every position, since nearly every one offers a switch or a weaker move.
        // A verdict for an action (a switch for a reason, a heal in time) gives them to that action, once.
        val quickBonus: Map<String, List<String>> = if (!quickRuns || !rulesApply || tuning.quickVerdictBonus <= 0.0) emptyMap() else ranked
            .filter { it.outcome.candidate.actionId !in quickExcluded }
            .mapNotNull { rank ->
                quickVerdict(rank, ranked) {
                    it !in jbro.cobblemon.mcc.betterai.matchup.LocalQuickDecider.HARD &&
                        it != jbro.cobblemon.mcc.betterai.matchup.LocalQuickDecider.KNOCKOUT_IF_STAYS
                }?.let { verdict ->
                    if (verdict.reason in jbro.cobblemon.mcc.betterai.matchup.LocalQuickDecider.AGAINST_CHOSEN) {
                        rank.outcome.candidate.actionId to "-${verdict.reason}"
                    } else verdict.rank.outcome.candidate.actionId to "+${verdict.reason}"
                }
            }
            // A sure knockout the expected switch may dodge: the draw decides, the knockout ahead by the bonus.
            .plus(listOfNotNull(ranked.firstOrNull()?.let { first ->
                quickVerdict(first, ranked) { it == jbro.cobblemon.mcc.betterai.matchup.LocalQuickDecider.KNOCKOUT_IF_STAYS }
                    ?.let { it.rank.outcome.candidate.actionId to "+${it.reason}" }
            }))
            .groupBy({ it.first }, { it.second }).mapValues { (_, reasons) -> reasons.distinct() }
        val drawRanked = if (quickBonus.isEmpty()) ranked else LocalBattleActionPolicy.sort(ranked.map { rank ->
            quickBonus[rank.outcome.candidate.actionId]?.let { reasons ->
                val sign = (if (reasons.any { it.startsWith("+") }) 1 else 0) - (if (reasons.any { it.startsWith("-") }) 1 else 0)
                rank.copy(comparisonValue = rank.comparisonValue + sign * tuning.quickVerdictBonus)
            } ?: rank
        })
        val selection = actionSelector.choose(
            drawRanked,
            seed,
            mixingContext(drawRanked),
        )
        // Its hard verdicts (a sure knockout, the senseless choices) replace the draw. Below Advanced only its blunder
        // checks: a lower tier may play weaker, not senselessly.
        val quick = if (!quickRuns) null else if (rulesApply) {
            quickVerdict(selection.rank, drawRanked) { it in jbro.cobblemon.mcc.betterai.matchup.LocalQuickDecider.HARD }
        } else ruleScores?.let { scores ->
            jbro.cobblemon.mcc.betterai.matchup.LocalQuickDecider.blunders(drawRanked, selection.rank, rulesContext, scores)
        }
        val drawn = quick?.rank ?: selection.rank
        // The draw chooses between actions, not between a move with its mechanic and the same move without: drawn
        // without it, the move takes its mechanic twin (Mega Evolution, Terastallization) when the ranking scored that
        // one higher. A 6% draw once held back a Mega Garchomp whose Mega Earthquake ranked first by 70 points.
        // Compared before the quick decider's bonus, which goes to the move without its mechanic as much as with it.
        val twin = mechanicTwin(ranked.firstOrNull { it.outcome.candidate.actionId == drawn.outcome.candidate.actionId } ?: drawn,
            ranked, ruleExclusions(ranked.map { it.outcome.candidate }).keys)
        val selected = twin ?: drawn
        stageTrace?.let { sink ->
            fun values(list: List<LocalBattleActionRank>) = list.associate { it.outcome.candidate.actionId to it.comparisonValue }
            sink(LocalDecisionStages(
                turn = difficultyContext.state.turn,
                base = values(baseRanked),
                tiers = ranked.associate { it.outcome.candidate.actionId to it.decisionTier },
                nativeStatus = nativeStatusAdjustments,
                nativeStatusStatus = statusPricing?.status,
                rules = mapOf("switch" to switchJudgement.adjustments, "gimmick" to gimmickAdjustments,
                    "prediction" to predictionAdjustments, "setup" to setupAdjustments, "protect" to protectAdjustments,
                    "healRace" to healRaceAdjustments, "screen" to screenAdjustments,
                    "hazardEncore" to hazardEncoreAdjustments, "debuff" to debuffAdjustments,
                    "freeTurn" to freeTurnAdjustments, "perish" to perishAdjustments,
                    "trickRoom" to trickRoomAdjustments, "perishTrap" to perishTrapAdjustments,
                    "knockoutWorth" to knockoutWorthAdjustments, "selfDrop" to selfDropAdjustments).filterValues { it.isNotEmpty() },
                afterRules = values(ruleAdjusted),
                afterKindWeights = values(switchAdjusted),
                refined = values(ranked),
                ruleExclusions = ruleExclusions(ranked.map { it.outcome.candidate }).mapValues { (id, reason) ->
                    setupGateVerdicts[id]?.failures?.takeIf { it.isNotEmpty() }?.let { "$reason$it" } ?: reason
                },
                drawProbabilities = selection.probabilitiesByActionId,
                drawExclusions = selection.exclusionsByActionId,
                drawn = drawn.outcome.candidate.actionId,
                quickBonus = quickBonus,
                quick = quick?.rank?.outcome?.candidate?.actionId,
                quickReason = quick?.reason,
                selected = selected.outcome.candidate.actionId,
            ))
        }
        decisionTrace?.resolved("legacy_lookahead", drawRanked, selection)
        retainedNativeState?.let { active?.nativeProductState = it.withPendingOwnAction(selected.outcome.candidate) }
        // Worlds to replay this turn on at the next decision, one per ability the opposing Pokemon in front may have.
        val worldsStarted = System.nanoTime()
        if (searchless && active != null) active.abilityWorlds = try {
            jbro.cobblemon.mcc.betterai.search.LocalAbilityPosterior.open(difficultyContext, profile.difficulty.tier, selected.outcome.candidate)
        } catch (e: RuntimeException) {
            logger.error("[BetterAI] The engine could not build the worlds to replay this turn on", e)
            null
        }
        val worldsMillis = (System.nanoTime() - worldsStarted) / 1_000_000L
        val confidence = (0.35 + selection.probability * 0.6).coerceIn(0.35, 0.99)
        return CompletableFuture.completedFuture(
            BattleDecision(
                requestId = context.requestId,
                actionId = selected.outcome.candidate.actionId,
                confidence = confidence,
                advice = LocalBattleMind.advice(selected, difficultyContext, strategy, profile),
                tags = buildSet {
                    addAll(setOf(
                    "local_tactical_v4",
                    "tuning_${tuning.id}",
                    "mixed_top40",
                    "contextual_human_mix",
                    "persistent_intent",
                    "evidence_gated_mixup",
                    "position_risk_budget",
                    "choice_pool_${selection.shortlistSize}",
                    "choice_seed_${selection.seed.toULong().toString(16)}",
                    "difficulty_${profile.difficulty.tier.name.lowercase()}",
                     ))
                    quick?.let { add("quick_${it.reason}") }
                    quickBonus.values.flatten().distinct().forEach { add("quick_${if (it.startsWith("-")) "minus" else "plus"}_${it.drop(1)}") }
                    if (twin != null) add("mechanic_twin")
                    difficultyContext.state.inferences.filter {
                        it.categoryId == "ability" && it.confidence == jbro.cobblemon.mcc.internal.ai.BattleInferenceConfidence.RULED_OUT
                    }.forEach { add("ability_ruled_out_${it.candidateId}") }
                    active?.lastAbilityReplay?.let(::add)
                    active?.abilityWorlds?.let { add("ability_worlds_${it.abilities.size}_ms_$worldsMillis") }
                    statusPricing?.let {
                        add("native_status_${it.status}")
                        add("native_status_ms_${it.elapsedMillis}")
                        if (it.plies > 0) add("native_status_plies_${it.plies}")
                        if (it.selfHarm.isNotEmpty()) add("native_status_self_harm_${it.selfHarm.size}")
                    }
                    doublesProtectReading?.takeIf { it.candidates > 0 }?.let {
                        add("doubles_protect_${it.status.lowercase(Locale.ROOT)}")
                        add("doubles_protect_ms_${it.elapsedMillis}")
                        add("doubles_protect_values_${it.values.size}")
                    }
                    if (lookahead == null) add("searchless") else {
                        // Doubles searches one turn whatever the tier asks for.
                        add("lookahead_requested_${if (tuning.doublesSingleTurn && context.state.format == BattleFormat.DOUBLE) 1
                            else profile.difficulty.lookaheadPlies}")
                        add("lookahead_turns_${lookahead.depthCompleted}")
                        add("lookahead_nodes_${lookahead.nodesVisited}")
                        add("lookahead_pruned_${lookahead.branchesPruned}")
                        add("lookahead_coverage_${(lookahead.publicResponseCoverage * 100).roundToInt()}")
                        add("lookahead_stop_${lookahead.terminationReason.name.lowercase(Locale.ROOT)}")
                        add("lookahead_elapsed_ms_${lookahead.elapsedMillis}")
                        add("lookahead_accepted_ms_${lookahead.acceptedDepthMillis}")
                        add("lookahead_accepted_nodes_${lookahead.acceptedDepthNodes}")
                        add("lookahead_partial_depth_candidates_${lookahead.partialDepthCandidates}")
                        if (lookahead.truncated) add("lookahead_truncated")
                        if (lookahead.publicResponseIncomplete) add("lookahead_public_response_incomplete")
                    }
                    nativeFallbackStatus?.let { add("native_fallback_${it.name.lowercase(Locale.ROOT)}") }
                    if (retainedNativeState != null) add("native_session_retained")
                    addAll(decisionDiagnostics(calculatedContext, selected))
                    rootDecision.switchReasonsByActionId[selected.outcome.candidate.actionId]
                        .orEmpty()
                        .forEach { add("switch_reason_${it.name.lowercase()}") }
                    rootDecision.switchVetoes.forEach { add("switch_veto_${it.name.lowercase()}") }
                },
            ),
        )
    }

    override fun closeSession(session: BattleBrainSession, result: BattleBrainCloseResult) {
        (session as? Session)?.nativeProductState = null
    }

    override fun chooseLeads(context: BattleLeadChoiceContext): List<UUID>? {
        val choice = LocalLeadChoice.choose(context) ?: return null
        val species = context.ownTeam.associate { it.battlePokemonId to it.speciesId.substringAfter(':') }
        logger.info(
            "[BetterAI Lead] persona={} tier={} leads={} scores={}",
            context.trainerPersonaId,
            context.trainerProfile.difficulty.tier,
            choice.leads.map { species[it] },
            choice.scores.entries.joinToString(prefix = "{", postfix = "}") { (id, score) ->
                "${species[id]}=${String.format(Locale.ROOT, "%.2f", score)}"
            },
        )
        return choice.leads
    }

    /** At most two top-ranked partner actions per protecting slot: four joint candidates in an ordinary double. */
    private fun doublesProtectTurnAdjustments(
        ranked: List<LocalBattleActionRank>,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        intents: List<jbro.cobblemon.mcc.betterai.matchup.OpponentIntent>,
        tuning: LocalDecisionTuning,
    ): jbro.cobblemon.mcc.betterai.search.LocalEngineTurnReadout.DoublesProtectReading {
        val bySlot = linkedMapOf<Int, MutableList<BattleActionCandidate>>()
        for (rank in ranked) {
            val candidate = rank.outcome.candidate
            if (candidate.kind != BattleActionKind.COMPOSITE) continue
            val protects = candidate.componentActions.filter { part ->
                part.kind == BattleActionKind.USE_MOVE && part.mechanic == null &&
                    jbro.cobblemon.mcc.betterai.mechanics.LocalStallingProtectionRules.isStallingProtection(part) &&
                    jbro.cobblemon.mcc.betterai.mechanics.LocalStallingProtectionRules.consecutiveSuccessfulUses(
                        context.state, BattleSide.ALLY, part.actorSlot,
                    ) == 0
            }
            if (protects.size != 1) continue
            val slot = protects.single().actorSlot ?: continue
            val user = context.state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.activeSlot == slot && !it.fainted
            }
            if (jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(user)) continue
            val selected = bySlot.getOrPut(slot) { mutableListOf() }
            if (selected.size < DOUBLES_PROTECT_PARTNERS) selected += candidate
        }
        val candidates = bySlot.values.flatten().distinctBy(BattleActionCandidate::actionId)
        if (candidates.isEmpty()) return jbro.cobblemon.mcc.betterai.search.LocalEngineTurnReadout.DoublesProtectReading(
            emptyMap(), 0, 0, 0, 0, 0L, "NO_CANDIDATES",
        )
        return try {
            jbro.cobblemon.mcc.betterai.search.LocalEngineTurnReadout.doublesProtectTurns(
                context, profile.difficulty.tier, candidates, intents, tuning,
            )
        } catch (e: RuntimeException) {
            logger.error("[BetterAI] Doubles Protect turns could not be played out; they keep LocalProtectCredit", e)
            jbro.cobblemon.mcc.betterai.search.LocalEngineTurnReadout.DoublesProtectReading(
                emptyMap(), candidates.size, 0, 0, 0, 0L, "FAILED",
            )
        }
    }

    /**
     * Each singles Protect's turn as the engine plays it against the opponent's likely replies, less the turn's end with
     * the opponent waiting that its ranking value already holds, in score units.
     * A Max Guard and a Dynamaxed user's move stay out: the engine does not read them under their own IDs.
     */
    private fun protectTurnAdjustments(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
    ): Map<String, Double> {
        val user = context.state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted }
        if (jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(user)) return emptyMap()
        // Only a Protect sure to work: consecutive-use success itself is stateful, not an accuracy roll to average.
        val protects = context.candidates.filter {
            it.kind == BattleActionKind.USE_MOVE && it.mechanic == null &&
                jbro.cobblemon.mcc.betterai.mechanics.LocalStallingProtectionRules.isStallingProtection(it) &&
                jbro.cobblemon.mcc.betterai.mechanics.LocalStallingProtectionRules.consecutiveSuccessfulUses(
                    context.state, BattleSide.ALLY, it.actorSlot) == 0
        }
        if (protects.isEmpty()) return emptyMap()
        val engine = try {
            val intents = LocalOpponentIntentPredictor.predict(context, jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.calculate(context), scoredSwitches = true)
            jbro.cobblemon.mcc.betterai.search.LocalEngineTurnReadout.protectTurns(context, profile.difficulty.tier, protects, intents, tuning)
        } catch (e: RuntimeException) {
            logger.error("[BetterAI] Protect turns could not be played out in the engine; they keep the waiting reading", e)
            return emptyMap()
        }
        return protects.mapNotNull { protect ->
            val played = engine[protect.actionId] ?: return@mapNotNull null
            val waiting = jbro.cobblemon.mcc.betterai.evaluation.LocalSinglesProtectReturn.turnEnd(protect, context, tuning)?.first
                ?: return@mapNotNull null
            protect.actionId to tuning.board(played - waiting)
        }.toMap()
    }

    private fun mechanicTwin(
        drawn: LocalBattleActionRank,
        ranked: List<LocalBattleActionRank>,
        excluded: Set<String>,
    ): LocalBattleActionRank? {
        val move = drawn.outcome.candidate
        if (move.kind != BattleActionKind.USE_MOVE || move.mechanic != null) return null
        return ranked.asSequence().filter { rank ->
            val other = rank.outcome.candidate
            other.kind == BattleActionKind.USE_MOVE && other.mechanic != null && other.actionId !in excluded &&
                other.actorSlot == move.actorSlot && other.moveSlot == move.moveSlot && other.targets == move.targets &&
                (rank.comparisonValue > drawn.comparisonValue || isMega(other) && rank.comparisonValue >= drawn.comparisonValue - MEGA_TIE_TOLERANCE)
        }.maxByOrNull { it.comparisonValue }
    }

    /**
     * Mega Evolution costs nothing ([jbro.cobblemon.mcc.betterai.evaluation.LocalMechanicOptionValue]) and its stats
     * stay for the battle, so a tie goes to it. A move capped at its knockout value scored the same with it as without
     * (Tyranitar's Earthquake at 297 either way) and half the draws kept the Pokemon un-Evolved: Roark's Tyranitar set
     * up and fell without Mega Evolving (user feedback and decision, 2026-10-18; G-706). A twin scored lower stays out;
     * "equal" allows for rounding, as the two values summed in another order came out 5e-14 apart.
     */
    private fun isMega(candidate: BattleActionCandidate): Boolean =
        candidate.mechanic?.mechanicId?.let(jbro.cobblemon.mcc.internal.ai.PublicIds::canonical) in MEGA_MECHANIC_IDS

    private fun decisionDiagnostics(
        calculatedContext: BattleDecisionContext,
        selected: LocalBattleActionRank,
    ): Set<String> = buildSet {
        // What the trainer could see of the Pokemon in front of it. Without the defender's public
        // types a weak-looking choice can be blindness rather than a bad evaluation, so preserve the
        // distinction on native and legacy decisions alike.
        val opponentActive = calculatedContext.state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted
        }
        when {
            opponentActive.isEmpty() -> add("opponent_active_absent")
            opponentActive.any { it.knownTypeIds.isEmpty() } -> add("opponent_types_unknown")
            else -> add("opponent_types_known")
        }
        if (opponentActive.any { it.combatStats == null }) add("opponent_stats_unknown")
        selected.outcome.candidate.let { chosen ->
            add("chose_${chosen.kind.name.lowercase()}")
            chosen.moveDetails?.typeId?.let { add("chose_type_$it") }
        }
        val chosenFacts = calculatedContext.candidates
            .firstOrNull { it.actionId == selected.outcome.candidate.actionId }?.facts
        chosenFacts?.typeChartMultiplier
            ?.let { add("chose_type_multiplier_${(it * 100).roundToInt()}") }

        // Record the public one-turn damage comparison only as diagnostics. It never feeds the
        // native rank back into the handmade projector.
        val damaging = calculatedContext.candidates.filter {
            it.moveDetails?.damageCategory != null &&
                it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS
        }
        fun expectedDamage(candidate: BattleActionCandidate): Double =
            candidate.facts?.standardDamageFractionRange
                ?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0
        val chosenCandidate = calculatedContext.candidates
            .firstOrNull { it.actionId == selected.outcome.candidate.actionId }
        val strongest = damaging.maxByOrNull(::expectedDamage)
        if (chosenCandidate != null && chosenCandidate in damaging && strongest != null &&
            expectedDamage(strongest) > expectedDamage(chosenCandidate) + WEAKER_CHOICE_MARGIN
        ) {
            add("weaker_attack_chosen")
            calculatedContext.candidates.forEach { candidate ->
                val moveType = candidate.moveDetails?.typeId ?: return@forEach
                val name = candidate.moveId?.substringAfter(':') ?: candidate.actionId
                val multiplier = candidate.facts?.typeChartMultiplier
                    ?.let { (it * 100).roundToInt().toString() } ?: "none"
                val damage = (expectedDamage(candidate) * 1000).roundToInt()
                add("cand_${name}_${moveType}_x${multiplier}_dmg$damage")
            }
        }
    }

    private class Session(
        override val sessionId: UUID,
        val battleId: UUID,
        val trainerPersonaId: String?,
        val strategy: BattleStrategyBrief?,
        val trainerProfile: BattleTrainerProfile,
        var nativeProductState: NativeProductSessionState? = null,
    ) : BattleBrainSession {
        /** The AI's ace scores ([LocalAceScore]) and the opponents seen when they were worked out. */
        @Volatile var aceScores: Map<UUID, Double> = emptyMap()
        @Volatile var aceOpponentIds: Set<UUID> = emptySet()
        /** What the opponent did in each matchup so far; see [LocalOpponentRepeats]. */
        val repeats = jbro.cobblemon.mcc.betterai.matchup.LocalOpponentRepeats()
        /** Each opposing Pokemon's entries already read for silent abilities, by its latest switch event. */
        val checkedEntries: MutableSet<Pair<UUID, Long>> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** The abilities each opposing Pokemon's silent entries and played turns ruled out ([withSilentEntries]). */
        val silentAbilities = java.util.concurrent.ConcurrentHashMap<UUID, Set<String>>()
        /** The last decision's worlds, one per ability of the opposing Pokemon in front, to replay the turn on. */
        @Volatile var abilityWorlds: jbro.cobblemon.mcc.betterai.search.LocalAbilityPosterior.Pending? = null
        /** Opposing Pokemon that left the field, with what leaving left them under each ability, until they come back. */
        val departures = java.util.concurrent.ConcurrentHashMap<UUID, jbro.cobblemon.mcc.betterai.search.LocalAbilityPosterior.Departure>()
        /** Diagnostics for the decision's tags: what the last replay took and ruled out. */
        @Volatile var lastAbilityReplay: String? = null
    }

    /**
     * The decision's state with the abilities opposing Pokemon cannot have, because they entered without the message
     * those abilities give on entry (LocalEntryAbilityCheck), added as ruled-out inferences: every reader of the
     * candidate abilities (LocalEntryAbilityProjector.publicPossibleAbilities, the engine's worlds) leaves them out.
     * Read once per entry, at the first decision after it, on the board it entered on.
     */
    private fun withSilentEntries(session: Session, context: BattleDecisionContext, tier: BattleTrainerTier): BattleDecisionContext {
        val state = context.state
        // The turn just played, replayed on the last decision's worlds (LocalAbilityPosterior).
        session.lastAbilityReplay = null
        session.abilityWorlds?.let { pending ->
            session.abilityWorlds = null
            val started = System.nanoTime()
            val reading = try {
                jbro.cobblemon.mcc.betterai.search.LocalAbilityPosterior.read(pending, context)
            } catch (e: RuntimeException) {
                logger.error("[BetterAI] The engine could not replay the last turn; the opponent's abilities stay as they are", e)
                null
            }
            val ruled = reading?.ruledOut
            ruled?.takeIf { it.isNotEmpty() }?.let { abilities -> session.silentAbilities.merge(pending.subject, abilities) { a, b -> a + b } }
            reading?.departure?.let { session.departures[it.subject] = it }
            session.lastAbilityReplay = "ability_replay_ms_${(System.nanoTime() - started) / 1_000_000L}_" +
                (ruled?.joinToString("_").takeUnless { it.isNullOrEmpty() }?.let { "ruled_$it" } ?: if (ruled == null) "unread" else "none") +
                "_" + (reading?.status ?: "error") + (reading?.detail?.let { "_$it" } ?: "")
        }
        fun entry(id: UUID) = id to (state.observedEvents.lastOrNull {
            it.kind == jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind.SWITCHED && it.actorPokemonId == id
        }?.sequence ?: -1L)
        // A Pokemon back from the bench, against what leaving left it with (Regenerator's heal).
        session.departures.values.toList().forEach { departure ->
            val ruled = try {
                jbro.cobblemon.mcc.betterai.search.LocalAbilityPosterior.arrival(departure, context)
            } catch (e: RuntimeException) {
                logger.error("[BetterAI] The opponent's return could not be read; its abilities stay as they are", e)
                emptySet()
            } ?: return@forEach
            session.departures.remove(departure.subject)
            if (ruled.isNotEmpty()) session.silentAbilities.merge(departure.subject, ruled) { a, b -> a + b }
            session.lastAbilityReplay = (session.lastAbilityReplay?.let { "${it}_" } ?: "") + "ability_return_" +
                (ruled.joinToString("_").ifEmpty { "none" })
        }
        // No switch offered: whether the opponent's ability is what holds this side in.
        try {
            jbro.cobblemon.mcc.betterai.search.LocalEntryAbilityCheck.trapRuledOut(context, tier)
        } catch (e: RuntimeException) {
            logger.error("[BetterAI] The engine could not read the trap; the opponent's abilities stay as they are", e)
            null
        }?.let { (id, ruled) ->
            if (ruled.isNotEmpty()) session.silentAbilities.merge(id, ruled) { a, b -> a + b }
            session.lastAbilityReplay = (session.lastAbilityReplay?.let { "${it}_" } ?: "") + "ability_trapped_" + (ruled.joinToString("_").ifEmpty { "none" })
        }
        val due = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
            it.knownAbilityId == null && entry(it.battlePokemonId) !in session.checkedEntries }
        if (due.isNotEmpty()) {
            val ruled = try {
                jbro.cobblemon.mcc.betterai.search.LocalEntryAbilityCheck.ruledOut(context, tier, due.mapTo(hashSetOf()) { it.battlePokemonId })
            } catch (e: RuntimeException) {
                logger.error("[BetterAI] The engine could not replay the opponent's entry; its abilities stay as they are", e)
                null
            }
            if (ruled != null) {
                due.forEach { session.checkedEntries += entry(it.battlePokemonId) }
                ruled.forEach { (id, abilities) -> session.silentAbilities.merge(id, abilities) { a, b -> a + b } }
            }
        }
        val present = state.inferences.filter { it.confidence == jbro.cobblemon.mcc.internal.ai.BattleInferenceConfidence.RULED_OUT }
            .mapTo(hashSetOf()) { it.subjectPokemonId to it.candidateId }
        val extra = session.silentAbilities.flatMap { (id, abilities) ->
            abilities.filter { (id to it) !in present && state.pokemon.any { pokemon -> pokemon.battlePokemonId == id && pokemon.knownAbilityId == null } }
                .map { jbro.cobblemon.mcc.internal.ai.BattleInferenceView(id, "ability", it, jbro.cobblemon.mcc.internal.ai.BattleInferenceConfidence.RULED_OUT) }
        }
        if (extra.isEmpty()) return context
        return context.copy(state = jbro.cobblemon.mcc.internal.ai.BattleStateView(state.battleId, state.format, state.turn, state.pokemon,
            state.field, state.remainingPokemonBySide, state.observedEvents, state.inferences + extra))
    }

    /**
     * An opposing Pokemon that can only have one ability - its species has one, or the others were ruled out -
     * is read as having revealed it, so the one-turn scoring (which reads only known abilities) sees it too.
     * The engine's worlds already hold it (2026-10-13, user decision).
     */
    private fun withSettledAbilities(context: BattleDecisionContext): BattleDecisionContext {
        val state = context.state
        val settled = state.pokemon.associate { pokemon ->
            pokemon.battlePokemonId to (if (pokemon.side != BattleSide.OPPONENT || pokemon.knownAbilityId != null) null
            else jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector.publicPossibleAbilities(state, pokemon).singleOrNull())
        }.filterValues { it != null }
        if (settled.isEmpty()) return context
        return context.copy(state = state.copyState(pokemon = state.pokemon.map { pokemon ->
            settled[pokemon.battlePokemonId]?.let { pokemon.copyState(knownAbilityId = it) } ?: pokemon
        }))
    }

    private fun BattleDecisionContext.withoutActivePlan(): BattleDecisionContext = copy(
        memory = BattleTacticalMemoryView(
            activePlan = null,
            activePlanOwner = null,
            tendencies = memory.tendencies,
            predictionCalibration = memory.predictionCalibration,
            turnsSinceLastSwitch = memory.turnsSinceLastSwitch,
            switchesThisBattle = memory.switchesThisBattle,
            switchPressure = memory.switchPressure,
            lastMoveId = memory.lastMoveId,
            sameMoveRepeatCount = memory.sameMoveRepeatCount,
            patternExposureCount = memory.patternExposureCount,
            patternResponseShiftEvidence = memory.patternResponseShiftEvidence,
            opponentResponseVolatility = memory.opponentResponseVolatility,
            nonProgressControlStreak = memory.nonProgressControlStreak,
        ),
    )
}

internal fun mergeDoublesProtectCredits(
    credits: Map<String, Double>,
    engineValues: Map<String, Double>,
): Map<String, Double> = credits + engineValues
