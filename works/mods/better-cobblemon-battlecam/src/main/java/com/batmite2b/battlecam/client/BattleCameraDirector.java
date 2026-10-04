/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.entity.Entity
 *  net.minecraft.util.hit.BlockHitResult
 *  net.minecraft.util.hit.HitResult$Type
 *  net.minecraft.util.math.BlockPos
 *  net.minecraft.util.math.Box
 *  net.minecraft.util.math.Position
 *  net.minecraft.util.math.Vec3d
 *  net.minecraft.world.BlockView
 *  net.minecraft.world.RaycastContext
 *  net.minecraft.world.RaycastContext$FluidHandling
 *  net.minecraft.world.RaycastContext$ShapeType
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleActionEvent;
import com.batmite2b.battlecam.client.BattleActionKind;
import com.batmite2b.battlecam.client.BattleActionScene;
import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.BattleCameraRig;
import com.batmite2b.battlecam.client.BattleTargetResolver;
import com.batmite2b.battlecam.client.BattleViewContext;
import com.batmite2b.battlecam.client.CameraPose;
import com.batmite2b.battlecam.client.GimmickEvent;
import com.batmite2b.battlecam.client.GimmickKind;
import com.batmite2b.battlecam.client.GimmickScene;
import com.batmite2b.battlecam.client.PokemonVisualSnapshot;
import com.batmite2b.battlecam.client.ReflectionUtil;
import com.batmite2b.battlecam.client.TrainerTargetResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Position;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

public final class BattleCameraDirector {
    private static final long SHOT_DURATION_MS = 8500L;
    private static final long TARGET_LOST_GRACE_MS = 2500L;
    private final List<String> singleShots = List.of("establish_wide", "left_angle", "duel_wide", "right_angle", "top_down");
    private final List<String> doubleShots = List.of("doubles_establish", "doubles_diagonal", "doubles_team_a", "doubles_team_b", "doubles_top");
    private final BattleTargetResolver targetResolver = new BattleTargetResolver();
    private final TrainerTargetResolver trainerTargetResolver = new TrainerTargetResolver();
    private int index = 0;
    private long shotStartMs = System.currentTimeMillis();
    private boolean lastDoubles = false;
    private long lastValidTargetsMs = 0L;
    private GimmickScene activeGimmickScene = null;
    private BattleActionScene activeActionScene = null;
    private final Map<UUID, Long> recentPokemonCryAt = new HashMap<UUID, Long>();
    private String activeBattleId = "";
    private boolean cutNextPose = false;

    public void reset() {
        this.index = 0;
        this.shotStartMs = System.currentTimeMillis();
        this.lastDoubles = false;
        this.lastValidTargetsMs = 0L;
        this.activeGimmickScene = null;
        this.activeActionScene = null;
        this.recentPokemonCryAt.clear();
        this.activeBattleId = "";
        this.cutNextPose = true;
    }

    public void onPokemonCry(UUID entityUuid, UUID pokemonUuid) {
        long now = System.currentTimeMillis();
        if (entityUuid != null) {
            this.recentPokemonCryAt.put(entityUuid, now);
        }
        if (pokemonUuid != null) {
            this.recentPokemonCryAt.put(pokemonUuid, now);
        }
        if (this.activeGimmickScene != null) {
            this.activeGimmickScene.markCrySeen(entityUuid, pokemonUuid, now);
        }
    }

    private void applyRecentCryToScene(GimmickScene scene, long now) {
        if (scene == null || scene.kind() != GimmickKind.MEGA || scene.entityUuid() == null) {
            return;
        }
        Iterator<Map.Entry<UUID, Long>> iterator = this.recentPokemonCryAt.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (now - entry.getValue() <= 12000L) continue;
            iterator.remove();
        }
        Long cryAt = this.recentPokemonCryAt.get(scene.entityUuid());
        if (cryAt != null && now - cryAt <= 12000L) {
            scene.markCrySeen(scene.entityUuid(), null, cryAt);
        }
    }

    public void update(MinecraftClient client, BattleCamState state, BattleCameraRig rig) {
        BattleActionEvent actionEvent;
        this.activeBattleId = state.activeBattleId;
        BattleTargetResolver.Targets targets = this.targetResolver.resolve(client, state.activeBattleId);
        long now = System.currentTimeMillis();
        boolean cutThisPose = this.cutNextPose;
        this.cutNextPose = false;
        GimmickEvent queued = state.pollGimmickEvent();
        if (this.activeGimmickScene == null) {
            if (queued != null) {
                this.activeGimmickScene = GimmickScene.start(queued);
                this.applyRecentCryToScene(this.activeGimmickScene, now);
                state.markGimmickTriggeredNow(queued.kind());
                cutThisPose = true;
            }
        } else if (queued != null) {
            if (this.activeGimmickScene.canAbsorb(queued, now)) {
                this.activeGimmickScene.absorb(queued);
            } else {
                state.deferGimmickEvent(queued);
            }
        }
        if (this.activeGimmickScene != null) {
            if (this.updateGimmickScene(client, targets, rig, this.activeGimmickScene, now, cutThisPose)) {
                return;
            }
            this.activeGimmickScene = null;
            BattleCamClient.STATE.clearCinematicFocus();
            cutThisPose = true;
        }
        BattleActionEvent battleActionEvent = actionEvent = this.activeActionScene != null && this.activeActionScene.kind() == BattleActionKind.DAMAGE ? state.pollDamageEventGroup() : state.pollActionEvent();
        if (this.activeActionScene != null && this.activeActionScene.canExtend(actionEvent)) {
            this.activeActionScene.extend(actionEvent, now);
            actionEvent = null;
        }
        if (this.activeActionScene != null && BattleCameraDirector.shouldInterruptActionScene(this.activeActionScene, actionEvent)) {
            this.activeActionScene = BattleActionScene.start(actionEvent);
            actionEvent = null;
            cutThisPose = true;
        }
        if (this.activeActionScene == null && actionEvent != null) {
            this.activeActionScene = BattleActionScene.start(actionEvent);
            cutThisPose = true;
        }
        if (this.activeActionScene != null) {
            if (this.updateActionScene(client, targets, rig, this.activeActionScene, now, cutThisPose)) {
                return;
            }
            this.activeActionScene = null;
            BattleCamClient.STATE.clearCinematicFocus();
            cutThisPose = true;
        }
        BattleCamClient.STATE.clearCinematicFocus();
        if (!targets.valid()) {
            TrainerTargetResolver.Targets trainerTargets = this.trainerTargetResolver.resolve(client);
            if (trainerTargets.valid()) {
                CameraPose trainerPose = trainerTargets.duel() ? this.computeTrainerDuelPose(trainerTargets) : this.computeTrainerSinglePose(trainerTargets);
                rig.setDesired(trainerPose.pos(), trainerPose.yaw(), trainerPose.pitch(), trainerPose.fov(), cutThisPose);
                return;
            }
            if (rig.active && now - this.lastValidTargetsMs <= 2500L) {
                return;
            }
            rig.active = false;
            return;
        }
        this.lastValidTargetsMs = now;
        if (targets.doubles() != this.lastDoubles) {
            this.lastDoubles = targets.doubles();
            this.index = 0;
            this.shotStartMs = now;
            cutThisPose = true;
        }
        if (state.mode == BattleCamState.Mode.AUTO && now - this.shotStartMs >= 8500L) {
            this.nextShot();
            now = System.currentTimeMillis();
            cutThisPose = true;
        }
        float progress = BattleCameraDirector.clamp01((float)(now - this.shotStartMs) / 8500.0f);
        String shotId = this.getCurrentShotId();
        BattleFlags flags = this.resolveBattleFlags(client, state.activeBattleId);
        CameraPose pose = targets.singleRemaining() ? this.computeSingleRemainingPose(shotId, targets, progress) : (targets.doubles() ? this.computeDoublePose(shotId, targets, progress, flags) : this.computeSinglePose(shotId, targets, progress, flags));
        rig.setDesired(pose.pos(), pose.yaw(), pose.pitch(), pose.fov(), cutThisPose);
    }

    private static boolean shouldInterruptActionScene(BattleActionScene activeScene, BattleActionEvent incoming) {
        if (incoming == null) {
            return false;
        }
        BattleActionKind activeKind = activeScene.kind();
        return switch (incoming.kind()) {
            default -> throw new MatchException(null, null);
            case BattleActionKind.FAINT -> {
                if (activeKind != BattleActionKind.FAINT && activeKind != BattleActionKind.DAMAGE) {
                    yield true;
                }
                yield false;
            }
            case BattleActionKind.DAMAGE -> {
                if (activeKind == BattleActionKind.MOVE) {
                    yield true;
                }
                yield false;
            }
            case BattleActionKind.SWITCH -> {
                if (activeKind == BattleActionKind.SWITCH && incoming.entityUuid() != null && !incoming.entityUuid().equals(activeScene.entityUuid())) {
                    yield true;
                }
                yield false;
            }
            case BattleActionKind.MOVE -> false;
        };
    }

    private boolean updateGimmickScene(MinecraftClient client, BattleTargetResolver.Targets targets, BattleCameraRig rig, GimmickScene scene, long now, boolean cut) {
        GimmickFocus gimmickFocus;
        Entity focusEntity;
        if (scene.isFinished(now)) {
            return false;
        }
        Entity entity = focusEntity = scene.entityUuid() != null ? this.findBattleEntityByUuid(client, scene.entityUuid()) : null;
        if (targets.valid()) {
            Vec3d facingTarget;
            double dr;
            double dl;
            Vec3d center = targets.center();
            Vec3d forward = targets.forward();
            Vec3d focus = focusEntity != null ? BattleCameraDirector.anchor(focusEntity) : (client.player != null && BattleCamClient.STATE.context == BattleViewContext.OWN_BATTLE ? ((dl = client.player.squaredDistanceTo(targets.left().x, targets.left().y, targets.left().z)) <= (dr = client.player.squaredDistanceTo(targets.right().x, targets.right().y, targets.right().z)) ? targets.left() : targets.right()) : center);
            Vec3d vec3d = facingTarget = scene.kind() == GimmickKind.MEGA ? BattleCameraDirector.opposingTeamCenterFor(focus, targets) : center;
            gimmickFocus = focusEntity != null ? GimmickFocus.fromEntity(focusEntity, facingTarget, forward, scene.kind() == GimmickKind.MEGA) : GimmickFocus.fallback(focus, facingTarget, forward);
        } else {
            TrainerTargetResolver.Targets trainerTargets = this.trainerTargetResolver.resolve(client);
            if (trainerTargets.valid()) {
                Vec3d focus;
                Vec3d center = trainerTargets.center();
                Vec3d forward = trainerTargets.forward().lengthSquared() > 1.0E-4 ? trainerTargets.forward() : new Vec3d(1.0, 0.0, 0.0);
                Vec3d vec3d = focus = focusEntity != null ? BattleCameraDirector.anchor(focusEntity) : center;
                gimmickFocus = focusEntity != null ? GimmickFocus.fromEntity(focusEntity, center, forward, scene.kind() == GimmickKind.MEGA) : GimmickFocus.fallback(focus, center, forward);
            } else {
                if (client.player == null) {
                    return true;
                }
                Vec3d center = client.player.getPos().add(0.0, 1.0, 0.0);
                Vec3d forward = new Vec3d(1.0, 0.0, 0.0);
                Vec3d focus = center;
                gimmickFocus = GimmickFocus.fallback(focus, center.add(forward), forward);
            }
        }
        CameraPose pose = this.computeGimmickPose(scene.kind(), gimmickFocus, scene.progress(now), scene.postCryProgress(now));
        pose = scene.kind() == GimmickKind.MEGA ? this.adjustFrontalCinematicPoseForObstructions(client, pose, gimmickFocus.face()) : this.adjustCinematicPoseForObstructions(client, pose, gimmickFocus.face());
        BattleCamClient.STATE.setCinematicFocus(pose.pos(), gimmickFocus.face(), scene.entityUuid(), scene.kind() == GimmickKind.MEGA);
        rig.setDesired(pose.pos(), pose.yaw(), pose.pitch(), pose.fov(), cut);
        return true;
    }

    private boolean updateActionScene(MinecraftClient client, BattleTargetResolver.Targets targets, BattleCameraRig rig, BattleActionScene scene, long now, boolean cut) {
        if (scene.isFinished(now)) {
            return false;
        }
        List<Entity> focusEntities = this.findBattleEntitiesByUuids(client, scene.entityUuids());
        if (focusEntities.isEmpty() || !targets.valid()) {
            return false;
        }
        Vec3d focus = scene.kind() == BattleActionKind.DAMAGE ? BattleCameraDirector.averageAnchor(focusEntities) : BattleCameraDirector.anchor(focusEntities.get(0));
        double focusRadius = BattleCameraDirector.horizontalRadius(focus, focusEntities);
        CameraPose pose = switch (scene.kind()) {
            default -> throw new MatchException(null, null);
            case BattleActionKind.MOVE -> this.computeMoveActionPose(focus, targets, scene.progress(now));
            case BattleActionKind.DAMAGE -> this.computeDamageActionPose(focus, targets, scene.progress(now), focusRadius);
            case BattleActionKind.FAINT -> this.computeFaintActionPose(focus, targets, scene.progress(now));
            case BattleActionKind.SWITCH -> this.computeSwitchActionPose(focus, targets, scene.progress(now));
        };
        pose = this.adjustCinematicPoseForObstructions(client, pose, focus);
        boolean hideDynamaxPokemon = scene.kind() == BattleActionKind.MOVE || scene.kind() == BattleActionKind.DAMAGE || scene.kind() == BattleActionKind.FAINT || scene.kind() == BattleActionKind.SWITCH;
        BattleCamClient.STATE.setCinematicFocus(pose.pos(), focus, scene.entityUuids(), false, hideDynamaxPokemon);
        rig.setDesired(pose.pos(), pose.yaw(), pose.pitch(), pose.fov(), cut);
        return true;
    }

    private CameraPose adjustCinematicPoseForObstructions(MinecraftClient client, CameraPose pose, Vec3d focus) {
        if (client.world == null || this.hasClearSight(client, pose.pos(), focus)) {
            return pose;
        }
        Vec3d offset = pose.pos().subtract(focus);
        Vec3d flatOffset = new Vec3d(offset.x, 0.0, offset.z);
        if (flatOffset.lengthSquared() < 1.0E-4) {
            return pose;
        }
        double[] angles = new double[]{35.0, -35.0, 70.0, -70.0, 110.0, -110.0, 155.0, -155.0};
        double[] heightOffsets = new double[]{0.0, 1.2, -0.6, 2.0};
        Vec3d best = null;
        double bestDistance = Double.MAX_VALUE;
        for (double heightOffset : heightOffsets) {
            double[] dArray = angles;
            int n = dArray.length;
            for (int i = 0; i < n; ++i) {
                double distance;
                double angle = dArray[i];
                Vec3d candidate = focus.add(BattleCameraDirector.rotateY(offset, angle)).add(0.0, heightOffset, 0.0);
                if (!this.isCameraSpotUsable(client, candidate) || !this.hasClearSight(client, candidate, focus) || !((distance = candidate.squaredDistanceTo(pose.pos())) < bestDistance)) continue;
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best == null) {
            double[] scales;
            Vec3d direction = offset.normalize();
            double distance = offset.length();
            for (double scale : scales = new double[]{0.82, 0.68, 0.54, 0.42, 0.32}) {
                Vec3d candidate = focus.add(direction.multiply(distance * scale));
                if (!this.isCameraSpotUsable(client, candidate) || !this.hasClearSight(client, candidate, focus)) continue;
                best = candidate;
                break;
            }
        }
        if (best == null) {
            return pose;
        }
        return this.buildPose(best, focus, pose.fov());
    }

    private CameraPose adjustFrontalCinematicPoseForObstructions(MinecraftClient client, CameraPose pose, Vec3d focus) {
        if (client.world == null || this.hasClearSight(client, pose.pos(), focus)) {
            return pose;
        }
        Vec3d offset = pose.pos().subtract(focus);
        if (offset.lengthSquared() < 1.0E-4) {
            return pose;
        }
        double distance = offset.length();
        Vec3d direction = offset.normalize();
        double[] heightOffsets = new double[]{0.45, 0.9, 1.35, -0.35};
        double[] scales = new double[]{0.9, 0.78, 0.66, 0.54, 1.12};
        for (double heightOffset : heightOffsets) {
            for (double scale : scales) {
                Vec3d candidate = focus.add(direction.multiply(distance * scale)).add(0.0, heightOffset, 0.0);
                if (!this.isCameraSpotUsable(client, candidate) || !this.hasClearSight(client, candidate, focus)) continue;
                return this.buildPose(candidate, focus, pose.fov());
            }
        }
        return pose;
    }

    private static Vec3d rotateY(Vec3d vector, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3d(vector.x * cos - vector.z * sin, vector.y, vector.x * sin + vector.z * cos);
    }

    private boolean hasClearSight(MinecraftClient client, Vec3d from, Vec3d to) {
        if (client.world == null) {
            return true;
        }
        BlockHitResult hit = client.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, (Entity)client.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        double totalDistance = from.distanceTo(to);
        double hitDistance = from.distanceTo(hit.getPos());
        return hitDistance >= totalDistance - 0.35;
    }

    private boolean isCameraSpotUsable(MinecraftClient client, Vec3d pos) {
        if (client.world == null) {
            return true;
        }
        BlockPos blockPos = BlockPos.ofFloored((Position)pos);
        return client.world.getBlockState(blockPos).getCollisionShape((BlockView)client.world, blockPos).isEmpty();
    }

    private CameraPose computeMoveActionPose(Vec3d focus, BattleTargetResolver.Targets targets, float progress) {
        float eased = BattleCameraDirector.easeInOut(progress);
        Vec3d center = targets.center();
        Vec3d toCenter = new Vec3d(center.x - focus.x, 0.0, center.z - focus.z);
        Vec3d front = toCenter.lengthSquared() > 1.0E-4 ? toCenter.normalize() : targets.forward();
        Vec3d side = new Vec3d(-front.z, 0.0, front.x);
        double distance = BattleCameraDirector.lerp(5.0, 3.9, eased);
        double sideOffset = BattleCameraDirector.lerp(1.2, 0.4, eased);
        double height = BattleCameraDirector.lerp(1.75, 1.35, eased);
        Vec3d cameraPos = focus.add(front.multiply(distance)).add(side.multiply(sideOffset)).add(0.0, height, 0.0);
        Vec3d lookAt = focus.add(0.0, 0.18, 0.0);
        float fov = (float)BattleCameraDirector.lerp(58.0, 47.0, eased);
        return this.buildPose(cameraPos, lookAt, fov);
    }

    private CameraPose computeDamageActionPose(Vec3d focus, BattleTargetResolver.Targets targets, float progress, double focusRadius) {
        float eased = BattleCameraDirector.easeOut(progress);
        Vec3d center = targets.center();
        Vec3d toBattleCenter = new Vec3d(center.x - focus.x, 0.0, center.z - focus.z);
        Vec3d front = toBattleCenter.lengthSquared() > 1.0E-4 ? toBattleCenter.normalize() : targets.forward();
        Vec3d side = new Vec3d(-front.z, 0.0, front.x);
        double impact = Math.sin((double)(Math.min(progress, 0.55f) / 0.55f) * Math.PI) * 0.35;
        double groupSpace = Math.min(2.2, focusRadius * 0.65);
        double distance = BattleCameraDirector.lerp(4.1 + groupSpace, 5.4 + groupSpace, eased) - impact;
        double height = BattleCameraDirector.lerp(1.28, 1.85 + groupSpace * 0.18, eased);
        double sideOffset = focusRadius > 1.2 ? 0.15 : 0.55;
        Vec3d cameraPos = focus.add(front.multiply(distance)).add(side.multiply(sideOffset)).add(0.0, height, 0.0);
        Vec3d lookAt = focus.add(0.0, 0.12, 0.0);
        float fov = (float)BattleCameraDirector.lerp(43.0 + groupSpace * 4.0, 58.0 + groupSpace * 4.0, eased);
        return this.buildPose(cameraPos, lookAt, fov);
    }

    private CameraPose computeFaintActionPose(Vec3d focus, BattleTargetResolver.Targets targets, float progress) {
        float eased = BattleCameraDirector.easeInOut(progress);
        Vec3d center = targets.center();
        Vec3d toBattleCenter = new Vec3d(center.x - focus.x, 0.0, center.z - focus.z);
        Vec3d front = toBattleCenter.lengthSquared() > 1.0E-4 ? toBattleCenter.normalize() : targets.forward();
        Vec3d side = new Vec3d(-front.z, 0.0, front.x);
        double distance = BattleCameraDirector.lerp(4.6, 6.2, eased);
        double height = BattleCameraDirector.lerp(1.25, 2.15, eased);
        double sideDrift = BattleCameraDirector.lerp(0.45, 1.1, eased);
        Vec3d cameraPos = focus.add(front.multiply(distance)).add(side.multiply(sideDrift)).add(0.0, height, 0.0);
        Vec3d lookAt = focus.add(0.0, BattleCameraDirector.lerp(0.18, -0.18, eased), 0.0);
        float fov = (float)BattleCameraDirector.lerp(48.0, 62.0, eased);
        return this.buildPose(cameraPos, lookAt, fov);
    }

    private CameraPose computeSwitchActionPose(Vec3d focus, BattleTargetResolver.Targets targets, float progress) {
        float eased = BattleCameraDirector.easeOut(progress);
        Vec3d opposing = BattleCameraDirector.opposingTeamCenterFor(focus, targets);
        Vec3d toOpposing = new Vec3d(opposing.x - focus.x, 0.0, opposing.z - focus.z);
        Vec3d front = toOpposing.lengthSquared() > 1.0E-4 ? toOpposing.normalize() : targets.forward();
        Vec3d side = new Vec3d(-front.z, 0.0, front.x);
        double introArc = Math.sin((double)eased * Math.PI) * 0.9;
        double distance = BattleCameraDirector.lerp(6.4, 4.4, eased);
        double sideOffset = BattleCameraDirector.lerp(1.8, 0.35, eased) + introArc;
        double height = BattleCameraDirector.lerp(2.45, 1.35, eased);
        Vec3d cameraPos = focus.add(front.multiply(distance)).add(side.multiply(sideOffset)).add(0.0, height, 0.0);
        Vec3d lookAt = focus.add(0.0, 0.18, 0.0);
        float fov = (float)BattleCameraDirector.lerp(68.0, 48.0, eased);
        return this.buildPose(cameraPos, lookAt, fov);
    }

    private CameraPose computeGimmickPose(GimmickKind kind, GimmickFocus focus, float progress, float postCryProgress) {
        float fov;
        Vec3d cameraPos;
        float eased = BattleCameraDirector.easeInOut(progress);
        Vec3d body = focus.body();
        Vec3d face = focus.face();
        Vec3d front = focus.front();
        Vec3d side = focus.side();
        double scale = focus.scale();
        switch (kind) {
            case MEGA: {
                return this.computeMegaRevealPoseFrontal(focus, postCryProgress);
            }
            case ULTRA_BURST: {
                double pulse = Math.sin((double)progress * Math.PI * 3.0) * 0.18 * scale;
                double distance = BattleCameraDirector.lerp(6.4, 3.5, eased) * scale - pulse;
                double sideOffset = BattleCameraDirector.lerp(-2.4, 0.55, eased) * scale;
                double height = BattleCameraDirector.lerp(1.45, 0.05, eased) * scale;
                double orbit = Math.sin((double)eased * Math.PI) * 1.15 * scale;
                cameraPos = BattleCameraDirector.cameraAroundFace(focus, distance, sideOffset + orbit, height);
                fov = (float)BattleCameraDirector.lerp(72.0, 40.0, eased);
                break;
            }
            case TERA: {
                double sparkleBeat = Math.sin((double)progress * Math.PI * 4.0) * 0.12 * scale;
                double distance = BattleCameraDirector.lerp(10.5, 4.9, eased) * scale - sparkleBeat;
                double sideOffset = BattleCameraDirector.lerp(4.8, -0.45, eased) * scale;
                double height = BattleCameraDirector.lerp(3.4, 0.55, eased) * scale;
                double drift = Math.sin((double)eased * Math.PI) * 1.35 * scale;
                cameraPos = BattleCameraDirector.cameraAroundFace(focus, distance, sideOffset + drift, height);
                fov = (float)BattleCameraDirector.lerp(86.0, 48.0, eased);
                break;
            }
            case DYNAMAX: {
                double distance = BattleCameraDirector.lerp(12.5, 8.4, eased) * scale;
                double sideOffset = BattleCameraDirector.lerp(3.8, 0.8, eased) * scale;
                double height = BattleCameraDirector.lerp(4.4, 1.65, eased) * scale;
                double arc = Math.sin((double)eased * Math.PI) * 1.7 * scale;
                cameraPos = BattleCameraDirector.cameraAroundFace(focus, distance + arc, sideOffset, height);
                fov = (float)BattleCameraDirector.lerp(92.0, 58.0, eased);
                break;
            }
            case FORM_CHANGE: {
                double distance = BattleCameraDirector.lerp(6.1, 3.8, eased) * scale;
                double sideOffset = BattleCameraDirector.lerp(-1.7, 0.25, eased) * scale;
                double height = BattleCameraDirector.lerp(1.05, 0.08, eased) * scale;
                double orbit = Math.sin((double)eased * Math.PI) * 0.8 * scale;
                cameraPos = BattleCameraDirector.cameraAroundFace(focus, distance, sideOffset + orbit, height);
                fov = (float)BattleCameraDirector.lerp(70.0, 46.0, eased);
                break;
            }
            default: {
                cameraPos = body.add(front.multiply(5.4 * scale)).add(side.multiply(0.9 * scale)).add(0.0, 0.6 * scale, 0.0);
                fov = 64.0f;
            }
        }
        return this.buildPose(cameraPos, face, fov);
    }

    private CameraPose computeMegaRevealPoseFrontal(GimmickFocus focus, float postCryProgress) {
        double scale = focus.scale();
        float zoomOut = BattleCameraDirector.easeOut(postCryProgress);
        double frontalDistance = BattleCameraDirector.lerp(5.9, 6.55, zoomOut) * scale;
        double height = BattleCameraDirector.lerp(0.32, 0.42, zoomOut) * scale;
        float fov = (float)BattleCameraDirector.lerp(56.0, 61.0, zoomOut);
        Vec3d cameraPos = BattleCameraDirector.cameraAroundFace(focus, frontalDistance, 0.0, height);
        return this.buildPose(cameraPos, focus.face(), fov);
    }

    private CameraPose computeTrainerDuelPose(TrainerTargetResolver.Targets t) {
        Vec3d center = t.center();
        Vec3d forward = t.forward().lengthSquared() > 1.0E-4 ? t.forward() : new Vec3d(1.0, 0.0, 0.0);
        Vec3d side = t.perp().lengthSquared() > 1.0E-4 ? t.perp() : new Vec3d(0.0, 0.0, 1.0);
        Vec3d cameraPos = center.add(side.multiply(4.8)).add(forward.multiply(0.8)).add(0.0, 1.9, 0.0);
        Vec3d lookAt = center.add(0.0, 1.1, 0.0);
        return this.buildPose(cameraPos, lookAt, 58.0f);
    }

    private CameraPose computeTrainerSinglePose(TrainerTargetResolver.Targets t) {
        Vec3d trainer = t.center();
        Vec3d cameraPos = trainer.add(2.2, 1.6, 2.2);
        Vec3d lookAt = trainer.add(0.0, 0.9, 0.0);
        return this.buildPose(cameraPos, lookAt, 52.0f);
    }

    private CameraPose computeSinglePose(String shotId, BattleTargetResolver.Targets t, float progress, BattleFlags flags) {
        Vec3d lookAt;
        Vec3d cameraPos;
        if (flags.hasDynamax) {
            return this.computeSingleDynamaxPose(shotId, t, progress);
        }
        Vec3d center = t.center();
        Vec3d left = t.left();
        Vec3d right = t.right();
        Vec3d side = t.perp();
        Vec3d forward = t.forward();
        float eased = BattleCameraDirector.easeInOut(progress);
        float fov = switch (shotId) {
            case "duel_wide" -> {
                double distance = BattleCameraDirector.lerp(7.8, 6.0, eased);
                double height = BattleCameraDirector.lerp(3.4, 2.8, eased);
                double drift = BattleCameraDirector.lerp(-1.5, 1.5, eased);
                cameraPos = center.add(side.multiply(-distance)).add(forward.multiply(drift)).add(0.0, height, 0.0);
                lookAt = center.add(0.0, 0.35, 0.0);
                yield (float)BattleCameraDirector.lerp(70.0, 61.0, eased);
            }
            case "left_angle" -> {
                double sideOffset = BattleCameraDirector.lerp(3.6, 2.4, eased);
                double backOffset = BattleCameraDirector.lerp(-2.2, -1.0, eased);
                double rise = BattleCameraDirector.lerp(1.45, 1.15, eased);
                double orbit = Math.sin((double)eased * Math.PI) * 0.9;
                cameraPos = left.add(side.multiply(sideOffset)).add(forward.multiply(backOffset + orbit)).add(0.0, rise, 0.0);
                lookAt = right.add(0.0, 0.25, 0.0);
                yield (float)BattleCameraDirector.lerp(64.0, 54.0, eased);
            }
            case "right_angle" -> {
                double sideOffset = BattleCameraDirector.lerp(-3.6, -2.4, eased);
                double backOffset = BattleCameraDirector.lerp(2.2, 1.0, eased);
                double rise = BattleCameraDirector.lerp(1.45, 1.15, eased);
                double orbit = Math.sin((double)eased * Math.PI) * 0.9;
                cameraPos = right.add(side.multiply(sideOffset)).add(forward.multiply(backOffset - orbit)).add(0.0, rise, 0.0);
                lookAt = left.add(0.0, 0.25, 0.0);
                yield (float)BattleCameraDirector.lerp(64.0, 54.0, eased);
            }
            case "top_down" -> {
                double height = BattleCameraDirector.lerp(9.4, 7.8, eased);
                double drift = Math.sin((double)eased * Math.PI) * 1.0;
                cameraPos = center.add(side.multiply(drift)).add(0.0, height, 0.01);
                lookAt = center.add(0.0, 0.12, 0.0);
                yield (float)BattleCameraDirector.lerp(72.0, 66.0, eased);
            }
            case "establish_wide" -> {
                double distance = BattleCameraDirector.lerp(9.0, 6.8, eased);
                double height = BattleCameraDirector.lerp(4.2, 3.1, eased);
                double arc = Math.sin((double)eased * Math.PI) * 1.4;
                cameraPos = center.add(side.multiply(distance)).add(forward.multiply(arc)).add(0.0, height, 0.0);
                lookAt = center.add(0.0, 0.35, 0.0);
                yield (float)BattleCameraDirector.lerp(76.0, 64.0, eased);
            }
            default -> {
                cameraPos = center.add(side.multiply(7.5)).add(0.0, 3.4, 0.0);
                lookAt = center.add(0.0, 0.35, 0.0);
                yield 68.0f;
            }
        };
        return this.buildPose(cameraPos, lookAt, fov);
    }

    private CameraPose computeSingleRemainingPose(String shotId, BattleTargetResolver.Targets t, float progress) {
        Vec3d cameraPos;
        Vec3d focus = t.center();
        Vec3d forward = t.forward().lengthSquared() > 1.0E-4 ? t.forward() : new Vec3d(1.0, 0.0, 0.0);
        Vec3d side = t.perp().lengthSquared() > 1.0E-4 ? t.perp() : new Vec3d(0.0, 0.0, 1.0);
        float eased = BattleCameraDirector.easeInOut(progress);
        float fov = switch (shotId) {
            case "top_down" -> {
                double drift = Math.sin((double)eased * Math.PI) * 1.0;
                cameraPos = focus.add(side.multiply(drift)).add(forward.multiply(0.4)).add(0.0, 7.0, 0.01);
                yield (float)BattleCameraDirector.lerp(66.0, 60.0, eased);
            }
            case "left_angle" -> {
                double distance = BattleCameraDirector.lerp(4.8, 4.0, eased);
                double sideOffset = BattleCameraDirector.lerp(2.8, 1.8, eased);
                cameraPos = focus.add(forward.multiply(distance)).add(side.multiply(sideOffset)).add(0.0, 1.35, 0.0);
                yield (float)BattleCameraDirector.lerp(56.0, 48.0, eased);
            }
            case "right_angle" -> {
                double distance = BattleCameraDirector.lerp(4.8, 4.0, eased);
                double sideOffset = BattleCameraDirector.lerp(-2.8, -1.8, eased);
                cameraPos = focus.add(forward.multiply(distance)).add(side.multiply(sideOffset)).add(0.0, 1.35, 0.0);
                yield (float)BattleCameraDirector.lerp(56.0, 48.0, eased);
            }
            case "duel_wide" -> {
                double distance = BattleCameraDirector.lerp(6.2, 5.2, eased);
                double drift = BattleCameraDirector.lerp(-0.8, 0.8, eased);
                cameraPos = focus.add(forward.multiply(distance)).add(side.multiply(drift)).add(0.0, 2.25, 0.0);
                yield (float)BattleCameraDirector.lerp(62.0, 54.0, eased);
            }
            default -> {
                double distance = BattleCameraDirector.lerp(6.8, 5.6, eased);
                double sideOffset = Math.sin((double)eased * Math.PI) * 1.15;
                cameraPos = focus.add(forward.multiply(distance)).add(side.multiply(sideOffset)).add(0.0, 2.6, 0.0);
                yield (float)BattleCameraDirector.lerp(68.0, 58.0, eased);
            }
        };
        Vec3d lookAt = focus.add(0.0, 0.12, 0.0);
        return this.buildPose(cameraPos, lookAt, fov);
    }

    private CameraPose computeSingleDynamaxPose(String shotId, BattleTargetResolver.Targets t, float progress) {
        Vec3d lookAt;
        Vec3d cameraPos;
        Vec3d center = t.center();
        Vec3d left = t.left();
        Vec3d right = t.right();
        Vec3d side = t.perp();
        Vec3d forward = t.forward();
        float eased = BattleCameraDirector.easeInOut(progress);
        float fov = switch (shotId) {
            case "left_angle" -> {
                cameraPos = left.add(side.multiply(6.6)).add(forward.multiply(-2.4 + Math.sin((double)eased * Math.PI) * 1.0)).add(0.0, 3.2, 0.0);
                lookAt = right.add(0.0, 0.9, 0.0);
                yield (float)BattleCameraDirector.lerp(80.0, 68.0, eased);
            }
            case "right_angle" -> {
                cameraPos = right.add(side.multiply(-6.6)).add(forward.multiply(2.4 - Math.sin((double)eased * Math.PI) * 1.0)).add(0.0, 3.2, 0.0);
                lookAt = left.add(0.0, 0.9, 0.0);
                yield (float)BattleCameraDirector.lerp(80.0, 68.0, eased);
            }
            case "top_down" -> {
                cameraPos = center.add(side.multiply(Math.sin((double)eased * Math.PI) * 1.6)).add(0.0, 13.0, 0.01);
                lookAt = center.add(0.0, 0.75, 0.0);
                yield (float)BattleCameraDirector.lerp(84.0, 76.0, eased);
            }
            case "duel_wide" -> {
                cameraPos = center.add(side.multiply(-12.0)).add(forward.multiply(BattleCameraDirector.lerp(-2.0, 2.0, eased))).add(0.0, 5.0, 0.0);
                lookAt = center.add(0.0, 0.8, 0.0);
                yield (float)BattleCameraDirector.lerp(86.0, 74.0, eased);
            }
            case "establish_wide" -> {
                cameraPos = center.add(side.multiply(13.5)).add(forward.multiply(Math.sin((double)eased * Math.PI) * 2.2)).add(0.0, 5.8, 0.0);
                lookAt = center.add(0.0, 0.85, 0.0);
                yield (float)BattleCameraDirector.lerp(90.0, 76.0, eased);
            }
            default -> {
                cameraPos = center.add(side.multiply(13.5)).add(forward.multiply(Math.sin((double)eased * Math.PI) * 2.2)).add(0.0, 5.8, 0.0);
                lookAt = center.add(0.0, 0.85, 0.0);
                yield 80.0f;
            }
        };
        return this.buildPose(cameraPos, lookAt, fov);
    }

    private CameraPose computeDoublePose(String shotId, BattleTargetResolver.Targets t, float progress, BattleFlags flags) {
        Vec3d lookAt;
        Vec3d cameraPos;
        if (flags.hasDynamax) {
            return this.computeDoubleDynamaxPose(shotId, t, progress);
        }
        Vec3d center = t.center();
        Vec3d teamA = t.teamACenter();
        Vec3d teamB = t.teamBCenter();
        Vec3d side = t.perp();
        Vec3d forward = t.forward();
        float eased = BattleCameraDirector.easeInOut(progress);
        float fov = switch (shotId) {
            case "doubles_diagonal" -> {
                double distance = BattleCameraDirector.lerp(10.0, 8.0, eased);
                double height = BattleCameraDirector.lerp(4.4, 3.2, eased);
                double arc = Math.sin((double)eased * Math.PI) * 2.0;
                cameraPos = center.add(side.multiply(distance)).add(forward.multiply(-4.0 + arc)).add(0.0, height, 0.0);
                lookAt = center.add(0.0, 0.32, 0.0);
                yield (float)BattleCameraDirector.lerp(78.0, 68.0, eased);
            }
            case "doubles_team_a" -> {
                double sideOffset = BattleCameraDirector.lerp(5.0, 3.3, eased);
                double backOffset = BattleCameraDirector.lerp(-3.0, -1.5, eased);
                double height = BattleCameraDirector.lerp(2.3, 1.7, eased);
                double push = Math.sin((double)eased * Math.PI) * 0.8;
                cameraPos = teamA.add(side.multiply(sideOffset)).add(forward.multiply(backOffset + push)).add(0.0, height, 0.0);
                lookAt = teamB.add(0.0, 0.28, 0.0);
                yield (float)BattleCameraDirector.lerp(66.0, 56.0, eased);
            }
            case "doubles_team_b" -> {
                double sideOffset = BattleCameraDirector.lerp(-5.0, -3.3, eased);
                double backOffset = BattleCameraDirector.lerp(3.0, 1.5, eased);
                double height = BattleCameraDirector.lerp(2.3, 1.7, eased);
                double push = Math.sin((double)eased * Math.PI) * 0.8;
                cameraPos = teamB.add(side.multiply(sideOffset)).add(forward.multiply(backOffset - push)).add(0.0, height, 0.0);
                lookAt = teamA.add(0.0, 0.28, 0.0);
                yield (float)BattleCameraDirector.lerp(66.0, 56.0, eased);
            }
            case "doubles_top" -> {
                double height = BattleCameraDirector.lerp(11.0, 9.0, eased);
                double drift = Math.sin((double)eased * Math.PI) * 1.4;
                cameraPos = center.add(side.multiply(drift)).add(0.0, height, 0.01);
                lookAt = center.add(0.0, 0.14, 0.0);
                yield (float)BattleCameraDirector.lerp(74.0, 68.0, eased);
            }
            case "doubles_establish" -> {
                double distance = BattleCameraDirector.lerp(11.5, 8.8, eased);
                double height = BattleCameraDirector.lerp(4.8, 3.6, eased);
                double arc = Math.sin((double)eased * Math.PI) * 2.2;
                cameraPos = center.add(side.multiply(distance)).add(forward.multiply(arc)).add(0.0, height, 0.0);
                lookAt = center.add(0.0, 0.32, 0.0);
                yield (float)BattleCameraDirector.lerp(80.0, 69.0, eased);
            }
            default -> {
                cameraPos = center.add(side.multiply(10.0)).add(0.0, 4.0, 0.0);
                lookAt = center.add(0.0, 0.32, 0.0);
                yield 70.0f;
            }
        };
        return this.buildPose(cameraPos, lookAt, fov);
    }

    private CameraPose computeDoubleDynamaxPose(String shotId, BattleTargetResolver.Targets t, float progress) {
        Vec3d lookAt;
        Vec3d cameraPos;
        Vec3d center = t.center();
        Vec3d teamA = t.teamACenter();
        Vec3d teamB = t.teamBCenter();
        Vec3d side = t.perp();
        Vec3d forward = t.forward();
        float eased = BattleCameraDirector.easeInOut(progress);
        float fov = switch (shotId) {
            case "doubles_team_a" -> {
                cameraPos = teamA.add(side.multiply(8.0)).add(forward.multiply(-4.2 + Math.sin((double)eased * Math.PI) * 1.0)).add(0.0, 4.0, 0.0);
                lookAt = teamB.add(0.0, 0.85, 0.0);
                yield (float)BattleCameraDirector.lerp(82.0, 70.0, eased);
            }
            case "doubles_team_b" -> {
                cameraPos = teamB.add(side.multiply(-8.0)).add(forward.multiply(4.2 - Math.sin((double)eased * Math.PI) * 1.0)).add(0.0, 4.0, 0.0);
                lookAt = teamA.add(0.0, 0.85, 0.0);
                yield (float)BattleCameraDirector.lerp(82.0, 70.0, eased);
            }
            case "doubles_top" -> {
                cameraPos = center.add(side.multiply(Math.sin((double)eased * Math.PI) * 1.8)).add(0.0, 15.0, 0.01);
                lookAt = center.add(0.0, 0.75, 0.0);
                yield (float)BattleCameraDirector.lerp(88.0, 78.0, eased);
            }
            case "doubles_diagonal" -> {
                cameraPos = center.add(side.multiply(15.0)).add(forward.multiply(-5.5 + Math.sin((double)eased * Math.PI) * 2.8)).add(0.0, 6.0, 0.0);
                lookAt = center.add(0.0, 0.85, 0.0);
                yield (float)BattleCameraDirector.lerp(90.0, 78.0, eased);
            }
            case "doubles_establish" -> {
                cameraPos = center.add(side.multiply(16.5)).add(forward.multiply(Math.sin((double)eased * Math.PI) * 3.0)).add(0.0, 6.8, 0.0);
                lookAt = center.add(0.0, 0.9, 0.0);
                yield (float)BattleCameraDirector.lerp(94.0, 80.0, eased);
            }
            default -> {
                cameraPos = center.add(side.multiply(16.5)).add(forward.multiply(Math.sin((double)eased * Math.PI) * 3.0)).add(0.0, 6.8, 0.0);
                lookAt = center.add(0.0, 0.9, 0.0);
                yield 82.0f;
            }
        };
        return this.buildPose(cameraPos, lookAt, fov);
    }

    private BattleFlags resolveBattleFlags(MinecraftClient client, String battleId) {
        if (client.world == null || client.player == null) {
            return new BattleFlags(false, false);
        }
        Box box = new Box(client.player.getX() - 96.0, client.player.getY() - 32.0, client.player.getZ() - 96.0, client.player.getX() + 96.0, client.player.getY() + 32.0, client.player.getZ() + 96.0);
        List<Entity> entities = client.world.getOtherEntities(null, box, entity -> entity != null && !entity.isRemoved() && BattleCameraDirector.isBattlePokemon(entity) && ReflectionUtil.entityMatchesBattle(entity, battleId));
        boolean hasDynamax = false;
        boolean hasTera = false;
        for (Entity entity2 : entities) {
            PokemonVisualSnapshot snapshot = PokemonVisualSnapshot.from(entity2);
            if (snapshot.dynamaxLike()) {
                hasDynamax = true;
            }
            if (!snapshot.teraLike()) continue;
            hasTera = true;
        }
        return new BattleFlags(hasDynamax, hasTera);
    }

    private Entity findBattleEntityByUuid(MinecraftClient client, UUID uuid) {
        if (client.world == null || client.player == null) {
            return null;
        }
        Box box = new Box(client.player.getX() - 96.0, client.player.getY() - 32.0, client.player.getZ() - 96.0, client.player.getX() + 96.0, client.player.getY() + 32.0, client.player.getZ() + 96.0);
        List<Entity> entities = client.world.getOtherEntities(null, box, entity -> entity != null && !entity.isRemoved() && ReflectionUtil.entityMatchesEntityOrPokemonUuid(entity, uuid) && ReflectionUtil.entityMatchesBattle(entity, this.activeBattleId));
        return entities.isEmpty() ? null : entities.get(0);
    }

    private List<Entity> findBattleEntitiesByUuids(MinecraftClient client, List<UUID> uuids) {
        if (client.world == null || client.player == null || uuids == null || uuids.isEmpty()) {
            return List.of();
        }
        Box box = new Box(client.player.getX() - 96.0, client.player.getY() - 32.0, client.player.getZ() - 96.0, client.player.getX() + 96.0, client.player.getY() + 32.0, client.player.getZ() + 96.0);
        ArrayList<Entity> matched = new ArrayList<Entity>();
        for (UUID uuid : uuids) {
            List<Entity> entities = client.world.getOtherEntities(null, box, entity -> entity != null && !entity.isRemoved() && ReflectionUtil.entityMatchesEntityOrPokemonUuid(entity, uuid) && ReflectionUtil.entityMatchesBattle(entity, this.activeBattleId));
            if (entities.isEmpty() || matched.contains(entities.get(0))) continue;
            matched.add(entities.get(0));
        }
        return List.copyOf(matched);
    }

    private static boolean isBattlePokemon(Entity entity) {
        Boolean b;
        if (!entity.getClass().getName().equals("com.cobblemon.mod.common.entity.pokemon.PokemonEntity")) {
            return false;
        }
        Object battleId = ReflectionUtil.invokeNoArg(entity, "getBattleId");
        if (battleId != null) {
            return true;
        }
        Object battling = ReflectionUtil.invokeNoArg(entity, "isBattling");
        return battling instanceof Boolean && (b = (Boolean)battling) != false;
    }

    private static Vec3d anchor(Entity entity) {
        return entity.getPos().add(0.0, (double)entity.getHeight() * 0.5, 0.0);
    }

    private static Vec3d averageAnchor(List<Entity> entities) {
        if (entities.isEmpty()) {
            return Vec3d.ZERO;
        }
        Vec3d sum = Vec3d.ZERO;
        for (Entity entity : entities) {
            sum = sum.add(BattleCameraDirector.anchor(entity));
        }
        return sum.multiply(1.0 / (double)entities.size());
    }

    private static double horizontalRadius(Vec3d focus, List<Entity> entities) {
        double radius = 0.0;
        for (Entity entity : entities) {
            Vec3d anchor = BattleCameraDirector.anchor(entity);
            double dx = anchor.x - focus.x;
            double dz = anchor.z - focus.z;
            double distance = Math.sqrt(dx * dx + dz * dz) + (double)entity.getWidth() * 0.5;
            radius = Math.max(radius, distance);
        }
        return radius;
    }

    private static Vec3d opposingTeamCenterFor(Vec3d focus, BattleTargetResolver.Targets targets) {
        double distanceToTeamB;
        if (targets == null || !targets.valid()) {
            return focus;
        }
        double distanceToTeamA = BattleCameraDirector.horizontalDistanceSquared(focus, targets.teamACenter());
        return distanceToTeamA <= (distanceToTeamB = BattleCameraDirector.horizontalDistanceSquared(focus, targets.teamBCenter())) ? targets.teamBCenter() : targets.teamACenter();
    }

    private static double horizontalDistanceSquared(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static Vec3d cameraAroundFace(GimmickFocus focus, double frontDistance, double sideOffset, double heightOffset) {
        return focus.face().add(focus.front().multiply(frontDistance)).add(focus.side().multiply(sideOffset)).add(0.0, heightOffset, 0.0);
    }

    private CameraPose buildPose(Vec3d cameraPos, Vec3d lookAt, float fov) {
        float yaw = BattleCameraDirector.lookYaw(cameraPos, lookAt);
        float pitch = BattleCameraDirector.lookPitch(cameraPos, lookAt);
        return new CameraPose(cameraPos, yaw, pitch, fov);
    }

    private static float lookYaw(Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from);
        return (float)(Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
    }

    private static float lookPitch(Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        return (float)(-Math.toDegrees(Math.atan2(d.y, horizontal)));
    }

    private static double lerp(double a, double b, float t) {
        return a + (b - a) * (double)t;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float easeInOut(float t) {
        return (float)(0.5 - 0.5 * Math.cos(Math.PI * (double)t));
    }

    private static float easeOut(float t) {
        return (float)(1.0 - Math.pow(1.0 - (double)t, 3.0));
    }

    public void nextShot() {
        this.index = (this.index + 1) % this.getCurrentShotList().size();
        this.shotStartMs = System.currentTimeMillis();
        this.cutNextPose = true;
    }

    public void previousShot() {
        --this.index;
        if (this.index < 0) {
            this.index = this.getCurrentShotList().size() - 1;
        }
        this.shotStartMs = System.currentTimeMillis();
        this.cutNextPose = true;
    }

    public String getCurrentShotId() {
        if (this.activeGimmickScene != null) {
            return "gimmick_" + this.activeGimmickScene.kind().name().toLowerCase();
        }
        if (this.activeActionScene != null) {
            return "action_" + this.activeActionScene.kind().name().toLowerCase();
        }
        List<String> shots = this.getCurrentShotList();
        if (this.index >= shots.size()) {
            this.index = 0;
        }
        return shots.get(this.index);
    }

    private List<String> getCurrentShotList() {
        return this.lastDoubles ? this.doubleShots : this.singleShots;
    }

    private record BattleFlags(boolean hasDynamax, boolean hasTera) {
    }

    private record GimmickFocus(Vec3d body, Vec3d face, Vec3d front, Vec3d side, double scale) {
        private static GimmickFocus fromEntity(Entity entity, Vec3d battleCenter, Vec3d fallbackForward, boolean strictEntityFront) {
            Vec3d front;
            Vec3d body = entity.getBoundingBox().getCenter();
            Vec3d toCenter = GimmickFocus.horizontalDirection(battleCenter.subtract(body));
            Vec3d vec3d = front = strictEntityFront ? toCenter : GimmickFocus.horizontalFacing(entity);
            if (front.lengthSquared() < 1.0E-4) {
                Vec3d vec3d2 = front = fallbackForward.lengthSquared() > 1.0E-4 ? fallbackForward.normalize() : new Vec3d(1.0, 0.0, 0.0);
            }
            if (!strictEntityFront && toCenter.lengthSquared() > 1.0E-4 && front.dotProduct(toCenter) < 0.0) {
                front = front.negate();
            }
            Vec3d side = new Vec3d(-front.z, 0.0, front.x);
            double scale = Math.max(0.68, Math.min(3.6, Math.max((double)entity.getWidth() / 1.1, (double)entity.getHeight() / 1.6)));
            double faceForward = Math.max(0.12, Math.min(1.4, (double)entity.getWidth() * 0.32));
            Vec3d face = new Vec3d(body.x, entity.getY() + (double)entity.getHeight() * 0.62, body.z).add(front.multiply(faceForward));
            return new GimmickFocus(body, face, front, side, scale);
        }

        private static GimmickFocus fallback(Vec3d body, Vec3d battleCenter, Vec3d fallbackForward) {
            Vec3d front = GimmickFocus.horizontalDirection(battleCenter.subtract(body));
            if (front.lengthSquared() < 1.0E-4) {
                front = fallbackForward.lengthSquared() > 1.0E-4 ? fallbackForward.normalize() : new Vec3d(1.0, 0.0, 0.0);
            }
            Vec3d side = new Vec3d(-front.z, 0.0, front.x);
            return new GimmickFocus(body, body.add(0.0, 0.25, 0.0), front, side, 1.0);
        }

        private static Vec3d horizontalFacing(Entity entity) {
            double yaw = Math.toRadians(entity.getYaw());
            return new Vec3d(-Math.sin(yaw), 0.0, Math.cos(yaw)).normalize();
        }

        private static Vec3d horizontalDirection(Vec3d direction) {
            Vec3d horizontal = new Vec3d(direction.x, 0.0, direction.z);
            return horizontal.lengthSquared() > 1.0E-4 ? horizontal.normalize() : Vec3d.ZERO;
        }
    }
}
