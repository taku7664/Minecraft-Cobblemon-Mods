package jbro.cobblemon.policy.support

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlin.io.path.name

/**
 * The server log around an inquiry, for the reviewer to read. Minecraft rolls its log at midnight and on start, so
 * each `yyyy-MM-dd-N.log.gz` holds one day and `latest.log` the day it was last written; lines carry only the time.
 */
internal object InquiryLogWindow {
    data class LogFile(val date: LocalDate, val lines: List<String>)

    data class Window(val text: String, val lines: Int, val truncated: Boolean)

    private val ROLLED = Regex("(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.log\\.gz")
    private val TIME = Regex("^\\[(\\d{2}):(\\d{2}):(\\d{2})]")
    private val IPV4 = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b")

    /** Reads the files of [logs] that can hold lines between [from] and [to], oldest first. */
    fun read(logs: Path, from: LocalDateTime, to: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): List<LogFile> {
        if (!Files.isDirectory(logs)) return emptyList()
        val days = from.toLocalDate()..to.toLocalDate()
        val rolled = Files.list(logs).use { files ->
            files.toList().mapNotNull { file ->
                val match = ROLLED.matchEntire(file.name) ?: return@mapNotNull null
                Triple(LocalDate.parse(match.groupValues[1]), match.groupValues[2].toInt(), file)
            }
        }.filter { it.first in days }.sortedWith(compareBy({ it.first }, { it.second }))
        val result = rolled.map { (date, _, file) ->
            LogFile(date, GZIPInputStream(Files.newInputStream(file)).use { readLines(it) })
        }.toMutableList()
        val latest = logs.resolve("latest.log")
        if (Files.isRegularFile(latest)) {
            val date = LocalDate.ofInstant(Files.getLastModifiedTime(latest).toInstant(), zone)
            if (date in days) result += LogFile(date, Files.newInputStream(latest).use { readLines(it) })
        }
        return result
    }

    private fun readLines(input: java.io.InputStream): List<String> =
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readLines()

    /**
     * The lines between [from] and [to], with IP addresses hidden. A line without a time (a stack trace, say) goes
     * with the line above it. Past [maxCharacters], lines naming the player in [names] are kept first, then the
     * ones nearest [at]; what is left stays in log order.
     */
    fun collect(files: List<LogFile>, from: LocalDateTime, to: LocalDateTime, at: LocalDateTime, names: Collection<String>, maxCharacters: Int): Window {
        data class Line(val index: Int, val time: LocalDateTime, val text: String)
        val picked = mutableListOf<Line>()
        for (file in files) {
            var time: LocalDateTime? = null
            for (raw in file.lines) {
                TIME.find(raw)?.let { match ->
                    val (hours, minutes, seconds) = match.destructured
                    time = LocalDateTime.of(file.date, LocalTime.of(hours.toInt(), minutes.toInt(), seconds.toInt()))
                }
                val stamp = time ?: continue
                if (stamp < from || stamp > to) continue
                picked += Line(picked.size, stamp, raw.replace(IPV4, "x.x.x.x"))
            }
        }
        val total = picked.sumOf { it.text.length + 1 }
        if (total <= maxCharacters) return Window(picked.joinToString("\n") { it.text }, picked.size, truncated = false)
        val keys = names.filter { it.isNotBlank() }.map { it.lowercase() }
        val ranked = picked.sortedWith(compareBy<Line> { line -> if (keys.any { it in line.text.lowercase() }) 0 else 1 }
            .thenBy { kotlin.math.abs(java.time.Duration.between(at, it.time).seconds) })
        var room = maxCharacters
        val kept = ranked.takeWhile { line -> (room - (line.text.length + 1)).also { room = it } >= 0 }.sortedBy { it.index }
        return Window(kept.joinToString("\n") { it.text }, kept.size, truncated = true)
    }

    /** [at] in the server's own time zone, which is the one its log is written in. */
    fun local(at: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
}
