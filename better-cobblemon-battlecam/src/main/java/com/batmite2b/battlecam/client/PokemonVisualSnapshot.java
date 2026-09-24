/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.entity.Entity
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.GimmickKind;
import com.batmite2b.battlecam.client.ReflectionUtil;
import java.util.UUID;
import net.minecraft.entity.Entity;

public record PokemonVisualSnapshot(UUID entityUuid, String speciesKey, String formKey, String aspectsKey, double height) {
    public static PokemonVisualSnapshot from(Entity entity) {
        Object pokemon = ReflectionUtil.invokeNoArg(entity, "getPokemon");
        Object species = ReflectionUtil.invokeNoArg(pokemon, "getSpecies");
        Object form = PokemonVisualSnapshot.firstNonNull(ReflectionUtil.invokeNoArg(pokemon, "getForm"), ReflectionUtil.invokeNoArg(pokemon, "getForme"));
        Object aspects = ReflectionUtil.invokeNoArg(pokemon, "getAspects");
        return new PokemonVisualSnapshot(entity.getUuid(), ReflectionUtil.normalizedValue(species), ReflectionUtil.normalizedValue(form), ReflectionUtil.normalizedValue(aspects), entity.getHeight());
    }

    public boolean visuallyChangedComparedTo(PokemonVisualSnapshot previous) {
        if (previous == null) {
            return false;
        }
        return !this.formKey.equals(previous.formKey) || !this.aspectsKey.equals(previous.aspectsKey) || Math.abs(this.height - previous.height) > 0.45;
    }

    public boolean megaLike() {
        return ReflectionUtil.containsAny(this.formKey, "mega", "primal") || ReflectionUtil.containsAny(this.aspectsKey, "mega", "primal");
    }

    public boolean ultraLike() {
        return ReflectionUtil.containsAny(this.formKey, "ultra", "burst") || ReflectionUtil.containsAny(this.aspectsKey, "ultra", "burst");
    }

    public boolean teraLike() {
        return ReflectionUtil.containsAny(this.formKey, "tera", "terastal", "terastall") || ReflectionUtil.containsAny(this.aspectsKey, "tera", "terastal", "terastall", "stellar");
    }

    public boolean dynamaxLike() {
        return ReflectionUtil.containsAny(this.formKey, "dmax", "gmax", "dynamax", "gigantamax", "gigamax") || ReflectionUtil.containsAny(this.aspectsKey, "dmax", "gmax", "dynamax", "gigantamax", "gigamax") || this.height > 4.5;
    }

    public GimmickKind activeGimmickKind() {
        if (this.megaLike()) {
            return GimmickKind.MEGA;
        }
        if (this.ultraLike()) {
            return GimmickKind.ULTRA_BURST;
        }
        if (this.teraLike()) {
            return GimmickKind.TERA;
        }
        if (this.dynamaxLike()) {
            return GimmickKind.DYNAMAX;
        }
        return null;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }
}
