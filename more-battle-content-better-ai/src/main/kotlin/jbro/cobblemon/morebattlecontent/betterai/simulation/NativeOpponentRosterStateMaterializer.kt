package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

internal enum class NativeOpponentRosterMaterializationIssueCode {
    PUBLIC_STATE_NOT_INITIAL,
    SELECTION_SIZE_MISMATCH,
    SELECTED_PREVIEW_SLOT_MISSING,
    REVEALED_ASSIGNMENT_MISMATCH,
    PUBLIC_APPEARANCE_ORDER_UNAVAILABLE,
    SYNTHETIC_ID_COLLISION,
    SHOWDOWN_SPECIES_UNAVAILABLE,
}

internal data class NativeOpponentRosterMaterializationIssue(
    val code: NativeOpponentRosterMaterializationIssueCode,
    val battlePokemonId: UUID? = null,
    val previewSlotId: Int? = null,
)

/**
 * A complete public opening for one roster hypothesis.
 *
 * Opponent bench IDs are derived from the public battle ID and opaque preview slot. They never
 * originate from the live hidden roster. A revealed Pokemon keeps its public displayed species in
 * [state], while its native identity comes from the assigned preview slot so an Illusion-compatible
 * world can be simulated without reading the real private identity.
 */
internal data class NativeMaterializedOpponentRoster(
    val state: BattleStateView,
    val identities: List<NativePublicPokemonIdentity>,
    val opponentPreviewSlotByPokemonId: Map<UUID, Int>,
) {
    init {
        val stateIds = state.pokemon.mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId)
        require(identities.mapTo(linkedSetOf(), NativePublicPokemonIdentity::battlePokemonId) == stateIds)
        require(opponentPreviewSlotByPokemonId.keys == state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT }
            .mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId))
        require(opponentPreviewSlotByPokemonId.values.distinct().size == opponentPreviewSlotByPokemonId.size)
    }
}

internal data class NativeOpponentRosterMaterialization(
    val roster: NativeMaterializedOpponentRoster?,
    val issues: List<NativeOpponentRosterMaterializationIssue>,
) {
    val identities: List<NativePublicPokemonIdentity> = roster?.identities.orEmpty()

    init {
        require((roster == null) == issues.isNotEmpty()) {
            "Opponent roster materialization must return a complete roster or explicit issues"
        }
    }
}

/** Converts one public selection hypothesis into the complete synthetic opening required by Showdown. */
internal object NativeOpponentRosterStateMaterializer {
    fun materialize(
        state: BattleStateView,
        preview: BattleOpponentTeamPreviewView,
        hypothesis: NativeOpponentRosterHypothesis,
        resolveShowdownSpecies: (speciesId: String, formId: String?) -> String?,
    ): NativeOpponentRosterMaterialization {
        val issues = linkedSetOf<NativeOpponentRosterMaterializationIssue>()
        val supportedSelectionSizes = when (state.format) {
            BattleFormat.SINGLE -> setOf(3, preview.pokemon.size)
            BattleFormat.DOUBLE -> setOf(4, preview.pokemon.size)
        }
        if (preview.selectionSize !in supportedSelectionSizes ||
            hypothesis.selectedPreviewSlotIds.size != preview.selectionSize ||
            state.remainingPokemonBySide.getValue(BattleSide.OPPONENT) != preview.selectionSize
        ) {
            issues += issue(NativeOpponentRosterMaterializationIssueCode.SELECTION_SIZE_MISMATCH)
        }

        val previewBySlot = preview.pokemon.associateBy(BattleOpponentTeamPreviewPokemonView::previewSlotId)
        hypothesis.selectedPreviewSlotIds.filterNot(previewBySlot::containsKey).forEach { slot ->
            issues += issue(
                NativeOpponentRosterMaterializationIssueCode.SELECTED_PREVIEW_SLOT_MISSING,
                previewSlotId = slot,
            )
        }

        val revealed = state.pokemon.filter { it.side == BattleSide.OPPONENT }
        val revealedIds = revealed.mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId)
        if (hypothesis.revealedAssignments.keys != revealedIds) {
            issues += issue(NativeOpponentRosterMaterializationIssueCode.REVEALED_ASSIGNMENT_MISMATCH)
        }
        val expectedActiveSlots = when (state.format) {
            BattleFormat.SINGLE -> listOf(0)
            BattleFormat.DOUBLE -> listOf(0, 1)
        }
        if (state.turn !in 0..1 ||
            !NativeOpeningStateRules.acceptsObservations(state) ||
            revealed.any { it.fainted || it.activeSlot == null } ||
            revealed.mapNotNull(BattlePokemonStateView::activeSlot).sorted() != expectedActiveSlots
        ) {
            issues += issue(NativeOpponentRosterMaterializationIssueCode.PUBLIC_STATE_NOT_INITIAL)
        }
        if (issues.isNotEmpty()) return unavailable(issues)

        val existingIds = state.pokemon.mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId)
        val assignedSlots = hypothesis.revealedAssignments.values.toSet()
        val appearanceOrderSlot = publicAppearanceOrderSlot(
            revealed,
            hypothesis.revealedAssignments,
            hypothesis.selectedPreviewSlotIds,
            previewBySlot,
        )
        if (appearanceOrderSlot.required && appearanceOrderSlot.previewSlotId == null) {
            issues += issue(NativeOpponentRosterMaterializationIssueCode.PUBLIC_APPEARANCE_ORDER_UNAVAILABLE)
            return unavailable(issues)
        }
        val unassignedSlots = hypothesis.selectedPreviewSlotIds.filterNot(assignedSlots::contains)
        val orderedSyntheticSlots = appearanceOrderSlot.previewSlotId?.let { finalSlot ->
            unassignedSlots.filterNot { it == finalSlot } + finalSlot
        } ?: unassignedSlots
        val syntheticBySlot = linkedMapOf<Int, BattlePokemonStateView>()
        orderedSyntheticSlots.forEach { slot ->
            val previewPokemon = previewBySlot.getValue(slot)
            val syntheticId = syntheticBattlePokemonId(state.battleId, slot)
            if (!existingIds.add(syntheticId)) {
                issues += issue(
                    NativeOpponentRosterMaterializationIssueCode.SYNTHETIC_ID_COLLISION,
                    syntheticId,
                    slot,
                )
            } else {
                syntheticBySlot[slot] = syntheticPokemon(syntheticId, previewPokemon)
            }
        }
        if (issues.isNotEmpty()) return unavailable(issues)

        val pokemon = state.pokemon + syntheticBySlot.values
        val opponentSlots = linkedMapOf<UUID, Int>()
        revealed.forEach { opponentSlots[it.battlePokemonId] = hypothesis.revealedAssignments.getValue(it.battlePokemonId) }
        syntheticBySlot.forEach { (slot, synthetic) -> opponentSlots[synthetic.battlePokemonId] = slot }

        val identities = mutableListOf<NativePublicPokemonIdentity>()
        pokemon.forEach { publicPokemon ->
            val slot = opponentSlots[publicPokemon.battlePokemonId]
            val nativeSpecies = if (slot == null) {
                resolveShowdownSpecies(publicPokemon.speciesId, publicPokemon.formId)
            } else {
                val selected = previewBySlot.getValue(slot)
                resolveShowdownSpecies(selected.speciesId, selected.formId)
            }
            if (nativeSpecies == null || normalizedSpeciesId(nativeSpecies).isBlank()) {
                issues += issue(
                    NativeOpponentRosterMaterializationIssueCode.SHOWDOWN_SPECIES_UNAVAILABLE,
                    publicPokemon.battlePokemonId,
                    slot,
                )
            } else {
                identities += NativePublicPokemonIdentity(
                    battlePokemonId = publicPokemon.battlePokemonId,
                    publicSpeciesId = publicPokemon.speciesId,
                    publicFormId = publicPokemon.formId,
                    showdownSpeciesId = nativeSpecies,
                )
            }
        }
        if (issues.isNotEmpty()) return unavailable(issues)

        val rootState = BattleStateView(
            battleId = state.battleId,
            format = state.format,
            turn = state.turn,
            pokemon = pokemon,
            field = state.field,
            remainingPokemonBySide = state.remainingPokemonBySide,
            // These remain public evidence for the build/world compiler. They are not replayed into
            // Showdown; the native root is still created from complete sets below this boundary.
            observedEvents = state.observedEvents,
            inferences = state.inferences,
        )
        return NativeOpponentRosterMaterialization(
            roster = NativeMaterializedOpponentRoster(rootState, identities, opponentSlots),
            issues = emptyList(),
        )
    }

    private fun syntheticPokemon(
        battlePokemonId: UUID,
        preview: BattleOpponentTeamPreviewPokemonView,
    ): BattlePokemonStateView = BattlePokemonStateView(
        battlePokemonId = battlePokemonId,
        side = BattleSide.OPPONENT,
        activeSlot = null,
        speciesId = preview.speciesId,
        formId = preview.formId,
        level = preview.level,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = preview.knownTypeIds,
        combatStats = preview.combatStats,
        knownFormStates = preview.knownFormStates,
        actionConstraints = BattlePokemonActionConstraintView.empty(),
        knownVolatileEffectIds = emptySet(),
    )

    private fun syntheticBattlePokemonId(battleId: UUID, previewSlotId: Int): UUID =
        UUID.nameUUIDFromBytes(
            "more-battle-content-better-ai:opponent-preview:$battleId:$previewSlotId"
                .toByteArray(StandardCharsets.UTF_8),
        )

    private fun normalizedSpeciesId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private data class PublicAppearanceOrderSlot(
        val required: Boolean,
        val previewSlotId: Int?,
    )

    private fun publicAppearanceOrderSlot(
        revealed: List<BattlePokemonStateView>,
        assignments: Map<UUID, Int>,
        selectedSlots: List<Int>,
        previewBySlot: Map<Int, BattleOpponentTeamPreviewPokemonView>,
    ): PublicAppearanceOrderSlot {
        val revealedById = revealed.associateBy(BattlePokemonStateView::battlePokemonId)
        val mismatchedAppearances = assignments.mapNotNull { (pokemonId, assignedSlot) ->
            val pokemon = revealedById.getValue(pokemonId)
            val assignedPreview = previewBySlot.getValue(assignedSlot)
            pokemon.takeUnless { samePublicIdentity(it, assignedPreview) }
        }
        if (mismatchedAppearances.isEmpty()) return PublicAppearanceOrderSlot(false, null)
        val assignedSlots = assignments.values.toSet()
        val appearanceSlot = selectedSlots.asSequence().filterNot(assignedSlots::contains).firstOrNull { slot ->
            val candidate = previewBySlot.getValue(slot)
            mismatchedAppearances.all { samePublicIdentity(it, candidate) }
        }
        return PublicAppearanceOrderSlot(true, appearanceSlot)
    }

    private fun samePublicIdentity(
        pokemon: BattlePokemonStateView,
        preview: BattleOpponentTeamPreviewPokemonView,
    ): Boolean {
        val publicForm = pokemon.formId
        val previewForm = preview.formId
        return normalizedSpeciesId(pokemon.speciesId) == normalizedSpeciesId(preview.speciesId) &&
            (publicForm == null || previewForm == null ||
                normalizedSpeciesId(publicForm) == normalizedSpeciesId(previewForm))
    }

    private fun issue(
        code: NativeOpponentRosterMaterializationIssueCode,
        battlePokemonId: UUID? = null,
        previewSlotId: Int? = null,
    ) = NativeOpponentRosterMaterializationIssue(code, battlePokemonId, previewSlotId)

    private fun unavailable(
        issues: Collection<NativeOpponentRosterMaterializationIssue>,
    ) = NativeOpponentRosterMaterialization(null, issues.toList())
}
