package jbro.cobblemon.simplemyroom.room;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Where a player stands, or would land, so a return point saved mid-flight does not drop them from the sky. */
public final class GroundedPosition {
    private static final int MAX_DROP = 64;

    private GroundedPosition() {
    }

    /** The y to save for {@code entity}: its own y on the ground or in water, else the first floor or water below. */
    public static double groundY(ServerLevel level, Entity entity) {
        if (!entity.isPassenger() && (entity.onGround() || entity.isInWater())) return entity.getY();
        double x = entity.getX();
        double z = entity.getZ();
        BlockPos start = BlockPos.containing(x, entity.getY(), z);
        for (int drop = 0; drop <= MAX_DROP; drop++) {
            BlockPos feet = start.below(drop);
            if (level.isOutsideBuildHeight(feet)) break;
            if (!level.getFluidState(feet).isEmpty()) return feet.getY();
            BlockPos below = feet.below();
            BlockState floor = level.getBlockState(below);
            VoxelShape shape = floor.getCollisionShape(level, below);
            if (shape.isEmpty()) continue;
            double y = below.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
            if (level.noCollision(entity, entity.getBoundingBox().move(0.0, y - entity.getY(), 0.0))) return y;
        }
        return entity.getY();
    }
}
