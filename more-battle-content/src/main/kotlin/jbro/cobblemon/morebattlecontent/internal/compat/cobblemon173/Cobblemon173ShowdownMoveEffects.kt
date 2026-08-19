package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectsView

/** Reads the exact Pokemon Showdown move bundle shipped inside Cobblemon 1.7.3. */
internal object Cobblemon173ShowdownMoveEffects {
    private val effectsByMoveId: Map<String, BattleMoveEffectsView> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            val source = requireNotNull(showdownMovesSource()) {
                "Cobblemon 1.7.3 Showdown move data is unavailable"
            }
            ShowdownDeclarativeMoveParser.parse(source)
        }.getOrDefault(emptyMap())
    }

    fun resolve(moveId: String): BattleMoveEffectsView? = effectsByMoveId[canonicalMoveId(moveId)]

    private fun showdownMovesSource(): String? {
        val resource = Cobblemon173ShowdownMoveEffects::class.java.classLoader
            .getResourceAsStream(SHOWDOWN_ZIP_RESOURCE) ?: return null
        resource.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    if (!entry.isDirectory && entry.name == MOVES_ENTRY) {
                        return zip.readBytes().toString(StandardCharsets.UTF_8)
                    }
                }
            }
        }
    }

    private fun canonicalMoveId(value: String): String = value.substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private const val SHOWDOWN_ZIP_RESOURCE = "data/cobblemon/showdown.zip"
    private const val MOVES_ENTRY = "data/moves.js"
}
