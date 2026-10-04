package jbro.cobblemon.npc.server

import jbro.cobblemon.npc.CobblemonNpc
import jbro.cobblemon.npc.content.NpcEntity
import jbro.cobblemon.npc.dialogue.DialogueCodec
import jbro.cobblemon.npc.dialogue.DialogueIds
import jbro.cobblemon.npc.network.DialogueFetchPayload
import jbro.cobblemon.npc.network.DialogueSourcePayload
import jbro.cobblemon.npc.network.DialogueStorePayload
import jbro.cobblemon.npc.network.EditorResultPayload
import jbro.cobblemon.npc.network.NpcSavePayload
import jbro.cobblemon.npc.network.NpcSettingsPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity

/** The operator's side of the wand: NPC settings and dialogue files, each request checked for permission again. */
object NpcEditorService {
    private const val REACH = 16.0
    private const val NAME_LENGTH = 64
    private const val SKIN_LENGTH = 128

    fun register() {
        ServerPlayNetworking.registerGlobalReceiver(NpcSavePayload.TYPE) { payload, context ->
            context.server().execute { saveNpc(context.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(DialogueFetchPayload.TYPE) { payload, context ->
            context.server().execute {
                val player = context.player()
                if (allowed(player) && DialogueIds.isDialogueId(payload.id)) {
                    ServerPlayNetworking.send(player, DialogueSourcePayload(payload.id, DialogueStore.source(payload.id)))
                }
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(DialogueStorePayload.TYPE) { payload, context ->
            context.server().execute { saveDialogue(context.player(), payload) }
        }
    }

    fun open(player: ServerPlayer, npc: NpcEntity) {
        if (!allowed(player)) {
            player.displayClientMessage(Component.translatable("message.cobblemon_npc.operator_only"), true)
            return
        }
        ServerPlayNetworking.send(player, NpcSettingsPayload(npc.id, npc.displayName(), npc.skinName, npc.dialogueId, DialogueStore.ids()))
    }

    private fun allowed(player: ServerPlayer) = player.hasPermissions(CobblemonNpc.EDIT_PERMISSION)

    private fun saveNpc(player: ServerPlayer, payload: NpcSavePayload) {
        if (!allowed(player)) return
        val npc = player.serverLevel().getEntity(payload.entityId) as? NpcEntity ?: return
        if (npc.distanceTo(player) > REACH) return
        if (payload.remove) {
            npc.remove(Entity.RemovalReason.DISCARDED)
            result(player, true, "removed")
            return
        }
        val dialogue = payload.dialogue.trim()
        if (dialogue.isNotEmpty() && !DialogueIds.isDialogueId(dialogue)) {
            result(player, false, "dialogue_id", dialogue)
            return
        }
        val name = payload.name.trim().take(NAME_LENGTH)
        npc.customName = if (name.isEmpty()) null else Component.literal(name)
        npc.isCustomNameVisible = name.isNotEmpty()
        npc.skinName = payload.skin.trim().take(SKIN_LENGTH)
        npc.dialogueId = dialogue
        result(player, true, "npc_saved")
    }

    private fun saveDialogue(player: ServerPlayer, payload: DialogueStorePayload) {
        if (!allowed(player)) return
        if (!DialogueIds.isDialogueId(payload.id)) {
            result(player, false, "dialogue_id", payload.id)
            return
        }
        val decoded = DialogueCodec.decode(payload.json)
        val dialogue = decoded.valid
        if (dialogue == null) {
            val problem = decoded.problems.first()
            ServerPlayNetworking.send(player, EditorResultPayload(false, "cobblemon_npc.problem.${problem.key}", problem.args))
            return
        }
        DialogueStore.save(payload.id, dialogue)
        result(player, true, "dialogue_saved", payload.id)
        if (payload.preview) DialogueSessions.start(player, payload.id)
    }

    private fun result(player: ServerPlayer, ok: Boolean, key: String, vararg args: String) =
        ServerPlayNetworking.send(player, EditorResultPayload(ok, "message.cobblemon_npc.$key", args.toList()))
}
