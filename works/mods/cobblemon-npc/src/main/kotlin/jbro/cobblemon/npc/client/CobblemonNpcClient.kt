package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.CobblemonNpc
import jbro.cobblemon.npc.network.DialogueClosePayload
import jbro.cobblemon.npc.network.DialogueShowPayload
import jbro.cobblemon.npc.network.DialogueSourcePayload
import jbro.cobblemon.npc.network.EditorResultPayload
import jbro.cobblemon.npc.network.NpcSettingsPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Screens the client keeps track of between server messages. */
object NpcClientState {
    /** The NPC settings to go back to from the dialogue editor. */
    var editorParent: Screen? = null

    /** The editor a preview was started from, reopened when the preview ends. */
    var previewReturn: Screen? = null

    fun clear() {
        editorParent = null
        previewReturn = null
    }
}

object CobblemonNpcClient : ClientModInitializer {
    override fun onInitializeClient() {
        EntityRendererRegistry.register(CobblemonNpc.NPC, ::NpcEntityRenderer)
        NpcSkins.register()
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> NpcClientState.clear() }

        ClientPlayNetworking.registerGlobalReceiver(DialogueShowPayload.TYPE) { payload, context ->
            context.client().execute {
                val client = context.client()
                val open = client.screen
                if (open is NpcDialogueScreen && open.session == payload.session) open.update(payload)
                else client.setScreen(NpcDialogueScreen(payload))
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(DialogueClosePayload.TYPE) { payload, context ->
            context.client().execute {
                val client = context.client()
                val open = client.screen as? NpcDialogueScreen ?: return@execute
                if (open.session != payload.session) return@execute
                open.closeFromServer()
                NpcClientState.previewReturn?.let {
                    NpcClientState.previewReturn = null
                    // Only when nothing else (a screen a command opened) took the place of the box.
                    if (client.screen == null) client.setScreen(it)
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(NpcSettingsPayload.TYPE) { payload, context ->
            context.client().execute { context.client().setScreen(NpcSettingsScreen(payload)) }
        }
        ClientPlayNetworking.registerGlobalReceiver(DialogueSourcePayload.TYPE) { payload, context ->
            context.client().execute {
                context.client().setScreen(DialogueEditorScreen(payload.id, payload.json, NpcClientState.editorParent))
                NpcClientState.editorParent = null
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(EditorResultPayload.TYPE) { payload, context ->
            context.client().execute {
                val message = Component.translatable(payload.key, *payload.args.toTypedArray())
                val open = context.client().screen
                if (open is NpcEditorScreen) open.showResult(payload.ok, message)
                else context.client().player?.displayClientMessage(message, true)
            }
        }
    }
}
