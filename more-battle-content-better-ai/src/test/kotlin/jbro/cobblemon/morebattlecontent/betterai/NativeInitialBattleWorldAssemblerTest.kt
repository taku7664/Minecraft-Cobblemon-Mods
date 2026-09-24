package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactOwnTeamView
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactPokemonBuildView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBuildKnowledge
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialBattleWorldAssembler
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialWorldAssemblyIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMaterializedOpponentRoster
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentPreviewBuildHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentPreviewBuildWorld
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeOpponentRosterHypothesis
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePublicPokemonIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeInitialBattleWorldAssemblerTest {
    @Test
    fun `maps public preview builds onto synthetic ids while keeping exact own builds separate`() {
        val roster = roster()
        val rosterHypothesis = NativeOpponentRosterHypothesis(
            hypothesisId = "roster:0,2,4",
            probability = 0.25,
            selectedPreviewSlotIds = listOf(0, 2, 4),
            revealedAssignments = mapOf(OPPONENT_0 to 0),
        )
        val buildWorld = NativeOpponentPreviewBuildWorld(
            hypothesisId = "sets:a",
            probability = 0.4,
            builds = listOf(
                opponentBuild(0, "protosynthesis"),
                opponentBuild(2, "regenerator"),
                opponentBuild(4, "grassy-surge"),
            ),
        )

        val result = NativeInitialBattleWorldAssembler.assemble(
            roster = roster,
            rosterHypothesis = rosterHypothesis,
            exactOwnTeam = BattleExactOwnTeamView(listOf(ownBuild())),
            opponentWorld = buildWorld,
        )

        assertTrue(result.issues.isEmpty())
        val world = requireNotNull(result.world)
        assertEquals("roster:0,2,4+sets:a", world.hypothesisId)
        assertEquals(0.1, world.probability, 1e-12)
        assertEquals(NativeBuildKnowledge.EXACT_OWN, world.pokemon.single { it.battlePokemonId == ALLY }.knowledge)
        assertEquals("static", world.pokemon.single { it.battlePokemonId == ALLY }.abilityId)
        assertEquals("lightball", world.pokemon.single { it.battlePokemonId == ALLY }.itemId)
        assertEquals("timid", world.pokemon.single { it.battlePokemonId == ALLY }.nature)
        assertEquals(31, world.pokemon.single { it.battlePokemonId == ALLY }.ivs.getValue("spe"))

        val idForSlot2 = roster.opponentPreviewSlotByPokemonId.entries.single { it.value == 2 }.key
        val idForSlot4 = roster.opponentPreviewSlotByPokemonId.entries.single { it.value == 4 }.key
        assertEquals("regenerator", world.pokemon.single { it.battlePokemonId == idForSlot2 }.abilityId)
        assertEquals("grassysurge", world.pokemon.single { it.battlePokemonId == idForSlot4 }.abilityId)
        assertTrue(world.pokemon.filter { it.battlePokemonId != ALLY }
            .all { it.knowledge == NativeBuildKnowledge.PUBLIC_HYPOTHESIS })
    }

    @Test
    fun `fails closed when a selected preview slot has no build`() {
        val result = NativeInitialBattleWorldAssembler.assemble(
            roster = roster(),
            rosterHypothesis = NativeOpponentRosterHypothesis(
                hypothesisId = "roster:0,2,4",
                probability = 1.0,
                selectedPreviewSlotIds = listOf(0, 2, 4),
                revealedAssignments = mapOf(OPPONENT_0 to 0),
            ),
            exactOwnTeam = BattleExactOwnTeamView(listOf(ownBuild())),
            opponentWorld = NativeOpponentPreviewBuildWorld(
                hypothesisId = "sets:incomplete",
                probability = 1.0,
                builds = listOf(opponentBuild(0, "protosynthesis"), opponentBuild(2, "regenerator")),
            ),
        )

        assertNull(result.world)
        assertEquals(
            listOf(NativeInitialWorldAssemblyIssueCode.OPPONENT_PREVIEW_BUILD_MISSING),
            result.issues.map { it.code },
        )
        assertEquals(4, result.issues.single().previewSlotId)
    }

    @Test
    fun `rejects a same-roster hypothesis that remaps the revealed lead to another slot`() {
        val result = NativeInitialBattleWorldAssembler.assemble(
            roster = roster(),
            rosterHypothesis = NativeOpponentRosterHypothesis(
                hypothesisId = "roster:0,2,4-wrong-map",
                probability = 1.0,
                selectedPreviewSlotIds = listOf(0, 2, 4),
                revealedAssignments = mapOf(OPPONENT_0 to 2),
            ),
            exactOwnTeam = BattleExactOwnTeamView(listOf(ownBuild())),
            opponentWorld = NativeOpponentPreviewBuildWorld(
                hypothesisId = "sets:a",
                probability = 1.0,
                builds = listOf(
                    opponentBuild(0, "protosynthesis"),
                    opponentBuild(2, "regenerator"),
                    opponentBuild(4, "grassy-surge"),
                ),
            ),
        )

        assertNull(result.world)
        assertEquals(
            listOf(NativeInitialWorldAssemblyIssueCode.ROSTER_HYPOTHESIS_MISMATCH),
            result.issues.map { it.code },
        )
    }

    private fun roster(): NativeMaterializedOpponentRoster {
        val state = BattleStateView(
            battleId = UUID(0, 900),
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = listOf(
                pokemon(ALLY, BattleSide.ALLY, 0, "pikachu"),
                pokemon(OPPONENT_0, BattleSide.OPPONENT, 0, "fluttermane"),
                pokemon(OPPONENT_2, BattleSide.OPPONENT, null, "amoonguss"),
                pokemon(OPPONENT_4, BattleSide.OPPONENT, null, "rillaboom"),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { if (it == BattleSide.ALLY) 1 else 3 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        return NativeMaterializedOpponentRoster(
            state = state,
            identities = state.pokemon.map {
                NativePublicPokemonIdentity(it.battlePokemonId, it.speciesId, it.formId, it.speciesId)
            },
            opponentPreviewSlotByPokemonId = mapOf(OPPONENT_0 to 0, OPPONENT_2 to 2, OPPONENT_4 to 4),
        )
    }

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        activeSlot: Int?,
        species: String,
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
        knownVolatileEffectIds = emptySet(),
    )

    private fun ownBuild() = BattleExactPokemonBuildView(
        battlePokemonId = ALLY,
        abilityId = "cobblemon:static",
        heldItemId = "cobblemon:light_ball",
        natureId = "cobblemon:timid",
        gender = "M",
        evs = spread(hp = 4, spa = 252, spe = 252),
        ivs = spread(31, 31, 31, 31, 31, 31),
    )

    private fun opponentBuild(slot: Int, ability: String) = NativeOpponentPreviewBuildHypothesis(
        previewSlotId = slot,
        abilityId = "cobblemon:$ability",
        itemId = "cobblemon:leftovers",
        natureId = "cobblemon:modest",
        gender = "N",
        evs = spread(hp = 252, spa = 252, spd = 4),
        ivs = spread(31, 31, 31, 31, 31, 31),
    )

    private fun spread(
        hp: Int = 0,
        atk: Int = 0,
        def: Int = 0,
        spa: Int = 0,
        spd: Int = 0,
        spe: Int = 0,
    ) = mapOf("hp" to hp, "atk" to atk, "def" to def, "spa" to spa, "spd" to spd, "spe" to spe)

    private companion object {
        val ALLY: UUID = UUID(0, 1)
        val OPPONENT_0: UUID = UUID(0, 2)
        val OPPONENT_2: UUID = UUID(0, 3)
        val OPPONENT_4: UUID = UUID(0, 4)
    }
}
