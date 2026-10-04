package jbro.minecraft.roundingblock.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import jbro.minecraft.roundingblock.client.settings.RoundingBlockConfig;
import jbro.minecraft.roundingblock.client.render.ModelAppearanceMode;
import jbro.minecraft.roundingblock.client.render.RoundedBlockModel;
import jbro.minecraft.roundingblock.mesh.MicroBlockShape;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RoundingBlockModelPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rounding-Block");
    private static final AtomicBoolean ACTIVATION_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean WEIGHTED_ACTIVATION_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SLAB_ACTIVATION_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean STAIR_ACTIVATION_LOGGED = new AtomicBoolean();

    private RoundingBlockModelPlugin() {
    }

    static void register(RoundingBlockConfig config) {
        RoundedBlockModel.applyConfig(config);
        ModelLoadingPlugin.register(context -> {
            RoundedBlockModel.beginModelBake();
            if (!RoundedBlockModel.isEnabledForCurrentBake()) {
                return;
            }
            Map<ModelResourceLocation, BlockState> statesByModel = BlockStateIndex.INSTANCE;
            context.modifyModelAfterBake().register(
                ModelModifier.WRAP_PHASE,
                (model, modifierContext) -> wrapEligibleModel(model, modifierContext.topLevelId(), statesByModel)
            );
        });
    }

    private static BakedModel wrapEligibleModel(
        BakedModel model,
        ModelResourceLocation topLevelId,
        Map<ModelResourceLocation, BlockState> statesByModel
    ) {
        if (model == null || topLevelId == null || model instanceof RoundedBlockModel) {
            return model;
        }
        if (!RoundedBlockModel.isEnabledForCurrentBake()) {
            return model;
        }
        boolean diagnosticLogging = RoundedBlockModel.isDiagnosticLoggingEnabledForCurrentBake();
        BlockState state = statesByModel.get(topLevelId);
        MicroBlockShape shape = state == null ? null : shapeFor(state);
        ModelAppearanceMode appearanceMode = ModelAppearanceMode.forModel(model);
        if (state == null
            || appearanceMode == ModelAppearanceMode.UNSUPPORTED
            || !((FabricBakedModel) model).isVanillaAdapter()
            || state.getRenderShape() != RenderShape.MODEL
            || !state.getFluidState().isEmpty()
            || !state.canOcclude() && !state.is(BlockTags.LEAVES) && !shape.isPartial()
            || !isSupportedRenderType(ItemBlockRenderTypes.getChunkRenderType(state))) {
            return model;
        }
        BakedModel wrapped = RoundedBlockModel.wrapIfEligible(model, state, shape, appearanceMode);
        if (diagnosticLogging && wrapped != model && ACTIVATION_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("Rounded full-cube model pipeline active; first wrapped model is {}", topLevelId);
        }
        if (diagnosticLogging
            && wrapped != model
            && appearanceMode == ModelAppearanceMode.DYNAMIC
            && WEIGHTED_ACTIVATION_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("Seeded weighted cube pipeline active; first wrapped model is {}", topLevelId);
        }
        if (diagnosticLogging
            && wrapped != model
            && shape.isPartial()
            && !shape.isComplex()
            && SLAB_ACTIVATION_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("Rounded slab model pipeline active; first wrapped model is {}", topLevelId);
        }
        if (diagnosticLogging
            && wrapped != model
            && shape.isComplex()
            && STAIR_ACTIVATION_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("Rounded stair model pipeline active; first wrapped model is {}", topLevelId);
        }
        return wrapped;
    }

    static MicroBlockShape shapeFor(BlockState state) {
        if (state.getBlock() instanceof StairBlock || state.is(BlockTags.STAIRS)) {
            return sampleShape(state);
        }
        if (!state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            return MicroBlockShape.FULL;
        }
        SlabType type = state.getValue(BlockStateProperties.SLAB_TYPE);
        return switch (type) {
            case BOTTOM -> MicroBlockShape.BOTTOM_HALF;
            case TOP -> MicroBlockShape.TOP_HALF;
            case DOUBLE -> MicroBlockShape.FULL;
        };
    }

    private static MicroBlockShape sampleShape(BlockState state) {
        java.util.List<AABB> boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
        MicroBlockShape.Builder builder = MicroBlockShape.builder();
        for (int z = 0; z <= 1; z++) {
            for (int y = 0; y <= 1; y++) {
                for (int x = 0; x <= 1; x++) {
                    double sampleX = 0.25 + 0.5 * x;
                    double sampleY = 0.25 + 0.5 * y;
                    double sampleZ = 0.25 + 0.5 * z;
                    for (AABB box : boxes) {
                        if (sampleX > box.minX && sampleX < box.maxX
                            && sampleY > box.minY && sampleY < box.maxY
                            && sampleZ > box.minZ && sampleZ < box.maxZ) {
                            builder.occupy(x, y, z);
                            break;
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    private static boolean isSupportedRenderType(RenderType renderType) {
        return renderType == RenderType.solid() || renderType == RenderType.cutoutMipped();
    }

    private static Map<ModelResourceLocation, BlockState> indexBlockStates() {
        Map<ModelResourceLocation, BlockState> result = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                result.put(BlockModelShaper.stateToModelLocation(state), state);
            }
        }
        return Map.copyOf(result);
    }

    private static final class BlockStateIndex {
        private static final Map<ModelResourceLocation, BlockState> INSTANCE = indexBlockStates();
    }
}
