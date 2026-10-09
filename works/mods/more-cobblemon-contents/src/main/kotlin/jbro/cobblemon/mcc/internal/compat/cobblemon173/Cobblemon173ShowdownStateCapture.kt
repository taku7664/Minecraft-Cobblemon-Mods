package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.runner.ShowdownService
import com.cobblemon.mod.common.battles.runner.graal.GraalShowdownService
import java.util.UUID
import java.util.WeakHashMap
import jbro.cobblemon.mcc.MoreCobblemonContents
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value

/**
 * Reads a live battle out of Cobblemon's Showdown as Showdown itself serializes it (`battle.toJSON()`), so Better
 * AI's native search starts from the real battle instead of rebuilding one from what the player can see.
 *
 * Cobblemon evaluates `showdown/index.js` as a plain script, so its top-level `battleMap` (battle ID to
 * BattleStream) is visible to any later script in the same context. Nothing in Cobblemon is patched.
 *
 * The context is single-threaded: Cobblemon enters it from the server thread, and so must this.
 */
internal object Cobblemon173ShowdownStateCapture {
    private const val SOURCE = """(function(id) {
  if (typeof battleMap === 'undefined') return null;
  const stream = battleMap.get(id);
  const battle = stream && stream.battle;
  if (!battle || battle.ended) return null;
  const state = battle.toJSON();
  state.log = [];
  state.inputLog = [];
  return JSON.stringify(state);
})"""

    private val functions = WeakHashMap<Context, Value>()
    @Volatile private var warned = false

    /** [capture] on a given context; the battle must have been started by the script that declares `battleMap`. */
    internal fun captureFrom(context: Context, battleId: String): String? {
        val function = synchronized(functions) { functions.getOrPut(context) { context.eval("js", SOURCE) } }
        return function.execute(battleId).takeUnless { it.isNull }?.asString()
    }

    /** The serialized battle, or null where Cobblemon runs Showdown elsewhere or the battle is not found. */
    fun capture(battleId: UUID): String? = try {
        val service = ShowdownService.service as? GraalShowdownService
        val context = service?.context
        if (context == null) null else captureFrom(context, battleId.toString())
    } catch (failure: Exception) {
        if (!warned) {
            warned = true
            MoreCobblemonContents.LOGGER.warn("Better AI could not read battle {} from Showdown", battleId, failure)
        }
        null
    } catch (failure: LinkageError) {
        if (!warned) {
            warned = true
            MoreCobblemonContents.LOGGER.warn("Better AI could not read battle {} from Showdown", battleId, failure)
        }
        null
    }
}
