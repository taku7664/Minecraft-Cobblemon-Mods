package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.entity.npc.NPCEntity
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.npc.api.NpcTalkChoice
import jbro.cobblemon.npc.api.NpcTalks
import net.minecraft.network.chat.Component
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
            WildNpcRole.HEAL -> heal(player, npc)
            WildNpcRole.TRADE -> WildTrader.talk(player, npc)
            WildNpcRole.QUIZ -> WildQuiz.talk(player, npc)
            WildNpcRole.GIFT -> gift(player, npc)
        }
    }

    /** A traveler gives each player one thing from the reward pool ([WildRewards]); it stays where it is. */
    private fun gift(player: ServerPlayer, npc: NPCEntity) {
        if (isDone(npc, player)) return WildTrainers.sayLine(player, npc, "$KEY.gift.done")
        NpcTalks.open(player, WildTrainers.talk(npc, Component.translatable("$KEY.gift.offer"), listOf(
            NpcTalkChoice(Component.translatable("$KEY.gift.yes")) answer@{
                if (!npc.isAlive || isDone(npc, it)) return@answer
                // Marked only once something was given, so an empty pool does not use up the player's turn.
                val reward = WildRewards.give(it, "wild_gift") ?: return@answer WildTrainers.sayLine(it, npc, "$KEY.gift.empty")
                markDone(npc, it)
                Mod.LOGGER.info("{} took a wild gift: {}", it.gameProfile.name, reward.string)
                NpcTalks.open(it, WildTrainers.talk(npc, Component.translatable("$KEY.gift.reward", reward)))
            },
            NpcTalkChoice(Component.translatable("$KEY.gift.no")),
        )))
    }

    /** A caretaker heals the whole party, as often as asked; it stays where it is. */
    private fun heal(player: ServerPlayer, npc: NPCEntity) {
        val party = Cobblemon.storage.getParty(player)
        if (party.none { it.canBeHealed() }) return WildTrainers.sayLine(player, npc, "$KEY.heal.fine")
        NpcTalks.open(player, WildTrainers.talk(npc, Component.translatable("$KEY.heal.offer"), listOf(
            NpcTalkChoice(Component.translatable("$KEY.heal.yes")) answer@{
                // Asked again at the answer: a battle may have begun while the box was open.
                if (BattleRegistry.getBattleByParticipatingPlayerId(it.uuid) != null) return@answer
                Cobblemon.storage.getParty(it).heal()
                WildTrainers.sayLine(it, npc, "$KEY.heal.done")
            },
            NpcTalkChoice(Component.translatable("$KEY.heal.no")),
        )))
    }

    /** Whether [player] already had their one turn with [npc] (a quiz answered, a gift taken). */
    fun isDone(npc: NPCEntity, player: ServerPlayer): Boolean = DONE_TAG + player.uuid in npc.tags

    fun markDone(npc: NPCEntity, player: ServerPlayer) {
        npc.addTag(DONE_TAG + player.uuid)
    }
}
