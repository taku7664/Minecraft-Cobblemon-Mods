package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic

internal data class ManagedPvePrepared(
    val playerId: UUID,
    val contentId: String,
    val trainerId: String,
    val trainerNameKey: String,
    val format: PveFormat,
    val mechanic: MajorBattleMechanic?,
    val playerTeam: List<BattlePokemon>,
    val opponentTeam: List<BattlePokemon>,
    val trainerProfile: BattleTrainerProfile,
    val brainSelectionContext: BattleBrainSelectionContext,
    val learningScopeId: UUID,
    val preview: BattleOpponentTeamPreviewView? = null,
    val appearance: jbro.cobblemon.morebattlecontent.api.presentation.TrainerResourceSkin? = null,
)

internal enum class PveFormat { SINGLE, DOUBLE }
internal enum class PveOutcome { WIN, LOSS }
internal sealed interface PveLaunchResult {
    data class Started(val battleId: UUID) : PveLaunchResult
    data object Unavailable : PveLaunchResult
}

internal fun protectManagedOpponent(pokemon: BattlePokemon) {
    Cobblemon173OpponentPokemonSafety.apply(pokemon.originalPokemon)
    Cobblemon173OpponentPokemonSafety.apply(pokemon.effectedPokemon)
    pokemon.effectedPokemon.heal()
}
