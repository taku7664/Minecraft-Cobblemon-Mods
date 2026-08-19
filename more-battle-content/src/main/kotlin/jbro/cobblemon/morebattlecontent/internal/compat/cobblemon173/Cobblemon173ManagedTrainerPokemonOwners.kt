package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID
import net.minecraft.world.entity.LivingEntity

/** Resolves generated NPC battle Pokemon to their temporary trainer entity for one battle lifetime. */
internal object Cobblemon173ManagedTrainerPokemonOwners {
    private val owners = ManagedOwnerRegistry<UUID, LivingEntity>()

    fun register(trainer: LivingEntity, team: Collection<BattlePokemon>): AutoCloseable =
        owners.register(
            owner = trainer,
            keys = team.flatMap { battlePokemon ->
                listOf(battlePokemon.originalPokemon.uuid, battlePokemon.effectedPokemon.uuid)
            },
        )

    @JvmStatic
    fun resolve(pokemon: Pokemon): LivingEntity? = owners.resolve(pokemon.uuid)
}
