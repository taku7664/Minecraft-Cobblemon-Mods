package jbro.minecraft.roundingblock.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import jbro.minecraft.roundingblock.client.render.ModelAppearanceMode;
import jbro.minecraft.roundingblock.client.render.RoundedBlockModel;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RoundingBlockModelPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rounding-Block");
    private static final AtomicBoolean ACTIVATION_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean WEIGHTED_ACTIVATION_LOGGED = new AtomicBoolean();

    private RoundingBlockModelPlugin() {
    }

    static void register() {
        Map<ModelResourceLocation, BlockState> statesByModel = indexBlockStates();
        ModelLoadingPlugin.register(context -> context.modifyModelAfterBake().register(
            ModelModifier.WRAP_PHASE,
            (model, modifierContext) -> wrapEligibleModel(model, modifierContext.topLevelId(), statesByModel)
        ));
    }

    private static BakedModel wrapEligibleModel(
        BakedModel model,
        ModelResourceLocation topLevelId,
        Map<ModelResourceLocation, BlockState> statesByModel
    ) {
        if (model == null || topLevelId == null || model instanceof RoundedBlockModel) {
            return model;
        }
        BlockState state = statesByModel.get(topLevelId);
        ModelAppearanceMode appearanceMode = ModelAppearanceMode.forModel(model);
        if (state == null
            || appearanceMode == ModelAppearanceMode.UNSUPPORTED
            || !((FabricBakedModel) model).isVanillaAdapter()
            || state.getRenderShape() != RenderShape.MODEL
            || !state.getFluidState().isEmpty()
            || !state.canOcclude() && !state.is(BlockTags.LEAVES)
            || !isSupportedRenderType(ItemBlockRenderTypes.getChunkRenderType(state))) {
            return model;
        }
        BakedModel wrapped = RoundedBlockModel.wrapIfEligible(model, state, appearanceMode);
        if (wrapped != model && ACTIVATION_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("Rounded full-cube model pipeline active; first wrapped model is {}", topLevelId);
        }
        if (wrapped != model
            && appearanceMode == ModelAppearanceMode.DYNAMIC
            && WEIGHTED_ACTIVATION_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("Seeded weighted cube pipeline active; first wrapped model is {}", topLevelId);
        }
        return wrapped;
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
}
