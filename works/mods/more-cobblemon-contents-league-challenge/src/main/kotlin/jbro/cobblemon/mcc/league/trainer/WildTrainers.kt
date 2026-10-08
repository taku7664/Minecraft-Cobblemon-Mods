package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.ErroredBattleStart
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.entity.npc.NPCBattleActor
import com.cobblemon.mod.common.entity.npc.NPCEntity
import java.util.UUID
import jbro.cobblemon.mcc.api.battle.MccBattleTag
import jbro.cobblemon.mcc.api.battle.MccBattleTags
import jbro.cobblemon.mcc.api.presentation.BattleResultNotices
import jbro.cobblemon.mcc.api.presentation.BattleScenes
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.rewards.BattlePointRewards
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.record.BattleRecordCategory
import jbro.cobblemon.mcc.internal.record.BattleRecordKey
import jbro.cobblemon.mcc.internal.record.BattleRecordOutcome
import jbro.cobblemon.mcc.internal.record.BattleRecordService
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.npc.api.NpcTalk
import jbro.cobblemon.npc.api.NpcTalkChoice
import jbro.cobblemon.npc.api.NpcTalks
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
    // Long enough for the trainer's closing words, which the camera looks at, to be said before it leaves.
    private const val DEFEATED_DESPAWN_TICKS = 300L
    /**
     * Saved with a defeated trainer, so it still leaves when the server stops or its chunk unloads before
     * [DEFEATED_DESPAWN_TICKS] are up: the next time it loads it goes on the next tick.
     */
    private const val DEFEATED_TAG = "mcc_wild_trainer_defeated"
    private const val SCENE_LINE_VARIANTS = 3
    /** Skin aspects for textures this mod ships under `textures/npcs/wild/`, such as `mcc_skin_nurse_joy`. */
    private const val OWN_SKIN_PREFIX = "mcc_skin_"
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
    /** Trainers named before their skin aspect arrived, to name again a little later. */
    private val renames = HashMap<UUID, Long>()

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(Resources)
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(WildRewards.Resources)
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(WildQuiz.Resources)
        UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
            val npc = entity as? NPCEntity ?: return@register InteractionResult.PASS
            val definition = definitionOf(npc) ?: return@register InteractionResult.PASS
            if (level.isClientSide || player !is ServerPlayer) return@register InteractionResult.SUCCESS
            if (hand == InteractionHand.MAIN_HAND) {
                if (definition.role == WildNpcRole.BATTLE) offer(player, npc, definition) else WildNpcRoles.talk(player, npc, definition)
            }
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
                    event.spawnablePosition.position.z.toDouble())) return@subscribe event.cancel()
            // A trader wants something the player who brought it has; with nothing to want it does not come.
            if (definitionOf(npc)?.role == WildNpcRole.TRADE && !WildTrader.prepare(npc, event.cause.entity as? ServerPlayer)) event.cancel()
        }
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            val npc = entity as? NPCEntity ?: return@register
            if (definitionOf(npc) == null) return@register
            if (DEFEATED_TAG in npc.tags) {
                despawns.putIfAbsent(npc.uuid, npc.level().server?.overworld()?.gameTime ?: 0L)
                return@register
            }
            // Cobblemon names the NPC after its class as soon as the class is set, so a custom name is always there
            // already; the personal name is added here when the skin has one.
            if (hasPersonalName(npc) || npc.customName == null) name(npc)
            // The skin, and with it the personal name, can land after the trainer joins the world.
            if (!hasPersonalName(npc)) renames[npc.uuid] = (npc.level().server?.overworld()?.gameTime ?: 0L) + RENAME_DELAY_TICKS
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (renames.isNotEmpty()) {
                val due = server.overworld().gameTime
                renames.entries.filter { it.value <= due }.map { it.key }.forEach { id ->
                    renames.remove(id)
                    server.allLevels.firstNotNullOfOrNull { it.getEntity(id) as? NPCEntity }?.let(::name)
                }
            }
            if (despawns.isEmpty()) return@register
            val now = server.overworld().gameTime
            despawns.entries.filter { it.value <= now }.map { it.key }.forEach { id ->
                despawns.remove(id)
                // One still in a battle keeps its tag and leaves the next time it loads.
                server.allLevels.firstNotNullOfOrNull { it.getEntity(id) as? NPCEntity }?.takeIf { !it.isInBattle() }?.discard()
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            fights.clear(); starting.clear(); cooldowns.clear(); despawns.clear(); renames.clear()
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

    private fun hasPersonalName(npc: NPCEntity): Boolean =
        npc.aspects.any { it.startsWith("rct_") && personalName(it) != null }

    /** Whether [npc] is in a battle still going on; a battle that was cancelled before it began can leave its ID behind. */
    private fun inLiveBattle(npc: NPCEntity): Boolean =
        npc.isInBattle() && npc.battleIds.any { BattleRegistry.getBattle(it) != null }

    /** The given name in an RCT skin aspect such as `rct_aroma_lady_elizabeth_02f7`, as "Elizabeth". */
    fun personalName(aspect: String): String? {
        val base = aspect.removePrefix("rct_").replace(Regex("_[0-9a-f]{4}$"), "")
        val given = base.substringAfterLast('_').takeIf { it.isNotEmpty() && it.all(Char::isLetter) } ?: return null
        return given.replaceFirstChar { it.uppercase() }
    }

    private fun offer(player: ServerPlayer, npc: NPCEntity, definition: WildTrainerDefinition) {
        name(npc)
        val refusal = when {
            BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null -> return
            inLiveBattle(npc) -> "busy"
            npc.uuid in despawns -> "done"
            (cooldowns[player.uuid to npc.uuid] ?: 0L) > player.serverLevel().gameTime -> "cooldown"
            Cobblemon.storage.getParty(player).none { !it.isFainted() } -> "no_pokemon"
            else -> null
        }
        if (refusal != null) {
            say(player, npc, "$KEY.refuse.$refusal")
            return
        }
        val line = "$KEY.greeting.${Random.nextInt(GREETINGS)}"
        NpcTalks.open(player, talk(npc, Component.translatable(line), listOf(
            NpcTalkChoice(Component.translatable("$KEY.option.battle")) { start(it, npc, definition) },
            NpcTalkChoice(Component.translatable("$KEY.option.leave")),
        )))
    }

    private fun start(player: ServerPlayer, npc: NPCEntity, definition: WildTrainerDefinition) {
        if (!npc.isAlive || inLiveBattle(npc) || BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) return
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
        if (party.none()) {
            Mod.LOGGER.warn("Wild trainer {} raised no Pokemon at cap {}", definition.npcClass, cap)
            return say(player, npc, "$KEY.refuse.busy")
        }
        npc.skill = WildTrainerQuality.of(definition.tier, cap).skill
        // Cobblemon reads the trainer's side from the NPC itself; pvn's own party argument is the player's.
        npc.party = party
        starting += npc.uuid
        val result = try {
            val stage = if (definition.tier == WildTrainerTier.ACE) "wild_trainer_ace" else "wild_trainer"
            MccBattleTags.during(setOf(player.uuid), MccBattleTag(ManagedBattleContentIds.LEAGUE_CHALLENGE, stage, definition.npcClass)) {
                BattleBuilder.pvn(player, npc, lead.uuid, BattleFormat.GEN_9_SINGLES, false, false)
            }
        } catch (failure: RuntimeException) {
            Mod.LOGGER.error("Wild trainer battle could not start for {}", player.uuid, failure)
            null
        } finally {
            starting -= npc.uuid
        }
        when (result) {
            is SuccessfulBattleStart -> fights[result.battle.battleId] = Fight(npc.uuid, player.uuid, definition)
            // Cobblemon says why (a fainted or busy Pokemon, a battle already on); the trainer is not to blame.
            is ErroredBattleStart -> {
                Mod.LOGGER.warn("Wild trainer battle for {} did not start: {}", player.uuid,
                    result.errors.joinToString { it.getMessageFor(player).string })
                result.sendTo(player) { it }
            }
            else -> say(player, npc, "$KEY.refuse.busy")
        }
    }

    private fun settle(server: MinecraftServer, player: ServerPlayer, npc: NPCEntity?, fight: Fight, won: Boolean) {
        val opponent = npc?.displayName?.copy() ?: Component.translatable("$KEY.unknown")
        val now = server.overworld().gameTime
        cooldowns[player.uuid to fight.npcId] = now + COOLDOWN_TICKS
        cooldowns.entries.removeIf { it.value <= now }
        record(server, player.uuid, won)
        if (npc != null) {
            val line = Component.translatable("$KEY.scene.${if (won) "player_won" else "player_lost"}.${Random.nextInt(SCENE_LINE_VARIANTS)}")
            BattleScenes.play(player, npc, opponent, listOf(line))
        }
        if (!won) {
            BattleResultNotices.defeat(player, opponent)
            return
        }
        if (npc != null) leave(npc) else despawns[fight.npcId] = now + DEFEATED_DESPAWN_TICKS
        val bp = fight.definition.bp.random()
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
    internal fun cap(player: ServerPlayer): Int {
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

    /**
     * Sends [npc] away once its business is done (a trainer beaten, a trade made): it goes after its closing words,
     * and still goes if the server stops first, the next time it loads.
     */
    internal fun leave(npc: NPCEntity) {
        npc.addTag(DEFEATED_TAG)
        despawns[npc.uuid] = (npc.level().server?.overworld()?.gameTime ?: 0L) + DEFEATED_DESPAWN_TICKS
    }

    /** Whether [npc] has been sent away and is about to go. */
    internal fun isLeaving(npc: NPCEntity): Boolean = DEFEATED_TAG in npc.tags || npc.uuid in despawns

    /** [npc] says [key] in the dialogue box; for the other wild NPC roles. */
    internal fun sayLine(player: ServerPlayer, npc: NPCEntity, key: String) = say(player, npc, key)

    /** [npc] says [key] in the NPC dialogue box; reading it closes the box. */
    private fun say(player: ServerPlayer, npc: NPCEntity, key: String) {
        NpcTalks.open(player, talk(npc, Component.translatable(key)))
    }

    /** A talk with [npc]: its name, its face in the name plate, and the camera on it while the box is up. */
    internal fun talk(npc: NPCEntity, line: Component, choices: List<NpcTalkChoice> = emptyList()) = talk(npc, listOf(line), choices)

    internal fun talk(npc: NPCEntity, lines: List<Component>, choices: List<NpcTalkChoice> = emptyList()) = NpcTalk(
        speaker = (npc.displayName ?: npc.name).copy(),
        lines = lines,
        choices = choices,
        npc = npc,
        skin = skinRef(npc),
    )

    /** The skin [npc] wears, as the dialogue box names it: an RCT Trainers+ skin, or one this mod ships. */
    private fun skinRef(npc: NPCEntity): String {
        npc.aspects.firstOrNull { it.startsWith("rct_") }?.let { return "rct:" + it.removePrefix("rct_") }
        val own = npc.aspects.firstOrNull { it.startsWith(OWN_SKIN_PREFIX) } ?: return ""
        return "${Mod.MOD_ID}:textures/npcs/wild/${own.removePrefix(OWN_SKIN_PREFIX)}.png"
    }

    /** Operator view and control; the commands live under `/mcc league trainer`. */
    internal fun activeFights(): Int = fights.size

    internal fun resetCooldowns(playerId: UUID): Int {
        val before = cooldowns.size
        cooldowns.keys.removeIf { it.first == playerId }
        return before - cooldowns.size
    }

    private const val GREETINGS = 4
    private const val RENAME_DELAY_TICKS = 20L

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
