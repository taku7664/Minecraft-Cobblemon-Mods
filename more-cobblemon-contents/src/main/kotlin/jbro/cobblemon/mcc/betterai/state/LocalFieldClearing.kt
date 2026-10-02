package jbro.cobblemon.mcc.betterai.state

import java.util.UUID
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Moves that clear the field in their callbacks, which declared effects do not carry: Rapid Spin and Mortal Spin
 * clear their user's side, Defog both sides' hazards and the target side's screens and the terrain, Tidy Up both
 * sides' hazards, Court Change swaps the sides' conditions, Brick Break and its kind break the target side's
 * screens, and Ice Spinner and Steel Roller end the terrain. The search only ever added these conditions.
 */
internal object LocalFieldClearing {
    fun apply(state: BattleStateView, userId: UUID, moveId: String?): BattleStateView {
        val id = moveId?.let(PublicIds::canonical) ?: return state
        if (id == "haze") {
            return state.derive(pokemon = state.pokemon.map {
                if (it.activeSlot == null || it.statStages.isEmpty()) it
                else it.copyState(statStages = emptyMap())
            })
        }
        if (id == "doubleshock") {
            return state.derive(pokemon = state.pokemon.map {
                if (it.battlePokemonId != userId) it
                else it.copyState(knownTypeIds = it.knownTypeIds.filterNot { type -> PublicIds.canonical(type) == "electric" }.toSet()
                    .ifEmpty { setOf("typeless") })
            })
        }
        if (id == "stoneaxe" || id == "ceaselessedge") {
            val user = state.pokemon.firstOrNull { it.battlePokemonId == userId } ?: return state
            val foe = if (user.side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
            val hazard = if (id == "stoneaxe") "stealthrock" else "spikes"
            val existing = state.field.sideConditions[foe].orEmpty()
            val current = existing.firstOrNull { PublicIds.canonical(it.effectId) == hazard }
            val maxStacks = if (hazard == "spikes") 3 else 1
            if (current != null && (current.stacks ?: 1) >= maxStacks) return state
            val added = if (current == null) jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView(hazard, null, 1)
                else jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView(current.effectId, null, (current.stacks ?: 1) + 1)
            val field = state.field
            return state.derive(field = BattleFieldStateView(
                weather = field.weather, terrain = field.terrain, roomEffects = field.roomEffects,
                globalEffects = field.globalEffects,
                sideConditions = BattleSide.entries.associateWith { side ->
                    if (side == foe) existing.filterNot { it === current } + added else field.sideConditions[side].orEmpty()
                },
            ))
        }
        if (id !in CLEARING_MOVES) return state
        val user = state.pokemon.firstOrNull { it.battlePokemonId == userId } ?: return state
        val own = user.side
        val foe = if (own == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val field = state.field
        fun without(side: BattleSide, ids: Set<String>) =
            field.sideConditions[side].orEmpty().filterNot { PublicIds.canonical(it.effectId) in ids }
        val sides = field.sideConditions.toMutableMap()
        var terrain = field.terrain
        when (id) {
            "rapidspin", "mortalspin" -> sides[own] = without(own, HAZARDS)
            "defog" -> {
                sides[own] = without(own, HAZARDS)
                sides[foe] = without(foe, HAZARDS + SCREENS + DEFOG_SIDE)
                terrain = null
            }
            "tidyup" -> {
                sides[own] = without(own, HAZARDS)
                sides[foe] = without(foe, HAZARDS)
            }
            "courtchange" -> {
                val mine = field.sideConditions[own].orEmpty().filter { PublicIds.canonical(it.effectId) in COURT_CHANGE }
                val theirs = field.sideConditions[foe].orEmpty().filter { PublicIds.canonical(it.effectId) in COURT_CHANGE }
                sides[own] = without(own, COURT_CHANGE) + theirs
                sides[foe] = without(foe, COURT_CHANGE) + mine
            }
            in SCREEN_BREAKERS -> sides[foe] = without(foe, SCREENS)
            "icespinner", "steelroller" -> terrain = null
        }
        val next = BattleFieldStateView(
            weather = field.weather,
            terrain = terrain,
            roomEffects = field.roomEffects,
            globalEffects = field.globalEffects,
            sideConditions = BattleSide.entries.associateWith { sides[it].orEmpty() },
        )
        return state.derive(field = next)
    }

    /** The damaging clearers, which act only through a hit that dealt damage. */
    fun needsDamage(moveId: String?): Boolean = moveId?.let(PublicIds::canonical) in DAMAGING_CLEARERS

    private val DAMAGING_CLEARERS = setOf(
        "rapidspin", "mortalspin", "brickbreak", "psychicfangs", "ragingbull", "icespinner", "steelroller",
        "stoneaxe", "ceaselessedge", "doubleshock",
    )
    private val HAZARDS = setOf("stealthrock", "spikes", "toxicspikes", "stickyweb", "gmaxsteelsurge")
    private val SCREENS = setOf("reflect", "lightscreen", "auroraveil")
    private val DEFOG_SIDE = setOf("safeguard", "mist")
    private val COURT_CHANGE = HAZARDS + SCREENS + setOf("tailwind", "safeguard", "mist", "luckychant")
    private val SCREEN_BREAKERS = setOf("brickbreak", "psychicfangs", "ragingbull")
    private val CLEARING_MOVES = setOf("rapidspin", "mortalspin", "defog", "tidyup", "courtchange", "icespinner", "steelroller") +
        SCREEN_BREAKERS
}
