package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173;

import com.cobblemon.mod.common.pokemon.OriginalTrainerType;
import com.cobblemon.mod.common.pokemon.Pokemon;

/** Marks generated opponents as NPC-owned and suppresses held-item rendering and drops. */
final class Cobblemon173OpponentPokemonSafety {
    private Cobblemon173OpponentPokemonSafety() {
    }

    static void apply(Pokemon pokemon) {
        apply(new Cobblemon173OpponentPokemonSafetyTarget() {
            @Override
            public void markNpcOwned() {
                pokemon.setOriginalTrainerType$common(OriginalTrainerType.NPC);
            }

            @Override
            public void setHeldItemVisible(boolean visible) {
                pokemon.setHeldItemVisible(visible);
            }

            @Override
            public void setCanDropHeldItem(boolean canDrop) {
                pokemon.setCanDropHeldItem$common(canDrop);
            }
        });
    }

    static void apply(Cobblemon173OpponentPokemonSafetyTarget target) {
        target.markNpcOwned();
        target.setHeldItemVisible(false);
        target.setCanDropHeldItem(false);
    }
}
