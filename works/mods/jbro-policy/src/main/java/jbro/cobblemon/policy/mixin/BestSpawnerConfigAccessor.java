package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.api.spawning.preset.BestSpawnerConfig;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets SpawnBucketOrder swap the loaded bucket maps for ordered copies. */
@Mixin(value = BestSpawnerConfig.class, remap = false)
public interface BestSpawnerConfigAccessor {
    @Mutable
    @Accessor("worldBuckets")
    void jbroPolicy$setWorldBuckets(Map<String, Float> buckets);

    @Mutable
    @Accessor("fishingBuckets")
    void jbroPolicy$setFishingBuckets(Map<String, Float> buckets);
}
