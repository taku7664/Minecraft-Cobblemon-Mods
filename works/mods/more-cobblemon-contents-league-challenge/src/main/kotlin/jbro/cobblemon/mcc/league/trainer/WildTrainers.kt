package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.ErroredBattleStart
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import jbro.cobblemon.mcc.api.battle.BrainNpcBattles
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
    /** The content the Better AI brain plays wild trainer battles under. */
    private const val WILD_TRAINER_CONTENT = "${Mod.MOD_ID}:wild_trainer"
    private const val KEY = "message.${Mod.MOD_ID}.wild_trainer"
    private const val COOLDOWN_TICKS = 6_000L
    /**
     * Saved with a defeated or finished NPC before its closing words begin. If a restart loses the live completion
     * callback, the tag still makes it leave the next time it loads.
     */
    private const val DEFEATED_TAG = "mcc_wild_trainer_defeated"
    private const val SCENE_LINE_VARIANTS = 3
    /** Skin aspects for textures this mod ships under `textures/npcs/wild/`, such as `mcc_skin_nurse_joy`. */
    private const val OWN_SKIN_PREFIX = "mcc_skin_"
    private const val PERSONAL_NAME_KEY = "npc.${Mod.MOD_ID}.personal_name"
    /** The last word of nameless RCT skins such as `rct_bug_catcher_01ec` and `rct_biker_goon_020f`. */
    private val NOT_PERSONAL_NAMES = setOf("catcher", "goon")
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
    private val pendingDepartures = HashSet<UUID>()
    private val despawns = HashSet<UUID>()
    /** Trainers named before their skin aspect arrived, to name again a little later. */
    private val renames = HashMap<UUID, Long>()

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(Resources)
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(WildRewards.Resources)
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(WildQuiz.Resources)
        WildNpcSpawns.register()
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
            val trainer = event.battle.actors.firstOrNull { actor -> BrainNpcBattles.npcOf(actor)?.let(::definitionOf) != null }
                ?: return@subscribe
            if (requireNotNull(BrainNpcBattles.npcOf(trainer)).uuid !in starting) return@subscribe event.cancel()
            // As in MCC battles, the opponent's held items stay hidden. Only the trainer's side: the player fights
            // with their own Pokemon, whose setting would outlast the battle.
            trainer.pokemonList.forEach {
                it.originalPokemon.heldItemVisible = false
                it.effectedPokemon.heldItemVisible = false
            }
        }
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL) { event ->
            val fight = fights.remove(event.battle.battleId) ?: return@subscribe
            val server = event.battle.players.firstOrNull()?.server ?: return@subscribe
            val npc = (event.winners + event.losers).firstNotNullOfOrNull(BrainNpcBattles::npcOf)
            val npcWon = event.winners.any { BrainNpcBattles.npcOf(it) != null }
            event.battle.actors.filterIsInstance<PlayerBattleActor>().forEach { actor ->
                val player = server.playerList.getPlayer(actor.uuid) ?: return@forEach
                settle(server, player, npc, fight, won = !npcWon)
            }
        }
        CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL) { event -> fights.remove(event.battle.battleId) }
        CobblemonEvents.ENTITY_SPAWN.subscribe(Priority.NORMAL) { event ->
            val npc = event.entity as? NPCEntity ?: return@subscribe
            val placeholder = WildNpcSpawns.isPlaceholder(npc)
            if (!placeholder && definitionOf(npc) == null) return@subscribe
            val position = event.spawnablePosition
            if (!spawnAllowed(position.world, position.position.x.toDouble(), position.position.y.toDouble(),
                    position.position.z.toDouble())) return@subscribe event.cancel()
            // A trader wants something the player who brought it has; with nothing to want it does not come.
            val accept = { candidate: NPCEntity ->
                definitionOf(candidate)?.role != WildNpcRole.TRADE || WildTrader.prepare(candidate, event.cause.entity as? ServerPlayer)
            }
            val accepted = if (placeholder) WildNpcSpawns.become(npc, position, accept) else accept(npc)
            if (!accepted) event.cancel()
        }
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            val npc = entity as? NPCEntity ?: return@register
            if (definitionOf(npc) == null) return@register
            if (npc.uuid in despawns) {
                if (!inLiveBattle(npc)) {
                    despawns -= npc.uuid
                    npc.discard()
                }
                return@register
            }
            if (DEFEATED_TAG in npc.tags) {
                if (npc.uuid !in pendingDepartures) despawns += npc.uuid
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
            despawns.toList().forEach { id ->
                // One still in a battle waits for it to end; one not loaded keeps its tag and leaves the next time it loads.
                val npc = server.allLevels.firstNotNullOfOrNull { it.getEntity(id) as? NPCEntity } ?: return@forEach
                if (WildNpcDeparturePolicy.shouldDiscard(marked = true, id in pendingDepartures, inLiveBattle(npc))) {
                    despawns -= id
                    npc.discard()
                }
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            fights.clear(); starting.clear(); cooldowns.clear(); pendingDepartures.clear(); despawns.clear(); renames.clear()
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
        val personal = skin?.let(::personalNameText)
        npc.customName = if (personal == null) title.copy() else title.copy().append(" ").append(personal)
    }

    /** [personalName] as a translatable name, so a Korean client reads "알렉" where the skin says "alec". */
    fun personalNameText(aspect: String): Component? =
        personalName(aspect)?.let { Component.translatableWithFallback(personalNameKey(it), it) }

    fun personalNameKey(name: String): String = "$PERSONAL_NAME_KEY.${name.lowercase()}"

    private fun hasPersonalName(npc: NPCEntity): Boolean =
        npc.aspects.any { it.startsWith("rct_") && personalName(it) != null }

    /** Whether [npc] is in a battle still going on; a battle that was cancelled before it began can leave its ID behind. */
    private fun inLiveBattle(npc: NPCEntity): Boolean =
        npc.isInBattle() && npc.battleIds.any { BattleRegistry.getBattle(it) != null }

    /** The given name in an RCT skin aspect such as `rct_aroma_lady_elizabeth_02f7`, as "Elizabeth". */
    fun personalName(aspect: String): String? {
        val base = aspect.removePrefix("rct_").replace(Regex("_[0-9a-f]{4}$"), "")
        val given = base.substringAfterLast('_').takeIf { it.isNotEmpty() && it.all(Char::isLetter) } ?: return null
        if (given in NOT_PERSONAL_NAMES) return null
        return given.replaceFirstChar { it.uppercase() }
    }

    private fun offer(player: ServerPlayer, npc: NPCEntity, definition: WildTrainerDefinition) {
        name(npc)
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) return
        val refusal = refusal(player, npc)
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

    /**
     * Why [npc] will not battle [player] now, or null when it will. Asked again when the battle is chosen: the talk
     * box can stay open while someone else beats the trainer or the cooldown is set by another fight.
     */
    private fun refusal(player: ServerPlayer, npc: NPCEntity): String? = when {
        inLiveBattle(npc) -> "busy"
        isLeaving(npc) -> "done"
        (cooldowns[player.uuid to npc.uuid] ?: 0L) > player.serverLevel().gameTime -> "cooldown"
        Cobblemon.storage.getParty(player).none { !it.isFainted() } -> "no_pokemon"
        else -> null
    }

    private fun start(player: ServerPlayer, npc: NPCEntity, definition: WildTrainerDefinition) {
        if (!npc.isAlive || BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) return
        refusal(player, npc)?.let { return say(player, npc, "$KEY.refuse.$it") }
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
        val skill = WildTrainerQuality.of(definition.tier, cap).skill
        npc.skill = skill
        // Cobblemon reads the trainer's side from the NPC itself; pvn's own party argument is the player's.
        npc.party = party
        starting += npc.uuid
        val result = try {
            val stage = if (definition.tier == WildTrainerTier.ACE) "wild_trainer_ace" else "wild_trainer"
            MccBattleTags.during(setOf(player.uuid), MccBattleTag(ManagedBattleContentIds.LEAGUE_CHALLENGE, stage, definition.npcClass)) {
                // Played by the Better AI; the battle is set up as Cobblemon's pvn sets it up.
                BrainNpcBattles.start(player, npc, lead.uuid, skill, WILD_TRAINER_CONTENT)
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
        val line = npc?.let {
            Component.translatable("$KEY.scene.${if (won) "player_won" else "player_lost"}.${Random.nextInt(SCENE_LINE_VARIANTS)}")
        }
        if (!won) {
            // A trainer already beaten by someone else still goes, after its words to this player.
            if (npc != null && line != null) {
                if (isLeaving(npc)) leaveAfterScene(player, npc, opponent, line) else BattleScenes.play(player, npc, opponent, listOf(line))
            }
            BattleResultNotices.defeat(player, opponent)
            return
        }
        if (npc != null && line != null) leaveAfterScene(player, npc, opponent, line) else despawns += fight.npcId
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
                return jbro.cobblemon.mcc.league.server.LeagueProgressQueries.cap(player)
            } catch (failure: RuntimeException) {
                Mod.LOGGER.warn("Wild trainer could not read {}'s level cap: {}", player.uuid, failure.message)
            }
        }
        return Cobblemon.storage.getParty(player).maxOfOrNull { it.level }?.coerceAtLeast(5) ?: 5
    }

    /**
     * Sends [npc] away now. Normal trainer and role completion uses [leaveAfterScene] or [leaveAfterTalk] so the
     * player keeps the speaker until the closing words end.
     */
    internal fun leave(npc: NPCEntity) {
        markLeaving(npc)
        finishLeaving(npc.level().server ?: return, npc.uuid)
    }

    private fun leaveAfterScene(player: ServerPlayer, npc: NPCEntity, speaker: Component, line: Component) {
        if (!markLeaving(npc)) return BattleScenes.play(player, npc, speaker, listOf(line))
        val server = player.server
        BattleScenes.playUntilFinished(player, npc, speaker, listOf(line), Runnable { finishLeaving(server, npc.uuid) })
    }

    /** Opens a role NPC's final [talk], keeping the NPC until that talk closes or loses its valid context. */
    internal fun leaveAfterTalk(player: ServerPlayer, npc: NPCEntity, talk: NpcTalk) {
        if (!markLeaving(npc)) return NpcTalks.open(player, talk)
        val server = player.server
        NpcTalks.open(player, talk) { finishLeaving(server, npc.uuid) }
    }

    private fun markLeaving(npc: NPCEntity): Boolean {
        npc.addTag(DEFEATED_TAG)
        return pendingDepartures.add(npc.uuid)
    }

    private fun finishLeaving(server: MinecraftServer, npcId: UUID) {
        pendingDepartures -= npcId
        val npc = server.allLevels.firstNotNullOfOrNull { it.getEntity(npcId) as? NPCEntity }
        if (npc == null || inLiveBattle(npc)) {
            despawns += npcId
        } else {
            despawns -= npcId
            npc.discard()
        }
    }

    /** Whether [npc] has been sent away and is about to go. */
    internal fun isLeaving(npc: NPCEntity): Boolean =
        DEFEATED_TAG in npc.tags || npc.uuid in pendingDepartures || npc.uuid in despawns

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

internal object WildNpcDeparturePolicy {
    fun shouldDiscard(marked: Boolean, waitingForDialogue: Boolean, inBattle: Boolean): Boolean =
        marked && !waitingForDialogue && !inBattle
}
