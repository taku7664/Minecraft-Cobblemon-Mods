package jbro.cobblemon.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * What the battle camera is looking at, for a shader pack that thins out the blocks in between (LumaVale). Two
 * targets (each Pokemon, each team, or the speaker twice) and a strength that eases in while the camera is held and
 * out when it lets go. Exposed to Iris as uniforms by {@code IrisSeeThroughUniformsMixin}; packs that do not read
 * them are unaffected.
 */
public final class BattlecamSeeThrough {
    private static final long FADE_NANOS = 400_000_000L;
    private static Vec3 targetA = Vec3.ZERO;
    private static Vec3 targetB = Vec3.ZERO;
    private static float strength = 0f;
    private static long lastNanos = 0L;

    private BattlecamSeeThrough() {
    }

    /** Called by the director as it frames a shot: the points whose view should stay clear. */
    public static void look(Vec3 a, Vec3 b) {
        targetA = a;
        targetB = b;
    }

    /** 0 to 1, eased toward whether the battle camera is in control; read once a frame. */
    public static float strength() {
        long now = System.nanoTime();
        float step = lastNanos == 0L ? 0f : (float) (now - lastNanos) / FADE_NANOS;
        lastNanos = now;
        float target = BattleCamClient.STATE.shouldOverrideCamera() ? 1f : 0f;
        strength = strength < target ? Math.min(target, strength + step) : Math.max(target, strength - step);
        return strength;
    }

    public static Vector3f relativeA() {
        return relative(targetA);
    }

    public static Vector3f relativeB() {
        return relative(targetB);
    }

    /** Relative to the rendering camera, the space a shader's player position is in. */
    private static Vector3f relative(Vec3 target) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return new Vector3f((float) (target.x - camera.x), (float) (target.y - camera.y), (float) (target.z - camera.z));
    }
}
