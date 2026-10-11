package jbro.cobblemon.mcc.api.battle

import com.cobblemon.mod.common.battles.BattleStartResult
import com.cobblemon.mod.common.entity.npc.NPCEntity
import java.util.UUID
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BrainNpcBattles
import net.minecraft.server.level.ServerPlayer

/**
 * Battles against a Cobblemon NPC that the Better AI plays: what `BattleBuilder.pvn` does, the trainer side decided by
 * the brain at [skill] (0-5, the trainer skill levels). The player's party, experience and the NPC's party work as in
 * Cobblemon's own NPC battles. The trainer's actor is not Cobblemon's `NPCBattleActor`: find the NPC through
 * [npcOf].
 */
object BrainNpcBattles {
    fun start(player: ServerPlayer, npc: NPCEntity, leadId: UUID?, skill: Int, contentId: String): BattleStartResult =
        Cobblemon173BrainNpcBattles.start(player, npc, leadId, skill, contentId)

    /** The NPC behind a battle actor, whether Cobblemon's NPC actor or a brain-played one. */
    fun npcOf(actor: com.cobblemon.mod.common.api.battles.model.actor.BattleActor): NPCEntity? =
        (actor as? com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor<*>)?.entity as? NPCEntity
}
