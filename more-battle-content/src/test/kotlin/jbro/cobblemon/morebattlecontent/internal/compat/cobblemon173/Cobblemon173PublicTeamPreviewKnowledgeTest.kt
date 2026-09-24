package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesMoveKnowledge
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesMovePool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173PublicTeamPreviewKnowledgeTest {
    @Test
    fun `preview enrichment uses only public species form and level`() {
        val requests = mutableListOf<List<Any?>>()
        val raw = BattleOpponentTeamPreviewView(
            selectionSize = 1,
            pokemon = listOf(BattleOpponentTeamPreviewPokemonView(
                previewSlotId = 2,
                speciesId = "cobblemon:fluttermane",
                formId = "normal",
                level = 50,
            )),
        )
        val stats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(100, 150),
            attack = BattleIntegerRange(80, 130),
            defence = BattleIntegerRange(70, 120),
            specialAttack = BattleIntegerRange(120, 190),
            specialDefence = BattleIntegerRange(110, 180),
            speed = BattleIntegerRange(120, 190),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        )
        val moves = PublicSpeciesMoveKnowledge { species, form ->
            requests += listOf("moves", species, form)
            PublicSpeciesMovePool(setOf("moonblast", "shadowball", "hidden-power"), "fixture:learnset")
        }

        val enriched = Cobblemon173PublicTeamPreviewKnowledge.enrich(
            preview = raw,
            facts = { species, form, level ->
                requests += listOf("facts", species, form, level)
                Cobblemon173PublicPreviewFacts(setOf("ghost", "fairy"), stats, emptyMap())
            },
            moveKnowledge = moves,
            moveDetails = { move ->
                move.takeUnless { it == "hidden-power" }?.let {
                    BattleMoveCandidateView("fairy", BattleMoveDamageCategory.SPECIAL, 80.0, 100.0, 0, 16)
                }
            },
        )

        val pokemon = enriched.pokemon.single()
        assertEquals(raw.selectionSize, enriched.selectionSize)
        assertEquals(2, pokemon.previewSlotId)
        assertEquals(setOf("ghost", "fairy"), pokemon.knownTypeIds)
        assertEquals(stats, pokemon.combatStats)
        assertEquals(setOf("hidden-power", "moonblast", "shadowball"), pokemon.moveCandidatePool?.moveIds)
        assertEquals(setOf("moonblast", "shadowball"), pokemon.moveCandidatePool?.moveDetails?.keys)
        assertEquals(
            listOf(
                listOf("facts", "cobblemon:fluttermane", "normal", 50),
                listOf("moves", "cobblemon:fluttermane", "normal"),
            ),
            requests,
        )
    }

    @Test
    fun `missing public rule sources stay missing instead of inventing a build`() {
        val raw = BattleOpponentTeamPreviewView(
            selectionSize = 1,
            pokemon = listOf(BattleOpponentTeamPreviewPokemonView(0, "addon:unknown", null, 50)),
        )

        val enriched = Cobblemon173PublicTeamPreviewKnowledge.enrich(
            preview = raw,
            facts = { _, _, _ -> null },
            moveKnowledge = PublicSpeciesMoveKnowledge { _, _ -> null },
            moveDetails = { error("No missing-source move may be resolved") },
        ).pokemon.single()

        assertTrue(enriched.knownTypeIds.isEmpty())
        assertNull(enriched.combatStats)
        assertNull(enriched.moveCandidatePool)
    }
}
