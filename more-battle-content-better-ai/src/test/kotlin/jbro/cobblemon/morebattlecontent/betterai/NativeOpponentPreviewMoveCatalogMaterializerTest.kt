package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMaterializedOpponentRoster
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinitionIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleWorldHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBuildKnowledge
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialBattleDefinitionCompiler
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveHypothesisCompiler
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentMoveSetHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentPreviewMoveCatalogIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentPreviewMoveCatalogMaterializer
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonBuildHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePublicPokemonIdentity
import jbro.cobblemon.morebattlecontent.betterai.state.LocalMoveUsageLookup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeOpponentPreviewMoveCatalogMaterializerTest {
    @Test
    fun `boss preview move marginals form a normalized bounded zero one two stab posterior`() {
        val result = NativeOpponentPreviewMoveCatalogMaterializer.materialize(
            roster = roster(),
            preview = preview(),
            sourceCatalog = BattlePublicActionCatalogView(emptyList()),
            tier = BattleTrainerTier.BOSS,
            usage = LocalMoveUsageLookup { _, _, move ->
                when (move) {
                    "moonblast", "shadowball" -> 0.5
                    else -> 0.4
                }
            },
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(16, result.worlds.size)
        assertEquals(1.0, result.worlds.sumOf { it.probability }, 1e-9)
        val massByShape = result.worlds.groupBy { world ->
            requireNotNull(world.catalog.inferredMovesForPokemon(BENCH_A)).slots.count {
                it.group == BattleOpponentMoveGroup.STAB_ATTACK &&
                    it.knowledge == BattleOpponentMoveKnowledge.EXPECTED
            }
        }.mapValues { (_, worlds) -> worlds.sumOf { it.probability } }
        assertEquals(setOf(0, 1, 2), massByShape.keys)
        assertEquals(0.25, massByShape.getValue(0), 1e-9)
        assertEquals(0.50, massByShape.getValue(1), 1e-9)
        assertEquals(0.25, massByShape.getValue(2), 1e-9)
        val oneStabVariants = result.worlds.map { world ->
            requireNotNull(world.catalog.inferredMovesForPokemon(BENCH_A)).slots
                .filter {
                    it.group == BattleOpponentMoveGroup.STAB_ATTACK &&
                        it.knowledge == BattleOpponentMoveKnowledge.EXPECTED
                }
                .mapNotNull { it.moveId }
                .toSet()
        }.filter { it.size == 1 }.toSet()
        assertEquals(
            setOf(setOf("moonblast"), setOf("shadowball")),
            oneStabVariants,
        )
    }

    @Test
    fun `selected synthetic bench receives public slots while revealed live inference wins`() {
        val roster = roster()
        val live = BattleOpponentMoveInferenceView(ACTIVE, listOf(
            confirmed(0, "thunderwave", BattleOpponentMoveGroup.STATUS_OTHER),
            expected(1, "moonblast", BattleOpponentMoveGroup.STAB_ATTACK),
            guess(2),
            guess(3),
        ))
        val source = BattlePublicActionCatalogView(
            entries = listOf(BattlePokemonActionCatalogView(
                ALLY,
                listOf(BattlePublicMoveOptionView(
                    "surf",
                    move("water", 90.0),
                    BattlePublicMoveKnowledge.EXACT_OWN,
                )),
                moveSetComplete = true,
            )),
            opponentMoveInferences = listOf(live),
        )

        val result = NativeOpponentPreviewMoveCatalogMaterializer.materialize(
            roster = roster,
            preview = preview(),
            sourceCatalog = source,
            tier = BattleTrainerTier.STANDARD,
            usage = LocalMoveUsageLookup { _, _, move ->
                mapOf("moonblast" to 0.9, "shadowball" to 0.8, "powergem" to 0.7)[move]
            },
        )

        assertTrue(result.issues.isEmpty())
        val catalog = result.worlds.single().catalog
        assertEquals(live.slots, catalog.inferredMovesForPokemon(ACTIVE)?.slots)
        assertEquals(
            setOf(ACTIVE, BENCH_A, BENCH_B),
            catalog.opponentMoveInferences.mapTo(linkedSetOf()) { it.battlePokemonId },
        )
        assertNull(catalog.inferredMovesForPokemon(UNSELECTED))
        val bench = requireNotNull(catalog.inferredMovesForPokemon(BENCH_A))
        assertEquals(1, bench.slots.count {
            it.group == BattleOpponentMoveGroup.STAB_ATTACK &&
                it.knowledge == BattleOpponentMoveKnowledge.EXPECTED
        })
        assertEquals(1, bench.slots.count {
            it.group == BattleOpponentMoveGroup.COVERAGE_ATTACK &&
                it.knowledge == BattleOpponentMoveKnowledge.EXPECTED
        })
        assertEquals(1, bench.slots.count {
            it.group == BattleOpponentMoveGroup.STATUS_OTHER &&
                it.knowledge == BattleOpponentMoveKnowledge.GUESS
        })
        assertEquals(
            listOf("moonblast", "powergem"),
            NativeMoveHypothesisCompiler.compile(
                roster.state.pokemon.single { it.battlePokemonId == BENCH_A },
                catalog,
            ).nativeMoveIds,
            "A guessed status role must not become a recursive native move",
        )
        assertFalse(catalog.opponentMoveInferences.flatMap { it.slots }.any {
            it.source == BattleOpponentMoveSource.DIFFICULTY_SET_READ
        })

        val definition = NativeInitialBattleDefinitionCompiler.compile(
            state = roster.state,
            catalog = catalog,
            identities = roster.identities,
            world = NativeBattleWorldHypothesis(
                hypothesisId = "preview-moves",
                probability = 1.0,
                pokemon = roster.state.pokemon.map { member ->
                    build(
                        member,
                        if (member.side == BattleSide.OPPONENT) {
                            requireNotNull(
                                NativeMoveHypothesisCompiler.compile(member, catalog).completeSetOrNull(),
                            )
                        } else {
                            null
                        },
                    )
                },
            ),
            seed = listOf(1, 2, 3, 4),
        )
        assertFalse(definition.issues.any { it.code == NativeBattleDefinitionIssueCode.MOVESET_UNAVAILABLE })
        assertEquals(3, requireNotNull(definition.definition).p2Team.size)
    }

    @Test
    fun `an existing live inference with fewer than four logical slots fails closed`() {
        val roster = roster()
        val source = BattlePublicActionCatalogView(
            entries = emptyList(),
            opponentMoveInferences = listOf(BattleOpponentMoveInferenceView(ACTIVE, listOf(
                confirmed(0, "thunderwave", BattleOpponentMoveGroup.STATUS_OTHER),
                expected(1, "moonblast", BattleOpponentMoveGroup.STAB_ATTACK),
            ))),
        )

        val result = NativeOpponentPreviewMoveCatalogMaterializer.materialize(
            roster = roster,
            preview = preview(),
            sourceCatalog = source,
            tier = BattleTrainerTier.STANDARD,
            usage = null,
        )

        assertTrue(result.worlds.isEmpty())
        assertEquals(
            listOf(NativeOpponentPreviewMoveCatalogIssueCode.NORMALIZED_MOVESET_INCOMPLETE),
            result.issues.map { it.code },
        )
        assertEquals(ACTIVE, result.issues.single().battlePokemonId)
    }

    @Test
    fun `public preview inference permits zero one or two stab slots without fabrication`() {
        val noStab = NativeOpponentPreviewMoveCatalogMaterializer.infer(
            pokemon(BENCH_A, null, "rock-only", setOf("ghost", "fairy")),
            previewPokemon(2, "rock-only", setOf("ghost", "fairy"), mapOf(
                "powergem" to move("rock", 80.0),
            )),
            BattleFormat.SINGLE,
            BattleTrainerTier.BOSS,
            usage = null,
        )
        val oneStab = NativeOpponentPreviewMoveCatalogMaterializer.infer(
            pokemon(BENCH_A, null, "one-stab", setOf("ghost", "fairy")),
            previewPokemon(2, "one-stab", setOf("ghost", "fairy"), mapOf(
                "moonblast" to move("fairy", 95.0),
                "powergem" to move("rock", 80.0),
            )),
            BattleFormat.SINGLE,
            BattleTrainerTier.BOSS,
            usage = null,
        )
        val twoStab = NativeOpponentPreviewMoveCatalogMaterializer.infer(
            pokemon(BENCH_A, null, "two-stab", setOf("ghost", "fairy")),
            previewPokemon(2, "two-stab", setOf("ghost", "fairy"), mapOf(
                "moonblast" to move("fairy", 95.0),
                "shadowball" to move("ghost", 80.0),
                "powergem" to move("rock", 80.0),
            )),
            BattleFormat.SINGLE,
            BattleTrainerTier.BOSS,
            usage = null,
        )

        assertEquals(0, noStab.slots.count { it.group == BattleOpponentMoveGroup.STAB_ATTACK })
        assertEquals(1, oneStab.slots.count { it.group == BattleOpponentMoveGroup.STAB_ATTACK })
        assertEquals(2, twoStab.slots.count { it.group == BattleOpponentMoveGroup.STAB_ATTACK })
        assertEquals("powergem", noStab.slots.single { it.knowledge == BattleOpponentMoveKnowledge.EXPECTED }.moveId)
    }

    @Test
    fun `missing selected preview pool fails closed instead of inventing native moves`() {
        val preview = BattleOpponentTeamPreviewView(
            3,
            (0 until 6).map { slot ->
                if (slot == 2) BattleOpponentTeamPreviewPokemonView(slot, "species-$slot", null, 50)
                else previewPokemon(slot, "species-$slot", setOf("normal"), mapOf(
                    "tackle" to move("normal", 40.0),
                ))
            },
        )

        val result = NativeOpponentPreviewMoveCatalogMaterializer.materialize(
            roster(),
            preview,
            BattlePublicActionCatalogView(emptyList()),
            BattleTrainerTier.STANDARD,
            null,
        )

        assertTrue(result.worlds.isEmpty())
        assertTrue(result.issues.any { it.previewSlotId == 2 })
    }

    private fun roster(): NativeMaterializedOpponentRoster {
        val members = listOf(
            pokemon(ALLY, 0, "ally", setOf("water"), BattleSide.ALLY),
            pokemon(ACTIVE, 0, "species-0", setOf("ghost", "fairy")),
            pokemon(BENCH_A, null, "species-2", setOf("ghost", "fairy")),
            pokemon(BENCH_B, null, "species-4", setOf("normal")),
        )
        return NativeMaterializedOpponentRoster(
            state = BattleStateView(
                BATTLE,
                BattleFormat.SINGLE,
                1,
                members,
                BattleFieldStateView.empty(),
                mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 3),
                emptyList(),
                emptyList(),
            ),
            identities = members.map { member ->
                NativePublicPokemonIdentity(
                    member.battlePokemonId,
                    member.speciesId,
                    member.formId,
                    member.speciesId,
                )
            },
            opponentPreviewSlotByPokemonId = mapOf(ACTIVE to 0, BENCH_A to 2, BENCH_B to 4),
        )
    }

    private fun preview() = BattleOpponentTeamPreviewView(
        3,
        (0 until 6).map { slot ->
            previewPokemon(
                slot,
                "species-$slot",
                if (slot == 4) setOf("normal") else setOf("ghost", "fairy"),
                if (slot == 4) mapOf("tackle" to move("normal", 40.0)) else linkedMapOf(
                    "moonblast" to move("fairy", 95.0),
                    "shadowball" to move("ghost", 80.0),
                    "powergem" to move("rock", 80.0),
                    "protect" to status("normal"),
                ),
            )
        },
    )

    private fun previewPokemon(
        slot: Int,
        species: String,
        types: Set<String>,
        moves: Map<String, BattleMoveCandidateView>,
    ) = BattleOpponentTeamPreviewPokemonView(
        slot,
        species,
        null,
        50,
        types,
        null,
        emptyMap(),
        BattleOpponentPreviewMovePoolView(species, null, moves.keys, "fixture:learnset", moves),
    )

    private fun pokemon(
        id: UUID,
        activeSlot: Int?,
        species: String,
        types: Set<String>,
        side: BattleSide = BattleSide.OPPONENT,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = activeSlot,
        speciesId = species,
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100)
        } else {
            null
        },
    )

    private fun build(
        pokemon: BattlePokemonStateView,
        opponentMoveSet: NativeOpponentMoveSetHypothesis? = null,
    ) = NativePokemonBuildHypothesis(
        battlePokemonId = pokemon.battlePokemonId,
        knowledge = if (pokemon.side == BattleSide.ALLY) {
            NativeBuildKnowledge.EXACT_OWN
        } else {
            NativeBuildKnowledge.PUBLIC_HYPOTHESIS
        },
        abilityId = "pressure",
        itemId = "",
        nature = "serious",
        gender = "N",
        evs = stats(0),
        ivs = stats(31),
        opponentMoveSet = opponentMoveSet,
    )

    private fun stats(value: Int) = setOf("hp", "atk", "def", "spa", "spd", "spe")
        .associateWith { value }

    private fun move(type: String, power: Double) = BattleMoveCandidateView(
        type, BattleMoveDamageCategory.SPECIAL, power, 100.0, 0, 16,
    )

    private fun status(type: String) = BattleMoveCandidateView(
        type, BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 16,
    )

    private fun expected(slot: Int, move: String, group: BattleOpponentMoveGroup) = BattleOpponentMoveSlotView(
        slot, move, group, BattleOpponentMoveKnowledge.EXPECTED,
        BattleOpponentMoveSource.LEARNSET_EXPECTATION, move("normal", 1.0),
    )

    private fun confirmed(slot: Int, move: String, group: BattleOpponentMoveGroup) = BattleOpponentMoveSlotView(
        slot, move, group, BattleOpponentMoveKnowledge.CONFIRMED,
        BattleOpponentMoveSource.PUBLIC_REVEAL, status("electric"),
    )

    private fun guess(slot: Int) = BattleOpponentMoveSlotView(
        slot,
        null,
        BattleOpponentMoveGroup.OTHER,
        BattleOpponentMoveKnowledge.GUESS,
        BattleOpponentMoveSource.GROUP_GUESS,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val ACTIVE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000020")
        val BENCH_A: UUID = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val BENCH_B: UUID = UUID.fromString("00000000-0000-0000-0000-000000000024")
        val UNSELECTED: UUID = UUID.fromString("00000000-0000-0000-0000-000000000025")
    }
}
