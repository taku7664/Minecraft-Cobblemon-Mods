package jbro.cobblemon.npc.content

import jbro.cobblemon.npc.CobblemonNpc
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext

/** An operator's tool: a right click on a block places an NPC there, a right click on an NPC opens its settings. */
class NpcWandItem(properties: Properties) : Item(properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        if (!player.hasPermissions(CobblemonNpc.EDIT_PERMISSION)) {
            if (!context.level.isClientSide) player.displayClientMessage(Component.translatable("message.cobblemon_npc.operator_only"), true)
            return InteractionResult.FAIL
        }
        val level = context.level as? ServerLevel ?: return InteractionResult.SUCCESS
        val pos = context.clickedPos.relative(context.clickedFace)
        val npc = CobblemonNpc.NPC.create(level) ?: return InteractionResult.FAIL
        // Facing whoever placed it.
        val yaw = player.yRot + 180f
        npc.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, yaw, 0f)
        npc.yHeadRot = yaw
        npc.yBodyRot = yaw
        // A literal name: the server has no language files to resolve a translated one with.
        npc.customName = Component.literal("NPC")
        npc.isCustomNameVisible = true
        npc.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.SPAWN_EGG, null)
        level.addFreshEntityWithPassengers(npc)
        player.displayClientMessage(Component.translatable("message.cobblemon_npc.placed"), true)
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, lines: MutableList<Component>, flag: TooltipFlag) {
        lines += Component.translatable("item.cobblemon_npc.npc_wand.place").withStyle(ChatFormatting.GRAY)
        lines += Component.translatable("item.cobblemon_npc.npc_wand.edit").withStyle(ChatFormatting.GRAY)
    }
}
