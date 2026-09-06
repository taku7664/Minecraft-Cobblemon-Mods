package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalScenarioPpSnapshotTest {
    @Test
    fun `both brains receive current PP consistent with executed public moves and legal candidates`() {
        val contexts = mutableListOf<BattleDecisionContext>()
        val definition = LocalTacticalScenarioDefinition(
            "random-complete-4", listOf("illumise_preset_2", "kyuremwhite_preset_1", "lugia_preset_1"),
            listOf("garganacl_preset_2", "gurdurr_preset_3", "taurospaldeacombat_preset_2"), 1795288325,
        )
        val result = LocalTacticalScenarioBattle.run(definition, maximumTurns = 120, recordedContexts = contexts)
        val initialPp = contexts.filter { it.state.turn == 1 }.flatMap { context ->
            context.state.pokemon.filter { it.side == BattleSide.ALLY }.flatMap { pokemon ->
                context.publicActionCatalog.forPokemon(pokemon.battlePokemonId).map { option ->
                    (pokemon.battlePokemonId to option.moveId) to option.details.currentPp
                }
            }
        }.toMap()
        var usedChecks = 0
        var candidateChecks = 0
        contexts.forEach { context ->
            val used = context.state.observedEvents.filter { it.kind == BattleObservedEventKind.MOVE_USED }
                .groupingBy { it.actorPokemonId to it.publicValueId }.eachCount()
            context.state.pokemon.forEach { pokemon ->
                context.publicActionCatalog.forPokemon(pokemon.battlePokemonId).forEach { option ->
                    val key = pokemon.battlePokemonId to option.moveId
                    val count = used[key] ?: 0
                    assertEquals((initialPp.getValue(key) - count).coerceAtLeast(0), option.details.currentPp,
                        "turn=${context.state.turn} side=${pokemon.side} move=${option.moveId}")
                    if (count > 0) usedChecks++
                }
            }
            context.candidates.filter { it.kind == BattleActionKind.USE_MOVE }.forEach { action ->
                val actor = context.state.pokemon.single { it.side == BattleSide.ALLY && it.activeSlot == action.actorSlot }
                val option = context.publicActionCatalog.forPokemon(actor.battlePokemonId).single { it.moveId == action.moveId }
                assertTrue(option.details.currentPp > 0)
                assertEquals(option.details.currentPp, action.moveDetails?.currentPp)
                candidateChecks++
            }
            // This fixture's Lugia has no charge/recharge/entry-only moves and the opposing team
            // cannot Taunt or Encore it. Its positive-PP moves must not disappear through a second
            // subtraction of historical usage while building current legal candidates.
            context.state.pokemon.singleOrNull { it.side == BattleSide.ALLY && it.activeSlot != null &&
                it.speciesId == "cobblemon:lugia" }?.let { lugia ->
                assertEquals(context.publicActionCatalog.forPokemon(lugia.battlePokemonId)
                    .filter { it.details.currentPp > 0 }.map { it.moveId }.toSet(),
                    context.candidates.filter { it.kind == BattleActionKind.USE_MOVE }.map { it.moveId }.toSet())
            }
        }
        assertTrue(usedChecks > 0)
        assertTrue(candidateChecks > 0)
        println("SCENARIO_PP checked=$usedChecks candidates=$candidateChecks turns=${result.turns.size} winner=${result.winner}")
    }
}
