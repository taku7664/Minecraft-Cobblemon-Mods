package jbro.cobblemon.mcc.internal.pvp

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/** One finished PvP battle: who beat whom, in which format, and when. Names are as they were then. */
internal data class PvpMatch(
    val id: Long,
    val battleId: String,
    val playedAt: Long,
    val format: String,
    val winnerId: UUID,
    val winnerName: String,
    val loserId: UUID,
    val loserName: String,
)

/** A player's results against one opponent. */
internal data class PvpOpponentRecord(val opponentId: UUID, val opponentName: String, val wins: Int, val losses: Int)

/**
 * Every PvP result of a world, in an SQLite file: one row per battle and nothing about the teams. Recording the
 * same battle twice keeps one row, so a retried completion cannot count twice. Each call opens its own
 * connection, which SQLite's write-ahead log lets readers do while a write is in progress.
 */
internal class PvpMatchStore(private val file: Path) {
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
                    CREATE TABLE IF NOT EXISTS matches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        battle_id TEXT NOT NULL UNIQUE,
                        played_at INTEGER NOT NULL,
                        format TEXT NOT NULL,
                        winner_uuid TEXT NOT NULL,
                        winner_name TEXT NOT NULL,
                        loser_uuid TEXT NOT NULL,
                        loser_name TEXT NOT NULL
                    )""".trimIndent())
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS matches_winner ON matches(winner_uuid, id)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS matches_loser ON matches(loser_uuid, id)")
            }
        }
    }

    private fun <T> connect(action: (Connection) -> T): T = source.connection.use(action)

    /** Records one battle; false when this battle was already recorded. */
    fun record(battleId: UUID, playedAt: Long, format: String, winnerId: UUID, winnerName: String, loserId: UUID, loserName: String): Boolean =
        connect { connection ->
            connection.prepareStatement("""
                INSERT OR IGNORE INTO matches (battle_id, played_at, format, winner_uuid, winner_name, loser_uuid, loser_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent()).use { insert ->
                insert.setString(1, battleId.toString())
                insert.setLong(2, playedAt)
                insert.setString(3, format)
                insert.setString(4, winnerId.toString())
                insert.setString(5, winnerName)
                insert.setString(6, loserId.toString())
                insert.setString(7, loserName)
                insert.executeUpdate() == 1
            }
        }

    /**
     * Newest first, [limit] at most, and only rows older than [before] when given, for paging. With [player], only
     * the battles that player fought.
     */
    fun matches(player: UUID? = null, limit: Int = 50, before: Long? = null): List<PvpMatch> = connect { connection ->
        val where = buildList {
            if (player != null) add("(winner_uuid = ? OR loser_uuid = ?)")
            if (before != null) add("id < ?")
        }.joinToString(" AND ").let { if (it.isEmpty()) "" else "WHERE $it" }
        connection.prepareStatement("SELECT * FROM matches $where ORDER BY id DESC LIMIT ?").use { query ->
            var index = 1
            if (player != null) {
                query.setString(index++, player.toString())
                query.setString(index++, player.toString())
            }
            if (before != null) query.setLong(index++, before)
            query.setInt(index, limit.coerceIn(1, MAX_PAGE))
            query.executeQuery().use { rows -> generateSequence { if (rows.next()) match(rows) else null }.toList() }
        }
    }

    /** [player]'s wins and losses against each opponent, most battles first. */
    fun opponents(player: UUID): List<PvpOpponentRecord> = connect { connection ->
        connection.prepareStatement("""
            SELECT opponent, MAX(name) AS name, SUM(won) AS wins, SUM(1 - won) AS losses, MAX(id) AS last FROM (
                SELECT loser_uuid AS opponent, loser_name AS name, 1 AS won, id FROM matches WHERE winner_uuid = ?
                UNION ALL
                SELECT winner_uuid AS opponent, winner_name AS name, 0 AS won, id FROM matches WHERE loser_uuid = ?
            ) GROUP BY opponent ORDER BY wins + losses DESC, last DESC""".trimIndent()).use { query ->
            query.setString(1, player.toString())
            query.setString(2, player.toString())
            query.executeQuery().use { rows ->
                generateSequence {
                    if (!rows.next()) null else PvpOpponentRecord(UUID.fromString(rows.getString("opponent")),
                        latestName(connection, UUID.fromString(rows.getString("opponent"))) ?: rows.getString("name"),
                        rows.getInt("wins"), rows.getInt("losses"))
                }.toList()
            }
        }
    }

    /** The player a name belongs to, by the most recent battle fought under it, ignoring case. */
    fun playerNamed(name: String): UUID? = connect { connection ->
        connection.prepareStatement("""
            SELECT uuid FROM (
                SELECT winner_uuid AS uuid, winner_name AS name, id FROM matches
                UNION ALL SELECT loser_uuid, loser_name, id FROM matches
            ) WHERE name = ? COLLATE NOCASE ORDER BY id DESC LIMIT 1""".trimIndent()).use { query ->
            query.setString(1, name)
            query.executeQuery().use { rows -> if (rows.next()) UUID.fromString(rows.getString(1)) else null }
        }
    }

    /** The name [player] last fought under. */
    fun nameOf(player: UUID): String? = connect { connection -> latestName(connection, player) }

    private fun latestName(connection: Connection, player: UUID): String? =
        connection.prepareStatement("""
            SELECT name FROM (
                SELECT winner_name AS name, id FROM matches WHERE winner_uuid = ?
                UNION ALL SELECT loser_name, id FROM matches WHERE loser_uuid = ?
            ) ORDER BY id DESC LIMIT 1""".trimIndent()).use { query ->
            query.setString(1, player.toString())
            query.setString(2, player.toString())
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun match(rows: ResultSet) = PvpMatch(
        id = rows.getLong("id"),
        battleId = rows.getString("battle_id"),
        playedAt = rows.getLong("played_at"),
        format = rows.getString("format"),
        winnerId = UUID.fromString(rows.getString("winner_uuid")),
        winnerName = rows.getString("winner_name"),
        loserId = UUID.fromString(rows.getString("loser_uuid")),
        loserName = rows.getString("loser_name"),
    )

    companion object {
        const val MAX_PAGE = 100
    }
}
