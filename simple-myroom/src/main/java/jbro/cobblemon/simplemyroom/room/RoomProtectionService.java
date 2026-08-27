package jbro.cobblemon.simplemyroom.room;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.GrindstoneBlock;

public final class RoomProtectionService {
    private final Map<UUID, Long> nextDenialMessageAt = new HashMap<>();

    public void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!SimpleMyRoom.protectionEnabled() || !(player instanceof ServerPlayer serverPlayer)
                || !SimpleMyRoom.config().protection.preventUnauthorizedBlockBreak) return true;
            return allowModification(serverPlayer, pos);
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!SimpleMyRoom.protectionEnabled() || world.isClientSide || !(player instanceof ServerPlayer serverPlayer)
                || !SimpleMyRoom.config().protection.preventUnauthorizedBlockBreak) {
                return InteractionResult.PASS;
            }
            return allowModification(serverPlayer, pos) ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!SimpleMyRoom.protectionEnabled() || world.isClientSide || !(player instanceof ServerPlayer serverPlayer) || !RoomDimensions.isRoom(world)
                || !SimpleMyRoom.config().protection.preventUnauthorizedBlockUse) {
                return InteractionResult.PASS;
            }
            if (RoomAccess.isProtectionAdmin(serverPlayer)) return InteractionResult.PASS;
            BlockPos clicked = hit.getBlockPos();
            Optional<RoomRecord> room = RoomAccess.roomAt(serverPlayer, clicked);
            if (room.isEmpty()) return deny(serverPlayer);
            if (RoomAccess.canModify(serverPlayer, clicked)) {
                if (SimpleMyRoom.config().visitorInteractions.denyPlacementAgainstAllowedBlocks
                    && player.getItemInHand(hand).getItem() instanceof BlockItem
                    && !RoomAccess.canModify(serverPlayer, clicked.relative(hit.getDirection()))) {
                    return deny(serverPlayer);
                }
                return InteractionResult.PASS;
            }
            boolean emptyHandAllowed = !SimpleMyRoom.config().visitorInteractions.requireEmptyHand || player.getItemInHand(hand).isEmpty();
            if (SimpleMyRoom.config().visitorInteractions.enabled
                && RoomAccess.canVisit(room.get(), serverPlayer.getGameProfile().getName())
                && emptyHandAllowed
                && isAllowedVisitorBlock(world.getBlockState(clicked).getBlock())) {
                return InteractionResult.PASS;
            }
            return deny(serverPlayer);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!SimpleMyRoom.protectionEnabled() || world.isClientSide || !(player instanceof ServerPlayer serverPlayer) || !RoomDimensions.isRoom(world)
                || !SimpleMyRoom.config().protection.preventUnauthorizedEntityAttack) return InteractionResult.PASS;
            boolean canModify = RoomAccess.canModifyRoomAt(serverPlayer, entity.getX(), entity.getZ());
            if (entity instanceof Player) {
                return DamagePolicy.canPlayerAttack(
                    canModify,
                    SimpleMyRoom.config().protection.preventPlayerDamage,
                    SimpleMyRoom.config().protection.preventUnauthorizedEntityAttack
                ) ? InteractionResult.PASS : deny(serverPlayer);
            }
            if (!canModify) return deny(serverPlayer);
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!SimpleMyRoom.protectionEnabled() || world.isClientSide || !(player instanceof ServerPlayer serverPlayer) || !RoomDimensions.isRoom(world)
                || !SimpleMyRoom.config().protection.preventUnauthorizedEntityUse) return InteractionResult.PASS;
            return RoomAccess.canModifyRoomAt(serverPlayer, entity.getX(), entity.getZ()) ? InteractionResult.PASS : deny(serverPlayer);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!SimpleMyRoom.protectionEnabled() || !RoomDimensions.isRoom(entity.level())) return true;
            Entity direct = source.getEntity();
            Entity causing = source.getDirectEntity();
            Player attackingPlayer = direct instanceof Player player ? player : causing instanceof Player player ? player : null;
            TamableAnimal attackingPet = direct instanceof TamableAnimal pet && pet.isTame() ? pet : null;
            if (entity instanceof ServerPlayer && SimpleMyRoom.config().protection.preventPlayerDamage
                && (attackingPlayer != null || attackingPet != null && SimpleMyRoom.config().protection.preventPetDamageToPlayers)) return false;
            if (attackingPlayer instanceof ServerPlayer attacker
                && SimpleMyRoom.config().protection.preventUnauthorizedEntityAttack) {
                return RoomAccess.canModifyRoomAt(attacker, entity.getX(), entity.getZ());
            }
            if (attackingPet != null && SimpleMyRoom.config().protection.preventPetDamageToPlayers) {
                UUID ownerId = attackingPet.getOwnerUUID();
                ServerPlayer owner = ownerId == null ? null : entity.getServer().getPlayerList().getPlayer(ownerId);
                return owner != null && RoomAccess.canModifyRoomAt(owner, entity.getX(), entity.getZ());
            }
            return true;
        });
    }

    private boolean isAllowedVisitorBlock(Block block) {
        if (SimpleMyRoom.config().visitorInteractions.includeOriginalSafeBlockClasses
            && (block instanceof AbstractChestBlock<?>
                || block instanceof CraftingTableBlock
                || block instanceof AnvilBlock
                || block instanceof GrindstoneBlock
                || block instanceof EnderChestBlock)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return SimpleMyRoom.config().visitorInteractions.allowedBlockIds.contains(id.toString());
    }

    private boolean allowModification(ServerPlayer player, BlockPos position) {
        if (RoomAccess.isProtectedBoundary(player.serverLevel(), position)) {
            deny(player);
            return false;
        }
        if (RoomAccess.canModify(player, position)) return true;
        deny(player);
        return false;
    }

    private InteractionResult deny(ServerPlayer player) {
        if (!SimpleMyRoom.config().protection.showDenialInActionBar) return InteractionResult.FAIL;
        long now = System.currentTimeMillis();
        long readyAt = nextDenialMessageAt.getOrDefault(player.getUUID(), 0L);
        if (now >= readyAt) {
            player.sendSystemMessage(Component.literal(SimpleMyRoom.messages().denied), true);
            nextDenialMessageAt.put(player.getUUID(), now + SimpleMyRoom.config().protection.denialMessageCooldownMillis);
        }
        return InteractionResult.FAIL;
    }
}
