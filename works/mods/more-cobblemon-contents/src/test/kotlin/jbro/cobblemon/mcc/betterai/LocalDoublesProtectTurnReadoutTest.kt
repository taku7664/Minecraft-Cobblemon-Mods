package jbro.cobblemon.mcc.betterai

import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.mcc.betterai.brain.mergeDoublesProtectCredits
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.matchup.IntentKind
import jbro.cobblemon.mcc.betterai.matchup.IntentOption
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe
import jbro.cobblemon.mcc.betterai.search.LocalEngineTurnReadout
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalDoublesProtectTurnReadoutTest {
    private val worker = EngineBranchWorker()
    private var previousLease: LocalEngineStopProbe.Lease? = null

    @BeforeAll
    fun engine() {
        previousLease = LocalEngineStopProbe.lease
        LocalEngineStopProbe.lease = object : LocalEngineStopProbe.Lease {
            override fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T? = action(worker)
        }
    }

    @AfterAll
    fun releaseEngine() {
        previousLease?.let { LocalEngineStopProbe.lease = it }
    }

    @Test
    fun `contact retaliation Protect is worth more than giving both opponents a setup turn`() {
        val contact = reading(listOf("tackle", "swordsdance"), listOf(IntentKind.ATTACK, IntentKind.SETUP))
        val setup = reading(listOf("swordsdance", "swordsdance"), listOf(IntentKind.SETUP, IntentKind.SETUP))

        println("DOUBLES PROTECT contact=$contact setup=$setup")
        assertEquals("SUCCESS", contact.status)
        assertEquals("SUCCESS", setup.status)
        assertTrue(contact.values.getValue(JOINT_ID) > 0.0, contact.toString())
        assertTrue(contact.values.getValue(JOINT_ID) > setup.values.getValue(JOINT_ID), "$contact vs $setup")
    }

    @Test
    fun `a doubles world failure leaves the existing Protect credit unchanged`() {
        val context = context(listOf("tackle", "swordsdance"), turn = 3)
        val result = LocalEngineTurnReadout.doublesProtectTurns(
            context,
            BattleTrainerTier.BOSS,
            context.candidates,
            intents(context, listOf("tackle", "swordsdance"), listOf(IntentKind.ATTACK, IntentKind.SETUP)),
            LocalDecisionTuning.CURRENT,
            seedCount = 1,
        )
        val credit = mapOf(JOINT_ID to 17.25, "ordinary" to -3.0)

        assertEquals("PLAN_FAILED", result.status)
        assertTrue(result.values.isEmpty())
        assertEquals(credit, mergeDoublesProtectCredits(credit, result.values))
    }

    private fun reading(moves: List<String>, kinds: List<IntentKind>): LocalEngineTurnReadout.DoublesProtectReading {
        val context = context(moves)
        return LocalEngineTurnReadout.doublesProtectTurns(
            context,
            BattleTrainerTier.BOSS,
            context.candidates,
            intents(context, moves, kinds),
            LocalDecisionTuning.CURRENT,
            seedCount = 1,
        )
    }

    private fun intents(context: BattleDecisionContext, moves: List<String>, kinds: List<IntentKind>) =
        context.state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null }
            .sortedBy { it.activeSlot }
            .mapIndexed { index, pokemon ->
                OpponentIntent(pokemon.battlePokemonId, requireNotNull(pokemon.activeSlot), listOf(IntentOption(
                    kinds[index],
                    moves[index],
                    ALLIES.first().takeIf { kinds[index] == IntentKind.ATTACK },
                    null,
                    1.0,
                    1.0,
                )))
            }

    private fun context(opponentMoves: List<String>, turn: Int = 1): BattleDecisionContext {
        val shield = BattleActionCandidate(
            actionId = "shield",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "spikyshield",
            moveDetails = details("spikyshield"),
        )
        val partner = BattleActionCandidate(
            actionId = "partner-hit",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 1,
            moveSlot = 0,
            moveId = "tackle",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 1)),
            moveDetails = details("tackle"),
        )
        val joint = BattleActionCandidate(
            actionId = JOINT_ID,
            kind = BattleActionKind.COMPOSITE,
            componentActionIds = listOf(shield.actionId, partner.actionId),
            componentActions = listOf(shield, partner),
        )
        val allySpecies = listOf("chesnaught", "mew", "celebi", "jirachi")
        val opponentSpecies = listOf("mewtwo", "pikachu", "raichu", "snorlax")
        val pokemon = ALLIES.mapIndexed { index, id ->
            pokemon(id, BattleSide.ALLY, index.takeIf { it < 2 }, allySpecies[index],
                if (index == 0) setOf("spikyshield") else setOf("tackle"), exact = true)
        } + OPPONENTS.take(2).mapIndexed { index, id ->
            pokemon(id, BattleSide.OPPONENT, index, opponentSpecies[index], setOf(opponentMoves[index]), exact = false)
        }
        val state = BattleStateView(
            battleId = BATTLE,
            format = BattleFormat.DOUBLE,
            turn = turn,
            pokemon = pokemon,
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 4, BattleSide.OPPONENT to 4),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val catalog = BattlePublicActionCatalogView(
            ALLIES.mapIndexed { index, id ->
                val move = if (index == 0) "spikyshield" else "tackle"
                BattlePokemonActionCatalogView(id, listOf(BattlePublicMoveOptionView(
                    move, details(move), BattlePublicMoveKnowledge.EXACT_OWN,
                )), true)
            } + OPPONENTS.take(2).mapIndexed { index, id ->
                val move = opponentMoves[index]
                BattlePokemonActionCatalogView(id, listOf(BattlePublicMoveOptionView(
                    move, details(move), BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                )), true)
            },
        )
        val exactOwn = BattleExactOwnTeamView(ALLIES.mapIndexed { index, id ->
            BattleExactPokemonBuildView(
                id, "synchronize", null, "serious", "N", ZERO_EVS, PERFECT_IVS,
                "normal", allySpecies[index],
            )
        })
        val preview = BattleOpponentTeamPreviewView(4, opponentSpecies.mapIndexed { index, species ->
            val move = opponentMoves.getOrElse(index) { "tackle" }
            val speciesId = "cobblemon:$species"
            BattleOpponentTeamPreviewPokemonView(
                index,
                speciesId,
                "normal",
                50,
                setOf("normal"),
                moveCandidatePool = BattleOpponentPreviewMovePoolView(
                    speciesId, "normal", setOf(move), "fixture:protect", mapOf(move to details(move)),
                ),
                buildCandidatePool = BattleOpponentPreviewBuildPoolView(
                    speciesId, "normal",
                    listOf(BattleOpponentPreviewAbilityView("synchronize", BattleAbilityAvailability.REGULAR)),
                    mapOf("N" to 1.0), "fixture:protect",
                ),
                showdownSpeciesId = species,
            )
        })
        return BattleDecisionContext(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            state,
            listOf(joint),
            System.currentTimeMillis() + 60_000L,
            publicActionCatalog = catalog,
        ).copy(opponentTeamPreview = preview, exactOwnTeam = exactOwn)
    }

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        activeSlot: Int?,
        species: String,
        moves: Set<String>,
        exact: Boolean,
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
        knownMoveIds = moves,
        knownAbilityId = "synchronize",
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = BattleCombatStatRangesView.exact(160, 100, 100, 100, 100, 100).takeIf { exact },
    )

    private fun details(move: String): BattleMoveCandidateView = when (move) {
        "spikyshield" -> BattleMoveCandidateView(
            "grass", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 4, 10,
            BattleMoveTargetPattern.SELF, MOVES.getValue(move),
        )
        "swordsdance" -> BattleMoveCandidateView(
            "normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 20,
            BattleMoveTargetPattern.SELF, MOVES.getValue(move),
        )
        else -> BattleMoveCandidateView(
            "normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 35,
            BattleMoveTargetPattern.SELECTED_OPPONENT, MOVES.getValue(move),
        )
    }

    private companion object {
        const val JOINT_ID = "joint-shield-hit"
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLIES = (101..104).map { UUID.fromString("00000000-0000-0000-0000-000000000$it") }
        val OPPONENTS = (201..204).map { UUID.fromString("00000000-0000-0000-0000-000000000$it") }
        val ZERO_EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
        val PERFECT_IVS = ZERO_EVS.mapValues { 31 }
        val MOVES: Map<String, BattleMoveEffectsView> by lazy {
            ZipInputStream(requireNotNull(LocalDoublesProtectTurnReadoutTest::class.java
                .getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/moves.js" }
                BattleDeclarativeMoveEffects.parse(zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
