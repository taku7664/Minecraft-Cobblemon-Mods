package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactOwnTeamView
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactPokemonBuildView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentPreviewAbilityView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentPreviewBuildPoolView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentPreviewMovePoolView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveOptionView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlanIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.morebattlecontent.betterai.state.LocalMoveUsageLookup
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentBuildUsageEntry
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentBuildUsageLookup
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentSpreadUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeInitialProductWorldPlannerTest {
    @Test
    fun `compiles one normalized bounded product beam from public singles preview`() {
        val planner = planner()

        val first = planner.plan(context(), BattleTrainerTier.INTRODUCTORY)
        val replay = planner.plan(context(), BattleTrainerTier.INTRODUCTORY)

        assertTrue(first.issues.isEmpty())
        assertEquals(9, first.worlds.size)
        assertEquals(1.0, first.worlds.sumOf { it.probability }, 1e-9)
        assertEquals(first.worlds.map { it.definition.seed }, replay.worlds.map { it.definition.seed })
        assertNotEquals(first.worlds[0].definition.seed, first.worlds[1].definition.seed)
        first.worlds.forEach { world ->
            assertEquals(3, world.definition.p1Team.size)
            assertEquals(3, world.definition.p2Team.size)
            assertEquals(3, world.publicContext.state.pokemon.count { it.side == BattleSide.OPPONENT })
            assertEquals(3, world.publicContext.publicActionCatalog.opponentMoveInferences.size)
            assertTrue(world.definition.p1Team.all { it.species == "mew" })
            assertTrue(world.definition.p2Team.all { it.species.isNotBlank() })
        }
    }

    @Test
    fun `compiles the same product contract for public doubles four selection`() {
        val result = planner().plan(context(BattleFormat.DOUBLE), BattleTrainerTier.BOSS)

        assertTrue(result.issues.isEmpty())
        // Two public leads are already assigned, so the remaining two are chosen from four slots.
        assertEquals(6, result.worlds.size)
        assertEquals(1.0, result.worlds.sumOf { it.probability }, 1e-9)
        result.worlds.forEach { world ->
            assertEquals("cobblemondoubles", world.definition.formatId)
            assertEquals(4, world.definition.p1Team.size)
            assertEquals(4, world.definition.p2Team.size)
            assertEquals(listOf(0, 1), world.publicContext.state.pokemon
                .filter { it.side == BattleSide.OPPONENT }
                .mapNotNull { it.activeSlot }
                .sorted())
            assertEquals(4, world.publicContext.publicActionCatalog.opponentMoveInferences.size)
        }
    }

    @Test
    fun `missing canonical Showdown identity fails instead of inventing a form id`() {
        val result = planner().plan(
            context(preview = preview(selectionSize = 3, missingShowdownSlot = 0)),
            BattleTrainerTier.INTRODUCTORY,
        )

        assertTrue(result.worlds.isEmpty())
        assertEquals(
            listOf(NativeInitialProductWorldPlanIssueCode.PUBLIC_SPECIES_IDENTITY_MISSING),
            result.issues.map { it.code }.distinct(),
        )
    }

    @Test
    fun `a roster with missing build usage invalidates the plan instead of losing probability mass`() {
        val result = planner(
            buildUsage = LocalOpponentBuildUsageLookup { species, _ ->
                if (species.endsWith("mewtwo")) null else BUILD_USAGE
            },
        ).plan(context(), BattleTrainerTier.INTRODUCTORY)

        assertTrue(result.worlds.isEmpty())
        assertEquals(
            NativeInitialProductWorldPlanIssueCode.BUILD_WORLD_COMPILATION_FAILED,
            result.issues.single().code,
        )
    }

    private fun planner(
        buildUsage: LocalOpponentBuildUsageLookup = LocalOpponentBuildUsageLookup { _, _ -> BUILD_USAGE },
    ) = NativeInitialProductWorldPlanner(
        moveUsageForFormat = { LocalMoveUsageLookup { _, _, _ -> 1.0 } },
        buildUsageForFormat = { buildUsage },
    )

    private fun context(
        format: BattleFormat = BattleFormat.SINGLE,
        preview: BattleOpponentTeamPreviewView = preview(
            selectionSize = if (format == BattleFormat.SINGLE) 3 else 4,
        ),
    ): BattleDecisionContext {
        val selectionSize = preview.selectionSize
        val allyIds = ALLIES.take(selectionSize)
        val activeCount = if (format == BattleFormat.SINGLE) 1 else 2
        val state = BattleStateView(
            battleId = BATTLE,
            format = format,
            turn = 1,
            pokemon = allyIds.mapIndexed { index, id ->
                pokemon(
                    id = id,
                    side = BattleSide.ALLY,
                    activeSlot = index.takeIf { it < activeCount },
                    species = "mew",
                    types = setOf("psychic"),
                    stats = BattleCombatStatRangesView.exact(100, 100, 100, 100, 100, 100),
                )
            } + OPPONENTS.take(activeCount).mapIndexed { index, id ->
                val (species, type) = PREVIEW_SPECIES[index]
                pokemon(
                    id = id,
                    side = BattleSide.OPPONENT,
                    activeSlot = index,
                    species = species,
                    types = setOf(type),
                    stats = null,
                )
            },
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(
                BattleSide.ALLY to selectionSize,
                BattleSide.OPPONENT to selectionSize,
            ),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val exactOwn = BattleExactOwnTeamView(allyIds.map { id ->
            BattleExactPokemonBuildView(
                battlePokemonId = id,
                abilityId = "synchronize",
                heldItemId = null,
                natureId = "serious",
                gender = "N",
                evs = ZERO_EVS,
                ivs = PERFECT_IVS,
                teraTypeId = "psychic",
                showdownSpeciesId = "mew",
            )
        })
        val catalog = BattlePublicActionCatalogView(allyIds.map { id ->
            BattlePokemonActionCatalogView(
                battlePokemonId = id,
                moves = listOf(BattlePublicMoveOptionView(
                    moveId = "psychic",
                    details = move("psychic"),
                    knowledge = BattlePublicMoveKnowledge.EXACT_OWN,
                )),
                moveSetComplete = true,
            )
        })
        return BattleDecisionContext(
            requestId = REQUEST,
            state = state,
            candidates = listOf(BattleActionCandidate(
                actionId = "move-psychic",
                kind = BattleActionKind.USE_MOVE,
                actorSlot = 0,
                moveSlot = 0,
                moveId = "psychic",
            )),
            deadlineEpochMillis = Long.MAX_VALUE,
            publicActionCatalog = catalog,
        ).copy(opponentTeamPreview = preview, exactOwnTeam = exactOwn)
    }

    private fun preview(
        selectionSize: Int = 3,
        missingShowdownSlot: Int? = null,
    ) = BattleOpponentTeamPreviewView(
        selectionSize = selectionSize,
        pokemon = PREVIEW_SPECIES.mapIndexed { slot, (species, type) ->
            val speciesId = "cobblemon:$species"
            BattleOpponentTeamPreviewPokemonView(
                previewSlotId = slot,
                speciesId = speciesId,
                formId = "normal",
                level = 50,
                knownTypeIds = setOf(type),
                moveCandidatePool = BattleOpponentPreviewMovePoolView(
                    speciesId = speciesId,
                    formId = "normal",
                    moveIds = setOf(typeMove(type)),
                    sourceId = "fixture:learnset",
                    moveDetails = mapOf(typeMove(type) to move(type)),
                ),
                buildCandidatePool = BattleOpponentPreviewBuildPoolView(
                    speciesId = speciesId,
                    formId = "normal",
                    abilities = listOf(BattleOpponentPreviewAbilityView(
                        "synchronize",
                        BattleAbilityAvailability.REGULAR,
                    )),
                    genderRates = mapOf("N" to 1.0),
                    sourceId = "fixture:form",
                ),
                showdownSpeciesId = species.takeUnless { slot == missingShowdownSlot },
            )
        },
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        activeSlot: Int?,
        species: String,
        types: Set<String>,
        stats: BattleCombatStatRangesView?,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = activeSlot,
        speciesId = "cobblemon:$species",
        formId = "normal",
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = types,
        combatStats = stats,
    )

    private fun typeMove(type: String): String = when (type) {
        "psychic" -> "psychic"
        "electric" -> "thunderbolt"
        else -> "tackle"
    }

    private fun move(type: String) = BattleMoveCandidateView(
        typeId = type,
        damageCategory = BattleMoveDamageCategory.SPECIAL,
        power = 80.0,
        accuracy = 100.0,
        priority = 0,
        currentPp = 10,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val ALLIES = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000102"),
            UUID.fromString("00000000-0000-0000-0000-000000000103"),
            UUID.fromString("00000000-0000-0000-0000-000000000104"),
        )
        val OPPONENTS = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000201"),
            UUID.fromString("00000000-0000-0000-0000-000000000202"),
        )
        val PREVIEW_SPECIES = listOf(
            "mew" to "psychic",
            "mewtwo" to "psychic",
            "pikachu" to "electric",
            "raichu" to "electric",
            "eevee" to "normal",
            "snorlax" to "normal",
        )
        val ZERO_EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
        val PERFECT_IVS = ZERO_EVS.mapValues { 31 }
        val BUILD_USAGE = LocalOpponentBuildUsageEntry(
            abilityRates = mapOf("synchronize" to 1.0),
            itemRates = emptyMap(),
            noItemRate = 1.0,
            spreads = listOf(LocalOpponentSpreadUsage(
                natureId = "adamant",
                evs = mapOf("hp" to 0, "atk" to 252, "def" to 0, "spa" to 0, "spd" to 4, "spe" to 252),
                rate = 1.0,
            )),
            unresolvedSpreadRate = 0.0,
            teraTypeRates = mapOf("normal" to 1.0),
        )
    }
}
