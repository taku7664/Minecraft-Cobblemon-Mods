package jbro.cobblemon.npc.api

import jbro.cobblemon.npc.server.DialogueSessions
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity

/**
 * One answer of a [NpcTalk]. [action] runs on the server thread once the player picks it; the box closes first, unless
 * the action opens another talk, which then takes the same box over without closing it.
 */
class NpcTalkChoice(val text: Component, val action: (ServerPlayer) -> Unit = {})

/**
 * A talk built in code rather than read from a dialogue file: [lines] read one page each, then [choices] to pick from
 * (none means the last page closes the box). [npc] is the speaker the camera turns to and who looks at the player;
 * [skin] is drawn in the name plate, written as the NPC skin field writes it (`rct:fisher_alec_036a`). The texts may
 * be translation keys; the client reads them in its own language.
 */
class NpcTalk(
    val speaker: Component,
    val lines: List<Component>,
    val choices: List<NpcTalkChoice> = emptyList(),
    val npc: Entity? = null,
    val skin: String = "",
) {
    init {
        require(lines.isNotEmpty()) { "A talk needs a line to show" }
    }
}

/** Opens talks built in code in the NPC dialogue box, with its letterbox and camera. Server thread only. */
object NpcTalks {
    /** Shows [talk] to [player], replacing whatever talk they had open. */
    fun open(player: ServerPlayer, talk: NpcTalk) = DialogueSessions.open(player, talk)

    /** Closes [player]'s dialogue box, if one is open. */
    fun close(player: ServerPlayer) = DialogueSessions.end(player)
}
