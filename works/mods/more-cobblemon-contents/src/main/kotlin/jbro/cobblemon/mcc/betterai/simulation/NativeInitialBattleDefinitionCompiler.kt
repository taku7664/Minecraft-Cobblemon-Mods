package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView

private fun normalizedNativeId(value: String): String = PublicIds.canonical(value)

internal fun nativeSpeciesId(speciesId: String, showdownSpeciesId: String): String {
    val supplied = normalizedNativeId(showdownSpeciesId)
    val publicSpecies = normalizedNativeId(speciesId)
    // Cobblemon's standard FormData can yield e.g. spiritombnormal while Showdown uses
    // spiritomb. The public form may also be absent for a disguised active Pokemon, so
    // resolve only the exact public-species + "normal" spelling, not an arbitrary suffix.
    // Named forms such as rotomheat retain their own species identity.
    return if (supplied == publicSpecies + "normal") publicSpecies else supplied
}

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

/** A complete numeric build; public opponent hypotheses also bind their four logical move slots. */
internal data class NativePokemonBuildHypothesis(
    val battlePokemonId: UUID,
    val knowledge: NativeBuildKnowledge,
    val abilityId: String,
    val itemId: String,
    val nature: String,
    val gender: String,
    val evs: Map<String, Int>,
    val ivs: Map<String, Int>,
    val teraTypeId: String? = null,
    val opponentMoveSet: NativeOpponentMoveSetHypothesis? = null,
) {
    init {
        require(normalizedNativeId(abilityId).isNotBlank())
        require(itemId.isBlank() || normalizedNativeId(itemId).isNotBlank())
        require(nature.isNotBlank())
        require(gender in setOf("M", "F", "N"))
        require(teraTypeId == null || normalizedNativeId(teraTypeId).isNotBlank())
        require(evs.keys == STAT_IDS && evs.values.all { it in 0..252 } && evs.values.sum() <= 510)
        require(ivs.keys == STAT_IDS && ivs.values.all { it in 0..31 })
        require(opponentMoveSet == null || opponentMoveSet.battlePokemonId == battlePokemonId) {
            "A bound opponent move set must belong to the same battle Pokemon"
        }
    }

    companion object {
        private val STAT_IDS = setOf("hp", "atk", "def", "spa", "spd", "spe")
    }
}

/** One bounded native world whose opponent builds own the normalized move slots they execute. */
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
    PUBLIC_ITEM_CONFLICT,
    PUBLIC_MOVE_CONFLICT,
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
        /** Rebuild a mid-battle board (see [NativeMidBattleStateRules]) instead of requiring the opening. */
        midBattle: Boolean = false,
    ): NativeInitialBattleDefinitionCompilation {
        require(seed.size == 4) { "Showdown PRNG seed must contain four integers" }
        val issues = linkedSetOf<NativeBattleDefinitionIssue>()
        val stateAccepted = if (midBattle) NativeMidBattleStateRules.blocker(state) == null else isInitialState(state)
        if (!stateAccepted) issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_STATE_NOT_INITIAL)

        val stateIds = state.pokemon.mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId)
        val identityById = identities.associateBy(NativePublicPokemonIdentity::battlePokemonId)
        val buildById = world.pokemon.associateBy(NativePokemonBuildHypothesis::battlePokemonId)
        if (identityById.size != identities.size || identityById.keys != stateIds || buildById.keys != stateIds) {
            issue(issues, NativeBattleDefinitionIssueCode.HYPOTHESIS_ROSTER_MISMATCH)
        }

        BattleSide.entries.forEach { side ->
            val sidePokemon = state.pokemon.filter { it.side == side }
            val alive = sidePokemon.filterNot(BattlePokemonStateView::fainted)
            if (sidePokemon.isEmpty() || state.remainingPokemonBySide.getValue(side) != alive.size) {
                issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_ROSTER_INCOMPLETE)
            }
            val expectedActive = minOf(if (state.format == BattleFormat.SINGLE) 1 else 2, alive.size)
            if (alive.mapNotNull(BattlePokemonStateView::activeSlot).sorted() != (0 until expectedActive).toList()) {
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
                // knownAbilityId is the live public ability, not necessarily the source-set ability:
                // Trace and native switch-in callbacks may legally change it before the first move.
                // The native opening frame must be reconciled with that public value after creation.
                val publicItems = buildList {
                    pokemon.knownHeldItemId?.let(::add)
                    addAll(revealedValues(state, id, BattleObservedEventKind.HELD_ITEM_REVEALED))
                }.map(::normalizedNativeId)
                val hypothesizedItem = normalizedNativeId(build.itemId)
                // Mid-battle the item may be gone (eaten, knocked off): only the first one revealed was the set's.
                val itemConflict = when {
                    midBattle && pokemon.side == BattleSide.ALLY -> false
                    midBattle -> revealedValues(state, id, BattleObservedEventKind.HELD_ITEM_REVEALED).firstOrNull()
                        ?.let(::normalizedNativeId)?.let { it != hypothesizedItem } == true
                    pokemon.side == BattleSide.ALLY -> publicItems.ifEmpty { listOf("") }.any { it != hypothesizedItem }
                    else -> publicItems.any { it != hypothesizedItem }
                }
                if (itemConflict) issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_ITEM_CONFLICT, id)
                if (pokemon.side == BattleSide.OPPONENT && build.opponentMoveSet != null) {
                    val publicMoves = pokemon.knownMoveIds.mapTo(linkedSetOf(), ::normalizedNativeId)
                    val worldMoves = build.opponentMoveSet.nativeMoveIds.toSet()
                    if (!worldMoves.containsAll(publicMoves)) {
                        issue(issues, NativeBattleDefinitionIssueCode.PUBLIC_MOVE_CONFLICT, id)
                    }
                }
            }

            val level = pokemon.level
            if (level == null) issue(issues, NativeBattleDefinitionIssueCode.LEVEL_UNAVAILABLE, id)
            val moves = concreteMoves(pokemon, catalog, build)
            if (build != null && moves.isEmpty()) {
                issue(issues, NativeBattleDefinitionIssueCode.MOVESET_UNAVAILABLE, id)
            }

            if (identity != null && build != null && level != null && moves.isNotEmpty()) {
                sets[id] = NativePokemonSet(
                    name = "native-${id.toString().takeLast(8)}",
                    species = nativeSpeciesId(identity.publicSpeciesId, identity.showdownSpeciesId),
                    moves = moves,
                    ability = normalizedNativeId(build.abilityId),
                    uuid = id.toString(),
                    item = normalizedNativeId(build.itemId),
                    nature = build.nature,
                    gender = build.gender,
                    teraType = build.teraTypeId?.let(::normalizedNativeId),
                    level = level,
                    evs = build.evs,
                    ivs = build.ivs,
                )
            }
        }

        if (issues.isNotEmpty()) return NativeInitialBattleDefinitionCompilation(null, issues.toList())
        // The public actives lead; a fainted Pokemon may still name the slot it fell in.
        fun team(side: BattleSide): List<NativePokemonSet> = state.pokemon.withIndex()
            .filter { it.value.side == side }
            .sortedWith(compareBy<IndexedValue<BattlePokemonStateView>> {
                if (it.value.fainted) Int.MAX_VALUE else it.value.activeSlot ?: Int.MAX_VALUE
            }.thenBy(IndexedValue<BattlePokemonStateView>::index))
            .map { sets.getValue(it.value.battlePokemonId) }
        return NativeInitialBattleDefinitionCompilation(
            definition = NativeBattleDefinition(
                formatId = if (state.format == BattleFormat.SINGLE) "cobblemonsingles" else "cobblemondoubles",
                seed = seed,
                p1Team = team(BattleSide.ALLY),
                p2Team = team(BattleSide.OPPONENT),
                situation = if (midBattle) situation(state, catalog, sets) else null,
            ),
            issues = emptyList(),
        )
    }

    private fun situation(
        state: BattleStateView,
        catalog: BattlePublicActionCatalogView,
        sets: Map<UUID, NativePokemonSet>,
    ): NativeBattleSituation = NativeBattleSituation(
        turn = state.turn,
        pokemon = state.pokemon.map { pokemon ->
            val id = pokemon.battlePokemonId
            val ally = pokemon.side == BattleSide.ALLY
            val item = if (ally) normalizedNativeId(pokemon.knownHeldItemId.orEmpty())
                else pokemon.knownHeldItemId?.let(::normalizedNativeId)
            val sinceSwitchIn = movesSinceSwitchIn(state, id)
            val active = pokemon.activeSlot != null && !pokemon.fainted
            val choiceItem = normalizedNativeId(item ?: sets.getValue(id).item) in CHOICE_ITEMS
            val exactMaxHp = pokemon.combatStats?.maxHp?.takeIf { ally && it.minimum == it.maximum }?.minimum
            NativePokemonSituation(
                uuid = id.toString(),
                hp = when {
                    pokemon.fainted -> 0
                    exactMaxHp != null -> Math.round(pokemon.hpFraction * exactMaxHp).toInt()
                    else -> null
                },
                hpFraction = if (pokemon.fainted) 0.0 else pokemon.hpFraction,
                status = pokemon.statusId?.let(::normalizedNativeId)?.takeUnless { it == "fnt" }.orEmpty(),
                boosts = pokemon.statStages.entries.mapNotNull { (stat, stage) ->
                    STAGE_IDS[normalizedNativeId(stat)]?.let { it to stage }
                }.toMap(),
                item = item,
                terastallized = pokemon.knownTeraTypeId?.let(::normalizedNativeId)?.takeIf(String::isNotEmpty)
                    ?.replaceFirstChar(Char::uppercaseChar),
                movePp = if (ally) {
                    catalog.forPokemon(id).associate { normalizedNativeId(it.moveId) to it.details.currentPp }
                } else {
                    emptyMap()
                },
                choiceLockedMove = sinceSwitchIn.lastOrNull()?.takeIf { active && choiceItem },
                movedSinceSwitchIn = active && sinceSwitchIn.isNotEmpty(),
                forme = NativeInBattleFormes.inBattleForme(pokemon.speciesId),
            )
        },
        weather = state.field.weather?.let(::effect),
        terrain = state.field.terrain?.let(::effect),
        pseudoWeather = (state.field.roomEffects + state.field.globalEffects).map(::effect),
        p1SideConditions = state.field.sideConditions[BattleSide.ALLY].orEmpty().map(::effect),
        p2SideConditions = state.field.sideConditions[BattleSide.OPPONENT].orEmpty().map(::effect),
    )

    private fun effect(view: BattleTimedEffectView) = NativeEffectSituation(
        id = normalizedNativeId(view.effectId),
        remainingTurns = view.remainingTurns ?: view.remainingTurnsRange?.maximum,
        layers = view.stacks,
    )

    /** The moves a Pokemon used since it last came in, oldest first. */
    private fun movesSinceSwitchIn(state: BattleStateView, pokemonId: UUID): List<String> {
        val events = state.observedEvents.filter { it.actorPokemonId == pokemonId }
        val since = events.indexOfLast { it.kind == BattleObservedEventKind.SWITCHED }
        return events.drop(since + 1).filter { it.kind == BattleObservedEventKind.MOVE_USED }
            .mapNotNull { it.publicValueId?.let(::normalizedNativeId) }
    }

    private fun concreteMoves(
        pokemon: BattlePokemonStateView,
        catalog: BattlePublicActionCatalogView,
        build: NativePokemonBuildHypothesis?,
    ): List<String> = when (pokemon.side) {
        BattleSide.ALLY -> catalog.forPokemon(pokemon.battlePokemonId).takeIf {
            catalog.isMoveSetComplete(pokemon.battlePokemonId) &&
                it.isNotEmpty() &&
                it.all { move -> move.knowledge == BattlePublicMoveKnowledge.EXACT_OWN }
        }?.map { normalizedNativeId(it.moveId) }
            ?.takeIf { moves -> moves.all(String::isNotBlank) && moves.distinct().size == moves.size }
            .orEmpty()
        BattleSide.OPPONENT -> build?.opponentMoveSet?.nativeMoveIds.orEmpty()
    }

    private fun revealedValues(
        state: BattleStateView,
        pokemonId: UUID,
        kind: BattleObservedEventKind,
    ): List<String> = state.observedEvents.asSequence()
        .filter { it.kind == kind && it.actorPokemonId == pokemonId }
        .mapNotNull { it.publicValueId }
        .toList()

    // Weather, terrain, field effects, stat stages and volatiles at the opening come from the leads' switch-in
    // abilities (Drought, Sand Stream, Electric Surge, Intimidate), which the native opening replays itself; the
    // root validator checks them against the public state. Only what a switch-in cannot cause makes it non-initial.
    private fun isInitialState(state: BattleStateView): Boolean =
        state.turn in 0..1 &&
            NativeOpeningStateRules.acceptsObservations(state) &&
            state.pokemon.all { pokemon ->
                pokemon.hpFraction == 1.0 &&
                    pokemon.statusId == null &&
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
    private val CHOICE_ITEMS = setOf("choiceband", "choicespecs", "choicescarf")
    private val STAGE_IDS = mapOf(
        "attack" to "atk", "atk" to "atk", "defence" to "def", "defense" to "def", "def" to "def",
        "specialattack" to "spa", "spa" to "spa", "specialdefence" to "spd", "specialdefense" to "spd",
        "spd" to "spd", "speed" to "spe", "spe" to "spe", "accuracy" to "accuracy", "evasion" to "evasion",
    )
}
