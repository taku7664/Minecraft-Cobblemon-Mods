package jbro.cobblemon.mcc.league.trainer

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.rewards.BattlePointRewards
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import kotlin.random.Random
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.item.ItemStack

/**
 * One thing the wild reward pool can give: [bp] BP, or [count] of one of [items] picked at random. It is offered only
 * to players whose level cap is within [minCap]..[maxCap].
 */
data class WildReward(
    val bp: IntRange?,
    val items: List<String>,
    val count: IntRange,
    val weight: Int,
    val minCap: Int,
    val maxCap: Int,
) {
    init {
        require((bp == null) != items.isEmpty()) { "A reward gives either BP or items" }
        require(bp == null || bp.first in 1..1_000 && bp.last >= bp.first && bp.last <= 1_000) { "Invalid BP range: $bp" }
        require(items.all { it.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")) }) { "Invalid item ID in $items" }
        require(count.first in 1..64 && count.last >= count.first && count.last <= 64) { "Invalid count: $count" }
        require(weight > 0) { "Weight must be positive" }
        require(minCap in 1..100 && maxCap in minCap..100) { "Invalid cap range: $minCap-$maxCap" }
    }
}

/** Reads `league-challenge/wild_rewards.json` (`docs/WILD_NPC_ROLES.md`). */
object WildRewardPoolParser {
    fun parse(json: String): List<WildReward> {
        val entries = JsonParser.parseString(json).asJsonObject.getAsJsonArray("entries")
            ?: throw IllegalArgumentException("The reward pool has no entries")
        val rewards = entries.mapIndexed { index, element ->
            try {
                read(element.asJsonObject)
            } catch (failure: RuntimeException) {
                throw IllegalArgumentException("Reward ${index + 1}: ${failure.message}", failure)
            }
        }
        require(rewards.isNotEmpty()) { "The reward pool is empty" }
        return rewards
    }

    private fun read(root: JsonObject): WildReward {
        val items = buildList {
            root.get("item")?.let { add(it.asString) }
            root.getAsJsonArray("items")?.forEach { add(it.asString) }
        }
        return WildReward(
            bp = root.get("bp")?.let(::range),
            items = items,
            count = root.get("count")?.let(::range) ?: 1..1,
            weight = root.get("weight")?.asInt ?: throw IllegalArgumentException("No weight"),
            minCap = root.get("min_cap")?.asInt ?: 1,
            maxCap = root.get("max_cap")?.asInt ?: 100,
        )
    }

    /** `[2, 5]` or a single number. */
    private fun range(element: JsonElement): IntRange = if (element is JsonArray) {
        require(element.size() == 2) { "A range is [min, max]" }
        element[0].asInt..element[1].asInt
    } else {
        element.asInt..element.asInt
    }
}

/** The pool quiz and gift NPCs give from. */
internal object WildRewards {
    @Volatile
    private var pool: List<WildReward> = emptyList()

    /**
     * Gives [player] one reward drawn for their level cap and names what it was, such as "3 BP" or "경험사탕S ×2";
     * null when nothing could be given. Items that do not fit in the inventory drop at their feet.
     */
    fun give(player: ServerPlayer, reason: String): Component? {
        val cap = WildTrainers.cap(player)
        val reward = pick(pool.filter { cap in it.minCap..it.maxCap }) ?: return null
        reward.bp?.let { range ->
            val amount = range.random().toLong()
            val result = BattlePointRewards.award(player.server, player.uuid, UUID.randomUUID(), amount,
                ManagedBattleContentIds.LEAGUE_CHALLENGE, reason)
            if (!result.accepted) {
                Mod.LOGGER.warn("Wild reward BP for {} was not paid: {}", player.uuid, result.status)
                return null
            }
            return Component.literal("$amount BP")
        }
        val id = reward.items.random()
        val item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElse(null)
        if (item == null) {
            Mod.LOGGER.warn("Wild reward item {} is not registered", id)
            return null
        }
        val stack = ItemStack(item, reward.count.random())
        val label = stack.hoverName.copy().append(" ×${stack.count}")
        if (!player.inventory.add(stack)) player.drop(stack, false)
        return label
    }

    private fun pick(rewards: List<WildReward>): WildReward? {
        if (rewards.isEmpty()) return null
        var roll = Random.nextInt(rewards.sumOf { it.weight })
        for (reward in rewards) {
            if (roll < reward.weight) return reward
            roll -= reward.weight
        }
        return rewards.last()
    }

    object Resources : SimpleSynchronousResourceReloadListener {
        private val file = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "league-challenge/wild_rewards.json")

        override fun getFabricId(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "wild_rewards")

        override fun onResourceManagerReload(manager: ResourceManager) {
            try {
                val text = manager.getResource(file).orElse(null)?.openAsReader()?.use { it.readText() }
                if (text == null) {
                    Mod.LOGGER.warn("No wild reward pool at {}", file)
                    return
                }
                pool = WildRewardPoolParser.parse(text)
                Mod.LOGGER.info("Loaded {} wild rewards", pool.size)
            } catch (failure: RuntimeException) {
                Mod.LOGGER.error("Wild reward pool reload rejected; keeping the previous pool", failure)
            }
        }
    }
}
