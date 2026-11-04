package jbro.cobblemon.mcc.internal.pvp

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.world.level.storage.LevelResource
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * Every ranked record of a world, in an SQLite file beside the match history: one row per player and format, and
 * the battles already settled so a settlement repeated after a restart cannot count twice.
 */
internal class PvpRankedStore(private val file: Path) {
    private val source = SQLiteDataSource(SQLiteConfig().apply {
        setJournalMode(SQLiteConfig.JournalMode.WAL)
        setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        setBusyTimeout(5_000)
    }).apply { url = "jdbc:sqlite:${file.toAbsolutePath()}" }

    init {
        Files.createDirectories(file.toAbsolutePath().parent)
        connect { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ratings (
                        player_uuid TEXT NOT NULL,
                        format TEXT NOT NULL,
                        name TEXT NOT NULL,
                        rating REAL NOT NULL,
                        peak REAL NOT NULL,
                        wins INTEGER NOT NULL,
                        losses INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, format)
                    )""".trimIndent())
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settled (
                        battle_id TEXT PRIMARY KEY,
                        settled_at INTEGER NOT NULL
                    )""".trimIndent())
            }
        }
    }

    private fun <T> connect(action: (Connection) -> T): T = source.connection.use(action)

    fun records(): List<PvpRankedRecord> = connect { connection ->
        connection.prepareStatement("SELECT player_uuid, format, name, rating, peak, wins, losses FROM ratings").use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val format = PvpBattleFormat.entries.firstOrNull { it.recordId == rows.getString(2) } ?: continue
                        add(PvpRankedRecord(UUID.fromString(rows.getString(1)), format, rows.getString(3),
                            rows.getDouble(4), rows.getDouble(5), rows.getInt(6), rows.getInt(7)))
                    }
                }
            }
        }
    }

    fun settledBattles(): List<UUID> = connect { connection ->
        connection.prepareStatement("SELECT battle_id FROM settled").use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(UUID.fromString(rows.getString(1))) } }
        }
    }

    /** Both records of [result] and its battle, in one transaction. */
    fun save(result: PvpRankedResult, at: Long) = connect { connection ->
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT OR IGNORE INTO settled (battle_id, settled_at) VALUES (?, ?)").use { statement ->
                statement.setString(1, result.battleId.toString())
                statement.setLong(2, at)
                statement.executeUpdate()
            }
            connection.prepareStatement("""
                INSERT INTO ratings (player_uuid, format, name, rating, peak, wins, losses, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid, format) DO UPDATE SET name = excluded.name, rating = excluded.rating,
                    peak = excluded.peak, wins = excluded.wins, losses = excluded.losses, updated_at = excluded.updated_at
                """.trimIndent()).use { statement ->
                listOf(result.winnerAfter, result.loserAfter).forEach { record ->
                    statement.setString(1, record.playerId.toString())
                    statement.setString(2, record.format.recordId)
                    statement.setString(3, record.name)
                    statement.setDouble(4, record.rating)
                    statement.setDouble(5, record.peak)
                    statement.setInt(6, record.wins)
                    statement.setInt(7, record.losses)
                    statement.setLong(8, at)
                    statement.executeUpdate()
                }
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        }
    }
}

/**
 * The world's ranked ladder while a server runs: read whole when the world opens, kept in memory, and each settled
 * game written off the server thread in order.
 */
internal object PvpRankedHistory {
    val ladder = PvpRankedLadder()
    @Volatile
    private var store: PvpRankedStore? = null
    private var writer: ExecutorService? = null

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            ladder.clear()
            store = try {
                PvpRankedStore(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("mcc_pvp_ranked.sqlite")).also {
                    ladder.load(it.records(), it.settledBattles())
                }
            } catch (failure: Exception) {
                MoreCobblemonContents.LOGGER.error("PvP ranked ladder could not be opened; ratings are kept only until the server stops", failure)
                null
            }
            writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mcc-pvp-ranked").apply { isDaemon = true } }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            writer?.let { pool ->
                pool.shutdown()
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) MoreCobblemonContents.LOGGER.warn("PvP ranked writes did not finish")
            }
            writer = null
            store = null
            ladder.clear()
        }
    }

    /** Settles one ranked battle in memory now and on disk later; null when it was settled before. */
    fun settle(battleId: UUID, format: PvpBattleFormat, winnerId: UUID, winnerName: String, loserId: UUID, loserName: String): PvpRankedResult? {
        val result = ladder.settle(battleId, format, winnerId, winnerName, loserId, loserName) ?: return null
        val target = store
        val at = System.currentTimeMillis()
        if (target != null) {
            writer?.execute {
                try {
                    target.save(result, at)
                } catch (failure: Exception) {
                    MoreCobblemonContents.LOGGER.error("PvP ranked result of battle {} could not be saved", battleId, failure)
                }
            }
        }
        return result
    }
}
