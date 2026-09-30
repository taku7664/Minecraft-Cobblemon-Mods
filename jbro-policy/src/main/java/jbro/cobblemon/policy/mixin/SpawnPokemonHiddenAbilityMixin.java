package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.command.SpawnPokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import jbro.cobblemon.policy.wild.WildPokemonPolicy;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** /spawnpokemon rolls the hidden ability chance too. */
@Mixin(value = SpawnPokemon.class, remap = false)
abstract class SpawnPokemonHiddenAbilityMixin {
    @ModifyArg(method = "execute", at = @At(value = "INVOKE", remap = true,
        target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private Entity jbroPolicy$hiddenAbilityChance(Entity entity) {
        if (entity instanceof PokemonEntity pokemon) WildPokemonPolicy.applyHiddenAbilityChance(pokemon.getPokemon());
        return entity;
    }
}
