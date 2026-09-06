package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Cobblemon173FuturePpTest {
    @Test
    fun `original catalog restores once without mutating sibling branch or consuming copied PP`() {
        val own = pokemon(BattleSide.ALLY)
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 2, listOf(own),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 0),
            emptyList(), emptyList())
        val catalog = Cobblemon173PublicActionCatalog.from(state,
            mapOf(own.battlePokemonId to mapOf("recover" to 2)),
            mapOf(own.battlePokemonId to mapOf("recover" to 3)),
            transformedPokemon = setOf(own.battlePokemonId),
            originalMoveIds = mapOf(own.battlePokemonId to setOf("recover")),
            originalPpSpent = mapOf(own.battlePokemonId to mapOf("recover" to 1)),
        ) { BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 8) }
        val restored = catalog.afterSwitch(setOf(own.battlePokemonId))
        assertEquals(3, catalog.forPokemon(own.battlePokemonId).single().details.currentPp)
        assertEquals(7, restored.forPokemon(own.battlePokemonId).single().details.currentPp)
        assertEquals(1, catalog.originalEntries.size)
        assertEquals(0, restored.originalEntries.size)
        assertEquals(restored, restored.afterSwitch(setOf(own.battlePokemonId)))
        val legacy = BattlePublicActionCatalogView::class.java.getConstructor(List::class.java)
            .newInstance(emptyList<BattlePokemonActionCatalogView>())
        assertEquals(emptyList<BattlePokemonActionCatalogView>(), legacy.entries)
    }

    @Test
    fun `copied public moves use temporary capacity while actual own PP still wins`() {
        val own = pokemon(BattleSide.ALLY)
        val opponent = pokemon(BattleSide.OPPONENT)
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 2, listOf(own, opponent),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            emptyList(), emptyList())
        fun pp(maximum: Int) = Cobblemon173PublicActionCatalog.from(state,
            mapOf(opponent.battlePokemonId to mapOf("recover" to 2)),
            mapOf(own.battlePokemonId to mapOf("recover" to 4)),
            transformedPokemon = setOf(own.battlePokemonId, opponent.battlePokemonId),
        ) { BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, maximum) }
            .entries.associate { it.battlePokemonId to it.moves.single().details.currentPp }
        assertEquals(3, pp(8)[opponent.battlePokemonId])
        assertEquals(0, pp(1)[opponent.battlePokemonId])
        assertEquals(4, pp(8)[own.battlePokemonId])
    }

    @Test
    fun `catalog shares PP estimates while refusing actual opponent PP and hidden moves`() {
        val own = pokemon(BattleSide.ALLY)
        val opponent = pokemon(BattleSide.OPPONENT)
        val state = BattleStateView(
            UUID.randomUUID(), BattleFormat.SINGLE, 20, listOf(own, opponent),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            emptyList(), emptyList(),
        )
        val catalog = Cobblemon173PublicActionCatalog.from(
            state,
            mapOf(own.battlePokemonId to mapOf("recover" to 3), opponent.battlePokemonId to mapOf("recover" to 5)),
            mapOf(own.battlePokemonId to mapOf("recover" to 0, "hiddenmove" to 9),
                opponent.battlePokemonId to mapOf("recover" to 1)),
        ) { BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 8) }
        val entries = catalog.entries.associateBy { it.battlePokemonId }
        assertEquals(0, entries.getValue(own.battlePokemonId).moves.single().details.currentPp)
        assertEquals(3, entries.getValue(opponent.battlePokemonId).moves.single().details.currentPp)
    }

    private fun pokemon(side: BattleSide) = BattlePokemonStateView(
        UUID.randomUUID(), side, 0, "cobblemon:alakazam", null, 50, 1.0, null,
        emptyMap(), setOf("recover"), null, null, false,
    )

    @Test
    fun `unknown PP assumes PP Max minus public uses and never goes negative`() {
        assertEquals(8, Cobblemon173PublicActionCatalog.remainingPp(8, 0, null))
        assertEquals(5, Cobblemon173PublicActionCatalog.remainingPp(8, 3, null))
        assertEquals(0, Cobblemon173PublicActionCatalog.remainingPp(8, 10, null))
    }

    @Test
    fun `actual current PP takes precedence without subtracting observed uses again`() {
        assertEquals(2, Cobblemon173PublicActionCatalog.remainingPp(8, 3, 2))
        assertEquals(0, Cobblemon173PublicActionCatalog.remainingPp(8, 0, 0))
        assertEquals(8, Cobblemon173PublicActionCatalog.remainingPp(8, 10, 8))
    }
}
