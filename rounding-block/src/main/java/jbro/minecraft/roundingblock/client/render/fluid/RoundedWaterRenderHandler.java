package jbro.minecraft.roundingblock.client.render.fluid;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import jbro.minecraft.roundingblock.client.render.RoundedBlockModel;
import jbro.minecraft.roundingblock.mesh.CubeFace;
import jbro.minecraft.roundingblock.mesh.MeshPlan;
import jbro.minecraft.roundingblock.mesh.MeshPrimitive;
import jbro.minecraft.roundingblock.mesh.MeshVertex;
import jbro.minecraft.roundingblock.mesh.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adds water only inside the carved contact recess of an actually rounded neighboring block. */
public final class RoundedWaterRenderHandler implements FluidRenderHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rounding-Block");
    private static final float SURFACE_EPSILON = 0.001F;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean ACTIVATION_LOGGED = new AtomicBoolean();
    private static final Direction[] HORIZONTAL = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final FluidRenderHandler delegate;
    private static FluidRenderHandler baseStill;
    private static FluidRenderHandler baseFlowing;
    private static RoundedWaterRenderHandler roundedStill;
    private static RoundedWaterRenderHandler roundedFlowing;
    private static volatile boolean roundingEnabled;

    private RoundedWaterRenderHandler(FluidRenderHandler delegate) {
        this.delegate = delegate;
    }

    /** Installs after every mod has had a chance to register its normal water tint/sprite handler. */
    public static void register() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> install());
    }

    static synchronized void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        FluidRenderHandlerRegistry registry = FluidRenderHandlerRegistry.INSTANCE;
        baseStill = registry.get(Fluids.WATER);
        baseFlowing = registry.get(Fluids.FLOWING_WATER);
        if (baseStill == null || baseFlowing == null) {
            INSTALLED.set(false);
            LOGGER.warn("Water contact rounding was not installed because a base water renderer is missing");
            return;
        }
        if (baseStill == baseFlowing) {
            roundedStill = new RoundedWaterRenderHandler(baseStill);
            roundedFlowing = roundedStill;
        } else {
            roundedStill = new RoundedWaterRenderHandler(baseStill);
            roundedFlowing = new RoundedWaterRenderHandler(baseFlowing);
        }
        setEnabled(RoundedBlockModel.currentConfig().enabled());
        LOGGER.info("Water contact rounding integrated through Fabric fluid rendering API");
    }

    /** Restores the exact pre-mod water handlers while rounding is disabled. */
    public static synchronized void setEnabled(boolean enabled) {
        roundingEnabled = enabled;
        if (!INSTALLED.get() || baseStill == null || baseFlowing == null) {
            return;
        }
        FluidRenderHandlerRegistry registry = FluidRenderHandlerRegistry.INSTANCE;
        registry.register(Fluids.WATER, enabled ? roundedStill : baseStill);
        registry.register(Fluids.FLOWING_WATER, enabled ? roundedFlowing : baseFlowing);
        if (!enabled) {
            clearOverrideMarker(registry, Fluids.WATER);
            clearOverrideMarker(registry, Fluids.FLOWING_WATER);
        }
    }

    private static void clearOverrideMarker(FluidRenderHandlerRegistry registry, Fluid fluid) {
        try {
            Field field = registry.getClass().getDeclaredField("modHandlers");
            field.setAccessible(true);
            Object value = field.get(registry);
            if (!(value instanceof Map<?, ?> overrides)) {
                throw new IllegalStateException("Fabric fluid override registry has an unexpected type");
            }
            overrides.remove(fluid);
            if (registry.getOverride(fluid) != null) {
                throw new IllegalStateException("Fabric fluid override marker remained registered");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                "Could not fully disable the Fabric water override for this Fabric API version",
                exception
            );
        }
    }

    @Override
    public TextureAtlasSprite[] getFluidSprites(
        @Nullable BlockAndTintGetter view,
        @Nullable BlockPos pos,
        FluidState state
    ) {
        return delegate.getFluidSprites(view, pos, state);
    }

    @Override
    public int getFluidColor(@Nullable BlockAndTintGetter view, @Nullable BlockPos pos, FluidState state) {
        return delegate.getFluidColor(view, pos, state);
    }

    @Override
    public void reloadTextures(TextureAtlas textureAtlas) {
        delegate.reloadTextures(textureAtlas);
    }

    @Override
    public void renderFluid(
        BlockPos pos,
        BlockAndTintGetter world,
        VertexConsumer vertices,
        BlockState blockState,
        FluidState fluidState
    ) {
        delegate.renderFluid(pos, world, vertices, blockState, fluidState);
        if (!blockState.is(Blocks.WATER) || !roundingEnabled) {
            return;
        }
        BlockPos abovePos = pos.above();
        BlockState aboveState = world.getBlockState(abovePos);
        if (fluidState.getType().isSame(aboveState.getFluidState().getType())) {
            return;
        }

        int contactBits = roundedContactBits(world, pos);
        if (contactBits == 0) {
            return;
        }

        CornerHeights heights = cornerHeights(world, pos, fluidState);
        if (occludesWaterSurface(world, abovePos, aboveState, heights.minimum())) {
            return;
        }
        TextureAtlasSprite[] sprites = delegate.getFluidSprites(world, pos, fluidState);
        if (sprites == null || sprites.length < 2 || sprites[0] == null || sprites[1] == null) {
            return;
        }
        int tint = delegate.getFluidColor(world, pos, fluidState);
        float red = ((tint >> 16) & 0xFF) / 255.0F;
        float green = ((tint >> 8) & 0xFF) / 255.0F;
        float blue = (tint & 0xFF) / 255.0F;
        float shade = world.getShade(Direction.UP, true);
        int light = lightColor(world, pos);
        net.minecraft.world.phys.Vec3 flow = fluidState.getFlow(world, pos);
        UvMapping uvMapping = UvMapping.create(sprites, flow);
        boolean backward = fluidState.shouldRenderBackwardUpFace(world, pos.above());

        for (int directionIndex = 0; directionIndex < HORIZONTAL.length; directionIndex++) {
            if ((contactBits & (1 << directionIndex)) == 0) {
                continue;
            }
            Direction direction = HORIZONTAL[directionIndex];
            BlockPos solidPos = pos.relative(direction);
            BlockState solidState = world.getBlockState(solidPos);
            MeshPlan patch = RoundedBlockModel.fluidContactPatch(
                world,
                solidState,
                solidPos,
                solidContactFace(direction),
                heights.first(direction),
                heights.second(direction)
            );
            if (patch.primitives().isEmpty()) {
                continue;
            }
            emit(vertices, patch, pos, solidPos, uvMapping, shade * red, shade * green,
                shade * blue, light, backward);
            if (RoundedBlockModel.isDiagnosticLoggingEnabledForCurrentBake()
                && ACTIVATION_LOGGED.compareAndSet(false, true)) {
                LOGGER.info("Rounded water contact surface active beside {}", solidState);
            }
        }
    }

    private static int roundedContactBits(BlockAndTintGetter world, BlockPos waterPos) {
        int bits = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int index = 0; index < HORIZONTAL.length; index++) {
            Direction direction = HORIZONTAL[index];
            cursor.setWithOffset(waterPos, direction);
            BlockState state = world.getBlockState(cursor);
            if (state.getFluidState().isEmpty() && RoundedBlockModel.isRoundedContactCandidate(state)) {
                bits |= 1 << index;
            }
        }
        return bits;
    }

    private static CornerHeights cornerHeights(BlockAndTintGetter world, BlockPos pos, FluidState state) {
        Fluid fluid = state.getType();
        float center = fluidHeight(world, fluid, pos);
        if (center >= 1.0F) {
            float full = 1.0F - SURFACE_EPSILON;
            return new CornerHeights(full, full, full, full);
        }
        float north = fluidHeight(world, fluid, pos.north());
        float south = fluidHeight(world, fluid, pos.south());
        float east = fluidHeight(world, fluid, pos.east());
        float west = fluidHeight(world, fluid, pos.west());
        return new CornerHeights(
            averageHeight(world, fluid, center, north, east, pos.north().east()) - SURFACE_EPSILON,
            averageHeight(world, fluid, center, north, west, pos.north().west()) - SURFACE_EPSILON,
            averageHeight(world, fluid, center, south, east, pos.south().east()) - SURFACE_EPSILON,
            averageHeight(world, fluid, center, south, west, pos.south().west()) - SURFACE_EPSILON
        );
    }

    private static float averageHeight(
        BlockAndTintGetter world,
        Fluid fluid,
        float center,
        float firstSide,
        float secondSide,
        BlockPos diagonalPos
    ) {
        if (firstSide >= 1.0F || secondSide >= 1.0F) {
            return 1.0F;
        }
        float diagonal = -1.0F;
        if (firstSide > 0.0F || secondSide > 0.0F) {
            diagonal = fluidHeight(world, fluid, diagonalPos);
            if (diagonal >= 1.0F) {
                return 1.0F;
            }
        }
        return averageHeightSamples(diagonal, center, secondSide, firstSide);
    }

    static float averageHeightSamples(float first, float second, float third, float fourth) {
        long sum = addWeightedHeight(0.0F, 0.0F, first);
        sum = addWeightedHeight(Float.intBitsToFloat((int) (sum >>> 32)), Float.intBitsToFloat((int) sum), second);
        sum = addWeightedHeight(Float.intBitsToFloat((int) (sum >>> 32)), Float.intBitsToFloat((int) sum), third);
        sum = addWeightedHeight(Float.intBitsToFloat((int) (sum >>> 32)), Float.intBitsToFloat((int) sum), fourth);
        float heightSum = Float.intBitsToFloat((int) (sum >>> 32));
        float weightSum = Float.intBitsToFloat((int) sum);
        return weightSum == 0.0F ? 0.0F : heightSum / weightSum;
    }

    private static long addWeightedHeight(float heightSum, float weightSum, float height) {
        if (height >= 0.8F) {
            heightSum += height * 10.0F;
            weightSum += 10.0F;
        } else if (height >= 0.0F) {
            heightSum += height;
            weightSum += 1.0F;
        }
        return (long) Float.floatToRawIntBits(heightSum) << 32
            | Float.floatToRawIntBits(weightSum) & 0xFFFFFFFFL;
    }

    private static float fluidHeight(BlockAndTintGetter world, Fluid fluid, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        FluidState fluidState = state.getFluidState();
        if (fluid.isSame(fluidState.getType())) {
            return fluid.isSame(world.getFluidState(pos.above()).getType()) ? 1.0F : fluidState.getOwnHeight();
        }
        return state.isSolid() ? -1.0F : 0.0F;
    }

    private static boolean occludesWaterSurface(
        BlockAndTintGetter world,
        BlockPos abovePos,
        BlockState aboveState,
        float minimumHeight
    ) {
        if (!aboveState.canOcclude()) {
            return false;
        }
        VoxelShape water = Shapes.box(0.0, 0.0, 0.0, 1.0, minimumHeight, 1.0);
        return Shapes.blockOccudes(water, aboveState.getOcclusionShape(world, abovePos), Direction.UP);
    }

    private static void emit(
        VertexConsumer output,
        MeshPlan patch,
        BlockPos waterPos,
        BlockPos solidPos,
        UvMapping uvMapping,
        float red,
        float green,
        float blue,
        int light,
        boolean backward
    ) {
        float baseX = sectionBaseCoordinate(waterPos.getX(), solidPos.getX());
        float baseY = sectionBaseCoordinate(waterPos.getY(), solidPos.getY());
        float baseZ = sectionBaseCoordinate(waterPos.getZ(), solidPos.getZ());
        for (MeshPrimitive primitive : patch.primitives()) {
            emitPrimitive(output, primitive.vertices(), waterPos, solidPos, uvMapping,
                baseX, baseY, baseZ, red, green, blue, light, false);
            if (backward) {
                emitPrimitive(output, primitive.vertices(), waterPos, solidPos, uvMapping,
                    baseX, baseY, baseZ, red, green, blue, light, true);
            }
        }
    }

    private static void emitPrimitive(
        VertexConsumer output,
        List<MeshVertex> vertices,
        BlockPos waterPos,
        BlockPos solidPos,
        UvMapping uvMapping,
        float baseX,
        float baseY,
        float baseZ,
        float red,
        float green,
        float blue,
        int light,
        boolean reverse
    ) {
        for (int offset = 0; offset < vertices.size(); offset++) {
            int index = reverse ? vertices.size() - 1 - offset : offset;
            Vec3 position = vertices.get(index).position();
            float localX = (solidPos.getX() - waterPos.getX()) + (float) position.x();
            float localZ = (solidPos.getZ() - waterPos.getZ()) + (float) position.z();
            long packedUv = uvMapping.packed(localX, localZ);
            output.addVertex(baseX + (float) position.x(), baseY + (float) position.y(), baseZ + (float) position.z())
                .setColor(red, green, blue, 1.0F)
                .setUv(
                    Float.intBitsToFloat((int) (packedUv >>> 32)),
                    Float.intBitsToFloat((int) packedUv)
                )
                .setLight(light)
                .setNormal(0.0F, 1.0F, 0.0F);
        }
    }

    private static int lightColor(BlockAndTintGetter world, BlockPos pos) {
        int current = LevelRenderer.getLightColor(world, pos);
        int above = LevelRenderer.getLightColor(world, pos.above());
        int block = Math.max(current & 0xFF, above & 0xFF);
        int sky = Math.max((current >> 16) & 0xFF, (above >> 16) & 0xFF);
        return block | sky << 16;
    }

    private static CubeFace solidContactFace(Direction directionFromWater) {
        return switch (directionFromWater) {
            case NORTH -> CubeFace.SOUTH;
            case SOUTH -> CubeFace.NORTH;
            case WEST -> CubeFace.EAST;
            case EAST -> CubeFace.WEST;
            default -> throw new IllegalArgumentException("Horizontal direction required");
        };
    }

    static int sectionBaseCoordinate(int waterCoordinate, int solidCoordinate) {
        return (waterCoordinate & 15) + solidCoordinate - waterCoordinate;
    }

    private record CornerHeights(float northEast, float northWest, float southEast, float southWest) {
        private float minimum() {
            return Math.min(Math.min(northEast, northWest), Math.min(southEast, southWest));
        }

        private float first(Direction direction) {
            return switch (direction) {
                case NORTH, WEST -> northWest;
                case SOUTH -> southWest;
                case EAST -> northEast;
                default -> throw new IllegalArgumentException("Horizontal direction required");
            };
        }

        private float second(Direction direction) {
            return switch (direction) {
                case NORTH, EAST -> northEast;
                case SOUTH -> southEast;
                case WEST -> southWest;
                default -> throw new IllegalArgumentException("Horizontal direction required");
            };
        }
    }

    private record UvMapping(
        TextureAtlasSprite sprite,
        float uBase,
        float uX,
        float uZ,
        float vBase,
        float vX,
        float vZ,
        float shrink,
        float centerU,
        float centerV
    ) {
        private static UvMapping create(TextureAtlasSprite[] sprites, net.minecraft.world.phys.Vec3 flow) {
            if (flow.x == 0.0 && flow.z == 0.0) {
                TextureAtlasSprite sprite = sprites[0];
                return new UvMapping(
                    sprite, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                    sprites[0].uvShrinkRatio(),
                    (sprite.getU0() + sprite.getU1()) * 0.5F,
                    (sprite.getV0() + sprite.getV1()) * 0.5F
                );
            }
            TextureAtlasSprite sprite = sprites[1];
            float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2.0);
            float sin = Mth.sin(angle) * 0.25F;
            float cos = Mth.cos(angle) * 0.25F;
            return new UvMapping(
                sprite,
                0.5F - cos - sin, 2.0F * cos, 2.0F * sin,
                0.5F - cos + sin, -2.0F * sin, 2.0F * cos,
                sprites[0].uvShrinkRatio(),
                (sprite.getU0() + sprite.getU1()) * 0.5F,
                (sprite.getV0() + sprite.getV1()) * 0.5F
            );
        }

        private long packed(float localX, float localZ) {
            float wrappedX = localX - Mth.floor(localX);
            float wrappedZ = localZ - Mth.floor(localZ);
            float u = sprite.getU(uBase + uX * wrappedX + uZ * wrappedZ);
            float v = sprite.getV(vBase + vX * wrappedX + vZ * wrappedZ);
            u = Mth.lerp(shrink, u, centerU);
            v = Mth.lerp(shrink, v, centerV);
            return (long) Float.floatToRawIntBits(u) << 32 | Float.floatToRawIntBits(v) & 0xFFFFFFFFL;
        }
    }
}
