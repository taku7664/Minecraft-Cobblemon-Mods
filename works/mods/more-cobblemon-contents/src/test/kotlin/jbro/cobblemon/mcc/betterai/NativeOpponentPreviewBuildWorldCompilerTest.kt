package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.BattleAbilityAvailability
import jbro.cobblemon.mcc.internal.ai.BattleOpponentPreviewAbilityView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentPreviewBuildPoolView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.engine.sim.OFFENSIVE_ASSUMPTION_NATURE
import jbro.cobblemon.mcc.betterai.simulation.NativeOpponentBuildWorldIssueCode
import jbro.cobblemon.mcc.betterai.simulation.NativeOpponentPreviewBuildWorldCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The opponent's build worlds come from public facts and fixed assumptions only (2026-10-12): no usage statistics. */
class NativeOpponentPreviewBuildWorldCompilerTest {
    @Test
    fun `unrevealed abilities split the worlds evenly and nothing else is guessed`() {
        val result = NativeOpponentPreviewBuildWorldCompiler.compile(preview(), listOf(0, 1), BattleTrainerTier.BOSS)

        assertTrue(result.issues.isEmpty(), result.issues.toString())
        // Two legal abilities each: four team worlds of equal weight.
        assertEquals(4, result.worlds.size)
        result.worlds.forEach { assertEquals(0.25, it.probability, 1e-12) }
        val builds = result.worlds.flatMap { it.builds }
        assertEquals(setOf("multiscale", "innerfocus", "mirrorarmor", "pressure"), builds.map { it.abilityId }.toSet())
        // No item, the offensive stand-in nature, the species' own first type as Tera.
        assertTrue(builds.all { it.itemId == null }, builds.toString())
        assertTrue(builds.all { it.natureId == OFFENSIVE_ASSUMPTION_NATURE })
        assertEquals(setOf("dragon"), builds.filter { it.previewSlotId == 0 }.map { it.teraTypeId }.toSet())
        assertEquals(setOf("flying"), builds.filter { it.previewSlotId == 1 }.map { it.teraTypeId }.toSet())
        // Deterministic.
        assertEquals(result.worlds.map { it.hypothesisId },
            NativeOpponentPreviewBuildWorldCompiler.compile(preview(), listOf(0, 1), BattleTrainerTier.BOSS).worlds.map { it.hypothesisId })
    }

    @Test
    fun `a revealed ability and item fix that slot`() {
        val result = NativeOpponentPreviewBuildWorldCompiler.compile(preview(), listOf(0, 1), BattleTrainerTier.BOSS,
            revealedAbilitiesBySlot = mapOf(0 to "multiscale"), revealedItemsBySlot = mapOf(1 to "leftovers"))

        assertTrue(result.issues.isEmpty(), result.issues.toString())
        assertEquals(2, result.worlds.size)
        val builds = result.worlds.flatMap { it.builds }
        assertEquals(setOf("multiscale"), builds.filter { it.previewSlotId == 0 }.map { it.abilityId }.toSet())
        assertEquals(setOf("leftovers"), builds.filter { it.previewSlotId == 1 }.map { it.itemId }.toSet())
        assertEquals(setOf<String?>(null), builds.filter { it.previewSlotId == 0 }.map { it.itemId }.toSet())
    }

    @Test
    fun `the evs follow the tier's assumption from the dex's base stats`() {
        val build = NativeOpponentPreviewBuildWorldCompiler.compile(
            BattleOpponentTeamPreviewView(1, listOf(preview().pokemon.first())), listOf(0), BattleTrainerTier.STANDARD,
        ).worlds.first().builds.single()
        // Dragonite: Attack 134, then Special Attack 100 (tied with Special Defence, taken in stat order).
        assertEquals(mapOf("hp" to 0, "atk" to 252, "def" to 0, "spa" to 252, "spd" to 0, "spe" to 0), build.evs)
        assertEquals(31, build.ivs.getValue("spe"))
    }

    @Test
    fun `missing public build pool is explicit failure`() {
        val pokemon = preview().pokemon.first()
        val result = NativeOpponentPreviewBuildWorldCompiler.compile(
            BattleOpponentTeamPreviewView(1, listOf(BattleOpponentTeamPreviewPokemonView(
                pokemon.previewSlotId, pokemon.speciesId, pokemon.formId, pokemon.level, pokemon.knownTypeIds,
            ))),
            listOf(0), BattleTrainerTier.BOSS,
        )
        assertEquals(listOf(NativeOpponentBuildWorldIssueCode.PUBLIC_BUILD_POOL_MISSING), result.issues.map { it.code })
    }

    private fun preview() = BattleOpponentTeamPreviewView(
        selectionSize = 2,
        pokemon = listOf(
            previewPokemon(0, "dragonite", listOf(
                BattleOpponentPreviewAbilityView("multiscale", BattleAbilityAvailability.HIDDEN),
                BattleOpponentPreviewAbilityView("innerfocus", BattleAbilityAvailability.REGULAR),
            ), setOf("dragon", "flying")),
            previewPokemon(1, "corviknight", listOf(
                BattleOpponentPreviewAbilityView("mirrorarmor", BattleAbilityAvailability.HIDDEN),
                BattleOpponentPreviewAbilityView("pressure", BattleAbilityAvailability.REGULAR),
            ), setOf("flying", "steel")),
        ),
    )

    private fun previewPokemon(
        slot: Int,
        species: String,
        abilities: List<BattleOpponentPreviewAbilityView>,
        types: Set<String>,
    ) = BattleOpponentTeamPreviewPokemonView(
        previewSlotId = slot,
        speciesId = "cobblemon:$species",
        formId = "normal",
        level = 50,
        knownTypeIds = types,
        buildCandidatePool = BattleOpponentPreviewBuildPoolView(
            speciesId = "cobblemon:$species",
            formId = "normal",
            abilities = abilities,
            genderRates = mapOf("M" to 0.5, "F" to 0.5),
            sourceId = "fixture:public-form",
        ),
    )
}
