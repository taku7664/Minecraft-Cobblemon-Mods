package jbro.cobblemon.policy.legend

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.command.argument.PokemonPropertiesArgumentType
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import java.util.concurrent.ThreadLocalRandom
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * `/spawnpokemonfor <player> <pokemon>` (OP): spawns a Pokemon in front of [player] as if their own spawner had. A
 * Legend becomes theirs, is announced, and without a level given takes a level from its wild spawn.
 */
object SpawnForCommand {
    private const val KEY = "message.${JbroPolicy.MOD_ID}.spawn_for."

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("spawnpokemonfor").requires { it.hasPermission(2) }
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("pokemon", PokemonPropertiesArgumentType.properties()).executes { context ->
                        val player = EntityArgument.getPlayer(context, "player")
                        val spawned = spawn(player, PokemonPropertiesArgumentType.getPokemonProperties(context, "pokemon"))
                        if (spawned == null) {
                            context.source.sendFailure(Component.translatable(KEY + "failed"))
                            return@executes 0
                        }
                        context.source.sendSuccess({
                            Component.translatable(KEY + "done", spawned.name, player.displayName)
                        }, true)
                        spawned.warnings.forEach { warning -> context.source.sendSystemMessage(warning) }
                        1
                    })))
        }
    }

    /** A spawned Pokemon and its name (a Legend's own, so Galarian Zapdos says so), with why its player still could not catch it. */
    class Spawned(val entity: PokemonEntity, val name: Component, val warnings: List<Component>)

    /** Spawns [properties] two blocks in front of [player] as theirs; null when the world refused the entity. */
    fun spawn(player: ServerPlayer, properties: PokemonProperties): Spawned? {
        val legend = LegendPolicy.legendOf(properties)
        if (legend != null && properties.level == null) properties.level = wildLevel(legend)
        val entity = properties.createEntity(player.level(), null)
        val ahead = player.lookAngle.multiply(1.0, 0.0, 1.0).normalize().scale(2.0)
        entity.moveTo(player.x + ahead.x, player.y, player.z + ahead.z, player.yRot + 180f, 0f)
        LegendPolicy.claim(entity.pokemon, player)
        if (!player.serverLevel().addFreshEntity(entity)) return null
        val name = legend?.let { Component.translatable(it.nameKey) } ?: entity.pokemon.species.translatedName
        return Spawned(entity, name, legend?.let { warnings(player, it) }.orEmpty())
    }

    /** A level from the Legend's own wild spawn, so it matches what players meet in the world. */
    private fun wildLevel(legend: Legend): Int? {
        val detail = CobblemonSpawnPools.WORLD_SPAWN_POOL.details
            .filterIsInstance<PokemonSpawnDetail>().firstOrNull { it.id == "jbro-legendary-${legend.id}" }
        val range = detail?.levelRange ?: return null
        return ThreadLocalRandom.current().nextInt(range.first, range.last + 1)
    }

    /** Why [player] still could not catch the Legend spawned for them. */
    private fun warnings(player: ServerPlayer, legend: Legend): List<Component> = buildList {
        if (LegendRecords.get(player.server).has(player.uuid, legend.id)) {
            add(Component.translatable(KEY + "already_caught", player.displayName).withStyle(ChatFormatting.YELLOW))
        }
        if (LegendRanks.check(player, legend.rank) != LegendRanks.Verdict.Allowed) {
            add(Component.translatable(KEY + "rank", player.displayName, LegendRanks.name(legend.rank)).withStyle(ChatFormatting.YELLOW))
        }
    }
}
