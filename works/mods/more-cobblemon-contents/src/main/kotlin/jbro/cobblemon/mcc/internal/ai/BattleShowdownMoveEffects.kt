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
            showdownSource(MOVES_ENTRY)?.let(BattleDeclarativeMoveEffects::parse).orEmpty()
        } catch (_: RuntimeException) {
            emptyMap()
        } catch (_: LinkageError) {
            emptyMap()
        }
    }

    fun resolve(moveId: String): BattleMoveEffectsView? = effectsByMoveId[canonicalMoveId(moveId)]

    /** The mechanic flags read from the move's code ([BattleDeclarativeMoveEffects]); empty for an unknown move. */
    fun flags(moveId: String?): Set<String> = moveId?.let(::resolve)?.mechanicFlags.orEmpty()

    /** The moves whose code shows [flag], in the bundle's order. */
    fun idsWithFlag(flag: String): List<String> = effectsByMoveId.filterValues { flag in it.mechanicFlags }.keys.toList()

    /** What follows [prefix] in every move flag that starts with it (`delayed_sleep:` gives `yawn`). */
    fun flagValues(prefix: String): Set<String> =
        effectsByMoveId.values.flatMap { it.mechanicFlags }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()

    /**
     * The types [moveId] turns into with the weather: an `onModifyType` that switches on `effectiveWeather` and sets
     * `move.type` (Weather Ball). Empty for any other move.
     */
    fun weatherTypes(moveId: String?): Set<String> = moveId?.let { weatherTypesByMoveId[canonicalMoveId(it)] }.orEmpty()

    private val weatherTypesByMoveId: Map<String, Set<String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            showdownSource(MOVES_ENTRY)?.let { BattleDeclarativeMoveEffects.entries(it, MOVES_MARKER) }.orEmpty().mapNotNull { (id, hooks) ->
                val body = hooks["onModifyType"]?.takeIf { WEATHER_READ in it } ?: return@mapNotNull null
                MOVE_TYPE.findAll(body).map { it.groupValues[1].lowercase() }.toSet().takeIf { it.isNotEmpty() }?.let { id to it }
            }.toMap()
        } catch (_: RuntimeException) {
            emptyMap()
        } catch (_: LinkageError) {
            emptyMap()
        }
    }

    /** One file of the Showdown bundle Cobblemon ships (`data/items.js`), or null when it cannot be read. */
    internal fun showdownSource(file: String): String? {
        val resource = BattleShowdownMoveEffects::class.java.classLoader.getResourceAsStream(SHOWDOWN_ZIP_RESOURCE) ?: return null
        resource.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    if (!entry.isDirectory && entry.name == file) return zip.readBytes().toString(StandardCharsets.UTF_8)
                }
            }
        }
    }

    private fun canonicalMoveId(value: String): String = value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)

    private const val SHOWDOWN_ZIP_RESOURCE = "data/cobblemon/showdown.zip"
    private const val MOVES_ENTRY = "data/moves.js"
    private const val MOVES_MARKER = "const Moves ="
    private const val WEATHER_READ = "effectiveWeather"
    private val MOVE_TYPE = Regex("""move\.type\s*=\s*["']([A-Za-z]+)["']""")
}
