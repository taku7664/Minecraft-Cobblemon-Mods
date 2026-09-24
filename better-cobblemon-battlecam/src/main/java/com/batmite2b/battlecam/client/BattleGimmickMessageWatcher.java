/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.entity.Entity
 *  net.minecraft.text.Text
 *  net.minecraft.util.math.Box
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.GimmickEvent;
import com.batmite2b.battlecam.client.GimmickKind;
import com.batmite2b.battlecam.client.ReflectionUtil;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

public final class BattleGimmickMessageWatcher {
    private static final long LOG_COOLDOWN_MS = 9000L;
    private final Map<String, Long> lastLogEventAt = new HashMap<String, Long>();
    private String trackedBattleId = "";

    public void onBattleMessage(MinecraftClient client, BattleCamState state, String message) {
        String key;
        long last;
        String normalized;
        if (!state.isBattleContextActive() || client.world == null || client.player == null || message == null) {
            return;
        }
        String battleId = ReflectionUtil.normalizedValue(state.activeBattleId);
        if (battleId.isBlank()) {
            return;
        }
        if (!battleId.equals(this.trackedBattleId)) {
            this.trackedBattleId = battleId;
            this.lastLogEventAt.clear();
        }
        if (!BattleGimmickMessageWatcher.isMegaStartingMessage(normalized = BattleGimmickMessageWatcher.normalize(message))) {
            return;
        }
        String pokemonName = BattleGimmickMessageWatcher.extractMegaPokemonName(normalized);
        Entity entity = this.resolvePokemon(client, battleId, pokemonName);
        if (entity == null) {
            return;
        }
        UUID uuid = BattleGimmickMessageWatcher.pokemonOrEntityUuid(entity);
        long now = System.currentTimeMillis();
        if (now - (last = this.lastLogEventAt.getOrDefault(key = battleId + "|" + String.valueOf(uuid) + "|mega", 0L).longValue()) < 9000L) {
            return;
        }
        this.lastLogEventAt.put(key, now);
        state.enqueueGimmickEvent(new GimmickEvent(uuid, GimmickKind.MEGA, now));
    }

    private static boolean isMegaStartingMessage(String message) {
        if (message.contains("has mega evolved") || message.contains("mega evolved into") || message.contains("megaevoluciono") || message.contains("mega evoluciono") || message.contains("megaevolucionado")) {
            return false;
        }
        return message.contains("is reacting to the key stone") || message.contains("esta reaccionando ante la piedra activadora") || message.contains("transformed into its mega form") || message.contains("se ha transformado en su forma mega") || message.contains("is mega evolving") || message.contains("is mega-evolving") || message.contains("mega evolving") || message.contains("mega-evolving") || message.contains("megaevolving") || message.contains("esta megaevolucionando") || message.contains("esta mega evolucionando") || message.contains("megaevolucionando") || message.contains("mega evolucionando") || message.contains("reacting") && message.contains("mega");
    }

    private Entity resolvePokemon(MinecraftClient client, String battleId, String pokemonName) {
        List<Entity> entities = this.currentBattlePokemonEntities(client, battleId);
        if (entities.isEmpty()) {
            return null;
        }
        if (!pokemonName.isBlank()) {
            for (Entity entity : entities) {
                if (!BattleGimmickMessageWatcher.entityNameMatches(entity, pokemonName)) continue;
                return entity;
            }
        }
        return entities.size() == 1 ? entities.get(0) : null;
    }

    private List<Entity> currentBattlePokemonEntities(MinecraftClient client, String battleId) {
        Box box = new Box(client.player.getX() - 96.0, client.player.getY() - 32.0, client.player.getZ() - 96.0, client.player.getX() + 96.0, client.player.getY() + 32.0, client.player.getZ() + 96.0);
        return client.world.getOtherEntities(null, box, entity -> entity != null && !entity.isRemoved() && entity.getClass().getName().equals("com.cobblemon.mod.common.entity.pokemon.PokemonEntity") && ReflectionUtil.entityMatchesBattle(entity, battleId));
    }

    private static UUID pokemonOrEntityUuid(Entity entity) {
        UUID uuid = ReflectionUtil.entityPokemonUuid(entity);
        return uuid != null ? uuid : entity.getUuid();
    }

    private static boolean entityNameMatches(Entity entity, String pokemonName) {
        ArrayList<String> candidates = new ArrayList<String>();
        BattleGimmickMessageWatcher.addNameCandidate(candidates, entity.getName());
        Object pokemon = ReflectionUtil.invokeNoArg(entity, "getPokemon");
        BattleGimmickMessageWatcher.addNameCandidate(candidates, ReflectionUtil.invokeNoArg(pokemon, "getDisplayName"));
        BattleGimmickMessageWatcher.addNameCandidate(candidates, ReflectionUtil.invokeNoArg(pokemon, "getNickname"));
        BattleGimmickMessageWatcher.addNameCandidate(candidates, ReflectionUtil.invokeNoArg(pokemon, "getSpecies"));
        for (String candidate : candidates) {
            if (!candidate.equals(pokemonName) && !candidate.contains(pokemonName) && !pokemonName.contains(candidate)) continue;
            return true;
        }
        return false;
    }

    private static void addNameCandidate(List<String> candidates, Object value) {
        String stringValue;
        if (value == null) {
            return;
        }
        if (value instanceof Text) {
            Text text = (Text)value;
            stringValue = text.getString();
        } else {
            Object reflected = ReflectionUtil.invokeNoArg(value, "getString");
            stringValue = reflected == null ? null : String.valueOf(reflected);
        }
        String normalized = BattleGimmickMessageWatcher.normalize(stringValue != null ? String.valueOf(stringValue) : String.valueOf(value));
        if (!normalized.isBlank()) {
            candidates.add(normalized);
        }
    }

    private static String extractMegaPokemonName(String message) {
        int possessive;
        String name = BattleGimmickMessageWatcher.beforeFirst(message, " is reacting to the key stone", " esta reaccionando ante la piedra activadora", " transformed into its mega form", " se ha transformado en su forma mega", " is mega evolving", " is mega-evolving", " mega evolving", " mega-evolving", " esta megaevolucionando", " esta mega evolucionando", " megaevolucionando", " mega evolucionando");
        if (name.isBlank() && message.contains("reacting") && (possessive = message.indexOf("'s")) > 0) {
            name = message.substring(0, possessive);
        }
        return BattleGimmickMessageWatcher.cleanupName(name);
    }

    private static String beforeFirst(String value, String ... markers) {
        int index = -1;
        for (String marker : markers) {
            int markerIndex = value.indexOf(marker);
            if (markerIndex < 0 || index >= 0 && markerIndex >= index) continue;
            index = markerIndex;
        }
        return index < 0 ? "" : value.substring(0, index);
    }

    private static String cleanupName(String value) {
        String cleaned = value.replace("!", "").replace(".", "").replace("?", "").replace("\u00a1", "").replace(":", "").replace(",", "").trim();
        cleaned = BattleGimmickMessageWatcher.stripPrefix(cleaned, "the opposing ");
        cleaned = BattleGimmickMessageWatcher.stripPrefix(cleaned, "opposing ");
        cleaned = BattleGimmickMessageWatcher.stripPrefix(cleaned, "opponent's ");
        cleaned = BattleGimmickMessageWatcher.stripPrefix(cleaned, "opponents ");
        cleaned = BattleGimmickMessageWatcher.stripPrefix(cleaned, "wild ");
        cleaned = BattleGimmickMessageWatcher.stripPrefix(cleaned, "el ");
        cleaned = BattleGimmickMessageWatcher.stripPrefix(cleaned, "la ");
        return cleaned.trim();
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT).trim(), Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }
}
