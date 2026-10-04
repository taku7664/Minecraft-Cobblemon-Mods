/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.client.network.AbstractClientPlayerEntity
 *  net.minecraft.entity.Entity
 *  net.minecraft.util.math.Box
 *  net.minecraft.util.math.Vec3d
 */
package com.batmite2b.battlecam.client;

import java.util.Comparator;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class TrainerTargetResolver {
    public Targets resolve(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return Targets.invalid();
        }
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        Box box = new Box(px - 64.0, py - 24.0, pz - 64.0, px + 64.0, py + 24.0, pz + 64.0);
        List<AbstractClientPlayerEntity> players = client.world.getEntitiesByClass(AbstractClientPlayerEntity.class, box, entity -> entity != null && !entity.isRemoved() && entity != client.player);
        players.sort(Comparator.comparingDouble(client.player::squaredDistanceTo));
        if (players.isEmpty()) {
            return Targets.invalid();
        }
        if (players.size() == 1) {
            Vec3d p = TrainerTargetResolver.anchor(players.get(0));
            return new Targets(p, p, p, Vec3d.ZERO, Vec3d.ZERO, false, true);
        }
        AbstractClientPlayerEntity a = (AbstractClientPlayerEntity)players.get(0);
        AbstractClientPlayerEntity b = (AbstractClientPlayerEntity)players.get(1);
        Vec3d left = TrainerTargetResolver.anchor(a);
        Vec3d right = TrainerTargetResolver.anchor(b);
        Vec3d center = left.add(right).multiply(0.5);
        Vec3d delta = right.subtract(left);
        Vec3d flat = new Vec3d(delta.x, 0.0, delta.z);
        if (flat.lengthSquared() < 1.0E-4) {
            return new Targets(left, right, center, Vec3d.ZERO, Vec3d.ZERO, true, true);
        }
        Vec3d forward = flat.normalize();
        Vec3d perp = new Vec3d(-forward.z, 0.0, forward.x);
        return new Targets(left, right, center, forward, perp, true, true);
    }

    private static Vec3d anchor(Entity entity) {
        return entity.getPos().add(0.0, (double)entity.getHeight() * 0.85, 0.0);
    }

    public record Targets(Vec3d left, Vec3d right, Vec3d center, Vec3d forward, Vec3d perp, boolean duel, boolean valid) {
        public static Targets invalid() {
            return new Targets(Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, false, false);
        }
    }
}
