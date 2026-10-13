package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.runner.ShowdownService
import com.cobblemon.mod.common.battles.runner.graal.GraalShowdownService
import com.google.gson.JsonParser
import java.util.UUID
import java.util.WeakHashMap
import jbro.cobblemon.mcc.MoreCobblemonContents
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value

/**
 * Stat stages of every active Pokemon, read from `pokemon.boosts` inside Cobblemon's Showdown. The public log misses
 * stages Showdown changes with `[silent]` (White Herb's reset), which Cobblemon then never forwards; this is the value
 * the battle actually uses. Same context rules as [Cobblemon173ShowdownStateCapture]: server thread only.
 */
internal object Cobblemon173ShowdownBoosts {
    // A Pokemon under Illusion is keyed by the Pokemon it shows, so the reading never names the hidden one.
    // Null until Showdown has flushed its log, so a reading never runs ahead of the messages already sent.
    private const val SOURCE = """(function(id) {
  if (typeof battleMap === 'undefined') return null;
  const stream = battleMap.get(id);
  const battle = stream && stream.battle;
  if (!battle || battle.ended || battle.sentLogPos < battle.log.length) return null;
  const out = {};
  for (const side of battle.sides) {
    if (!side) continue;
    for (const pokemon of side.active) {
      if (!pokemon || pokemon.fainted) continue;
      const shown = pokemon.illusion ? pokemon.illusion.uuid : pokemon.uuid;
      if (!shown) continue;
      const boosts = {};
      for (const stat in pokemon.boosts) if (pokemon.boosts[stat]) boosts[stat] = pokemon.boosts[stat];
      out[shown] = boosts;
    }
  }
  return JSON.stringify(out);
})"""

    private val functions = WeakHashMap<Context, Value>()
    @Volatile private var warned = false

    /** Stages by active Pokemon UUID and Showdown stat id, or null when Showdown cannot be read right now. */
    fun read(battleId: UUID): Map<UUID, Map<String, Int>>? = try {
        val context = (ShowdownService.service as? GraalShowdownService)?.context
        context?.let { readFrom(it, battleId.toString()) }?.let(::parse)
    } catch (failure: Exception) {
        warnOnce(battleId, failure)
        null
    } catch (failure: LinkageError) {
        warnOnce(battleId, failure)
        null
    }

    internal fun readFrom(context: Context, battleId: String): String? {
        val function = synchronized(functions) { functions.getOrPut(context) { context.eval("js", SOURCE) } }
        return function.execute(battleId).takeUnless { it.isNull }?.asString()
    }

    internal fun parse(json: String): Map<UUID, Map<String, Int>> =
        JsonParser.parseString(json).asJsonObject.entrySet().mapNotNull { (id, boosts) ->
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@mapNotNull null
            uuid to boosts.asJsonObject.entrySet().associate { (stat, stage) -> stat to stage.asInt }
        }.toMap()

    private fun warnOnce(battleId: UUID, failure: Throwable) {
        if (warned) return
        warned = true
        MoreCobblemonContents.LOGGER.warn("Could not read stat stages of battle {} from Showdown", battleId, failure)
    }
}
