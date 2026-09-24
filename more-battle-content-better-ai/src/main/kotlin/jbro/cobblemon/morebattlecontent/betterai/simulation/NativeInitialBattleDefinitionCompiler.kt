package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

private fun normalizedNativeId(value: String): String = value.substringAfter(':')
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

internal enum class NativeBuildKnowledge { EXACT_OWN, PUBLIC_HYPOTHESIS }

/** Species identity resolved only from the public species/form carried by the battle DTO. */
internal data class NativePublicPokemonIdentity(
    val battlePokemonId: UUID,
    val publicSpeciesId: String,
    val publicFormId: String?,
    val showdownSpeciesId: String,
) {
    init {
        require(publicSpeciesId.isNotBlank())
        require(publicFormId == null || publicFormId.isNotBlank())
        require(normalizedNativeId(showdownSpeciesId).isNotBlank())
    }
}

/** A complete numeric build; no neutral-nature or zero-EV defaults are admitted here. */
internal data class NativePokemonBuildHypothesis(
    val battlePokemonId: UUID,
    val knowledge: NativeBuildKnowledge,
    val abilityId: String,
    val itemId: String,
    val nature: String,
    val gender: String,
    val evs: Map<String, Int>,
    val ivs: Map<String, Int>,
) {
    init {
        require(normalizedNativeId(abilityId).isNotBlank())
        require(itemId.isBlank() || normalizedNativeId(itemId).isNotBlank())
        require(nature.isNotBlank())
        require(gender in setOf("M", "F", "N"))
        require(evs.keys == STAT_IDS && evs.values.all { it in 0..252 } && evs.values.sum() <= 510)
        require(ivs.keys == STAT_IDS && ivs.values.all { it in 0..31 })
    }

    companion object {
        private val STAT_IDS = setOf("hp", "atk", "def", "spa", "spd", "spe")
    }
}

/** One bounded, complete native world. Move identities remain owned by normalized move slots. */
internal data class NativeBattleWorldHypothesis(
    val hypothesisId: String,
    val probability: Double,
    val pokemon: List<NativePokemonBuildHypothesis>,
) {
    init {
        require(hypothesisId.isNotBlank())
        require(probability.isFinite() && probability > 0.0 && probability <= 1.0)
        require(pokemon.isNotEmpty())
        require(pokemon.map(NativePokemonBuildHypothesis::battlePokemonId).distinct().size == pokemon.size)
    }
}

internal enum class NativeBattleDefinitionIssueCode {
    PUBLIC_STATE_NOT_INITIAL,
    PUBLIC_ROSTER_INCOMPLETE,
    ACTIVE_LAYOUT_INVALID,
    UNKNOWN_PUBLIC_SPECIES,
    PUBLIC_IDENTITY_MISSING,
    PUBLIC_IDENTITY_STALE,
    BUILD_HYPOTHESIS_MISSING,
    BUILD_KNOWLEDGE_MISMATCH,
    PUBLIC_ABILITY_CONFLICT,
    PUBLIC_ITEM_CONFLICT,
    MOVESET_UNAVAILABLE,
    LEVEL_UNAVAILABLE,
    HYPOTHESIS_ROSTER_MISMATCH,
}

internal data class NativeBattleDefinitionIssue(
    val code: NativeBattleDefinitionIssueCode,
    val battlePokemonId: UUID? = null,
)

internal data class NativeInitialBattleDefinitionCompilation(
    val definition: NativeBattleDefinition?,
    val issues: List<NativeBattleDefinitionIssue>,
) {
    init {
        require((definition == null) == issues.isNotEmpty()) {
            "A native initial battle definition is either complete or unavailable with explicit issues"
        }
    }
}

/**
 * Compiles a synthetic Showdown root only when the complete opening is publicly reconstructable.
 *
 * A mid-battle DTO, an omitted hidden bench, or an unresolved concrete move fails closed. This is
 * intentionally narrower than arbitrary state reconstruction: native history-sensitive fields must
 * later descend from the synthetic root rather than be guessed from a partial public board.
 */
internal object NativeInitialBattleDefinitionCompiler {
    fun compile(
        state: BattleStateView,
        catalog: BattlePublicActionCatalogView,
        identities: List<NativePublicPokemonIdentity>,
        world: NativeBattleWorldHypothesis,
        seed: List<Int>,
    ): NativeInitialBattleDefinitionCompilation {
        require(seed.size == 4) { "Showdown PRNG seed must contain four integers" }
        val issues = linkedSetOf<NativeBattleDefinitionIssue>()
        if (!isInitialState(state)) issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_STATE_NOT_INITIAL)

        val stateIds = state.pokemon.mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId)
        val identityById = identities.associateBy(NativePublicPokemonIdentity::battlePokemonId)
        val buildById = world.pokemon.associateBy(NativePokemonBuildHypothesis::battlePokemonId)
        if (identityById.size != identities.size || identityById.keys != stateIds || buildById.keys != stateIds) {
            issue(issues, NativeBattleDefinitionIssueCode.HYPOTHESIS_ROSTER_MISMATCH)
        }

        BattleSide.entries.forEach { side ->
            val sidePokemon = state.pokemon.filter { it.side == side }
            if (sidePokemon.isEmpty() || state.remainingPokemonBySide.getValue(side) != sidePokemon.size) {
                issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_ROSTER_INCOMPLETE)
            }
            val expectedActive = minOf(if (state.format == BattleFormat.SINGLE) 1 else 2, sidePokemon.size)
            if (sidePokemon.mapNotNull(BattlePokemonStateView::activeSlot).sorted() != (0 until expectedActive).toList()) {
                issue(issues, NativeBattleDefinitionIssueCode.ACTIVE_LAYOUT_INVALID)
            }
        }

        val sets = linkedMapOf<UUID, NativePokemonSet>()
        state.pokemon.forEach { pokemon ->
            val id = pokemon.battlePokemonId
            val identity = identityById[id]
            val build = buildById[id]
            if (normalizedNativeId(pokemon.speciesId) == UNKNOWN_SPECIES_ID ||
                identity?.showdownSpeciesId?.let(::normalizedNativeId) == UNKNOWN_SPECIES_ID
            ) {
                issue(issues, NativeBattleDefinitionIssueCode.UNKNOWN_PUBLIC_SPECIES, id)
            }
            when {
                identity == null -> issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_IDENTITY_MISSING, id)
                identity.publicSpeciesId != pokemon.speciesId || identity.publicFormId != pokemon.formId ->
                    issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_IDENTITY_STALE, id)
            }
            if (build == null) {
                issue(issues, NativeBattleDefinitionIssueCode.BUILD_HYPOTHESIS_MISSING, id)
            } else {
                val expectedKnowledge = if (pokemon.side == BattleSide.ALLY) {
                    NativeBuildKnowledge.EXACT_OWN
                } else {
                    NativeBuildKnowledge.PUBLIC_HYPOTHESIS
                }
                if (build.knowledge != expectedKnowledge) {
                    issue(issues, NativeBattleDefinitionIssueCode.BUILD_KNOWLEDGE_MISMATCH, id)
                }
                val publicAbility = pokemon.knownAbilityId
                if (publicAbility != null && normalizedNativeId(publicAbility) != normalizedNativeId(build.abilityId)) {
                    issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_ABILITY_CONFLICT, id)
                }
                val publicItem = pokemon.knownHeldItemId?.let(::normalizedNativeId)
                val hypothesizedItem = normalizedNativeId(build.itemId)
                val itemConflict = when (pokemon.side) {
                    BattleSide.ALLY -> publicItem.orEmpty() != hypothesizedItem
                    BattleSide.OPPONENT -> publicItem != null && publicItem != hypothesizedItem
                }
                if (itemConflict) issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_ITEM_CONFLICT, id)
            }

            val level = pokemon.level
            if (level == null) issue(issues, NativeBattleDefinitionIssueCode.LEVEL_UNAVAILABLE, id)
            val moves = concreteMoves(pokemon, catalog)
            if (moves.isEmpty()) issue(issues, NativeBattleDefinitionIssueCode.MOVESET_UNAVAILABLE, id)

            if (identity != null && build != null && level != null && moves.isNotEmpty()) {
                sets[id] = NativePokemonSet(
                    name = "native-${id.toString().takeLast(8)}",
                    species = normalizedNativeId(identity.showdownSpeciesId),
                    moves = moves,
                    ability = normalizedNativeId(build.abilityId),
                    uuid = id.toString(),
                    item = normalizedNativeId(build.itemId),
                    nature = build.nature,
                    gender = build.gender,
                    level = level,
                    evs = build.evs,
                    ivs = build.ivs,
                )
            }
        }

        if (issues.isNotEmpty()) return NativeInitialBattleDefinitionCompilation(null, issues.toList())
        fun team(side: BattleSide): List<NativePokemonSet> = state.pokemon.withIndex()
            .filter { it.value.side == side }
            .sortedWith(compareBy<IndexedValue<BattlePokemonStateView>> {
                it.value.activeSlot ?: Int.MAX_VALUE
            }.thenBy(IndexedValue<BattlePokemonStateView>::index))
            .map { sets.getValue(it.value.battlePokemonId) }
        return NativeInitialBattleDefinitionCompilation(
            definition = NativeBattleDefinition(
                formatId = if (state.format == BattleFormat.SINGLE) "cobblemonsingles" else "cobblemondoubles",
                seed = seed,
                p1Team = team(BattleSide.ALLY),
                p2Team = team(BattleSide.OPPONENT),
            ),
            issues = emptyList(),
        )
    }

    private fun concreteMoves(
        pokemon: BattlePokemonStateView,
        catalog: BattlePublicActionCatalogView,
    ): List<String> = when (pokemon.side) {
        BattleSide.ALLY -> catalog.forPokemon(pokemon.battlePokemonId).takeIf {
            catalog.isMoveSetComplete(pokemon.battlePokemonId) &&
                it.isNotEmpty() &&
                it.all { move -> move.knowledge == BattlePublicMoveKnowledge.EXACT_OWN }
        }?.map { normalizedNativeId(it.moveId) }
            ?.takeIf { moves -> moves.all(String::isNotBlank) && moves.distinct().size == moves.size }
            .orEmpty()
        BattleSide.OPPONENT -> NativeMoveHypothesisCompiler.compile(pokemon, catalog).nativeMoveIds
    }

    private fun isInitialState(state: BattleStateView): Boolean =
        state.turn in 0..1 &&
            state.observedEvents.isEmpty() &&
            state.field.weather == null &&
            state.field.terrain == null &&
            state.field.roomEffects.isEmpty() &&
            state.field.globalEffects.isEmpty() &&
            state.field.sideConditions.values.all(List<*>::isEmpty) &&
            state.pokemon.all { pokemon ->
                pokemon.hpFraction == 1.0 &&
                    pokemon.statusId == null &&
                    pokemon.statStages.isEmpty() &&
                    pokemon.knownVolatileEffectIds.isEmpty() &&
                    pokemon.actionConstraints == BattlePokemonActionConstraintView.empty() &&
                    !pokemon.fainted &&
                    (pokemon.side != BattleSide.ALLY ||
                        pokemon.combatStats?.knowledge == BattleCombatStatKnowledge.EXACT_OWN)
            }

    private fun issue(
        issues: MutableSet<NativeBattleDefinitionIssue>,
        code: NativeBattleDefinitionIssueCode,
        pokemonId: UUID? = null,
    ) {
        issues += NativeBattleDefinitionIssue(code, pokemonId)
    }

    private const val UNKNOWN_SPECIES_ID = "unknown"
}
