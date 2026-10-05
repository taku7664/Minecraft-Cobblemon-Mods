package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.ReflectionUtil;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class BattleTargetResolver {
    public Targets resolve(Minecraft client) {
        return this.resolve(client, BattleCamClient.STATE.activeBattleId);
    }

    public Targets resolve(Minecraft client, String battleId) {
        if (client.level == null || client.player == null) {
            return Targets.invalid();
        }
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        AABB box = new AABB(px - 64.0, py - 24.0, pz - 64.0, px + 64.0, py + 24.0, pz + 64.0);
        List<Entity> nearby = client.level.getEntities((Entity)null, box, entity -> entity != null && !entity.isRemoved() && BattleTargetResolver.isCobblemonPokemon(entity) && BattleTargetResolver.isBattling(entity) && ReflectionUtil.entityMatchesBattle(entity, battleId));
        nearby.sort(Comparator.comparingDouble(entity -> client.player.distanceToSqr(entity)));
        if (nearby.size() == 1) {
            return this.resolveSingleRemaining((Entity)nearby.get(0), client.player.position());
        }
        if (nearby.size() < 2) {
            return Targets.invalid();
        }
        if (nearby.size() >= 4) {
            return this.resolveDoubles(nearby.subList(0, 4));
        }
        return this.resolveSingles((Entity)nearby.get(0), (Entity)nearby.get(1));
    }

    private Targets resolveSingles(Entity a, Entity b) {
        Vec3 left = BattleTargetResolver.anchor(a);
        Vec3 right = BattleTargetResolver.anchor(b);
        Vec3 center = left.add(right).scale(0.5);
        Vec3 delta = right.subtract(left);
        Vec3 flat = new Vec3(delta.x, 0.0, delta.z);
        if (flat.lengthSqr() < 1.0E-4) {
            return Targets.invalid();
        }
        Vec3 forward = flat.normalize();
        Vec3 perp = new Vec3(-forward.z, 0.0, forward.x);
        return new Targets(left, right, center, forward, perp, left, left, right, right, left, right, false, false, true);
    }

    private Targets resolveDoubles(List<Entity> entities) {
        ArrayList<Vec3> points = new ArrayList<Vec3>();
        for (Entity entity : entities) {
            points.add(BattleTargetResolver.anchor(entity));
        }
        Vec3 center = BattleTargetResolver.average(points);
        int bestI = 0;
        int bestJ = 1;
        double bestDist = -1.0;
        for (int i = 0; i < points.size(); ++i) {
            for (int j = i + 1; j < points.size(); ++j) {
                double d = ((Vec3)points.get(i)).distanceToSqr((Vec3)points.get(j));
                if (!(d > bestDist)) continue;
                bestDist = d;
                bestI = i;
                bestJ = j;
            }
        }
        Vec3 axisRaw = ((Vec3)points.get(bestJ)).subtract((Vec3)points.get(bestI));
        Vec3 axisFlat = new Vec3(axisRaw.x, 0.0, axisRaw.z);
        if (axisFlat.lengthSqr() < 1.0E-4) {
            return Targets.invalid();
        }
        Vec3 forward = axisFlat.normalize();
        Vec3 perp = new Vec3(-forward.z, 0.0, forward.x);
        points.sort(Comparator.comparingDouble(point -> BattleTargetResolver.projection(point.subtract(center), forward)));
        Vec3 teamA1 = (Vec3)points.get(0);
        Vec3 teamA2 = (Vec3)points.get(1);
        Vec3 teamB1 = (Vec3)points.get(2);
        Vec3 teamB2 = (Vec3)points.get(3);
        Vec3 teamACenter = teamA1.add(teamA2).scale(0.5);
        Vec3 teamBCenter = teamB1.add(teamB2).scale(0.5);
        Vec3 overallCenter = teamACenter.add(teamBCenter).scale(0.5);
        Vec3 fightAxis = teamBCenter.subtract(teamACenter);
        Vec3 fightAxisFlat = new Vec3(fightAxis.x, 0.0, fightAxis.z);
        if (fightAxisFlat.lengthSqr() < 1.0E-4) {
            return Targets.invalid();
        }
        Vec3 correctedForward = fightAxisFlat.normalize();
        Vec3 correctedPerp = new Vec3(-correctedForward.z, 0.0, correctedForward.x);
        return new Targets(teamACenter, teamBCenter, overallCenter, correctedForward, correctedPerp, teamA1, teamA2, teamB1, teamB2, teamACenter, teamBCenter, true, false, true);
    }

    private Targets resolveSingleRemaining(Entity entity, Vec3 playerPos) {
        Vec3 focus = BattleTargetResolver.anchor(entity);
        Vec3 playerToFocus = focus.subtract(playerPos);
        Vec3 flat = new Vec3(playerToFocus.x, 0.0, playerToFocus.z);
        Vec3 forward = flat.lengthSqr() > 1.0E-4 ? flat.normalize() : new Vec3(1.0, 0.0, 0.0);
        Vec3 perp = new Vec3(-forward.z, 0.0, forward.x);
        return new Targets(focus, focus, focus, forward, perp, focus, focus, focus, focus, focus, focus, false, true, true);
    }

    private static double projection(Vec3 a, Vec3 ontoUnit) {
        return a.x * ontoUnit.x + a.y * ontoUnit.y + a.z * ontoUnit.z;
    }

    private static Vec3 average(List<Vec3> points) {
        Vec3 sum = Vec3.ZERO;
        for (Vec3 point : points) {
            sum = sum.add(point);
        }
        return sum.scale(1.0 / (double)points.size());
    }

    private static boolean isCobblemonPokemon(Entity entity) {
        return entity.getClass().getName().equals("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
    }

    private static boolean isBattling(Entity entity) {
        Object result;
        Method method2;
        try {
            method2 = entity.getClass().getMethod("isBattling", new Class[0]);
            result = method2.invoke((Object)entity, new Object[0]);
            if (result instanceof Boolean) {
                Boolean b = (Boolean)result;
                return b;
            }
        }
        catch (Exception ignored) {
            // empty catch block
        }
        try {
            method2 = entity.getClass().getMethod("getBattleId", new Class[0]);
            result = method2.invoke((Object)entity, new Object[0]);
            return result != null;
        }
        catch (Exception exception) {
            return false;
        }
    }

    private static Vec3 anchor(Entity entity) {
        return entity.position().add(0.0, (double)entity.getBbHeight() * 0.5, 0.0);
    }

    public record Targets(Vec3 left, Vec3 right, Vec3 center, Vec3 forward, Vec3 perp, Vec3 teamA1, Vec3 teamA2, Vec3 teamB1, Vec3 teamB2, Vec3 teamACenter, Vec3 teamBCenter, boolean doubles, boolean singleRemaining, boolean valid) {
        public static Targets invalid() {
            return new Targets(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, false, false, false);
        }
    }
}
