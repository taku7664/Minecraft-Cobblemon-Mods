package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import kotlin.math.floor
import jbro.cobblemon.mcc.internal.ai.*

/** Showdown's EatItem / Update effects from public items, without a scoring bonus. */
internal object LocalBerryMechanics {
    const val LAST_CONSUMED_ITEM = "better_ai:last_consumed_item="

    fun canEat(state: BattleStateView, holder: BattlePokemonStateView): Boolean =
        holder.activeSlot != null && !holder.fainted && holder.hpFraction > 0.0 &&
            state.pokemon.none { foe -> foe.side != holder.side && foe.activeSlot != null && !foe.fainted && foe.hpFraction > 0.0 &&
                LocalPublicAbilityState.effectiveKnownAbility(state, foe) in UNNERVE_ABILITIES }

    fun afterUpdate(state: BattleStateView): BattleStateView {
        var current = state
        for (initial in state.pokemon) {
            val holder = current.pokemon.first { it.battlePokemonId == initial.battlePokemonId }
            val item = LocalPublicItemState.activeItemId(current, holder) ?: continue
            if (!canEat(current, holder)) continue
            val status = canonical(holder.statusId)
            val confusion = holder.knownVolatileEffectIds.any { canonical(it) == "confusion" }
            val curesStatus = item == "lumberry" && status.isNotEmpty() || STATUS_BERRIES[item]?.contains(status) == true
            val curesConfusion = item in setOf("lumberry", "persimberry") && confusion
            val hpBerry = item in HEAL_BERRIES || item == "oranberry"
            val threshold = if (item in setOf("sitrusberry", "oranberry") ||
                LocalPublicAbilityState.effectiveKnownAbility(current, holder) == "gluttony") .5 else .25
            val heals = hpBerry && holder.hpFraction <= threshold && (item != "oranberry" || holder.combatStats?.maxHp != null)
            val boosts = item in STAT_BERRIES && holder.hpFraction <= threshold
            if (!curesStatus && !curesConfusion && !heals && !boosts) continue
            current = consume(current, holder.battlePokemonId, item)
            current = eatExternalBerry(current, holder.battlePokemonId, item)
        }
        return current
    }

    /** Records item use; unlike Knock Off, eating leaves a lastItem that Harvest may restore. */
    fun consume(state: BattleStateView, pokemonId: UUID, itemId: String): BattleStateView = state.copyState(pokemon = state.pokemon.map {
        if (it.battlePokemonId != pokemonId) it else {
            val markers = it.knownVolatileEffectIds.filterNot { marker -> marker.startsWith(LAST_CONSUMED_ITEM) }.toSet() +
                (LAST_CONSUMED_ITEM + canonical(itemId)) +
                if (LocalPublicAbilityState.effectiveKnownAbility(state, it) == "unburden") setOf("unburden") else emptySet()
            it.copyState(knownHeldItemId = null, knownVolatileEffectIds = markers)
        }
    })

    /** The user's held item is unchanged when Bug Bite or Pluck eats a target's berry. */
    fun eatExternalBerry(state: BattleStateView, pokemonId: UUID, berryId: String): BattleStateView {
        val holder = state.pokemon.firstOrNull { it.battlePokemonId == pokemonId } ?: return state
        if (holder.fainted || holder.hpFraction <= 0.0) return state
        val item = canonical(berryId)
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, holder)
        val ripen = if (ability == "ripen") 2.0 else 1.0
        var hp = holder.hpFraction
        val healBlocked = holder.knownVolatileEffectIds.any { canonical(it) == "healblock" }
        fun heal(fraction: Double) { if (!healBlocked) hp = LocalHpArithmetic.change(holder, hp, fraction).coerceAtMost(1.0) }
        val baseHeal = when (item) {
            "oranberry" -> holder.combatStats?.maxHp?.let { 10.0 / ((it.minimum.toDouble() + it.maximum) / 2.0) }
            else -> HEAL_BERRIES[item]
        }
        if (baseHeal != null) heal(roundedFraction(holder, baseHeal * ripen))
        if (ability == "cheekpouch") heal(roundedFraction(holder, 1.0 / 3.0))
        val status = canonical(holder.statusId)
        val curesStatus = item == "lumberry" || STATUS_BERRIES[item]?.contains(status) == true
        val volatiles = if (item in setOf("lumberry", "persimberry")) holder.knownVolatileEffectIds.filterNot {
            canonical(it) == "confusion"
        }.toSet() else holder.knownVolatileEffectIds
        var current = state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId == pokemonId) it.copyState(hpFraction = hp, statusId = if (curesStatus) null else it.statusId,
                knownVolatileEffectIds = volatiles) else it
        })
        STAT_BERRIES[item]?.let { boost -> current = LocalStatStageChange.apply(current, pokemonId, pokemonId,
            boost.mapValues { (_, amount) -> (amount * ripen).toInt() }) }
        return current
    }

    fun lastConsumedItem(holder: BattlePokemonStateView): String? = holder.knownVolatileEffectIds
        .firstOrNull { it.startsWith(LAST_CONSUMED_ITEM) }?.substringAfter(LAST_CONSUMED_ITEM)

    fun roundedFraction(holder: BattlePokemonStateView, fraction: Double): Double {
        val max = holder.combatStats?.maxHp
        return if (max != null && max.minimum == max.maximum)
            floor(max.minimum * fraction).coerceAtLeast(1.0) / max.minimum else fraction
    }
    private fun canonical(value: String?): String = value?.let(PublicIds::canonical).orEmpty()
    private val UNNERVE_ABILITIES = setOf("unnerve", "asoneglastrier", "asonespectrier")
    private val HEAL_BERRIES = mapOf("sitrusberry" to .25, "figyberry" to 1.0 / 3.0, "wikiberry" to 1.0 / 3.0,
        "magoberry" to 1.0 / 3.0, "aguavberry" to 1.0 / 3.0, "iapapaberry" to 1.0 / 3.0)
    private val STATUS_BERRIES = mapOf("cheriberry" to setOf("par", "paralysis", "paralyzed"),
        "chestoberry" to setOf("slp", "sleep", "asleep"), "pechaberry" to setOf("psn", "tox", "poison", "toxic", "poisoned", "badlypoisoned"),
        "rawstberry" to setOf("brn", "burn", "burned"), "aspearberry" to setOf("frz", "freeze", "frozen"))
    private val STAT_BERRIES = mapOf("liechiberry" to mapOf("attack" to 1), "ganlonberry" to mapOf("defence" to 1),
        "salacberry" to mapOf("speed" to 1), "petayaberry" to mapOf("special_attack" to 1), "apicotberry" to mapOf("special_defence" to 1))
}
