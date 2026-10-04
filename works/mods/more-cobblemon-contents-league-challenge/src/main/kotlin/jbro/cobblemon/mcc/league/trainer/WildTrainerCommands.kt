package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import jbro.cobblemon.mcc.internal.command.MccAdminArguments
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import kotlin.math.atan2
import kotlin.math.floor
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** `/mcc league trainer spawn|despawn|list|cooldown` for operators; `spawn [kind|random] [count] [pos]` works anywhere. */
object WildTrainerCommands {
    private const val KEY = "command.${Mod.MOD_ID}.admin.trainer"
    private const val DEFAULT_RADIUS = 64
    private const val RANDOM = "random"
    private const val MAX_COUNT = 20
    private const val SPREAD = 3
    private const val FRONT_DISTANCE = 2.0
    private const val MAX_LIFT = 16
    private const val MAX_DROP = 64

    fun build(): LiteralArgumentBuilder<CommandSourceStack> = literal("trainer")
        .then(literal("spawn")
            .executes { spawn(it, null, 1, null) }
            .then(argument("kind", ResourceLocationArgument.id())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf(RANDOM) + WildTrainers.definitions.keys, builder) }
                .executes { spawn(it, ResourceLocationArgument.getId(it, "kind"), 1, null) }
                .then(argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                    .executes { spawn(it, ResourceLocationArgument.getId(it, "kind"), IntegerArgumentType.getInteger(it, "count"), null) }
                    .then(argument("pos", Vec3Argument.vec3())
                        .executes {
                            spawn(it, ResourceLocationArgument.getId(it, "kind"), IntegerArgumentType.getInteger(it, "count"),
                                Vec3Argument.getVec3(it, "pos"))
                        }))))
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

    /**
     * Calls out [count] trainers of [kind] (any loaded kind when null or `random`) at [pos], or a couple of blocks in
     * front of whoever ran the command. Works in any world and next to terminals; only natural spawns keep to the wild.
     */
    private fun spawn(context: CommandContext<CommandSourceStack>, kind: ResourceLocation?, count: Int, pos: Vec3?): Int {
        val source = context.source
        val kinds = WildTrainers.definitions.keys.filter { NPCClasses.getByIdentifier(ResourceLocation.parse(it)) != null }
        if (kinds.isEmpty()) {
            source.sendFailure(Component.translatable("$KEY.none"))
            return 0
        }
        val fixed = kind?.takeUnless { it.path == RANDOM && it.namespace == "minecraft" }?.let { id ->
            resolve(id, kinds) ?: run {
                source.sendFailure(Component.translatable("$KEY.unknown", id.toString()))
                return 0
            }
        }
        val level = source.level
        val viewer = source.entity
        val origin = pos
            ?: viewer?.let { it.position().add(Vec3(it.lookAngle.x, 0.0, it.lookAngle.z).normalize().scale(FRONT_DISTANCE)) }
            ?: source.position
        val spawned = mutableListOf<NPCEntity>()
        repeat(count) { index ->
            val spot = if (index == 0) origin
                else origin.add(level.random.nextInt(-SPREAD, SPREAD + 1).toDouble(), 0.0, level.random.nextInt(-SPREAD, SPREAD + 1).toDouble())
            val npc = NPCEntity(level)
            npc.npc = NPCClasses.getByIdentifier(ResourceLocation.parse(fixed ?: kinds.random())) ?: return@repeat
            npc.moveTo(spot.x, spot.y, spot.z, 0f, 0f)
            npc.initialize(1)
            if (!stand(level, npc, spot)) return@repeat
            // Face whoever called them, or the way the command looked when nobody did.
            val yaw = viewer?.let { Math.toDegrees(atan2(-(it.x - npc.x), it.z - npc.z)).toFloat() } ?: source.rotation.y
            npc.moveTo(npc.x, npc.y, npc.z, yaw, 0f)
            npc.yHeadRot = yaw
            npc.yBodyRot = yaw
            if (!level.addFreshEntity(npc)) return@repeat
            WildTrainers.name(npc)
            spawned += npc
        }
        when (spawned.size) {
            0 -> {
                source.sendFailure(Component.translatable("$KEY.no_space"))
                return 0
            }
            1 -> source.sendSuccess({ Component.translatable("$KEY.spawned", spawned.single().displayName) }, true)
            else -> source.sendSuccess({ Component.translatable("$KEY.spawned_many", spawned.size) }, true)
        }
        return spawned.size
    }

    /** [id] as a loaded kind, also by its bare name: `hiker` finds `rctmod:hiker` when only one kind is called that. */
    private fun resolve(id: ResourceLocation, kinds: List<String>): String? {
        val full = id.toString()
        if (full in kinds) return full
        if (id.namespace != "minecraft") return null
        return kinds.filter { it.substringAfter(':') == id.path }.singleOrNull()
    }

    /**
     * Moves [npc] to free space near [spot]: up out of any block it is buried in, then down onto the ground below it.
     * False when there is no room within reach; over the void it stays where it was asked.
     */
    private fun stand(level: ServerLevel, npc: NPCEntity, spot: Vec3): Boolean {
        var y = floor(spot.y)
        npc.setPos(spot.x, y, spot.z)
        var lifts = 0
        while (!level.noCollision(npc) && lifts < MAX_LIFT) {
            y += 1
            npc.setPos(spot.x, y, spot.z)
            lifts++
        }
        if (!level.noCollision(npc)) return false
        val lifted = y
        var drops = 0
        while (drops < MAX_DROP && y - 1 >= level.minBuildHeight && level.noCollision(npc, npc.boundingBox.move(0.0, -1.0, 0.0))) {
            y -= 1
            npc.setPos(spot.x, y, spot.z)
            drops++
        }
        // No ground within reach (the void, or high in the sky): leave it where it was asked.
        if (drops == MAX_DROP || y - 1 < level.minBuildHeight) npc.setPos(spot.x, lifted, spot.z)
        return true
    }

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
