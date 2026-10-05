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
        override val description = "플레이어에게 BP를 지급한다 (접속하지 않아도 됨)"
        override val options = JsonArray().apply {
            add(DiscordCommands.stringOption("player", "마인크래프트 아이디 또는 UUID"))
            add(DiscordCommands.integerOption("amount", "지급할 BP", 1))
            add(DiscordCommands.stringOption("reason", "지급 사유", required = false))
        }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = DiscordAdminCommands.profile(server, asked) ?: return DiscordAdminCommands.unknown(asked)
            val amount = options["amount"]?.toLongOrNull()?.takeIf { it > 0 } ?: return DiscordRest.message("지급할 BP는 1 이상이어야 해요.")
            val reason = "Discord ${caller.userName}: " + (options["reason"]?.trim()?.ifEmpty { null } ?: "운영진 지급")
            val result = BattlePointRewards.award(server, profile.id, UUID.randomUUID(), amount, "jbro_policy:discord_admin", reason)
            return DiscordRest.message(when (result.status) {
                BattlePointRewards.Status.APPLIED, BattlePointRewards.Status.ALREADY_APPLIED ->
                    "${profile.name}에게 ${amount} BP를 지급했어요. 지금 잔액은 ${result.balance} BP예요."
                BattlePointRewards.Status.UNAVAILABLE -> "BP 저장소를 쓸 수 없어서 지급하지 못했어요."
                else -> "BP를 지급하지 못했어요 (${result.status})."
            })
        }
    }

    /** The record categories `/전적` asks for, in the order Discord lists them, by label to content ID. */
    private val recordKinds = listOf(
        "리그챌린지" to ManagedBattleContentIds.LEAGUE_CHALLENGE,
        "배틀팩토리" to ManagedBattleContentIds.BATTLE_FACTORY,
        "배틀타워" to ManagedBattleContentIds.BATTLE_TOWER,
        "PvP" to ManagedBattleContentIds.PVP,
    )

    /** One category of a trainer's records: the hub dashboard's card for that content alone. */
    private val record = object : DiscordCommand {
        override val name = "전적"
        override val description = "트레이너의 리그챌린지·배틀팩토리·배틀타워·PvP 전적"
        override val options = JsonArray().apply {
            add(DiscordCommands.stringOption("분류", "볼 전적", recordKinds))
            add(DiscordCommands.stringOption("닉네임", "마인크래프트 닉네임"))
        }

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject {
            val contentId = options["분류"].orEmpty()
            val kind = recordKinds.firstOrNull { it.second == contentId }?.first
                ?: return DiscordRest.message("볼 전적 분류를 골라 주세요.")
            val asked = options["닉네임"].orEmpty().trim()
            val profile = server.playerList.getPlayerByName(asked)?.gameProfile
                ?: server.profileCache?.get(asked)?.orElse(null)
                ?: return DiscordRest.message("'$asked' 트레이너를 찾지 못했어요. 서버에 한 번이라도 접속한 닉네임인지 확인해 주세요.")
            val cards = MccDashboard.cards(server, profile.id).filter { it.contentId == contentId }
            val embed = JsonObject().apply {
                addProperty("title", "${profile.name}의 $kind 전적".take(TITLE_LIMIT))
                addProperty("color", COLOR)
                addProperty("description", cards.joinToString("\n\n", transform = ::lines).ifBlank { "아직 기록이 없어요." }
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
        override val description = "배틀타워·배틀팩토리·PvP 순위"
        override val options = JsonArray().apply {
            add(DiscordCommands.stringOption("종류", "볼 순위", listOf(
                "배틀타워" to ManagedBattleContentIds.BATTLE_TOWER,
                "배틀팩토리" to ManagedBattleContentIds.BATTLE_FACTORY,
                "PvP" to ManagedBattleContentIds.PVP,
            )))
        }

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject {
            val contentId = options["종류"].orEmpty()
            val boards = MccRankings.boards(server, contentId)
            val embed = JsonObject().apply {
                addProperty("title", (KoreanText.render(MccDashboardCards.contentName(contentId)) + " 순위").take(TITLE_LIMIT))
                addProperty("color", COLOR)
                if (boards.isEmpty()) addProperty("description", "순위를 불러오지 못했어요.")
                add("fields", JsonArray().apply {
                    boards.forEach { board ->
                        add(JsonObject().apply {
                            addProperty("name", (KoreanText.render(MccDashboardCards.formatName(board.formatId)) +
                                " · " + KoreanText.render(board.valueName)).take(TITLE_LIMIT))
                            val top = board.entries.take(RANKING_SIZE)
                            addProperty("value", if (top.isEmpty()) "아직 기록이 없어요." else
                                top.joinToString("\n") { "${it.place}. ${it.playerName} — ${it.value}" }.take(VALUE_LIMIT))
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
        override val description = "빡켓몬 위키 주소 (계정을 연동했다면 내 정보가 보이는 링크)"
        override val ephemeral = true

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject =
            DiscordRest.message(WikiApi.publicUrl()?.let { "빡켓몬 위키: $it" } ?: "지금은 위키가 꺼져 있어요.")

        override fun reply(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val base = WikiApi.publicUrl() ?: return DiscordRest.message("지금은 위키가 꺼져 있어요.")
            val player = DiscordLinks.all().entries.firstOrNull { it.value == caller.userId }?.key
                ?: return DiscordRest.message("빡켓몬 위키: $base\n게임에서 `/디코인증`으로 계정을 연동하면 내 정보까지 보이는 링크를 받을 수 있어요.")
            val link = WikiApi.sharedLinkFor(player) ?: return DiscordRest.message("지금은 위키가 꺼져 있어요.")
            return DiscordRest.message("내 정보가 보이는 위키 링크: $link\n이 링크를 받은 사람은 내 정보를 볼 수 있으니 다른 사람에게 보내지 마세요.")
        }
    }

    private val pokedex = object : DiscordCommand {
        override val name = "도감"
        override val description = "포켓몬의 위키 도감 페이지"
        override val options = JsonArray().apply { add(DiscordCommands.stringOption("포켓몬", "포켓몬 이름 (한국어 또는 영어)")) }

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject {
            val asked = options["포켓몬"].orEmpty().trim()
            val base = WikiApi.publicUrl() ?: return DiscordRest.message("지금은 위키가 꺼져 있어요.")
            val species = speciesNamed(asked) ?: return DiscordRest.message("'$asked'(이)라는 포켓몬을 찾지 못했어요.")
            val koreanName = koreanName(species) ?: species.name
            return DiscordRest.message("$koreanName 도감: ${base.trimEnd('/')}/pages/pokemon.html?id=${species.resourceIdentifier.path}")
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
