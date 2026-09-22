package jbro.cobblemon.battleui.extended

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.battleui.extended.battle.messages.MessageParser
import jbro.cobblemon.battleui.extended.battle.messages.RawProtocolStateUpdater
import jbro.cobblemon.battleui.extended.battle.messages.StateUpdater
import jbro.cobblemon.battleui.extended.battle.messages.TranslationKeys
import net.minecraft.text.Text
import net.minecraft.text.TranslatableTextContent

/**
 * Parses Cobblemon battle messages (via TranslatableTextContent) and updates BattleStateTracker.
 * Delegates to TranslationKeys for constant lookups, MessageParser for argument extraction,
 * and StateUpdater for state mutation.
 */
object BattleMessageInterceptor {

    /**
     * Clear stale move tracking data. Called when battle state is cleared.
     */
    fun clearMoveTracking() {
        MessageParser.clearMoveTracking()
    }

    fun processMessages(messages: List<Text>) {
        for (message in messages) {
            processComponent(message)
        }
    }

    private fun processComponent(text: Text) {
        val contents = text.content

        if (contents !is TranslatableTextContent && RawProtocolStateUpdater.process(text.string) { pnx ->
                CobblemonClient.battle
                    ?.getPokemonFromPNX(pnx)
                    ?.second
                    ?.battlePokemon
                    ?.uuid
            }
        ) {
            return
        }

        if (contents is TranslatableTextContent) {
            val key = contents.key
            val args = contents.args

            if (key.startsWith("cobblemon.battle.")) {
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessage: key='$key', args=${args.map {
                    when (it) {
                        is Text -> it.string
                        else -> it.toString()
                    }
                }}")
            }

            if (key == TranslationKeys.TURN_KEY) {
                StateUpdater.extractTurn(args)
                return
            }

            // Track move usage with target: [user, moveName, target]
            if (key == "cobblemon.battle.used_move_on" && args.size >= 3) {
                val user = MessageParser.extractPokemonName(args[0])
                val moveName = MessageParser.argToString(args[1])
                val moveKey = MessageParser.argToTranslationKey(args[1])
                val moveId = MessageParser.extractMoveId(args[1])
                val target = MessageParser.extractPokemonName(args[2])
                MessageParser.trackMove(user, moveName, moveKey, target)
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Move tracked - $user used $moveName (key=$moveKey) on $target")

                BattleStateTracker.addRevealedMove(user, moveId)
            }

            // Track move usage without target (self-targeting): [user, moveName]
            if (key == "cobblemon.battle.used_move" && args.size >= 2) {
                val user = MessageParser.extractPokemonName(args[0])
                val moveName = MessageParser.argToString(args[1])
                val moveKey = MessageParser.argToTranslationKey(args[1])
                val moveId = MessageParser.extractMoveId(args[1])
                MessageParser.trackMove(user, moveName, moveKey, null)
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Self-move tracked - $user used $moveName (key=$moveKey)")

                BattleStateTracker.addRevealedMove(user, moveId)

                if (MessageParser.isMove(TranslationKeys.BATON_PASS_KEYS, TranslationKeys.BATON_PASS_NAME)) {
                    BattleStateTracker.markBatonPassUsed(user)
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // Ability Reveal Messages
            // ═══════════════════════════════════════════════════════════════════

            if (key == TranslationKeys.ABILITY_GENERIC_KEY && args.size >= 2) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                val abilityId = MessageParser.extractAbilityId(args[1])
                BattleStateTracker.setRevealedAbility(pokemonName, abilityId)
            }

            TranslationKeys.ABILITY_SINGLE_ARG_KEYS[key]?.let { abilityId ->
                if (args.isNotEmpty()) {
                    val pokemonName = MessageParser.extractPokemonName(args[0])
                    BattleStateTracker.setRevealedAbility(pokemonName, abilityId)
                }
            }

            if (key == TranslationKeys.ABILITY_TRACE_KEY && args.size >= 3) {
                val tracerName = MessageParser.extractPokemonName(args[0])
                val targetName = MessageParser.extractPokemonName(args[1])
                val copiedAbilityId = MessageParser.extractAbilityId(args[2])
                BattleStateTracker.setRevealedAbility(tracerName, copiedAbilityId)
                BattleStateTracker.setRevealedAbility(targetName, copiedAbilityId)
            }

            if (key == TranslationKeys.ABILITY_RECEIVER_KEY) {
                // Cobblemon renders the [of] Pokemon (the fainted ability donor) as arg 0.
                // The actual Receiver/Power of Alchemy holder is not retained in this Text,
                // so assigning the copied ability here would corrupt the donor's state.
                CobblemonExtendedBattleUI.LOGGER.debug(
                    "BattleMessageInterceptor: Receiver event omitted the receiving Pokemon; leaving ability ownership unchanged"
                )
            }

            if (key == TranslationKeys.ABILITY_REPLACE_KEY && args.size >= 2) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                val newAbilityId = MessageParser.extractAbilityId(args[1])
                BattleStateTracker.setRevealedAbility(pokemonName, newAbilityId)
            }

            if (key == TranslationKeys.ABILITY_MAGICBOUNCE_KEY && args.isNotEmpty()) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                BattleStateTracker.setRevealedAbility(pokemonName, "magicbounce")
            }

            TranslationKeys.ABILITY_START_KEYS[key]?.let { abilityId ->
                if (args.isNotEmpty()) {
                    val pokemonName = MessageParser.extractPokemonName(args[0])
                    BattleStateTracker.setRevealedAbility(pokemonName, abilityId)
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // Stat Boost/Unboost
            // ═══════════════════════════════════════════════════════════════════

            TranslationKeys.BOOST_MAGNITUDE_KEYS[key]?.let { stages ->
                StateUpdater.extractBoost(args, stages)
                return
            }

            TranslationKeys.UNBOOST_MAGNITUDE_KEYS[key]?.let { stages ->
                StateUpdater.extractBoost(args, -stages)
                return
            }

            if (key == "cobblemon.battle.setboost.bellydrum" || key == "cobblemon.battle.setboost.angerpoint") {
                StateUpdater.extractSetBoost(args)
                return
            }

            if (key == "cobblemon.battle.clearallboost") {
                BattleStateTracker.clearAllStatsForAll()
                return
            }

            if (key == "cobblemon.battle.clearboost") {
                StateUpdater.extractClearBoost(args)
                return
            }

            if (key == "cobblemon.battle.invertboost") {
                StateUpdater.extractInvertBoost(args)
                return
            }

            if (key == "cobblemon.battle.swapboost.heartswap" || key == "cobblemon.battle.swapboost.generic") {
                StateUpdater.extractSwapBoostAllStats(args)
                return
            }

            if (key == "cobblemon.battle.swapboost.powerswap") {
                StateUpdater.extractSwapBoostSpecific(args, listOf(BattleStateTracker.BattleStat.ATTACK, BattleStateTracker.BattleStat.SPECIAL_ATTACK))
                return
            }
            if (key == "cobblemon.battle.swapboost.guardswap") {
                StateUpdater.extractSwapBoostSpecific(args, listOf(BattleStateTracker.BattleStat.DEFENSE, BattleStateTracker.BattleStat.SPECIAL_DEFENSE))
                return
            }
            if (key == "cobblemon.battle.activate.speedswap") {
                StateUpdater.extractSwapBoostSpecific(args, listOf(BattleStateTracker.BattleStat.SPEED))
                return
            }

            if (key == "cobblemon.battle.copyboost.generic") {
                StateUpdater.extractCopyBoost(args)
                return
            }

            // ═══════════════════════════════════════════════════════════════════
            // Weather, Terrain, Field, Side Conditions
            // ═══════════════════════════════════════════════════════════════════

            TranslationKeys.WEATHER_START_KEYS[key]?.let { weather ->
                BattleStateTracker.setWeather(weather)
                return
            }

            if (key in TranslationKeys.WEATHER_END_KEYS) {
                BattleStateTracker.clearWeather()
                return
            }

            TranslationKeys.TERRAIN_START_KEYS[key]?.let { terrain ->
                BattleStateTracker.setTerrain(terrain)
                return
            }

            if (key in TranslationKeys.TERRAIN_END_KEYS) {
                BattleStateTracker.clearTerrain()
                return
            }

            TranslationKeys.FIELD_START_KEYS[key]?.let { condition ->
                BattleStateTracker.setFieldCondition(condition)
                return
            }

            TranslationKeys.FIELD_END_KEYS[key]?.let { condition ->
                BattleStateTracker.clearFieldCondition(condition)
                return
            }

            TranslationKeys.SIDE_START_KEYS[key]?.let { (condition, isAlly) ->
                BattleStateTracker.setSideCondition(isAlly, condition)
                return
            }

            TranslationKeys.SIDE_END_KEYS[key]?.let { (condition, isAlly) ->
                BattleStateTracker.clearSideCondition(isAlly, condition)
                return
            }

            // Court Change
            if (key == TranslationKeys.COURT_CHANGE_KEY) {
                BattleStateTracker.swapSideConditions()
                if (args.isNotEmpty()) {
                    val pokemonName = MessageParser.extractPokemonName(args[0])
                    BattleStateTracker.addRevealedMove(pokemonName, "courtchange")
                }
            }

            // Terastallization
            if (key == TranslationKeys.TERASTALLIZE_KEY && args.size >= 2) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                val teraType = MessageParser.extractTypeId(args[1])
                BattleStateTracker.setTerastallized(pokemonName, teraType)
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: $pokemonName Terastallized into $teraType type")
            }

            // ═══════════════════════════════════════════════════════════════════
            // Volatile Statuses
            // ═══════════════════════════════════════════════════════════════════

            TranslationKeys.VOLATILE_START_KEYS[key]?.let { volatileStatus ->
                StateUpdater.extractVolatileStatusStart(args, volatileStatus)
                return
            }

            TranslationKeys.VOLATILE_END_KEYS[key]?.let { volatileStatus ->
                StateUpdater.extractVolatileStatusEnd(args, volatileStatus)
                return
            }

            if (key in TranslationKeys.NIGHTMARE_CLEAR_KEYS) {
                StateUpdater.extractVolatileStatusEnd(args, BattleStateTracker.VolatileStatus.NIGHTMARE)
                if (key != TranslationKeys.DROWSY_END_KEY) return
            }

            if (key == TranslationKeys.DROWSY_END_KEY) {
                StateUpdater.extractVolatileStatusEnd(args, BattleStateTracker.VolatileStatus.DROWSY)
                return
            }

            TranslationKeys.VOLATILE_ACTIVATE_KEYS[key]?.let { volatileStatus ->
                StateUpdater.extractVolatileStatusStart(args, volatileStatus)
                return
            }

            // ═══════════════════════════════════════════════════════════════════
            // Item Tracking
            // ═══════════════════════════════════════════════════════════════════

            if (key in TranslationKeys.ITEM_REVEAL_KEYS) {
                StateUpdater.extractItemReveal(args)
                return
            }

            if (key == TranslationKeys.TRICK_KEY) {
                StateUpdater.extractTrickItem(args)
                return
            }

            if (key == TranslationKeys.LIFE_ORB_KEY) {
                StateUpdater.extractLifeOrbReveal(args)
                return
            }

            if (key == TranslationKeys.FRISK_KEY) {
                StateUpdater.extractFriskItem(args)
                return
            }

            if (key == TranslationKeys.THIEF_KEY) {
                StateUpdater.extractThiefItem(args)
                return
            }

            if (key == TranslationKeys.BESTOW_KEY) {
                StateUpdater.extractBestowItem(args)
                return
            }

            if (key in TranslationKeys.ITEM_CONSUMED_KEYS) {
                StateUpdater.extractItemConsumed(args)
                return
            }

            if (key in TranslationKeys.ITEM_DESTROYED_KEYS) {
                StateUpdater.extractItemDestroyed(args)
                return
            }

            TranslationKeys.ITEM_SINGLE_ARG_EVENTS[key]?.let { (itemTranslationKey, status) ->
                StateUpdater.extractItemSingleArg(args, itemTranslationKey, status)
                return
            }

            if (key in TranslationKeys.BERRY_DAMAGE_KEYS) {
                StateUpdater.extractBerryFromKey(key, args)
                return
            }

            if (key == TranslationKeys.KNOCKOFF_KEY) {
                StateUpdater.extractKnockOff(args)
                return
            }

            if (key == TranslationKeys.STEALEAT_KEY) {
                StateUpdater.extractStealEat(args)
                return
            }

            if (key == TranslationKeys.CORROSIVEGAS_KEY) {
                StateUpdater.extractCorrosiveGas(args)
                return
            }

            if (key in TranslationKeys.TRICK_ACTIVATE_KEYS) {
                StateUpdater.extractTrickActivation(args)
                return
            }

            if (key in TranslationKeys.HEALING_ITEM_KEYS) {
                StateUpdater.extractHealingItem(args)
                return
            }

            // Perish Song
            if (key == TranslationKeys.PERISH_SONG_FIELD_KEY) {
                val activePokemon = CobblemonClient.battle?.let { battle ->
                    (battle.side1.activeClientBattlePokemon + battle.side2.activeClientBattlePokemon)
                        .mapNotNull { it.battlePokemon?.uuid }
                        .toSet()
                }.orEmpty()
                BattleStateTracker.applyPerishSongTo(activePokemon)
                return
            }

            // Transform (Ditto)
            if (key == TranslationKeys.TRANSFORM_KEY) {
                StateUpdater.markPokemonTransformed(args)
                return
            }

            // ═══════════════════════════════════════════════════════════════════
            // Form Change Detection
            // ═══════════════════════════════════════════════════════════════════

            if (key == TranslationKeys.FORMECHANGE_PERMANENT_KEY && args.size >= 2) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                val formName = MessageParser.argToString(args[1])
                BattleStateTracker.setCurrentForm(pokemonName, formName, isMega = false, isTemporary = false)
                val speciesId = BattleStateTracker.getSpeciesIdByName(pokemonName)
                BattleStateTracker.updateTypesForFormChange(pokemonName, speciesId, formName)
            }

            if (key == TranslationKeys.FORMECHANGE_TEMPORARY_KEY && args.size >= 2) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                val formName = MessageParser.argToString(args[1])
                BattleStateTracker.setCurrentForm(pokemonName, formName, isMega = false, isTemporary = true)
                val speciesId = BattleStateTracker.getSpeciesIdByName(pokemonName)
                BattleStateTracker.updateTypesForFormChange(pokemonName, speciesId, formName)
            }

            if (key in TranslationKeys.FORMECHANGE_ENDED_KEYS && args.isNotEmpty()) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                BattleStateTracker.clearCurrentForm(pokemonName)
                BattleStateTracker.restoreOriginalTypes(pokemonName)
            }

            if ((key == TranslationKeys.MEGA_FORMECHANGE_KEY || key == TranslationKeys.MEGA_EVOLVED_KEY) && args.isNotEmpty()) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                BattleStateTracker.setCurrentForm(pokemonName, "Mega", isMega = true, isTemporary = false)
                val speciesId = BattleStateTracker.getSpeciesIdByName(pokemonName)
                BattleStateTracker.updateTypesForFormChange(pokemonName, speciesId, "Mega")
            }

            TranslationKeys.SPECIAL_FORMECHANGE_KEYS[key]?.let { formName ->
                if (args.isNotEmpty()) {
                    val pokemonName = MessageParser.extractPokemonName(args[0])
                    BattleStateTracker.setCurrentForm(pokemonName, formName, isMega = false, isTemporary = true)
                    val speciesId = BattleStateTracker.getSpeciesIdByName(pokemonName)
                    BattleStateTracker.updateTypesForFormChange(pokemonName, speciesId, formName)
                }
            }

            TranslationKeys.SPECIAL_FORMECHANGE_END_KEYS[key]?.let { revertFormName ->
                if (args.isNotEmpty()) {
                    val pokemonName = MessageParser.extractPokemonName(args[0])
                    BattleStateTracker.clearCurrentForm(pokemonName)
                    BattleStateTracker.restoreOriginalTypes(pokemonName)
                    CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: $pokemonName reverted to $revertFormName")
                }
            }

            if (key == TranslationKeys.DYNAMAX_KEY && args.isNotEmpty()) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                BattleStateTracker.setCurrentForm(pokemonName, "Dynamax", isMega = false, isTemporary = true)
            }

            if (key == TranslationKeys.GIGANTAMAX_KEY && args.isNotEmpty()) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                BattleStateTracker.setCurrentForm(pokemonName, "Gigantamax", isMega = false, isTemporary = true)
            }

            if (key == TranslationKeys.DYNAMAX_END_KEY && args.isNotEmpty()) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                BattleStateTracker.clearCurrentForm(pokemonName)
                return
            }

            // ═══════════════════════════════════════════════════════════════════
            // Type Modification Moves
            // ═══════════════════════════════════════════════════════════════════

            if (key in TranslationKeys.TYPE_CHANGE_KEYS && args.size >= 2) {
                val targetName = MessageParser.extractPokemonName(args[0])
                val newType = MessageParser.extractTypeId(args[1])
                BattleStateTracker.setTypeReplacement(targetName, newType, null, null)
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: $targetName type changed to $newType")
            }

            if (key in TranslationKeys.TYPE_ADD_KEYS && args.size >= 2) {
                val targetName = MessageParser.extractPokemonName(args[0])
                val addedType = MessageParser.extractTypeId(args[1])
                BattleStateTracker.addType(targetName, addedType, null)
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: $targetName gained $addedType type")
            }

            if (key in TranslationKeys.BURN_UP_KEYS && args.isNotEmpty()) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Burn Up detected for '$pokemonName'")
                BattleStateTracker.loseType(pokemonName, "Fire", null)
            }

            if (key in TranslationKeys.DOUBLE_SHOCK_KEYS && args.isNotEmpty()) {
                val pokemonName = MessageParser.extractPokemonName(args[0])
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Double Shock detected for '$pokemonName'")
                BattleStateTracker.loseType(pokemonName, "Electric", null)
            }

            // ═══════════════════════════════════════════════════════════════════
            // Faint / Switch
            // ═══════════════════════════════════════════════════════════════════

            if (key == TranslationKeys.FAINT_KEY) {
                StateUpdater.markPokemonFainted(args)
                return
            }
        }

        for (sibling in text.siblings) {
            processComponent(sibling)
        }
    }
}
