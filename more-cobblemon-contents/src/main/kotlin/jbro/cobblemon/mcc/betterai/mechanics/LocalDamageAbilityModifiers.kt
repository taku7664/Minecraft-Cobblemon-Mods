package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Damage multipliers from abilities and held items that the stat and power steps do not carry, as one factor on the
 * finished damage. A stat multiplier (Huge Power, Protosynthesis, Fur Coat, sand's Special Defence) moves damage by
 * about the same factor, so it is folded in here rather than threaded through every stat reader.
 *
 * The AI knows its own abilities and items exactly; an opponent's count once revealed, which is how every other
 * mechanic in this layer reads them.
 */
internal object LocalDamageAbilityModifiers {
    fun attacker(
        candidate: BattleActionCandidate,
        details: BattleMoveCandidateView,
        actor: BattlePokemonStateView?,
        ability: String?,
        item: String?,
        target: BattlePokemonStateView,
        typeChart: Double?,
        context: BattleDecisionContext,
    ): Double {
        actor ?: return 1.0
        val state = context.state
        val type = canonical(details.typeId)
        val physical = details.damageCategory == BattleMoveDamageCategory.PHYSICAL
        val special = details.damageCategory == BattleMoveDamageCategory.SPECIAL
        val flags = details.effects?.mechanicFlags.orEmpty().mapTo(hashSetOf(), ::canonical)
        val weather = LocalPublicFieldMechanics.effectiveWeatherId(state)
        val terrain = LocalPublicFieldMechanics.terrainId(state)
        val stab = actor.knownBaseStabTypeIds.any { canonical(it) == type } || actor.knownTypeIds.any { canonical(it) == type } ||
            canonical(actor.knownTeraTypeId) == type
        var multiplier = when (ability) {
            "hugepower", "purepower" -> if (physical) 2.0 else 1.0
            "adaptability" -> if (stab) 2.0 / 1.5 else 1.0
            "blaze" -> pinch(actor, type == "fire")
            "torrent" -> pinch(actor, type == "water")
            "overgrow" -> pinch(actor, type == "grass")
            "swarm" -> pinch(actor, type == "bug")
            "sheerforce" -> if (hasSecondaryEffect(details)) 1.3 else 1.0
            "ironfist" -> if ("punch" in flags) 1.2 else 1.0
            "sharpness" -> if ("slicing" in flags) 1.5 else 1.0
            "strongjaw" -> if ("bite" in flags) 1.5 else 1.0
            "megalauncher" -> if ("pulse" in flags) 1.5 else 1.0
            "toughclaws" -> if ("contact" in flags) 1.3 else 1.0
            "punkrock" -> if ("sound" in flags) 1.3 else 1.0
            "reckless" -> if (details.effects?.effects.orEmpty().any {
                    it.kind == BattleMoveEffectKind.RECOIL_FRACTION || it.kind == BattleMoveEffectKind.CRASH_RECOIL
                }) 1.2 else 1.0
            "tintedlens" -> if ((typeChart ?: 1.0) in 0.0001..0.9999) 2.0 else 1.0
            "solarpower" -> if (special && weather in SUN) 1.5 else 1.0
            "sandforce" -> if (weather in SAND && type in setOf("rock", "ground", "steel")) 1.3 else 1.0
            "transistor" -> if (type == "electric") 1.3 else 1.0
            "dragonsmaw" -> if (type == "dragon") 1.5 else 1.0
            "rockypayload" -> if (type == "rock") 1.5 else 1.0
            "steelworker" -> if (type == "steel") 1.5 else 1.0
            "waterbubble" -> if (type == "water") 2.0 else 1.0
            "gorillatactics" -> if (physical) 1.5 else 1.0
            "supremeoverlord" -> 1.0 + 0.1 * state.pokemon.count { it.side == actor.side && it.fainted }.coerceAtMost(5)
            "orichalcumpulse" -> if (physical && weather in SUN) 4.0 / 3.0 else 1.0
            // Pixilate and its kind: the Normal move they turned into their type is 20% stronger.
            "pixilate", "refrigerate", "aerilate", "galvanize", "normalize" -> if (printedType(candidate) == "normal") 1.2 else 1.0
            "sniper" -> if (LocalConditionalDamageAbilities.critical(candidate, state, actor, target)) 1.5 else 1.0
            "analytic" -> LocalConditionalDamageAbilities.analytic(candidate, actor, context)
            "stakeout" -> if ("stakeout_switched:${target.battlePokemonId}" in candidate.tags) 2.0 else 1.0
            "protean", "libero" -> if (stab) 1.0 else 1.5
            "hadronengine" -> if (special && terrain == "electricterrain") 4.0 / 3.0 else 1.0
            "protosynthesis" -> paradox(actor, item, weather in SUN, physical, special, offence = true)
            "quarkdrive" -> paradox(actor, item, terrain == "electricterrain", physical, special, offence = true)
            else -> 1.0
        }
        // Steely Spirit and the auras reach every Pokemon on the field.
        if (type == "steel" && activeWith(state, "steelyspirit", actor.side)) multiplier *= 1.5
        if (type == "fairy" && activeWith(state, "fairyaura", null)) multiplier *= auraFactor(state)
        if (type == "dark" && activeWith(state, "darkaura", null)) multiplier *= auraFactor(state)
        // A Ruin ability on the field lowers everyone else's stat by a quarter.
        val targetAbility = LocalPublicAbilityState.effectiveKnownAbility(state, target)
        if (physical && targetAbility != "swordofruin" && activeWith(state, "swordofruin", null)) multiplier /= 0.75
        if (special && targetAbility != "beadsofruin" && activeWith(state, "beadsofruin", null)) multiplier /= 0.75
        if (physical && ability != "tabletsofruin" && activeWith(state, "tabletsofruin", null, except = actor)) multiplier *= 0.75
        if (special && ability != "vesselofruin" && activeWith(state, "vesselofruin", null, except = actor)) multiplier *= 0.75
        multiplier *= typeBoostItem(item, type)
        return multiplier
    }

    fun defender(
        candidate: BattleActionCandidate,
        details: BattleMoveCandidateView,
        actor: BattlePokemonStateView?,
        target: BattlePokemonStateView,
        /** Null when Mold Breaker or the like ignores it. */
        ability: String?,
        typeChart: Double?,
        context: BattleDecisionContext,
    ): Double {
        val state = context.state
        val physical = details.damageCategory == BattleMoveDamageCategory.PHYSICAL
        val special = details.damageCategory == BattleMoveDamageCategory.SPECIAL
        val flags = details.effects?.mechanicFlags.orEmpty().mapTo(hashSetOf(), ::canonical)
        val weather = LocalPublicFieldMechanics.effectiveWeatherId(state)
        val terrain = LocalPublicFieldMechanics.terrainId(state)
        val types = target.knownTypeIds.mapTo(hashSetOf(), ::canonical)
        val superEffective = (typeChart ?: 1.0) > 1.0
        var multiplier = when (ability) {
            "multiscale", "shadowshield" -> if (target.hpFraction >= 0.999) 0.5 else 1.0
            "filter", "solidrock", "prismarmor" -> if (superEffective) 0.75 else 1.0
            "icescales" -> if (special) 0.5 else 1.0
            "furcoat" -> if (physical) 0.5 else 1.0
            "fluffy" -> if ("contact" in flags) 0.5 else 1.0
            "punkrock" -> if ("sound" in flags) 0.5 else 1.0
            "marvelscale" -> if (physical && target.statusId != null) 2.0 / 3.0 else 1.0
            "protosynthesis" -> paradox(target, LocalPublicItemState.activeItemId(state, target), weather in SUN, physical, special, offence = false)
            "quarkdrive" -> paradox(target, LocalPublicItemState.activeItemId(state, target), terrain == "electricterrain", physical, special, offence = false)
            else -> 1.0
        }
        // Glaive Rush leaves its user taking double damage until it moves again.
        if (target.knownVolatileEffectIds.any { canonical(it) == "glaiverush" }) multiplier *= 2.0
        // Delta Stream takes away a Flying type's weaknesses.
        if (weather == "deltastream" && "flying" in types &&
            canonical(details.typeId) in setOf("electric", "ice", "rock")) multiplier *= 0.5
        // Sandstorm raises a Rock type's Special Defence by half, snow an Ice type's Defence.
        if (special && weather in SAND && "rock" in types) multiplier /= 1.5
        if (physical && weather in SNOW && "ice" in types) multiplier /= 1.5
        // Eviolite raises both defences by half on a Pokemon that can still evolve, which species data says.
        if (LocalPublicItemState.activeItemId(state, target) == "eviolite" && LocalPublicSpeciesData.evolvesFurther(target)) {
            multiplier /= 1.5
        }
        // Friend Guard on the target's partner takes a quarter off.
        if (state.pokemon.any {
                it.side == target.side && it.battlePokemonId != target.battlePokemonId && it.activeSlot != null &&
                    !it.fainted && LocalPublicAbilityState.effectiveKnownAbility(state, it) == "friendguard"
            }) multiplier *= 0.75
        return multiplier
    }

    /** Blaze and its kind: half again the power of their type at a third of HP or less. */
    private fun pinch(actor: BattlePokemonStateView, matches: Boolean): Double =
        if (matches && actor.hpFraction <= 1.0 / 3.0) 1.5 else 1.0

    /**
     * Protosynthesis and Quark Drive boost the holder's highest stat by 30% (Speed by half) in sun or Electric Terrain,
     * or for good once a Booster Energy is held.
     */
    private fun paradox(
        pokemon: BattlePokemonStateView,
        item: String?,
        fieldActive: Boolean,
        physical: Boolean,
        special: Boolean,
        offence: Boolean,
    ): Double {
        if (!fieldActive && item != BOOSTER_ENERGY) return 1.0
        val stat = paradoxStat(pokemon) ?: return 1.0
        return when {
            offence && physical && stat == "attack" -> 1.3
            offence && special && stat == "special_attack" -> 1.3
            !offence && physical && stat == "defence" -> 1.0 / 1.3
            !offence && special && stat == "special_defence" -> 1.0 / 1.3
            else -> 1.0
        }
    }

    fun paradoxStat(pokemon: BattlePokemonStateView): String? {
        val stats = pokemon.combatStats ?: return null
        // Ties go to the earlier stat, as Showdown's loop does.
        return listOf(
            "attack" to stats.attack.maximum,
            "defence" to stats.defence.maximum,
            "special_attack" to stats.specialAttack.maximum,
            "special_defence" to stats.specialDefence.maximum,
            "speed" to stats.speed.maximum,
        ).fold<Pair<String, Int>, Pair<String, Int>?>(null) { best, next -> if (best == null || next.second > best.second) next else best }?.first
    }

    /** Whether a Protosynthesis or Quark Drive holder's Speed is the stat raised now. */
    fun paradoxSpeedActive(state: BattleStateView, pokemon: BattlePokemonStateView): Boolean {
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, pokemon)
        val item = LocalPublicItemState.activeItemId(state, pokemon)
        val active = when (ability) {
            "protosynthesis" -> LocalPublicFieldMechanics.effectiveWeatherId(state) in SUN || item == BOOSTER_ENERGY
            "quarkdrive" -> LocalPublicFieldMechanics.terrainId(state) == "electricterrain" || item == BOOSTER_ENERGY
            else -> false
        }
        return active && paradoxStat(pokemon) == "speed"
    }

    /** The move's own type in the dex, before any ability changed it. */
    private fun printedType(candidate: BattleActionCandidate): String? = runCatching {
        jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex.current().second.move(candidate.moveId.orEmpty().substringAfter(':'))?.type
    }.getOrNull()?.let(::canonical)

    fun hasSecondaryEffect(details: BattleMoveCandidateView): Boolean =
        details.effects?.effects.orEmpty().any { effect ->
            val chance = effect.probability ?: return@any false
            chance > 0.0 && chance < 1.0 && effect.kind in SECONDARY_KINDS
        }

    private fun activeWith(
        state: BattleStateView,
        ability: String,
        side: BattleSide?,
        except: BattlePokemonStateView? = null,
    ): Boolean = state.pokemon.any {
        it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 && (side == null || it.side == side) &&
            it.battlePokemonId != except?.battlePokemonId &&
            LocalPublicAbilityState.effectiveKnownAbility(state, it) == ability
    }

    private fun auraFactor(state: BattleStateView): Double =
        if (activeWith(state, "aurabreak", null)) 0.75 else 4.0 / 3.0

    private fun typeBoostItem(item: String?, type: String): Double {
        val boosted = TYPE_BOOST_ITEMS[item] ?: return 1.0
        return if (boosted == type) 1.2 else 1.0
    }

    private fun canonical(value: String?): String = value?.let(PublicIds::canonical).orEmpty()

    private const val BOOSTER_ENERGY = "boosterenergy"
    private val SUN = setOf("sun", "sunnyday", "harshsunlight", "desolateland")
    private val SAND = setOf("sand", "sandstorm")
    private val SNOW = setOf("snow", "snowscape")
    private val SECONDARY_KINDS = setOf(
        BattleMoveEffectKind.STATUS, BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectKind.VOLATILE_STATUS,
    )
    private val TYPE_BOOST_ITEMS = mapOf(
        "charcoal" to "fire", "mysticwater" to "water", "magnet" to "electric", "miracleseed" to "grass",
        "nevermeltice" to "ice", "blackbelt" to "fighting", "poisonbarb" to "poison", "softsand" to "ground",
        "sharpbeak" to "flying", "twistedspoon" to "psychic", "silverpowder" to "bug", "hardstone" to "rock",
        "spelltag" to "ghost", "dragonfang" to "dragon", "blackglasses" to "dark", "metalcoat" to "steel",
        "silkscarf" to "normal", "fairyfeather" to "fairy",
        "flameplate" to "fire", "splashplate" to "water", "zapplate" to "electric", "meadowplate" to "grass",
        "icicleplate" to "ice", "fistplate" to "fighting", "toxicplate" to "poison", "earthplate" to "ground",
        "skyplate" to "flying", "mindplate" to "psychic", "insectplate" to "bug", "stoneplate" to "rock",
        "spookyplate" to "ghost", "dracoplate" to "dragon", "dreadplate" to "dark", "ironplate" to "steel",
        "pixieplate" to "fairy",
    )
}
