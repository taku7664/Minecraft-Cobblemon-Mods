package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon

/** Applies MBC-only presentation rules to disposable Cobblemon battle copies. */
internal object Cobblemon173BattlePokemonAppearance {
    fun hideHeldItem(battlePokemon: BattlePokemon) {
        hideHeldItem(
            object : Cobblemon173BattlePokemonAppearanceTarget {
                override fun setOriginalBattleCopyHeldItemVisible(visible: Boolean) {
                    battlePokemon.originalPokemon.heldItemVisible = visible
                }

                override fun setEffectedBattleCopyHeldItemVisible(visible: Boolean) {
                    battlePokemon.effectedPokemon.heldItemVisible = visible
                }
            },
        )
    }

    fun hideHeldItems(vararg teams: Iterable<BattlePokemon>) {
        teams.forEach { team -> team.forEach(::hideHeldItem) }
    }

    internal fun hideHeldItem(target: Cobblemon173BattlePokemonAppearanceTarget) {
        target.setOriginalBattleCopyHeldItemVisible(false)
        target.setEffectedBattleCopyHeldItemVisible(false)
    }
}

internal interface Cobblemon173BattlePokemonAppearanceTarget {
    fun setOriginalBattleCopyHeldItemVisible(visible: Boolean)

    fun setEffectedBattleCopyHeldItemVisible(visible: Boolean)
}
