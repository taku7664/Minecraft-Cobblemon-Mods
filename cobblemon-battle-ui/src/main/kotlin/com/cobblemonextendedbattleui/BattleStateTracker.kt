package jbro.cobblemon.battleui.extended

import com.cobblemon.mod.common.pokemon.FormData
import jbro.cobblemon.battleui.extended.battle.state.*
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/**
 * Facade for all battle state tracking. Delegates to focused sub-trackers
 * in the battle.state package while maintaining a stable public API.
 *
 * All enums, data classes, and public function signatures are preserved
 * for backward compatibility with callers (BattleInfoPanel, BattleMessageInterceptor,
 * TeamIndicatorUI, etc.).
 */
object BattleStateTracker {

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums & Data Classes (stable public API)
    // ═══════════════════════════════════════════════════════════════════════════

    enum class BattleStat(val translationKey: String, val abbrKey: String) {
        ATTACK("cobblemon_battle_ui.stat.attack", "cobblemon_battle_ui.stat.attack.abbr"),
        DEFENSE("cobblemon_battle_ui.stat.defense", "cobblemon_battle_ui.stat.defense.abbr"),
        SPECIAL_ATTACK("cobblemon_battle_ui.stat.special_attack", "cobblemon_battle_ui.stat.special_attack.abbr"),
        SPECIAL_DEFENSE("cobblemon_battle_ui.stat.special_defense", "cobblemon_battle_ui.stat.special_defense.abbr"),
        SPEED("cobblemon_battle_ui.stat.speed", "cobblemon_battle_ui.stat.speed.abbr"),
        ACCURACY("cobblemon_battle_ui.stat.accuracy", "cobblemon_battle_ui.stat.accuracy.abbr"),
        EVASION("cobblemon_battle_ui.stat.evasion", "cobblemon_battle_ui.stat.evasion.abbr");

        val displayName: String get() = Text.translatable(translationKey).string
        val abbr: String get() = Text.translatable(abbrKey).string
    }

    data class DynamicTypeState(
        val primaryType: String?,
        val secondaryType: String?,
        val hasLostPrimaryType: Boolean = false,
        val addedTypes: List<String> = emptyList(),
        val originalPrimaryType: String?,
        val originalSecondaryType: String?
    )

    data class FormState(
        val currentForm: String,
        val originalForm: String? = null,
        val isMega: Boolean = false,
        val isTemporary: Boolean = false
    )

    enum class Weather(val translationKey: String, val icon: String) {
        RAIN("cobblemon_battle_ui.weather.rain", "🌧"),
        SUN("cobblemon_battle_ui.weather.sun", "☀"),
        SANDSTORM("cobblemon_battle_ui.weather.sandstorm", "🏜"),
        HAIL("cobblemon_battle_ui.weather.hail", "🌨"),
        SNOW("cobblemon_battle_ui.weather.snow", "❄");

        val displayName: String get() = Text.translatable(translationKey).string
    }

    data class WeatherState(
        val type: Weather,
        val startTurn: Int,
        var confirmedExtended: Boolean = false
    )

    enum class Terrain(val translationKey: String, val icon: String) {
        ELECTRIC("cobblemon_battle_ui.terrain.electric", "⚡"),
        GRASSY("cobblemon_battle_ui.terrain.grassy", "🌿"),
        MISTY("cobblemon_battle_ui.terrain.misty", "🌫"),
        PSYCHIC("cobblemon_battle_ui.terrain.psychic", "🔮");

        val displayName: String get() = Text.translatable(translationKey).string
    }

    data class TerrainState(
        val type: Terrain,
        val startTurn: Int,
        var confirmedExtended: Boolean = false
    )

    enum class FieldCondition(val translationKey: String, val icon: String, val baseDuration: Int) {
        TRICK_ROOM("cobblemon_battle_ui.field.trick_room", "🔄", 5),
        GRAVITY("cobblemon_battle_ui.field.gravity", "⬇", 5),
        MAGIC_ROOM("cobblemon_battle_ui.field.magic_room", "✨", 5),
        WONDER_ROOM("cobblemon_battle_ui.field.wonder_room", "🔀", 5);

        val displayName: String get() = Text.translatable(translationKey).string
    }

    data class FieldConditionState(
        val type: FieldCondition,
        val startTurn: Int
    )

    enum class SideCondition(val translationKey: String, val icon: String, val baseDuration: Int?, val maxStacks: Int = 1) {
        REFLECT("cobblemon_battle_ui.side.reflect", "🛡", 5),
        LIGHT_SCREEN("cobblemon_battle_ui.side.light_screen", "💡", 5),
        AURORA_VEIL("cobblemon_battle_ui.side.aurora_veil", "🌈", 5),
        TAILWIND("cobblemon_battle_ui.side.tailwind", "💨", 4),
        SAFEGUARD("cobblemon_battle_ui.side.safeguard", "🔰", 5),
        LUCKY_CHANT("cobblemon_battle_ui.side.lucky_chant", "🍀", 5),
        MIST("cobblemon_battle_ui.side.mist", "🌁", 5),
        STEALTH_ROCK("cobblemon_battle_ui.side.stealth_rock", "🪨", null),
        SPIKES("cobblemon_battle_ui.side.spikes", "📌", null, 3),
        TOXIC_SPIKES("cobblemon_battle_ui.side.toxic_spikes", "☠", null, 2),
        STICKY_WEB("cobblemon_battle_ui.side.sticky_web", "🕸", null);

        val displayName: String get() = Text.translatable(translationKey).string
    }

    data class SideConditionState(
        val type: SideCondition,
        val startTurn: Int,
        var stacks: Int = 1,
        var confirmedExtended: Boolean = false
    )

    enum class VolatileStatus(
        val translationKey: String,
        val icon: String,
        val isNegative: Boolean = true,
        val baseDuration: Int? = null,
        val countsDown: Boolean = false
    ) {
        LEECH_SEED("cobblemon_battle_ui.volatile.leech_seed", "🌱"),
        CONFUSION("cobblemon_battle_ui.volatile.confusion", "💫", baseDuration = 4),
        TAUNT("cobblemon_battle_ui.volatile.taunt", "😤", baseDuration = 3),
        ENCORE("cobblemon_battle_ui.volatile.encore", "🔁", baseDuration = 3),
        DISABLE("cobblemon_battle_ui.volatile.disable", "🚫", baseDuration = 4),
        TORMENT("cobblemon_battle_ui.volatile.torment", "😈"),
        INFATUATION("cobblemon_battle_ui.volatile.infatuation", "💕"),
        PERISH_SONG("cobblemon_battle_ui.volatile.perish_song", "💀", baseDuration = 4, countsDown = true),
        DROWSY("cobblemon_battle_ui.volatile.drowsy", "😴", baseDuration = 1),
        CURSE("cobblemon_battle_ui.volatile.curse", "👻"),
        NIGHTMARE("cobblemon_battle_ui.volatile.nightmare", "😱"),
        BOUND("cobblemon_battle_ui.volatile.bound", "⛓", baseDuration = 5),
        TRAPPED("cobblemon_battle_ui.volatile.trapped", "🚷"),
        SUBSTITUTE("cobblemon_battle_ui.volatile.substitute", "🎭", isNegative = false),
        AQUA_RING("cobblemon_battle_ui.volatile.aqua_ring", "💧", isNegative = false),
        INGRAIN("cobblemon_battle_ui.volatile.ingrain", "🌳", isNegative = false),
        FOCUS_ENERGY("cobblemon_battle_ui.volatile.focus_energy", "🎯", isNegative = false),
        MAGNET_RISE("cobblemon_battle_ui.volatile.magnet_rise", "🧲", isNegative = false, baseDuration = 5),
        EMBARGO("cobblemon_battle_ui.volatile.embargo", "📦", baseDuration = 5),
        HEAL_BLOCK("cobblemon_battle_ui.volatile.heal_block", "💔", baseDuration = 5),
        DESTINY_BOND("cobblemon_battle_ui.volatile.destiny_bond", "🔗", isNegative = false),
        FLINCH("cobblemon_battle_ui.volatile.flinch", "💥");

        val displayName: String get() = Text.translatable(translationKey).string
    }

    data class VolatileStatusState(
        val type: VolatileStatus,
        val startTurn: Int
    )

    enum class ItemStatus(val displaySuffix: String?) {
        HELD(null),
        KNOCKED_OFF("knocked off"),
        STOLEN("stolen"),
        SWAPPED("swapped"),
        CONSUMED("used"),
        DESTROYED("destroyed")
    }

    data class TrackedItem(
        val name: String,
        var status: ItemStatus,
        val revealTurn: Int,
        var removalTurn: Int? = null
    )

    data class BatonPassData(
        val stats: Map<BattleStat, Int>,
        val volatiles: Set<VolatileStatusState>
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Battle-level state (stays in facade)
    // ═══════════════════════════════════════════════════════════════════════════

    private var lastBattleId: UUID? = null

    var currentTurn: Int = 0
        private set

    var isSpectating: Boolean = false
        private set

    fun setSpectating(spectating: Boolean) {
        isSpectating = spectating
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Spectating mode = $spectating")
    }

    fun checkBattleChanged(battleId: UUID) {
        if (lastBattleId != battleId) {
            clear()
            lastBattleId = battleId
        }
    }

    fun setTurn(turn: Int) {
        currentTurn = turn
        ConditionTracker.checkForExpiredConditions(currentTurn)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Turn $turn")
    }

    fun clear() {
        lastBattleId = null
        currentTurn = 0
        isSpectating = false
        PokemonRegistry.clear()
        StatTracker.clear()
        ConditionTracker.clear()
        VolatileStatusTracker.clear()
        TypeTracker.clear()
        FormTracker.clear()
        AbilityItemTracker.clear()
        MoveTracker.clear()
        batonPassUsers.clear()
        BattleMessageInterceptor.clearMoveTracking()
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared all state")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pokemon Registry (delegates to PokemonRegistry)
    // ═══════════════════════════════════════════════════════════════════════════

    fun setPlayerNames(allyName: String, opponentName: String) = PokemonRegistry.setPlayerNames(allyName, opponentName)
    fun setPlayerNames(allyNames: Collection<String>, opponentNames: Collection<String>) = PokemonRegistry.setPlayerNames(allyNames, opponentNames)
    fun registerPokemon(uuid: UUID, name: String, isAlly: Boolean) {
        PokemonRegistry.registerPokemon(uuid, name, isAlly)
        VolatileStatusTracker.ensureInitialized(uuid)
    }
    fun registerPokemon(uuid: UUID, name: String, isAlly: Boolean, ownerName: String?) {
        PokemonRegistry.registerPokemon(uuid, name, isAlly, ownerName)
        VolatileStatusTracker.ensureInitialized(uuid)
    }
    fun isPokemonAlly(uuid: UUID): Boolean = PokemonRegistry.isPokemonAlly(uuid)
    fun getPokemonUuid(pokemonName: String, preferAlly: Boolean? = null): UUID? = PokemonRegistry.getPokemonUuid(pokemonName, preferAlly)

    // ── KO & Transform Tracking ──────────────────────────────────────────────

    fun markAsKO(pokemonName: String, preferAlly: Boolean? = null) = PokemonRegistry.markAsKO(pokemonName, preferAlly)
    fun markAsKO(uuid: UUID) = PokemonRegistry.markAsKO(uuid)
    fun isKO(uuid: UUID): Boolean = PokemonRegistry.isKO(uuid)

    fun markAsTransformed(pokemonName: String, preferAlly: Boolean? = null) = PokemonRegistry.markAsTransformed(pokemonName, preferAlly)
    fun markAsTransformed(uuid: UUID) = PokemonRegistry.markAsTransformed(uuid)
    fun isTransformed(uuid: UUID): Boolean = PokemonRegistry.isTransformed(uuid)
    fun clearTransformStatus(uuid: UUID) = PokemonRegistry.clearTransformStatus(uuid)

    // ═══════════════════════════════════════════════════════════════════════════
    // Stat Changes (delegates to StatTracker)
    // ═══════════════════════════════════════════════════════════════════════════

    fun applyStatChange(pokemonName: String, stat: BattleStat, stages: Int, preferAlly: Boolean? = null) = StatTracker.applyStatChange(pokemonName, stat, stages, preferAlly)
    fun setStatStage(pokemonName: String, stat: BattleStat, stage: Int, preferAlly: Boolean? = null) = StatTracker.setStatStage(pokemonName, stat, stage, preferAlly)
    fun clearPokemonStats(uuid: UUID) = StatTracker.clearPokemonStats(uuid)
    fun clearPokemonStatsByName(pokemonName: String, preferAlly: Boolean? = null) = StatTracker.clearPokemonStatsByName(pokemonName, preferAlly)
    fun clearAllStatsForAll() = StatTracker.clearAllStatsForAll()
    fun invertStats(pokemonName: String, preferAlly: Boolean? = null) = StatTracker.invertStats(pokemonName, preferAlly)
    fun copyStats(sourceName: String, targetName: String, sourceIsAlly: Boolean? = null, targetIsAlly: Boolean? = null) = StatTracker.copyStats(sourceName, targetName, sourceIsAlly, targetIsAlly)
    fun swapStats(pokemon1Name: String, pokemon2Name: String, pokemon1IsAlly: Boolean? = null, pokemon2IsAlly: Boolean? = null) = StatTracker.swapStats(pokemon1Name, pokemon2Name, pokemon1IsAlly, pokemon2IsAlly)
    fun swapSpecificStats(pokemon1Name: String, pokemon2Name: String, statsToSwap: List<BattleStat>, pokemon1IsAlly: Boolean? = null, pokemon2IsAlly: Boolean? = null) = StatTracker.swapSpecificStats(pokemon1Name, pokemon2Name, statsToSwap, pokemon1IsAlly, pokemon2IsAlly)
    fun stealPositiveStats(userPokemonName: String, targetPokemonName: String, userIsAlly: Boolean? = null, targetIsAlly: Boolean? = null) = StatTracker.stealPositiveStats(userPokemonName, targetPokemonName, userIsAlly, targetIsAlly)
    fun getStatChanges(uuid: UUID): Map<BattleStat, Int> = StatTracker.getStatChanges(uuid)
    fun getStatFromName(name: String): BattleStat? = StatTracker.getStatFromName(name)

    // ═══════════════════════════════════════════════════════════════════════════
    // Conditions (delegates to ConditionTracker)
    // ═══════════════════════════════════════════════════════════════════════════

    val weather: WeatherState? get() = ConditionTracker.weather
    val terrain: TerrainState? get() = ConditionTracker.terrain

    fun setWeather(type: Weather) = ConditionTracker.setWeather(type, currentTurn)
    fun clearWeather() = ConditionTracker.clearWeather(currentTurn)
    fun getWeatherTurnsRemaining(): String? = ConditionTracker.getWeatherTurnsRemaining(currentTurn)

    fun setTerrain(type: Terrain) = ConditionTracker.setTerrain(type, currentTurn)
    fun clearTerrain() = ConditionTracker.clearTerrain(currentTurn)
    fun getTerrainTurnsRemaining(): String? = ConditionTracker.getTerrainTurnsRemaining(currentTurn)

    fun setFieldCondition(type: FieldCondition) = ConditionTracker.setFieldCondition(type, currentTurn)
    fun clearFieldCondition(type: FieldCondition) = ConditionTracker.clearFieldCondition(type)
    fun getFieldConditions(): Map<FieldCondition, FieldConditionState> = ConditionTracker.getFieldConditions()
    fun getFieldConditionTurnsRemaining(type: FieldCondition): String? = ConditionTracker.getFieldConditionTurnsRemaining(type, currentTurn)

    fun setSideCondition(isPlayerSide: Boolean, type: SideCondition) = ConditionTracker.setSideCondition(isPlayerSide, type, currentTurn)
    fun clearSideCondition(isPlayerSide: Boolean, type: SideCondition) = ConditionTracker.clearSideCondition(isPlayerSide, type)
    fun getPlayerSideConditions(): Map<SideCondition, SideConditionState> = ConditionTracker.getPlayerSideConditions()
    fun getOpponentSideConditions(): Map<SideCondition, SideConditionState> = ConditionTracker.getOpponentSideConditions()
    fun swapSideConditions() = ConditionTracker.swapSideConditions()
    fun getSideConditionTurnsRemaining(isPlayerSide: Boolean, type: SideCondition): String? = ConditionTracker.getSideConditionTurnsRemaining(isPlayerSide, type, currentTurn)

    // ── Terastallization ─────────────────────────────────────────────────────

    fun setTerastallized(pokemonName: String, teraTypeName: String, preferAlly: Boolean? = null) {
        val uuid = PokemonRegistry.resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for Terastallization")
            return
        }
        setTerastallized(uuid, teraTypeName)
    }
    fun setTerastallized(uuid: UUID, teraTypeName: String) = ConditionTracker.setTerastallized(uuid, teraTypeName)
    fun isTerastallized(uuid: UUID): Boolean = ConditionTracker.isTerastallized(uuid)
    fun getTeraType(uuid: UUID): String? = ConditionTracker.getTeraType(uuid)

    // ═══════════════════════════════════════════════════════════════════════════
    // Volatile Statuses (delegates to VolatileStatusTracker)
    // ═══════════════════════════════════════════════════════════════════════════

    fun setVolatileStatus(pokemonName: String, type: VolatileStatus, preferAlly: Boolean? = null) = VolatileStatusTracker.setVolatileStatus(pokemonName, type, currentTurn, preferAlly)
    fun clearVolatileStatus(pokemonName: String, type: VolatileStatus, preferAlly: Boolean? = null) = VolatileStatusTracker.clearVolatileStatus(pokemonName, type, preferAlly)
    fun clearPokemonVolatiles(uuid: UUID) = VolatileStatusTracker.clearPokemonVolatiles(uuid)
    fun clearPokemonVolatilesByName(pokemonName: String, preferAlly: Boolean? = null) = VolatileStatusTracker.clearPokemonVolatilesByName(pokemonName, preferAlly)
    fun getVolatileStatuses(uuid: UUID): Set<VolatileStatusState> = VolatileStatusTracker.getVolatileStatuses(uuid)
    fun getVolatileTurnsRemaining(state: VolatileStatusState): String? = VolatileStatusTracker.getVolatileTurnsRemaining(state, currentTurn)
    fun applyPerishSongTo(activePokemon: Set<UUID>) = VolatileStatusTracker.applyPerishSongTo(activePokemon, currentTurn)

    // ═══════════════════════════════════════════════════════════════════════════
    // Dynamic Types (delegates to TypeTracker)
    // ═══════════════════════════════════════════════════════════════════════════

    fun initializeDynamicTypes(uuid: UUID, primaryType: String?, secondaryType: String?) = TypeTracker.initializeDynamicTypes(uuid, primaryType, secondaryType)
    fun setTypeReplacement(pokemonName: String, newPrimaryType: String?, newSecondaryType: String?, preferAlly: Boolean?) = TypeTracker.setTypeReplacement(pokemonName, newPrimaryType, newSecondaryType, preferAlly)
    fun loseType(pokemonName: String, typeName: String, preferAlly: Boolean?) = TypeTracker.loseType(pokemonName, typeName, preferAlly)
    fun addType(pokemonName: String, typeName: String, preferAlly: Boolean?) = TypeTracker.addType(pokemonName, typeName, preferAlly)
    fun getDynamicTypes(uuid: UUID): DynamicTypeState? = TypeTracker.getDynamicTypes(uuid)
    fun clearDynamicTypes(uuid: UUID) = TypeTracker.clearDynamicTypes(uuid)
    fun restoreOriginalTypes(pokemonName: String, preferAlly: Boolean? = null) = TypeTracker.restoreOriginalTypes(pokemonName, preferAlly)
    fun restoreOriginalTypes(uuid: UUID) = TypeTracker.restoreOriginalTypes(uuid)

    // ═══════════════════════════════════════════════════════════════════════════
    // Form Changes (delegates to FormTracker)
    // ═══════════════════════════════════════════════════════════════════════════

    fun registerSpeciesId(uuid: UUID, speciesId: Identifier) = FormTracker.registerSpeciesId(uuid, speciesId)
    fun getSpeciesId(uuid: UUID): Identifier? = FormTracker.getSpeciesId(uuid)
    fun getSpeciesIdByName(pokemonName: String, preferAlly: Boolean? = null): Identifier? = FormTracker.getSpeciesIdByName(pokemonName, preferAlly)

    fun setCurrentForm(pokemonName: String, formName: String, isMega: Boolean = false, isTemporary: Boolean = false, preferAlly: Boolean? = null) = FormTracker.setCurrentForm(pokemonName, formName, isMega, isTemporary, preferAlly)
    fun clearCurrentForm(pokemonName: String, preferAlly: Boolean? = null) = FormTracker.clearCurrentForm(pokemonName, preferAlly)
    fun getCurrentForm(uuid: UUID): FormState? = FormTracker.getCurrentForm(uuid)
    fun getCurrentFormByName(pokemonName: String, preferAlly: Boolean? = null): FormState? = FormTracker.getCurrentFormByName(pokemonName, preferAlly)

    fun formNameToAspects(formName: String): List<String> = FormTracker.formNameToAspects(formName)
    fun formNameToAspect(formName: String): String = FormTracker.formNameToAspect(formName)
    fun updateTypesForFormChange(pokemonName: String, speciesId: Identifier?, formName: String, preferAlly: Boolean? = null): FormData? = FormTracker.updateTypesForFormChange(pokemonName, speciesId, formName, preferAlly)

    // ═══════════════════════════════════════════════════════════════════════════
    // Abilities & Items (delegates to AbilityItemTracker)
    // ═══════════════════════════════════════════════════════════════════════════

    fun setRevealedAbility(pokemonName: String, abilityName: String, preferAlly: Boolean? = null) = AbilityItemTracker.setRevealedAbility(pokemonName, abilityName, preferAlly)
    fun setRevealedAbility(uuid: UUID, abilityName: String) = AbilityItemTracker.setRevealedAbility(uuid, abilityName)
    fun getRevealedAbility(uuid: UUID): String? = AbilityItemTracker.getRevealedAbility(uuid)
    fun getRevealedAbilityByName(pokemonName: String, preferAlly: Boolean? = null): String? = AbilityItemTracker.getRevealedAbilityByName(pokemonName, preferAlly)
    fun clearRevealedAbility(uuid: UUID) = AbilityItemTracker.clearRevealedAbility(uuid)
    fun replaceAbilityForTransform(uuid: UUID, copiedAbility: String?) = AbilityItemTracker.replaceAbilityForTransform(uuid, copiedAbility)

    fun setItem(pokemonName: String, itemName: String, status: ItemStatus, preferAlly: Boolean? = null) = AbilityItemTracker.setItem(pokemonName, itemName, status, currentTurn, preferAlly)
    fun getItem(uuid: UUID): TrackedItem? = AbilityItemTracker.getItem(uuid)
    fun getItemByName(pokemonName: String, preferAlly: Boolean? = null): TrackedItem? = AbilityItemTracker.getItemByName(pokemonName, preferAlly)
    fun revealHealingItem(pokemonName: String, itemName: String, preferAlly: Boolean? = null) = AbilityItemTracker.revealHealingItem(pokemonName, itemName, currentTurn, preferAlly)
    fun transferItem(fromPokemon: String, toPokemon: String, itemName: String, fromIsAlly: Boolean? = null, toIsAlly: Boolean? = null) = AbilityItemTracker.transferItem(fromPokemon, toPokemon, itemName, currentTurn, fromIsAlly, toIsAlly)
    fun receiveItemViaTrick(pokemonName: String, newItemName: String, preferAlly: Boolean? = null) = AbilityItemTracker.receiveItemViaTrick(pokemonName, newItemName, currentTurn, preferAlly)
    fun markItemSwapped(pokemonName: String, preferAlly: Boolean? = null) = AbilityItemTracker.markItemSwapped(pokemonName, currentTurn, preferAlly)

    // ═══════════════════════════════════════════════════════════════════════════
    // Moves & PP (delegates to MoveTracker)
    // ═══════════════════════════════════════════════════════════════════════════

    fun addRevealedMove(pokemonName: String, moveName: String, preferAlly: Boolean? = null) = MoveTracker.addRevealedMove(pokemonName, moveName, preferAlly)
    fun getRevealedMoves(uuid: UUID): Set<String> = MoveTracker.getRevealedMoves(uuid)

    // ═══════════════════════════════════════════════════════════════════════════
    // Baton Pass (coordinates between StatTracker and VolatileStatusTracker)
    // ═══════════════════════════════════════════════════════════════════════════

    private val BATON_PASS_VOLATILES = setOf(
        VolatileStatus.SUBSTITUTE,
        VolatileStatus.FOCUS_ENERGY,
        VolatileStatus.INGRAIN,
        VolatileStatus.AQUA_RING,
        VolatileStatus.MAGNET_RISE,
        VolatileStatus.CONFUSION,
        VolatileStatus.EMBARGO,
        VolatileStatus.HEAL_BLOCK
    )

    private val batonPassUsers = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()

    fun markBatonPassUsed(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = PokemonRegistry.resolvePokemonUuid(pokemonName, preferAlly) ?: return
        batonPassUsers.add(uuid)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName used Baton Pass")
    }

    private fun takeBatonPassData(uuid: UUID): BatonPassData? {
        if (!batonPassUsers.remove(uuid)) return null
        val stats = StatTracker.getRawStats(uuid) ?: emptyMap()
        val volatiles = VolatileStatusTracker.getRawVolatiles(uuid)
            ?.filter { it.type in BATON_PASS_VOLATILES }
            ?.toSet() ?: emptySet()
        return BatonPassData(stats, volatiles)
    }

    fun clearPokemonAfterSwitch(uuid: UUID): BatonPassData? {
        val batonData = takeBatonPassData(uuid)
        val wasTransformed = PokemonRegistry.isTransformed(uuid)
        StatTracker.clearPokemonStats(uuid)
        VolatileStatusTracker.clearPokemonVolatiles(uuid)
        PokemonRegistry.clearTransformStatus(uuid)
        if (wasTransformed) {
            AbilityItemTracker.clearRevealedAbility(uuid)
        }
        FormTracker.handleSwitchOut(uuid)
        return batonData
    }

    fun applyBatonPass(uuid: UUID, batonData: BatonPassData) {
        if (batonData.stats.isNotEmpty()) {
            StatTracker.applyBatonPassStats(uuid, batonData.stats)
        }

        if (batonData.volatiles.isNotEmpty()) {
            VolatileStatusTracker.applyBatonPassVolatiles(uuid, batonData.volatiles)
        }

        CobblemonExtendedBattleUI.LOGGER.debug(
            "BattleStateTracker: Applied Baton Pass to UUID $uuid: " +
            "${batonData.stats.size} stats, ${batonData.volatiles.size} volatiles"
        )
    }
}
