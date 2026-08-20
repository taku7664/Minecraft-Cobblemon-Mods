package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.validation.IdentifierSyntax

internal enum class PvpBattleFormat(
    val recordId: String,
    val registrationRange: IntRange,
    val selectionSize: Int,
) {
    SINGLE("single", 3..6, 3),
    DOUBLE("double", 4..6, 4),
}

internal class PvpRulesPreset private constructor(
    val battleLevel: Int,
    val entrySelectionSeconds: Int,
    val turnSelectionSeconds: Int,
    val totalBattleSecondsPerPlayer: Int,
    allowedMajorMechanics: Set<PvpBattleMechanic>,
    val bagItemsAllowed: Boolean,
    val experienceAllowed: Boolean,
    val prizeMoneyAllowed: Boolean,
) {
    val allowedMajorMechanics: Set<PvpBattleMechanic> = Collections.unmodifiableSet(LinkedHashSet(allowedMajorMechanics))

    init {
        require(battleLevel in 1..100) { "PvP battle level must be between 1 and 100" }
        require(entrySelectionSeconds > 0) { "PvP entry selection time must be positive" }
        require(turnSelectionSeconds > 0) { "PvP turn selection time must be positive" }
        require(totalBattleSecondsPerPlayer > 0) { "PvP total battle time must be positive" }
    }

    companion object {
        fun champions(
            entrySelectionSeconds: Int = 90,
            turnSelectionSeconds: Int = 45,
            totalBattleSecondsPerPlayer: Int = 7 * 60,
        ) = PvpRulesPreset(
            battleLevel = 50,
            entrySelectionSeconds = entrySelectionSeconds,
            turnSelectionSeconds = turnSelectionSeconds,
            totalBattleSecondsPerPlayer = totalBattleSecondsPerPlayer,
            allowedMajorMechanics = PvpBattleMechanic.entries.toSet(),
            bagItemsAllowed = false,
            experienceAllowed = false,
            prizeMoneyAllowed = false,
        )
    }
}

internal data class PvpPokemonRegistration(
    val pokemonId: UUID,
    val speciesId: String,
    val heldItemId: String?,
    val level: Int,
    /**
     * Cobblemon form name. Forms and regional variants are public information in competitive play,
     * so team preview renders them rather than falling back to the base model.
     */
    val formId: String? = null,
) {
    init {
        require(IdentifierSyntax.isResourceId(speciesId)) { "Invalid PvP species ID: $speciesId" }
        require(heldItemId == null || IdentifierSyntax.isResourceId(heldItemId)) { "Invalid PvP held item ID" }
        require(level in 1..100) { "PvP Pokemon level must be between 1 and 100" }
        require(formId == null || formId.isNotBlank()) { "PvP form ID cannot be blank" }
    }

    val battleLevel: Int
        get() = 50
}

internal class PvpRegisteredTeam internal constructor(
    val format: PvpBattleFormat,
    members: List<PvpPokemonRegistration>,
) {
    val members: List<PvpPokemonRegistration> = Collections.unmodifiableList(ArrayList(members))
}

internal class PvpSelectedTeam internal constructor(
    val format: PvpBattleFormat,
    members: List<PvpPokemonRegistration>,
) {
    val members: List<PvpPokemonRegistration> = Collections.unmodifiableList(ArrayList(members))
}

internal enum class PvpTeamIssue {
    WRONG_TEAM_SIZE,
    WRONG_SELECTION_SIZE,
    DUPLICATE_POKEMON,
    DUPLICATE_SPECIES,
    DUPLICATE_HELD_ITEM,
    UNREGISTERED_POKEMON,
    FORMAT_MISMATCH,
}

internal sealed interface PvpTeamRegistrationResult {
    data class Accepted(val team: PvpRegisteredTeam) : PvpTeamRegistrationResult
    data class Rejected(val issues: Set<PvpTeamIssue>) : PvpTeamRegistrationResult
}

internal sealed interface PvpTeamSelectionResult {
    data class Accepted(val team: PvpSelectedTeam) : PvpTeamSelectionResult
    data class Rejected(val issues: Set<PvpTeamIssue>) : PvpTeamSelectionResult
}

internal object PvpTeamRules {
    fun register(candidates: List<PvpPokemonRegistration>, format: PvpBattleFormat): PvpTeamRegistrationResult {
        val issues = linkedSetOf<PvpTeamIssue>()
        if (candidates.size !in format.registrationRange) issues += PvpTeamIssue.WRONG_TEAM_SIZE
        if (candidates.map(PvpPokemonRegistration::pokemonId).distinct().size != candidates.size) {
            issues += PvpTeamIssue.DUPLICATE_POKEMON
        }
        if (candidates.map(PvpPokemonRegistration::speciesId).distinct().size != candidates.size) {
            issues += PvpTeamIssue.DUPLICATE_SPECIES
        }
        val heldItems = candidates.mapNotNull(PvpPokemonRegistration::heldItemId)
        if (heldItems.distinct().size != heldItems.size) issues += PvpTeamIssue.DUPLICATE_HELD_ITEM
        return if (issues.isEmpty()) {
            PvpTeamRegistrationResult.Accepted(PvpRegisteredTeam(format, candidates))
        } else {
            PvpTeamRegistrationResult.Rejected(Collections.unmodifiableSet(issues))
        }
    }

    fun select(
        team: PvpRegisteredTeam,
        pokemonIds: List<UUID>,
        format: PvpBattleFormat,
    ): PvpTeamSelectionResult {
        val issues = linkedSetOf<PvpTeamIssue>()
        if (team.format != format) issues += PvpTeamIssue.FORMAT_MISMATCH
        if (pokemonIds.size != format.selectionSize) issues += PvpTeamIssue.WRONG_SELECTION_SIZE
        if (pokemonIds.distinct().size != pokemonIds.size) issues += PvpTeamIssue.DUPLICATE_POKEMON
        val registered = team.members.associateBy(PvpPokemonRegistration::pokemonId)
        if (pokemonIds.any { it !in registered }) issues += PvpTeamIssue.UNREGISTERED_POKEMON
        return if (issues.isEmpty()) {
            PvpTeamSelectionResult.Accepted(PvpSelectedTeam(format, pokemonIds.map(registered::getValue)))
        } else {
            PvpTeamSelectionResult.Rejected(Collections.unmodifiableSet(issues))
        }
    }
}
