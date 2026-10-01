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
import jbro.cobblemon.mcc.api.wiki.WikiApi
import net.minecraft.server.MinecraftServer

/**
 * The slash commands that read More Cobblemon Contents: /전적, /랭킹, /위키 and /도감, and the news it publishes.
 * Loaded only when More Cobblemon Contents is installed.
 */
internal object MccDiscordCommands {
    private const val RANKING_SIZE = 10
    private const val COLOR = 0xF2B24B
    // Discord's limits for an embed's title and field name, and for a field's value.
    private const val TITLE_LIMIT = 256
    private const val VALUE_LIMIT = 1024

    fun register() {
        DiscordCommands.add(record)
        DiscordCommands.add(ranking)
        DiscordCommands.add(wiki)
        DiscordCommands.add(pokedex)
        MccNews.listen { _, event -> DiscordNews.post(DiscordNews.emoji(event.kind) + " " + KoreanText.render(event.message)) }
    }

    private val record = object : DiscordCommand {
        override val name = "전적"
        override val description = "트레이너의 리그·배틀타워·배틀팩토리·PvP 전적"
        override val options = JsonArray().apply { add(DiscordCommands.stringOption("닉네임", "마인크래프트 닉네임")) }

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject {
            val asked = options["닉네임"].orEmpty().trim()
            val profile = server.playerList.getPlayerByName(asked)?.gameProfile
                ?: server.profileCache?.get(asked)?.orElse(null)
                ?: return DiscordRest.message("'$asked' 트레이너를 찾지 못했어요. 서버에 한 번이라도 접속한 닉네임인지 확인해 주세요.")
            val cards = MccDashboard.cards(server, profile.id)
            val embed = JsonObject().apply {
                addProperty("title", "${profile.name}의 전적".take(TITLE_LIMIT))
                addProperty("color", COLOR)
                if (cards.isEmpty()) addProperty("description", "아직 기록이 없어요.")
                add("fields", JsonArray().apply { cards.take(25).forEach { add(field(it)) } })
            }
            return DiscordRest.message(embed = embed)
        }

        private fun field(card: MccDashboardCard) = JsonObject().apply {
            addProperty("name", KoreanText.render(card.title).ifBlank { card.contentId }.take(TITLE_LIMIT))
            val lines = card.stats.map { "**${KoreanText.render(it.label)}** ${KoreanText.render(it.value)}" } +
                card.rows.map { row ->
                    val detail = row.detail?.let { " · " + KoreanText.render(it) }.orEmpty()
                    "${KoreanText.render(row.title)}: ${KoreanText.render(row.value)}$detail"
                } + listOfNotNull(card.note?.let { "_${KoreanText.render(it)}_" })
            addProperty("value", lines.joinToString("\n").ifBlank { "-" }.take(VALUE_LIMIT))
            addProperty("inline", false)
        }
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

    private val wiki = object : DiscordCommand {
        override val name = "위키"
        override val description = "빡켓몬 위키 주소"

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject =
            DiscordRest.message(WikiApi.publicUrl()?.let { "빡켓몬 위키: $it" } ?: "지금은 위키가 꺼져 있어요.")
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
