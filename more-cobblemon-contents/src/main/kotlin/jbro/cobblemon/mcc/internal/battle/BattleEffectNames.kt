package jbro.cobblemon.mcc.internal.battle

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.pokemon.helditem.CobblemonHeldItemManager
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import java.util.Locale

/**
 * Cobblemon writes a few battle lines with the name Showdown sent, as plain English text: an ability's popup
 * ("Orichalcum Pulse activated!"), Leftovers' heal, the move an `-activate` or `-start` names. Every player then reads
 * that name in English. This swaps such a name for the translated name of the ability, item, move or type it spells,
 * so each client shows it in its own language; text that names none of them stays as it was.
 */
object BattleEffectNames {
    enum class Kind { ABILITY, ITEM, MOVE, TYPE }

    /** Lines whose text argument is an ability's name: the popup, Trace, Receiver and the moves that replace one. */
    private val ABILITY_LINES = setOf("ability.generic", "ability.trace", "ability.receiver", "ability.replace")

    /** Lines whose text argument is a held item's name. */
    private val ITEM_LINES = setOf("damage.item", "heal.leftovers", "heal.item")

    /** What an `-activate` or `-start` effect argument can name, in the order a name is looked up. */
    private val EFFECT_KINDS = listOf(Kind.MOVE, Kind.ABILITY, Kind.ITEM, Kind.TYPE)

    private val NON_ID = Regex("[^a-z0-9]+")

    /** The kinds of name the text arguments of [key] (a `cobblemon.battle.` sub-key) can be, or null to leave it be. */
    fun kindsFor(key: String): List<Kind>? = when {
        key in ABILITY_LINES -> listOf(Kind.ABILITY)
        key in ITEM_LINES -> listOf(Kind.ITEM)
        key.startsWith("activate.") || key.startsWith("start.") -> EFFECT_KINDS
        else -> null
    }

    /** Translates the names among [args] that Cobblemon registers; the battle-line hook calls this. */
    @JvmStatic
    fun localize(key: String, args: Array<Any?>) = localize(key, args, ::registered)

    /** Replaces, in place, each text argument of [key] that [lookup] can name; other arguments are untouched. */
    fun localize(key: String, args: Array<Any?>, lookup: (Kind, String) -> Component?) {
        val kinds = kindsFor(key) ?: return
        for (index in args.indices) {
            val id = (args[index] as? String)?.let(::showdownId)?.takeIf(String::isNotEmpty) ?: continue
            kinds.firstNotNullOfOrNull { lookup(it, id) }?.let { args[index] = it }
        }
    }

    /** The id Showdown and Cobblemon key a name by: "Orichalcum Pulse" is `orichalcumpulse`. */
    fun showdownId(name: String): String = name.lowercase(Locale.ROOT).replace(NON_ID, "")

    /** The translated name Cobblemon registers for [id], or null when it has none (an item it never maps, a count). */
    private fun registered(kind: Kind, id: String): Component? = when (kind) {
        Kind.ABILITY -> Abilities.get(id)?.let { Component.translatable(it.displayName) }
        Kind.MOVE -> Moves.getByName(id)?.displayName?.copy()
        Kind.ITEM -> CobblemonHeldItemManager.nameOf(id).takeIf { it.contents is TranslatableContents }
        Kind.TYPE -> ElementalTypes.get(id)?.displayName?.copy()
    }
}
