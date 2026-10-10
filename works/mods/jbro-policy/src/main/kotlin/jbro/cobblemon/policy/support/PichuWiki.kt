package jbro.cobblemon.policy.support

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
 * to [BUDGET] characters; a question that matches nothing gets the FAQ page. A list of every page always comes along.
 */
internal object PichuWiki {
    const val BUDGET = 6_000
    private const val DEFAULT_DIRECTORY = "config/more-cobblemon-contents/wiki"
    /** Pages with nothing to read: the copy template, and the personal dashboard that fills itself in the browser. */
    private val SKIPPED = setOf("_template.html", "me.html")
    private const val FALLBACK = "pages/faq.html"
    /** Below this best page score the question is small talk or something the wiki does not cover. */
    private const val THRESHOLD = 10.0
    /** Pieces of question words ("어디서", "어떻게", "있어요") that match headings without saying what is asked. */
    private val STOP = ("어디 디서 디에 어떻 떻게 뭐야 뭔가 무엇 엇인 방법 하나 나요 해요 돼요 되나 있나 나와 와요 주세 세요 알려 려줘 려주 " +
        "인가 가요 는데 하면 할까 까요 언제 누가 얼마 마나 하는 는법 싶어 싶은 왜안 이에 에요 예요 있어 어요 있는 없어 뭐있 뭐가 되요 줘요 하죠")
        .split(' ').toSet()
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
    fun reference(root: Path, question: String, budget: Int = BUDGET): String = select(load(root), question, budget)

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
        fun idf(gram: String) = df[gram]?.let { ln(1.0 + count / it) * (if (gram.length == 1) 0.3 else 1.0) } ?: 0.0
        val asked = grams(question).filterNot { it in STOP }.toSet()
        fun sectionScore(section: Section) = asked.sumOf { gram ->
            idf(gram) * ((if (gram in section.headGrams) 2.0 else 0.0) + min(section.bodyCounts[gram] ?: 0, 3) / 3.0)
        }
        fun metaScore(page: Page) = asked.filter { it in page.metaGrams }.sumOf { 3 * idf(it) }
        fun pageScore(page: Page) = metaScore(page) + (page.sections.maxOfOrNull(::sectionScore) ?: 0.0)

        val ranked = pages.map { it to pageScore(it) }.sortedByDescending { it.second }
        val top = ranked.first().second
        val matched = top >= THRESHOLD
        val chosen = if (matched) ranked.take(2).filter { it.second >= 0.6 * top }.map { it.first }
            else listOfNotNull(pages.firstOrNull { it.path == FALLBACK })
        val scored = chosen.flatMap { page ->
            val bonus = 0.4 * metaScore(page)
            page.sections.map { Triple(page, it, sectionScore(it) + bonus + 0.01) }
        }.sortedByDescending { it.third }
        val best = scored.firstOrNull()?.third ?: 0.0
        var used = 0
        val picked = HashSet<Section>()
        for ((_, section, score) in scored) {
            if (matched && score < 0.25 * best) continue
            if (used + section.text.length > budget) continue
            used += section.text.length
            picked += section
        }

        return buildString {
            append("위키 문서 목록: ").append(pages.joinToString(", ") { it.title }).append('\n')
            if (!matched) append("(질문과 맞는 위키 문서를 찾지 못해 자주 묻는 질문만 넣었습니다.)\n")
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
            val heading = if (index == 0) "개요" else plain(part.substringBefore("</h2>")).ifBlank { "개요" }
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
    internal fun grams(text: String): List<String> = text.lowercase().replace(Regex("[^0-9a-z가-힣]+"), " ")
        .split(' ').filter(String::isNotEmpty).flatMap { word ->
            if (word.length < 2) listOf(word) else listOf(word.substring(0, 1)) + (0 until word.length - 1).map { word.substring(it, it + 2) }
        }

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&")
        .trim()
}
