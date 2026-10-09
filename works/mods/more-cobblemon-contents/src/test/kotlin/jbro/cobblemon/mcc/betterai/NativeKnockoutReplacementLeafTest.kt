package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonObject
import java.util.UUID
import jbro.cobblemon.mcc.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.mcc.betterai.search.NativeRecursiveSearch
import jbro.cobblemon.mcc.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownSearchTree
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveOptionView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A knockout leaves the battle waiting for the opponent's replacement. The leaf used to be scored right there, with
 * nobody across from the attacker, so Flame Charge's +1 Speed against the next Pokemon scored exactly what
 * Flamethrower did. Shadow Tag keeps the doomed lead in, so the knockout line is the only line.
 */
class NativeKnockoutReplacementLeafTest {
    @Test
    fun `a knockout that also raises speed outscores one that does not`() {
        val worker = EngineBranchWorker()
        val root = worker.createBattle(battle())
        val template = template()
        val tree = NativeShowdownSearchTree(worker, root, template)
        val catalog = BattlePublicActionCatalogView((root.p1Team + root.p2Team).map { mon ->
            val ally = root.p1Team.any { it.uuid == mon.uuid }
            BattlePokemonActionCatalogView(UUID.fromString(mon.uuid), mon.moves.map { move ->
                BattlePublicMoveOptionView(move.id, details(move.id, move.pp),
                    if (ally) BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
            }, moveSetComplete = ally)
        })
        val rootActions = tree.actions(tree.root, BattleSide.ALLY).filter { it.mechanic == null }
        val context = BattleDecisionContext(UUID.nameUUIDFromBytes("knockout-replacement".toByteArray()),
            tree.root.state, rootActions, Long.MAX_VALUE, publicActionCatalog = catalog)
        assertEquals(listOf("tackle"), tree.actions(tree.root, BattleSide.OPPONENT)
            .filter { it.mechanic == null }.map { it.moveId }, "Shadow Tag must leave the lead no switch")

        val values = NativeRecursiveSearch(
            tree = tree,
            world = NativeSearchWorldKey("w", 0),
            evaluate = { LocalLookaheadStateEvaluator.evaluate(it, context, includePositionEffects = true) },
            nodeLimit = 10_000,
        ).evaluate(maxDepth = 1).rootValues
            .filter { it.action.mechanic == null }
            .associate { requireNotNull(it.action.moveId) to it.value }

        assertTrue(values.getValue("flamecharge") > values.getValue("flamethrower"),
            "The +1 Speed must count once the replacement is in: $values")
    }

    private fun details(id: String, pp: Int) = EmbeddedTeamInput.publicMoveDetails(JsonObject().apply {
        val move = MOVES.getValue(id)
        addProperty("type", move[0]); addProperty("category", move[1]); addProperty("power", move[2].toDouble())
        addProperty("accuracy", 100.0); addProperty("priority", 0); addProperty("target", "normal")
    }, id, pp)

    private fun battle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(301, 307, 311, 313),
        p1Team = listOf(
            NativePokemonSet("Gothitelle", "Gothitelle", listOf("flamecharge", "flamethrower"), "Shadow Tag", ALLY.toString(),
                nature = "Timid", level = 50,
                evs = mapOf("hp" to 4, "atk" to 0, "def" to 0, "spa" to 252, "spd" to 0, "spe" to 252)),
        ),
        p2Team = listOf(
            NativePokemonSet("Wurmple", "Wurmple", listOf("tackle"), "Shield Dust", LEAD.toString(), nature = "Hardy", level = 5),
            NativePokemonSet("Gyarados", "Gyarados", listOf("waterfall", "earthquake"), "Moxie", BACK.toString(),
                nature = "Jolly", level = 50,
                evs = mapOf("hp" to 4, "atk" to 252, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 252)),
        ),
    )

    private fun template() = BattleStateView(
        battleId = BATTLE, format = BattleFormat.SINGLE, turn = 1,
        pokemon = listOf(
            mon(ALLY, BattleSide.ALLY, "gothitelle", setOf("psychic"), 50, 0),
            mon(LEAD, BattleSide.OPPONENT, "wurmple", setOf("bug"), 5, 0),
            mon(BACK, BattleSide.OPPONENT, "gyarados", setOf("water", "flying"), 50, null),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2),
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(id: UUID, side: BattleSide, species: String, types: Set<String>, level: Int, slot: Int?) = BattlePokemonStateView(
        battlePokemonId = id, side = side, activeSlot = slot, speciesId = "cobblemon:$species", formId = null, level = level,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null,
        knownHeldItemId = null, fainted = false, knownTypeIds = types,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000002301")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000002302")
        val LEAD: UUID = UUID.fromString("00000000-0000-0000-0000-000000002303")
        val BACK: UUID = UUID.fromString("00000000-0000-0000-0000-000000002304")
        val MOVES = mapOf(
            "flamecharge" to listOf("Fire", "Physical", "50"), "flamethrower" to listOf("Fire", "Special", "90"),
            "tackle" to listOf("Normal", "Physical", "40"), "waterfall" to listOf("Water", "Physical", "80"),
            "earthquake" to listOf("Ground", "Physical", "100"),
        )
    }
}
