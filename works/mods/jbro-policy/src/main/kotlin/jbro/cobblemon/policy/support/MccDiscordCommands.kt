package jbro.cobblemon.policy.support

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import jbro.cobblemon.mcc.api.hub.MccDashboard
import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.api.hub.MccDashboardCards
import jbro.cobblemon.mcc.api.hub.MccRankings
import jbro.cobblemon.mcc.api.news.MccNews
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.rewards.BattlePointRewards
import jbro.cobblemon.mcc.api.wiki.WikiApi
import java.util.UUID
import net.minecraft.server.MinecraftServer

/**
 * The slash commands that read More Cobblemon Contents: /전적, /랭킹, /위키 and /도감, and the news it publishes.
 * Loaded only when More Cobblemon Contents is installed.
 */
internal object MccDiscordCommands {
    private const val RANKING_SIZE = 10
    private const val COLOR = 0xF2B24B
    // Discord's limits for an embed's title and field name, a field's value and an embed's description.
    private const val TITLE_LIMIT = 256
    private const val VALUE_LIMIT = 1024
    private const val DESCRIPTION_LIMIT = 4096

    fun register() {
        DiscordCommands.add(record)
        DiscordCommands.add(ranking)
        DiscordCommands.add(wiki)
        DiscordCommands.add(pokedex)
        MccNews.listen { _, event -> DiscordNews.post(DiscordNews.emoji(event.kind) + " " + KoreanText.render(event.message)) }
    }

    /** The operator commands that change More Cobblemon Contents' records; added only with an admin channel. */
    fun registerAdmin() = DiscordCommands.add(battlePoints)

    private val battlePoints = object : DiscordAdminCommand {
        override val name = "bp"
        override val description get() = DiscordText.say("bpDescription")
        override val options = JsonArray().apply {
            add(DiscordCommands.stringOption("player", DiscordText.say("adminPlayerOption")))
            add(DiscordCommands.integerOption("amount", DiscordText.say("bpAmountOption"), 1))
            add(DiscordCommands.stringOption("reason", DiscordText.say("bpReasonOption"), required = false))
        }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = DiscordAdminCommands.profile(server, asked) ?: return DiscordAdminCommands.unknown(asked)
            val amount = options["amount"]?.toLongOrNull()?.takeIf { it > 0 } ?: return DiscordRest.message(DiscordText.say("bpBadAmount"))
            val reason = "Discord ${caller.userName}: " + (options["reason"]?.trim()?.ifEmpty { null } ?: DiscordText.say("bpDefaultReason"))
            val result = BattlePointRewards.award(server, profile.id, UUID.randomUUID(), amount, "jbro_policy:discord_admin", reason)
            return DiscordRest.message(when (result.status) {
                BattlePointRewards.Status.APPLIED, BattlePointRewards.Status.ALREADY_APPLIED ->
                    DiscordText.say("bpDone", "player" to profile.name, "amount" to amount, "balance" to result.balance)
                BattlePointRewards.Status.UNAVAILABLE -> DiscordText.say("bpUnavailable")
                else -> DiscordText.say("bpFailed", "status" to result.status)
            })
        }
    }

    /** The record categories `/전적` asks for, in the order Discord lists them, by label to content ID. */
    private val recordKinds = listOf(
        DiscordText.say("recordLeague") to ManagedBattleContentIds.LEAGUE_CHALLENGE,
        DiscordText.say("recordFactory") to ManagedBattleContentIds.BATTLE_FACTORY,
        DiscordText.say("recordTower") to ManagedBattleContentIds.BATTLE_TOWER,
        "PvP" to ManagedBattleContentIds.PVP,
    )

    /** One category of a trainer's records: the hub dashboard's card for that content alone. */
    private val record = object : DiscordCommand {
        override val name = "전적"
        override val description get() = DiscordText.say("recordsDescription")
        override val options = JsonArray().apply {
            add(DiscordCommands.stringOption("분류", DiscordText.say("recordsKindOption"), recordKinds))
            add(DiscordCommands.stringOption("닉네임", DiscordText.say("recordsNameOption")))
        }

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject {
            val contentId = options["분류"].orEmpty()
            val kind = recordKinds.firstOrNull { it.second == contentId }?.first
                ?: return DiscordRest.message(DiscordText.say("recordsNoKind"))
            val asked = options["닉네임"].orEmpty().trim()
            val profile = server.playerList.getPlayerByName(asked)?.gameProfile
                ?: server.profileCache?.get(asked)?.orElse(null)
                ?: return DiscordRest.message(DiscordText.say("recordsUnknown", "asked" to asked))
            val cards = MccDashboard.cards(server, profile.id).filter { it.contentId == contentId }
            val embed = JsonObject().apply {
                addProperty("title", DiscordText.say("recordsTitle", "player" to profile.name, "kind" to kind).take(TITLE_LIMIT))
                addProperty("color", COLOR)
                addProperty("description", cards.joinToString("\n\n", transform = ::lines).ifBlank { DiscordText.say("noRecords") }
                    .take(DESCRIPTION_LIMIT))
            }
            return DiscordRest.message(embed = embed)
        }

        /** A card's stats, rows and note, one per line; the title already names the card. */
        private fun lines(card: MccDashboardCard): String =
            (card.stats.map { "**${KoreanText.render(it.label)}** ${KoreanText.render(it.value)}" } +
                card.rows.map { row ->
                    val detail = row.detail?.let { " · " + KoreanText.render(it) }.orEmpty()
                    "${KoreanText.render(row.title)}: ${KoreanText.render(row.value)}$detail"
                } + listOfNotNull(card.note?.let { "_${KoreanText.render(it)}_" })).joinToString("\n")
    }

    private val ranking = object : DiscordCommand {
        override val name = "랭킹"
        override val description get() = DiscordText.say("rankingDescription")
        override val options = JsonArray().apply {
            add(DiscordCommands.stringOption("종류", DiscordText.say("rankingKindOption"), listOf(
                DiscordText.say("recordTower") to ManagedBattleContentIds.BATTLE_TOWER,
                DiscordText.say("recordFactory") to ManagedBattleContentIds.BATTLE_FACTORY,
                "PvP" to ManagedBattleContentIds.PVP,
            )))
        }

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject {
            val contentId = options["종류"].orEmpty()
            val boards = MccRankings.boards(server, contentId)
            val embed = JsonObject().apply {
                addProperty("title", DiscordText.say("rankingTitle", "content" to KoreanText.render(MccDashboardCards.contentName(contentId))).take(TITLE_LIMIT))
                addProperty("color", COLOR)
                if (boards.isEmpty()) addProperty("description", DiscordText.say("rankingFailed"))
                add("fields", JsonArray().apply {
                    boards.forEach { board ->
                        add(JsonObject().apply {
                            addProperty("name", (KoreanText.render(MccDashboardCards.formatName(board.formatId)) +
                                " · " + KoreanText.render(board.valueName)).take(TITLE_LIMIT))
                            val top = board.entries.take(RANKING_SIZE)
                            addProperty("value", if (top.isEmpty()) DiscordText.say("noRecords") else
                                top.joinToString("\n") { DiscordText.say("rankingRow", "place" to it.place, "player" to it.playerName, "value" to it.value) }.take(VALUE_LIMIT))
                            addProperty("inline", true)
                        })
                    }
                })
            }
            return DiscordRest.message(embed = embed)
        }
    }

    /**
     * The wiki's address, and to a member who linked their Minecraft account, their own link that also shows their
     * data on the wiki. The link opens their data to whoever has it, so only the caller sees the reply.
     */
    private val wiki = object : DiscordCommand {
        override val name = "위키"
        override val description get() = DiscordText.say("wikiDescription")
        override val ephemeral = true

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject =
            DiscordRest.message(WikiApi.publicUrl()?.let { DiscordText.say("wikiLink", "url" to it) } ?: DiscordText.say("wikiOff"))

        override fun reply(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val base = WikiApi.publicUrl() ?: return DiscordRest.message(DiscordText.say("wikiOff"))
            val player = DiscordLinks.all().entries.firstOrNull { it.value == caller.userId }?.key
                ?: return DiscordRest.message(DiscordText.say("wikiUnlinked", "url" to base))
            val link = WikiApi.sharedLinkFor(player) ?: return DiscordRest.message(DiscordText.say("wikiOff"))
            return DiscordRest.message(DiscordText.say("wikiShared", "url" to link))
        }
    }

    private val pokedex = object : DiscordCommand {
        override val name = "도감"
        override val description get() = DiscordText.say("dexDescription")
        override val options = JsonArray().apply { add(DiscordCommands.stringOption("포켓몬", DiscordText.say("dexOption"))) }

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject {
            val asked = options["포켓몬"].orEmpty().trim()
            val base = WikiApi.publicUrl() ?: return DiscordRest.message(DiscordText.say("wikiOff"))
            val species = speciesNamed(asked) ?: return DiscordRest.message(DiscordText.say("dexUnknown", "asked" to asked))
            val koreanName = koreanName(species) ?: species.name
            return DiscordRest.message(DiscordText.say("dexLink", "name" to koreanName, "url" to "${base.trimEnd('/')}/pages/pokemon.html?id=${species.resourceIdentifier.path}"))
        }
    }

    /**
     * The species' Korean name from Cobblemon's own `ko_kr.json`. Read by key: on a dedicated server a species' name
     * component already holds the English text, so rendering it never reaches the Korean table.
     */
    private fun koreanName(species: com.cobblemon.mod.common.pokemon.Species): String? =
        KoreanText.translate("${species.resourceIdentifier.namespace}.species.${species.resourceIdentifier.path}.name")

    /** A species by its Korean name, English name or ID, ignoring case and spaces, among every loaded species. */
    internal fun speciesNamed(asked: String): com.cobblemon.mod.common.pokemon.Species? {
        val wanted = normalize(asked).takeIf { it.isNotEmpty() } ?: return null
        val all = PokemonSpecies.species
        return all.firstOrNull { species ->
            koreanName(species)?.let(::normalize) == wanted ||
                normalize(species.name) == wanted || species.resourceIdentifier.path == wanted
        }.also { found ->
            if (found == null) jbro.cobblemon.policy.JbroPolicy.LOGGER.info("No species named '{}' among {}", asked, all.size)
        }
    }

    private fun normalize(name: String) = name.lowercase().replace(" ", "")
}
