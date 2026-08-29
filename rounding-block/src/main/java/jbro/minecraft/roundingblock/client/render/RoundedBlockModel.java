package jbro.minecraft.roundingblock.client.render;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import jbro.minecraft.roundingblock.mesh.CubeFace;
import jbro.minecraft.roundingblock.mesh.ExposureMask;
import jbro.minecraft.roundingblock.mesh.MeshPlan;
import jbro.minecraft.roundingblock.mesh.MeshPrimitive;
import jbro.minecraft.roundingblock.mesh.MeshVertex;
import jbro.minecraft.roundingblock.mesh.RoundedVoxelMesher;
import jbro.minecraft.roundingblock.mesh.VoxelNeighborhood;
import jbro.minecraft.roundingblock.mesh.VerticalBlockShape;
import jbro.minecraft.roundingblock.mesh.VerticalVoxelNeighborhood;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoundedBlockModel implements BakedModel, FabricBakedModel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rounding-Block");
    private static final int PLAN_CACHE_LIMIT = 256;
    private static final RoundedVoxelMesher MESHER = new RoundedVoxelMesher();
    private static final Map<Integer, MeshPlan> PLAN_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, MeshPlan> VERTICAL_PLAN_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockState, VerticalBlockShape> ROUNDED_SHAPES = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED_DIAGNOSTICS = ConcurrentHashMap.newKeySet();

    private final BakedModel delegate;
    private final BlockState expectedState;
    private final VerticalBlockShape shape;
    private final ModelAppearanceMode appearanceMode;
    private final Map<CubeFace, List<FaceAppearance>> staticAppearances;
    private final Map<BakedQuad, Optional<FaceAppearance>> appearanceCache;

    private RoundedBlockModel(
        BakedModel delegate,
        BlockState expectedState,
        VerticalBlockShape shape,
        ModelAppearanceMode appearanceMode,
        Map<CubeFace, List<FaceAppearance>> appearances,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache
    ) {
        this.delegate = delegate;
        this.expectedState = expectedState;
        this.shape = shape;
        this.appearanceMode = appearanceMode;
        this.staticAppearances = appearances;
        this.appearanceCache = appearanceCache;
    }

    public static BakedModel wrapIfEligible(
        BakedModel delegate,
        BlockState expectedState,
        VerticalBlockShape shape,
        ModelAppearanceMode appearanceMode
    ) {
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache = new ConcurrentHashMap<>();
        Map<CubeFace, List<FaceAppearance>> appearances = FaceAppearance.analyze(
            delegate,
            expectedState,
            shape,
            () -> RandomSource.create(0x524F554E444544L),
            appearanceCache
        );
        if (appearances.size() != CubeFace.values().length) {
            return delegate;
        }
        ROUNDED_SHAPES.put(expectedState, shape);
        return new RoundedBlockModel(delegate, expectedState, shape, appearanceMode, appearances, appearanceCache);
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(
        BlockAndTintGetter blockView,
        BlockState state,
        BlockPos pos,
        Supplier<RandomSource> randomSupplier,
        RenderContext context
    ) {
        logOnce("world-emitter", "World emitter reached; first state is {}", state);
        String rejection = rejectionReason(blockView, state, pos);
        if (rejection != null) {
            logOnce("fallback-" + rejection, "Original-model fallback [{}]; first state is {}", rejection, state);
            emitOriginal(blockView, state, pos, randomSupplier, context);
            return;
        }
        NeighborhoodSnapshot snapshot = neighborhood(blockView, pos);
        ExposureMask exposure = exposureMask(blockView, state, pos);
        if (!snapshot.hasPartialShape() && exposure.bits() == 0) {
            return;
        }
        if ((!snapshot.hasPartialShape() && snapshot.blocks().isAxisAlignedLayered())
            || (snapshot.hasPartialShape() && snapshot.vertical().isHorizontallyLayered())) {
            logOnce("layered-original", "Axis-aligned terrain uses the original model directly; first state is {}", state);
            emitOriginal(blockView, state, pos, randomSupplier, context);
            return;
        }
        Map<CubeFace, List<FaceAppearance>> appearances = appearanceMode == ModelAppearanceMode.DYNAMIC
            ? FaceAppearance.analyze(delegate, state, shape, randomSupplier, appearanceCache)
            : staticAppearances;
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        if (appearances.size() != CubeFace.values().length) {
            logOnce("fallback-appearance", "Original-model fallback [appearance-analysis]; first state is {}", state);
            emitOriginal(blockView, state, pos, randomSupplier, context);
            return;
        }
        if (renderer == null) {
            logOnce("fallback-renderer", "Original-model fallback [renderer-unavailable]; first state is {}", state);
            emitOriginal(blockView, state, pos, randomSupplier, context);
            return;
        }

        int planarFaceBits = snapshot.hasPartialShape() ? 0 : snapshot.blocks().planarFaceBits();
        MeshPlan plan = snapshot.hasPartialShape()
            ? cachedPlan(snapshot.vertical())
            : cachedPlan(snapshot.blocks());
        if (planarFaceBits != 0) {
            logOnce("planar-original", "Planar faces use original model quads; first state is {}", state);
            emitOriginalFaces(blockView, state, pos, randomSupplier, context, planarFaceBits);
        }
        logOnce(
            "rounded-output",
            "Rounded world geometry emitted; first state is {}, exposure bits={}, primitives={}",
            state,
            exposure.bits(),
            plan.primitives().size()
        );
        if (!plan.primitives().isEmpty()) {
            logOnce(
                "rounded-nonempty-output",
                "Non-empty rounded world geometry emitted; first state is {}, exposure bits={}, primitives={}",
                state,
                exposure.bits(),
                plan.primitives().size()
            );
        }
        if (shape.isPartial()) {
            logOnce(
                "rounded-partial-output",
                "Rounded slab geometry emitted; first state is {}, primitives={}",
                state,
                plan.primitives().size()
            );
        }
        emitPlan(plan, appearances, renderer, context);
    }

    @Override
    public void emitItemQuads(ItemStack stack, Supplier<RandomSource> randomSupplier, RenderContext context) {
        ((FabricBakedModel) delegate).emitItemQuads(stack, randomSupplier, context);
    }

    private String rejectionReason(BlockAndTintGetter blockView, BlockState state, BlockPos pos) {
        if (state != expectedState) {
            return "state-mismatch";
        }
        if (blockView.getBlockState(pos) != state) {
            return "world-state-mismatch";
        }
        if (state.getRenderShape() != RenderShape.MODEL) {
            return "non-model-render-shape";
        }
        if (!state.getFluidState().isEmpty()) {
            return "contains-fluid";
        }
        if (!state.canOcclude() && !state.is(BlockTags.LEAVES) && !shape.isPartial()) {
            return "non-occluding";
        }
        if (!isSupportedRenderType(ItemBlockRenderTypes.getChunkRenderType(state))) {
            return "non-solid-render-layer";
        }
        return null;
    }

    private static boolean isSupportedRenderType(RenderType renderType) {
        return renderType == RenderType.solid() || renderType == RenderType.cutoutMipped();
    }

    private static NeighborhoodSnapshot neighborhood(BlockAndTintGetter blockView, BlockPos pos) {
        VoxelNeighborhood.Builder blocks = VoxelNeighborhood.builder();
        VerticalVoxelNeighborhood.Builder vertical = VerticalVoxelNeighborhood.builder();
        boolean hasPartialShape = false;
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    VerticalBlockShape neighborShape = ROUNDED_SHAPES.get(blockView.getBlockState(pos.offset(x, y, z)));
                    if (neighborShape != null) {
                        blocks.occupy(x, y, z);
                        hasPartialShape |= neighborShape.isPartial();
                        for (int layer = 0; layer <= 1; layer++) {
                            if (neighborShape.occupiesLayer(layer)) {
                                vertical.occupy(x, 2 * y + layer, z);
                            }
                        }
                    }
                }
            }
        }
        return new NeighborhoodSnapshot(blocks.build(), vertical.build(), hasPartialShape);
    }

    private static MeshPlan cachedPlan(VoxelNeighborhood neighborhood) {
        MeshPlan cached = PLAN_CACHE.get(neighborhood.bits());
        if (cached != null) {
            return cached;
        }
        MeshPlan generated = MESHER.mesh(neighborhood).withoutPlanarFaces(neighborhood.planarFaceBits());
        if (PLAN_CACHE.size() >= PLAN_CACHE_LIMIT) {
            return generated;
        }
        MeshPlan raced = PLAN_CACHE.putIfAbsent(neighborhood.bits(), generated);
        return raced == null ? generated : raced;
    }

    private static MeshPlan cachedPlan(VerticalVoxelNeighborhood neighborhood) {
        MeshPlan cached = VERTICAL_PLAN_CACHE.get(neighborhood.bits());
        if (cached != null) {
            return cached;
        }
        MeshPlan generated = MESHER.mesh(neighborhood);
        if (VERTICAL_PLAN_CACHE.size() >= PLAN_CACHE_LIMIT) {
            return generated;
        }
        MeshPlan raced = VERTICAL_PLAN_CACHE.putIfAbsent(neighborhood.bits(), generated);
        return raced == null ? generated : raced;
    }

    private void emitOriginalFaces(
        BlockAndTintGetter blockView,
        BlockState state,
        BlockPos pos,
        Supplier<RandomSource> randomSupplier,
        RenderContext context,
        int planarFaceBits
    ) {
        context.pushTransform(quad -> {
            CubeFace face = FaceAppearance.toCubeFace(quad.lightFace());
            return (planarFaceBits & (1 << face.ordinal())) != 0;
        });
        try {
            emitOriginal(blockView, state, pos, randomSupplier, context);
        } finally {
            context.popTransform();
        }
    }

    private static void logOnce(String key, String message, Object... arguments) {
        if (LOGGED_DIAGNOSTICS.add(key)) {
            LOGGER.info(message, arguments);
        }
    }

    private static ExposureMask exposureMask(BlockAndTintGetter blockView, BlockState state, BlockPos pos) {
        int bits = 0;
        for (Direction direction : Direction.values()) {
            if (Block.shouldRenderFace(state, blockView, pos, direction, pos.relative(direction))) {
                bits |= 1 << FaceAppearance.toCubeFace(direction).ordinal();
            }
        }
        return new ExposureMask(bits);
    }

    private static void emitPlan(
        MeshPlan plan,
        Map<CubeFace, List<FaceAppearance>> appearances,
        Renderer renderer,
        RenderContext context
    ) {
        RenderMaterial standard = renderer.materialById(RenderMaterial.MATERIAL_STANDARD);
        QuadEmitter emitter = context.getEmitter();
        for (MeshPrimitive primitive : plan.primitives()) {
            Direction nominalFace = FaceAppearance.toDirection(primitive.materialFace());
            List<MeshVertex> vertices = primitive.vertices();
            for (FaceAppearance appearance : appearances.get(primitive.materialFace())) {
                for (int outputIndex = 0; outputIndex < 4; outputIndex++) {
                    MeshVertex vertex = vertices.get(Math.min(outputIndex, vertices.size() - 1));
                    AffineUvMapping.Uv uv = appearance.uv(vertex.position());
                    emitter.pos(
                        outputIndex,
                        (float) vertex.position().x(),
                        (float) vertex.position().y(),
                        (float) vertex.position().z()
                    );
                    emitter.normal(
                        outputIndex,
                        (float) vertex.normal().x(),
                        (float) vertex.normal().y(),
                        (float) vertex.normal().z()
                    );
                    emitter.uv(outputIndex, uv.u(), uv.v());
                    emitter.color(outputIndex, 0xFFFFFFFF);
                }
                emitter.material(standard);
                emitter.colorIndex(appearance.tintIndex());
                emitter.nominalFace(nominalFace);
                emitter.cullFace(null);
                emitter.emit();
            }
        }
    }

    private void emitOriginal(
        BlockAndTintGetter blockView,
        BlockState state,
        BlockPos pos,
        Supplier<RandomSource> randomSupplier,
        RenderContext context
    ) {
        ((FabricBakedModel) delegate).emitBlockQuads(blockView, state, pos, randomSupplier, context);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
        return delegate.getQuads(state, direction, random);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return delegate.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return delegate.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return delegate.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return delegate.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return delegate.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return delegate.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return delegate.getOverrides();
    }

    private record NeighborhoodSnapshot(
        VoxelNeighborhood blocks,
        VerticalVoxelNeighborhood vertical,
        boolean hasPartialShape
    ) {
    }

}
