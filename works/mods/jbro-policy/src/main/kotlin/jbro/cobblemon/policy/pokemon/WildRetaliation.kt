package jbro.cobblemon.policy.pokemon

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.Entity

/**
 * The server runs on Peaceful, which stops a hurt wild Pokemon from hitting back four times over: Cobblemon will not take
 * the attacker as its target or enter its fight activity, vanilla `canAttack` refuses players, and `Player.hurt` zeroes
 * mob damage. For wild Pokemon alone, those checks see Easy instead of Peaceful; the rest of Peaceful stays as it is.
 * Which species retaliate is still Cobblemon's species data (`behaviour.combat`).
 */
object WildRetaliation {
    @JvmStatic
    fun isWild(entity: Entity?): Boolean = entity is PokemonEntity && entity.pokemon.isWild()

    /** The difficulty a retaliation check should see when [entity] is the one doing it. */
    @JvmStatic
    fun difficultyFor(entity: Entity?, difficulty: Difficulty): Difficulty =
        if (difficulty == Difficulty.PEACEFUL && isWild(entity)) Difficulty.EASY else difficulty
}
