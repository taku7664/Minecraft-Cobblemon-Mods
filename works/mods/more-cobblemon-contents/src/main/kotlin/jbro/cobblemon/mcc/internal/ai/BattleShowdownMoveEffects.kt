package jbro.cobblemon.mcc.internal.ai

import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Every move's declared effects ([BattleDeclarativeMoveEffects]) from the Showdown move bundle Cobblemon ships, by move
 * id. For a move the battle never offered as a candidate: the Max Move a Dynamaxed Pokemon's attack turns into.
 */
object BattleShowdownMoveEffects {
    private val effectsByMoveId: Map<String, BattleMoveEffectsView> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            showdownMovesSource()?.let(BattleDeclarativeMoveEffects::parse).orEmpty()
        } catch (_: RuntimeException) {
            emptyMap()
        } catch (_: LinkageError) {
            emptyMap()
        }
    }

    fun resolve(moveId: String): BattleMoveEffectsView? = effectsByMoveId[canonicalMoveId(moveId)]

    private fun showdownMovesSource(): String? {
        val resource = BattleShowdownMoveEffects::class.java.classLoader.getResourceAsStream(SHOWDOWN_ZIP_RESOURCE) ?: return null
        resource.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    if (!entry.isDirectory && entry.name == MOVES_ENTRY) return zip.readBytes().toString(StandardCharsets.UTF_8)
                }
            }
        }
    }

    private fun canonicalMoveId(value: String): String = value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)

    private const val SHOWDOWN_ZIP_RESOURCE = "data/cobblemon/showdown.zip"
    private const val MOVES_ENTRY = "data/moves.js"
}
