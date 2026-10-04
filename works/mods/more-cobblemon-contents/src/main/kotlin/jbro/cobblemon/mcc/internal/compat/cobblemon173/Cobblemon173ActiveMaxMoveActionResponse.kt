package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.ActiveBattlePokemon
import com.cobblemon.mod.common.battles.MoveActionResponse
import com.cobblemon.mod.common.battles.ShowdownActionResponse
import com.cobblemon.mod.common.battles.ShowdownActionResponseType
import com.cobblemon.mod.common.battles.ShowdownMoveset
import net.minecraft.network.RegistryFriendlyByteBuf

/** Active Max moves use the transformed target without submitting another Dynamax activation. */
internal class Cobblemon173ActiveMaxMoveActionResponse(private val moveId: String, private val targetPnx: String?) :
    ShowdownActionResponse(ShowdownActionResponseType.MOVE) {
    private val command = MoveActionResponse(moveId, targetPnx)

    override fun isValid(activeBattlePokemon: ActiveBattlePokemon, showdownMoveSet: ShowdownMoveset?, forceSwitch: Boolean): Boolean {
        if (forceSwitch || showdownMoveSet == null || showdownMoveSet.canDynamax) return false
        val index = showdownMoveSet.moves.indexOfFirst { it.id == moveId }
        if (index < 0) return false
        val max = showdownMoveSet.maxMoves?.getOrNull(index)?.takeUnless { it.disabled } ?: return false
        val targets = max.target.targetList(activeBattlePokemon)?.takeIf { it.isNotEmpty() } ?: return true
        val pnx = targetPnx ?: return false
        val (_, target) = activeBattlePokemon.actor.battle.getActorAndActiveSlotFromPNX(pnx)
        return target in targets
    }

    override fun toShowdownString(activeBattlePokemon: ActiveBattlePokemon, showdownMoveSet: ShowdownMoveset?): String =
        command.toShowdownString(activeBattlePokemon, showdownMoveSet)

    override fun saveToBuffer(buffer: RegistryFriendlyByteBuf) = command.saveToBuffer(buffer)
}
