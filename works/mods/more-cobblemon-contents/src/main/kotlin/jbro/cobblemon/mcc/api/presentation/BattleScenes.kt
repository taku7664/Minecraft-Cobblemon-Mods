package jbro.cobblemon.mcc.api.presentation

import jbro.cobblemon.mcc.internal.presentation.BattleScenePayload
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
    fun play(player: ServerPlayer, focus: Entity?, speaker: Component?, lines: List<Component>) {
        require(lines.size in 1..MAX_LINES) { "A scene has 1 to $MAX_LINES lines" }
        if (!ServerPlayNetworking.canSend(player, BattleScenePayload.TYPE)) {
            lines.forEach { line ->
                player.sendSystemMessage(speaker?.let { Component.literal("<").append(it).append("> ").append(line) } ?: line)
            }
            return
        }
        // The client only knows entities it is tracking in its own level.
        val focusId = focus?.takeIf { it.level() === player.level() && !it.isRemoved }?.id ?: BattleScenePayload.NO_FOCUS
        ServerPlayNetworking.send(player, BattleScenePayload(focusId, speaker, lines))
    }
}
