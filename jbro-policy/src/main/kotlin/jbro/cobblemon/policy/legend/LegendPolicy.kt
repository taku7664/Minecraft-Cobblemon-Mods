package jbro.cobblemon.policy.legend

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawnerFactory
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID
import jbro.cobblemon.policy.JbroPolicy
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * Wild Legends belong to the player whose spawner made them, or who placed the Poke Snack that drew them: only that
 * player may battle or catch them. A Legend
 * appears only for players who have not caught it yet and who carry its entry Pokemon; since Cobblenav lists spawns
 * through the same spawner check, the Pokenav's spawn list follows along. Catching also needs the Legend's League rank.
 */
object LegendPolicy {
    private const val OWNER_KEY = "jbro_policy_legend_owner"
    private const val KEY = "message.${JbroPolicy.MOD_ID}.legend."

    fun register() {
        PlayerSpawnerFactory.influenceBuilders.add { player -> SpawnFilter(player) }
        // Lowest, so a spawn another mod cancels is neither claimed nor announced.
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.LOWEST) { event ->
            if (event.isCanceled) return@subscribe
            val player = event.spawnablePosition.cause.entity as? ServerPlayer ?: return@subscribe
            claim(event.entity.pokemon, player)
        }
        // A Poke Snack's Legend belongs to whoever placed the snack.
        CobblemonEvents.POKE_SNACK_SPAWN_POKEMON_POST.subscribe { event ->
            val placer = event.pokeSnackBlockEntity.placedBy ?: return@subscribe
            val level = event.pokeSnackBlockEntity.level ?: return@subscribe
            val player = level.server?.playerList?.getPlayer(placer) ?: return@subscribe
            claim(event.pokemonEntity.pokemon, player)
        }
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe { event ->
            val battle = event.battle
            val players = battle.playerUUIDs.toSet()
            val claimed = battle.actors.filter { it.type == ActorType.WILD }
                .flatMap { actor -> actor.pokemonList.map { it.originalPokemon } }
                .any { pokemon -> ownerOf(pokemon)?.let { it !in players } == true }
            if (claimed) {
                event.cancel()
                battle.players.forEach { deny(it, Component.translatable(KEY + "other_trainer")) }
            }
        }
        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe { event ->
            val thrower = event.pokeBall.owner as? ServerPlayer ?: return@subscribe
            val pokemon = event.pokemon.pokemon
            val reason = captureDenial(thrower, pokemon) ?: return@subscribe
            event.cancel()
            deny(thrower, reason)
        }
        CobblemonEvents.POKEMON_CAPTURED.subscribe { event ->
            val legend = legendOf(event.pokemon) ?: return@subscribe
            event.pokemon.persistentData.remove(OWNER_KEY)
            LegendRecords.get(event.player.server).add(event.player.uuid, legend.id)
        }
    }

    /** Why [player] may not catch [pokemon], or null when they may. */
    private fun captureDenial(player: ServerPlayer, pokemon: Pokemon): Component? {
        val legend = legendOf(pokemon) ?: return null
        val owner = ownerOf(pokemon)
        if (owner != null && owner != player.uuid) return Component.translatable(KEY + "other_trainer")
        if (LegendRecords.get(player.server).has(player.uuid, legend.id)) return Component.translatable(KEY + "already_caught")
        return when (val verdict = LegendRanks.check(player, legend.rank)) {
            LegendRanks.Verdict.Allowed -> null
            LegendRanks.Verdict.Unknown -> Component.translatable(KEY + "rank_unknown")
            is LegendRanks.Verdict.TooLow -> Component.translatable(KEY + "rank_too_low", LegendRanks.name(verdict.needed))
        }
    }

    /** Whether [legend] may spawn around [player]: not caught by them yet, and its entry Pokemon is in their party. */
    internal fun mayMeet(player: ServerPlayer, legend: Legend): Boolean {
        if (LegendRecords.get(player.server).has(player.uuid, legend.id)) return false
        if (legend.entry.isEmpty()) return true
        return legend.entryMet(Cobblemon.storage.getParty(player).mapTo(mutableSetOf()) { it.species.resourceIdentifier.path })
    }

    /** Makes [player] the owner of a freshly spawned Legend and announces it; anything else, or an owned one, is left alone. */
    internal fun claim(pokemon: Pokemon, player: ServerPlayer) {
        val legend = legendOf(pokemon) ?: return
        if (ownerOf(pokemon) != null) return
        pokemon.persistentData.putUUID(OWNER_KEY, player.uuid)
        player.server.playerList.broadcastSystemMessage(Component.translatable(
            "legend.${JbroPolicy.MOD_ID}.appeared.${legend.id}", player.displayName, Component.translatable(legend.nameKey),
        ).withStyle(ChatFormatting.LIGHT_PURPLE), false)
    }

    /**
     * For the Poke Snack mixin: whether a snack placed by [placer] may offer [pokemon]. A Legend needs its placer
     * online, because the entry rule reads their party.
     */
    @JvmStatic
    fun snackMayOffer(snack: PokeSnackBlockEntity, pokemon: PokemonProperties): Boolean {
        val legend = legendOf(pokemon) ?: return true
        val placer = snack.placedBy ?: return false
        val player = snack.level?.server?.playerList?.getPlayer(placer) ?: return false
        return mayMeet(player, legend)
    }

    /** The Legend [pokemon] is, its regional form telling Galarian Zapdos from Zapdos; null for any other Pokemon. */
    fun legendOf(pokemon: Pokemon): Legend? = LegendCatalog.of(pokemon.species.resourceIdentifier.path, pokemon.aspects)

    /** The Legend a spawn or a command's [properties] make, or null; the aspects are read only for species with forms. */
    fun legendOf(properties: PokemonProperties): Legend? {
        val species = properties.species ?: return null
        return LegendCatalog.of(species, if (LegendCatalog.hasForms(species)) aspectsOf(properties) else emptySet())
    }

    /**
     * The aspects [properties] ask for. Cobblemon keeps a form flag such as `galarian` among its custom properties
     * rather than in [PokemonProperties.aspects], and `form=galar` names the form instead, so every spelling counts.
     */
    internal fun aspectsOf(properties: PokemonProperties): Set<String> = buildSet {
        addAll(properties.aspects)
        for (token in properties.asString(" ").lowercase().split(' ')) {
            add(token.substringAfter("aspect=").removeSuffix("=true"))
        }
        properties.form?.lowercase()?.let { form -> FORM_ASPECTS[form]?.let(::add) }
    }

    private val FORM_ASPECTS = mapOf("galar" to "galarian", "galarian" to "galarian")

    private fun ownerOf(pokemon: Pokemon): UUID? =
        if (pokemon.persistentData.hasUUID(OWNER_KEY)) pokemon.persistentData.getUUID(OWNER_KEY) else null

    private fun deny(player: ServerPlayer, message: Component) {
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED))
    }

    /** Each player's spawner asks this before offering a spawn, and so does Cobblenav's spawn list. */
    private class SpawnFilter(private val player: ServerPlayer) : SpawningInfluence {
        override fun affectSpawnable(detail: SpawnDetail, spawnablePosition: SpawnablePosition): Boolean {
            val legend = (detail as? PokemonSpawnDetail)?.pokemon?.let(::legendOf) ?: return true
            return mayMeet(player, legend)
        }
    }
}
