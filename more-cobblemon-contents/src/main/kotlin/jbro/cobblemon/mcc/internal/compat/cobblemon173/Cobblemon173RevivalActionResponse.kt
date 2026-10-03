package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.*
import java.util.UUID
import net.minecraft.network.RegistryFriendlyByteBuf

/** Revival is a switch-shaped protocol response, but may target a fainted active Pokemon. */
internal class Cobblemon173RevivalActionResponse(private val targetId: UUID) :
    ShowdownActionResponse(ShowdownActionResponseType.SWITCH) {
    private val switch = SwitchActionResponse(targetId)

    override fun isValid(activeBattlePokemon: ActiveBattlePokemon, showdownMoveSet: ShowdownMoveset?, forceSwitch: Boolean): Boolean =
        forceSwitch && activeBattlePokemon.actor.request?.side?.pokemon?.any {
            it.uuid == activeBattlePokemon.battlePokemon?.uuid && it.reviving
        } == true && activeBattlePokemon.actor.pokemonList.any { it.uuid == targetId && it.health <= 0 }

    override fun toShowdownString(activeBattlePokemon: ActiveBattlePokemon, showdownMoveSet: ShowdownMoveset?): String =
        switch.toShowdownString(activeBattlePokemon, showdownMoveSet)

    override fun saveToBuffer(buffer: RegistryFriendlyByteBuf) = switch.saveToBuffer(buffer)
}
