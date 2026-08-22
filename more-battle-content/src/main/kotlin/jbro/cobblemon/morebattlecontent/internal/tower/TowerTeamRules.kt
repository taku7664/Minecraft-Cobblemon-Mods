package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.validation.IdentifierSyntax

internal enum class TowerBattleFormat(
    val recordId: String,
    val selectionSize: Int,
) {
    SINGLE("single", 3),
    DOUBLE("double", 4),
}

internal data class TowerPokemonRegistration(
    val pokemonId: UUID,
    val speciesId: String,
    val heldItemId: String?,
    val level: Int,
    val legendaryClass: Boolean = false,
) {
    init {
        require(IdentifierSyntax.isResourceId(speciesId)) { "Invalid species ID: $speciesId" }
        require(heldItemId == null || IdentifierSyntax.isResourceId(heldItemId)) {
            "Invalid held item ID: $heldItemId"
        }
        require(level in MIN_LEVEL..MAX_LEVEL) { "Pokemon level must be between $MIN_LEVEL and $MAX_LEVEL" }
    }

    val battleLevel: Int
        get() = level.coerceAtMost(TOWER_BATTLE_LEVEL_CAP)
}

internal class TowerRegisteredTeam private constructor(members: List<TowerPokemonRegistration>) {
    val members: List<TowerPokemonRegistration> = members.immutableCopy()

    internal companion object {
        fun fromValidated(members: List<TowerPokemonRegistration>) = TowerRegisteredTeam(members)
    }
}

internal class TowerSelectedTeam internal constructor(
    val format: TowerBattleFormat,
    members: List<TowerPokemonRegistration>,
) {
    val members: List<TowerPokemonRegistration> = members.immutableCopy()
}

internal sealed interface TowerTeamRegistrationIssue {
    data class WrongTeamSize(val actual: Int, val required: Int = TOWER_REGISTERED_TEAM_SIZE) :
        TowerTeamRegistrationIssue

    data class DuplicatePokemon(val pokemonId: UUID) : TowerTeamRegistrationIssue
    data class DuplicateSpecies(val speciesId: String) : TowerTeamRegistrationIssue
    data class DuplicateHeldItem(val heldItemId: String) : TowerTeamRegistrationIssue
}

internal sealed interface TowerTeamRegistrationResult {
    data class Accepted(val team: TowerRegisteredTeam) : TowerTeamRegistrationResult
    data class Rejected(val issues: List<TowerTeamRegistrationIssue>) : TowerTeamRegistrationResult
}

internal sealed interface TowerTeamSelectionIssue {
    data class WrongSelectionSize(
        val format: TowerBattleFormat,
        val actual: Int,
        val required: Int = format.selectionSize,
    ) : TowerTeamSelectionIssue

    data class DuplicatePokemon(val pokemonId: UUID) : TowerTeamSelectionIssue
    data class UnregisteredPokemon(val pokemonId: UUID) : TowerTeamSelectionIssue
    data class LegendaryClassNotAllowed(val pokemonId: UUID) : TowerTeamSelectionIssue
}

internal sealed interface TowerTeamSelectionResult {
    data class Accepted(val selection: TowerSelectedTeam) : TowerTeamSelectionResult
    data class Rejected(val issues: List<TowerTeamSelectionIssue>) : TowerTeamSelectionResult
}

internal object TowerTeamRules {
    fun register(candidates: List<TowerPokemonRegistration>): TowerTeamRegistrationResult {
        val issues = buildList {
            if (candidates.size != TOWER_REGISTERED_TEAM_SIZE) {
                add(TowerTeamRegistrationIssue.WrongTeamSize(candidates.size))
            }
            candidates.duplicateValuesBy { it.pokemonId }.forEach {
                add(TowerTeamRegistrationIssue.DuplicatePokemon(it))
            }
            candidates.duplicateValuesBy { it.speciesId }.forEach {
                add(TowerTeamRegistrationIssue.DuplicateSpecies(it))
            }
            candidates.mapNotNull { it.heldItemId }.duplicateValues().forEach {
                add(TowerTeamRegistrationIssue.DuplicateHeldItem(it))
            }
        }

        return if (issues.isEmpty()) {
            TowerTeamRegistrationResult.Accepted(TowerRegisteredTeam.fromValidated(candidates))
        } else {
            TowerTeamRegistrationResult.Rejected(issues)
        }
    }

    fun select(
        team: TowerRegisteredTeam,
        format: TowerBattleFormat,
        pokemonIds: List<UUID>,
        legendaryClassAllowed: Boolean = false,
    ): TowerTeamSelectionResult {
        val registeredById = team.members.associateBy { it.pokemonId }
        val issues = buildList {
            if (pokemonIds.size != format.selectionSize) {
                add(TowerTeamSelectionIssue.WrongSelectionSize(format, pokemonIds.size))
            }
            pokemonIds.duplicateValues().forEach {
                add(TowerTeamSelectionIssue.DuplicatePokemon(it))
            }
            pokemonIds.distinct().filterNot(registeredById::containsKey).forEach {
                add(TowerTeamSelectionIssue.UnregisteredPokemon(it))
            }
            if (!legendaryClassAllowed) {
                pokemonIds.distinct().mapNotNull(registeredById::get).filter { it.legendaryClass }.forEach {
                    add(TowerTeamSelectionIssue.LegendaryClassNotAllowed(it.pokemonId))
                }
            }
        }

        return if (issues.isEmpty()) {
            TowerTeamSelectionResult.Accepted(
                TowerSelectedTeam(format, pokemonIds.map(registeredById::getValue)),
            )
        } else {
            TowerTeamSelectionResult.Rejected(issues)
        }
    }
}

private fun <T> List<T>.immutableCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))

private fun <T, V> Iterable<T>.duplicateValuesBy(selector: (T) -> V): List<V> = map(selector).duplicateValues()

private fun <T> Iterable<T>.duplicateValues(): List<T> {
    val seen = HashSet<T>()
    val duplicates = LinkedHashSet<T>()
    forEach { value ->
        if (!seen.add(value)) duplicates.add(value)
    }
    return duplicates.toList()
}

internal const val TOWER_REGISTERED_TEAM_SIZE = 6
internal const val TOWER_BATTLE_LEVEL_CAP = 50
private const val MIN_LEVEL = 1
private const val MAX_LEVEL = 100
