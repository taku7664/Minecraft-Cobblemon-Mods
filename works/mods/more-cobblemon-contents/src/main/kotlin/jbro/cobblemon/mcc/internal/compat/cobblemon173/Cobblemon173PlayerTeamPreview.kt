package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView

/**
 * The trainer's view of the player's team before the battle: every party member that enters, in party order. The
 * trainer's native engine starts only from a known opponent roster, so every battle a brain plays hands it this.
 */
internal object Cobblemon173PlayerTeamPreview {
    fun of(team: List<BattlePokemon>): BattleOpponentTeamPreviewView = BattleOpponentTeamPreviewView(
        selectionSize = team.size,
        pokemon = team.mapIndexed { slot, battlePokemon ->
            val pokemon = battlePokemon.effectedPokemon
            BattleOpponentTeamPreviewPokemonView(
                previewSlotId = slot,
                speciesId = pokemon.species.resourceIdentifier.toString(),
                formId = pokemon.form.name.takeUnless { pokemon.form == pokemon.species.standardForm },
                level = pokemon.level,
            )
        },
    )
}
