package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.mcc.betterai.search.NativeInformationSetSearch
import jbro.cobblemon.mcc.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.mcc.betterai.search.NativeProductSearchRunner
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchAggregator
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchInput
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchRequest
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchResult
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.mcc.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownSearchTree
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The information-set search proves the same minimax as the per-world search. With one world nothing is averaged
 * between worlds, so once replacements spend a turn as they do there, every root value must agree exactly.
 */
class NativeInformationSetSearchTest {
    private val worker = EngineBranchWorker()

    @Test
    fun `one world gives the per-world minimax values`() {
        val root = worker.createBattle(battle())
        val perWorld = perWorld(request(listOf(root)))
        val informationSet = informationSet(request(listOf(root)), replacementSpendsTurn = true)
        assertEquals(NativeProductWorldSearchStatus.COMPLETED, perWorld.status, "$perWorld")
        assertEquals(NativeProductWorldSearchStatus.COMPLETED, informationSet.status, "$informationSet")
        assertEquals(values(perWorld).keys, values(informationSet).keys)
        values(perWorld).forEach { (id, value) ->
            assertEquals(value, values(informationSet).getValue(id), 1e-9, id)
        }
    }

    @Test
    fun `a narrower tier follows the same few replies as the per-world search`() {
        val root = worker.createBattle(battle())
        val perWorld = perWorld(request(listOf(root), opponentResponseLimit = 2))
        val informationSet = informationSet(request(listOf(root), opponentResponseLimit = 2), replacementSpendsTurn = true)
        assertEquals(NativeProductWorldSearchStatus.COMPLETED, perWorld.status, "$perWorld")
        assertEquals(NativeProductWorldSearchStatus.COMPLETED, informationSet.status, "$informationSet")
        assertEquals(values(perWorld).keys, values(informationSet).keys)
        values(perWorld).forEach { (id, value) ->
            assertEquals(value, values(informationSet).getValue(id), 1e-9, id)
        }
    }

    @Test
    fun `every sampled world keeps its root for the next turn`() {
        val root = worker.createBattle(battle())
        val roots = listOf(root) + (1..3).map { worker.reseed(root.snapshotJson, it) }
        val result = informationSet(request(roots), replacementSpendsTurn = false)
        assertEquals(NativeProductWorldSearchStatus.COMPLETED, result.status, "$result")
        assertEquals(4, result.rootSnapshots.size)
        assertTrue(result.rootValues.all { it.value.isFinite() })
    }

    @Test
    fun `a root candidate the rules rule out is neither searched nor valued`() {
        val root = worker.createBattle(battle())
        val full = informationSet(request(listOf(root)), replacementSpendsTurn = false)
        val excluded = values(full).keys.first { it.contains("switch", ignoreCase = true) }
        val pruned = informationSet(request(listOf(root)).copy(excludedRootActionIds = setOf(excluded)), replacementSpendsTurn = false)
        assertEquals(NativeProductWorldSearchStatus.COMPLETED, pruned.status, "$pruned")
        assertEquals(values(full).keys - excluded, values(pruned).keys)
        values(pruned).forEach { (id, value) -> assertEquals(values(full).getValue(id), value, 1e-9, id) }
        assertTrue(pruned.nodesVisited < full.nodesVisited, "${pruned.nodesVisited} < ${full.nodesVisited}")
    }

    private fun perWorld(request: NativeProductWorldSearchRequest) = NativeProductWorldSearchAggregator(
        NativeProductSearchRunner(nanoTime = { 0L }, lease = { _, action -> action(worker) })::run,
    ).search(request)

    private fun informationSet(request: NativeProductWorldSearchRequest, replacementSpendsTurn: Boolean) =
        NativeInformationSetSearch(replacementSpendsTurn, lease = { _, action -> action(worker) }).search(request)

    private fun values(result: NativeProductWorldSearchResult): Map<String, Double> =
        result.rootValues.associate { it.action.actionId to it.value }

    private fun request(roots: List<NativeBattleFrame>, opponentResponseLimit: Int? = null): NativeProductWorldSearchRequest {
        val tree = NativeShowdownSearchTree(worker, roots.first(), template())
        return NativeProductWorldSearchRequest(
            worlds = roots.mapIndexed { index, frame ->
                NativeProductWorldSearchInput(
                    key = NativeSearchWorldKey("w", index),
                    probability = 1.0 / roots.size,
                    definition = battle(),
                    publicState = template(),
                    rootSnapshot = NativeProductRootSnapshot(worker.rulesFingerprint, frame),
                    evaluate = LocalBoardMaterial::evaluate,
                )
            },
            productActions = tree.actions(tree.root, BattleSide.ALLY),
            maxDepth = 2,
            opponentResponseLimit = opponentResponseLimit,
            nodeLimit = 1_000_000,
            deadlineNanos = Long.MAX_VALUE,
            nanoTime = { 0L },
        )
    }

    private fun battle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(11, 13, 17, 19),
        p1Team = listOf(
            NativePokemonSet("Garchomp", "Garchomp", listOf("earthquake", "dragonclaw", "swordsdance", "stoneedge"), "Rough Skin",
                ALLY_LEAD.toString(), nature = "Jolly", level = 50,
                evs = mapOf("hp" to 4, "atk" to 252, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 252)),
            NativePokemonSet("Ferrothorn", "Ferrothorn", listOf("powerwhip", "gyroball", "leechseed", "protect"), "Iron Barbs",
                ALLY_BENCH.toString(), nature = "Relaxed", level = 50,
                evs = mapOf("hp" to 252, "atk" to 4, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 0)),
        ),
        p2Team = listOf(
            NativePokemonSet("Dragonite", "Dragonite", listOf("extremespeed", "dragonclaw", "earthquake", "dragondance"), "Multiscale",
                OPPONENT_LEAD.toString(), nature = "Adamant", level = 50,
                evs = mapOf("hp" to 4, "atk" to 252, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 252)),
            NativePokemonSet("Blissey", "Blissey", listOf("seismictoss", "softboiled", "toxic", "flamethrower"), "Natural Cure",
                OPPONENT_BENCH.toString(), nature = "Bold", level = 50,
                evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 4, "spe" to 0)),
        ),
    )

    private fun template() = BattleStateView(
        battleId = BATTLE, format = BattleFormat.SINGLE, turn = 1,
        pokemon = listOf(
            mon(ALLY_LEAD, BattleSide.ALLY, "garchomp", setOf("dragon", "ground"), 0),
            mon(ALLY_BENCH, BattleSide.ALLY, "ferrothorn", setOf("grass", "steel"), null),
            mon(OPPONENT_LEAD, BattleSide.OPPONENT, "dragonite", setOf("dragon", "flying"), 0),
            mon(OPPONENT_BENCH, BattleSide.OPPONENT, "blissey", setOf("normal"), null),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2),
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(id: UUID, side: BattleSide, species: String, types: Set<String>, slot: Int?) = BattlePokemonStateView(
        battlePokemonId = id, side = side, activeSlot = slot, speciesId = "cobblemon:$species", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null,
        knownHeldItemId = null, fainted = false, knownTypeIds = types,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000003301")
        val ALLY_LEAD: UUID = UUID.fromString("00000000-0000-0000-0000-000000003302")
        val ALLY_BENCH: UUID = UUID.fromString("00000000-0000-0000-0000-000000003303")
        val OPPONENT_LEAD: UUID = UUID.fromString("00000000-0000-0000-0000-000000003304")
        val OPPONENT_BENCH: UUID = UUID.fromString("00000000-0000-0000-0000-000000003305")
    }
}
