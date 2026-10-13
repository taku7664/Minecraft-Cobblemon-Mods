package jbro.cobblemon.mcc.internal.mixin.client;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import jbro.cobblemon.mcc.api.battle.ManagedPveBattles;
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173OpponentLevelScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The client reads each battle Pokemon's properties back from a string and clamps `level=` to its own maximum, so a
 * managed opponent above it (Cobblemon173OpponentLevelScope) showed at 100. Every battle Pokemon the client is sent
 * (battle start, switch, replace, transform) is read here; the level the server sent is kept, up to MCC's limit.
 */
@Mixin(BattleInitializePacket.ActiveBattlePokemonDTO.Companion.class)
abstract class BattlePokemonDtoLevelMixin {
    @Redirect(method = "loadFromBuffer", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lcom/cobblemon/mod/common/api/pokemon/PokemonProperties$Companion;parse$default(Lcom/cobblemon/mod/common/api/pokemon/PokemonProperties$Companion;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Lcom/cobblemon/mod/common/api/pokemon/PokemonProperties;"))
    private PokemonProperties mcc$parseAboveMaximum(PokemonProperties.Companion companion, String source, String delimiter,
                                                    String assigner, int mask, Object marker) {
        // Kotlin's defaults for the arguments the call left out (parse$default's mask bits).
        String usedDelimiter = (mask & 2) != 0 ? " " : delimiter;
        String usedAssigner = (mask & 4) != 0 ? "=" : assigner;
        return Cobblemon173OpponentLevelScope.during(ManagedPveBattles.MAX_OPPONENT_LEVEL,
            () -> companion.parse(source, usedDelimiter, usedAssigner));
    }
}
