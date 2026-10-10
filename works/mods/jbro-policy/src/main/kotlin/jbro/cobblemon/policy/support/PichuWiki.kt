package jbro.cobblemon.policy.support

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * The server wiki as plain text, so Pichu answers from what players can read instead of guessing. Read on every
 * question: operators edit the wiki in place without a restart.
 */
internal object PichuWiki {
    const val LIMIT = 60_000
    private const val DEFAULT_DIRECTORY = "config/more-cobblemon-contents/wiki"
    /** Pages with nothing to read: the copy template, and the personal dashboard that fills itself in the browser. */
    private val SKIPPED = setOf("_template.html", "me.html")

    /** Where MCC serves the wiki from, as its `wiki.json` names it. */
    fun directory(gameDir: Path): Path {
        val configured = runCatching {
            val file = gameDir.resolve("config/more-cobblemon-contents/wiki.json")
            if (Files.isRegularFile(file)) JsonParser.parseString(Files.readString(file)).asJsonObject.get("directory")?.asString else null
        }.getOrNull()?.trim()?.ifEmpty { null }
        return gameDir.resolve(configured ?: DEFAULT_DIRECTORY).toAbsolutePath().normalize()
    }

    /** Home first, then the pages by file name; blank when the wiki is not installed. */
    fun text(root: Path, limit: Int = LIMIT): String {
        val pages = buildList {
            root.resolve("index.html").takeIf(Files::isRegularFile)?.let(::add)
            val dir = root.resolve("pages")
            if (Files.isDirectory(dir)) Files.list(dir).use { files ->
                addAll(files.filter { it.name.endsWith(".html") && it.name !in SKIPPED }.sorted().toList())
            }
        }
        val out = StringBuilder()
        for (page in pages) {
            val body = runCatching { page(Files.readString(page)) }.getOrNull()?.takeIf(String::isNotBlank) ?: continue
            if (out.length + body.length + 2 > limit) break
            out.append(body).append("\n\n")
        }
        return out.toString().trim()
    }

    /** One page's `<main>` as text: headings become `#` lines, list items `- ` lines, table cells ` | `. */
    internal fun page(html: String): String {
        val title = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let(::plain)
        val main = Regex("<main[^>]*>(.*)</main>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return ""
        var text = main.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<h1[^>]*>.*?</h1>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<h([2-4])[^>]*>"), "\n\n## ")
            .replace(Regex("<li[^>]*>"), "\n- ")
            .replace(Regex("<t[dh][^>]*>"), " | ")
            .replace(Regex("</?(p|div|tr|ul|ol|table|thead|tbody|br|h[1-6]|pre)[^>]*>"), "\n")
        text = plain(text)
        val lines = text.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter(String::isNotEmpty)
        if (lines.isEmpty()) return ""
        return "# ${title ?: "위키"}\n" + lines.joinToString("\n")
    }

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&")
        .trim()
}
