package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.dialogue.Dialogue
import com.cobblemon.mod.common.api.dialogue.DialogueManager
import com.cobblemon.mod.common.api.dialogue.DialoguePage
import com.cobblemon.mod.common.api.dialogue.DialogueSpeaker
import com.cobblemon.mod.common.api.dialogue.FunctionDialogueAction
import com.cobblemon.mod.common.api.dialogue.FunctionDialogueText
import com.cobblemon.mod.common.api.dialogue.ReferenceDialogueFaceProvider
import com.cobblemon.mod.common.api.dialogue.input.DialogueOption
import com.cobblemon.mod.common.api.dialogue.input.DialogueOptionSetInput
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.entity.npc.NPCBattleActor
import com.cobblemon.mod.common.entity.npc.NPCEntity
import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.BattleResultNotices
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.rewards.BattlePointRewards
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.record.BattleRecordCategory
import jbro.cobblemon.mcc.internal.record.BattleRecordKey
import jbro.cobblemon.mcc.internal.record.BattleRecordOutcome
import jbro.cobblemon.mcc.internal.record.BattleRecordService
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.mcc.league.server.LeagueCatalogResources
import jbro.cobblemon.mcc.league.server.LeagueSavedData
import jbro.cobblemon.mcc.league.system.LeagueEngine
import kotlin.random.Random
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB

/**
 * Trainers met in the wild. They spawn as Cobblemon NPCs from the League's spawn pool; talking to one offers a
 * battle against a party made for the challenger's level cap, fought with the player's own Pokemon in a normal
 * Cobblemon battle, so experience, evolution and the level cap apply as in any battle. A win pays BP.
 */
object WildTrainers {
    const val RECORD_FORMAT = "wild_trainer"
    private const val KEY = "message.${Mod.MOD_ID}.wild_trainer"
    private const val COOLDOWN_TICKS = 6_000L
    private const val DEFEATED_DESPAWN_TICKS = 60L
    private const val NEARBY_LIMIT = 2
    private const val NEARBY_RADIUS = 64.0
    private const val TERMINAL_CHUNK_RADIUS = 2
    /**
     * The only worlds wild trainers appear in. An allowlist, so the plaza, MyRooms, the battle lounge and any
     * dimension added later stay free of them.
     */
    val WILD_DIMENSIONS = setOf("minecraft:overworld")

    /** The loaded definitions by NPC class ID; empty until the first successful reload. */
    @Volatile
    var definitions: Map<String, WildTrainerDefinition> = emptyMap()
        private set

    private data class Fight(val npcId: UUID, val playerId: UUID, val definition: WildTrainerDefinition)

    private val fights = HashMap<UUID, Fight>()
    private val starting = HashSet<UUID>()
    private val cooldowns = HashMap<Pair<UUID, UUID>, Long>()
    private val despawns = HashMap<UUID, Long>()

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(Resources)
        UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
            val npc = entity as? NPCEntity ?: return@register InteractionResult.PASS
            val definition = definitionOf(npc) ?: return@register InteractionResult.PASS
            if (level.isClientSide || player !is ServerPlayer) return@register InteractionResult.SUCCESS
            if (hand == InteractionHand.MAIN_HAND) offer(player, npc, definition)
            InteractionResult.SUCCESS
        }
        // A wild trainer battles only through its offer; Cobblemon's own challenge would use an empty party.
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGH) { event ->
            val npc = event.battle.actors.filterIsInstance<NPCBattleActor>().map { it.npc }.firstOrNull { definitionOf(it) != null }
                ?: return@subscribe
            if (npc.uuid !in starting) event.cancel()
        }
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL) { event ->
            val fight = fights.remove(event.battle.battleId) ?: return@subscribe
            val server = event.battle.players.firstOrNull()?.server ?: return@subscribe
            val npc = (event.winners + event.losers).filterIsInstance<NPCBattleActor>().firstOrNull()?.npc
            val npcWon = event.winners.any { it is NPCBattleActor }
            event.battle.actors.filterIsInstance<PlayerBattleActor>().forEach { actor ->
                val player = server.playerList.getPlayer(actor.uuid) ?: return@forEach
                settle(server, player, npc, fight, won = !npcWon)
            }
        }
        CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL) { event -> fights.remove(event.battle.battleId) }
        CobblemonEvents.ENTITY_SPAWN.subscribe(Priority.NORMAL) { event ->
            val npc = event.entity as? NPCEntity ?: return@subscribe
            if (definitionOf(npc) == null) return@subscribe
            val level = event.spawnablePosition.world
            if (!spawnAllowed(level, event.spawnablePosition.position.x.toDouble(), event.spawnablePosition.position.y.toDouble(),
                    event.spawnablePosition.position.z.toDouble())) event.cancel()
        }
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            val npc = entity as? NPCEntity ?: return@register
            if (definitionOf(npc) != null && npc.customName == null) name(npc)
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (despawns.isEmpty()) return@register
            val now = server.overworld().gameTime
            despawns.entries.filter { it.value <= now }.map { it.key }.forEach { id ->
                despawns.remove(id)
                server.allLevels.firstNotNullOfOrNull { it.getEntity(id) as? NPCEntity }?.takeIf { !it.isInBattle() }?.discard()
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            fights.clear(); starting.clear(); cooldowns.clear(); despawns.clear()
        }
    }

    fun definitionOf(npc: NPCEntity): WildTrainerDefinition? =
        definitions[npc.npc.id.toString()]

    /** Whether a wild trainer may appear at this spot: in the wild, not crowding others, not at a terminal. */
    fun spawnAllowed(level: ServerLevel, x: Double, y: Double, z: Double): Boolean {
        if (!isWild(level.dimension().location().toString())) return false
        val nearby = level.getEntitiesOfClass(NPCEntity::class.java, AABB(x, y, z, x, y, z).inflate(NEARBY_RADIUS)) { definitionOf(it) != null }
        if (nearby.size >= NEARBY_LIMIT) return false
        val center = ChunkPos(net.minecraft.core.BlockPos.containing(x, y, z))
        val terminalTypes = HoloTerminals.all().map { it.blockEntityType }.toSet()
        for (dx in -TERMINAL_CHUNK_RADIUS..TERMINAL_CHUNK_RADIUS) for (dz in -TERMINAL_CHUNK_RADIUS..TERMINAL_CHUNK_RADIUS) {
            val chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz) ?: continue
            if (chunk.blockEntities.values.any { it.type in terminalTypes }) return false
        }
        return true
    }

    /** Whether [dimension] is a world wild trainers roam, as opposed to a plaza, room or lounge. */
    fun isWild(dimension: String): Boolean = dimension in WILD_DIMENSIONS

    /** Names a wild trainer after its class and the trainer its skin belongs to, such as "Hiker Bob". */
    fun name(npc: NPCEntity) {
        val title = npc.npc.names.firstOrNull() ?: return
        val skin = npc.aspects.firstOrNull { it.startsWith("rct_") }
        val personal = skin?.let(::personalName)
        npc.customName = if (personal == null) title.copy() else title.copy().append(" ").append(personal)
    }

    /** The given name in an RCT skin aspect such as `rct_aroma_lady_elizabeth_02f7`, as "Elizabeth". */
    fun personalName(aspect: String): String? {
        val base = aspect.removePrefix("rct_").replace(Regex("_[0-9a-f]{4}$"), "")
        val given = base.substringAfterLast('_').takeIf { it.isNotEmpty() && it.all(Char::isLetter) } ?: return null
        return given.replaceFirstChar { it.uppercase() }
    }

    private fun offer(player: ServerPlayer, npc: NPCEntity, definition: WildTrainerDefinition) {
        if (npc.customName == null) name(npc)
        val refusal = when {
            BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null -> return
            npc.isInBattle() -> "busy"
            npc.uuid in despawns -> "done"
            (cooldowns[player.uuid to npc.uuid] ?: 0L) > player.serverLevel().gameTime -> "cooldown"
            Cobblemon.storage.getParty(player).none { !it.isFainted() } -> "no_pokemon"
            else -> null
        }
        if (refusal != null) {
            say(player, npc, "$KEY.refuse.$refusal")
            return
        }
        val speaker = DialogueSpeaker(
            name = FunctionDialogueText { (npc.displayName ?: npc.name).copy() },
            face = ReferenceDialogueFaceProvider(npc.id, false),
        )
        val challenge = DialogueOption(
            text = FunctionDialogueText { Component.translatable("$KEY.option.battle") },
            value = "battle",
            action = FunctionDialogueAction { dialogue, _ ->
                DialogueManager.stopDialogue(dialogue.playerEntity)
                start(dialogue.playerEntity, npc, definition)
            },
        )
        val leave = DialogueOption(
            text = FunctionDialogueText { Component.translatable("$KEY.option.leave") },
            value = "leave",
            action = FunctionDialogueAction { dialogue, _ -> DialogueManager.stopDialogue(dialogue.playerEntity) },
        )
        val line = "$KEY.greeting.${Random.nextInt(GREETINGS)}"
        val page = DialoguePage(
            id = "challenge",
            speaker = "npc",
            lines = mutableListOf<com.cobblemon.mod.common.api.dialogue.DialogueText>(FunctionDialogueText { Component.translatable(line) }),
            input = DialogueOptionSetInput(options = mutableListOf(challenge, leave), vertical = false),
        )
        DialogueManager.startDialogue(player, npc, Dialogue(
            pages = listOf(page),
            speakers = mapOf("npc" to speaker),
            escapeAction = FunctionDialogueAction { dialogue, _ -> DialogueManager.stopDialogue(dialogue.playerEntity) },
        ))
    }

    private fun start(player: ServerPlayer, npc: NPCEntity, definition: WildTrainerDefinition) {
        if (!npc.isAlive || npc.isInBattle() || BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) return
        val lead = Cobblemon.storage.getParty(player).firstOrNull { !it.isFainted() } ?: return say(player, npc, "$KEY.refuse.no_pokemon")
        val party = NPCPartyStore(npc)
        val cap = cap(player)
        val heldItems = HashSet<String>()
        WildTrainerParty.roll(definition, cap, Random.Default).forEach { (species, level) ->
            val pokemon = try {
                WildTrainerPokemon.create(species, level, definition.tier, cap, heldItems, Random.Default)
            } catch (failure: RuntimeException) {
                Mod.LOGGER.warn("Wild trainer {} could not raise {} at level {}", definition.npcClass, species, level, failure)
                null
            }
            if (pokemon == null) {
                Mod.LOGGER.warn("Wild trainer {} names unknown species {}", definition.npcClass, species)
                return@forEach
            }
            party.add(pokemon)
        }
        if (party.none()) return say(player, npc, "$KEY.refuse.busy")
        npc.skill = WildTrainerQuality.of(definition.tier, cap).skill
        starting += npc.uuid
        val result = try {
            BattleBuilder.pvn(player, npc, lead.uuid, BattleFormat.GEN_9_SINGLES, false, false, party)
        } catch (failure: RuntimeException) {
            Mod.LOGGER.error("Wild trainer battle could not start for {}", player.uuid, failure)
            null
        } finally {
            starting -= npc.uuid
        }
        if (result is SuccessfulBattleStart) {
            fights[result.battle.battleId] = Fight(npc.uuid, player.uuid, definition)
        } else {
            say(player, npc, "$KEY.refuse.busy")
        }
    }

    private fun settle(server: MinecraftServer, player: ServerPlayer, npc: NPCEntity?, fight: Fight, won: Boolean) {
        val opponent = npc?.displayName?.copy() ?: Component.translatable("$KEY.unknown")
        val now = server.overworld().gameTime
        cooldowns[player.uuid to fight.npcId] = now + COOLDOWN_TICKS
        cooldowns.entries.removeIf { it.value <= now }
        record(server, player.uuid, won)
        if (!won) {
            BattleResultNotices.defeat(player, opponent)
            return
        }
        despawns[fight.npcId] = now + DEFEATED_DESPAWN_TICKS
        val bp = fight.definition.bp
        val paid = if (bp <= 0) 0L else {
            val transaction = UUID.nameUUIDFromBytes("wild_trainer:${fight.npcId}:${player.uuid}:$now".toByteArray())
            val result = BattlePointRewards.award(server, player.uuid, transaction, bp, ManagedBattleContentIds.LEAGUE_CHALLENGE,
                "wild_trainer_${fight.definition.npcClass.substringAfter(':')}")
            if (result.accepted) bp else 0L.also { Mod.LOGGER.warn("Wild trainer BP for {} was not paid: {}", player.uuid, result.status) }
        }
        BattleResultNotices.victory(player, opponent, paid)
    }

    private fun record(server: MinecraftServer, playerId: UUID, won: Boolean) {
        if (!BattleRecordService.isAvailable(server)) return
        BattleRecordService.recordOutcome(server,
            BattleRecordKey(playerId, BattleRecordCategory(ManagedBattleContentIds.LEAGUE_CHALLENGE, RECORD_FORMAT)),
            if (won) BattleRecordOutcome.WIN else BattleRecordOutcome.LOSS)
    }

    /** The challenger's League level cap, or their strongest Pokemon's level when no League is loaded. */
    private fun cap(player: ServerPlayer): Int {
        val catalog = LeagueCatalogResources.current
        if (catalog != null) {
            try {
                return LeagueEngine(catalog).cap(LeagueSavedData.get(player.server).read(catalog.id, player.uuid))
            } catch (failure: RuntimeException) {
                Mod.LOGGER.warn("Wild trainer could not read {}'s level cap: {}", player.uuid, failure.message)
            }
        }
        return Cobblemon.storage.getParty(player).maxOfOrNull { it.level }?.coerceAtLeast(5) ?: 5
    }

    private fun say(player: ServerPlayer, npc: NPCEntity, key: String) {
        player.sendSystemMessage(Component.literal("<").append(npc.displayName ?: npc.name).append("> ").append(Component.translatable(key)))
    }

    /** Operator view and control; the commands live under `/mcc league trainer`. */
    internal fun activeFights(): Int = fights.size

    internal fun resetCooldowns(playerId: UUID): Int {
        val before = cooldowns.size
        cooldowns.keys.removeIf { it.first == playerId }
        return before - cooldowns.size
    }

    private const val GREETINGS = 4

    private object Resources : SimpleSynchronousResourceReloadListener {
        override fun getFabricId(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "wild_trainers")

        override fun onResourceManagerReload(manager: ResourceManager) {
            try {
                val files = manager.listResources("league-challenge/wild_trainers") { it.path.endsWith(".json") }.map { (id, resource) ->
                    id.toString() to resource.openAsReader().use { it.readText() }
                }.toMap()
                definitions = WildTrainerCatalogParser.parse(files)
                Mod.LOGGER.info("Loaded {} wild trainer kinds", definitions.size)
            } catch (failure: RuntimeException) {
                Mod.LOGGER.error("Wild trainer reload rejected; keeping the previous kinds", failure)
            }
        }
    }
}
