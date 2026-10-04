package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.batmite2b.battlecam.client.BattleCameraRig;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

final class BattleCameraRigParityTest {
    @Test
    void retainsTheOriginalBattleCamInterpolationRates() {
        var rig = new BattleCameraRig();
        rig.setDesired(Vec3d.ZERO, 0.0f, 0.0f, 70.0f);
        rig.setDesired(new Vec3d(10.0, 5.0, -10.0), 90.0f, -30.0f, 50.0f);

        rig.renderStep();

        assertEquals(new Vec3d(1.2, 0.6, -1.2), rig.getRenderPos());
        assertEquals(14.4f, rig.getRenderYaw(), 0.0001f);
        assertEquals(-4.8f, rig.getRenderPitch(), 0.0001f);
        assertEquals(67.6f, rig.getRenderFov(), 0.0001f);
    }
}
