package jbro.cobblemon.policy.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reject newly added vanilla animals without discarding Cobblemon Pokemon, which also inherit Animal. */
@Mixin(ServerLevel.class)
abstract class ServerLevelVanillaAnimalMixin {
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$rejectVanillaAnimal(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!((ServerLevel) (Object) this).getServer().isDedicatedServer()) return;
        if ("minecraft".equals(EntityType.getKey(entity.getType()).getNamespace())
            && (entity instanceof Animal || entity instanceof WaterAnimal)) {
            cir.setReturnValue(false);
        }
    }
}
