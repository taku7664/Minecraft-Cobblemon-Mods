package jbro.cobblemon.betterbattlepresentation.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class DynamaxCloudTintTest {
    @Test
    void inactiveEffectKeepsVanillaCloudColor() {
        Vec3 original = new Vec3(0.92, 0.86, 0.78);

        Vec3 tinted = DynamaxCloudTint.mix(original, 0.0F);

        assertColor(original, tinted);
    }

    @Test
    void fullEffectMakesCloudsNearlyBlackBurgundy() {
        Vec3 tinted = DynamaxCloudTint.mix(new Vec3(1.0, 1.0, 1.0), 1.0F);

        assertColor(new Vec3(0.075, 0.006, 0.018), tinted);
    }

    @Test
    void effectStrengthIsClampedBeforeMixing() {
        Vec3 original = new Vec3(0.6, 0.7, 0.8);

        assertColor(original, DynamaxCloudTint.mix(original, -2.0F));
        assertColor(new Vec3(0.075, 0.006, 0.018), DynamaxCloudTint.mix(original, 3.0F));
    }

    private void assertColor(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 0.000_001);
        assertEquals(expected.y, actual.y, 0.000_001);
        assertEquals(expected.z, actual.z, 0.000_001);
    }
}
