package jbro.cobblemon.mcc.league.client

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.client.gui.snapshots.SnapshotWarningScreen
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.concurrent.atomic.AtomicInteger
import jbro.cobblemon.mcc.league.trainer.WildSpeciesRarity
import jbro.cobblemon.npc.client.NpcDialogueScreen
import jbro.cobblemon.npc.network.DialogueAnswerPayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory

/**
 * Development-only walk through the wild NPC roles (`docs/WILD_NPC_ROLES.md`). Set `MCC_NPC_ROLE_CHECK=1` and launch
 * with `--quickPlaySingleplayer npc-role-check` (a disposable copy of a capture world): it heals at a caretaker, trades
 * with a trader, opens the PC from a second trader, talks to a wild trainer, and logs whether Cobblemon's spawns name
 * the player who caused them. Screenshots land in `screenshots/` as `npc-role-<step>.png`; the log reports `ROLE CHECK`.
 */
internal object WildNpcRoleCheckHarness {
    private const val WORLD = "npc-role-check"
    private const val NS = "more_cobblemon_contents_league_challenge"
    private val logger = LoggerFactory.getLogger("npc-role-check")
    private val spawnCauses = AtomicInteger()
    private val playerCauses = AtomicInteger()

    fun installFromEnvironment() {
        if (System.getenv("MCC_NPC_ROLE_CHECK") != "1" || !FabricLoader.getInstance().isDevelopmentEnvironment) return
        // Whether a spawn names the player it was made for: the trader needs that to know whose Pokemon to want.
        CobblemonEvents.ENTITY_SPAWN.subscribe(Priority.LOWEST) { event ->
            val count = spawnCauses.incrementAndGet()
            val cause = event.cause.entity
            if (cause is ServerPlayer) playerCauses.incrementAndGet()
            if (count <= 6) logger.info("ROLE CHECK spawn {} of {} caused by {}", count, event.entity.type.descriptionId,
                cause?.let { "${it.javaClass.simpleName} ${it.name.string}" } ?: "nothing")
        }
        var phase = 0
        var ticks = 0
        var phaseTick = 0
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            ticks++
            if (ticks == 1) client.options.pauseOnLostFocus = false
            check(ticks < 12_000) { "NPC role check timed out in phase $phase" }
            (client.screen as? AccessibilityOnboardingScreen)?.let { it.onClose(); return@EndTick }
            (client.screen as? BackupConfirmScreen)?.let { screen ->
                screen.children().filterIsInstance<Button>().single {
                    it.message.string == Component.translatable("selectWorld.backupJoinConfirmButton").string
                }.onPress()
                return@EndTick
            }
            (client.screen as? SnapshotWarningScreen)?.let {
                it.consumer(SnapshotWarningScreen.Acknowledgement.YES, false)
                return@EndTick
            }
            val server = client.singleplayerServer ?: return@EndTick
            val player = client.player ?: return@EndTick
            if (client.level == null || client.overlay != null) return@EndTick
            check(server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() == WORLD) {
                "The NPC role check may only change the disposable $WORLD world"
            }
            fun since(n: Int) = ticks - phaseTick >= n
            fun next(to: Int) { phase = to; phaseTick = ticks }
            fun shot(step: String) {
                Screenshot.grab(client.gameDirectory, "npc-role-$step.png", client.mainRenderTarget) {}
                logger.info("ROLE CHECK capture {} (screen={})", step, client.screen?.javaClass?.simpleName)
            }
            fun onServer(action: (ServerPlayer) -> Unit) = server.execute {
                try {
                    action(server.playerList.getPlayer(player.uuid)!!)
                } catch (failure: RuntimeException) {
                    logger.error("ROLE CHECK failed on the server in phase {}", phase, failure)
                }
            }
            fun answer(choice: Int) {
                val screen = client.screen as? NpcDialogueScreen
                if (screen == null) {
                    logger.warn("ROLE CHECK no dialogue box to answer in phase {} (screen={})", phase, client.screen?.javaClass?.simpleName)
                    return
                }
                ClientPlayNetworking.send(DialogueAnswerPayload(screen.session, choice))
            }
            fun dialogueOpen() = client.screen is NpcDialogueScreen
            when (phase) {
                0 -> if (client.screen == null) {
                    client.options.guiScale().set(2)
                    client.resizeDisplay()
                    onServer { sp ->
                        val party = Cobblemon.storage.getParty(sp)
                        party.clearParty()
                        Cobblemon.storage.getPC(sp).clearPC()
                        party.add(pokemon("rattata level=20").also { it.currentHealth = 1 })
                        party.add(pokemon("pikachu level=18").also { it.currentHealth = 1 })
                        spawn(sp, "wild_caretaker", 3.0)
                    }
                    next(1)
                }
                1 -> if (since(40)) { onServer { interact(it, "wild_caretaker") }; next(2) }
                2 -> if (dialogueOpen() && since(40)) { shot("1-heal-offer"); answer(0); next(3) }
                3 -> if (since(40)) {
                    shot("2-heal-done")
                    onServer { sp ->
                        val party = Cobblemon.storage.getParty(sp).toList()
                        logger.info("ROLE CHECK heal: party at full health {} (expected true)", party.all { it.currentHealth == it.maxHealth })
                    }
                    answer(-1)
                    next(4)
                }
                4 -> if (since(20)) {
                    onServer { sp ->
                        val trader = spawn(sp, "wild_trader", 4.0)
                        val prepared = jbro.cobblemon.mcc.league.trainer.WildTrader.prepare(trader, sp)
                        val wanted = trader.tags.firstOrNull { it.startsWith("mcc_trade_want:") }?.substringAfter(':')
                        logger.info("ROLE CHECK trader prepared {} wanting {}", prepared, wanted)
                        // The wanted species goes to party slot 1, holding a berry that should come back.
                        val party = Cobblemon.storage.getParty(sp)
                        party.clearParty()
                        party.add(pokemon("$wanted level=24").also { it.swapHeldItem(ItemStack(item("oran_berry")), false) })
                        party.add(pokemon("pikachu level=18"))
                        sp.inventory.clearContent()
                    }
                    next(5)
                }
                5 -> if (since(30)) { onServer { interact(it, "wild_trader") }; next(6) }
                6 -> if (dialogueOpen() && since(40)) { shot("3-trade-offer"); answer(0); next(7) }
                7 -> if (since(40)) { shot("4-trade-confirm"); answer(0); next(8) }
                8 -> if (since(40)) {
                    shot("5-trade-done")
                    onServer { sp ->
                        val got = Cobblemon.storage.getParty(sp).get(0)
                        logger.info("ROLE CHECK trade: slot 1 now {} Lv.{} rarity {} shiny {} OT {} '{}'; berries back {}",
                            got?.species?.resourceIdentifier, got?.level, got?.species?.let(WildSpeciesRarity::of), got?.shiny,
                            got?.originalTrainerType, got?.originalTrainerName, sp.inventory.countItem(item("oran_berry")))
                    }
                    answer(-1)
                    next(9)
                }
                9 -> if (since(30)) {
                    onServer { sp ->
                        val trader = spawn(sp, "wild_trader", -4.0)
                        logger.info("ROLE CHECK second trader prepared {}", jbro.cobblemon.mcc.league.trainer.WildTrader.prepare(trader, sp))
                        interact(sp, "wild_trader", nearest = true)
                    }
                    next(10)
                }
                10 -> if (dialogueOpen() && since(40)) { answer(1); next(11) }
                11 -> if (since(40)) {
                    logger.info("ROLE CHECK PC: screen after the PC answer {}", client.screen?.javaClass?.name)
                    shot("6-pc")
                    client.setScreen(null)
                    next(12)
                }
                12 -> if (since(20)) {
                    onServer { sp ->
                        spawn(sp, "wild_youngster", 3.0)
                        interact(sp, "wild_youngster")
                    }
                    next(13)
                }
                13 -> if (dialogueOpen() && since(40)) { shot("7-trainer-offer"); answer(1); next(14) }
                14 -> if (since(40) && (spawnCauses.get() >= 6 || since(2400))) {
                    logger.info("ROLE CHECK spawns seen {}, caused by a player {}", spawnCauses.get(), playerCauses.get())
                    next(15)
                }
                15 -> if (since(20)) client.stop()
            }
        })
    }

    private fun pokemon(properties: String): Pokemon = PokemonProperties.parse(properties).create()

    private fun item(path: String) = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("cobblemon", path))

    /** Puts an NPC of [npcClass] [ahead] blocks in front of the player (behind when negative), facing them. */
    private fun spawn(player: ServerPlayer, npcClass: String, ahead: Double): NPCEntity {
        val level = player.serverLevel()
        val yaw = Math.toRadians(player.yRot.toDouble())
        val x = player.x - Math.sin(yaw) * ahead
        val z = player.z + Math.cos(yaw) * ahead
        val npc = NPCEntity(level)
        npc.moveTo(x, player.y, z, player.yRot + 180f, 0f)
        npc.npc = checkNotNull(NPCClasses.getByIdentifier(ResourceLocation.fromNamespaceAndPath(NS, npcClass))) { "No NPC class $npcClass" }
        npc.initialize(1)
        level.addFreshEntity(npc)
        return npc
    }

    /** Right-clicks the nearest NPC of [npcClass] through the same callback a real click goes through. */
    private fun interact(player: ServerPlayer, npcClass: String, nearest: Boolean = true) {
        val npc = player.serverLevel().getEntitiesOfClass(NPCEntity::class.java, player.boundingBox.inflate(16.0)) {
            it.npc.id.toString() == "$NS:$npcClass" && "mcc_wild_trainer_defeated" !in it.tags
        }.minByOrNull { if (nearest) it.distanceToSqr(player) else 0.0 }
        if (npc == null) return logger.warn("ROLE CHECK no {} to talk to", npcClass)
        val result = UseEntityCallback.EVENT.invoker().interact(player, player.serverLevel(), InteractionHand.MAIN_HAND, npc, null)
        logger.info("ROLE CHECK talked to {}: {}", npcClass, result)
    }
}
