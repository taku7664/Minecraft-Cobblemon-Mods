package jbro.cobblemon.npc.content

import jbro.cobblemon.npc.CobblemonNpc
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.context.UseOnContext

/**
 * An operator's tool. A right click on a block places a new NPC there, and a right click on an NPC opens its settings.
 * Sneaking, a right click on an NPC copies its name, skin and dialogue onto the wand, and a right click on a block
 * places a copy. The copy stays on the wand until another NPC is copied.
 */
class NpcWandItem(properties: Properties) : Item(properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        if (!player.hasPermissions(CobblemonNpc.EDIT_PERMISSION)) {
            if (!context.level.isClientSide) player.displayClientMessage(Component.translatable("message.cobblemon_npc.operator_only"), true)
            return InteractionResult.FAIL
        }
        val copy = if (player.isSecondaryUseActive) {
            copied(context.itemInHand) ?: run {
                if (!context.level.isClientSide) player.displayClientMessage(Component.translatable("message.cobblemon_npc.nothing_copied"), true)
                return InteractionResult.FAIL
            }
        } else {
            null
        }
        val level = context.level as? ServerLevel ?: return InteractionResult.SUCCESS
        val pos = context.clickedPos.relative(context.clickedFace)
        val npc = place(level, pos, player) ?: return InteractionResult.FAIL
        if (copy == null) {
            player.displayClientMessage(Component.translatable("message.cobblemon_npc.placed"), true)
        } else {
            npc.customName = Component.literal(copy.getString(NAME))
            npc.skinName = copy.getString(SKIN)
            npc.dialogueId = copy.getString(DIALOGUE)
            player.displayClientMessage(Component.translatable("message.cobblemon_npc.pasted", npc.displayName()), true)
        }
        return InteractionResult.CONSUME
    }

    /** A new NPC at [pos], facing [player], named "NPC" until it is set up. */
    private fun place(level: ServerLevel, pos: BlockPos, player: Player): NpcEntity? {
        val npc = CobblemonNpc.NPC.create(level) ?: return null
        val yaw = player.yRot + 180f
        npc.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, yaw, 0f)
        npc.yHeadRot = yaw
        npc.yBodyRot = yaw
        // A literal name: the server has no language files to resolve a translated one with.
        npc.customName = Component.literal("NPC")
        npc.isCustomNameVisible = true
        npc.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.SPAWN_EGG, null)
        level.addFreshEntityWithPassengers(npc)
        return npc
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, lines: MutableList<Component>, flag: TooltipFlag) {
        lines += Component.translatable("item.cobblemon_npc.npc_wand.place").withStyle(ChatFormatting.GRAY)
        lines += Component.translatable("item.cobblemon_npc.npc_wand.edit").withStyle(ChatFormatting.GRAY)
        lines += Component.translatable("item.cobblemon_npc.npc_wand.copy").withStyle(ChatFormatting.GRAY)
        lines += Component.translatable("item.cobblemon_npc.npc_wand.paste").withStyle(ChatFormatting.GRAY)
        copied(stack)?.let { lines += Component.translatable("item.cobblemon_npc.npc_wand.copied", it.getString(NAME)).withStyle(ChatFormatting.YELLOW) }
    }

    companion object {
        private const val COPY = "NpcCopy"
        private const val NAME = "Name"
        private const val SKIN = "NpcSkin"
        private const val DIALOGUE = "NpcDialogue"

        /** Remembers [npc]'s name, skin and dialogue on [stack], replacing what was copied before. */
        fun copy(stack: ItemStack, npc: NpcEntity) {
            val tag = CompoundTag()
            tag.putString(NAME, npc.displayName())
            tag.putString(SKIN, npc.skinName)
            tag.putString(DIALOGUE, npc.dialogueId)
            CustomData.update(DataComponents.CUSTOM_DATA, stack) { it.put(COPY, tag) }
        }

        private fun copied(stack: ItemStack): CompoundTag? =
            stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompound(COPY)?.takeIf { !it.isEmpty }
    }
}
