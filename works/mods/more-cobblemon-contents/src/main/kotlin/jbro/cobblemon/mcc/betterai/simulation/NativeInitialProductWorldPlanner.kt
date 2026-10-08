package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.abs
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleExactOwnTeamView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.state.LocalMoveUsageLookup
import jbro.cobblemon.mcc.betterai.state.LocalOpponentBuildUsage
import jbro.cobblemon.mcc.betterai.state.LocalOpponentBuildUsageLookup
import jbro.cobblemon.mcc.betterai.state.LocalOpponentMoveUsage

internal enum class NativeInitialProductWorldPlanIssueCode {
    OPPONENT_PREVIEW_MISSING,
    EXACT_OWN_TEAM_MISSING,
    PUBLIC_SPECIES_IDENTITY_MISSING,
    PUBLIC_SPECIES_IDENTITY_CONFLICT,
    ROSTER_COMPILATION_FAILED,
    ROSTER_MATERIALIZATION_FAILED,
    MOVE_CATALOG_MATERIALIZATION_FAILED,
    BUILD_WORLD_COMPILATION_FAILED,
    INITIAL_WORLD_ASSEMBLY_FAILED,
    WORLD_PRIOR_INVALID,
    BATTLE_DEFINITION_COMPILATION_FAILED,
}

internal data class NativeInitialProductWorldPlanIssue(
    val code: NativeInitialProductWorldPlanIssueCode,
    val hypothesisId: String? = null,
    val detailCode: String? = null,
)

/** One retained, normalized world with the public source context used to evaluate its leaves. */
internal data class NativeInitialProductWorld(
    val hypothesisId: String,
    val probability: Double,
    val definition: NativeBattleDefinition,
    val publicContext: BattleDecisionContext,
) {
    init {
        require(hypothesisId.isNotBlank())
        require(probability.isFinite() && probability > 0.0 && probability <= 1.0)
        val definitionIds = (definition.p1Team + definition.p2Team).mapTo(linkedSetOf()) {
            UUID.fromString(it.uuid)
        }
        require(definitionIds == publicContext.state.pokemon.mapTo(linkedSetOf()) { it.battlePokemonId }) {
            "A planned native definition must cover the same public Pokemon identities"
        }
    }
}

internal data class NativeInitialProductWorldPlan(
    val worlds: List<NativeInitialProductWorld>,
    val issues: List<NativeInitialProductWorldPlanIssue>,
) {
    init {
        require(worlds.isEmpty() != issues.isEmpty()) {
            "An initial native product plan must contain normalized worlds or explicit issues"
        }
        if (worlds.isNotEmpty()) {
            require(worlds.map(NativeInitialProductWorld::hypothesisId).distinct().size == worlds.size)
            require(abs(worlds.sumOf(NativeInitialProductWorld::probability) - 1.0) <= NORMALIZATION_EPSILON)
        }
    }

    private companion object {
        const val NORMALIZATION_EPSILON = 1e-9
    }
}

/**
 * Compiles the complete opening posterior consumed by product-native search.
 *
 * Every selected-roster branch must materialize. A failed branch invalidates the plan rather than
 * deleting its probability mass. Only after all branches are complete is the joint posterior
 * bounded and renormalized.
 */
internal class NativeInitialProductWorldPlanner(
    private val moveUsageForFormat: (BattleFormat) -> LocalMoveUsageLookup? =
        LocalOpponentMoveUsage::forFormat,
    private val buildUsageForFormat: (BattleFormat) -> LocalOpponentBuildUsageLookup =
        LocalOpponentBuildUsage::forFormat,
    private val worldLimit: (BattleTrainerTier, Int) -> Int = ::defaultWorldLimit,
) {
    /** Rebuilds the posterior from a mid-battle public board ([NativeMidBattleStateRules]) instead of the opening. */
    fun planMidBattle(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
    ): NativeInitialProductWorldPlan = plan(context, tier, midBattle = true)

    fun plan(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
        midBattle: Boolean = false,
    ): NativeInitialProductWorldPlan {
        val preview = context.opponentTeamPreview ?: return failure(
            NativeInitialProductWorldPlanIssueCode.OPPONENT_PREVIEW_MISSING,
        )
        val exactOwnTeam = context.exactOwnTeam ?: return failure(
            NativeInitialProductWorldPlanIssueCode.EXACT_OWN_TEAM_MISSING,
        )
        val identities = publicIdentityResolver(context, preview, exactOwnTeam)
        if (identities.issues.isNotEmpty()) return NativeInitialProductWorldPlan(emptyList(), identities.issues)

        val rosterCompilation = NativeOpponentRosterHypothesisCompiler.compile(context.state, preview, midBattle = midBattle)
        if (rosterCompilation.issues.isNotEmpty()) {
            return failure(
                NativeInitialProductWorldPlanIssueCode.ROSTER_COMPILATION_FAILED,
                detailCodes = rosterCompilation.issues.map { it.code.name },
            )
        }

        val limit = worldLimit(tier, preview.selectionSize)
        val candidates = PriorityQueue<PendingWorld>(minOf(maxOf(1, limit), 64), WORST_FIRST)
        val seenIds = hashSetOf<String>()
        var duplicateId = false
        var candidateCount = 0
        var priorMass = 0.0
        rosterCompilation.hypotheses.forEach { rosterHypothesis ->
            val materialization = NativeOpponentRosterStateMaterializer.materialize(
                context.state,
                preview,
                rosterHypothesis,
                midBattle = midBattle,
                resolveShowdownSpecies = identities.resolve,
            )
            val roster = materialization.roster ?: return failure(
                NativeInitialProductWorldPlanIssueCode.ROSTER_MATERIALIZATION_FAILED,
                rosterHypothesis.hypothesisId,
                materialization.issues.map { it.code.name },
            )
            val moveMaterialization = NativeOpponentPreviewMoveCatalogMaterializer.materialize(
                roster,
                preview,
                context.publicActionCatalog,
                tier,
                moveUsageForFormat(context.state.format),
            )
            if (moveMaterialization.issues.isNotEmpty()) return failure(
                NativeInitialProductWorldPlanIssueCode.MOVE_CATALOG_MATERIALIZATION_FAILED,
                rosterHypothesis.hypothesisId,
                moveMaterialization.issues.map { issue ->
                    issue.code.name + "@" + speciesOf(roster.state, issue.battlePokemonId)
                },
            )
            // A revealed ability or item fixes that slot's; a world with another one would contradict the native
            // frame and fail the whole search. The first reveal is the set's: later ones may be copied (Trace) or
            // swapped (Trick). Copied abilities carry their source.
            fun firstRevealedBySlot(kind: BattleObservedEventKind) = context.state.observedEvents.asSequence()
                .filter { it.kind == kind && it.publicSourceEffectId == null }
                .mapNotNull { event ->
                    val slot = event.actorPokemonId?.let(roster.opponentPreviewSlotByPokemonId::get)
                    val value = event.publicValueId?.let(PublicIds::canonical)
                    if (slot == null || value.isNullOrEmpty()) null else slot to value
                }
                .distinctBy { it.first }
                .toMap()
            val buildCompilation = NativeOpponentPreviewBuildWorldCompiler.compile(
                preview,
                rosterHypothesis.selectedPreviewSlotIds,
                tier,
                buildUsageForFormat(context.state.format),
                context.localOpponentStatSpreads,
                firstRevealedBySlot(BattleObservedEventKind.ABILITY_REVEALED),
                firstRevealedBySlot(BattleObservedEventKind.HELD_ITEM_REVEALED),
            )
            if (buildCompilation.issues.isNotEmpty()) {
                return failure(
                    NativeInitialProductWorldPlanIssueCode.BUILD_WORLD_COMPILATION_FAILED,
                    rosterHypothesis.hypothesisId,
                    buildCompilation.issues.map { issue ->
                        issue.code.name + (issue.previewSlotId?.let { "@slot$it" } ?: "")
                    },
                )
            }
            moveMaterialization.worlds.forEach { moveWorld ->
                val prepared = NativeInitialBattleWorldAssembler.prepare(
                    roster, rosterHypothesis, exactOwnTeam, moveWorld.catalog,
                )
                buildCompilation.worlds.forEach { buildWorld ->
                    val assemblyIssues = NativeInitialBattleWorldAssembler.validationIssues(prepared, buildWorld)
                    if (assemblyIssues.isNotEmpty()) return failure(
                        NativeInitialProductWorldPlanIssueCode.INITIAL_WORLD_ASSEMBLY_FAILED,
                        rosterHypothesis.hypothesisId,
                        assemblyIssues.map { it.code.name },
                    )
                    val hypothesisId = NativeInitialBattleWorldAssembler.hypothesisId(prepared, buildWorld)
                    val probability = (rosterHypothesis.probability * buildWorld.probability) * moveWorld.probability
                    candidateCount++
                    priorMass += probability
                    if (!seenIds.add(hypothesisId)) duplicateId = true
                    val candidate = PendingWorld(
                        hypothesisId, probability, prepared, buildWorld, moveWorld,
                    )
                    if (limit > 0) {
                        if (candidates.size < limit) candidates.add(candidate)
                        else if (BEST_FIRST.compare(candidate, candidates.peek()) < 0) {
                            candidates.remove()
                            candidates.add(candidate)
                        }
                    }
                }
            }
        }

        val priorIssue = validatePrior(candidateCount, duplicateId, priorMass)
        if (priorIssue != null) return NativeInitialProductWorldPlan(emptyList(), listOf(priorIssue))
        if (limit <= 0) return failure(NativeInitialProductWorldPlanIssueCode.WORLD_PRIOR_INVALID)
        val retained = candidates.toList().sortedWith(BEST_FIRST)
        val retainedMass = retained.sumOf(PendingWorld::probability)
        if (!retainedMass.isFinite() || retainedMass <= 0.0) {
            return failure(NativeInitialProductWorldPlanIssueCode.WORLD_PRIOR_INVALID)
        }

        val worlds = retained.map { prepared ->
            val assembly = NativeInitialBattleWorldAssembler.assemblePrepared(
                prepared.preparation, prepared.buildWorld,
            )
            val assembled = assembly.world ?: return failure(
                NativeInitialProductWorldPlanIssueCode.INITIAL_WORLD_ASSEMBLY_FAILED,
                prepared.preparation.rosterHypothesis.hypothesisId,
                assembly.issues.map { it.code.name },
            )
            val roster = prepared.preparation.roster
            val catalogContext = context.copy(state = roster.state, publicActionCatalog = prepared.moveWorld.catalog)
            val probability = prepared.probability / retainedMass
            val compilation = NativeInitialBattleDefinitionCompiler.compile(
                state = roster.state,
                catalog = prepared.moveWorld.catalog,
                identities = roster.identities,
                world = assembled.copy(probability = probability),
                seed = NativeProductSeedPolicy.derive(
                    context.state.battleId,
                    prepared.hypothesisId,
                    randomSampleIndex = 0,
                ),
                midBattle = midBattle,
            )
            val definition = compilation.definition ?: return failure(
                NativeInitialProductWorldPlanIssueCode.BATTLE_DEFINITION_COMPILATION_FAILED,
                prepared.hypothesisId,
                compilation.issues.map { issue ->
                    issue.code.name + (issue.battlePokemonId?.let { "@" + speciesOf(roster.state, it) } ?: "")
                },
            )
            NativeInitialProductWorld(
                hypothesisId = prepared.hypothesisId,
                probability = probability,
                definition = definition,
                publicContext = catalogContext,
            )
        }
        return NativeInitialProductWorldPlan(worlds, emptyList())
    }

    private fun publicIdentityResolver(
        context: BattleDecisionContext,
        preview: BattleOpponentTeamPreviewView,
        exactOwnTeam: BattleExactOwnTeamView,
    ): IdentityResolverCompilation {
        val issues = mutableListOf<NativeInitialProductWorldPlanIssue>()
        val values = linkedMapOf<PublicSpeciesKey, String>()
        fun add(speciesId: String, formId: String?, showdownSpeciesId: String?, detail: String) {
            if (showdownSpeciesId.isNullOrBlank()) {
                issues += NativeInitialProductWorldPlanIssue(
                    NativeInitialProductWorldPlanIssueCode.PUBLIC_SPECIES_IDENTITY_MISSING,
                    detailCode = detail,
                )
                return
            }
            val key = PublicSpeciesKey(canonical(speciesId), canonical(formId.orEmpty()))
            // Use the preview/own-team species here, before a disguise can replace the
            // observed active species with a different public identity.
            val nativeId = nativeSpeciesId(speciesId, showdownSpeciesId)
            val previous = values.putIfAbsent(key, nativeId)
            if (previous != null && previous != nativeId) {
                issues += NativeInitialProductWorldPlanIssue(
                    NativeInitialProductWorldPlanIssueCode.PUBLIC_SPECIES_IDENTITY_CONFLICT,
                    detailCode = detail,
                )
            }
        }
        context.state.pokemon.asSequence().filter { it.side == BattleSide.ALLY }.forEach { pokemon ->
            val build = exactOwnTeam.buildFor(pokemon.battlePokemonId)
            add(
                pokemon.speciesId,
                pokemon.formId,
                build?.showdownSpeciesId,
                "ally:${pokemon.battlePokemonId}",
            )
        }
        preview.pokemon.forEach { pokemon ->
            add(
                pokemon.speciesId,
                pokemon.formId,
                pokemon.showdownSpeciesId,
                "preview:${pokemon.previewSlotId}",
            )
        }
        return IdentityResolverCompilation(
            resolve = { speciesId, formId ->
                values[PublicSpeciesKey(canonical(speciesId), canonical(formId.orEmpty()))]
            },
            issues = issues,
        )
    }

    private fun validatePrior(count: Int, duplicateId: Boolean, sum: Double): NativeInitialProductWorldPlanIssue? {
        if (count == 0 || duplicateId) {
            return NativeInitialProductWorldPlanIssue(NativeInitialProductWorldPlanIssueCode.WORLD_PRIOR_INVALID)
        }
        return if (!sum.isFinite() || abs(sum - 1.0) > NORMALIZATION_EPSILON) {
            NativeInitialProductWorldPlanIssue(NativeInitialProductWorldPlanIssueCode.WORLD_PRIOR_INVALID)
        } else {
            null
        }
    }

    /** Names the Pokemon behind a detail code, so a server log says which one stopped the plan. */
    private fun speciesOf(state: BattleStateView, pokemonId: java.util.UUID): String =
        state.pokemon.firstOrNull { it.battlePokemonId == pokemonId }
            ?.let { "${it.side.name.lowercase()}:${PublicIds.canonical(it.speciesId)}" } ?: "unknown"

    private fun failure(
        code: NativeInitialProductWorldPlanIssueCode,
        hypothesisId: String? = null,
        detailCodes: List<String> = emptyList(),
    ): NativeInitialProductWorldPlan = NativeInitialProductWorldPlan(
        worlds = emptyList(),
        issues = detailCodes.distinct().ifEmpty { listOf(null) }.map { detail ->
            NativeInitialProductWorldPlanIssue(code, hypothesisId, detail)
        },
    )

    private data class PendingWorld(
        val hypothesisId: String,
        val probability: Double,
        val preparation: NativeInitialBattleWorldAssembler.Preparation,
        val buildWorld: NativeOpponentPreviewBuildWorld,
        val moveWorld: NativeOpponentPreviewMoveCatalogWorld,
    )

    private data class PublicSpeciesKey(val speciesId: String, val formId: String)

    private data class IdentityResolverCompilation(
        val resolve: (String, String?) -> String?,
        val issues: List<NativeInitialProductWorldPlanIssue>,
    )

    private companion object {
        const val NORMALIZATION_EPSILON = 1e-9
        val BEST_FIRST = compareByDescending<PendingWorld> { it.probability }.thenBy { it.hypothesisId }
        val WORST_FIRST = BEST_FIRST.reversed()

        fun defaultWorldLimit(tier: BattleTrainerTier, selectionSize: Int): Int = when (tier) {
            BattleTrainerTier.INTRODUCTORY -> 3
            BattleTrainerTier.STANDARD -> 6
            BattleTrainerTier.ADVANCED -> 10
            BattleTrainerTier.BOSS -> 16
        } * selectionSize

        fun canonical(value: String): String = PublicIds.canonical(value)

    }
}
