package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage
import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class Cobblemon173PublicTypeKnowledgeTest {
    @Test
    fun `only explicit public types are accepted and missing replacement becomes unknown`() {
        val soak = change("|-start|p2a: Target|typechange|Water")!!
        assertEquals(setOf("water"), soak.types)
        assertEquals(setOf("fire", "flying"), change("|-start|p2a: Target|typechange|Fire/Flying")!!.types)
        assertTrue(change("|-start|p1a: User|typechange|[from] move: Reflect Type|[of] p2a: Target")!!.types.isEmpty())
        assertTrue(change("|-start|p1a: User|typechange|???")!!.types.isEmpty())
        val tera = change("|-terastallize|p2a: Target|Water")!!
        assertEquals(PublicTypeChangeKind.TERA, tera.kind)
        assertEquals(setOf("water"), tera.types)
        assertNull(change("|-start|p1a: User|substitute"))
        assertNull(change("|move|p1a: User|Soak|p2a: Target"))
    }

    @Test
    fun `public Tera type survives switching and emits exact inference evidence`() {
        for (side in BattleSide.entries) {
            val first = pokemon(side, UUID(0, 1))
            val other = pokemon(side, UUID(0, 2))
            val observer = Cobblemon173PublicBattleObserver(3)
            observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, first))
            observer.observe(Cobblemon173PublicObservation.TypesChanged(
                1,
                first,
                change("|-terastallize|p2a: Target|Water")!!,
            ))

            val revealed = observer.publicSnapshot()
            assertEquals(setOf("water"), revealed.pokemon.single().knownTypeIds)
            assertEquals(
                listOf(BattleObservedEventKind.TERA_TYPE_REVEALED),
                revealed.events.filter { it.kind == BattleObservedEventKind.TERA_TYPE_REVEALED }.map { it.kind },
            )
            assertEquals(
                "water",
                revealed.events.single { it.kind == BattleObservedEventKind.TERA_TYPE_REVEALED }.publicValueId,
            )

            observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, other))
            assertEquals(
                setOf("water"),
                observer.publicSnapshot().pokemon.single { it.battlePokemonId == first.battlePokemonId }.knownTypeIds,
            )
            observer.observe(Cobblemon173PublicObservation.PokemonPresented(3, first))
            val returned = observer.publicSnapshot()
            assertEquals(
                setOf("water"),
                returned.pokemon.single { it.battlePokemonId == first.battlePokemonId }.knownTypeIds,
            )
            assertEquals(setOf("water"), returned.typeOverrides[first.battlePokemonId])
        }
    }

    @Test
    fun `confirmed Tera inference survives bounded event eviction`() {
        val first = pokemon(BattleSide.OPPONENT, UUID(0, 1))
        val other = pokemon(BattleSide.OPPONENT, UUID(0, 2))
        val observer = Cobblemon173PublicBattleObserver(3, maximumRecentEvents = 1)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, first))
        observer.observe(Cobblemon173PublicObservation.TypesChanged(
            1,
            first,
            change("|-terastallize|p2a: Target|Water")!!,
        ))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, other))
        val snapshot = observer.publicSnapshot()

        assertTrue(snapshot.events.none { it.kind == BattleObservedEventKind.TERA_TYPE_REVEALED })
        assertEquals(mapOf(first.battlePokemonId to "water"), snapshot.teraTypes)

        val assembled = Cobblemon173BattleStateAssembler.assemble(
            battleId = UUID(0, 99),
            format = BattleFormat.SINGLE,
            turn = 2,
            ownPokemon = emptyList(),
            publicSnapshot = snapshot,
            inferenceKnowledge = { _, _ -> emptyList() },
        )
        val inference = assembled.inferences.single { it.categoryId == "tera_type" }
        assertEquals(first.battlePokemonId, inference.subjectPokemonId)
        assertEquals("water", inference.candidateId)
        assertEquals(BattleInferenceConfidence.CONFIRMED, inference.confidence)
        assertEquals(setOf(BattleInferenceBasis.PUBLIC_REVEAL), inference.basis)
        assertTrue(inference.evidenceEventSequences.isEmpty())
    }

    @Test
    fun `public replacements persist across snapshots and reset for both sides on switch`() {
        for (side in BattleSide.entries) {
            val first = pokemon(side, UUID(0, 1))
            val other = pokemon(side, UUID(0, 2))
            val observer = Cobblemon173PublicBattleObserver(3)
            observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, first))
            observer.observe(Cobblemon173PublicObservation.TypesChanged(1, first, change("|-start|p2a: Target|typechange|Water")!!))
            observer.observeActivePresence(first)
            observer.observe(Cobblemon173PublicObservation.HpChanged(1, first.copy(hpFraction = 0.5)))
            assertEquals(setOf("water"), observer.publicSnapshot().pokemon.single().knownTypeIds)
            assertEquals(setOf("water"), observer.publicSnapshot().typeOverrides[first.battlePokemonId])
            observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, other))
            assertEquals(setOf("electric"), observer.publicSnapshot().pokemon.single { it.battlePokemonId == first.battlePokemonId }.knownTypeIds)
            assertFalse(observer.publicSnapshot().typeOverrides.containsKey(first.battlePokemonId))
            observer.observe(Cobblemon173PublicObservation.PokemonPresented(3, first))
            assertEquals(setOf("electric"), observer.publicSnapshot().pokemon.single { it.battlePokemonId == first.battlePokemonId }.knownTypeIds)
        }
    }

    @Test
    fun `added type replaces the prior addition and replacement clears it`() {
        val state = Cobblemon173PublicTypeKnowledge()
        val id = UUID(0, 1)
        fun apply(line: String) = state.apply(id, setOf("electric"), change(line)!!)
        assertEquals(setOf("electric", "grass"), apply("|-start|p2a: Target|typeadd|Grass"))
        assertEquals(setOf("electric", "ghost"), apply("|-start|p2a: Target|typeadd|Ghost"))
        assertEquals(setOf("water"), apply("|-start|p2a: Target|typechange|Water"))
        assertTrue(apply("|-start|p2a: Target|typechange|[from] move: Reflect Type").isEmpty())
        assertTrue(apply("|-start|p2a: Target|typeadd|Ghost").isEmpty())
        assertEquals(setOf("electric"), state.clear(id))
        assertTrue(state.snapshot().isEmpty())
    }

    @Test
    fun `assembler applies explicit and unknown own types without leaking them after reset`() {
        val first = pokemon(BattleSide.ALLY, UUID(0, 1))
        val observer = Cobblemon173PublicBattleObserver(1)
        val own = first.toView(null, true)
        fun assembled() = Cobblemon173BattleStateAssembler.assemble(UUID(0, 3), BattleFormat.SINGLE, 1,
            listOf(own), observer.publicSnapshot(), inferenceKnowledge = { _, _ -> emptyList() }).pokemon.single()
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, first))
        observer.observe(Cobblemon173PublicObservation.TypesChanged(1, first, change("|-start|p1a: User|typechange|Water")!!))
        assertEquals(setOf("water"), assembled().knownTypeIds)
        observer.observe(Cobblemon173PublicObservation.TypesChanged(1, first,
            change("|-start|p1a: User|typechange|[from] move: Reflect Type")!!))
        assertTrue(assembled().knownTypeIds.isEmpty(), "UNKNOWN must not fall back to the original species types")
        observer.observe(Cobblemon173PublicObservation.TypesChanged(1, first,
            change("|-start|p1a: User|typechange|Electric|[silent]")!!))
        assertEquals(setOf("electric"), assembled().knownTypeIds)
        observer.reset()
        assertEquals(setOf("electric"), assembled().knownTypeIds)
    }

    @Test
    fun `removing added type restores the changed base without affecting another Pokemon`() {
        val state = Cobblemon173PublicTypeKnowledge()
        val first = UUID(0, 1)
        val second = UUID(0, 2)
        state.apply(first, setOf("electric"), change("|-start|p1a: User|typechange|Water")!!)
        state.apply(first, setOf("electric"), change("|-start|p1a: User|typeadd|Ghost")!!)
        state.apply(second, setOf("fire"), change("|-start|p1b: Other|typeadd|Grass")!!)
        assertEquals(setOf("water"), state.apply(first, setOf("electric"), change("|-end|p1a: User|typeadd")!!))
        assertEquals(setOf("fire", "grass"), state.snapshot()[second])
        state.reset()
        assertTrue(state.snapshot().isEmpty())
    }

    private fun change(line: String) = Cobblemon173PublicTypeChange.fromMessage(BattleMessage(line))
    private fun pokemon(side: BattleSide, id: UUID) = Cobblemon173PublicPokemonSnapshot(
        id, side, 0, "cobblemon:pikachu", null, 50, 1.0, null, emptyMap(), false, setOf("electric"),
    )
}
