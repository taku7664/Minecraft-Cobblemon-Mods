/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.entity.Entity
 */
package com.batmite2b.battlecam.client;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.entity.Entity;

public final class ReflectionUtil {
    private ReflectionUtil() {
    }

    public static Object invokeNoArg(Object target, String ... methodNames) {
        if (target == null) {
            return null;
        }
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName, new Class[0]);
                method.setAccessible(true);
                return method.invoke(target, new Object[0]);
            }
            catch (Exception exception) {
            }
        }
        return null;
    }

    public static String normalizedValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Iterable) {
            Iterable iterable = (Iterable)value;
            ArrayList<String> parts = new ArrayList<String>();
            for (Object element : iterable) {
                String s = ReflectionUtil.normalizedValue(element);
                if (s.isBlank()) continue;
                parts.add(s);
            }
            Collections.sort(parts);
            return String.join((CharSequence)"|", parts);
        }
        return String.valueOf(value).toLowerCase(Locale.ROOT).trim();
    }

    public static String entityBattleId(Entity entity) {
        return ReflectionUtil.normalizedValue(ReflectionUtil.invokeNoArg(entity, "getBattleId"));
    }

    public static UUID entityPokemonUuid(Entity entity) {
        UUID value;
        Object pokemon = ReflectionUtil.invokeNoArg(entity, "getPokemon");
        Object uuid = ReflectionUtil.invokeNoArg(pokemon, "getUuid");
        return uuid instanceof UUID ? (value = (UUID)uuid) : null;
    }

    public static boolean entityMatchesEntityOrPokemonUuid(Entity entity, UUID uuid) {
        if (entity == null || uuid == null) {
            return false;
        }
        if (uuid.equals(entity.getUuid())) {
            return true;
        }
        return uuid.equals(ReflectionUtil.entityPokemonUuid(entity));
    }

    public static boolean entityMatchesBattle(Entity entity, String battleId) {
        String expected = ReflectionUtil.normalizedValue(battleId);
        if (expected.isBlank()) {
            return true;
        }
        return expected.equals(ReflectionUtil.entityBattleId(entity));
    }

    public static boolean containsAny(String source, String ... needles) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (!normalized.contains(needle.toLowerCase(Locale.ROOT))) continue;
            return true;
        }
        return false;
    }
}
