package jbro.cobblemon.dimensions

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.RespawnAnchorBlock

/** What holds inside these dimensions: low gravity in Ultra Space, falling out returns home, no beds. */
object DimensionRules {
    /** Ultra Space keeps a fifth of normal gravity: a jump clears about five blocks. */
    private const val ULTRA_GRAVITY = -0.8
    private val LOW_GRAVITY = CobblemonDimensions.id("ultra_space_low_gravity")

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        // Beds and respawn anchors would set a respawn point inside, which would undo the only ways out.
        UseBlockCallback.EVENT.register { player, level, _, hit ->
            val block = level.getBlockState(hit.blockPos).block
            if ((block is BedBlock || block is RespawnAnchorBlock) && ModDimensions.isOurs(level)) {
                if (player is ServerPlayer) {
                    val key = if (block is BedBlock) "message.cobblemon_dimensions.no_sleep" else "message.cobblemon_dimensions.no_anchor"
                    player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), false)
                }
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }
    }

    private fun tick(server: MinecraftServer) {
        for (player in server.playerList.players) {
            val dimension = ModDimension.of(player.level())
            updateGravity(player, dimension == ModDimension.ULTRA_SPACE)
            // Falling past the bottom of a dimension is a way home, not a death.
            if (dimension != null && !player.isSpectator && player.y < player.level().minBuildHeight) {
                DimensionTravel.returnHome(player)
            }
        }
    }

    private fun updateGravity(player: ServerPlayer, low: Boolean) {
        val gravity = player.getAttribute(Attributes.GRAVITY) ?: return
        val has = gravity.hasModifier(LOW_GRAVITY)
        if (low && !has) {
            gravity.addTransientModifier(AttributeModifier(LOW_GRAVITY, ULTRA_GRAVITY, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        } else if (!low && has) {
            gravity.removeModifier(LOW_GRAVITY)
        }
    }
}
