package com.batmite2b.battlecam.client;

import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class TrainerTargetResolver {
    public Targets resolve(Minecraft client) {
        if (client.level == null || client.player == null) {
            return Targets.invalid();
        }
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        AABB box = new AABB(px - 64.0, py - 24.0, pz - 64.0, px + 64.0, py + 24.0, pz + 64.0);
        List<AbstractClientPlayer> players = client.level.getEntitiesOfClass(AbstractClientPlayer.class, box, entity -> entity != null && !entity.isRemoved() && entity != client.player);
        players.sort(Comparator.comparingDouble(client.player::distanceToSqr));
        if (players.isEmpty()) {
            return Targets.invalid();
        }
        if (players.size() == 1) {
            Vec3 p = TrainerTargetResolver.anchor(players.get(0));
            return new Targets(p, p, p, Vec3.ZERO, Vec3.ZERO, false, true);
        }
        AbstractClientPlayer a = (AbstractClientPlayer)players.get(0);
        AbstractClientPlayer b = (AbstractClientPlayer)players.get(1);
        Vec3 left = TrainerTargetResolver.anchor(a);
        Vec3 right = TrainerTargetResolver.anchor(b);
        Vec3 center = left.add(right).scale(0.5);
        Vec3 delta = right.subtract(left);
        Vec3 flat = new Vec3(delta.x, 0.0, delta.z);
        if (flat.lengthSqr() < 1.0E-4) {
            return new Targets(left, right, center, Vec3.ZERO, Vec3.ZERO, true, true);
        }
        Vec3 forward = flat.normalize();
        Vec3 perp = new Vec3(-forward.z, 0.0, forward.x);
        return new Targets(left, right, center, forward, perp, true, true);
    }

    private static Vec3 anchor(Entity entity) {
        return entity.position().add(0.0, (double)entity.getBbHeight() * 0.85, 0.0);
    }

    public record Targets(Vec3 left, Vec3 right, Vec3 center, Vec3 forward, Vec3 perp, boolean duel, boolean valid) {
        public static Targets invalid() {
            return new Targets(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, false, false);
        }
    }
}
