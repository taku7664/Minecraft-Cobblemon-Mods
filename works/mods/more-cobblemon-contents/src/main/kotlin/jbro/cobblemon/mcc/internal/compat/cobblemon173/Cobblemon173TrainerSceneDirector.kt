package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import jbro.cobblemon.mcc.api.presentation.BattleScenes
import jbro.cobblemon.mcc.api.presentation.TrainerScenes
import jbro.cobblemon.mcc.api.presentation.TrainerScenes.Moment
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * Plays a managed battle's [TrainerScenes] for its player: each moment once, said by the trainer with the camera on
 * its body ([Cobblemon173TrainerBody]). Battle hooks may call in from Showdown's thread; the scene is sent from the
 * server thread. A Dynamax or Mega Evolution is read off the battle log as Showdown writes it ([watch]), which is
 * before Cobblemon has played that turn to the client, so the lines come with it rather than after the turn.
 */
internal class Cobblemon173TrainerSceneDirector(
    private val player: ServerPlayer,
    private val scenes: TrainerScenes,
    private val trainerNameKey: String,
    private val teamSize: Int,
) {
    private val played = EnumSet.noneOf(Moment::class.java)
    @Volatile private var watched: PokemonBattle? = null
    private var side = ""
    private var readMessages = 0

    fun battleStarted() = play(Moment.BATTLE_START)

    /** Reads [battle]'s log from now on for the trainer, Showdown side [showdownSide] (`p2`), changing form. */
    fun watch(battle: PokemonBattle, showdownSide: String) {
        if (scenes[Moment.DYNAMAX].isEmpty() && scenes[Moment.MEGA_EVOLUTION].isEmpty()) return
        side = showdownSide
        readMessages = battle.showdownMessages.size
        watched = battle
        WATCHING += this
    }

    /** New log lines: the trainer's side starting a Dynamax or Mega Evolving brings its lines. */
    private fun readLog() {
        val battle = watched ?: return
        if (battle.ended) return stopWatching()
        // Showdown's thread may be adding to the log meanwhile; a read that collides is retried next tick.
        val messages = try {
            battle.showdownMessages.toList()
        } catch (_: ConcurrentModificationException) {
            return
        }
        if (readMessages > messages.size) readMessages = 0
        messages.subList(readMessages, messages.size).forEach { raw ->
            raw.lineSequence().forEach { line -> moment(line, side)?.let(::play) }
        }
        readMessages = messages.size
    }

    private fun stopWatching() {
        watched = null
        WATCHING -= this
    }

    /** A choice is asked of the trainer: with one Pokemon left standing (of more than one), it has its say. */
    fun choiceRequested(team: List<BattlePokemon>) {
        if (teamSize > 1 && team.count { it.health > 0 } == 1) play(Moment.LAST_POKEMON)
    }

    fun ended(outcome: PveOutcome?, finished: Runnable = Runnable {}) {
        stopWatching()
        when (outcome) {
            PveOutcome.WIN -> play(Moment.PLAYER_WON, finished)
            PveOutcome.LOSS -> play(Moment.PLAYER_LOST, finished)
            null -> finished.run()
        }
    }

    private fun play(moment: Moment, finished: Runnable? = null) {
        val keys = scenes[moment]
        if (keys.isEmpty()) return finished?.run() ?: Unit
        synchronized(played) { if (!played.add(moment)) return finished?.run() ?: Unit }
        val server = player.server
        val midTurn = moment == Moment.DYNAMAX || moment == Moment.MEGA_EVOLUTION
        val send = Runnable {
            val online = server.playerList.getPlayer(player.uuid)
            if (online == null) {
                finished?.run()
                return@Runnable
            }
            val focus = Cobblemon173TrainerBody.of(player.uuid)
            val speaker = Component.translatable(trainerNameKey)
            val lines = keys.map { Component.translatable(it) }
            when {
                midTurn -> BattleScenes.interject(online, focus, speaker, lines)
                finished != null && focus != null -> BattleScenes.playUntilFinished(online, focus, speaker, lines, finished)
                else -> {
                    BattleScenes.play(online, focus, speaker, lines)
                    finished?.run()
                }
            }
        }
        if (server.isSameThread) send.run() else server.execute(send)
    }

    internal companion object {
        private val WATCHING: MutableSet<Cobblemon173TrainerSceneDirector> = ConcurrentHashMap.newKeySet()

        fun registerServer() {
            ServerTickEvents.END_SERVER_TICK.register { WATCHING.toList().forEach { it.readLog() } }
        }

        /** The moment a Showdown log [line] marks for the trainer on [side] (`p2`), if any. */
        internal fun moment(line: String, side: String): Moment? {
            val parts = line.split('|')
            if (parts.size < 3 || !parts[2].startsWith(side)) return null
            return when {
                parts[1] == "-start" && parts.getOrNull(3) == "Dynamax" -> Moment.DYNAMAX
                parts[1] == "-mega" -> Moment.MEGA_EVOLUTION
                else -> null
            }
        }
    }
}
