package jbro.cobblemon.npc

import jbro.cobblemon.npc.content.NpcEntity
import jbro.cobblemon.npc.content.NpcWandItem
import jbro.cobblemon.npc.network.NpcPayloads
import jbro.cobblemon.npc.server.DialogueSessions
import jbro.cobblemon.npc.server.DialogueStore
import jbro.cobblemon.npc.server.NpcCommands
import jbro.cobblemon.npc.server.NpcEditorService
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import org.slf4j.LoggerFactory

/** NPCs that talk in the UI kit's dialogue box and run commands as the talk goes on; operators set them up with a wand. */
object CobblemonNpc : ModInitializer {
    const val MOD_ID = "cobblemon_npc"
    /** Operators edit NPCs and dialogues from this permission level. */
    const val EDIT_PERMISSION = 2
    val LOGGER = LoggerFactory.getLogger("Cobblemon NPC")

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    val NPC: EntityType<NpcEntity> = Registry.register(BuiltInRegistries.ENTITY_TYPE, id("npc"),
        EntityType.Builder.of(::NpcEntity, MobCategory.MISC).sized(0.6f, 1.8f).eyeHeight(1.62f).fireImmune()
            .clientTrackingRange(10).build("$MOD_ID:npc"))

    val WAND: Item = Registry.register(BuiltInRegistries.ITEM, id("npc_wand"),
        NpcWandItem(Item.Properties().stacksTo(1).rarity(Rarity.EPIC)))

    override fun onInitialize() {
        FabricDefaultAttributeRegistry.register(NPC, NpcEntity.createAttributes())
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.OP_BLOCKS).register { it.accept(WAND) }
        NpcPayloads.register()
        DialogueSessions.register()
        NpcEditorService.register()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> NpcCommands.register(dispatcher) }
        ServerLifecycleEvents.SERVER_STARTING.register { DialogueStore.load() }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> DialogueSessions.forget(handler.player) }
    }
}
