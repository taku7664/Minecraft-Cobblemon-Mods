/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.entity.Entity
 *  net.minecraft.util.math.Box
 *  net.minecraft.util.math.Vec3d
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.ReflectionUtil;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class BattleTargetResolver {
    public Targets resolve(MinecraftClient client) {
        return this.resolve(client, BattleCamClient.STATE.activeBattleId);
    }

    public Targets resolve(MinecraftClient client, String battleId) {
        if (client.world == null || client.player == null) {
            return Targets.invalid();
        }
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        Box box = new Box(px - 64.0, py - 24.0, pz - 64.0, px + 64.0, py + 24.0, pz + 64.0);
        List<Entity> nearby = client.world.getOtherEntities(null, box, entity -> entity != null && !entity.isRemoved() && BattleTargetResolver.isCobblemonPokemon(entity) && BattleTargetResolver.isBattling(entity) && ReflectionUtil.entityMatchesBattle(entity, battleId));
        nearby.sort(Comparator.comparingDouble(entity -> client.player.squaredDistanceTo(entity)));
        if (nearby.size() == 1) {
            return this.resolveSingleRemaining((Entity)nearby.get(0), client.player.getPos());
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
        Vec3d left = BattleTargetResolver.anchor(a);
        Vec3d right = BattleTargetResolver.anchor(b);
        Vec3d center = left.add(right).multiply(0.5);
        Vec3d delta = right.subtract(left);
        Vec3d flat = new Vec3d(delta.x, 0.0, delta.z);
        if (flat.lengthSquared() < 1.0E-4) {
            return Targets.invalid();
        }
        Vec3d forward = flat.normalize();
        Vec3d perp = new Vec3d(-forward.z, 0.0, forward.x);
        return new Targets(left, right, center, forward, perp, left, left, right, right, left, right, false, false, true);
    }

    private Targets resolveDoubles(List<Entity> entities) {
        ArrayList<Vec3d> points = new ArrayList<Vec3d>();
        for (Entity entity : entities) {
            points.add(BattleTargetResolver.anchor(entity));
        }
        Vec3d center = BattleTargetResolver.average(points);
        int bestI = 0;
        int bestJ = 1;
        double bestDist = -1.0;
        for (int i = 0; i < points.size(); ++i) {
            for (int j = i + 1; j < points.size(); ++j) {
                double d = ((Vec3d)points.get(i)).squaredDistanceTo((Vec3d)points.get(j));
                if (!(d > bestDist)) continue;
                bestDist = d;
                bestI = i;
                bestJ = j;
            }
        }
        Vec3d axisRaw = ((Vec3d)points.get(bestJ)).subtract((Vec3d)points.get(bestI));
        Vec3d axisFlat = new Vec3d(axisRaw.x, 0.0, axisRaw.z);
        if (axisFlat.lengthSquared() < 1.0E-4) {
            return Targets.invalid();
        }
        Vec3d forward = axisFlat.normalize();
        Vec3d perp = new Vec3d(-forward.z, 0.0, forward.x);
        points.sort(Comparator.comparingDouble(point -> BattleTargetResolver.projection(point.subtract(center), forward)));
        Vec3d teamA1 = (Vec3d)points.get(0);
        Vec3d teamA2 = (Vec3d)points.get(1);
        Vec3d teamB1 = (Vec3d)points.get(2);
        Vec3d teamB2 = (Vec3d)points.get(3);
        Vec3d teamACenter = teamA1.add(teamA2).multiply(0.5);
        Vec3d teamBCenter = teamB1.add(teamB2).multiply(0.5);
        Vec3d overallCenter = teamACenter.add(teamBCenter).multiply(0.5);
        Vec3d fightAxis = teamBCenter.subtract(teamACenter);
        Vec3d fightAxisFlat = new Vec3d(fightAxis.x, 0.0, fightAxis.z);
        if (fightAxisFlat.lengthSquared() < 1.0E-4) {
            return Targets.invalid();
        }
        Vec3d correctedForward = fightAxisFlat.normalize();
        Vec3d correctedPerp = new Vec3d(-correctedForward.z, 0.0, correctedForward.x);
        return new Targets(teamACenter, teamBCenter, overallCenter, correctedForward, correctedPerp, teamA1, teamA2, teamB1, teamB2, teamACenter, teamBCenter, true, false, true);
    }

    private Targets resolveSingleRemaining(Entity entity, Vec3d playerPos) {
        Vec3d focus = BattleTargetResolver.anchor(entity);
        Vec3d playerToFocus = focus.subtract(playerPos);
        Vec3d flat = new Vec3d(playerToFocus.x, 0.0, playerToFocus.z);
        Vec3d forward = flat.lengthSquared() > 1.0E-4 ? flat.normalize() : new Vec3d(1.0, 0.0, 0.0);
        Vec3d perp = new Vec3d(-forward.z, 0.0, forward.x);
        return new Targets(focus, focus, focus, forward, perp, focus, focus, focus, focus, focus, focus, false, true, true);
    }

    private static double projection(Vec3d a, Vec3d ontoUnit) {
        return a.x * ontoUnit.x + a.y * ontoUnit.y + a.z * ontoUnit.z;
    }

    private static Vec3d average(List<Vec3d> points) {
        Vec3d sum = Vec3d.ZERO;
        for (Vec3d point : points) {
            sum = sum.add(point);
        }
        return sum.multiply(1.0 / (double)points.size());
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

    private static Vec3d anchor(Entity entity) {
        return entity.getPos().add(0.0, (double)entity.getHeight() * 0.5, 0.0);
    }

    public record Targets(Vec3d left, Vec3d right, Vec3d center, Vec3d forward, Vec3d perp, Vec3d teamA1, Vec3d teamA2, Vec3d teamB1, Vec3d teamB2, Vec3d teamACenter, Vec3d teamBCenter, boolean doubles, boolean singleRemaining, boolean valid) {
        public static Targets invalid() {
            return new Targets(Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, false, false, false);
        }
    }
}
