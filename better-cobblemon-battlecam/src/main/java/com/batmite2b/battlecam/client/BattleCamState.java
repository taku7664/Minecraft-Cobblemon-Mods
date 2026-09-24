/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.client.network.AbstractClientPlayerEntity
 *  net.minecraft.entity.Entity
 *  net.minecraft.text.Text
 *  net.minecraft.util.math.Vec3d
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleActionEvent;
import com.batmite2b.battlecam.client.BattleActionKind;
import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleCameraDirector;
import com.batmite2b.battlecam.client.BattleCameraRig;
import com.batmite2b.battlecam.client.BattleViewContext;
import com.batmite2b.battlecam.client.GimmickEvent;
import com.batmite2b.battlecam.client.GimmickKind;
import com.batmite2b.battlecam.client.PokemonVisualSnapshot;
import com.batmite2b.battlecam.client.ReflectionUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jbro.cobblemon.battlecam.BattlecamModePolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public final class BattleCamState {
    public BattleViewContext context = BattleViewContext.NONE;
    public String activeBattleId = "";
    public Mode mode;
    public String activeShotId;
    public boolean showOwnBody;
    private boolean ownBattleUiSeen;
    private boolean ownBattleCameraPaused;
    private Vec3d cinematicCameraPos;
    private Vec3d cinematicFocusPos;
    private Set<UUID> cinematicFocusUuids;
    private boolean cinematicHideBattlePokemon;
    private boolean cinematicHideDynamaxPokemon;
    private final ArrayDeque<GimmickEvent> gimmickQueue;
    private final ArrayDeque<BattleActionEvent> actionQueue;
    private long lastMegaAt;
    private long lastUltraAt;
    private long lastTeraAt;
    private long lastDynamaxAt;
    private long lastFormChangeAt;
    private String lastQueuedGimmickKey;
    private long lastQueuedGimmickAt;
    private String lastQueuedActionKey;
    private long lastQueuedActionAt;
    public final BattleCameraRig rig;
    private final BattleCameraDirector director;

    public BattleCamState() {
        this.mode = BattleCamClient.CONFIG.defaultMode;
        this.activeShotId = "establish_wide";
        this.showOwnBody = true;
        this.ownBattleUiSeen = false;
        this.ownBattleCameraPaused = false;
        this.cinematicCameraPos = null;
        this.cinematicFocusPos = null;
        this.cinematicFocusUuids = Set.of();
        this.cinematicHideBattlePokemon = false;
        this.cinematicHideDynamaxPokemon = false;
        this.gimmickQueue = new ArrayDeque<>();
        this.actionQueue = new ArrayDeque<>();
        this.lastMegaAt = 0L;
        this.lastUltraAt = 0L;
        this.lastTeraAt = 0L;
        this.lastDynamaxAt = 0L;
        this.lastFormChangeAt = 0L;
        this.lastQueuedGimmickKey = "";
        this.lastQueuedGimmickAt = 0L;
        this.lastQueuedActionKey = "";
        this.lastQueuedActionAt = 0L;
        this.rig = new BattleCameraRig();
        this.director = new BattleCameraDirector();
    }

    public void setContext(BattleViewContext newContext) {
        this.setContext(newContext, "");
    }

    public void setContext(BattleViewContext newContext, String newBattleId) {
        String normalizedBattleId = ReflectionUtil.normalizedValue(newBattleId);
        if (this.context == newContext && this.activeBattleId.equals(normalizedBattleId)) {
            return;
        }
        this.context = newContext;
        this.activeBattleId = newContext == BattleViewContext.NONE ? "" : normalizedBattleId;
        this.director.reset();
        this.rig.reset();
        this.gimmickQueue.clear();
        this.actionQueue.clear();
        this.clearCinematicFocus();
        this.ownBattleUiSeen = false;
        this.ownBattleCameraPaused = false;
        this.activeShotId = this.director.getCurrentShotId();
        this.lastMegaAt = 0L;
        this.lastUltraAt = 0L;
        this.lastTeraAt = 0L;
        this.lastDynamaxAt = 0L;
        this.lastFormChangeAt = 0L;
        this.lastQueuedGimmickKey = "";
        this.lastQueuedGimmickAt = 0L;
        this.lastQueuedActionKey = "";
        this.lastQueuedActionAt = 0L;
        switch (newContext) {
            case NONE: {
                this.mode = configuredModeFor(newContext);
                this.showOwnBody = true;
                break;
            }
            case SPECTATING: {
                this.mode = configuredModeFor(newContext);
                this.showOwnBody = true;
                break;
            }
            case OWN_BATTLE: {
                this.mode = configuredModeFor(newContext);
                this.showOwnBody = true;
            }
        }
    }

    public void applyConfiguredMode() {
        if (this.context != BattleViewContext.NONE) {
            this.mode = configuredModeFor(this.context);
        }
    }

    private static Mode configuredModeFor(BattleViewContext context) {
        return BattlecamModePolicy.modeForCurrentBattle(
            context,
            BattleCamClient.CONFIG.defaultModeFor(context)
        );
    }

    public void tick(MinecraftClient client) {
        if (!this.isBattleContextActive() || this.mode == Mode.OFF || this.isCameraTemporarilyPaused()) {
            this.rig.active = false;
            return;
        }
        this.director.update(client, this, this.rig);
        this.activeShotId = this.director.getCurrentShotId();
    }

    public void updateOwnBattleUiPause(boolean battleScreenOpen) {
        if (this.context != BattleViewContext.OWN_BATTLE || !BattleCamClient.CONFIG.pauseOwnBattleCameraWhenBattleUiCloses) {
            this.ownBattleUiSeen = false;
            this.ownBattleCameraPaused = false;
            return;
        }
        if (battleScreenOpen) {
            this.ownBattleUiSeen = true;
            this.ownBattleCameraPaused = false;
            return;
        }
        this.ownBattleCameraPaused = this.ownBattleUiSeen;
    }

    public boolean isCameraTemporarilyPaused() {
        return this.ownBattleCameraPaused;
    }

    public boolean isBattleContextActive() {
        return this.context != BattleViewContext.NONE;
    }

    public boolean shouldOverrideCamera() {
        return this.isBattleContextActive() && this.mode != Mode.OFF && !this.isCameraTemporarilyPaused() && this.rig.active;
    }

    public boolean shouldShowHud() {
        return this.isBattleContextActive() && this.mode != Mode.OFF && !this.isCameraTemporarilyPaused();
    }

    public boolean shouldHideOwnBody() {
        return this.context == BattleViewContext.OWN_BATTLE && this.shouldOverrideCamera() && !this.showOwnBody;
    }

    public void cycleMode() {
        this.mode = switch (this.mode.ordinal()) {
            default -> throw new MatchException(null, null);
            case 0 -> Mode.AUTO;
            case 1 -> Mode.MANUAL;
            case 2 -> Mode.OFF;
        };
    }

    public void toggleOwnBody() {
        if (this.context != BattleViewContext.OWN_BATTLE) {
            return;
        }
        this.showOwnBody = !this.showOwnBody;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage((Text)Text.literal((String)(this.showOwnBody ? "BattleCam body view: ON" : "BattleCam body view: OFF")), true);
        }
    }

    public void nextShot() {
        this.director.nextShot();
        this.activeShotId = this.director.getCurrentShotId();
    }

    public void previousShot() {
        this.director.previousShot();
        this.activeShotId = this.director.getCurrentShotId();
    }

    public void enqueueGimmickEvent(GimmickEvent event) {
        if (!this.isBattleContextActive()) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = event.kind().name();
        if (event.entityUuid() != null) {
            key = key + "|" + String.valueOf(event.entityUuid());
        }
        if (key.equals(this.lastQueuedGimmickKey) && now - this.lastQueuedGimmickAt < 1200L) {
            return;
        }
        this.lastQueuedGimmickKey = key;
        this.lastQueuedGimmickAt = now;
        this.gimmickQueue.offerLast(event);
    }

    public boolean wasGimmickTriggeredRecently(GimmickKind kind, long cooldownMs) {
        long now = System.currentTimeMillis();
        long last = switch (kind) {
            default -> throw new MatchException(null, null);
            case GimmickKind.MEGA -> this.lastMegaAt;
            case GimmickKind.ULTRA_BURST -> this.lastUltraAt;
            case GimmickKind.TERA -> this.lastTeraAt;
            case GimmickKind.DYNAMAX -> this.lastDynamaxAt;
            case GimmickKind.FORM_CHANGE -> this.lastFormChangeAt;
        };
        return now - last < cooldownMs;
    }

    public void markGimmickTriggeredNow(GimmickKind kind) {
        long now = System.currentTimeMillis();
        switch (kind) {
            case MEGA: {
                this.lastMegaAt = now;
                break;
            }
            case ULTRA_BURST: {
                this.lastUltraAt = now;
                break;
            }
            case TERA: {
                this.lastTeraAt = now;
                break;
            }
            case DYNAMAX: {
                this.lastDynamaxAt = now;
                break;
            }
            case FORM_CHANGE: {
                this.lastFormChangeAt = now;
            }
        }
    }

    public GimmickEvent pollGimmickEvent() {
        long now = System.currentTimeMillis();
        Iterator<GimmickEvent> iterator = this.gimmickQueue.iterator();
        while (iterator.hasNext()) {
            GimmickEvent event = iterator.next();
            if (event.timestampMs() > now) continue;
            iterator.remove();
            return event;
        }
        return null;
    }

    public void deferGimmickEvent(GimmickEvent event) {
        if (event == null) {
            return;
        }
        this.gimmickQueue.offerFirst(event);
    }

    public void notifyPokemonCry(UUID entityUuid, UUID pokemonUuid) {
        this.director.onPokemonCry(entityUuid, pokemonUuid);
    }

    public void enqueueActionEvent(BattleActionEvent event) {
        if (!this.isBattleContextActive() || event.entityUuid() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = event.kind().name() + "|" + String.valueOf(event.entityUuid());
        long cooldownMs = switch (event.kind()) {
            case DAMAGE -> 450L;
            case FAINT -> 1400L;
            case MOVE -> 900L;
            case SWITCH -> 1800L;
        };
        if (key.equals(this.lastQueuedActionKey) && now - this.lastQueuedActionAt < cooldownMs) {
            return;
        }
        this.lastQueuedActionKey = key;
        this.lastQueuedActionAt = now;
        while (this.actionQueue.size() >= 4) {
            this.actionQueue.pollFirst();
        }
        this.actionQueue.offerLast(event);
    }

    public BattleActionEvent pollActionEvent() {
        BattleActionEvent damage = this.pollDamageEventGroup();
        if (damage != null) {
            return damage;
        }
        BattleActionEvent faint = this.pollFirstActionOfKind(BattleActionKind.FAINT);
        if (faint != null) {
            return faint;
        }
        return this.actionQueue.pollFirst();
    }

    public BattleActionEvent pollDamageEventGroup() {
        BattleActionEvent first = this.pollFirstActionOfKind(BattleActionKind.DAMAGE);
        if (first == null) {
            return null;
        }
        ArrayList<UUID> uuids = new ArrayList<UUID>(first.entityUuids());
        long timestamp = first.timestampMs();
        long duration = first.durationMs();
        Iterator<BattleActionEvent> iterator = this.actionQueue.iterator();
        while (iterator.hasNext()) {
            BattleActionEvent event = iterator.next();
            if (event.kind() != BattleActionKind.DAMAGE || Math.abs(event.timestampMs() - timestamp) > 650L) continue;
            for (UUID uuid : event.entityUuids()) {
                if (uuids.contains(uuid)) continue;
                uuids.add(uuid);
            }
            duration = Math.max(duration, event.durationMs());
            timestamp = Math.min(timestamp, event.timestampMs());
            iterator.remove();
        }
        if (uuids.size() > 1) {
            duration = Math.max(duration, 2400L);
        }
        return new BattleActionEvent(uuids, BattleActionKind.DAMAGE, timestamp, duration);
    }

    private BattleActionEvent pollFirstActionOfKind(BattleActionKind kind) {
        Iterator<BattleActionEvent> iterator = this.actionQueue.iterator();
        while (iterator.hasNext()) {
            BattleActionEvent event = iterator.next();
            if (event.kind() != kind) continue;
            iterator.remove();
            return event;
        }
        return null;
    }

    public void setCinematicFocus(Vec3d cameraPos, Vec3d focusPos, UUID focusUuid) {
        this.setCinematicFocus(cameraPos, focusPos, focusUuid == null ? List.of() : List.of(focusUuid));
    }

    public void setCinematicFocus(Vec3d cameraPos, Vec3d focusPos, List<UUID> focusUuids) {
        this.setCinematicFocus(cameraPos, focusPos, focusUuids, false);
    }

    public void setCinematicFocus(Vec3d cameraPos, Vec3d focusPos, UUID focusUuid, boolean hideBattlePokemon) {
        this.setCinematicFocus(cameraPos, focusPos, focusUuid == null ? List.of() : List.of(focusUuid), hideBattlePokemon);
    }

    public void setCinematicFocus(Vec3d cameraPos, Vec3d focusPos, List<UUID> focusUuids, boolean hideBattlePokemon) {
        this.setCinematicFocus(cameraPos, focusPos, focusUuids, hideBattlePokemon, false);
    }

    public void setCinematicFocus(Vec3d cameraPos, Vec3d focusPos, List<UUID> focusUuids, boolean hideBattlePokemon, boolean hideDynamaxPokemon) {
        this.cinematicCameraPos = cameraPos;
        this.cinematicFocusPos = focusPos;
        this.cinematicFocusUuids = focusUuids == null || focusUuids.isEmpty() ? Set.of() : new HashSet<UUID>(focusUuids);
        this.cinematicHideBattlePokemon = hideBattlePokemon;
        this.cinematicHideDynamaxPokemon = hideDynamaxPokemon;
    }

    public void clearCinematicFocus() {
        this.cinematicCameraPos = null;
        this.cinematicFocusPos = null;
        this.cinematicFocusUuids = Set.of();
        this.cinematicHideBattlePokemon = false;
        this.cinematicHideDynamaxPokemon = false;
    }

    public boolean shouldHideObstructingEntity(Entity entity) {
        double radius;
        if (!this.shouldOverrideCamera() || entity == null || entity.isRemoved() || this.cinematicCameraPos == null || this.cinematicFocusPos == null) {
            return false;
        }
        for (UUID focusUuid : this.cinematicFocusUuids) {
            if (!ReflectionUtil.entityMatchesEntityOrPokemonUuid(entity, focusUuid)) continue;
            return false;
        }
        boolean pokemon = entity.getClass().getName().equals("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
        boolean player = entity instanceof AbstractClientPlayerEntity;
        if (!pokemon && !player) {
            return false;
        }
        if (pokemon && !ReflectionUtil.entityMatchesBattle(entity, this.activeBattleId)) {
            return false;
        }
        if (this.cinematicHideBattlePokemon && pokemon) {
            return true;
        }
        if (this.cinematicHideDynamaxPokemon && pokemon && PokemonVisualSnapshot.from(entity).dynamaxLike()) {
            return true;
        }
        Vec3d sight = this.cinematicFocusPos.subtract(this.cinematicCameraPos);
        double sightLengthSquared = sight.lengthSquared();
        if (sightLengthSquared < 1.0E-4) {
            return false;
        }
        Vec3d entityCenter = entity.getBoundingBox().getCenter();
        double t = entityCenter.subtract(this.cinematicCameraPos).dotProduct(sight) / sightLengthSquared;
        if (t <= 0.08 || t >= 0.94) {
            return false;
        }
        Vec3d closest = this.cinematicCameraPos.add(sight.multiply(t));
        double distanceSquared = entityCenter.squaredDistanceTo(closest);
        return distanceSquared <= (radius = Math.max(0.55, Math.max((double)entity.getWidth() * 0.75, (double)entity.getHeight() * 0.28))) * radius;
    }

    public String getHudText() {
        String source = switch (this.context) {
            case SPECTATING -> "Spectate";
            case OWN_BATTLE -> "Self";
            case NONE -> "Idle";
        };
        String bodyInfo = this.context == BattleViewContext.OWN_BATTLE ? " | Body: " + (this.showOwnBody ? "ON" : "OFF") : "";
        return "BattleCam: " + source + " | " + this.mode.name() + " | Shot: " + this.activeShotId + " | Camera: " + (this.isCameraTemporarilyPaused() ? "PAUSED" : (this.rig.active ? "LIVE" : "WAIT")) + bodyInfo;
    }

    public static enum Mode {
        OFF,
        AUTO,
        MANUAL;

    }
}
