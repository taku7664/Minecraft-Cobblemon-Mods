package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.api.spawning.BestSpawner;
import jbro.cobblemon.policy.legend.LegendSpawnBucket;
import jbro.cobblemon.policy.pokemon.SpawnBucketOrder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every load or reload of the spawner config gets the legend bucket and a stable bucket order, which the Pokenav's
 * rarity list follows.
 */
@Mixin(value = BestSpawner.class, remap = false)
abstract class BestSpawnerBucketOrderMixin {
    @Inject(method = "loadConfig", at = @At("RETURN"))
    private void jbroPolicy$orderBuckets(CallbackInfo ci) {
        LegendSpawnBucket.INSTANCE.apply(BestSpawner.INSTANCE.getConfig());
        SpawnBucketOrder.INSTANCE.apply(BestSpawner.INSTANCE.getConfig());
    }
}
