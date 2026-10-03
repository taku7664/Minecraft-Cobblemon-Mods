package jbro.minecraft.roundingblock.client.render;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import jbro.minecraft.roundingblock.client.settings.RoundingBlockConfig;
import jbro.minecraft.roundingblock.mesh.CubeFace;
import jbro.minecraft.roundingblock.mesh.FluidContactPatchMesher;
import jbro.minecraft.roundingblock.mesh.MeshPlan;
import jbro.minecraft.roundingblock.mesh.MeshPrimitive;
import jbro.minecraft.roundingblock.mesh.MeshVertex;
import jbro.minecraft.roundingblock.mesh.MicroBlockShape;
import jbro.minecraft.roundingblock.mesh.MicroVoxelNeighborhood;
import jbro.minecraft.roundingblock.mesh.RoundedVoxelMesher;
import jbro.minecraft.roundingblock.mesh.VoxelNeighborhood;
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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoundedBlockModel implements BakedModel, FabricBakedModel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rounding-Block");
    private static final int FACE_COUNT = CubeFace.values().length;
    private static final MeshPlan EMPTY_PLAN = new MeshPlan(List.of());
    private static volatile RuntimeResources activeRuntime;
    private static final Set<String> LOGGED_DIAGNOSTICS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean WORLD_EMITTER_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean LAYERED_ORIGINAL_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean PLANAR_ORIGINAL_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean ROUNDED_OUTPUT_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean ROUNDED_PARTIAL_LOGGED = new AtomicBoolean();
    private static final ThreadLocal<NeighborhoodScratch> NEIGHBORHOOD_SCRATCH =
        ThreadLocal.withInitial(NeighborhoodScratch::new);

    private final BakedModel delegate;
    private final RuntimeResources runtime;
    private final BlockState expectedState;
    private final MicroBlockShape shape;
    private final ModelAppearanceMode appearanceMode;
    private final Map<CubeFace, List<FaceAppearance>> staticAppearances;
    private final Map<BakedQuad, Optional<FaceAppearance>> appearanceCache;
    private final IdentitySelectionCache<BakedQuad, Map<CubeFace, List<FaceAppearance>>>
        dynamicAppearanceCache;

    private RoundedBlockModel(
        BakedModel delegate,
        RuntimeResources runtime,
        BlockState expectedState,
        MicroBlockShape shape,
        ModelAppearanceMode appearanceMode,
        Map<CubeFace, List<FaceAppearance>> appearances,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache
    ) {
        this.delegate = delegate;
        this.runtime = runtime;
        this.expectedState = expectedState;
        this.shape = shape;
        this.appearanceMode = appearanceMode;
        this.staticAppearances = appearances;
        this.appearanceCache = appearanceMode == ModelAppearanceMode.DYNAMIC ? appearanceCache : null;
        this.dynamicAppearanceCache = appearanceMode == ModelAppearanceMode.DYNAMIC
            ? new IdentitySelectionCache<>(runtime.appearanceVariantCacheLimit)
            : null;
    }

    public static synchronized void applyConfig(RoundingBlockConfig config) {
        activatePreparedRuntime(prepareConfig(config));
    }

    public static PreparedRuntime prepareConfig(RoundingBlockConfig config) {
        return new PreparedRuntime(new RuntimeResources(config));
    }

    public static synchronized void activatePreparedRuntime(PreparedRuntime prepared) {
        activeRuntime = prepared.runtime;
        resetDiagnostics();
    }

    public static PreparedRuntime currentRuntimeSnapshot() {
        return new PreparedRuntime(requireRuntime());
    }

    public static synchronized void beginModelBake() {
        RuntimeResources runtime = requireRuntime();
        activeRuntime = runtime.freshBakeGeneration();
    }

    public static boolean isEnabledForCurrentBake() {
        return requireRuntime().config.enabled();
    }

    public static boolean isDiagnosticLoggingEnabledForCurrentBake() {
        return requireRuntime().diagnosticLogging;
    }

    public static RoundingBlockConfig currentConfig() {
        return requireRuntime().config;
    }

    /** Cheap rejection used before the fluid renderer performs surface sampling. */
    public static boolean isRoundedContactCandidate(BlockState state) {
        RuntimeResources runtime = requireRuntime();
        return runtime.config.enabled() && runtime.roundedShapes.containsKey(state);
    }

    /**
     * Returns only the horizontal water surface that occupies this rounded
     * block's carved contact recess. An empty plan means that the state is not
     * currently rounded or the requested fluid edge remains planar.
     */
    public static MeshPlan fluidContactPatch(
        BlockAndTintGetter blockView,
        BlockState solidState,
        BlockPos solidPos,
        CubeFace contactFace,
        float firstHeight,
        float secondHeight
    ) {
        RuntimeResources runtime = requireRuntime();
        if (!runtime.config.enabled() || runtime.roundedShapes.get(solidState) == null) {
            return EMPTY_PLAN;
        }
        Object topology = contactTopologyKey(runtime, blockView, solidState, solidPos);
        if (topology == null) {
            return EMPTY_PLAN;
        }
        FluidContactPlanKey key = new FluidContactPlanKey(
            topology, contactFace, Float.floatToIntBits(firstHeight), Float.floatToIntBits(secondHeight)
        );
        return runtime.fluidContactPlans.get(
            key,
            ignored -> runtime.fluidContactMesher.mesh(
                solidPlan(runtime, topology), contactFace, firstHeight, secondHeight
            )
        );
    }

    static Object currentBakeGenerationForTest() {
        return requireRuntime();
    }

    private static RuntimeResources requireRuntime() {
        RuntimeResources runtime = activeRuntime;
        if (runtime == null) {
            throw new IllegalStateException("Rounding-Block must be configured before model baking");
        }
        return runtime;
    }

    private static Object contactTopologyKey(
        RuntimeResources runtime,
        BlockAndTintGetter blockView,
        BlockState centerState,
        BlockPos centerPos
    ) {
        VoxelNeighborhood.Builder blocks = VoxelNeighborhood.builder();
        boolean hasPartialShape = false;
        boolean hasComplexShape = false;
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    neighborPos.set(centerPos.getX() + x, centerPos.getY() + y, centerPos.getZ() + z);
                    BlockState neighborState = blockView.getBlockState(neighborPos);
                    MicroBlockShape neighborShape = runtime.roundedShapes.get(neighborState);
                    if (neighborShape == null || !connectsForMeshing(centerState, neighborState, neighborShape)) {
                        continue;
                    }
                    blocks.occupy(x, y, z);
                    hasPartialShape |= neighborShape.isPartial();
                    hasComplexShape |= neighborShape.isComplex();
                }
            }
        }
        if (hasComplexShape) {
            return microNeighborhood(runtime, blockView, centerState, centerPos);
        }
        if (hasPartialShape) {
            return verticalNeighborhood(runtime, blockView, centerState, centerPos);
        }
        return blocks.build();
    }

    private static MeshPlan solidPlan(RuntimeResources runtime, Object topology) {
        if (topology instanceof MicroVoxelNeighborhood micro) {
            return runtime.complexShapePlans.get(micro, runtime.mesher::mesh);
        }
        if (topology instanceof VerticalVoxelNeighborhood vertical) {
            return runtime.slabPlans.get(vertical.bits(), runtime.slabPlanLoader);
        }
        VoxelNeighborhood full = (VoxelNeighborhood) topology;
        return runtime.fullBlockPlans.get(full.bits(), runtime.fullBlockPlanLoader);
    }

    private static void resetDiagnostics() {
        LOGGED_DIAGNOSTICS.clear();
        WORLD_EMITTER_LOGGED.set(false);
        LAYERED_ORIGINAL_LOGGED.set(false);
        PLANAR_ORIGINAL_LOGGED.set(false);
        ROUNDED_OUTPUT_LOGGED.set(false);
        ROUNDED_PARTIAL_LOGGED.set(false);
    }

    public static BakedModel wrapIfEligible(
        BakedModel delegate,
        BlockState expectedState,
        MicroBlockShape shape,
        ModelAppearanceMode appearanceMode
    ) {
        RuntimeResources runtime = requireRuntime();
        if (!runtime.config.enabled()) {
            return delegate;
        }
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache = new ConcurrentHashMap<>();
        Supplier<RandomSource> deterministicRandom = () -> RandomSource.create(0x524F554E444544L);
        Map<CubeFace, List<FaceAppearance>> appearances = shape.isComplex()
            ? FaceAppearance.analyzeComplex(delegate, expectedState, deterministicRandom, appearanceCache)
            : FaceAppearance.analyze(
                delegate,
                expectedState,
                shape.verticalProfile(),
                deterministicRandom,
                appearanceCache
            );
        if (appearances.size() != FACE_COUNT) {
            return delegate;
        }
        runtime.roundedShapes.put(expectedState, shape);
        return new RoundedBlockModel(
            delegate, runtime, expectedState, shape, appearanceMode, appearances, appearanceCache
        );
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
        if (runtime.diagnosticLogging
            && !WORLD_EMITTER_LOGGED.get()
            && WORLD_EMITTER_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("World emitter reached; first state is {}", state);
        }
        String rejection = rejectionReason(blockView, state, pos);
        if (rejection != null) {
            logOnce("fallback-" + rejection, "Original-model fallback [{}]; first state is {}", rejection, state);
            emitOriginal(blockView, state, pos, randomSupplier, context);
            return;
        }
        NeighborhoodScratch snapshot = NEIGHBORHOOD_SCRATCH.get();
        snapshot.scan(runtime, blockView, state, pos);
        int blockBits = snapshot.blockBits;
        int exposureBits = snapshot.exposureBits;
        boolean hasPartialShape = snapshot.hasPartialShape;
        boolean hasComplexShape = snapshot.hasComplexShape;
        if (!hasPartialShape && exposureBits == 0) {
            return;
        }
        VerticalVoxelNeighborhood vertical = VerticalVoxelNeighborhood.EMPTY;
        MicroVoxelNeighborhood micro = MicroVoxelNeighborhood.EMPTY;
        if (hasComplexShape) {
            micro = microNeighborhood(blockView, state, pos);
        } else if (hasPartialShape) {
            vertical = verticalNeighborhood(blockView, state, pos);
        }
        if ((!hasPartialShape && VoxelNeighborhood.isAxisAlignedLayered(blockBits))
            || (hasPartialShape && !hasComplexShape && vertical.isHorizontallyLayered())) {
            if (runtime.diagnosticLogging
                && !LAYERED_ORIGINAL_LOGGED.get()
                && LAYERED_ORIGINAL_LOGGED.compareAndSet(false, true)) {
                LOGGER.info("Axis-aligned terrain uses the original model directly; first state is {}", state);
            }
            emitOriginal(blockView, state, pos, randomSupplier, context);
            return;
        }
        Map<CubeFace, List<FaceAppearance>> appearances = appearanceMode == ModelAppearanceMode.DYNAMIC
            ? FaceAppearance.analyzeDynamic(
                delegate,
                state,
                shape.isComplex() ? null : shape.verticalProfile(),
                shape.isComplex(),
                randomSupplier,
                appearanceCache,
                dynamicAppearanceCache
            )
            : staticAppearances;
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        if (appearances.size() != FACE_COUNT) {
            logOnce("fallback-appearance", "Original-model fallback [appearance-analysis]; first state is {}", state);
            emitOriginal(blockView, state, pos, randomSupplier, context);
            return;
        }
        if (renderer == null) {
            logOnce("fallback-renderer", "Original-model fallback [renderer-unavailable]; first state is {}", state);
            emitOriginal(blockView, state, pos, randomSupplier, context);
            return;
        }

        int planarFaceBits = hasPartialShape ? 0 : VoxelNeighborhood.planarFaceBits(blockBits);
        MeshPlan plan = hasComplexShape
            ? cachedPlan(micro)
            : hasPartialShape
                ? cachedPlan(vertical)
                : cachedPlan(blockBits);
        if (planarFaceBits != 0) {
            if (runtime.diagnosticLogging
                && !PLANAR_ORIGINAL_LOGGED.get()
                && PLANAR_ORIGINAL_LOGGED.compareAndSet(false, true)) {
                LOGGER.info("Planar faces use original model quads; first state is {}", state);
            }
            emitOriginalFaces(blockView, state, pos, randomSupplier, context, planarFaceBits);
        }
        if (runtime.diagnosticLogging
            && !ROUNDED_OUTPUT_LOGGED.get()
            && ROUNDED_OUTPUT_LOGGED.compareAndSet(false, true)) {
            LOGGER.info(
                "Rounded world geometry emitted; first state is {}, exposure bits={}, primitives={}",
                state,
                exposureBits,
                plan.primitives().size()
            );
        }
        if (shape.isPartial()
            && !shape.isComplex()
            && runtime.diagnosticLogging
            && !ROUNDED_PARTIAL_LOGGED.get()
            && ROUNDED_PARTIAL_LOGGED.compareAndSet(false, true)) {
            LOGGER.info(
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

    static Direction directionForNeighborOffset(int x, int y, int z) {
        if (y == 0 && z == 0) {
            return x == -1 ? Direction.WEST : x == 1 ? Direction.EAST : null;
        }
        if (x == 0 && z == 0) {
            return y == -1 ? Direction.DOWN : y == 1 ? Direction.UP : null;
        }
        if (x == 0 && y == 0) {
            return z == -1 ? Direction.NORTH : z == 1 ? Direction.SOUTH : null;
        }
        return null;
    }

    private VerticalVoxelNeighborhood verticalNeighborhood(
        BlockAndTintGetter blockView,
        BlockState state,
        BlockPos pos
    ) {
        return verticalNeighborhood(runtime, blockView, state, pos);
    }

    private static VerticalVoxelNeighborhood verticalNeighborhood(
        RuntimeResources runtime,
        BlockAndTintGetter blockView,
        BlockState state,
        BlockPos pos
    ) {
        VerticalVoxelNeighborhood.Builder vertical = VerticalVoxelNeighborhood.builder();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    neighborPos.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    BlockState neighborState = blockView.getBlockState(neighborPos);
                    MicroBlockShape neighborShape = runtime.roundedShapes.get(neighborState);
                    if (neighborShape == null || !connectsForMeshing(state, neighborState, neighborShape)) {
                        continue;
                    }
                    int verticalLayerBits = neighborShape.verticalLayerBits();
                    if ((verticalLayerBits & 1) != 0) {
                        vertical.occupy(x, 2 * y, z);
                    }
                    if ((verticalLayerBits & 2) != 0) {
                        vertical.occupy(x, 2 * y + 1, z);
                    }
                }
            }
        }
        return vertical.build();
    }

    private MicroVoxelNeighborhood microNeighborhood(
        BlockAndTintGetter blockView,
        BlockState state,
        BlockPos pos
    ) {
        return microNeighborhood(runtime, blockView, state, pos);
    }

    private static MicroVoxelNeighborhood microNeighborhood(
        RuntimeResources runtime,
        BlockAndTintGetter blockView,
        BlockState state,
        BlockPos pos
    ) {
        MicroVoxelNeighborhood.Builder micro = MicroVoxelNeighborhood.builder();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    neighborPos.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    BlockState neighborState = blockView.getBlockState(neighborPos);
                    MicroBlockShape neighborShape = runtime.roundedShapes.get(neighborState);
                    if (neighborShape != null && connectsForMeshing(state, neighborState, neighborShape)) {
                        micro.occupyBlock(x, y, z, neighborShape);
                    }
                }
            }
        }
        return micro.build();
    }

    static boolean connectsForMeshing(
        BlockState centerState,
        BlockState neighborState,
        MicroBlockShape neighborShape
    ) {
        if (neighborShape.isPartial() || neighborState.canOcclude()) {
            return true;
        }
        return isLeaves(centerState) && isLeaves(neighborState);
    }

    private static boolean isLeaves(BlockState state) {
        return state.getBlock() instanceof LeavesBlock || state.is(BlockTags.LEAVES);
    }

    private MeshPlan cachedPlan(int neighborhoodBits) {
        return runtime.fullBlockPlans.get(neighborhoodBits, runtime.fullBlockPlanLoader);
    }

    private MeshPlan cachedPlan(VerticalVoxelNeighborhood neighborhood) {
        return runtime.slabPlans.get(neighborhood.bits(), runtime.slabPlanLoader);
    }

    private MeshPlan cachedPlan(MicroVoxelNeighborhood neighborhood) {
        return runtime.complexShapePlans.get(neighborhood, runtime.mesher::mesh);
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

    private void logOnce(String key, String message, Object... arguments) {
        if (runtime.diagnosticLogging && LOGGED_DIAGNOSTICS.add(key)) {
            LOGGER.info(message, arguments);
        }
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
            List<FaceAppearance> faceAppearances = appearances.get(primitive.materialFace());
            if (faceAppearances.size() == 1 || FaceAppearance.allShareCoverage(faceAppearances)) {
                for (FaceAppearance appearance : faceAppearances) {
                    emitPrimitiveAppearance(primitive, appearance, nominalFace, standard, emitter);
                }
                continue;
            }
            double sampleX = 0.0;
            double sampleY = 0.0;
            double sampleZ = 0.0;
            for (MeshVertex vertex : vertices) {
                sampleX += vertex.position().x();
                sampleY += vertex.position().y();
                sampleZ += vertex.position().z();
            }
            double inverseVertexCount = 1.0 / vertices.size();
            sampleX *= inverseVertexCount;
            sampleY *= inverseVertexCount;
            sampleZ *= inverseVertexCount;
            for (int appearanceIndex = 0; appearanceIndex < faceAppearances.size(); appearanceIndex++) {
                FaceAppearance appearance = faceAppearances.get(appearanceIndex);
                if (!FaceAppearance.isBestMatch(
                    faceAppearances, appearanceIndex, sampleX, sampleY, sampleZ
                )) {
                    continue;
                }
                emitPrimitiveAppearance(primitive, appearance, nominalFace, standard, emitter);
            }
        }
    }

    private static void emitPrimitiveAppearance(
        MeshPrimitive primitive,
        FaceAppearance appearance,
        Direction nominalFace,
        RenderMaterial standard,
        QuadEmitter emitter
    ) {
        List<MeshVertex> vertices = primitive.vertices();
        int lastVertex = vertices.size() - 1;
        for (int outputIndex = 0; outputIndex < 4; outputIndex++) {
            MeshVertex vertex = vertices.get(Math.min(outputIndex, lastVertex));
            long packedUv = appearance.packedUv(vertex.position());
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
            emitter.uv(
                outputIndex,
                Float.intBitsToFloat((int) (packedUv >>> 32)),
                Float.intBitsToFloat((int) packedUv)
            );
            emitter.color(outputIndex, 0xFFFFFFFF);
        }
        emitter.material(standard);
        emitter.colorIndex(appearance.tintIndex());
        emitter.nominalFace(nominalFace);
        emitter.cullFace(null);
        emitter.emit();
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

    private record FluidContactPlanKey(
        Object topology,
        CubeFace contactFace,
        int firstHeightBits,
        int secondHeightBits
    ) {
    }

    private static final class NeighborhoodScratch {
        private final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        private int blockBits;
        private int exposureBits;
        private boolean hasPartialShape;
        private boolean hasComplexShape;

        private void scan(
            RuntimeResources runtime,
            BlockAndTintGetter blockView,
            BlockState state,
            BlockPos pos
        ) {
            blockBits = 0;
            exposureBits = 0;
            hasPartialShape = false;
            hasComplexShape = false;
            int originX = pos.getX();
            int originY = pos.getY();
            int originZ = pos.getZ();
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        neighborPos.set(originX + x, originY + y, originZ + z);
                        BlockState neighborState = blockView.getBlockState(neighborPos);
                        Direction direction = directionForNeighborOffset(x, y, z);
                        if (direction != null
                            && Block.shouldRenderFace(state, blockView, pos, direction, neighborPos)) {
                            exposureBits |= 1 << FaceAppearance.toCubeFace(direction).ordinal();
                        }
                        MicroBlockShape neighborShape = runtime.roundedShapes.get(neighborState);
                        if (neighborShape != null && connectsForMeshing(state, neighborState, neighborShape)) {
                            blockBits |= 1 << ((x + 1) + 3 * (y + 1) + 9 * (z + 1));
                            hasPartialShape |= neighborShape.isPartial();
                            hasComplexShape |= neighborShape.isComplex();
                        }
                    }
                }
            }
        }
    }

    private static final class RuntimeResources {
        private final RoundingBlockConfig config;
        private final Map<BlockState, MicroBlockShape> roundedShapes = new ConcurrentHashMap<>();
        private final RoundedVoxelMesher mesher;
        private final BoundedLongLoadingCache<MeshPlan> fullBlockPlans;
        private final BoundedLongLoadingCache<MeshPlan> slabPlans;
        private final BoundedLoadingCache<MicroVoxelNeighborhood, MeshPlan> complexShapePlans;
        private final FluidContactPatchMesher fluidContactMesher;
        private final BoundedLoadingCache<FluidContactPlanKey, MeshPlan> fluidContactPlans;
        private final LongFunction<MeshPlan> fullBlockPlanLoader;
        private final LongFunction<MeshPlan> slabPlanLoader;
        private final int appearanceVariantCacheLimit;
        private final boolean diagnosticLogging;

        private RuntimeResources(RoundingBlockConfig config) {
            this(
                config,
                config.enabled() ? new RoundedVoxelMesher(config.quality().radius(), config.quality().segments()) : null,
                config.enabled() ? new BoundedLongLoadingCache<>(config.cache().fullBlockPlans()) : null,
                config.enabled() ? new BoundedLongLoadingCache<>(config.cache().slabPlans()) : null,
                config.enabled() ? new BoundedLoadingCache<>(config.cache().complexShapePlans()) : null,
                config.enabled() ? new FluidContactPatchMesher() : null,
                config.enabled() ? new BoundedLoadingCache<>(config.cache().fluidContactPlans()) : null
            );
        }

        private RuntimeResources(
            RoundingBlockConfig config,
            RoundedVoxelMesher mesher,
            BoundedLongLoadingCache<MeshPlan> fullBlockPlans,
            BoundedLongLoadingCache<MeshPlan> slabPlans,
            BoundedLoadingCache<MicroVoxelNeighborhood, MeshPlan> complexShapePlans,
            FluidContactPatchMesher fluidContactMesher,
            BoundedLoadingCache<FluidContactPlanKey, MeshPlan> fluidContactPlans
        ) {
            this.config = config;
            this.mesher = mesher;
            this.fullBlockPlans = fullBlockPlans;
            this.slabPlans = slabPlans;
            this.complexShapePlans = complexShapePlans;
            this.fluidContactMesher = fluidContactMesher;
            this.fluidContactPlans = fluidContactPlans;
            this.fullBlockPlanLoader = bits -> {
                VoxelNeighborhood neighborhood = new VoxelNeighborhood((int) bits);
                return mesher.mesh(neighborhood).withoutPlanarFaces(neighborhood.planarFaceBits());
            };
            this.slabPlanLoader = bits -> mesher.mesh(new VerticalVoxelNeighborhood(bits));
            this.appearanceVariantCacheLimit = config.cache().weightedModelVariants();
            this.diagnosticLogging = config.debug().diagnosticLogging();
        }

        private RuntimeResources freshBakeGeneration() {
            return new RuntimeResources(
                config,
                mesher,
                fullBlockPlans,
                slabPlans,
                complexShapePlans,
                fluidContactMesher,
                fluidContactPlans
            );
        }
    }

    /** Opaque prepared state used to swap render generations without rebuilding on the client thread. */
    public static final class PreparedRuntime {
        private final RuntimeResources runtime;

        private PreparedRuntime(RuntimeResources runtime) {
            this.runtime = runtime;
        }
    }

}
