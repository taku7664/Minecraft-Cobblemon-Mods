package jbro.cobblemon.policy.legend

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import java.util.UUID
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.plaza.Plaza
import jbro.cobblemon.policy.support.InquiryVerdict
import jbro.cobblemon.policy.support.KoreanText
import jbro.cobblemon.policy.support.PichuRules
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

    /** What an inquiry the spawn log cannot settle alone puts to an operator. */
    class Ask(val command: InquiryVerdict.SuggestedCommand?, val note: String?, val source: UUID?)

    sealed interface Proposable {
        /** Not about a vanished Legend, or its record shows it is alive, caught or fainted. */
        data object None : Proposable
        data class NoRecord(val legend: String?) : Proposable
        data class Despawned(val entry: LegendSpawnLog.Entry) : Proposable
    }

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

    /**
     * Whether an operator can be asked to spawn again the Legend [text] says vanished. Only a spawn log entry showing the
     * despawn counts: the player's words alone must never put a free Legend up for approval.
     */
    fun proposable(text: String, entries: List<LegendSpawnLog.Entry>, names: Map<String, String>): Proposable {
        if (!VANISHED.containsMatchIn(text)) return Proposable.None
        val squeezed = squeeze(text)
        val named = names.filterValues { it.isNotEmpty() && squeeze(it) in squeezed }.keys
        if (named.size > 1 || (named.isEmpty() && "전설" !in squeezed)) return Proposable.None
        val newest = entries.filter { named.isEmpty() || it.legend in named }.maxByOrNull { it.spawnedAt }
            ?: return Proposable.NoRecord(named.singleOrNull())
        return if (newest.state == LegendSpawnLog.State.DESPAWNED) Proposable.Despawned(newest) else Proposable.None
    }

    private fun squeeze(text: String) = text.lowercase().replace(Regex("[^0-9a-z가-힣]"), "")

    /** What to put to an operator for [text] from [playerId], filled in by the server. Call on the server thread. */
    fun proposal(server: MinecraftServer, playerId: UUID, text: String): Ask? {
        val rules = PichuRules.current()
        if (!rules.flag("legendProposal.enabled")) return null
        val names = names()
        return when (val found = proposable(text, LegendSpawnLog.get(server).of(playerId), names)) {
            Proposable.None -> null
            is Proposable.NoRecord -> Ask(null, rules.text("legendProposal.noRecord",
                "name" to (found.legend?.let(names::get) ?: rules.text("legendProposal.anyLegend"))), null)
            is Proposable.Despawned -> {
                val entry = found.entry
                val legend = LegendCatalog[entry.legend] ?: return null
                val player = server.playerList.getPlayer(playerId) ?: return null
                if (LegendRecords.get(server).has(playerId, legend.id) || LegendRanks.check(player, legend.rank) != LegendRanks.Verdict.Allowed) return null
                val cap = LegendRanks.levelCap(player)
                val spec = listOfNotNull(legend.species, legend.aspect, cap?.let { "level=$it" }, "shiny=yes".takeIf { entry.shiny }).joinToString(" ")
                val level = cap?.let { rules.text("legendProposal.levelCap", "cap" to it) } ?: rules.text("legendProposal.levelWild")
                Ask(InquiryVerdict.SuggestedCommand(
                    rules.text("legendProposal.command", "player" to player.gameProfile.name, "spec" to spec),
                    rules.text("legendProposal.why", "name" to names[entry.legend], "level" to level)), null, entry.pokemon)
            }
        }
    }

    /** Whether the despawn behind a waiting proposal still stands. Call on the server thread. */
    fun stillGone(server: MinecraftServer, source: UUID): Boolean {
        val log = LegendSpawnLog.get(server)
        val entry = log.entry(source) ?: return false
        val newest = log.of(entry.owner).filter { it.legend == entry.legend }.maxByOrNull { it.spawnedAt }
        return newest?.pokemon == source && entry.state == LegendSpawnLog.State.DESPAWNED && !LegendRecords.get(server).has(entry.owner, entry.legend)
    }

    /** Counts the spawn an approved proposal just made as a restore of [source], when it did spawn. Call on the server thread. */
    fun continued(server: MinecraftServer, source: UUID) {
        val log = LegendSpawnLog.get(server)
        val entry = log.entry(source) ?: return
        val spawned = log.of(entry.owner).filter { it.legend == entry.legend }.maxByOrNull { it.spawnedAt } ?: return
        if (spawned.pokemon != source) log.restored(source, spawned.pokemon)
    }

    /** Acts on [text] from [playerId]; null when it does not ask for a Legend back. Call on the server thread. */
    fun attempt(server: MinecraftServer, playerId: UUID, text: String): Outcome? {
        val names = names()
        val log = LegendSpawnLog.get(server)
        return when (val decision = decide(text, log.of(playerId), names, System.currentTimeMillis())) {
            Decision.None -> null
            is Decision.StillThere -> {
                val entry = decision.entry
                Outcome(say("legendStillThere", "subject" to subject(names[entry.legend]), "x" to entry.x, "z" to entry.z), false)
            }
            is Decision.Restore -> restore(server, playerId, decision.entry, names[decision.entry.legend].orEmpty())
        }
    }

    private fun restore(server: MinecraftServer, playerId: UUID, entry: LegendSpawnLog.Entry, name: String): Outcome? {
        val legend = LegendCatalog[entry.legend] ?: return null
        val player = server.playerList.getPlayer(playerId)
            ?: return Outcome(say("legendOffline", "subject" to subject(name)), false)
        if (LegendRecords.get(server).has(playerId, legend.id) || LegendRanks.check(player, legend.rank) != LegendRanks.Verdict.Allowed) return null
        val level = player.serverLevel()
        if (Plaza.isPlaza(level) || level.dimension().location().toString() == "myroom:rooms") {
            return Outcome(say("legendRestricted", "subject" to subject(name)), false)
        }
        if (Cobblemon.battleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return Outcome(say("legendInBattle", "subject" to subject(name)), false)
        }
        val cap = LegendRanks.levelCap(player)
        val spec = listOfNotNull(legend.species, legend.aspect, cap?.let { "level=$it" }, "shiny=yes".takeIf { entry.shiny }).joinToString(" ")
        val spawned = SpawnForCommand.spawn(player, PokemonProperties.parse(spec)) ?: return null
        LegendSpawnLog.get(server).restored(entry.pokemon, spawned.entity.pokemon.uuid)
        JbroPolicy.LOGGER.info("Restored despawned Legend {} for {} at level {}", legend.id, player.gameProfile.name, spawned.entity.pokemon.level)
        return Outcome(say("legendRestored", "subject" to subject(name), "level" to spawned.entity.pokemon.level), true,
            "spawnpokemonfor ${player.gameProfile.name} $spec")
    }

    private fun names() = LegendCatalog.all().associate { it.id to (KoreanText.translate(it.nameKey) ?: "") }

    private fun say(key: String, vararg values: Pair<String, Any?>) = PichuRules.current().text("texts.$key", *values)

    /** [name] with 이 or 가, by whether it ends on a final consonant. */
    private fun subject(name: String?): String {
        val last = name?.lastOrNull() ?: return "${name.orEmpty()}이"
        return if (last in '가'..'힣' && (last - '가') % 28 == 0) "${name}가" else "${name}이"
    }
}
