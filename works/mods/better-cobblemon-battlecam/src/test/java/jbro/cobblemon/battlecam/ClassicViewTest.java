package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.batmite2b.battlecam.client.BattleCameraDirector;
import com.batmite2b.battlecam.client.CameraPose;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The classic view lays the picture out as Brilliant Diamond/Shining Pearl's move selection does, whatever the field. */
final class ClassicViewTest {
    private static final double ASPECT = 16.0 / 9.0;

    /** Where [point] lands on screen, as fractions of the half-screen from the middle: x right, y up; then its depth. */
    private static double[] screen(CameraPose pose, Vec3 point) {
        Vec3 forward = BattleCameraDirector.lookVector(pose.yaw(), pose.pitch());
        Vec3 right = forward.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
        Vec3 up = right.cross(forward);
        Vec3 d = point.subtract(pose.pos());
        double depth = d.dot(forward);
        double tanHalf = Math.tan(Math.toRadians(pose.fov() / 2.0));
        return new double[]{d.dot(right) / depth / (tanHalf * ASPECT), d.dot(up) / depth / tanHalf, depth};
    }

    @Test
    void ownPokemonLeftAndBelowOpponentClearToTheRight() {
        Vec3 mine = new Vec3(0.0, 65.0, 0.0);
        for (Vec3 foe : new Vec3[]{new Vec3(4.0, 65.0, 0.0), new Vec3(0.0, 64.6, -7.0), new Vec3(-5.0, 65.3, 5.0)}) {
            for (double height : new double[]{0.6, 1.2, 2.0}) {
                for (boolean doubles : new boolean[]{false, true}) {
                    CameraPose pose = BattleCameraDirector.classicPose(mine, foe, doubles, height);
                    double[] own = screen(pose, mine);
                    double[] opponent = screen(pose, foe);
                    assertTrue(own[2] > 0.0 && opponent[2] > own[2], "both ahead, the player's own nearer");
                    assertTrue(own[0] < 0.0, "own Pokemon left of the middle");
                    assertTrue(opponent[0] > own[0] && opponent[0] < 1.0, "opponent right of the own Pokemon, on screen");
                    if (foe.y >= mine.y) assertTrue(own[1] < opponent[1], "own Pokemon below the opponent");
                }
            }
        }
    }
}
