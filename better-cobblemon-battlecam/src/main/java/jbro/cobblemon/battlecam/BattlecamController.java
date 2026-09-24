package jbro.cobblemon.battlecam;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattleSide;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class BattlecamController {
    private static final int AUTO_SHOT_TICKS = 160;
    private static final double SMOOTHING = 0.14;
    private static BattlecamMode mode = BattlecamMode.AUTO;
    private static List<BattlecamShot> shots = List.of();
    private static int shotIndex;
    private static long lastShotTick;
    private static boolean active;
    private static Vec3d currentPosition;
    private static Vec3d currentTarget;
    private static UUID activeBattleId;
    private static BattlecamBattleType activeBattleType;

    private BattlecamController() {
    }

    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            endBattle();
            return;
        }

        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) {
            endBattle();
            return;
        }

        BattlecamBattleType battleType = BattlecamBattleType.classify(battle);
        if (!battle.getBattleId().equals(activeBattleId) || battleType != activeBattleType) {
            activeBattleId = battle.getBattleId();
            activeBattleType = battleType;
            mode = BattlecamConfigStore.current().defaultMode(battleType);
            shotIndex = 0;
            lastShotTick = client.world.getTime();
            currentPosition = null;
            currentTarget = null;
        }

        Map<UUID, PokemonEntity> entities = findBattlePokemon(client, battle);
        List<BattlecamPoint> side1 = pointsFor(battle.getSide1(), entities);
        List<BattlecamPoint> side2 = pointsFor(battle.getSide2(), entities);
        shots = BattlecamShotPlanner.plan(side1, side2);
        if (!shots.isEmpty()) {
            shotIndex = Math.floorMod(shotIndex, shots.size());
        }

        active = BattlecamActivationPolicy.shouldActivate(
            BattlecamConfigStore.current().enabled(battleType),
            !shots.isEmpty(),
            mode
        );

        if (!active) {
            currentPosition = null;
            currentTarget = null;
            return;
        }

        long tick = client.world.getTime();
        if (mode == BattlecamMode.AUTO && tick - lastShotTick >= AUTO_SHOT_TICKS) {
            shotIndex = (shotIndex + 1) % shots.size();
            lastShotTick = tick;
        }
    }

    public static void cycleMode(MinecraftClient client) {
        mode = mode.next();
        lastShotTick = client.world == null ? 0L : client.world.getTime();
        client.inGameHud.setOverlayMessage(
            net.minecraft.text.Text.translatable("message.better_cobblemon_battlecam.mode", mode.name()),
            false
        );
    }

    public static void nextShot(MinecraftClient client) {
        if (mode == BattlecamMode.MANUAL && !shots.isEmpty()) {
            shotIndex = (shotIndex + 1) % shots.size();
            lastShotTick = client.world == null ? 0L : client.world.getTime();
        }
    }

    public static void previousShot(MinecraftClient client) {
        if (mode == BattlecamMode.MANUAL && !shots.isEmpty()) {
            shotIndex = Math.floorMod(shotIndex - 1, shots.size());
            lastShotTick = client.world == null ? 0L : client.world.getTime();
        }
    }

    public static Optional<CameraPose> sampleCamera(MinecraftClient client) {
        if (!active || shots.isEmpty() || client.world == null || client.player == null) {
            return Optional.empty();
        }

        BattlecamShot shot = shots.get(shotIndex);
        Vec3d desiredTarget = toVec(shot.target());
        Vec3d desiredPosition = clipCamera(client, desiredTarget, toVec(shot.camera()));
        if (currentPosition == null || currentTarget == null) {
            currentPosition = desiredPosition;
            currentTarget = desiredTarget;
        } else {
            currentPosition = currentPosition.lerp(desiredPosition, SMOOTHING);
            currentTarget = currentTarget.lerp(desiredTarget, SMOOTHING);
        }

        Vec3d direction = currentTarget.subtract(currentPosition);
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));
        return Optional.of(new CameraPose(currentPosition, yaw, pitch));
    }

    public static boolean isActive() {
        return active;
    }

    public static BattlecamMode mode() {
        return mode;
    }

    public static int shotNumber() {
        return shots.isEmpty() ? 0 : shotIndex + 1;
    }

    public static int shotCount() {
        return shots.size();
    }

    public static String shotName() {
        return shots.isEmpty() ? "-" : shots.get(shotIndex).name();
    }

    public static void applyConfig(BattlecamConfig config) {
        if (activeBattleType != null) {
            mode = config.defaultMode(activeBattleType);
        }
        active = false;
        currentPosition = null;
        currentTarget = null;
    }

    private static void endBattle() {
        active = false;
        shots = List.of();
        currentPosition = null;
        currentTarget = null;
        activeBattleId = null;
        activeBattleType = null;
    }

    private static Map<UUID, PokemonEntity> findBattlePokemon(MinecraftClient client, ClientBattle battle) {
        Set<UUID> expected = new HashSet<>();
        collectActiveIds(battle.getSide1(), expected);
        collectActiveIds(battle.getSide2(), expected);
        Map<UUID, PokemonEntity> result = new HashMap<>();
        if (expected.isEmpty()) {
            return result;
        }

        List<PokemonEntity> nearby = client.world.getEntitiesByClass(
            PokemonEntity.class,
            client.player.getBoundingBox().expand(128.0),
            entity -> battle.getBattleId().equals(entity.getBattleId())
        );
        for (PokemonEntity entity : nearby) {
            UUID id = entity.getPokemon().getUuid();
            if (expected.contains(id)) {
                result.put(id, entity);
            }
        }
        return result;
    }

    private static void collectActiveIds(ClientBattleSide side, Set<UUID> output) {
        for (ActiveClientBattlePokemon activePokemon : side.getActiveClientBattlePokemon()) {
            if (activePokemon.getBattlePokemon() != null) {
                output.add(activePokemon.getBattlePokemon().getUuid());
            }
        }
    }

    private static List<BattlecamPoint> pointsFor(ClientBattleSide side, Map<UUID, PokemonEntity> entities) {
        List<BattlecamPoint> points = new ArrayList<>();
        for (ActiveClientBattlePokemon activePokemon : side.getActiveClientBattlePokemon()) {
            if (activePokemon.getBattlePokemon() == null) {
                continue;
            }
            PokemonEntity entity = entities.get(activePokemon.getBattlePokemon().getUuid());
            if (entity != null) {
                points.add(new BattlecamPoint(
                    entity.getX(),
                    entity.getY() + Math.max(0.8, entity.getHeight() * 0.55),
                    entity.getZ()
                ));
            }
        }
        return points;
    }

    private static Vec3d clipCamera(MinecraftClient client, Vec3d target, Vec3d desired) {
        HitResult hit = client.world.raycast(new RaycastContext(
            target,
            desired,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        if (hit.getType() == HitResult.Type.MISS) {
            return desired;
        }
        Vec3d hitPosition = hit.getPos();
        Vec3d towardTarget = target.subtract(hitPosition).normalize().multiply(0.35);
        return hitPosition.add(towardTarget);
    }

    private static Vec3d toVec(BattlecamPoint point) {
        return new Vec3d(point.x(), point.y(), point.z());
    }
}
