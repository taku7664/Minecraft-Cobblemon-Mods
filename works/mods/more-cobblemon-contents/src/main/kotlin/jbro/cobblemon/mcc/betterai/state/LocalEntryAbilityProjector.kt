package jbro.cobblemon.mcc.betterai.state

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.LocalReactiveAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicSpeciesData
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex

/** Applies deterministic, publicly known ability effects caused by entering battle. */
internal object LocalEntryAbilityProjector {
    fun project(original: BattleStateView, incomingPokemonId: UUID): BattleStateView {
        val state = markEntry(original, incomingPokemonId)
        val incoming = state.pokemon.firstOrNull {
            it.battlePokemonId == incomingPokemonId && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return state
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, incoming)
        if (ability == "trace") {
            if (LocalReactiveAbilityState.TRACE_GAVE_UP in incoming.knownVolatileEffectIds) return state
            val copiedAbility = resolvedTraceAbility(state, incoming) ?: return state
            val copied = state.copyState(pokemon = state.pokemon.map {
                if (it.battlePokemonId == incomingPokemonId) it.copyState(knownAbilityId = copiedAbility,
                    knownBaseAbilityId = it.knownBaseAbilityId ?: it.knownAbilityId) else it
            })
            // Setting the copied ability runs its entry callback (weather, Intimidate, Download).
            return project(copied, incomingPokemonId)
        }
        val fieldState = projectField(state, incoming, ability)
        entryBoost(fieldState, incoming, ability)?.let { boost ->
            return LocalStatStageChange.apply(fieldState, incoming.battlePokemonId, null, boost)
        }
        if (ability != "intimidate") return fieldState

        val foes = fieldState.pokemon.filter {
            it.side != incoming.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        var next = fieldState
        for (foe in foes) {
            val current = next.pokemon.first { it.battlePokemonId == foe.battlePokemonId }
            // Intimidate never calls boost through a decoy, even when Contrary would turn it positive.
            if ("substitute" in current.canonicalKnownVolatileEffectIds) continue
            val targetAbility = LocalPublicAbilityState.effectiveKnownAbility(next, current)
            if (targetAbility in INTIMIDATE_IMMUNITIES) continue
            if (targetAbility == "guarddog") {
                // TryBoost receives the already capped change, so a floor of -6 gives it zero.
                val attack = current.statStages.entries.firstOrNull {
                    LocalStatStageChange.normalise(it.key) == "attack"
                }?.value ?: 0
                if (attack > -6) next = LocalStatStageChange.apply(next, current.battlePokemonId,
                    current.battlePokemonId, mapOf("attack" to 1), updateItems = false)
            } else {
                next = LocalStatStageChange.apply(next, current.battlePokemonId, incomingPokemonId,
                    mapOf("attack" to -1), updateItems = false)
                // AfterBoost still runs when an item or a stage limit removed the attempted drop.
                if (targetAbility == "rattled") next = LocalStatStageChange.apply(next, current.battlePokemonId,
                    current.battlePokemonId, mapOf("speed" to 1), updateItems = false)
            }
        }
        // Showdown updates held items after this entry event, including all Mirror Armor reflections.
        return (foes.map { it.battlePokemonId } + incomingPokemonId).fold(next, LocalStatStageChange::whiteHerb)
    }

    /** Trace samples eligible foes; hidden alternatives are limited to publicly legal species abilities. */
    fun projectOutcomes(state: BattleStateView, incomingPokemonId: UUID, entering: Boolean = true): List<LocalTraitStateBranch> {
        val incoming = state.pokemon.firstOrNull { it.battlePokemonId == incomingPokemonId } ?: return listOf(LocalTraitStateBranch(state, 1.0))
        fun unchanged() = listOf(LocalTraitStateBranch(if (entering) project(state, incomingPokemonId) else state, 1.0))
        if (LocalReactiveAbilityState.TRACE_GAVE_UP in incoming.knownVolatileEffectIds ||
            LocalPublicAbilityState.effectiveKnownAbility(state, incoming) != "trace" ||
            LocalPublicItemState.activeItemId(state, incoming) == "abilityshield") return unchanged()
        val foes = state.pokemon.filter { it.side != incoming.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        if (foes.isEmpty()) return unchanged()
        val possibilities = foes.map { publicPossibleAbilities(state, it) }
        if (possibilities.any { it.isEmpty() }) return unchanged()
        var assignments = listOf(emptyList<String>() to 1.0)
        for (possible in possibilities) assignments = assignments.flatMap { (selected, chance) ->
            possible.map { (selected + it) to chance / possible.size }
        }
        val dex = EngineRuntimeDex.current().second
        val copied = linkedMapOf<Pair<UUID?, String?>, Double>()
        for ((abilities, chance) in assignments) {
            val eligible = if (entering && "noability" in abilities) emptyList() else abilities.indices.filter {
                abilities[it] != "noability" && dex.abilityOrNull(abilities[it])?.flag("notrace") == false
            }
            if (eligible.isEmpty()) copied[null to null] = (copied[null to null] ?: 0.0) + chance
            else for (index in eligible) {
                val key = foes[index].battlePokemonId to abilities[index]
                copied[key] = (copied[key] ?: 0.0) + chance / eligible.size
            }
        }
        return copied.map { (sourceAndAbility, chance) ->
            val (sourceId, ability) = sourceAndAbility
            val selected = if (ability == null) state else state.copyState(pokemon = state.pokemon.map {
                if (it.battlePokemonId == incomingPokemonId) it.copyState(knownAbilityId = ability,
                    knownBaseAbilityId = it.knownBaseAbilityId ?: it.knownAbilityId)
                else if (it.battlePokemonId == sourceId) it.copyState(knownAbilityId = ability) else it
            })
            val projected = if (ability == null && !entering) selected else project(selected, incomingPokemonId)
            val updated = if (entering || LocalReactiveAbilityState.ENTERED_THIS_TURN in incoming.knownVolatileEffectIds) projected
                else projected.copyState(pokemon = projected.pokemon.map {
                    if (it.battlePokemonId == incomingPokemonId) it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds - LocalReactiveAbilityState.ENTERED_THIS_TURN) else it
                })
            LocalTraitStateBranch(updated, chance)
        }
    }

    /** A waiting Trace can acquire an eligible ability later, when Update follows another switch or move. */
    fun updateOutcomes(state: BattleStateView): List<LocalTraitStateBranch> {
        var branches = listOf(LocalTraitStateBranch(state, 1.0))
        for (holder in state.pokemon.filter { it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
            LocalPublicAbilityState.effectiveKnownAbility(state, it) == "trace" }) branches = branches.flatMap { parent ->
            projectOutcomes(parent.state, holder.battlePokemonId, entering = false).map { it.copy(probability = parent.probability * it.probability) }
        }
        return branches
    }

    private fun publicPossibleAbilities(state: BattleStateView, foe: BattlePokemonStateView): List<String> {
        canonical(foe.knownAbilityId)?.takeIf { it.isNotEmpty() }?.let { return listOf(it) }
        val knownInferences = state.inferences.filter { it.subjectPokemonId == foe.battlePokemonId && it.categoryId == "ability" }
        val ruledOut = knownInferences.filter { it.confidence == BattleInferenceConfidence.RULED_OUT }.mapNotNull { canonical(it.candidateId) }.toSet()
        val inferred = knownInferences.filter { it.confidence != BattleInferenceConfidence.RULED_OUT }.mapNotNull { canonical(it.candidateId) }.distinct()
        val species = LocalPublicSpeciesData.species(foe)
        val possible = if (inferred.isNotEmpty()) inferred else species?.abilities?.values.orEmpty().mapNotNull(::canonical)
        return possible.filter { it.isNotEmpty() && it !in ruledOut }.distinct()
    }

    private fun markEntry(state: BattleStateView, pokemonId: UUID): BattleStateView = state.copyState(pokemon = state.pokemon.map { incoming ->
        if (incoming.battlePokemonId != pokemonId || incoming.activeSlot == null || incoming.fainted || incoming.hpFraction <= 0.0) incoming else {
            val ability = LocalPublicAbilityState.effectiveKnownAbility(state, incoming)
            var markers = incoming.knownVolatileEffectIds + LocalReactiveAbilityState.ENTERED_THIS_TURN
            if (ability == "trace" && (LocalPublicItemState.activeItemId(state, incoming) == "abilityshield" || state.pokemon.any {
                    it.side != incoming.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 && canonical(it.knownAbilityId) == "noability"
                })) markers = markers + LocalReactiveAbilityState.TRACE_GAVE_UP
            if (ability == "slowstart" && !LocalReactiveAbilityState.slowStartActive(incoming)) markers = markers + (LocalReactiveAbilityState.SLOW_START_TURNS + "5")
            if (ability == "illusion") {
                val team = state.pokemon.filter { it.side == incoming.side }
                val ownIndex = team.indexOfFirst { it.battlePokemonId == pokemonId }
                val disguise = team.lastOrNull { !it.fainted && it.hpFraction > 0.0 }
                if (disguise != null && team.indexOf(disguise) > ownIndex &&
                    !(incoming.knownTeraTypeId != null && disguise.speciesId.substringAfter(':').startsWith("ogerpon")))
                    markers = markers + (LocalReactiveAbilityState.ILLUSION_AS + disguise.battlePokemonId)
            }
            incoming.copyState(knownVolatileEffectIds = markers)
        }
    })

    /** Resolve Trace only when public knowledge rules out every alternative copied ability. */
    private fun resolvedTraceAbility(state: BattleStateView, incoming: BattlePokemonStateView): String? {
        if (LocalPublicItemState.activeItemId(state, incoming) == "abilityshield") return null
        val foes = state.pokemon.filter {
            it.side != incoming.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        if (foes.isEmpty()) return null
        // Trace copies getAbility(), including an ability temporarily suppressed by Gastro Acid.
        // An unrevealed foe could be another valid target, so do not select the revealed one by default.
        val known = foes.map { canonical(it.knownAbilityId)?.takeIf(String::isNotEmpty) ?: return null }
        if ("noability" in known) return null // Showdown's onStart gives up in this case.
        val dex = EngineRuntimeDex.current().second
        val copyable = known.mapNotNull { id ->
            val definition = dex.abilityOrNull(id) ?: return null
            id.takeUnless { definition.flag("notrace") }
        }.distinct()
        return copyable.singleOrNull()
    }

    /**
     * Download raises Attack or Special Attack by the foes' weaker defence; Intrepid Sword and Dauntless Shield raise
     * Attack and Defence on entry.
     */
    private fun entryBoost(state: BattleStateView, incoming: BattlePokemonStateView, ability: String?): Map<String, Int>? =
        when (ability) {
            "intrepidsword" -> mapOf("attack" to 1)
            "dauntlessshield" -> mapOf("defence" to 1)
            "download" -> {
                val foes = state.pokemon.filter { it.side != incoming.side && it.activeSlot != null && !it.fainted }
                val defence = foes.sumOf { it.combatStats?.defence?.let { r -> (r.minimum + r.maximum) / 2 } ?: 0 }
                val special = foes.sumOf { it.combatStats?.specialDefence?.let { r -> (r.minimum + r.maximum) / 2 } ?: 0 }
                if (foes.isEmpty() || defence == 0 && special == 0) null
                else if (defence < special) mapOf("attack" to 1) else mapOf("special_attack" to 1)
            }
            else -> null
        }

    private fun projectField(
        state: BattleStateView,
        incoming: BattlePokemonStateView,
        ability: String?,
    ): BattleStateView {
        val requestedWeather = ENTRY_WEATHER[ability]
        val currentWeather = canonical(state.field.weather?.effectId)
        val weather = requestedWeather?.takeUnless {
            currentWeather in STRONG_WEATHERS && it !in STRONG_WEATHERS
        }
        val terrain = ENTRY_TERRAIN[ability]
        if (weather == null && terrain == null) return state
        val item = LocalPublicItemState.activeItemId(state, incoming)
        val weatherTurns = if (WEATHER_EXTENDERS[weather] == item) EXTENDED_FIELD_TURNS else ENTRY_FIELD_TURNS
        val terrainTurns = if (item == TERRAIN_EXTENDER) EXTENDED_FIELD_TURNS else ENTRY_FIELD_TURNS
        val field = BattleFieldStateView(
            weather = weather?.let { BattleTimedEffectView(it, weatherTurns) } ?: state.field.weather,
            terrain = terrain?.let { BattleTimedEffectView(it, terrainTurns) } ?: state.field.terrain,
            roomEffects = state.field.roomEffects,
            globalEffects = state.field.globalEffects,
            sideConditions = state.field.sideConditions,
        )
        return state.derive(
            field = field,
        )
    }

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private val INTIMIDATE_IMMUNITIES = setOf(
        "clearbody",
        "fullmetalbody",
        "hypercutter",
        "innerfocus",
        "oblivious",
        "owntempo",
        "scrappy",
        "whitesmoke",
    )
    private val ENTRY_WEATHER = mapOf(
        "drizzle" to "raindance",
        "drought" to "sunnyday",
        "sandstream" to "sandstorm",
        "snowwarning" to "snow",
        "orichalcumpulse" to "sunnyday",
    )
    private val ENTRY_TERRAIN = mapOf(
        "electricsurge" to "electricterrain",
        "grassysurge" to "grassyterrain",
        "mistysurge" to "mistyterrain",
        "psychicsurge" to "psychicterrain",
        "hadronengine" to "electricterrain",
    )
    private const val ENTRY_FIELD_TURNS = 5
    private const val EXTENDED_FIELD_TURNS = 8
    private const val TERRAIN_EXTENDER = "terrainextender"
    private val STRONG_WEATHERS = setOf("desolateland", "primordialsea", "deltastream")
    private val WEATHER_EXTENDERS = mapOf(
        "raindance" to "damprock",
        "sunnyday" to "heatrock",
        "sandstorm" to "smoothrock",
        "snow" to "icyrock",
    )
}
