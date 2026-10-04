package jbro.cobblemon.ui.extended.battle.messages

import jbro.cobblemon.ui.extended.BattleStateTracker
import jbro.cobblemon.ui.extended.BattleStateTracker.BattleStat
import jbro.cobblemon.ui.extended.BattleStateTracker.ItemStatus
import jbro.cobblemon.ui.extended.BattleStateTracker.VolatileStatus
import jbro.cobblemon.ui.extended.CobblemonUi
import jbro.cobblemon.ui.extended.TeamIndicatorUI
import net.minecraft.network.chat.Component

/**
 * Takes parsed battle message arguments and calls the appropriate
 * BattleStateTracker methods to update state.
 */
object StateUpdater {

    fun extractTurn(args: Array<out Any>) {
        if (args.isEmpty()) return

        val turnStr = when (val arg0 = args[0]) {
            is Component -> arg0.string
            is String -> arg0
            is Number -> arg0.toString()
            else -> arg0.toString()
        }

        val turn = turnStr.toIntOrNull()
        if (turn != null) {
            BattleStateTracker.setTurn(turn)
        }
    }

    // Args: [pokemonName, statName]. stages > 0 for boost, < 0 for drop.
    fun extractBoost(args: Array<out Any>, stages: Int) {
        if (args.size < 2) {
            CobblemonUi.LOGGER.debug("StateUpdater: Boost args too short: ${args.size}")
            return
        }

        val pokemonName = MessageParser.extractPokemonName(args[0])
        val stat = MessageParser.resolveStat(args[1]) ?: return

        CobblemonUi.LOGGER.debug("StateUpdater: $pokemonName ${stat.abbr} ${if (stages > 0) "+" else ""}$stages")
        BattleStateTracker.applyStatChange(pokemonName, stat, stages)
    }

    // Belly Drum / Anger Point: sets Attack to +6
    fun extractSetBoost(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = MessageParser.extractPokemonName(args[0])
        CobblemonUi.LOGGER.debug("StateUpdater: $pokemonName Attack set to +6 (Belly Drum/Anger Point)")
        BattleStateTracker.setStatStage(pokemonName, BattleStat.ATTACK, 6)
    }

    fun extractClearBoost(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = MessageParser.extractPokemonName(args[0])
        CobblemonUi.LOGGER.debug("StateUpdater: Clearing all stats for $pokemonName")
        BattleStateTracker.clearPokemonStatsByName(pokemonName)
    }

    // Topsy-Turvy: invert all stat changes
    fun extractInvertBoost(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = MessageParser.extractPokemonName(args[0])
        CobblemonUi.LOGGER.debug("StateUpdater: Inverting stats for $pokemonName")
        BattleStateTracker.invertStats(pokemonName)
    }

    // Heart Swap: swap all stats. Single-arg variant uses lastMoveTarget.
    fun extractSwapBoostAllStats(args: Array<out Any>) {
        if (args.isEmpty()) {
            CobblemonUi.LOGGER.debug("StateUpdater: SwapBoostAllStats no args")
            return
        }

        val pokemon1 = MessageParser.extractPokemonName(args[0])
        val pokemon2: String

        if (args.size >= 2) {
            pokemon2 = MessageParser.extractPokemonName(args[1])
        } else {
            pokemon2 = MessageParser.lastMoveTarget ?: run {
                CobblemonUi.LOGGER.debug("StateUpdater: SwapBoostAllStats no target tracked for $pokemon1")
                return
            }
        }

        CobblemonUi.LOGGER.debug("StateUpdater: Swapping ALL stats between $pokemon1 and $pokemon2")
        BattleStateTracker.swapStats(pokemon1, pokemon2)
    }

    // Power/Guard/Speed Swap: swap specific stats. Single-arg variant uses lastMoveTarget.
    fun extractSwapBoostSpecific(args: Array<out Any>, statsToSwap: List<BattleStat>) {
        if (args.isEmpty()) {
            CobblemonUi.LOGGER.debug("StateUpdater: SwapBoostSpecific no args")
            return
        }

        val pokemon1 = MessageParser.extractPokemonName(args[0])
        val pokemon2: String

        if (args.size >= 2) {
            pokemon2 = MessageParser.extractPokemonName(args[1])
        } else {
            pokemon2 = MessageParser.lastMoveTarget ?: run {
                CobblemonUi.LOGGER.debug("StateUpdater: SwapBoostSpecific no target tracked for $pokemon1")
                return
            }
        }

        CobblemonUi.LOGGER.debug("StateUpdater: Swapping ${statsToSwap.map { it.abbr }} between $pokemon1 and $pokemon2")
        BattleStateTracker.swapSpecificStats(pokemon1, pokemon2, statsToSwap)
    }

    // Psych Up: copy all stat changes from source to copier
    fun extractCopyBoost(args: Array<out Any>) {
        if (args.size < 2) {
            CobblemonUi.LOGGER.debug("StateUpdater: CopyBoost needs 2 args, got ${args.size}")
            return
        }

        val copier = MessageParser.extractPokemonName(args[0])
        val source = MessageParser.extractPokemonName(args[1])

        CobblemonUi.LOGGER.debug("StateUpdater: $copier copies stats from $source")
        BattleStateTracker.copyStats(source, copier)
    }

    fun extractVolatileStatusStart(args: Array<out Any>, volatileStatus: VolatileStatus) {
        if (args.isEmpty()) {
            CobblemonUi.LOGGER.debug("StateUpdater: No args for volatile status start: ${volatileStatus.displayName}")
            return
        }

        val pokemonName = MessageParser.extractPokemonName(args[0])
        CobblemonUi.LOGGER.debug("StateUpdater: Volatile start - $pokemonName gained ${volatileStatus.displayName}")
        BattleStateTracker.setVolatileStatus(pokemonName, volatileStatus)
    }

    fun extractVolatileStatusEnd(args: Array<out Any>, volatileStatus: VolatileStatus) {
        if (args.isEmpty()) {
            CobblemonUi.LOGGER.debug("StateUpdater: No args for volatile status end: ${volatileStatus.displayName}")
            return
        }

        val pokemonName = MessageParser.extractPokemonName(args[0])
        CobblemonUi.LOGGER.debug("StateUpdater: Volatile end - $pokemonName lost ${volatileStatus.displayName}")
        BattleStateTracker.clearVolatileStatus(pokemonName, volatileStatus)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Item Extraction Functions
    // ═════════════════════════════════════════════════════════════════════════

    fun extractItemReveal(args: Array<out Any>) {
        if (args.size < 2) return
        val pokemonName = MessageParser.extractPokemonName(args[0])
        val itemName = MessageParser.argToString(args[1])
        BattleStateTracker.setItem(pokemonName, itemName, ItemStatus.HELD)
    }

    fun extractTrickItem(args: Array<out Any>) {
        if (args.size < 2) return
        val pokemonName = MessageParser.extractPokemonName(args[0])
        val itemName = MessageParser.argToString(args[1])
        BattleStateTracker.receiveItemViaTrick(pokemonName, itemName)
    }

    fun extractTrickActivation(args: Array<out Any>) {
        if (args.isEmpty()) return
        val userName = MessageParser.extractPokemonName(args[0])
        val targetName = args.getOrNull(1)?.let(MessageParser::extractPokemonName)
        CobblemonUi.LOGGER.debug(
            "StateUpdater: $userName used Trick/Switcheroo${targetName?.let { " on $it" }.orEmpty()}"
        )
        BattleStateTracker.markItemSwapped(userName)
        targetName?.let(BattleStateTracker::markItemSwapped)
    }

    fun extractLifeOrbReveal(args: Array<out Any>) {
        if (args.isEmpty()) return
        val pokemonName = MessageParser.extractPokemonName(args[0])
        CobblemonUi.LOGGER.debug("StateUpdater: Life Orb revealed for $pokemonName")
        val itemName = Component.translatable(TranslationKeys.LIFE_ORB_ITEM_KEY).string
        BattleStateTracker.setItem(pokemonName, itemName, ItemStatus.HELD)
    }

    fun extractFriskItem(args: Array<out Any>) {
        if (args.size < 2) return
        val targetPokemon = MessageParser.extractPokemonName(args[0])
        val itemName = MessageParser.argToString(args[1])
        BattleStateTracker.setItem(targetPokemon, itemName, ItemStatus.HELD)
    }

    fun extractThiefItem(args: Array<out Any>) {
        if (args.size < 3) return
        val thiefPokemon = MessageParser.extractPokemonName(args[0])
        val itemName = MessageParser.argToString(args[1])
        val victimPokemon = MessageParser.extractPokemonName(args[2])
        BattleStateTracker.transferItem(victimPokemon, thiefPokemon, itemName)
    }

    fun extractBestowItem(args: Array<out Any>) {
        if (args.size < 3) return
        val receiverPokemon = MessageParser.extractPokemonName(args[0])
        val itemName = MessageParser.argToString(args[1])
        val giverPokemon = MessageParser.extractPokemonName(args[2])
        BattleStateTracker.transferItem(giverPokemon, receiverPokemon, itemName)
    }

    fun extractItemConsumed(args: Array<out Any>) {
        if (args.size < 2) return
        val pokemonName = MessageParser.extractPokemonName(args[0])
        val itemName = MessageParser.argToString(args[1])
        BattleStateTracker.setItem(pokemonName, itemName, ItemStatus.CONSUMED)
    }

    fun extractItemDestroyed(args: Array<out Any>) {
        if (args.size < 2) return
        val pokemonName = MessageParser.extractPokemonName(args[0])
        val itemName = MessageParser.argToString(args[1])
        BattleStateTracker.setItem(pokemonName, itemName, ItemStatus.DESTROYED)
    }

    fun extractItemSingleArg(
        args: Array<out Any>,
        itemTranslationKey: String,
        status: ItemStatus
    ) {
        if (args.isEmpty()) return
        val pokemonName = MessageParser.extractPokemonName(args[0])
        val itemName = Component.translatable(itemTranslationKey).string
        BattleStateTracker.setItem(pokemonName, itemName, status)
    }

    fun extractBerryFromKey(key: String, args: Array<out Any>) {
        if (args.isEmpty()) return
        val pokemonName = MessageParser.extractPokemonName(args[0])
        val berryId = key.substringAfterLast(".")
        val itemId = berryId.removeSuffix("berry") + "_berry"
        val berryName = Component.translatable("item.cobblemon.$itemId").string
        BattleStateTracker.setItem(pokemonName, berryName, ItemStatus.CONSUMED)
    }

    fun extractKnockOff(args: Array<out Any>) {
        if (args.size < 2) return
        val targetPokemon = MessageParser.extractPokemonName(args[0])
        val itemName = MessageParser.argToString(args[1])
        BattleStateTracker.setItem(targetPokemon, itemName, ItemStatus.KNOCKED_OFF)
    }

    fun extractStealEat(args: Array<out Any>) {
        if (args.size < 3) return
        val itemName = MessageParser.argToString(args[1])
        val targetPokemon = MessageParser.extractPokemonName(args[0])
        BattleStateTracker.setItem(targetPokemon, itemName, ItemStatus.STOLEN)
    }

    fun extractCorrosiveGas(args: Array<out Any>) {
        extractItemDestroyed(args)
    }

    fun extractHealingItem(args: Array<out Any>) {
        if (args.size < 2) return
        val pokemonName = MessageParser.extractPokemonName(args[0])
        val itemName = MessageParser.argToString(args[1])
        BattleStateTracker.revealHealingItem(pokemonName, itemName)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Faint, Switch, Transform Handling
    // ═════════════════════════════════════════════════════════════════════════

    fun markPokemonFainted(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = MessageParser.extractPokemonName(args[0])
        CobblemonUi.LOGGER.debug("StateUpdater: Pokemon fainted - $pokemonName")

        BattleStateTracker.markAsKO(pokemonName)
        TeamIndicatorUI.markPokemonAsKO(pokemonName)
        BattleStateTracker.clearPokemonStatsByName(pokemonName)
        BattleStateTracker.clearPokemonVolatilesByName(pokemonName)
    }

    fun markPokemonTransformed(args: Array<out Any>) {
        if (args.size < 2) {
            CobblemonUi.LOGGER.debug("StateUpdater: Transform message needs 2 args, got ${args.size}")
            return
        }

        val transformerName = MessageParser.extractPokemonName(args[0])
        val targetName = MessageParser.extractPokemonName(args[1])

        CobblemonUi.LOGGER.debug("StateUpdater: Transform detected - '$transformerName' transformed into '$targetName'")

        BattleStateTracker.markAsTransformed(transformerName)
        TeamIndicatorUI.markPokemonAsTransformed(transformerName, targetName)
    }
}
