package jbro.cobblemon.npc.server

import jbro.cobblemon.npc.dialogue.ConditionContext
import jbro.cobblemon.npc.dialogue.DialogueCommand
import jbro.cobblemon.npc.dialogue.DialogueText
import jbro.cobblemon.npc.dialogue.DialogueWalker
import jbro.cobblemon.npc.dialogue.NpcDialogue
import jbro.cobblemon.npc.network.DialogueAnswerPayload
import jbro.cobblemon.npc.network.DialogueClosePayload
import jbro.cobblemon.npc.network.DialogueLeavePayload
import jbro.cobblemon.npc.network.DialogueShowPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandResultCallback
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * The talks going on, one per player. The server decides every step; the client only reads pages and reports the
 * answer, so a dialogue's conditions and commands cannot be skipped or forged from the client.
 */
object DialogueSessions {
    private class Session(
        val id: Int,
        val dialogue: NpcDialogue,
        val speaker: String,
        val skin: String,
        var nodeId: String,
        var choices: List<Int>,
    )

    private val sessions = mutableMapOf<UUID, Session>()
    private var nextId = 1

    fun register() {
        ServerPlayNetworking.registerGlobalReceiver(DialogueAnswerPayload.TYPE) { payload, context ->
            context.server().execute { answer(context.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(DialogueLeavePayload.TYPE) { payload, context ->
            context.server().execute {
                if (sessions[context.player().uuid]?.id == payload.session) sessions.remove(context.player().uuid)
            }
        }
    }

    /**
     * Opens dialogue [dialogueId] for [player] at [node] (the start node when null). [speaker] and [skin] are the
     * NPC's; blank ones fall back to the dialogue's own. False when there is no such dialogue.
     */
    fun start(player: ServerPlayer, dialogueId: String, node: String? = null, speaker: String = "", skin: String = ""): Boolean {
        val dialogue = DialogueStore[dialogueId] ?: return false
        if (node != null && node !in dialogue.nodes) return false
        val session = Session(nextId++, dialogue, speaker.ifBlank { dialogue.speaker.orEmpty() },
            skin.ifBlank { dialogue.skin.orEmpty() }, dialogue.start, emptyList())
        sessions[player.uuid] = session
        follow(player, session, walker(player, session).enter(node))
        return true
    }

    /** Closes [player]'s dialogue box, if one is open. */
    fun end(player: ServerPlayer) {
        val session = sessions.remove(player.uuid) ?: return
        ServerPlayNetworking.send(player, DialogueClosePayload(session.id))
    }

    fun forget(player: ServerPlayer) {
        sessions.remove(player.uuid)
    }

    private fun answer(player: ServerPlayer, payload: DialogueAnswerPayload) {
        val session = sessions[player.uuid]?.takeIf { it.id == payload.session } ?: return
        val walker = walker(player, session)
        val move = if (payload.choice < 0) {
            // A node with answers to pick does not move on by itself.
            if (session.choices.isNotEmpty()) return
            walker.read(session.nodeId)
        } else {
            val index = session.choices.getOrNull(payload.choice) ?: return
            walker.choose(session.nodeId, index) ?: return
        }
        follow(player, session, move)
    }

    /** Shows the next step first, then runs the commands, so a screen a command opens is not closed by the box. */
    private fun follow(player: ServerPlayer, session: Session, move: DialogueWalker.Move) {
        when (val step = move.step) {
            is DialogueWalker.Step.Show -> {
                session.nodeId = step.nodeId
                session.choices = step.choices
                ServerPlayNetworking.send(player, DialogueShowPayload(
                    session.id,
                    fill(player, session, session.speaker),
                    session.skin,
                    step.node.lines.map { fill(player, session, it) },
                    step.choices.map { fill(player, session, step.node.choices[it].text) },
                ))
            }
            DialogueWalker.Step.End -> {
                sessions.remove(player.uuid, session)
                ServerPlayNetworking.send(player, DialogueClosePayload(session.id))
            }
        }
        move.commands.forEach { run(player, session, it) }
    }

    private fun run(player: ServerPlayer, session: Session, text: String) {
        val command = DialogueCommand.parse(fill(player, session, text)) ?: return
        val source = player.createCommandSourceStack().let {
            // The operator who wrote the dialogue grants the rights; the player sees none of the feedback.
            if (command.asServer) it.withPermission(4).withSuppressedOutput() else it
        }
        player.server.commands.performPrefixedCommand(source, command.command)
    }

    private fun fill(player: ServerPlayer, session: Session, text: String) =
        DialogueText.fill(text, player.gameProfile.name, session.speaker)

    private fun walker(player: ServerPlayer, session: Session) = DialogueWalker(session.dialogue, object : ConditionContext {
        override fun hasTag(tag: String) = tag in player.tags

        override fun hasPermission(level: Int) = player.hasPermissions(level)

        override fun check(command: String): Boolean {
            var passed = false
            val source = player.createCommandSourceStack().withPermission(2).withSuppressedOutput()
                .withCallback(CommandResultCallback { success, result -> passed = success && result > 0 })
            player.server.commands.performPrefixedCommand(source, fill(player, session, command))
            return passed
        }
    })
}
