package jbro.cobblemon.policy.support

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.math.ln
import kotlin.math.min

/**
 * The parts of the server wiki that bear on a question, so Pichu answers from what players can read instead of
 * guessing, without paying for the whole wiki on every question. Read on every question: operators edit the wiki in
 * place without a restart.
 *
 * Each page splits at its `<h2>` headings. Korean words carry particles ("웜홀에"), so the question and the wiki are
 * compared as two-letter pieces plus each word's first letter. A page's nav title and search keywords count most and
 * act as its synonyms, a section heading next, its text least. The best one or two pages give their best sections up
 * to [BUDGET] characters; a question that matches nothing gets the FAQ page, and a weak match also its best sections.
 * A list of every page always comes along. A species or dropped item named in the question adds its dex facts, which
 * the wiki pages only draw from data files in the browser.
 */
internal object PichuWiki {
    const val BUDGET = 6_000
    private const val DEFAULT_DIRECTORY = "config/more-cobblemon-contents/wiki"
    /** Pages with nothing to read: the copy template, and the personal dashboard that fills itself in the browser. */
    private val SKIPPED = setOf("_template.html", "me.html")
    private const val FALLBACK = "pages/faq.html"
    /** Below this best page score the question is small talk or something the wiki does not cover. */
    private const val THRESHOLD = 10.0
    /** From here up to [THRESHOLD] the best page may hold the answer, so it comes along with the FAQ. */
    private const val WEAK_THRESHOLD = 3.0
    private const val DEX_BUDGET = 1_500
    private const val DEX_SPECIES = 3
    private const val DROP_SPECIES = 12
    private const val SPAWN_ROWS = 4
    private const val ROW_BIOMES = 8
    private fun say(key: String, vararg values: Pair<String, Any?>) = PichuRules.current().text("ai.$key", *values)

    /** Pieces of question words ("어디서", "어떻게", "있어요") that match headings without saying what is asked. */
    private val STOP: Set<String> get() = PichuRules.current().list("wiki.stopWords").toSet()
    private val NAV_ENTRY = Regex("""path: "([^"]+)", title: "([^"]+)", icon: "[^"]*", keywords: "([^"]*)"""")
    private val MAIN = Regex("<main[^>]*>(.*)</main>", RegexOption.DOT_MATCHES_ALL)
    private val H1 = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL)
    private val SCRIPT = Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    class Section(val heading: String, val text: String) {
        internal val headGrams = grams(heading).toSet()
        internal val bodyCounts = grams(text).groupingBy { it }.eachCount()
    }

    class Page(val path: String, val title: String, keywords: String, val sections: List<Section>) {
        internal val metaGrams = grams("$title $keywords").toSet()
    }

    /** Where MCC serves the wiki from, as its `wiki.json` names it. */
    fun directory(gameDir: Path): Path {
        val configured = runCatching {
            val file = gameDir.resolve("config/more-cobblemon-contents/wiki.json")
            if (Files.isRegularFile(file)) JsonParser.parseString(Files.readString(file)).asJsonObject.get("directory")?.asString else null
        }.getOrNull()?.trim()?.ifEmpty { null }
        return gameDir.resolve(configured ?: DEFAULT_DIRECTORY).toAbsolutePath().normalize()
    }

    /** What to hand Pichu for [question]; blank when the wiki is not installed. */
    fun reference(root: Path, question: String, budget: Int = BUDGET): String {
        val pages = select(load(root), question, budget)
        // A broken data file must not cost the question its wiki pages.
        val dex = runCatching { dex(root, question) }.getOrDefault("")
        return if (pages.isBlank() || dex.isBlank()) pages else "$pages\n\n$dex"
    }

    /** Dex facts for species the question names, or the species that drop an item it names; blank when it names neither. */
    internal fun dex(root: Path, question: String): String {
        val index = root.resolve("assets/data/dex/index.js")
        if (!Files.isRegularFile(index)) return ""
        val entries = array(Files.readString(index)).map { it.asJsonObject }
        val words = words(question)
        val named = entries.filter { entry ->
            val name = entry.get("name").asString
            if (name.length < 2) name in words else words.any { it.startsWith(name) }
        }.sortedByDescending { it.get("name").asString.length }.take(DEX_SPECIES)
        val legends = root.resolve("assets/data/legends.js").takeIf(Files::isRegularFile)?.let { file ->
            array(Files.readString(file)).map { it.asJsonObject }.associateBy { it.get("species").asString }
        }.orEmpty()
        val drops = LinkedHashMap<String, MutableList<String>>()
        for (entry in entries) for (item in dropNames(entry)) {
            if (words.any { it.startsWith(item) }) drops.getOrPut(item) { ArrayList() } += entry.get("name").asString
        }
        return buildString {
            for (entry in named) append(speciesFacts(root, entry, legends[entry.get("id").asString])).append("\n\n")
            for ((item, species) in drops) {
                append(say("wikiDrop", "item" to item)).append('\n').append(species.take(DROP_SPECIES).joinToString(", "))
                if (species.size > DROP_SPECIES) append(say("wikiDropMore", "count" to species.size - DROP_SPECIES))
                append("\n\n")
            }
        }.trim().take(DEX_BUDGET)
    }

    private fun speciesFacts(root: Path, entry: JsonObject, legend: JsonObject?) = buildString {
        val name = entry.get("name").asString
        append(say("wikiDex", "name" to name)).append('\n')
        val stats = entry.getAsJsonArray("stats").joinToString("/") { it.asString }
        append(say("wikiStats", "stats" to stats)).append('\n')
        dropNames(entry).takeIf { it.isNotEmpty() }?.let { append(say("wikiDrops", "drops" to it.joinToString(", "))).append('\n') }
        if (legend != null) {
            append(say("wikiLegend", "rank" to legend.get("rank").asString, "rarity" to legend.get("rarity").asString)).append('\n')
            append(say("wikiSpawn", "row" to spawnRow(legend))).append('\n')
        } else {
            val file = root.resolve("assets/data/dex/species/${entry.get("id").asString}.js")
            val text = if (Files.isRegularFile(file)) Files.readString(file) else ""
            val species = if (text.isEmpty()) null else JsonParser.parseString(text.substring(text.indexOf("= {") + 2, text.lastIndexOf('}') + 1)).asJsonObject
            val rows = species?.getAsJsonArray("spawns")?.map { it.asJsonObject }.orEmpty()
            if (rows.isEmpty()) append(say("wikiNoSpawn")).append('\n')
            for (row in rows.take(SPAWN_ROWS)) append(say("wikiSpawn", "row" to spawnRow(row))).append('\n')
            if (rows.size > SPAWN_ROWS) append(say("wikiMoreSpawns", "count" to rows.size - SPAWN_ROWS)).append('\n')
        }
    }

    /** One spawn entry of `legends.js` or a species file: where it appears and what else must hold. */
    private fun spawnRow(row: JsonObject): String {
        val biomes = row.getAsJsonArray("biomes").map { it.asJsonObject }.filterNot { it.get("group").asBoolean }
            .map { it.get("name").asString }.distinct()
        val conditions = row.getAsJsonArray("conditions").map { condition ->
            if (condition.isJsonObject) condition.asJsonObject.get("text").asString else condition.asString
        }
        return listOfNotNull(
            row.get("bucket")?.asString,
            row.get("position")?.asString,
            row.getAsJsonArray("dimensions").map { it.asString }.takeIf { it.isNotEmpty() }?.joinToString(", "),
            biomes.takeIf { it.isNotEmpty() }?.let { say("wikiBiomes", "biomes" to it.take(ROW_BIOMES).joinToString(", ") + if (it.size > ROW_BIOMES) say("wikiBiomesMore") else "") },
            conditions.takeIf { it.isNotEmpty() }?.let { say("wikiConditions", "conditions" to it.joinToString(", ")) },
        ).joinToString(" · ")
    }

    /** The Korean words of a dex entry's `dropSearch`; its item ids and one-letter words would match too much. */
    private fun dropNames(entry: JsonObject) = entry.get("dropSearch")?.asString.orEmpty().split(' ')
        .filter { word -> word.length >= 2 && word.all { it in '가'..'힣' } }

    private fun array(script: String) = JsonParser.parseString(script.substring(script.indexOf("= [") + 2, script.lastIndexOf(']') + 1)).asJsonArray

    /** Home and the pages in rail order, then any page the rail does not list. */
    fun load(root: Path): List<Page> {
        val nav = runCatching { Files.readString(root.resolve("assets/nav.js")) }.getOrDefault("")
        val listed = NAV_ENTRY.findAll(nav).associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }
        val files = buildList {
            if (Files.isRegularFile(root.resolve("index.html"))) add("index.html")
            val dir = root.resolve("pages")
            if (Files.isDirectory(dir)) Files.list(dir).use { found ->
                addAll(found.filter { it.name.endsWith(".html") && it.name !in SKIPPED }.map { "pages/${it.name}" }.sorted().toList())
            }
        }
        val order = listed.keys.withIndex().associate { it.value to it.index }
        return files.sortedBy { order[it] ?: Int.MAX_VALUE }.mapNotNull { path ->
            val (heading, sections) = runCatching { sections(Files.readString(root.resolve(path))) }.getOrNull() ?: return@mapNotNull null
            if (sections.isEmpty()) return@mapNotNull null
            val (title, keywords) = listed[path] ?: ((heading ?: path) to "")
            Page(path, title, keywords, sections)
        }
    }

    internal fun select(pages: List<Page>, question: String, budget: Int = BUDGET): String {
        if (pages.isEmpty()) return ""
        val count = pages.sumOf { it.sections.size }.toDouble()
        val df = HashMap<String, Int>()
        for (page in pages) for (section in page.sections) {
            for (gram in section.headGrams + section.bodyCounts.keys + page.metaGrams) df.merge(gram, 1, Int::plus)
        }
        // A word's first letter says little, but a word that is one letter ("맵", "렉") is what was asked.
        val letterWords = words(question).filter { it.length == 1 }.toSet()
        fun idf(gram: String) = df[gram]?.let { ln(1.0 + count / it) * (if (gram.length == 1 && gram !in letterWords) 0.3 else 1.0) } ?: 0.0
        val asked = grams(question).filterNot { it in STOP }.toSet()
        fun sectionScore(section: Section) = asked.sumOf { gram ->
            idf(gram) * ((if (gram in section.headGrams) 2.0 else 0.0) + min(section.bodyCounts[gram] ?: 0, 3) / 3.0)
        }
        fun metaScore(page: Page) = asked.filter { it in page.metaGrams }.sumOf { 3 * idf(it) }
        fun pageScore(page: Page) = metaScore(page) + (page.sections.maxOfOrNull(::sectionScore) ?: 0.0)

        val ranked = pages.map { it to pageScore(it) }.sortedByDescending { it.second }
        val top = ranked.first().second
        val matched = top >= THRESHOLD
        val weak = !matched && top >= WEAK_THRESHOLD
        val fallback = listOfNotNull(pages.firstOrNull { it.path == FALLBACK })
        val chosen = when {
            matched -> ranked.take(2).filter { it.second >= 0.6 * top }.map { it.first }
            weak -> (listOf(ranked.first().first) + fallback).distinct()
            else -> fallback
        }
        val scored = chosen.flatMap { page ->
            val bonus = 0.4 * metaScore(page)
            page.sections.map { Triple(page, it, sectionScore(it) + bonus + 0.01) }
        }.sortedByDescending { it.third }
        val best = scored.firstOrNull()?.third ?: 0.0
        var used = 0
        val picked = HashSet<Section>()
        for ((page, section, score) in scored) {
            if (matched && score < 0.25 * best) continue
            if (weak && page.path != FALLBACK && score < 0.5 * best) continue
            if (used + section.text.length > budget) continue
            used += section.text.length
            picked += section
        }

        return buildString {
            append(say("wikiPages", "pages" to pages.joinToString(", ") { it.title })).append('\n')
            if (weak) append(say("wikiWeak")).append('\n')
            else if (!matched) append(say("wikiUnmatched")).append('\n')
            for (page in chosen) for (section in page.sections) if (section in picked) {
                append("\n# ").append(page.title).append(" > ").append(section.heading).append('\n').append(section.text).append('\n')
            }
        }.trim()
    }

    /** A page's `<h1>` and its `<main>` cut at each `<h2>`; what comes before the first `<h2>` is "개요". */
    internal fun sections(html: String): Pair<String?, List<Section>> {
        val main = MAIN.find(html)?.groupValues?.get(1) ?: return null to emptyList()
        val title = H1.find(main)?.groupValues?.get(1)?.let(::plain)
        val parts = main.replace(SCRIPT, " ").replace(H1, " ").split(Regex("<h2[^>]*>"))
        return title to parts.mapIndexedNotNull { index, part ->
            val heading = if (index == 0) say("wikiOverview") else plain(part.substringBefore("</h2>")).ifBlank { say("wikiOverview") }
            val text = text(if (index == 0) part else part.substringAfter("</h2>", ""))
            if (text.isBlank()) null else Section(heading, text)
        }
    }

    /** HTML as text: smaller headings become `###` lines, list items `- ` lines, table cells ` | `. */
    internal fun text(html: String): String {
        val text = plain(html.replace(SCRIPT, " ")
            .replace(Regex("<h[3-6][^>]*>"), "\n### ")
            .replace(Regex("<li[^>]*>"), "\n- ")
            .replace(Regex("<t[dh][^>]*>"), " | ")
            .replace(Regex("</?(p|div|tr|ul|ol|table|thead|tbody|br|h[1-6]|pre)[^>]*>"), "\n"))
        return text.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter(String::isNotEmpty).joinToString("\n")
    }

    /** Two-letter pieces of each word plus its first letter, so "웜홀에" still meets "웜홀". */
    internal fun grams(text: String): List<String> = words(text).flatMap { word ->
        if (word.length < 2) listOf(word) else listOf(word.substring(0, 1)) + (0 until word.length - 1).map { word.substring(it, it + 2) }
    }

    private fun words(text: String) = text.lowercase().replace(Regex("[^0-9a-z가-힣]+"), " ").split(' ').filter(String::isNotEmpty)

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&")
        .trim()
}
