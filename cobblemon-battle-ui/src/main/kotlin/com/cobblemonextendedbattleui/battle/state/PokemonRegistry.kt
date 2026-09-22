package jbro.cobblemon.battleui.extended.battle.state

import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks UUID-to-name mappings, ally/opponent designation, KO status,
 * and transform status for all Pokemon in battle.
 *
 * Provides [resolvePokemonUuid] — the core name-to-UUID resolution used
 * by every other sub-tracker.
 */
object PokemonRegistry {

    // Maps lowercase name -> list of (UUID, isAlly) pairs to handle mirror matches
    private val nameToUuids = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<UUID, Boolean>>>()
    private val uuidIsAlly = ConcurrentHashMap<UUID, Boolean>()

    // Actor names and per-Pokemon owners disambiguate multi battles and mirror teams.
    private var allyPlayerNames: Set<String> = emptySet()
    private var opponentPlayerNames: Set<String> = emptySet()
    private val uuidOwnerNames = ConcurrentHashMap<UUID, String>()

    // KO tracking — persists after faint, only cleared on battle end
    private val knockedOutPokemon = ConcurrentHashMap.newKeySet<UUID>()

    // Transform tracking (Ditto via Transform/Impostor)
    private val transformedPokemon = ConcurrentHashMap.newKeySet<UUID>()

    fun clear() {
        nameToUuids.clear()
        uuidIsAlly.clear()
        allyPlayerNames = emptySet()
        opponentPlayerNames = emptySet()
        uuidOwnerNames.clear()
        knockedOutPokemon.clear()
        transformedPokemon.clear()
    }

    fun setPlayerNames(allyName: String, opponentName: String) {
        setPlayerNames(listOf(allyName), listOf(opponentName))
    }

    fun setPlayerNames(allyNames: Collection<String>, opponentNames: Collection<String>) {
        allyPlayerNames = allyNames.mapNotNull(::normalizeOwnerName).toSet()
        opponentPlayerNames = opponentNames.mapNotNull(::normalizeOwnerName).toSet()
        CobblemonExtendedBattleUI.LOGGER.debug(
            "PokemonRegistry: Player names set - Ally: $allyNames, Opponent: $opponentNames"
        )
    }

    fun registerPokemon(uuid: UUID, name: String, isAlly: Boolean) {
        registerPokemon(uuid, name, isAlly, null)
    }

    fun registerPokemon(uuid: UUID, name: String, isAlly: Boolean, ownerName: String?) {
        val lowerName = name.lowercase()
        val uuidList = nameToUuids.computeIfAbsent(lowerName) { CopyOnWriteArrayList() }

        val existingEntry = uuidList.find { it.first == uuid }
        if (existingEntry == null) {
            uuidList.add(Pair(uuid, isAlly))
            CobblemonExtendedBattleUI.LOGGER.debug("PokemonRegistry: Registered '$name' (${if (isAlly) "ally" else "opponent"}) with UUID $uuid")
        } else if (existingEntry.second != isAlly) {
            uuidList.remove(existingEntry)
            uuidList.add(Pair(uuid, isAlly))
        }

        uuidIsAlly[uuid] = isAlly
        normalizeOwnerName(ownerName)?.let { uuidOwnerNames[uuid] = it }
    }

    fun isPokemonAlly(uuid: UUID): Boolean = uuidIsAlly[uuid] ?: false

    fun getPokemonUuid(pokemonName: String, preferAlly: Boolean? = null): UUID? =
        resolvePokemonUuid(pokemonName, preferAlly)

    // ── KO Tracking ──────────────────────────────────────────────────────────

    fun markAsKO(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("PokemonRegistry: Unknown Pokemon '$pokemonName' for KO tracking")
            return
        }
        markAsKO(uuid)
    }

    fun markAsKO(uuid: UUID) {
        knockedOutPokemon.add(uuid)
        CobblemonExtendedBattleUI.LOGGER.debug("PokemonRegistry: Marked UUID $uuid as KO'd")
    }

    fun isKO(uuid: UUID): Boolean = knockedOutPokemon.contains(uuid)

    // ── Transform Tracking ───────────────────────────────────────────────────

    fun markAsTransformed(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("PokemonRegistry: Unknown Pokemon '$pokemonName' for transform tracking")
            return
        }
        markAsTransformed(uuid)
    }

    fun markAsTransformed(uuid: UUID) {
        transformedPokemon.add(uuid)
        CobblemonExtendedBattleUI.LOGGER.debug("PokemonRegistry: Marked UUID $uuid as transformed")
    }

    fun isTransformed(uuid: UUID): Boolean = transformedPokemon.contains(uuid)

    fun clearTransformStatus(uuid: UUID) {
        if (transformedPokemon.remove(uuid)) {
            CobblemonExtendedBattleUI.LOGGER.debug("PokemonRegistry: Cleared transform status for UUID $uuid")
        }
    }

    // ── Name → UUID Resolution ───────────────────────────────────────────────

    /**
     * Resolve Pokemon name to UUID. For mirror matches, disambiguates via:
     * 1. Owner prefix (e.g., "Player123's Togekiss")
     * 2. "opposing" or "the opposing" prefix (indicates opponent's Pokemon)
     * 3. preferAlly hint
     * Ambiguous matches are ignored instead of choosing the first registration.
     */
    fun resolvePokemonUuid(pokemonName: String, preferAlly: Boolean? = null): UUID? {
        var lookupName = pokemonName.lowercase()
        var ownerDeterminedSide: Boolean? = null

        val opposingPrefixes = listOf("the opposing ", "opposing ")
        for (prefix in opposingPrefixes) {
            if (lookupName.startsWith(prefix)) {
                lookupName = lookupName.removePrefix(prefix)
                ownerDeterminedSide = false
                CobblemonExtendedBattleUI.LOGGER.debug(
                    "PokemonRegistry: Detected opponent prefix in '$pokemonName', using name '$lookupName'"
                )
                break
            }
        }

        if (pokemonName.contains("'s ")) {
            val ownerName = pokemonName.substringBefore("'s ").lowercase()
            val strippedName = pokemonName.substringAfter("'s ").lowercase()

            if (ownerName in allyPlayerNames) {
                ownerDeterminedSide = true
                CobblemonExtendedBattleUI.LOGGER.debug(
                    "PokemonRegistry: Owner '$ownerName' matched an ally actor"
                )
            } else if (ownerName in opponentPlayerNames) {
                ownerDeterminedSide = false
                CobblemonExtendedBattleUI.LOGGER.debug(
                    "PokemonRegistry: Owner '$ownerName' matched an opponent actor"
                )
            }

            if (nameToUuids[lookupName] == null) {
                lookupName = strippedName
            }
        }

        val uuidList = nameToUuids[lookupName] ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug(
                "PokemonRegistry: Could not find Pokemon '$lookupName' (original: '$pokemonName') in registry"
            )
            return null
        }

        val ownerName = pokemonName.takeIf { it.contains("'s ") }
            ?.substringBefore("'s ")
            ?.let(::normalizeOwnerName)
        if (ownerName != null) {
            val ownerMatches = uuidList.filter { uuidOwnerNames[it.first] == ownerName }
            if (ownerMatches.size == 1) return ownerMatches.single().first
            if (ownerMatches.size > 1) {
                CobblemonExtendedBattleUI.LOGGER.warn(
                    "PokemonRegistry: Owner-qualified Pokemon '{}' still matched multiple UUIDs; ignoring update",
                    pokemonName
                )
            }
            CobblemonExtendedBattleUI.LOGGER.debug(
                "PokemonRegistry: No exact owner match for '$pokemonName'; ignoring update"
            )
            return null
        }

        if (uuidList.size == 1) {
            return uuidList[0].first
        }

        if (uuidList.size > 1) {
            val targetIsAlly = ownerDeterminedSide ?: preferAlly

            if (targetIsAlly != null) {
                val matches = uuidList.filter { it.second == targetIsAlly }
                if (matches.size == 1) {
                    val method = if (ownerDeterminedSide != null) "owner name" else "preferAlly hint"
                    CobblemonExtendedBattleUI.LOGGER.debug(
                        "PokemonRegistry: Resolved '$pokemonName' to ${if (targetIsAlly) "ally" else "opponent"} via $method"
                    )
                    return matches.single().first
                }
            }

            CobblemonExtendedBattleUI.LOGGER.warn(
                "PokemonRegistry: Ambiguous Pokemon '{}' in mirror match; ignoring the update instead of guessing a side",
                pokemonName
            )
            return null
        }

        return null
    }

    private fun normalizeOwnerName(name: String?): String? =
        name?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

}
