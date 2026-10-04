package jbro.cobblemon.mcc.internal.wiki

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** The wiki's static files, as both the server's wiki and a player's own local copy serve them. */
internal object WikiFiles {
    class File(val status: Int, val type: String, val body: ByteArray, val cache: Boolean = false)

    /** The file at [rawPath] under [root]; a directory serves its index.html, and nothing outside [root] is ever read. */
    fun read(root: Path, rawPath: String): File {
        val path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8).trimStart('/')
        var target = root.resolve(path).normalize()
        if (!target.startsWith(root)) return text(403, "forbidden")
        if (Files.isDirectory(target)) target = target.resolve("index.html")
        if (!Files.isRegularFile(target)) return text(404, "not found")
        val extension = target.fileName.toString().substringAfterLast('.', "").lowercase()
        val type = TYPES[extension] ?: "application/octet-stream"
        return File(200, type, Files.readAllBytes(target), cache = extension in CACHED)
    }

    fun text(status: Int, message: String) = File(status, "text/plain; charset=utf-8", message.toByteArray())

    private val CACHED = setOf("woff2", "png", "jpg", "jpeg", "gif", "webp", "svg")
    private val TYPES = mapOf(
        "html" to "text/html; charset=utf-8", "css" to "text/css; charset=utf-8", "js" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8", "txt" to "text/plain; charset=utf-8", "md" to "text/plain; charset=utf-8",
        "woff2" to "font/woff2", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "svg" to "image/svg+xml",
    )
}
