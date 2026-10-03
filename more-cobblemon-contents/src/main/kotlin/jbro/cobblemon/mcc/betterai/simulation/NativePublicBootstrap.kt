package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.mechanics.copyState

/** A public board seed, distinct from pre-battle sets and from a private live engine snapshot. */
internal data class NativePublicBootstrap(
    val turn: Int,
    val pokemon: List<NativePublicPokemonSeed>,
    val weather: NativeTimedEffectFrame?,
    val terrain: NativeTimedEffectFrame?,
    val pseudoWeather: List<NativeTimedEffectFrame>,
    val p1SideConditions: List<NativeTimedEffectFrame>,
    val p2SideConditions: List<NativeTimedEffectFrame>,
)

internal data class NativePublicPokemonSeed(
    val uuid: String,
    val activeSlot: Int?,
    val hpFraction: Double,
    val publicPercentHp: Boolean,
    val status: String,
    val boosts: Map<String, Int>,
    val ability: String?,
    val item: String?,
    val movePp: Map<String, Int>,
    val activeTurns: Int,
    val activeMoveActions: Int,
    val lastMoveId: String?,
    val substituteHpFraction: Double?,
    val historyKnown: Boolean = true,
) {
    init {
        java.util.UUID.fromString(uuid)
        require(activeSlot == null || activeSlot in 0..1)
        require(hpFraction.isFinite() && hpFraction in 0.0..1.0)
        require(activeSlot == null || hpFraction > 0.0)
        require(boosts.keys.all { it in setOf("atk", "def", "spa", "spd", "spe", "accuracy", "evasion") } &&
            boosts.values.all { it in -6..6 })
        require(movePp.values.all { it >= 0 })
        require(activeTurns >= 0 && activeMoveActions >= 0)
        require(substituteHpFraction == null || substituteHpFraction.isFinite() && substituteHpFraction > 0.0)
    }
}

/**
 * Restarts native search only where public facts determine the seed. Hidden sleep/toxic/volatile
 * counters are never reset to their opening values. Unsupported boards keep the local fallback.
 */
internal object NativePublicBootstrapCompiler {
    fun hypothesisIssues(definition: NativeBattleDefinition, seed: NativePublicBootstrap, publicState: BattleStateView): List<String> = buildList {
        val byId = seed.pokemon.associateBy { it.uuid }
        val publicById = publicState.pokemon.associateBy { it.battlePokemonId.toString() }
        (definition.p1Team + definition.p2Team).forEach { set ->
            val mon = byId.getValue(set.uuid)
            // These counters can survive departure; a bench member may re-enter a future branch.
            if (id(mon.ability ?: set.ability) in HISTORY_ABILITIES) add("ABILITY_HISTORY_UNAVAILABLE@${set.uuid}")
            if (set.moves.any { id(it) in HISTORY_MOVES }) add("MOVE_HISTORY_UNAVAILABLE@${set.uuid}")
            val publicForm = formId(publicById.getValue(set.uuid).formId.orEmpty())
            val setForm = EngineRuntimeDex.current().second.species(set.species)?.forme?.let(::formId)
            if (setForm != null && setForm != publicForm) add("CURRENT_FORM_UNAVAILABLE@${set.uuid}")
            if (mon.activeSlot != null) {
                if (id(mon.item ?: set.item) in HISTORY_ITEMS) add("ITEM_HISTORY_UNAVAILABLE@${set.uuid}")
                if (!mon.historyKnown && (set.moves.any { id(it) in setOf("fakeout", "firstimpression") } ||
                        id(mon.item ?: set.item) in setOf("choiceband", "choicespecs", "choicescarf"))) {
                    add("ACTIVE_HISTORY_UNAVAILABLE@${set.uuid}")
                }
            }
        }
    }
    fun needed(state: BattleStateView): Boolean = state.turn !in 0..1 ||
        !NativeOpeningStateRules.acceptsObservations(state) ||
        state.pokemon.any { it.fainted || it.hpFraction != 1.0 || it.statusId != null ||
            it.knownHeldItemId?.isEmpty() == true ||
            it.statStages.isNotEmpty() || it.knownVolatileEffectIds.isNotEmpty() ||
            it.actionConstraints != BattlePokemonActionConstraintView.empty() } ||
        state.field.weather != null || state.field.terrain != null ||
        state.field.roomEffects.isNotEmpty() || state.field.globalEffects.isNotEmpty() ||
        state.field.sideConditions.values.any { it.isNotEmpty() }

    fun issues(state: BattleStateView): List<String> = buildList {
        if (state.observedEvents.any { it.turn == state.turn && it.kind == BattleObservedEventKind.MOVE_USED }) {
            add("PARTIAL_TURN_UNAVAILABLE")
        }
        state.pokemon.forEach { mon ->
            fun reject(reason: String) { add("$reason@${mon.battlePokemonId}") }
            if (id(mon.statusId.orEmpty()) !in SIMPLE_STATUSES) reject("STATUS_COUNTER_UNAVAILABLE")
            if (mon.actionConstraints != BattlePokemonActionConstraintView.empty()) reject("ACTION_HISTORY_UNAVAILABLE")
            val volatiles = mon.knownVolatileEffectIds.mapTo(linkedSetOf(), ::id)
            if (volatiles.any { it != "substitute" }) reject("VOLATILE_COUNTER_UNAVAILABLE")
            if ("substitute" in volatiles && mon.knownSubstituteHpFractionRange?.let {
                it.minimum == it.maximum
            } != true) reject("SUBSTITUTE_HP_UNAVAILABLE")
            if (mon.knownTeraTypeId != null) reject("MECHANIC_HISTORY_UNAVAILABLE")
            if (id(mon.knownAbilityId.orEmpty()) in HISTORY_ABILITIES) reject("ABILITY_HISTORY_UNAVAILABLE")
            val previous = state.observedEvents.lastOrNull {
                it.actorPokemonId == mon.battlePokemonId && it.kind == BattleObservedEventKind.MOVE_USED
            }?.publicValueId?.let(::id)
            if (previous in PROTECTION_MOVES) reject("PROTECTION_COUNTER_UNAVAILABLE")
        }
        BattleSide.entries.forEach { side ->
            val slots = state.pokemon.filter { it.side == side && !it.fainted && it.hpFraction > 0.0 }
                .mapNotNull { it.activeSlot }.sorted()
            if (slots.isEmpty() || slots != slots.indices.toList()) add("ACTIVE_LAYOUT_UNAVAILABLE@$side")
        }
        val effects = listOfNotNull(state.field.weather, state.field.terrain) + state.field.roomEffects +
            state.field.globalEffects + state.field.sideConditions.values.flatten()
        effects.forEach { effect ->
            if (id(effect.effectId) !in SUPPORTED_EFFECTS) add("FIELD_HISTORY_UNAVAILABLE@${effect.effectId}")
            if (effect.remainingTurnsRange != null) add("FIELD_DURATION_UNAVAILABLE@${effect.effectId}")
            if (id(effect.effectId) in TIMED_EFFECTS && effect.remainingTurns == null) {
                add("FIELD_DURATION_UNAVAILABLE@${effect.effectId}")
            }
        }
    }.distinct()

    /** Only a temporary set-compilation view; this state is never searched or returned to callers. */
    fun setCompilationState(state: BattleStateView, ownTeam: BattleExactOwnTeamView): BattleStateView {
        val capacity = if (state.format == BattleFormat.SINGLE) 1 else 2
        val active = BattleSide.entries.flatMap { side ->
            state.pokemon.filter { it.side == side }.sortedBy { it.activeSlot ?: Int.MAX_VALUE }
                .take(capacity).mapIndexed { slot, mon -> mon.battlePokemonId to slot }
        }.toMap()
        return BattleStateView(
            state.battleId, state.format, 1,
            state.pokemon.map { mon -> mon.copyState(
                activeSlot = active[mon.battlePokemonId], hpFraction = 1.0, statusId = null,
                statStages = emptyMap(), fainted = false, knownVolatileEffectIds = emptySet(),
                actionConstraints = BattlePokemonActionConstraintView.empty(),
                knownHeldItemId = if (mon.side == BattleSide.ALLY) ownTeam.buildFor(mon.battlePokemonId)?.heldItemId else null,
            ) },
            BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { side -> state.pokemon.count { it.side == side } },
            emptyList(), emptyList(),
        )
    }

    fun compile(state: BattleStateView, catalog: BattlePublicActionCatalogView): NativePublicBootstrap {
        require(issues(state).isEmpty()) { "Unsupported native public bootstrap: ${issues(state)}" }
        fun effect(value: BattleTimedEffectView) = NativeTimedEffectFrame(id(value.effectId), value.remainingTurns, value.stacks)
        return NativePublicBootstrap(
            state.turn.coerceAtLeast(1),
            state.pokemon.map { mon ->
                val history = state.observedEvents.filter { it.actorPokemonId == mon.battlePokemonId }
                val lastSwitch = history.lastOrNull { it.kind == BattleObservedEventKind.SWITCHED }
                val moves = history.filter { it.kind == BattleObservedEventKind.MOVE_USED &&
                    (lastSwitch == null || it.sequence > lastSwitch.sequence) }
                val activeTurns = if (mon.activeSlot == null) 0 else if (lastSwitch != null) {
                    (state.turn - lastSwitch.turn).coerceAtLeast(0)
                } else state.turn.coerceAtLeast(1)
                val pp = linkedMapOf<String, Int>()
                catalog.forPokemon(mon.battlePokemonId).forEach { pp[id(it.moveId)] = it.details.currentPp }
                catalog.inferredMovesForPokemon(mon.battlePokemonId)?.slots?.forEach { slot ->
                    slot.moveId?.let { move -> slot.details?.let { pp[id(move)] = it.currentPp } }
                }
                NativePublicPokemonSeed(
                    mon.battlePokemonId.toString(), mon.activeSlot.takeUnless { mon.fainted },
                    mon.hpFraction, mon.side == BattleSide.OPPONENT,
                    id(mon.statusId.orEmpty()), mon.statStages.mapKeys { statId(it.key) },
                    mon.knownAbilityId?.let(::id),
                    if (mon.side == BattleSide.ALLY) id(mon.knownHeldItemId.orEmpty()) else mon.knownHeldItemId?.let(::id),
                    pp, activeTurns, moves.size.coerceAtLeast(if (activeTurns > 1) 1 else 0),
                    moves.lastOrNull()?.publicValueId?.let(::id),
                    mon.knownSubstituteHpFractionRange?.minimum,
                    lastSwitch != null || moves.isNotEmpty(),
                )
            },
            state.field.weather?.let(::effect), state.field.terrain?.let(::effect),
            (state.field.roomEffects + state.field.globalEffects).map(::effect),
            state.field.sideConditions.getValue(BattleSide.ALLY).map(::effect),
            state.field.sideConditions.getValue(BattleSide.OPPONENT).map(::effect),
        )
    }

    private fun id(value: String) = PublicIds.canonical(value)
    private fun formId(value: String) = when (id(value)) {
        "normal", "standard", "default" -> ""
        "alolan" -> "alola"
        "galarian" -> "galar"
        "hisuian" -> "hisui"
        "paldean" -> "paldea"
        else -> id(value)
    }
    private fun statId(value: String): String = when (id(value)) {
        "attack" -> "atk"
        "defence", "defense" -> "def"
        "specialattack", "spatk" -> "spa"
        "specialdefence", "specialdefense", "spdef" -> "spd"
        "speed" -> "spe"
        else -> id(value)
    }
    private val SIMPLE_STATUSES = setOf("", "brn", "par", "psn", "frz")
    private val HISTORY_ABILITIES = setOf("truant", "unburden", "slowstart", "supremeoverlord", "illusion",
        "disguise", "iceface", "battlebond", "zerotohero", "protean", "libero", "cudchew",
        "protosynthesis", "quarkdrive", "flashfire")
    private val HISTORY_MOVES = setOf("ragefist", "lastresort", "retaliate", "belch", "recycle", "spitup",
        "swallow", "stockpile", "copycat", "mirrormove", "instruct", "pursuit", "furycutter",
        "rollout", "iceball", "echoedvoice", "lastrespects")
    private val HISTORY_ITEMS = setOf("metronome")
    private val PROTECTION_MOVES = setOf("protect", "detect", "endure", "kingsshield", "spikyshield",
        "banefulbunker", "obstruct", "silktrap", "burningbulwark", "wideguard", "quickguard")
    private val TIMED_EFFECTS = setOf("raindance", "sunnyday", "sandstorm", "snow", "hail",
        "electricterrain", "grassyterrain", "mistyterrain", "psychicterrain", "trickroom", "wonderroom",
        "magicroom", "gravity", "reflect", "lightscreen", "auroraveil", "tailwind", "safeguard", "mist")
    private val SUPPORTED_EFFECTS = TIMED_EFFECTS + setOf("stealthrock", "spikes", "toxicspikes", "stickyweb", "steelsurge")
}
