package jbro.cobblemon.policy.legend

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import java.util.UUID
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.plaza.Plaza
import jbro.cobblemon.policy.support.KoreanText
import net.minecraft.server.MinecraftServer

/**
 * Spawns a Legend again for the player who reports it vanished, at their level cap. The AI reviewing an inquiry has no
 * say in this: the player's words only ask, and [LegendSpawnLog] decides whether their Legend really despawned.
 */
internal object LegendRestore {
    /** How long after its spawn a Legend can still be asked back. */
    private const val WINDOW_MILLIS = 24L * 60 * 60 * 1000
    /** A restored Legend may despawn again, but not forever: past this an operator looks. */
    private const val MAX_RESTORES = 2
    private val VANISHED = Regex("사라|디스폰|증발|소멸|없어[졌짐]|어디\\s*갔")

    sealed interface Decision {
        data object None : Decision
        data class Restore(val entry: LegendSpawnLog.Entry) : Decision
        data class StillThere(val entry: LegendSpawnLog.Entry) : Decision
    }

    class Outcome(val message: String, val changed: Boolean, val ran: String? = null)

    /**
     * What [text] asks for, given [entries] of its writer. It must say a Legend vanished, naming one or at least saying
     * "전설"; a Legend it names other than theirs asks for nothing. [names] holds each Legend id's Korean name.
     */
    fun decide(text: String, entries: List<LegendSpawnLog.Entry>, names: Map<String, String>, now: Long): Decision {
        if (!VANISHED.containsMatchIn(text)) return Decision.None
        val squeezed = squeeze(text)
        val named = names.filterValues { it.isNotEmpty() && squeeze(it) in squeezed }.keys
        if (named.isEmpty() && "전설" !in squeezed) return Decision.None
        val recent = entries.filter { now - it.spawnedAt in 0..WINDOW_MILLIS && (named.isEmpty() || it.legend in named) }
            .sortedByDescending { it.spawnedAt }
        recent.firstOrNull { it.state == LegendSpawnLog.State.DESPAWNED && it.restores < MAX_RESTORES }?.let { return Decision.Restore(it) }
        recent.firstOrNull { it.state == LegendSpawnLog.State.ALIVE }?.let { return Decision.StillThere(it) }
        return Decision.None
    }

    private fun squeeze(text: String) = text.lowercase().replace(Regex("[^0-9a-z가-힣]"), "")

    /** Acts on [text] from [playerId]; null when it does not ask for a Legend back. Call on the server thread. */
    fun attempt(server: MinecraftServer, playerId: UUID, text: String): Outcome? {
        val names = LegendCatalog.all().associate { it.id to (KoreanText.translate(it.nameKey) ?: "") }
        val log = LegendSpawnLog.get(server)
        return when (val decision = decide(text, log.of(playerId), names, System.currentTimeMillis())) {
            Decision.None -> null
            is Decision.StillThere -> {
                val entry = decision.entry
                Outcome("${names[entry.legend]}${subject(names[entry.legend])} 아직 사라진 기록이 없어요. " +
                    "마지막으로 확인한 곳은 X ${entry.x}, Z ${entry.z} 근처예요. 그쪽으로 가면 다시 만날 수 있어요.", false)
            }
            is Decision.Restore -> restore(server, playerId, decision.entry, names[decision.entry.legend].orEmpty())
        }
    }

    private fun restore(server: MinecraftServer, playerId: UUID, entry: LegendSpawnLog.Entry, name: String): Outcome? {
        val legend = LegendCatalog[entry.legend] ?: return null
        val player = server.playerList.getPlayer(playerId)
            ?: return Outcome("$name${subject(name)} 사라진 기록을 확인했어요. 게임에 접속한 상태로 다시 문의해 주세요.", false)
        if (LegendRecords.get(server).has(playerId, legend.id) || LegendRanks.check(player, legend.rank) != LegendRanks.Verdict.Allowed) return null
        val level = player.serverLevel()
        if (Plaza.isPlaza(level) || level.dimension().location().toString() == "myroom:rooms") {
            return Outcome("$name${subject(name)} 사라진 기록을 확인했어요. 광장이나 마이룸에서는 부를 수 없어서, 밖으로 나간 뒤 다시 문의해 주세요.", false)
        }
        if (Cobblemon.battleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return Outcome("$name${subject(name)} 사라진 기록을 확인했어요. 배틀이 끝난 뒤 다시 문의해 주세요.", false)
        }
        val cap = LegendRanks.levelCap(player)
        val spec = listOfNotNull(legend.species, legend.aspect, cap?.let { "level=$it" }, "shiny=yes".takeIf { entry.shiny }).joinToString(" ")
        val spawned = SpawnForCommand.spawn(player, PokemonProperties.parse(spec)) ?: return null
        LegendSpawnLog.get(server).restored(entry.pokemon, spawned.entity.pokemon.uuid)
        JbroPolicy.LOGGER.info("Restored despawned Legend {} for {} at level {}", legend.id, player.gameProfile.name, spawned.entity.pokemon.level)
        return Outcome("$name${subject(name)} 사라진 기록을 확인해서 레벨 ${spawned.entity.pokemon.level}로 바로 앞에 다시 불렀어요.", true,
            "spawnpokemonfor ${player.gameProfile.name} $spec")
    }

    /** 이 or 가, by whether [name] ends on a final consonant. */
    private fun subject(name: String?): String {
        val last = name?.lastOrNull() ?: return "이"
        return if (last in '가'..'힣' && (last - '가') % 28 == 0) "가" else "이"
    }
}
