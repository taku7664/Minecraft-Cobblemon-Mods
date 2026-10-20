package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.batmite2b.battlecam.client.BattleCameraDirector;
import java.lang.reflect.Method;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** A shot from straight above keeps one heading from its start to its end, the one it has in its middle. */
final class TopDownHeadingTest {
    private static float yaw(Object pose) throws Exception {
        Method yaw = pose.getClass().getDeclaredMethod("yaw");
        yaw.setAccessible(true);
        return (Float) yaw.invoke(pose);
    }

    @Test
    void headingDoesNotSwingAsTheDriftShrinks() throws Exception {
        var director = new BattleCameraDirector();
        Method topDown = BattleCameraDirector.class.getDeclaredMethod("buildTopDownPose", Vec3.class, Vec3.class, float.class, Vec3.class);
        Method plain = BattleCameraDirector.class.getDeclaredMethod("buildPose", Vec3.class, Vec3.class, float.class);
        topDown.setAccessible(true);
        plain.setAccessible(true);
        Vec3 center = new Vec3(10.0, 64.0, -4.0);
        Vec3 side = new Vec3(0.6, 0.0, 0.8);
        Vec3 lookAt = center.add(0.0, 0.12, 0.0);
        // The middle of the shot, a full drift aside: the heading the shot is built around.
        Vec3 middle = center.add(side.scale(1.0)).add(0.0, 9.0, 0.01);
        float designed = yaw(plain.invoke(director, middle, lookAt, 70.0f));
        for (double drift : new double[]{0.0, 0.004, 0.01, 0.05, 0.5, 1.0}) {
            Vec3 camera = center.add(side.scale(drift)).add(0.0, 9.0, 0.01);
            assertEquals(designed, yaw(topDown.invoke(director, camera, lookAt, 70.0f, side.scale(-1.0))), 0.5f);
        }
    }
}
