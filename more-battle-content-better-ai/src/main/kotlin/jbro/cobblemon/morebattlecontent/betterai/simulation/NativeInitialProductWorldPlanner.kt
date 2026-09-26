package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactOwnTeamView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.state.LocalMoveUsageLookup
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentBuildUsage
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentBuildUsageLookup
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentMoveUsage

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
    fun plan(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
    ): NativeInitialProductWorldPlan {
        val preview = context.opponentTeamPreview ?: return failure(
            NativeInitialProductWorldPlanIssueCode.OPPONENT_PREVIEW_MISSING,
        )
        val exactOwnTeam = context.exactOwnTeam ?: return failure(
            NativeInitialProductWorldPlanIssueCode.EXACT_OWN_TEAM_MISSING,
        )
        val identities = publicIdentityResolver(context, preview, exactOwnTeam)
        if (identities.issues.isNotEmpty()) return NativeInitialProductWorldPlan(emptyList(), identities.issues)

        val rosterCompilation = NativeOpponentRosterHypothesisCompiler.compile(context.state, preview)
        if (rosterCompilation.issues.isNotEmpty()) {
            return failure(
                NativeInitialProductWorldPlanIssueCode.ROSTER_COMPILATION_FAILED,
                detailCodes = rosterCompilation.issues.map { it.code.name },
            )
        }

        val candidates = mutableListOf<PreparedWorld>()
        rosterCompilation.hypotheses.forEach { rosterHypothesis ->
            val materialization = NativeOpponentRosterStateMaterializer.materialize(
                context.state,
                preview,
                rosterHypothesis,
                identities.resolve,
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
                moveMaterialization.issues.map { it.code.name },
            )
            val buildCompilation = NativeOpponentPreviewBuildWorldCompiler.compile(
                preview,
                rosterHypothesis.selectedPreviewSlotIds,
                tier,
                buildUsageForFormat(context.state.format),
                context.localOpponentStatSpreads,
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
                buildCompilation.worlds.forEach { buildWorld ->
                    val assembly = NativeInitialBattleWorldAssembler.assemble(
                        roster,
                        rosterHypothesis,
                        exactOwnTeam,
                        buildWorld,
                        moveWorld.catalog,
                    )
                    val assembled = assembly.world ?: return failure(
                        NativeInitialProductWorldPlanIssueCode.INITIAL_WORLD_ASSEMBLY_FAILED,
                        rosterHypothesis.hypothesisId,
                        assembly.issues.map { it.code.name },
                    )
                    candidates += PreparedWorld(
                        world = assembled.copy(probability = assembled.probability * moveWorld.probability),
                        roster = roster,
                        catalogContext = context.copy(
                            state = roster.state,
                            publicActionCatalog = moveWorld.catalog,
                        ),
                    )
                }
            }
        }

        val priorIssue = validatePrior(candidates)
        if (priorIssue != null) return NativeInitialProductWorldPlan(emptyList(), listOf(priorIssue))
        val limit = worldLimit(tier, preview.selectionSize)
        if (limit <= 0) return failure(NativeInitialProductWorldPlanIssueCode.WORLD_PRIOR_INVALID)
        val retained = candidates.sortedWith(
            compareByDescending<PreparedWorld> { it.world.probability }
                .thenBy { it.world.hypothesisId },
        ).take(limit)
        val retainedMass = retained.sumOf { it.world.probability }
        if (!retainedMass.isFinite() || retainedMass <= 0.0) {
            return failure(NativeInitialProductWorldPlanIssueCode.WORLD_PRIOR_INVALID)
        }

        val worlds = retained.map { prepared ->
            val probability = prepared.world.probability / retainedMass
            val compilation = NativeInitialBattleDefinitionCompiler.compile(
                state = prepared.roster.state,
                catalog = prepared.catalogContext.publicActionCatalog,
                identities = prepared.roster.identities,
                world = prepared.world.copy(probability = probability),
                seed = NativeProductSeedPolicy.derive(
                    context.state.battleId,
                    prepared.world.hypothesisId,
                    randomSampleIndex = 0,
                ),
            )
            val definition = compilation.definition ?: return failure(
                NativeInitialProductWorldPlanIssueCode.BATTLE_DEFINITION_COMPILATION_FAILED,
                prepared.world.hypothesisId,
                compilation.issues.map { it.code.name },
            )
            NativeInitialProductWorld(
                hypothesisId = prepared.world.hypothesisId,
                probability = probability,
                definition = definition,
                publicContext = prepared.catalogContext,
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
            val nativeId = canonical(showdownSpeciesId)
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

    private fun validatePrior(candidates: List<PreparedWorld>): NativeInitialProductWorldPlanIssue? {
        if (candidates.isEmpty() || candidates.map { it.world.hypothesisId }.distinct().size != candidates.size) {
            return NativeInitialProductWorldPlanIssue(NativeInitialProductWorldPlanIssueCode.WORLD_PRIOR_INVALID)
        }
        val sum = candidates.sumOf { it.world.probability }
        return if (!sum.isFinite() || abs(sum - 1.0) > NORMALIZATION_EPSILON) {
            NativeInitialProductWorldPlanIssue(NativeInitialProductWorldPlanIssueCode.WORLD_PRIOR_INVALID)
        } else {
            null
        }
    }

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

    private data class PreparedWorld(
        val world: NativeBattleWorldHypothesis,
        val roster: NativeMaterializedOpponentRoster,
        val catalogContext: BattleDecisionContext,
    )

    private data class PublicSpeciesKey(val speciesId: String, val formId: String)

    private data class IdentityResolverCompilation(
        val resolve: (String, String?) -> String?,
        val issues: List<NativeInitialProductWorldPlanIssue>,
    )

    private companion object {
        const val NORMALIZATION_EPSILON = 1e-9

        fun defaultWorldLimit(tier: BattleTrainerTier, selectionSize: Int): Int = when (tier) {
            BattleTrainerTier.INTRODUCTORY -> 3
            BattleTrainerTier.STANDARD -> 6
            BattleTrainerTier.ADVANCED -> 10
            BattleTrainerTier.BOSS -> 16
        } * selectionSize

        fun canonical(value: String): String = value.substringAfter(':')
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)

    }
}
