package jbro.cobblemon.battleui.extended.battle.messages

import jbro.cobblemon.battleui.extended.BattleStateTracker.BattleStat
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI
import net.minecraft.text.Text
import net.minecraft.text.TranslatableTextContent
import java.util.Locale

/**
 * Argument extraction utilities, move context tracking, and stat resolution
 * for parsing Cobblemon battle messages.
 */
object MessageParser {

    // Track last move user and target for moves that don't specify target in swap messages
    var lastMoveUser: String? = null
        private set
    var lastMoveTarget: String? = null
        private set
    var lastMoveName: String? = null
        private set
    var lastMoveKey: String? = null
        private set

    fun clearMoveTracking() {
        lastMoveUser = null
        lastMoveTarget = null
        lastMoveName = null
        lastMoveKey = null
    }

    fun trackMove(user: String, moveName: String, moveKey: String?, target: String?) {
        lastMoveUser = user
        lastMoveName = moveName
        lastMoveKey = moveKey
        lastMoveTarget = target
    }

    /**
     * Check if the last move matches a special move by translation key or English name fallback.
     */
    fun isMove(moveKeys: Set<String>, englishName: String): Boolean {
        if (lastMoveKey != null && moveKeys.any { lastMoveKey!!.equals(it, ignoreCase = true) }) {
            return true
        }
        return lastMoveName?.lowercase() == englishName
    }

    fun argToString(arg: Any): String {
        return when (arg) {
            is Text -> arg.string
            is String -> arg
            is TranslatableTextContent -> {
                Text.translatable(arg.key, *arg.args).string
            }
            else -> arg.toString()
        }
    }

    /**
     * Extract the actual Pokemon name from a message argument.
     * Handles nested TranslatableTextContent like "cobblemon.battle.owned_pokemon" which
     * wraps the Pokemon name inside: "%1$s's %2$s" where args[0]=owner, args[1]=pokemon.
     */
    fun extractPokemonName(arg: Any): String {
        when (arg) {
            is Text -> {
                val content = arg.content
                if (content is TranslatableTextContent) {
                    if (content.key == "cobblemon.battle.owned_pokemon" && content.args.size >= 2) {
                        return canonicalOwnedPokemonName(content.args[0], content.args[1])
                    }
                    if (content.key.startsWith("cobblemon.species.") && content.key.endsWith(".name")) {
                        return arg.string
                    }
                }
                return arg.string
            }
            is TranslatableTextContent -> {
                if (arg.key == "cobblemon.battle.owned_pokemon" && arg.args.size >= 2) {
                    return canonicalOwnedPokemonName(arg.args[0], arg.args[1])
                }
                return Text.translatable(arg.key, *arg.args).string
            }
            is String -> return arg
            else -> return arg.toString()
        }
    }

    /** Keeps structured owner data without depending on the active language's possessive grammar. */
    private fun canonicalOwnedPokemonName(ownerArg: Any, pokemonArg: Any): String =
        "${argToString(ownerArg)}'s ${argToString(pokemonArg)}"

    /**
     * Extract the translation key from an argument if it's a TranslatableTextContent.
     * Returns null if it's not translatable (plain string, etc.)
     */
    fun argToTranslationKey(arg: Any): String? {
        return when (arg) {
            is Text -> {
                val content = arg.content
                if (content is TranslatableTextContent) {
                    content.key
                } else {
                    null
                }
            }
            is TranslatableTextContent -> arg.key
            else -> null
        }
    }

    /** Returns a locale-independent Cobblemon type ID when the argument carries a type key. */
    fun extractTypeId(arg: Any): String {
        val key = argToTranslationKey(arg)
        if (key != null && key.startsWith("cobblemon.type.")) {
            return key.removePrefix("cobblemon.type.").removeSuffix(".name")
        }
        return argToString(arg).lowercase(Locale.ROOT)
    }

    /** Returns a locale-independent Cobblemon ability ID when the argument carries an ability key. */
    fun extractAbilityId(arg: Any): String {
        val key = argToTranslationKey(arg)
        if (key != null && key.startsWith("cobblemon.ability.")) {
            return key.removePrefix("cobblemon.ability.").removeSuffix(".desc")
        }
        return argToString(arg)
            .substringAfterLast(':')
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
    }

    /** Returns a locale-independent Cobblemon move ID when the argument carries a move key. */
    fun extractMoveId(arg: Any): String {
        val key = argToTranslationKey(arg)
        if (key != null && key.startsWith("cobblemon.move.")) {
            return key.removePrefix("cobblemon.move.")
        }
        return argToString(arg)
            .substringAfterLast(':')
            .substringAfterLast('.')
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
    }

    /**
     * Resolve a BattleStat from a stat argument using a 3-strategy approach:
     * 1. Translation key matching (language-independent)
     * 2. Reverse lookup via Cobblemon's translated stat names (any language)
     * 3. English string fallback
     */
    fun resolveStat(statArg: Any): BattleStat? {
        val statName = argToString(statArg)

        // Strategy 1: Try translation key
        val statKey = argToTranslationKey(statArg)
        var stat = statKey?.let { TranslationKeys.STAT_KEY_MAPPING[it] }

        // Strategy 2: Reverse lookup
        if (stat == null) {
            stat = getStatFromTranslatedName(statName)
            if (stat != null) {
                CobblemonExtendedBattleUI.LOGGER.debug("MessageParser: Stat resolved via reverse lookup - '$statName'")
            }
        } else {
            CobblemonExtendedBattleUI.LOGGER.debug("MessageParser: Stat resolved via key match - '$statKey'")
        }

        // Strategy 3: English fallback
        if (stat == null) {
            stat = TranslationKeys.STAT_NAME_MAPPING[statName.lowercase()]
            if (stat != null) {
                CobblemonExtendedBattleUI.LOGGER.debug("MessageParser: Stat resolved via English fallback - '$statName'")
            }
        }

        if (stat == null) {
            CobblemonExtendedBattleUI.LOGGER.debug("MessageParser: Unknown stat - key='$statKey', name='$statName'")
        }

        return stat
    }

    /**
     * Reverse lookup: Find stat by comparing received string against Cobblemon's translated stat names.
     */
    private fun getStatFromTranslatedName(translatedName: String): BattleStat? {
        val lowerName = translatedName.lowercase()
        for ((key, stat) in TranslationKeys.COBBLEMON_STAT_KEYS) {
            val translated = Text.translatable(key).string.lowercase()
            if (translated == lowerName) {
                return stat
            }
        }
        return null
    }
}
