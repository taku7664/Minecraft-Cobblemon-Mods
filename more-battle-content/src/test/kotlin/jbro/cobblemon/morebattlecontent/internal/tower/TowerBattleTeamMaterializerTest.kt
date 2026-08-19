package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerBattleTeamMaterializerTest {
    @Test
    fun `materializes clones in selection order without mutating sources`() {
        val sources = validSources()
        val selection = selectedTeam(sources, listOf(3, 1, 5))
        val materializer = materializer()

        val result = materializer.materialize(selection, sources)

        result as TowerBattleTeamMaterialization.Created<Clone>
        assertEquals(listOf(sources[3].id, sources[1].id, sources[5].id), result.members.map(Clone::sourceId))
        assertEquals(listOf(44, 42, 46), result.members.map(Clone::battleLevel))
        assertEquals(listOf(41, 42, 43, 44, 45, 46), sources.map { it.level })
    }

    @Test
    fun `caps only clone battle level at fifty`() {
        val sources = validSources().toMutableList()
        sources[0] = sources[0].copy(level = 78)
        val selection = selectedTeam(sources, listOf(0, 1, 2))

        val result = materializer().materialize(selection, sources) as TowerBattleTeamMaterialization.Created<Clone>

        assertEquals(50, result.members.first().battleLevel)
        assertEquals(78, sources.first().level)
    }

    @Test
    fun `validates every source before creating any clone`() {
        val sources = validSources()
        val selection = selectedTeam(sources, listOf(0, 1, 2))
        val changed = sources.mapIndexed { index, source -> if (index == 2) source.copy(item = "minecraft:changed") else source }
        var cloneCalls = 0
        val materializer = TowerBattleTeamMaterializer<Source, Clone>(Source::registration) { source, level ->
            cloneCalls++
            Clone(source.id, level)
        }

        val result = materializer.materialize(selection, changed)

        assertEquals(TowerBattleTeamMaterialization.SourceChanged(sources[2].id), result)
        assertEquals(0, cloneCalls)
    }

    @Test
    fun `missing and duplicate current sources are rejected`() {
        val sources = validSources()
        val selection = selectedTeam(sources, listOf(0, 1, 2))
        val materializer = materializer()

        assertEquals(
            TowerBattleTeamMaterialization.MissingSource(sources[2].id),
            materializer.materialize(selection, sources.take(2)),
        )
        assertEquals(
            TowerBattleTeamMaterialization.DuplicateSource(sources[0].id),
            materializer.materialize(selection, sources + sources[0]),
        )
    }

    @Test
    fun `clone failure does not return a partial team`() {
        val sources = validSources()
        val selection = selectedTeam(sources, listOf(0, 1, 2))
        val materializer = TowerBattleTeamMaterializer<Source, Clone>(Source::registration) { source, level ->
            if (source == sources[1]) throw IllegalStateException("clone failed")
            Clone(source.id, level)
        }

        val result = materializer.materialize(selection, sources)

        assertTrue(result is TowerBattleTeamMaterialization.CloneFailed)
        assertEquals(sources[1].id, (result as TowerBattleTeamMaterialization.CloneFailed).pokemonId)
    }

    private fun materializer() = TowerBattleTeamMaterializer<Source, Clone>(Source::registration) { source, level ->
        Clone(source.id, level)
    }

    private fun selectedTeam(sources: List<Source>, indexes: List<Int>): TowerSelectedTeam {
        val registered = TowerTeamRules.register(sources.map(Source::registration)) as TowerTeamRegistrationResult.Accepted
        return (TowerTeamRules.select(
            registered.team,
            TowerBattleFormat.SINGLE,
            indexes.map { sources[it].id },
        ) as TowerTeamSelectionResult.Accepted).selection
    }

    private fun validSources(): List<Source> = (1..6).map { index ->
        Source(
            id = UUID(0, index.toLong()),
            species = "cobblemon:species_$index",
            item = if (index == 6) null else "minecraft:item_$index",
            level = 40 + index,
        )
    }

    private data class Source(val id: UUID, val species: String, val item: String?, val level: Int) {
        fun registration() = TowerPokemonRegistration(id, species, item, level)
    }

    private data class Clone(val sourceId: UUID, val battleLevel: Int)
}
