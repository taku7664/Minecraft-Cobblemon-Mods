package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentPreviewAbilityView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentPreviewBuildPoolView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentBuildWorldIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentPreviewBuildWorldCompiler
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentBuildUsageEntry
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentBuildUsageLookup
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentSpreadUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeOpponentPreviewBuildWorldCompilerTest {
    @Test
    fun `intersects public legal abilities applies item clause and normalizes a bounded team beam`() {
        val result = NativeOpponentPreviewBuildWorldCompiler.compile(
            preview = preview(),
            selectedPreviewSlotIds = listOf(0, 1),
            tier = BattleTrainerTier.BOSS,
            usage = usage(),
        )

        assertTrue(result.issues.isEmpty())
        assertTrue(result.worlds.isNotEmpty())
        assertTrue(result.worlds.size <= 32)
        assertEquals(1.0, result.worlds.sumOf { it.probability }, 1e-12)
        assertTrue(result.worlds.all { it.builds.map { build -> build.previewSlotId } == listOf(0, 1) })
        assertFalse(result.worlds.flatMap { it.builds }.any { it.abilityId == "levitate" })
        assertTrue(result.worlds.all { world ->
            val heldItems = world.builds.mapNotNull { it.itemId }
            heldItems.distinct().size == heldItems.size
        })
        assertEquals(
            result.worlds.map { it.hypothesisId },
            NativeOpponentPreviewBuildWorldCompiler.compile(
                preview(), listOf(0, 1), BattleTrainerTier.BOSS, usage(),
            ).worlds.map { it.hypothesisId },
        )
    }

    @Test
    fun `difficulty caps complete per-pokemon worlds without fabricating neutral spreads`() {
        val counts = BattleTrainerTier.entries.associateWith { tier ->
            NativeOpponentPreviewBuildWorldCompiler.compile(
                preview = BattleOpponentTeamPreviewView(1, listOf(preview().pokemon.first())),
                selectedPreviewSlotIds = listOf(0),
                tier = tier,
                usage = usage(),
            ).worlds
        }

        assertTrue(counts.getValue(BattleTrainerTier.INTRODUCTORY).size <= 3)
        assertTrue(counts.getValue(BattleTrainerTier.STANDARD).size <= 6)
        assertTrue(counts.getValue(BattleTrainerTier.ADVANCED).size <= 10)
        assertTrue(counts.getValue(BattleTrainerTier.BOSS).size <= 16)
        assertTrue(counts.values.zipWithNext().all { (left, right) -> left.size <= right.size })
        val bossBuilds = counts.getValue(BattleTrainerTier.BOSS).flatMap { it.builds }
        assertTrue(bossBuilds.all { it.natureId in setOf("adamant", "jolly") })
        assertTrue(bossBuilds.any { it.ivs.getValue("atk") == 0 })
        assertTrue(bossBuilds.any { it.ivs.getValue("spe") == 0 })
        assertTrue(bossBuilds.any { it.itemId == null })
    }

    @Test
    fun `fails closed when selected public build knowledge or usage is missing`() {
        val missingPool = NativeOpponentPreviewBuildWorldCompiler.compile(
            preview = BattleOpponentTeamPreviewView(1, listOf(
                BattleOpponentTeamPreviewPokemonView(0, "addon:unknown", null, 50),
            )),
            selectedPreviewSlotIds = listOf(0),
            tier = BattleTrainerTier.BOSS,
            usage = usage(),
        )
        val missingUsage = NativeOpponentPreviewBuildWorldCompiler.compile(
            preview = BattleOpponentTeamPreviewView(1, listOf(preview().pokemon.first())),
            selectedPreviewSlotIds = listOf(0),
            tier = BattleTrainerTier.BOSS,
            usage = LocalOpponentBuildUsageLookup { _, _ -> null },
        )

        assertTrue(missingPool.worlds.isEmpty())
        assertTrue(missingUsage.worlds.isEmpty())
        assertEquals(listOf(NativeOpponentBuildWorldIssueCode.PUBLIC_BUILD_POOL_MISSING),
            missingPool.issues.map { it.code })
        assertEquals(listOf(NativeOpponentBuildWorldIssueCode.BUILD_USAGE_MISSING),
            missingUsage.issues.map { it.code })
    }

    @Test
    fun `fails closed rather than accepting a usage ability illegal for the public form`() {
        val result = NativeOpponentPreviewBuildWorldCompiler.compile(
            preview = BattleOpponentTeamPreviewView(1, listOf(preview().pokemon.first())),
            selectedPreviewSlotIds = listOf(0),
            tier = BattleTrainerTier.BOSS,
            usage = LocalOpponentBuildUsageLookup { _, _ ->
                buildUsage(abilities = mapOf("levitate" to 1.0))
            },
        )

        assertTrue(result.worlds.isEmpty())
        assertEquals(listOf(NativeOpponentBuildWorldIssueCode.LEGAL_ABILITY_USAGE_MISSING),
            result.issues.map { it.code })
    }

    private fun preview() = BattleOpponentTeamPreviewView(
        selectionSize = 2,
        pokemon = listOf(
            previewPokemon(0, "dragonite", listOf(
                BattleOpponentPreviewAbilityView("multiscale", BattleAbilityAvailability.HIDDEN),
                BattleOpponentPreviewAbilityView("innerfocus", BattleAbilityAvailability.REGULAR),
            ), mapOf("M" to 0.5, "F" to 0.5)),
            previewPokemon(1, "corviknight", listOf(
                BattleOpponentPreviewAbilityView("mirrorarmor", BattleAbilityAvailability.HIDDEN),
                BattleOpponentPreviewAbilityView("pressure", BattleAbilityAvailability.REGULAR),
            ), mapOf("M" to 0.5, "F" to 0.5)),
        ),
    )

    private fun previewPokemon(
        slot: Int,
        species: String,
        abilities: List<BattleOpponentPreviewAbilityView>,
        genders: Map<String, Double>,
    ) = BattleOpponentTeamPreviewPokemonView(
        previewSlotId = slot,
        speciesId = "cobblemon:$species",
        formId = "normal",
        level = 50,
        buildCandidatePool = BattleOpponentPreviewBuildPoolView(
            speciesId = "cobblemon:$species",
            formId = "normal",
            abilities = abilities,
            genderRates = genders,
            sourceId = "fixture:public-form",
        ),
    )

    private fun usage() = LocalOpponentBuildUsageLookup { species, _ ->
        when (species.substringAfter(':')) {
            "dragonite" -> buildUsage(
                abilities = mapOf("multiscale" to 0.75, "innerfocus" to 0.2, "levitate" to 0.05),
            )
            "corviknight" -> buildUsage(
                abilities = mapOf("mirrorarmor" to 0.8, "pressure" to 0.2),
                items = mapOf("leftovers" to 0.8, "safetygoggles" to 0.15),
                noItem = 0.05,
            )
            else -> null
        }
    }

    private fun buildUsage(
        abilities: Map<String, Double>,
        items: Map<String, Double> = mapOf("leftovers" to 0.8, "focussash" to 0.15),
        noItem: Double = 0.05,
    ) = LocalOpponentBuildUsageEntry(
        abilityRates = abilities,
        itemRates = items,
        noItemRate = noItem,
        spreads = listOf(
            LocalOpponentSpreadUsage("adamant", spread(atk = 252, spe = 252), 0.7),
            LocalOpponentSpreadUsage("jolly", spread(hp = 252), 0.3),
        ),
        unresolvedSpreadRate = 0.0,
        teraTypeRates = mapOf("normal" to 1.0),
    )

    private fun spread(
        hp: Int = 0,
        atk: Int = 0,
        def: Int = 0,
        spa: Int = 0,
        spd: Int = 0,
        spe: Int = 0,
    ) = mapOf("hp" to hp, "atk" to atk, "def" to def, "spa" to spa, "spd" to spd, "spe" to spe)
}
