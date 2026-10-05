package jbro.cobblemon.dimensions.portal

import jbro.cobblemon.dimensions.CobblemonDimensions
import jbro.cobblemon.dimensions.ModDimension
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.FlintAndSteelItem
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction

/** The two paradox portals and lighting them: flint and steel on a finished frame. */
object PortalBlocks {
    val ANCIENT_PORTAL: ParadoxPortalBlock = register("ancient_portal", ModDimension.ANCIENT, MapColor.COLOR_ORANGE)
    val FUTURE_PORTAL: ParadoxPortalBlock = register("future_portal", ModDimension.FUTURE, MapColor.COLOR_PURPLE)

    val PORTAL_ENTITY: BlockEntityType<ParadoxPortalBlockEntity> = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
        CobblemonDimensions.id("paradox_portal"),
        BlockEntityType.Builder.of(::ParadoxPortalBlockEntity, ANCIENT_PORTAL, FUTURE_PORTAL).build(null))

    fun register() {
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            val stack = player.getItemInHand(hand)
            if (stack.item !is FlintAndSteelItem || PortalFrames.frameTag(level.getBlockState(hit.blockPos)) == null) {
                return@register InteractionResult.PASS
            }
            if (level.isClientSide) return@register InteractionResult.SUCCESS
            if (!PortalFrames.tryLight(level, hit.blockPos)) return@register InteractionResult.PASS
            stack.hurtAndBreak(1, player, if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND)
            InteractionResult.SUCCESS
        }
    }

    private fun register(path: String, dimension: ModDimension, color: MapColor): ParadoxPortalBlock =
        Registry.register(BuiltInRegistries.BLOCK, CobblemonDimensions.id(path), ParadoxPortalBlock(dimension,
            BlockBehaviour.Properties.of().mapColor(color).noCollission().lightLevel { 15 }.strength(-1f, 3600000f)
                .noLootTable().pushReaction(PushReaction.BLOCK)))
}
