/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.entity.Entity
 *  net.minecraft.util.math.Box
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleActionEvent;
import com.batmite2b.battlecam.client.BattleActionKind;
import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.ReflectionUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;

public final class BattleSwitchWatcher {
    private static final long SWITCH_LOG_COOLDOWN_MS = 1800L;
    private static final long INITIAL_SETTLE_MS = 2500L;
    private static final long FALLBACK_ENTITY_COOLDOWN_MS = 6000L;
    private static final long FALLBACK_COOLDOWN_PRUNE_MS = 30000L;
    private String lastSwitchKey = "";
    private long lastSwitchAtMs = 0L;
    private String trackedBattleId = "";
    private long initialPokemonSeenAtMs = 0L;
    private boolean fallbackInitialized = false;
    private final Set<UUID> knownPokemon = new HashSet<UUID>();
    private final Map<UUID, Long> fallbackSwitchAtByUuid = new HashMap<UUID, Long>();

    public void tick(MinecraftClient client, BattleCamState state) {
        if (!state.isBattleContextActive() || client.world == null || client.player == null) {
            this.resetFallbackTracking();
            return;
        }
        String battleId = ReflectionUtil.normalizedValue(state.activeBattleId);
        if (battleId.isBlank()) {
            this.resetFallbackTracking();
            return;
        }
        long now = System.currentTimeMillis();
        if (!battleId.equals(this.trackedBattleId)) {
            this.trackedBattleId = battleId;
            this.initialPokemonSeenAtMs = 0L;
            this.fallbackInitialized = false;
            this.knownPokemon.clear();
            this.fallbackSwitchAtByUuid.clear();
            this.lastSwitchKey = "";
            this.lastSwitchAtMs = 0L;
        }
        List<Entity> entities = this.currentBattlePokemonEntities(client, battleId);
        HashSet<UUID> currentPokemon = new HashSet<UUID>();
        for (Entity entity : entities) {
            UUID uuid = BattleSwitchWatcher.pokemonOrEntityUuid(entity);
            if (uuid == null) continue;
            currentPokemon.add(uuid);
        }
        if (currentPokemon.isEmpty()) {
            this.knownPokemon.clear();
            return;
        }
        if (!this.fallbackInitialized) {
            this.knownPokemon.clear();
            this.knownPokemon.addAll(currentPokemon);
            this.initialPokemonSeenAtMs = now;
            this.fallbackInitialized = true;
            return;
        }
        if (now - this.initialPokemonSeenAtMs < 2500L) {
            this.knownPokemon.clear();
            this.knownPokemon.addAll(currentPokemon);
            return;
        }
        this.fallbackSwitchAtByUuid.entrySet().removeIf(entry -> now - (Long)entry.getValue() > 30000L);
        for (UUID uuid : currentPokemon) {
            long lastFallbackAt;
            if (this.knownPokemon.contains(uuid) || now - (lastFallbackAt = this.fallbackSwitchAtByUuid.getOrDefault(uuid, 0L).longValue()) < 6000L) continue;
            this.fallbackSwitchAtByUuid.put(uuid, now);
            this.lastSwitchKey = battleId + "|" + String.valueOf(uuid);
            this.lastSwitchAtMs = now;
            state.enqueueActionEvent(new BattleActionEvent(uuid, BattleActionKind.SWITCH, now, 2600L));
            break;
        }
        this.knownPokemon.clear();
        this.knownPokemon.addAll(currentPokemon);
    }

    public void onBattleMessage(MinecraftClient client, BattleCamState state, String message) {
        if (!state.isBattleContextActive() || client.world == null || client.player == null || message == null) {
            return;
        }
        String normalized = BattleSwitchWatcher.normalize(message);
        if (!normalized.contains("come back")) {
            return;
        }
        String pokemonName = BattleSwitchWatcher.extractComeBackName(normalized);
        Entity entity = this.resolveReturningPokemon(client, state.activeBattleId, pokemonName);
        if (entity == null) {
            return;
        }
        UUID uuid = BattleSwitchWatcher.pokemonOrEntityUuid(entity);
        long now = System.currentTimeMillis();
        String key = ReflectionUtil.normalizedValue(state.activeBattleId) + "|" + String.valueOf(uuid);
        if (key.equals(this.lastSwitchKey) && now - this.lastSwitchAtMs < 1800L) {
            return;
        }
        this.lastSwitchKey = key;
        this.lastSwitchAtMs = now;
        this.knownPokemon.add(uuid);
        state.enqueueActionEvent(new BattleActionEvent(uuid, BattleActionKind.SWITCH, now, 3200L));
    }

    private void resetFallbackTracking() {
        this.trackedBattleId = "";
        this.initialPokemonSeenAtMs = 0L;
        this.fallbackInitialized = false;
        this.knownPokemon.clear();
        this.fallbackSwitchAtByUuid.clear();
        this.lastSwitchKey = "";
        this.lastSwitchAtMs = 0L;
    }

    private Entity resolveReturningPokemon(MinecraftClient client, String battleId, String pokemonName) {
        List<Entity> entities = this.currentBattlePokemonEntities(client, battleId);
        if (entities.isEmpty()) {
            return null;
        }
        if (!pokemonName.isBlank()) {
            for (Entity entity : entities) {
                if (!BattleSwitchWatcher.entityNameMatches(entity, pokemonName)) continue;
                return entity;
            }
        }
        return entities.size() == 1 ? entities.get(0) : null;
    }

    private static UUID pokemonOrEntityUuid(Entity entity) {
        UUID uuid = ReflectionUtil.entityPokemonUuid(entity);
        return uuid != null ? uuid : entity.getUuid();
    }

    private List<Entity> currentBattlePokemonEntities(MinecraftClient client, String battleId) {
        Box box = new Box(client.player.getX() - 96.0, client.player.getY() - 32.0, client.player.getZ() - 96.0, client.player.getX() + 96.0, client.player.getY() + 32.0, client.player.getZ() + 96.0);
        return client.world.getOtherEntities(null, box, entity -> entity != null && !entity.isRemoved() && entity.getClass().getName().equals("com.cobblemon.mod.common.entity.pokemon.PokemonEntity") && ReflectionUtil.entityMatchesBattle(entity, battleId));
    }

    private static boolean entityNameMatches(Entity entity, String pokemonName) {
        ArrayList<String> candidates = new ArrayList<String>();
        BattleSwitchWatcher.addNameCandidate(candidates, entity.getName());
        Object pokemon = ReflectionUtil.invokeNoArg(entity, "getPokemon");
        BattleSwitchWatcher.addNameCandidate(candidates, ReflectionUtil.invokeNoArg(pokemon, "getDisplayName"));
        BattleSwitchWatcher.addNameCandidate(candidates, ReflectionUtil.invokeNoArg(pokemon, "getNickname"));
        BattleSwitchWatcher.addNameCandidate(candidates, ReflectionUtil.invokeNoArg(pokemon, "getSpecies"));
        for (String candidate : candidates) {
            if (!candidate.equals(pokemonName) && !candidate.contains(pokemonName) && !pokemonName.contains(candidate)) continue;
            return true;
        }
        return false;
    }

    private static void addNameCandidate(List<String> candidates, Object value) {
        if (value == null) {
            return;
        }
        Object stringValue = ReflectionUtil.invokeNoArg(value, "getString");
        String normalized = BattleSwitchWatcher.normalize(stringValue != null ? String.valueOf(stringValue) : String.valueOf(value));
        if (!normalized.isBlank()) {
            candidates.add(normalized);
        }
    }

    private static String extractComeBackName(String normalizedMessage) {
        int comma = normalizedMessage.indexOf(44);
        int comeBack = normalizedMessage.indexOf("come back");
        String name = comma >= 0 && comma < comeBack ? normalizedMessage.substring(0, comma) : (comma >= 0 && comma > comeBack ? normalizedMessage.substring(comma + 1) : normalizedMessage.replace("come back", ""));
        return BattleSwitchWatcher.cleanupName(name);
    }

    private static String cleanupName(String value) {
        return value.replace("!", "").replace(".", "").replace("?", "").replace(":", "").trim();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).trim();
    }
}
