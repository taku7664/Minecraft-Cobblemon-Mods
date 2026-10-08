package jbro.cobblemon.npc.server

import jbro.cobblemon.npc.api.NpcTalk
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
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * The talks going on, one per player. The server decides every step; the client only reads pages and reports the
 * answer, so a dialogue's conditions and commands cannot be skipped or forged from the client.
 *
 * A talk is either a dialogue file walked node by node, or an [NpcTalk] built in code whose answers run code.
 */
object DialogueSessions {
    private class Session(
        val id: Int,
        val dialogue: NpcDialogue?,
        val speaker: String,
        val skin: String,
        var nodeId: String,
        var choices: List<Int>,
        val npcEntityId: Int = DialogueShowPayload.NO_NPC,
        val talk: NpcTalk? = null,
    )

    private val sessions = mutableMapOf<UUID, Session>()
    /** The box whose answer is being run, by player; a talk the answer opens shows in that box. */
    private val answering = mutableMapOf<UUID, Int>()
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
     * NPC's; blank ones fall back to the dialogue's own. [npc] is the one speaking, which the client's camera turns
     * to. False when there is no such dialogue.
     */
    fun start(player: ServerPlayer, dialogueId: String, node: String? = null, speaker: String = "", skin: String = "",
              npc: net.minecraft.world.entity.Entity? = null): Boolean {
        val dialogue = DialogueStore[dialogueId] ?: return false
        if (node != null && node !in dialogue.nodes) return false
        val session = Session(newId(player), dialogue, speaker.ifBlank { dialogue.speaker.orEmpty() },
            skin.ifBlank { dialogue.skin.orEmpty() }, dialogue.start, emptyList(),
            npc?.takeIf { it.level() === player.level() }?.id ?: DialogueShowPayload.NO_NPC)
        sessions[player.uuid] = session
        follow(player, session, walker(player, session).enter(node))
        return true
    }

    /** Shows [talk] to [player], replacing whatever talk they had open. */
    fun open(player: ServerPlayer, talk: NpcTalk) {
        val session = Session(newId(player), null, "", talk.skin, "", talk.choices.indices.toList(),
            talk.npc?.takeIf { it.level() === player.level() }?.id ?: DialogueShowPayload.NO_NPC, talk)
        sessions[player.uuid] = session
        ServerPlayNetworking.send(player, DialogueShowPayload(session.id, talk.speaker, talk.skin, talk.lines,
            talk.choices.map { it.text }, session.npcEntityId))
    }

    /** The player talking with the NPC [npcEntityId] in [level], if one is; the NPC keeps its eyes on them. */
    fun listener(level: net.minecraft.world.level.Level, npcEntityId: Int): ServerPlayer? {
        val playerId = sessions.entries.firstOrNull { it.value.npcEntityId == npcEntityId }?.key ?: return null
        return (level.getPlayerByUUID(playerId) as? ServerPlayer)?.takeIf { it.level() === level }
    }

    /** Closes [player]'s dialogue box, if one is open. */
    fun end(player: ServerPlayer) {
        val session = sessions.remove(player.uuid) ?: return
        ServerPlayNetworking.send(player, DialogueClosePayload(session.id))
    }

    fun forget(player: ServerPlayer) {
        sessions.remove(player.uuid)
        answering.remove(player.uuid)
    }

    /** A talk opened while an answer runs keeps that answer's box, so the client updates it rather than reopening. */
    private fun newId(player: ServerPlayer) = answering[player.uuid] ?: nextId++

    private fun answer(player: ServerPlayer, payload: DialogueAnswerPayload) {
        val session = sessions[player.uuid]?.takeIf { it.id == payload.session } ?: return
        session.talk?.let { return answerTalk(player, session, it, payload.choice) }
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

    private fun answerTalk(player: ServerPlayer, session: Session, talk: NpcTalk, choice: Int) {
        val picked = if (choice < 0) {
            if (talk.choices.isNotEmpty()) return
            null
        } else {
            talk.choices.getOrNull(choice) ?: return
        }
        sessions.remove(player.uuid, session)
        if (picked != null) {
            answering[player.uuid] = session.id
            try {
                picked.action(player)
            } catch (failure: RuntimeException) {
                // The box still closes below; a failed answer must not leave the player waiting in it.
                jbro.cobblemon.npc.CobblemonNpc.LOGGER.error("A talk answer failed for {}", player.gameProfile.name, failure)
            } finally {
                answering.remove(player.uuid)
            }
        }
        // The answer opened no further talk in this box: it closes.
        if (sessions[player.uuid]?.id != session.id) ServerPlayNetworking.send(player, DialogueClosePayload(session.id))
    }

    /** Shows the next step first, then runs the commands, so a screen a command opens is not closed by the box. */
    private fun follow(player: ServerPlayer, session: Session, move: DialogueWalker.Move) {
        when (val step = move.step) {
            is DialogueWalker.Step.Show -> {
                session.nodeId = step.nodeId
                session.choices = step.choices
                ServerPlayNetworking.send(player, DialogueShowPayload(
                    session.id,
                    Component.literal(fill(player, session, session.speaker)),
                    session.skin,
                    step.node.lines.map { Component.literal(fill(player, session, it)) },
                    step.choices.map { Component.literal(fill(player, session, step.node.choices[it].text)) },
                    session.npcEntityId,
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

    private fun walker(player: ServerPlayer, session: Session) = DialogueWalker(session.dialogue!!, object : ConditionContext {
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
