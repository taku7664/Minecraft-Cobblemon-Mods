package jbro.cobblemon.policy.plaza

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BucketItem
import net.minecraft.world.level.Level

/**
 * The plaza keeps its build. Players may still eat, throw balls, use items and open or use blocks; what they may not
 * do is change the world: break or place blocks, use items on blocks, pour buckets, or hit entities and decorations.
 * Nothing living takes damage, and fire, fluids, explosions and pistons change nothing. Operators bypass the player
 * rules so they can build.
 */
object PlazaProtection {
    private const val OP_BYPASS_LEVEL = 2

    /** Whether [player] may not change the world here. */
    @JvmStatic
    fun deniesWorldEdit(player: Player?, level: Level): Boolean =
        Plaza.isPlaza(level) && (player == null || !player.hasPermissions(OP_BYPASS_LEVEL))

    /** Whether fire, fluids, explosions and pistons may not change blocks here. */
    @JvmStatic
    fun deniesEnvironment(level: Level): Boolean = Plaza.isPlaza(level)

    fun register() {
        AttackBlockCallback.EVENT.register { player, level, _, _, _ ->
            if (deniesWorldEdit(player, level)) InteractionResult.FAIL else InteractionResult.PASS
        }
        PlayerBlockBreakEvents.BEFORE.register { level, player, _, _, _ -> !deniesWorldEdit(player, level) }
        // Other item-on-block uses (placing, tilling, bone meal, flint and steel) stop in ItemStack.useOn.
        UseItemCallback.EVENT.register { player, level, hand ->
            val stack = player.getItemInHand(hand)
            if (stack.item is BucketItem && deniesWorldEdit(player, level)) InteractionResultHolder.fail(stack)
            else InteractionResultHolder.pass(stack)
        }
        AttackEntityCallback.EVENT.register { player, level, _, _, _ ->
            if (deniesWorldEdit(player, level)) InteractionResult.FAIL else InteractionResult.PASS
        }
        UseEntityCallback.EVENT.register { player, level, _, entity, _ ->
            if ((entity is ArmorStand || entity is HangingEntity) && deniesWorldEdit(player, level)) InteractionResult.FAIL
            else InteractionResult.PASS
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (!deniesEnvironment(entity.level())) return@register true
            val attacker = source.entity
            attacker is Player && attacker.hasPermissions(OP_BYPASS_LEVEL)
        }
    }
}
