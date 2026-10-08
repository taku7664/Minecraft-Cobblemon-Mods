package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.interpreter.Effect;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.interpreter.instructions.CantInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.util.LocalizationUtilsKt;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A move stopped by its target's Queenly Majesty, Dazzling, Armor Tail or Damp. Showdown names the ability's holder
 * first and the Pokemon that tried the move in {@code [of]}; Cobblemon puts the first one in its generic "can't use"
 * line, so the holder was said to be unable to use the attacker's move, and the ability went unmentioned.
 */
@Mixin(value = CantInstruction.class, remap = false)
abstract class CantInstructionMixin {
    private static final Set<String> BLOCKING_ABILITIES = Set.of("queenlymajesty", "dazzling", "armortail", "damp");

    @Redirect(
        method = "postActionEffect$lambda$0",
        // No descriptor: it would name a Minecraft class, which remap = false leaves unmapped in the shipped JAR.
        at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/util/LocalizationUtilsKt;battleLang"),
        require = 0
    )
    private static MutableComponent mcc$nameTheBlockedPokemon(String key, Object[] args, CantInstruction self, PokemonBattle battle) {
        if (!"cant.generic".equals(key) || args.length < 2) return LocalizationUtilsKt.battleLang(key, args);
        BattleMessage message = self.getMessage();
        Effect effect = message.effectAt(1);
        String ability = effect == null ? null : effect.getId();
        if (ability == null || !BLOCKING_ABILITIES.contains(ability)) return LocalizationUtilsKt.battleLang(key, args);
        BattlePokemon attacker = message.battlePokemonFromOptional(battle, "of");
        if (attacker == null) return LocalizationUtilsKt.battleLang(key, args);
        return LocalizationUtilsKt.battleLang("cant.blocked_by_ability",
            new Object[] {attacker.getName(), args[1], args[0], Component.translatable("cobblemon.ability." + ability)});
    }
}
