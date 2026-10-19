package jbro.cobblemon.policy.pokemon

import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.world.Difficulty
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import java.util.UUID
import java.util.WeakHashMap

/**
 * The server runs on Peaceful, which stops a hurt wild Pokemon from hitting back four times over: Cobblemon will not take
 * the attacker as its target or enter its fight activity, vanilla `canAttack` refuses players, and `Player.hurt` zeroes
 * mob damage. Those checks see Easy instead of Peaceful only for a wild Pokemon that a player hurt in the last
 * [WINDOW_TICKS], and toward that player only. Nobody else is attacked first, whatever else makes a wild Pokemon angry.
 * Which species retaliate is still Cobblemon's species data (`behaviour.combat`).
 */
object WildRetaliation {
    /** Cobblemon's anger at an attacker lasts about this long too. */
    private const val WINDOW_TICKS = 20L * 30

    private class Hit(val player: UUID, val tick: Long)

    // Server thread only; entries go with their entity.
    private val hits = WeakHashMap<PokemonEntity, Hit>()

    @JvmStatic
    fun isWild(entity: Entity?): Boolean = entity is PokemonEntity && entity.pokemon.isWild()

    /** Called after a wild Pokemon took damage. A Poke Ball hitting it is a capture, not an attack. */
    @JvmStatic
    fun noteHurt(pokemon: PokemonEntity, source: DamageSource) {
        val player = source.entity as? Player ?: return
        if (source.directEntity is EmptyPokeBallEntity || !pokemon.pokemon.isWild()) return
        hits[pokemon] = Hit(player.uuid, pokemon.level().gameTime)
    }

    /** The player [entity] may hit back at now, if any. */
    private fun provoker(entity: Entity?): UUID? {
        if (!isWild(entity)) return null
        val hit = hits[entity as PokemonEntity] ?: return null
        return hit.player.takeIf { entity.level().gameTime - hit.tick <= WINDOW_TICKS }
    }

    /** For Cobblemon's target and fight checks, which do not name the player. */
    @JvmStatic
    fun difficultyFor(entity: Entity?, difficulty: Difficulty): Difficulty =
        if (difficulty == Difficulty.PEACEFUL && provoker(entity) != null) Difficulty.EASY else difficulty

    /** For checks between [entity] and a [target]: only the player who hurt it. */
    @JvmStatic
    fun difficultyFor(entity: Entity?, target: Entity?, difficulty: Difficulty): Difficulty =
        if (difficulty == Difficulty.PEACEFUL && target is Player && provoker(entity) == target.uuid) Difficulty.EASY else difficulty
}
