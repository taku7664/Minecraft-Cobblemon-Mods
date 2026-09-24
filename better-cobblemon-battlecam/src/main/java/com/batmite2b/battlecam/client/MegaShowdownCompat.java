/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.entity.Entity
 *  net.minecraft.util.math.Box
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.BattleViewContext;
import com.batmite2b.battlecam.client.GimmickEvent;
import com.batmite2b.battlecam.client.GimmickKind;
import com.batmite2b.battlecam.client.PokemonVisualSnapshot;
import com.batmite2b.battlecam.client.ReflectionUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;

public final class MegaShowdownCompat {
    private static final long EVENT_COOLDOWN_MS = 1200L;
    private final Map<UUID, PokemonVisualSnapshot> previousSnapshots = new HashMap<UUID, PokemonVisualSnapshot>();
    private final Map<UUID, Long> lastEventAt = new HashMap<UUID, Long>();
    private String lastBattleId = "";

    public void tick(MinecraftClient client, BattleCamState state) {
        if (client.world == null || client.player == null) {
            return;
        }
        if (state.context == BattleViewContext.NONE) {
            this.previousSnapshots.clear();
            this.lastEventAt.clear();
            this.lastBattleId = "";
            return;
        }
        String battleId = ReflectionUtil.normalizedValue(state.activeBattleId);
        if (!battleId.equals(this.lastBattleId)) {
            this.previousSnapshots.clear();
            this.lastEventAt.clear();
            this.lastBattleId = battleId;
        }
        Box box = new Box(client.player.getX() - 96.0, client.player.getY() - 32.0, client.player.getZ() - 96.0, client.player.getX() + 96.0, client.player.getY() + 32.0, client.player.getZ() + 96.0);
        List<Entity> tracked = client.world.getOtherEntities(null, box, entity -> entity != null && !entity.isRemoved() && MegaShowdownCompat.isCobblemonPokemon(entity) && MegaShowdownCompat.isBattling(entity) && ReflectionUtil.entityMatchesBattle(entity, state.activeBattleId));
        HashMap<UUID, PokemonVisualSnapshot> currentSnapshots = new HashMap<UUID, PokemonVisualSnapshot>();
        for (Entity entity2 : tracked) {
            long last;
            long now;
            GimmickKind kind;
            PokemonVisualSnapshot current = PokemonVisualSnapshot.from(entity2);
            currentSnapshots.put(entity2.getUuid(), current);
            PokemonVisualSnapshot previous = this.previousSnapshots.get(entity2.getUuid());
            if (previous == null || !current.visuallyChangedComparedTo(previous) || (kind = this.classify(previous, current)) == null || (now = System.currentTimeMillis()) - (last = this.lastEventAt.getOrDefault(entity2.getUuid(), 0L).longValue()) < 1200L) continue;
            this.lastEventAt.put(entity2.getUuid(), now);
            state.enqueueGimmickEvent(new GimmickEvent(entity2.getUuid(), kind, now));
        }
        this.previousSnapshots.clear();
        this.previousSnapshots.putAll(currentSnapshots);
    }

    private GimmickKind classify(PokemonVisualSnapshot oldState, PokemonVisualSnapshot newState) {
        if (!oldState.megaLike() && newState.megaLike()) {
            return GimmickKind.MEGA;
        }
        if (!oldState.ultraLike() && newState.ultraLike()) {
            return GimmickKind.ULTRA_BURST;
        }
        if (!oldState.teraLike() && newState.teraLike()) {
            return GimmickKind.TERA;
        }
        if (!oldState.dynamaxLike() && newState.dynamaxLike()) {
            return GimmickKind.DYNAMAX;
        }
        return GimmickKind.FORM_CHANGE;
    }

    private static boolean isCobblemonPokemon(Entity entity) {
        return entity.getClass().getName().equals("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
    }

    private static boolean isBattling(Entity entity) {
        Object battling = ReflectionUtil.invokeNoArg(entity, "isBattling");
        if (battling instanceof Boolean) {
            Boolean b = (Boolean)battling;
            return b;
        }
        return ReflectionUtil.invokeNoArg(entity, "getBattleId") != null;
    }
}
