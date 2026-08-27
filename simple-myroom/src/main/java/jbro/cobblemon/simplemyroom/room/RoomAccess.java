package jbro.cobblemon.simplemyroom.room;

import java.util.Optional;
import java.util.OptionalLong;
import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class RoomAccess {
    private RoomAccess() {
    }

    public static boolean hasAdminPermission(ServerPlayer player) {
        return player.hasPermissions(SimpleMyRoom.config().commands.adminPermissionLevel);
    }

    public static boolean isProtectionAdmin(ServerPlayer player) {
        return SimpleMyRoom.config().access.adminsBypassProtection && hasAdminPermission(player);
    }

    public static boolean isVisitAdmin(ServerPlayer player) {
        return SimpleMyRoom.config().access.adminsBypassVisitRules && hasAdminPermission(player);
    }

    public static RoomLayout layout(MinecraftServer server) {
        return RoomStorage.get(server).effectiveLayout();
    }

    public static Optional<RoomRecord> roomAt(ServerPlayer player, BlockPos position) {
        return roomAt(player.getServer(), position.getX(), position.getZ());
    }

    public static Optional<RoomRecord> roomAt(ServerPlayer player, int x, int z) {
        return roomAt(player.getServer(), x, z);
    }

    public static Optional<RoomRecord> roomAt(MinecraftServer server, int x, int z) {
        OptionalLong index = layout(server).indexAt(x, z);
        if (index.isEmpty()) return Optional.empty();
        return RoomStorage.get(server).findRoom(index.getAsLong());
    }

    public static boolean canModify(ServerPlayer player, BlockPos position) {
        if (!SimpleMyRoom.protectionEnabled() || !RoomDimensions.isRoom(player.level()) || isProtectionAdmin(player)) {
            return true;
        }
        return roomAt(player, position).map(room -> room.canModify(
            player.getUUID(),
            player.getGameProfile().getName(),
            SimpleMyRoom.config().access.trustedPlayersCanModify
        )).orElse(false);
    }

    public static boolean isAllocated(ServerLevel level, BlockPos position) {
        if (!RoomDimensions.isRoom(level)) return true;
        RoomLayout layout = layout(level.getServer());
        OptionalLong index = layout.indexAt(position.getX(), position.getZ());
        if (index.isEmpty()) index = layout.boundaryIndexAt(position.getX(), position.getZ());
        return index.isPresent() && RoomStorage.get(level.getServer()).findRoom(index.getAsLong()).isPresent();
    }

    public static boolean isProtectedBoundary(ServerLevel level, BlockPos position) {
        if (!RoomDimensions.isRoom(level) || !SimpleMyRoom.config().protection.protectBoundaryBlocks) return false;
        RoomLayout layout = layout(level.getServer());
        if (!isConfiguredBoundaryBlock(level, position) || layout.indexAt(position.getX(), position.getZ()).isPresent()) return false;
        OptionalLong index = layout.boundaryIndexAt(position.getX(), position.getZ());
        return index.isPresent() && RoomStorage.get(level.getServer()).findRoom(index.getAsLong()).isPresent();
    }

    public static boolean canModifyRoomAt(ServerPlayer player, double x, double z) {
        if (isProtectionAdmin(player)) return true;
        return roomAt(player, (int) Math.floor(x), (int) Math.floor(z)).map(room -> room.canModify(
            player.getUUID(),
            player.getGameProfile().getName(),
            SimpleMyRoom.config().access.trustedPlayersCanModify
        )).orElse(false);
    }

    public static boolean canOccupy(RoomRecord room, ServerPlayer player) {
        return room.canOccupy(
            player.getUUID(),
            player.getGameProfile().getName(),
            SimpleMyRoom.config().access.trustedPlayersCanVisitPrivateRooms
        );
    }

    public static boolean canVisit(RoomRecord room, String playerName) {
        return room.canVisit(playerName, SimpleMyRoom.config().access.trustedPlayersCanVisitPrivateRooms);
    }

    public static Block configuredBoundaryBlock() {
        ResourceLocation id = ResourceLocation.tryParse(SimpleMyRoom.config().layout.boundaryBlock);
        return id == null ? Blocks.BARRIER : BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.BARRIER);
    }

    private static boolean isConfiguredBoundaryBlock(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).is(configuredBoundaryBlock());
    }
}
