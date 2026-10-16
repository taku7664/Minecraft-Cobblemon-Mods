package jbro.cobblemon.mcc.api.presentation

import jbro.cobblemon.mcc.internal.presentation.BattleScenePayload
import jbro.cobblemon.mcc.internal.presentation.BattleSceneAwaitPayload
import jbro.cobblemon.mcc.internal.presentation.BattleSceneAwaitPolicy
import jbro.cobblemon.mcc.internal.presentation.BattleSceneFinishedPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity

/**
 * A short scripted moment for one player: someone says a few lines in a message box while the battle camera looks at
 * [focus]. Meant for a battle's closing words, so it can be sent as soon as the battle ends: the client waits for its
 * battle screen to close before playing it. The camera only moves when the player has Better Cobblemon Battlecam on
 * for that battle; a client without scene support reads the lines in chat.
 */
object BattleScenes {
    const val MAX_LINES = BattleScenePayload.MAX_LINES

    /** [speaker] (no name plate when null) says [lines] to [player] while the camera looks at [focus], when given. */
    fun play(player: ServerPlayer, focus: Entity?, speaker: Component?, lines: List<Component>) =
        send(player, focus, speaker, lines, interject = false)

    /**
     * Like [play], for words said in the middle of a turn: the lines start at once instead of after the battle
     * narration on screen, and that narration waits until they are over.
     */
    fun interject(player: ServerPlayer, focus: Entity?, speaker: Component?, lines: List<Component>) =
        send(player, focus, speaker, lines, interject = true)

    /**
     * Plays closing words and runs [finished] only after the supporting client closes the last line. A client without
     * scene support receives chat instead and completes immediately. Disconnecting, changing level, losing the focus
     * entity or walking out of range also completes it, without advancing a dialogue on a timer.
     */
    fun playUntilFinished(player: ServerPlayer, focus: Entity, speaker: Component?, lines: List<Component>, finished: Runnable) {
        require(lines.size in 1..MAX_LINES) { "A scene has 1 to $MAX_LINES lines" }
        val supportsAwaiting = BattleSceneAwaitPolicy.supported(
            ServerPlayNetworking.canSend(player, BattleSceneAwaitPayload.TYPE),
            BattleSceneFinishedPayload.TYPE.id() in ServerPlayNetworking.getReceived(player),
        )
        if (!supportsAwaiting) {
            chat(player, speaker, lines)
            finished.run()
            return
        }
        val completionId = jbro.cobblemon.mcc.internal.presentation.BattleSceneCompletions.await(player, focus, finished)
        try {
            val focusId = focus.takeIf { it.level() === player.level() && !it.isRemoved }?.id ?: BattleScenePayload.NO_FOCUS
            ServerPlayNetworking.send(player, BattleSceneAwaitPayload(focusId, speaker, lines, completionId))
        } catch (failure: RuntimeException) {
            jbro.cobblemon.mcc.internal.presentation.BattleSceneCompletions.cancel(completionId)
            throw failure
        }
    }

    private fun send(player: ServerPlayer, focus: Entity?, speaker: Component?, lines: List<Component>, interject: Boolean) {
        require(lines.size in 1..MAX_LINES) { "A scene has 1 to $MAX_LINES lines" }
        if (!ServerPlayNetworking.canSend(player, BattleScenePayload.TYPE)) {
            chat(player, speaker, lines)
            return
        }
        // The client only knows entities it is tracking in its own level.
        val focusId = focus?.takeIf { it.level() === player.level() && !it.isRemoved }?.id ?: BattleScenePayload.NO_FOCUS
        ServerPlayNetworking.send(player, BattleScenePayload(focusId, speaker, lines, interject))
    }

    private fun chat(player: ServerPlayer, speaker: Component?, lines: List<Component>) {
        lines.forEach { line ->
            player.sendSystemMessage(speaker?.let { Component.literal("<").append(it).append("> ").append(line) } ?: line)
        }
    }
}
