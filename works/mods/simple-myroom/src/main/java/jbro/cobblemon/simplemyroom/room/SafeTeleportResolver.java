package jbro.cobblemon.simplemyroom.room;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class SafeTeleportResolver {
    public Optional<RoomSpawnPoint> find(
        ServerLevel level,
        Entity entity,
        RoomSpawnPoint requested,
        int horizontalRadius,
        int verticalRange,
        boolean requireSolidFloor,
        boolean allowFluid,
        Predicate<RoomSpawnPoint> allowed
    ) {
        for (SafePositionSearchPlan.Offset offset : SafePositionSearchPlan.offsets(horizontalRadius, verticalRange)) {
            RoomSpawnPoint candidate = new RoomSpawnPoint(
                requested.x() + offset.x(), requested.y() + offset.y(), requested.z() + offset.z(),
                requested.yaw(), requested.pitch()
            );
            if (allowed.test(candidate) && isSafe(level, entity, candidate, requireSolidFloor, allowFluid)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean isSafe(
        ServerLevel level,
        Entity entity,
        RoomSpawnPoint point,
        boolean requireSolidFloor,
        boolean allowFluid
    ) {
        AABB moved = entity.getBoundingBox().move(
            point.x() - entity.getX(), point.y() - entity.getY(), point.z() - entity.getZ()
        );
        BlockPos feet = BlockPos.containing(point.x(), point.y(), point.z());
        BlockPos head = BlockPos.containing(point.x(), moved.maxY - 0.001, point.z());
        if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(head)
            || !level.getWorldBorder().isWithinBounds(feet) || !level.noCollision(entity, moved)) {
            return false;
        }
        if (!allowFluid && (!level.getFluidState(feet).isEmpty() || !level.getFluidState(head).isEmpty())) return false;
        if (!requireSolidFloor) return true;
        BlockPos floor = BlockPos.containing(point.x(), point.y() - 0.01, point.z());
        BlockState floorState = level.getBlockState(floor);
        return floorState.isFaceSturdy(level, floor, Direction.UP);
    }
}
