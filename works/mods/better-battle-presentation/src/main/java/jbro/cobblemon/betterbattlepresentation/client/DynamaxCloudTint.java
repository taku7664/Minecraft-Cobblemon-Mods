package jbro.cobblemon.betterbattlepresentation.client;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

public final class DynamaxCloudTint {
    private static final Vec3 FULL_EFFECT_COLOR = new Vec3(0.075, 0.006, 0.018);

    private DynamaxCloudTint() {
    }

    public static Vec3 apply(Vec3 original) {
        return mix(original, DynamaxAtmosphereClientState.strength());
    }

    static Vec3 mix(Vec3 original, float strength) {
        Objects.requireNonNull(original, "original");
        double amount = Math.clamp(strength, 0.0F, 1.0F);
        return new Vec3(
            original.x + (FULL_EFFECT_COLOR.x - original.x) * amount,
            original.y + (FULL_EFFECT_COLOR.y - original.y) * amount,
            original.z + (FULL_EFFECT_COLOR.z - original.z) * amount
        );
    }
}
