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
    val teraTypeId: String,
    val evs: Map<String, Int>,
    val ivs: Map<String, Int>,
) {
    init {
        require(previewSlotId in 0 until 6)
        require(nativeId(abilityId).isNotBlank())
        require(itemId == null || nativeId(itemId).isNotBlank())
        require(nativeId(natureId).isNotBlank())
        require(gender in setOf("M", "F", "N"))
        require(nativeId(teraTypeId).isNotBlank())
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
    /** Validation and move compilation depend on the roster and move catalog, not on a build world. */
    internal class Preparation internal constructor(
        val roster: NativeMaterializedOpponentRoster,
        val rosterHypothesis: NativeOpponentRosterHypothesis,
        val exactById: Map<UUID, BattleExactPokemonBuildView>,
        val opponentMoveSets: Map<UUID, NativeOpponentMoveSetHypothesis>,
        val moveFingerprint: String?,
        val structuralIssues: List<NativeInitialWorldAssemblyIssue>,
        val moveIssues: List<NativeInitialWorldAssemblyIssue>,
    )

    fun assemble(
        roster: NativeMaterializedOpponentRoster,
        rosterHypothesis: NativeOpponentRosterHypothesis,
        exactOwnTeam: BattleExactOwnTeamView,
        opponentWorld: NativeOpponentPreviewBuildWorld,
        catalog: BattlePublicActionCatalogView,
    ): NativeInitialBattleWorldAssembly = assemblePrepared(
        prepare(roster, rosterHypothesis, exactOwnTeam, catalog), opponentWorld,
    )

    fun prepare(
        roster: NativeMaterializedOpponentRoster,
        rosterHypothesis: NativeOpponentRosterHypothesis,
        exactOwnTeam: BattleExactOwnTeamView,
        catalog: BattlePublicActionCatalogView,
    ): Preparation {
        val structuralIssues = linkedSetOf<NativeInitialWorldAssemblyIssue>()
        val allyIds = roster.state.pokemon.asSequence()
            .filter { it.side == BattleSide.ALLY }
            .mapTo(linkedSetOf()) { it.battlePokemonId }
        val exactById = exactOwnTeam.builds.associateBy(BattleExactPokemonBuildView::battlePokemonId)
        if (exactById.keys != allyIds) {
            structuralIssues += NativeInitialWorldAssemblyIssue(NativeInitialWorldAssemblyIssueCode.EXACT_OWN_TEAM_MISMATCH)
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
            structuralIssues += NativeInitialWorldAssemblyIssue(NativeInitialWorldAssemblyIssueCode.ROSTER_HYPOTHESIS_MISMATCH)
        }
        val opponentMoveSets = linkedMapOf<UUID, NativeOpponentMoveSetHypothesis>()
        val moveIssues = linkedSetOf<NativeInitialWorldAssemblyIssue>()
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
                moveIssues += NativeInitialWorldAssemblyIssue(code, pokemon.battlePokemonId, slot)
            } else {
                opponentMoveSets[pokemon.battlePokemonId] = complete
            }
        }
        val moveFingerprint = if (moveIssues.isNotEmpty()) null else
            roster.opponentPreviewSlotByPokemonId.entries.sortedBy { it.value }
                .joinToString("|") { (pokemonId, slot) ->
                    "s$slot=${opponentMoveSets.getValue(pokemonId).fingerprint}"
                }
        return Preparation(
            roster, rosterHypothesis, exactById, opponentMoveSets, moveFingerprint,
            structuralIssues.toList(), moveIssues.toList(),
        )
    }

    /** Also run for discarded candidates so malformed low-probability worlds still fail closed. */
    fun validationIssues(
        prepared: Preparation,
        opponentWorld: NativeOpponentPreviewBuildWorld,
    ): List<NativeInitialWorldAssemblyIssue> {
        val selectedSlots = prepared.roster.opponentPreviewSlotByPokemonId.values.toSet()
        val presentSlots = opponentWorld.builds.mapTo(linkedSetOf(), NativeOpponentPreviewBuildHypothesis::previewSlotId)
        val buildIssues = selectedSlots.sorted().filterNot(presentSlots::contains).map { slot ->
            NativeInitialWorldAssemblyIssue(
                NativeInitialWorldAssemblyIssueCode.OPPONENT_PREVIEW_BUILD_MISSING,
                previewSlotId = slot,
            )
        }
        return prepared.structuralIssues + buildIssues + prepared.moveIssues
    }

    fun hypothesisId(prepared: Preparation, opponentWorld: NativeOpponentPreviewBuildWorld): String =
        "${prepared.rosterHypothesis.hypothesisId}+${opponentWorld.hypothesisId}+moves:" +
            requireNotNull(prepared.moveFingerprint) { "A complete move set is required for a world ID" }

    fun assemblePrepared(
        prepared: Preparation,
        opponentWorld: NativeOpponentPreviewBuildWorld,
    ): NativeInitialBattleWorldAssembly {
        val issues = validationIssues(prepared, opponentWorld)
        if (issues.isNotEmpty()) return NativeInitialBattleWorldAssembly(null, issues)
        val previewBuildBySlot = opponentWorld.builds.associateBy(NativeOpponentPreviewBuildHypothesis::previewSlotId)
        val builds = prepared.roster.state.pokemon.map { pokemon ->
            when (pokemon.side) {
                BattleSide.ALLY -> exactBuild(requireNotNull(prepared.exactById[pokemon.battlePokemonId]))
                BattleSide.OPPONENT -> {
                    val slot = prepared.roster.opponentPreviewSlotByPokemonId.getValue(pokemon.battlePokemonId)
                    publicBuild(
                        pokemon.battlePokemonId,
                        previewBuildBySlot.getValue(slot),
                        prepared.opponentMoveSets.getValue(pokemon.battlePokemonId),
                    )
                }
            }
        }
        return NativeInitialBattleWorldAssembly(
            world = NativeBattleWorldHypothesis(
                hypothesisId = hypothesisId(prepared, opponentWorld),
                probability = prepared.rosterHypothesis.probability * opponentWorld.probability,
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
        teraTypeId = build.teraTypeId?.let(::nativeId),
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
        teraTypeId = nativeId(build.teraTypeId),
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
