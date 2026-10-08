package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.entity.npc.NPCEntity
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import net.minecraft.server.level.ServerPlayer

/**
 * The wild NPCs that do not battle: they share the trainers' spawning, crowd limit, naming and leaving
 * ([WildTrainers]), and differ only in what a talk does. See `docs/WILD_NPC_ROLES.md`.
 */
internal object WildNpcRoles {
    const val KEY = "message.${Mod.MOD_ID}.wild_npc"
    /** Saved on the NPC per player, so "already done with you" survives restarts and unloads. */
    private const val DONE_TAG = "mcc_wild_npc_done:"

    /** A player talked to [npc], a non-battling wild NPC of [definition]. */
    fun talk(player: ServerPlayer, npc: NPCEntity, definition: WildTrainerDefinition) {
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) return
        if (npc.customName == null) WildTrainers.name(npc)
        if (WildTrainers.isLeaving(npc)) return WildTrainers.sayLine(player, npc, "$KEY.leaving")
        when (definition.role) {
            WildNpcRole.BATTLE -> Unit
            WildNpcRole.HEAL, WildNpcRole.TRADE, WildNpcRole.QUIZ, WildNpcRole.GIFT ->
                Mod.LOGGER.warn("Wild NPC role {} of {} is not made yet", definition.role.id, definition.npcClass)
        }
    }

    /** Whether [player] already had their one turn with [npc] (a quiz answered, a gift taken). */
    fun isDone(npc: NPCEntity, player: ServerPlayer): Boolean = DONE_TAG + player.uuid in npc.tags

    fun markDone(npc: NPCEntity, player: ServerPlayer) {
        npc.addTag(DONE_TAG + player.uuid)
    }
}
