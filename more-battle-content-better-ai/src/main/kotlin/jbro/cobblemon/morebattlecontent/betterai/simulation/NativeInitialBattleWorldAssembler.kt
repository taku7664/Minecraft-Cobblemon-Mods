package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactOwnTeamView
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactPokemonBuildView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

/** One complete public build assumption for an opaque team-preview slot. */
internal data class NativeOpponentPreviewBuildHypothesis(
    val previewSlotId: Int,
    val abilityId: String,
    val itemId: String?,
    val natureId: String,
    val gender: String,
    val evs: Map<String, Int>,
    val ivs: Map<String, Int>,
) {
    init {
        require(previewSlotId in 0 until 6)
        require(nativeId(abilityId).isNotBlank())
        require(itemId == null || nativeId(itemId).isNotBlank())
        require(nativeId(natureId).isNotBlank())
        require(gender in setOf("M", "F", "N"))
        requireValidSpreads(evs, ivs)
    }
}

/** A coherent set-build world, conditional on whichever preview roster is selected. */
internal data class NativeOpponentPreviewBuildWorld(
    val hypothesisId: String,
    val probability: Double,
    val builds: List<NativeOpponentPreviewBuildHypothesis>,
) {
    init {
        require(hypothesisId.isNotBlank())
        require(probability.isFinite() && probability > 0.0 && probability <= 1.0)
        require(builds.isNotEmpty())
        require(builds.map(NativeOpponentPreviewBuildHypothesis::previewSlotId).distinct().size == builds.size)
    }
}

internal enum class NativeInitialWorldAssemblyIssueCode {
    EXACT_OWN_TEAM_MISMATCH,
    ROSTER_HYPOTHESIS_MISMATCH,
    OPPONENT_PREVIEW_BUILD_MISSING,
    OPPONENT_MOVE_INFERENCE_MISSING,
    OPPONENT_MOVESET_INCOMPLETE,
    OPPONENT_EXECUTABLE_MOVE_MISSING,
}

internal data class NativeInitialWorldAssemblyIssue(
    val code: NativeInitialWorldAssemblyIssueCode,
    val battlePokemonId: UUID? = null,
    val previewSlotId: Int? = null,
)

internal data class NativeInitialBattleWorldAssembly(
    val world: NativeBattleWorldHypothesis?,
    val issues: List<NativeInitialWorldAssemblyIssue>,
) {
    init {
        require((world == null) == issues.isNotEmpty()) {
            "Initial native world assembly must return one complete world or explicit issues"
        }
    }
}

/** Joins exact self knowledge and public opponent-slot hypotheses without crossing the boundary. */
internal object NativeInitialBattleWorldAssembler {
    fun assemble(
        roster: NativeMaterializedOpponentRoster,
        rosterHypothesis: NativeOpponentRosterHypothesis,
        exactOwnTeam: BattleExactOwnTeamView,
        opponentWorld: NativeOpponentPreviewBuildWorld,
        catalog: BattlePublicActionCatalogView,
    ): NativeInitialBattleWorldAssembly {
        val issues = linkedSetOf<NativeInitialWorldAssemblyIssue>()
        val allyIds = roster.state.pokemon.asSequence()
            .filter { it.side == BattleSide.ALLY }
            .mapTo(linkedSetOf()) { it.battlePokemonId }
        val exactById = exactOwnTeam.builds.associateBy(BattleExactPokemonBuildView::battlePokemonId)
        if (exactById.keys != allyIds) {
            issues += NativeInitialWorldAssemblyIssue(NativeInitialWorldAssemblyIssueCode.EXACT_OWN_TEAM_MISMATCH)
        }

        val selectedSlots = roster.opponentPreviewSlotByPokemonId.values.toSet()
        val revealedOpponentIds = roster.state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT && it.activeSlot != null }
            .mapTo(linkedSetOf()) { it.battlePokemonId }
        val revealedAssignmentsMatch = rosterHypothesis.revealedAssignments.keys == revealedOpponentIds &&
            rosterHypothesis.revealedAssignments.all { (pokemonId, slot) ->
                roster.opponentPreviewSlotByPokemonId[pokemonId] == slot
            }
        if (selectedSlots != rosterHypothesis.selectedPreviewSlotIds.toSet() || !revealedAssignmentsMatch) {
            issues += NativeInitialWorldAssemblyIssue(NativeInitialWorldAssemblyIssueCode.ROSTER_HYPOTHESIS_MISMATCH)
        }
        val previewBuildBySlot = opponentWorld.builds.associateBy(NativeOpponentPreviewBuildHypothesis::previewSlotId)
        selectedSlots.sorted().filterNot(previewBuildBySlot::containsKey).forEach { slot ->
            issues += NativeInitialWorldAssemblyIssue(
                NativeInitialWorldAssemblyIssueCode.OPPONENT_PREVIEW_BUILD_MISSING,
                previewSlotId = slot,
            )
        }
        val opponentMoveSets = linkedMapOf<UUID, NativeOpponentMoveSetHypothesis>()
        roster.state.pokemon.asSequence().filter { it.side == BattleSide.OPPONENT }.forEach { pokemon ->
            val slot = roster.opponentPreviewSlotByPokemonId.getValue(pokemon.battlePokemonId)
            val compiled = NativeMoveHypothesisCompiler.compile(pokemon, catalog)
            val complete = compiled.completeSetOrNull()
            if (complete == null) {
                val code = when (compiled.unavailableReason) {
                    "normalized_inference_missing" ->
                        NativeInitialWorldAssemblyIssueCode.OPPONENT_MOVE_INFERENCE_MISSING
                    "normalized_inference_incomplete" ->
                        NativeInitialWorldAssemblyIssueCode.OPPONENT_MOVESET_INCOMPLETE
                    else -> NativeInitialWorldAssemblyIssueCode.OPPONENT_EXECUTABLE_MOVE_MISSING
                }
                issues += NativeInitialWorldAssemblyIssue(code, pokemon.battlePokemonId, slot)
            } else {
                opponentMoveSets[pokemon.battlePokemonId] = complete
            }
        }
        if (issues.isNotEmpty()) return NativeInitialBattleWorldAssembly(null, issues.toList())

        val builds = roster.state.pokemon.map { pokemon ->
            when (pokemon.side) {
                BattleSide.ALLY -> exactBuild(requireNotNull(exactById[pokemon.battlePokemonId]))
                BattleSide.OPPONENT -> {
                    val slot = roster.opponentPreviewSlotByPokemonId.getValue(pokemon.battlePokemonId)
                    publicBuild(
                        pokemon.battlePokemonId,
                        previewBuildBySlot.getValue(slot),
                        opponentMoveSets.getValue(pokemon.battlePokemonId),
                    )
                }
            }
        }
        val moveFingerprint = roster.opponentPreviewSlotByPokemonId.entries.sortedBy { it.value }
            .joinToString("|") { (pokemonId, slot) ->
                "s$slot=${opponentMoveSets.getValue(pokemonId).fingerprint}"
            }
        return NativeInitialBattleWorldAssembly(
            world = NativeBattleWorldHypothesis(
                hypothesisId = "${rosterHypothesis.hypothesisId}+${opponentWorld.hypothesisId}+moves:$moveFingerprint",
                probability = rosterHypothesis.probability * opponentWorld.probability,
                pokemon = builds,
            ),
            issues = emptyList(),
        )
    }

    private fun exactBuild(build: BattleExactPokemonBuildView) = NativePokemonBuildHypothesis(
        battlePokemonId = build.battlePokemonId,
        knowledge = NativeBuildKnowledge.EXACT_OWN,
        abilityId = nativeId(build.abilityId),
        itemId = build.heldItemId?.let(::nativeId).orEmpty(),
        nature = nativeId(build.natureId),
        gender = build.gender,
        evs = build.evs,
        ivs = build.ivs,
    )

    private fun publicBuild(
        battlePokemonId: UUID,
        build: NativeOpponentPreviewBuildHypothesis,
        moveSet: NativeOpponentMoveSetHypothesis,
    ) = NativePokemonBuildHypothesis(
        battlePokemonId = battlePokemonId,
        knowledge = NativeBuildKnowledge.PUBLIC_HYPOTHESIS,
        abilityId = nativeId(build.abilityId),
        itemId = build.itemId?.let(::nativeId).orEmpty(),
        nature = nativeId(build.natureId),
        gender = build.gender,
        evs = build.evs,
        ivs = build.ivs,
        opponentMoveSet = moveSet,
    )
}

private fun nativeId(value: String): String = value.substringAfter(':')
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

private fun requireValidSpreads(evs: Map<String, Int>, ivs: Map<String, Int>) {
    val stats = setOf("hp", "atk", "def", "spa", "spd", "spe")
    require(evs.keys == stats && evs.values.all { it in 0..252 } && evs.values.sum() <= 510)
    require(ivs.keys == stats && ivs.values.all { it in 0..31 })
}
