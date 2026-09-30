package jbro.cobblemon.mcc.internal.battle

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.mcc.api.rules.BattleGimmickLocks
import jbro.cobblemon.mcc.internal.battle.rules.GimmickLockRule
import jbro.cobblemon.mcc.internal.battle.rules.ManagedActionSubmission
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

/**
 * Ordinary battles whose players' gimmicks a [BattleGimmickLocks] lock holds. Their clients hide every gimmick for
 * the battle, and the server refuses any a player submits anyway.
 */
internal object GimmickLockedBattles {
    private val lockedPlayers = ConcurrentHashMap<UUID, Set<UUID>>()
    /** Players already told why their gimmicks are locked, this session. */
    private val told = ConcurrentHashMap.newKeySet<UUID>()

    fun register() {
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> told.remove(handler.player.uuid) }
    }

    /** Called for a battle MCC content does not manage, from its constructor before any request reaches a client. */
    fun attach(battle: PokemonBattle) {
        val sides = listOf(battle.side1, battle.side2).map { side ->
            side.actors.filterIsInstance<PlayerBattleActor>().mapTo(LinkedHashSet()) { it.uuid }
        }
        val players = battle.players.associateBy { it.uuid }
        val reasons = HashMap<UUID, net.minecraft.network.chat.Component>()
        val locked = GimmickLockRule.lockedPlayers(sides) { id ->
            players[id]?.let(BattleGimmickLocks::reason)?.also { reasons[id] = it } != null
        }
        if (locked.isEmpty()) return
        lockedPlayers[battle.battleId] = locked
        val payload = ShowManagedBattleMechanicsPayload(battle.battleId, emptySet())
        locked.mapNotNull(players::get).forEach { player ->
            if (ServerPlayNetworking.canSend(player, ShowManagedBattleMechanicsPayload.TYPE)) ServerPlayNetworking.send(player, payload)
            if (told.add(player.uuid)) reasons[player.uuid]?.let(player::sendSystemMessage)
        }
    }

    /** Why [actorId] may not submit [submission] in [battleId], or null when it may. */
    fun rejection(battleId: UUID, actorId: UUID, submission: ManagedActionSubmission): String? =
        GimmickLockRule.REJECTION.takeIf { lockedPlayers[battleId]?.contains(actorId) == true && GimmickLockRule.rejects(submission) }

    fun detach(battle: PokemonBattle) {
        val locked = lockedPlayers.remove(battle.battleId) ?: return
        val payload = HideManagedBattleMechanicsPayload(battle.battleId)
        battle.players.filter { it.uuid in locked }.forEach { player ->
            if (ServerPlayNetworking.canSend(player, HideManagedBattleMechanicsPayload.TYPE)) ServerPlayNetworking.send(player, payload)
        }
    }
}
