package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.betterai.engine.dex.Species
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/** Species facts that are public for any Pokemon on the field: weight, whether it can still evolve. */
internal object LocalPublicSpeciesData {
    fun species(pokemon: BattlePokemonStateView): Species? {
        val dex = runCatching { EngineRuntimeDex.current().second }.getOrNull() ?: return null
        val base = PublicIds.canonical(pokemon.speciesId.substringAfter(':'))
        val form = pokemon.formId?.let(PublicIds::canonical).orEmpty()
        return (if (form.isNotEmpty() && form != "normal") dex.species(base + form) else null) ?: dex.species(base)
    }

    fun weightKg(pokemon: BattlePokemonStateView): Double? =
        species(pokemon)?.weighthg?.takeIf { it > 0 }?.let { it / 10.0 }

    fun evolvesFurther(pokemon: BattlePokemonStateView): Boolean = species(pokemon)?.nfe == true
}
