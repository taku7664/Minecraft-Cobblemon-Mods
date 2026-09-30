package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import jbro.cobblemon.mcc.internal.command.MccAdminArguments
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB

/** `/mcc league trainer spawn|despawn|list|cooldown` for operators. */
object WildTrainerCommands {
    private const val KEY = "command.${Mod.MOD_ID}.admin.trainer"
    private const val DEFAULT_RADIUS = 64

    fun build(): LiteralArgumentBuilder<CommandSourceStack> = literal("trainer")
        .then(literal("spawn").then(argument("kind", ResourceLocationArgument.id())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(WildTrainers.definitions.keys, builder) }
            .executes { context ->
                val kind = ResourceLocationArgument.getId(context, "kind")
                val npcClass = NPCClasses.getByIdentifier(kind)
                if (npcClass == null || WildTrainers.definitions[kind.toString()] == null) {
                    context.source.sendFailure(Component.translatable("$KEY.unknown", kind.toString()))
                    return@executes 0
                }
                val level = context.source.level
                val npc = NPCEntity(level)
                npc.npc = npcClass
                npc.moveTo(context.source.position.x, context.source.position.y, context.source.position.z, context.source.rotation.y, 0f)
                npc.initialize(1)
                level.addFreshEntity(npc)
                WildTrainers.name(npc)
                context.source.sendSuccess({ Component.translatable("$KEY.spawned", npc.displayName) }, true)
                1
            }))
        .then(literal("despawn")
            .executes { despawn(it.source, DEFAULT_RADIUS) }
            .then(argument("radius", IntegerArgumentType.integer(1, 512)).executes { despawn(it.source, IntegerArgumentType.getInteger(it, "radius")) }))
        .then(literal("list")
            .executes { list(it.source, DEFAULT_RADIUS) }
            .then(argument("radius", IntegerArgumentType.integer(1, 512)).executes { list(it.source, IntegerArgumentType.getInteger(it, "radius")) }))
        .then(literal("cooldown").then(literal("reset").then(argument("player", GameProfileArgument.gameProfile()).executes { context ->
            val player = MccAdminArguments.profile(context) ?: return@executes 0
            val cleared = WildTrainers.resetCooldowns(player.id)
            context.source.sendSuccess({ Component.translatable("$KEY.cooldown_reset", player.name, cleared) }, true)
            1
        })))

    private fun nearby(source: CommandSourceStack, radius: Int): List<NPCEntity> =
        source.level.getEntitiesOfClass(NPCEntity::class.java, AABB.ofSize(source.position, radius * 2.0, radius * 2.0, radius * 2.0)) {
            WildTrainers.definitionOf(it) != null
        }

    private fun despawn(source: CommandSourceStack, radius: Int): Int {
        val removable = nearby(source, radius).filter { !it.isInBattle() }
        removable.forEach(NPCEntity::discard)
        source.sendSuccess({ Component.translatable("$KEY.despawned", removable.size, radius) }, true)
        return 1
    }

    private fun list(source: CommandSourceStack, radius: Int): Int {
        val trainers = nearby(source, radius)
        source.sendSuccess({ Component.translatable("$KEY.list.header", trainers.size, radius, WildTrainers.activeFights()) }, false)
        trainers.forEach { npc ->
            source.sendSuccess({
                Component.translatable("$KEY.list.entry", npc.displayName, npc.npc.id.toString(), npc.blockX, npc.blockY, npc.blockZ,
                    npc.isInBattle().toString())
            }, false)
        }
        return 1
    }
}
