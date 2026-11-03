package jbro.cobblemon.policy.support

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.battles.BattleRegistry
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

/**
 * A log of every battle on the server, wild, trainer, tower, factory, league and PvP alike, kept for a week so an
 * inquiry about a battle has its turns to look at. Each is one gzip file under `jbro-policy/battle-logs/<date>/`: a JSON
 * header line, the battle as players read it, then Showdown's own messages.
 */
internal object BattleRecords {
    const val KEEP_DAYS = 7L
    private const val CHECK_TICKS = 20
    private const val BATTLES_PER_INQUIRY = 3
    private val zone: ZoneId = ZoneId.systemDefault()
    private val TIME = DateTimeFormatter.ofPattern("HHmmss").withZone(zone)
    private val gson = Gson()
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "jbro-policy-battle-records").apply { isDaemon = true } }
    private val active = ConcurrentHashMap<UUID, Pair<PokemonBattle, Long>>()
    private lateinit var root: Path

    /** The header line; a battle's [players] are the ones it can be looked up by. */
    data class Header(val battle: String, val start: Long, val end: Long, val format: String, val turns: Int,
        val players: List<String>, val actors: List<String>)

    class Found(val header: Header, val path: Path, val body: String) {
        /** One line for the operators' card. */
        fun summary() = PichuRules.current().text("ai.battleSummary", "time" to TIME.format(Instant.ofEpochMilli(header.start)),
            "actors" to header.actors.joinToString(" vs "), "turns" to header.turns, "file" to "${path.parent.fileName}/${path.fileName}")
    }

    fun register(gameDir: Path) {
        root = gameDir.resolve(JbroPolicy.MOD_ID.replace('_', '-')).resolve("battle-logs")
        CobblemonEvents.BATTLE_STARTED_POST.subscribe(Priority.LOWEST) { event ->
            active[event.battle.battleId] = event.battle to System.currentTimeMillis()
        }
        var ticks = 0
        ServerTickEvents.END_SERVER_TICK.register {
            if (++ticks % CHECK_TICKS == 0) finish(false)
        }
        ServerLifecycleEvents.SERVER_STARTED.register { writer.execute { prune(root, LocalDate.now(zone)) } }
        ServerLifecycleEvents.SERVER_STOPPING.register { finish(true) }
    }

    /** Hands over battles that ended, or all of them when the server is stopping. Runs on the server thread, where the logs are safe to copy. */
    private fun finish(all: Boolean) {
        for ((id, entry) in active) {
            val (battle, start) = entry
            if (!all && !battle.ended && BattleRegistry.getBattle(id) != null) continue
            active.remove(id)
            runCatching {
                val header = Header(id.toString(), start, System.currentTimeMillis(), battle.format.battleType.name, battle.turn,
                    battle.playerUUIDs.map(UUID::toString), battle.actors.map { actor ->
                        KoreanText.render(actor.getName()) + actor.pokemonList.joinToString(", ", " (", ")") { "${it.originalPokemon.species.resourceIdentifier.path} ${it.originalPokemon.level}" }
                    })
                val chat = battle.chatLog.map(KoreanText::render)
                val showdown = battle.showdownMessages.toList()
                writer.execute {
                    try { save(root, header, chat, showdown) } catch (failure: Exception) { JbroPolicy.LOGGER.warn("Could not save battle {}", id, failure) }
                }
            }.onFailure { JbroPolicy.LOGGER.warn("Could not record battle {}", id, it) }
        }
    }

    internal fun save(root: Path, header: Header, chat: List<String>, showdown: List<String>): Path {
        val day = Instant.ofEpochMilli(header.start).atZone(zone).toLocalDate().toString()
        val file = root.resolve(day).resolve("${TIME.format(Instant.ofEpochMilli(header.start))}-${header.battle}.log.gz")
        Files.createDirectories(file.parent)
        GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter(StandardCharsets.UTF_8).use { out ->
            out.write(gson.toJson(header))
            out.write("\n\n[진행]\n")
            chat.forEach { out.write(it); out.newLine() }
            out.write("\n[Showdown]\n")
            showdown.forEach { out.write(it); out.newLine() }
        }
        return file
    }

    internal fun prune(root: Path, today: LocalDate) {
        if (!Files.isDirectory(root)) return
        val days = Files.list(root).use { it.toList() }
        for (day in days) {
            val date = runCatching { LocalDate.parse(day.fileName.toString()) }.getOrNull() ?: continue
            if (!date.isBefore(today.minusDays(KEEP_DAYS))) continue
            Files.walk(day).use { paths -> paths.sorted(Comparator.reverseOrder()).toList() }.forEach(Files::deleteIfExists)
        }
    }

    /** The latest battles of [player] that overlap [from]..[to] in milliseconds, each body at most [maxChars] from its end. */
    fun forPlayer(player: UUID, from: Long, to: Long, maxChars: Int): List<Found> =
        if (::root.isInitialized) read(root, player, from, to, maxChars) else emptyList()

    internal fun read(root: Path, player: UUID, from: Long, to: Long, maxChars: Int): List<Found> {
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        val files = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
            .map { root.resolve(it.toString()) }.filter(Files::isDirectory)
            .flatMap { dir -> Files.list(dir).use { it.toList() } }.toList()
        val found = files.mapNotNull { file ->
            runCatching {
                BufferedReader(InputStreamReader(GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)).use { reader ->
                    val header = gson.fromJson(reader.readLine(), Header::class.java)
                    if (player.toString() !in header.players || header.end < from || header.start > to) null
                    else Found(header, file, reader.readText().trim().takeLast(maxChars))
                }
            }.getOrNull()
        }
        return found.sortedByDescending { it.header.start }.take(BATTLES_PER_INQUIRY)
    }
}
