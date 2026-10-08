package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.storage.pc.link.PCLinkManager
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.net.messages.client.storage.pc.OpenPCPacket
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.JsonParser
import java.util.UUID
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.mcc.league.server.WildSpawnSpecies
import jbro.cobblemon.npc.api.NpcTalkChoice
import jbro.cobblemon.npc.api.NpcTalks
import kotlin.random.Random
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.server.level.ServerPlayer

/**
 * The trade NPC (`docs/WILD_NPC_ROLES.md`). When it spawns it picks a species the player who brought it has, in their
 * party or PC; given that species from party slot 1, it gives back a Pokemon one rarity step rarer, whose original
 * trainer is the NPC. Only that player may trade with it, and it leaves after the trade.
 */
internal object WildTrader {
    private const val KEY = "${WildNpcRoles.KEY}.trade"
    private const val WANT_TAG = "mcc_trade_want:"
    private const val OWNER_TAG = "mcc_trade_owner:"
    private const val OWNER_NAME_TAG = "mcc_trade_owner_name:"
    private const val SHINY_ONE_IN = 100
    private const val PC_REACH = 8.0
    private val wantWeights = mapOf(WildRarity.COMMON to 80, WildRarity.UNCOMMON to 15, WildRarity.RARE to 5)

    /**
     * Picks what a newly spawned trader wants from [player]'s party and PC and writes it on the NPC. False when
     * [player] has nothing it could want, and the trader should not spawn.
     */
    fun prepare(npc: NPCEntity, player: ServerPlayer?): Boolean {
        if (player == null) return false
        val owned = (Cobblemon.storage.getParty(player) + Cobblemon.storage.getPC(player)).toList()
        val byRarity = owned.mapNotNull { pokemon -> WildSpeciesRarity.of(pokemon.species)?.let { it to pokemon } }
            .groupBy({ it.first }, { it.second })
        val weighted = wantWeights.filterKeys { it in byRarity }
        val rarity = if (weighted.isNotEmpty()) pick(weighted) else WildRarity.ULTRA_RARE.takeIf { it in byRarity } ?: return false
        val wanted = byRarity.getValue(rarity).random().species.resourceIdentifier.path
        npc.addTag(WANT_TAG + wanted)
        npc.addTag(OWNER_TAG + player.uuid)
        npc.addTag(OWNER_NAME_TAG + player.gameProfile.name)
        return true
    }

    fun talk(player: ServerPlayer, npc: NPCEntity) {
        val wanted = tagValue(npc, WANT_TAG)?.let(PokemonSpecies::getByName)
        val owner = tagValue(npc, OWNER_TAG)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (wanted == null || owner == null) return WildTrainers.sayLine(player, npc, "$KEY.confused")
        if (owner != player.uuid) {
            return NpcTalks.open(player, WildTrainers.talk(npc,
                Component.translatable("$KEY.not_yours", tagValue(npc, OWNER_NAME_TAG) ?: "?")))
        }
        NpcTalks.open(player, WildTrainers.talk(npc, Component.translatable("$KEY.offer", wanted.translatedName), listOf(
            NpcTalkChoice(Component.translatable("$KEY.choice.trade")) { confirm(it, npc) },
            NpcTalkChoice(Component.translatable("$KEY.choice.pc")) { openPc(it, npc) },
            NpcTalkChoice(Component.translatable("$KEY.choice.later")),
        )))
    }

    /** Asks about the Pokemon in party slot 1, naming it so the player knows which one goes. */
    private fun confirm(player: ServerPlayer, npc: NPCEntity) {
        val wanted = tagValue(npc, WANT_TAG) ?: return
        val first = Cobblemon.storage.getParty(player).get(0)
        if (first == null || first.species.resourceIdentifier.path != wanted) {
            val species = PokemonSpecies.getByName(wanted)?.translatedName ?: Component.literal(wanted)
            return NpcTalks.open(player, WildTrainers.talk(npc, Component.translatable("$KEY.not_first", species)))
        }
        val lines = mutableListOf<Component>(Component.translatable("$KEY.confirm", label(first), first.level))
        val held = first.heldItem()
        if (!held.isEmpty) lines += Component.translatable("$KEY.item_back", held.hoverName)
        val chosen = first.uuid
        NpcTalks.open(player, WildTrainers.talk(npc, lines, listOf(
            NpcTalkChoice(Component.translatable("$KEY.choice.send")) { trade(it, npc, chosen) },
            NpcTalkChoice(Component.translatable("$KEY.choice.rethink")),
        )))
    }

    private fun trade(player: ServerPlayer, npc: NPCEntity, chosen: UUID) {
        if (!npc.isAlive || WildTrainers.isLeaving(npc)) return
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) return
        val party = Cobblemon.storage.getParty(player)
        val given = party.get(0)
        // The party may have changed while the box was open.
        if (given == null || given.uuid != chosen || given.species.resourceIdentifier.path != tagValue(npc, WANT_TAG)) {
            return confirm(player, npc)
        }
        val rarity = WildSpeciesRarity.of(given.species) ?: return WildTrainers.sayLine(player, npc, "$KEY.confused")
        val received = create(player, npc, rarity.next(), given.level) ?: return WildTrainers.sayLine(player, npc, "$KEY.confused")
        // The given Pokemon leaves with the NPC; what it held comes back to the player.
        val held = given.heldItem().copy()
        if (!party.remove(given)) return
        if (!held.isEmpty && !player.inventory.add(held)) player.drop(held, false)
        party.add(received)
        WildNpcRoles.markDone(npc, player)
        WildTrainers.leave(npc)
        Mod.LOGGER.info("{} traded {} for {} with a wild trader", player.gameProfile.name,
            given.species.resourceIdentifier, received.species.resourceIdentifier)
        NpcTalks.open(player, WildTrainers.talk(npc, Component.translatable("$KEY.done", label(received))))
    }

    /** Opens the player's PC while they stay by the trader, so they can bring the wanted Pokemon to slot 1. */
    private fun openPc(player: ServerPlayer, npc: NPCEntity) {
        val pc = Cobblemon.storage.getPC(player)
        PCLinkManager.addLink(player.uuid, pc) { it.distanceToSqr(npc) <= PC_REACH * PC_REACH && npc.isAlive }
        OpenPCPacket(pc).sendToPlayer(player)
    }

    /** The Pokemon given back: a random species of [rarity], at [level] but never past the player's cap. */
    private fun create(player: ServerPlayer, npc: NPCEntity, rarity: WildRarity, level: Int): Pokemon? {
        val species = WildSpeciesRarity.species(rarity).randomOrNull() ?: return null
        val at = level.coerceAtMost(WildTrainers.cap(player)).coerceIn(1, 100)
        val pokemon = PokemonProperties.parse("${species.resourceIdentifier.path} level=$at").create()
        WildSpawnSpecies.fit(pokemon, at)
        pokemon.shiny = Random.nextInt(SHINY_ONE_IN) == 0
        wildRolls(pokemon)
        pokemon.setOriginalTrainer(originalTrainerName(npc))
        pokemon.heal()
        return pokemon
    }

    /** jbro-policy's wild IV bands and hidden ability chance, when the server runs it. */
    private fun wildRolls(pokemon: Pokemon) {
        if (!FabricLoader.getInstance().isModLoaded("jbro_policy")) return
        try {
            Class.forName("jbro.cobblemon.policy.wild.WildPokemonPolicy").getMethod("applyWildRolls", Pokemon::class.java)
                .invoke(null, pokemon)
        } catch (failure: ReflectiveOperationException) {
            Mod.LOGGER.warn("Wild rolls could not be applied to a traded Pokemon", failure)
        }
    }

    /**
     * The NPC's name as its nameplate reads in Korean, such as "낚시꾼 Alec". The title is a translation key the
     * dedicated server cannot read itself, so it is looked up in the mod's own Korean file.
     */
    private fun originalTrainerName(npc: NPCEntity): String {
        val title = npc.npc.names.firstOrNull()
        val key = (title?.contents as? TranslatableContents)?.key
        val korean = key?.let { koreanNames[it] } ?: title?.string ?: "?"
        val personal = npc.aspects.firstOrNull { it.startsWith("rct_") }?.let(WildTrainers::personalName)
        return if (personal == null) korean else "$korean $personal"
    }

    private val koreanNames: Map<String, String> by lazy {
        val stream = WildTrader::class.java.getResourceAsStream("/assets/${Mod.MOD_ID}/lang/ko_kr.json") ?: return@lazy emptyMap()
        stream.reader(Charsets.UTF_8).use { reader ->
            JsonParser.parseReader(reader).asJsonObject.entrySet().associate { it.key to it.value.asString }
        }
    }

    /** The Pokemon's nickname or species, with a star when it is shiny. */
    private fun label(pokemon: Pokemon): Component {
        val name = (pokemon.nickname ?: pokemon.species.translatedName).copy()
        return if (pokemon.shiny) name.append("★") else name
    }

    private fun tagValue(npc: NPCEntity, prefix: String): String? =
        npc.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    private fun pick(weights: Map<WildRarity, Int>): WildRarity {
        var roll = Random.nextInt(weights.values.sum())
        for ((rarity, weight) in weights) {
            if (roll < weight) return rarity
            roll -= weight
        }
        return weights.keys.last()
    }
}
