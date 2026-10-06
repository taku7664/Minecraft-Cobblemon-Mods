package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.EnumSet
import jbro.cobblemon.mcc.api.presentation.BattleScenes
import jbro.cobblemon.mcc.api.presentation.TrainerScenes
import jbro.cobblemon.mcc.api.presentation.TrainerScenes.Moment
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * Plays a managed battle's [TrainerScenes] for its player: each moment once, said by the trainer with the camera on
 * its body ([Cobblemon173TrainerBody]). Battle hooks may call in from Showdown's thread; the scene is sent from the
 * server thread.
 */
internal class Cobblemon173TrainerSceneDirector(
    private val player: ServerPlayer,
    private val scenes: TrainerScenes,
    private val trainerNameKey: String,
    private val teamSize: Int,
) {
    private val played = EnumSet.noneOf(Moment::class.java)

    fun battleStarted() = play(Moment.BATTLE_START)

    /** A choice is asked of the trainer: with one Pokemon left standing (of more than one), it has its say. */
    fun choiceRequested(team: List<BattlePokemon>) {
        if (teamSize > 1 && team.count { it.health > 0 } == 1) play(Moment.LAST_POKEMON)
    }

    fun ended(outcome: PveOutcome?) {
        when (outcome) {
            PveOutcome.WIN -> play(Moment.PLAYER_WON)
            PveOutcome.LOSS -> play(Moment.PLAYER_LOST)
            null -> Unit
        }
    }

    private fun play(moment: Moment) {
        val keys = scenes[moment]
        if (keys.isEmpty()) return
        synchronized(played) { if (!played.add(moment)) return }
        val server = player.server
        val send = Runnable {
            val online = server.playerList.getPlayer(player.uuid) ?: return@Runnable
            BattleScenes.play(online, Cobblemon173TrainerBody.of(player.uuid), Component.translatable(trainerNameKey),
                keys.map { Component.translatable(it) })
        }
        if (server.isSameThread) send.run() else server.execute(send)
    }
}
