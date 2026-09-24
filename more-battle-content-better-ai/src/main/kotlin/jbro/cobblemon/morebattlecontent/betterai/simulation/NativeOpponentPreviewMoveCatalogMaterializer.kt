package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveGroup
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.state.LocalMoveUsageLookup

internal enum class NativeOpponentPreviewMoveCatalogIssueCode {
    SELECTED_PREVIEW_SLOT_MISSING,
    PUBLIC_MOVE_POOL_MISSING,
    EXECUTABLE_MOVE_UNAVAILABLE,
}

internal data class NativeOpponentPreviewMoveCatalogIssue(
    val code: NativeOpponentPreviewMoveCatalogIssueCode,
    val battlePokemonId: UUID,
    val previewSlotId: Int,
)

internal data class NativeOpponentPreviewMoveCatalogMaterialization(
    val catalog: BattlePublicActionCatalogView?,
    val issues: List<NativeOpponentPreviewMoveCatalogIssue>,
) {
    init {
        require((catalog == null) == issues.isNotEmpty()) {
            "Preview move materialization must return a complete catalog or explicit issues"
        }
    }
}

/** Maps public preview learnsets onto selected synthetic IDs without consulting a live hidden set. */
internal object NativeOpponentPreviewMoveCatalogMaterializer {
    fun materialize(
        roster: NativeMaterializedOpponentRoster,
        preview: BattleOpponentTeamPreviewView,
        sourceCatalog: BattlePublicActionCatalogView,
        tier: BattleTrainerTier,
        usage: LocalMoveUsageLookup?,
    ): NativeOpponentPreviewMoveCatalogMaterialization {
        val previewBySlot = preview.pokemon.associateBy(BattleOpponentTeamPreviewPokemonView::previewSlotId)
        val issues = mutableListOf<NativeOpponentPreviewMoveCatalogIssue>()
        val inferences = roster.state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT }
            .mapNotNull { pokemon ->
                val slot = roster.opponentPreviewSlotByPokemonId.getValue(pokemon.battlePokemonId)
                val previewPokemon = previewBySlot[slot]
                if (previewPokemon == null) {
                    issues += issue(
                        NativeOpponentPreviewMoveCatalogIssueCode.SELECTED_PREVIEW_SLOT_MISSING,
                        pokemon,
                        slot,
                    )
                    return@mapNotNull null
                }
                sourceCatalog.inferredMovesForPokemon(pokemon.battlePokemonId)?.let { return@mapNotNull it }
                if (previewPokemon.moveCandidatePool == null) {
                    issues += issue(
                        NativeOpponentPreviewMoveCatalogIssueCode.PUBLIC_MOVE_POOL_MISSING,
                        pokemon,
                        slot,
                    )
                    return@mapNotNull null
                }
                val inferred = infer(pokemon, previewPokemon, roster.state.format, tier, usage)
                if (inferred.slots.none { it.knowledge != BattleOpponentMoveKnowledge.GUESS }) {
                    issues += issue(
                        NativeOpponentPreviewMoveCatalogIssueCode.EXECUTABLE_MOVE_UNAVAILABLE,
                        pokemon,
                        slot,
                    )
                    null
                } else {
                    inferred
                }
            }
            .toList()
        if (issues.isNotEmpty()) {
            return NativeOpponentPreviewMoveCatalogMaterialization(null, issues)
        }
        return NativeOpponentPreviewMoveCatalogMaterialization(
            sourceCatalog.withOpponentMoveInferences(inferences),
            emptyList(),
        )
    }

    internal fun infer(
        pokemon: BattlePokemonStateView,
        preview: BattleOpponentTeamPreviewPokemonView,
        format: BattleFormat,
        tier: BattleTrainerTier,
        usage: LocalMoveUsageLookup?,
    ): BattleOpponentMoveInferenceView {
        require(pokemon.side == BattleSide.OPPONENT)
        val pool = requireNotNull(preview.moveCandidatePool) {
            "A public preview move pool is required before inference"
        }
        val policy = policy(tier)
        val ranked = pool.moveDetails.entries
            .filter { (moveId, details) -> moveId in pool.moveIds && details.currentPp > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<String, BattleMoveCandidateView>> { (moveId, _) ->
                    usage?.rate(preview.speciesId, preview.formId, moveId) ?: -1.0
                }.thenByDescending { (_, details) -> attackScore(details, format) }
                    .thenBy { (moveId, _) -> canonical(moveId) },
            )
        val stab = ranked.filter { (_, details) -> group(preview, details) == BattleOpponentMoveGroup.STAB_ATTACK }
            .distinctBy { (_, details) -> canonical(details.typeId) }
        val coverage = ranked.filter { (_, details) ->
            group(preview, details) == BattleOpponentMoveGroup.COVERAGE_ATTACK
        }
        val slots = mutableListOf<BattleOpponentMoveSlotView>()
        addExpected(slots, stab.take(policy.stabSlots), BattleOpponentMoveGroup.STAB_ATTACK)
        addExpected(slots, coverage.take(policy.coverageSlots), BattleOpponentMoveGroup.COVERAGE_ATTACK)
        if (slots.isEmpty()) {
            ranked.firstOrNull { (_, details) -> details.damageCategory != BattleMoveDamageCategory.STATUS }
                ?.let { candidate -> addExpected(slots, listOf(candidate), group(preview, candidate.value)) }
        }
        repeat(minOf(policy.statusGuessSlots, MAX_MOVE_SLOTS - slots.size)) {
            slots += guessed(slots.size, BattleOpponentMoveGroup.STATUS_OTHER)
        }
        while (slots.size < MAX_MOVE_SLOTS) slots += guessed(slots.size, BattleOpponentMoveGroup.OTHER)
        return BattleOpponentMoveInferenceView(pokemon.battlePokemonId, slots)
    }

    private fun addExpected(
        slots: MutableList<BattleOpponentMoveSlotView>,
        candidates: List<Map.Entry<String, BattleMoveCandidateView>>,
        group: BattleOpponentMoveGroup,
    ) {
        candidates.asSequence().take(MAX_MOVE_SLOTS - slots.size).forEach { (moveId, details) ->
            slots += BattleOpponentMoveSlotView(
                slot = slots.size,
                moveId = moveId,
                group = group,
                knowledge = BattleOpponentMoveKnowledge.EXPECTED,
                source = BattleOpponentMoveSource.LEARNSET_EXPECTATION,
                details = details,
            )
        }
    }

    private fun guessed(slot: Int, group: BattleOpponentMoveGroup) = BattleOpponentMoveSlotView(
        slot = slot,
        moveId = null,
        group = group,
        knowledge = BattleOpponentMoveKnowledge.GUESS,
        source = BattleOpponentMoveSource.GROUP_GUESS,
    )

    private fun group(
        preview: BattleOpponentTeamPreviewPokemonView,
        details: BattleMoveCandidateView,
    ): BattleOpponentMoveGroup = when {
        details.damageCategory != BattleMoveDamageCategory.STATUS -> {
            if (preview.knownTypeIds.any { sameId(it, details.typeId) }) {
                BattleOpponentMoveGroup.STAB_ATTACK
            } else {
                BattleOpponentMoveGroup.COVERAGE_ATTACK
            }
        }
        isPureSetup(details) -> BattleOpponentMoveGroup.PURE_SETUP
        else -> BattleOpponentMoveGroup.STATUS_OTHER
    }

    private fun isPureSetup(details: BattleMoveCandidateView): Boolean {
        val effects = details.effects?.effects.orEmpty()
        return effects.isNotEmpty() && effects.all { effect ->
            effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.target == BattleMoveEffectTarget.USER &&
                effect.statStages.isNotEmpty()
        } && effects.any { effect -> effect.statStages.values.any { it > 0 } }
    }

    private fun attackScore(details: BattleMoveCandidateView, format: BattleFormat): Double {
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0
        val spreadValue = if (
            format == BattleFormat.DOUBLE && details.targetPattern == BattleMoveTargetPattern.ALL_OPPONENTS
        ) 1.5 else 1.0
        return details.power * (details.accuracy / 100.0) * spreadValue + details.priority.coerceAtLeast(0) * 12.0
    }

    private fun policy(tier: BattleTrainerTier): PublicPreviewPolicy = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> PublicPreviewPolicy(1, 0, 0)
        BattleTrainerTier.STANDARD -> PublicPreviewPolicy(1, 1, 1)
        BattleTrainerTier.ADVANCED -> PublicPreviewPolicy(1, 2, 1)
        BattleTrainerTier.BOSS -> PublicPreviewPolicy(2, 1, 1)
    }

    private fun issue(
        code: NativeOpponentPreviewMoveCatalogIssueCode,
        pokemon: BattlePokemonStateView,
        previewSlotId: Int,
    ) = NativeOpponentPreviewMoveCatalogIssue(code, pokemon.battlePokemonId, previewSlotId)

    private fun sameId(left: String, right: String): Boolean = canonical(left) == canonical(right)
    private fun canonical(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private data class PublicPreviewPolicy(
        val stabSlots: Int,
        val coverageSlots: Int,
        val statusGuessSlots: Int,
    )

    private const val MAX_MOVE_SLOTS = 4
}
