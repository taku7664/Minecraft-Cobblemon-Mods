package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelection
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelector
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluation
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRankAdapter
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeRecursiveSearch
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeStellarProductBrainIntegrationTest {
    @Test
    fun `consumed Stellar type changes real native values and the product Brain choice`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = stellarDecisionBattle()
            val opening = engine.createBattle(definition)
            val teraThunderbolt = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening).single {
                it.moveId == "thunderbolt" && it.mechanic?.mechanicId == "tera"
            }
            val opponentSplash = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, opening).single {
                it.moveId == "splash" && it.mechanic == null
            }
            val consumed = engine.branch(
                opening.snapshotJson,
                NativeShowdownChoiceEncoder.encode(teraThunderbolt, BattleSide.ALLY, opening),
                NativeShowdownChoiceEncoder.encode(opponentSplash, BattleSide.OPPONENT, opening),
            )

            assertEquals(listOf("Electric"), consumed.p1Active.single().stellarBoostedTypes)
            val tree = NativeShowdownSearchTree(engine, consumed, publicTemplate())
            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            assertEquals(setOf("thunderbolt", "icebeam"), candidates.mapNotNull { it.moveId }.toSet())

            val search = NativeRecursiveSearch(
                tree = tree,
                world = NativeSearchWorldKey("stellar-consumption", 0),
                evaluate = ::material,
                nodeLimit = 16,
            ).evaluate(maxDepth = 1)
            val values = search.rootValues.associate { it.action.moveId to it.value }
            assertTrue(requireNotNull(values["icebeam"]) > requireNotNull(values["thunderbolt"]),
                "An unused off-type Stellar boost must outperform the already consumed equal-power type")

            val context = BattleDecisionContext(
                requestId = UUID.randomUUID(),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = Long.MAX_VALUE,
            )
            val ranks = NativeProductRankAdapter.rank(search.rootValues, material(tree.root.state))
            assertEquals("icebeam", ranks.first().outcome.candidate.moveId)
            val sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("stellar-consumption", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, consumed),
                    publicContext = context,
                )),
                publicTurn = context.state.turn,
                lastObservedEventSequence = null,
            )
            val brain = LocalTacticalBrain(
                actionSelector = LocalActionSelector { ranked, seed, _ ->
                    LocalActionSelection(ranked.first(), seed, ranked.size, 1.0)
                },
                nativeInitialDecision = { supplied, _, _, _, previous ->
                    assertEquals(context.candidates.map { it.actionId }, supplied.candidates.map { it.actionId })
                    assertEquals(null, previous)
                    NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.AVAILABLE,
                        ranked = ranks,
                        depthCompleted = search.depthCompleted,
                        nodesVisited = search.nodesVisited,
                        searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                        sessionState = sessionState,
                    )
                },
            )
            val brainSession = brain.openSession(BattleBrainOpenContext(
                battleId = context.state.battleId,
                format = BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(2),
            ))

            val decision = brain.decide(brainSession, context).toCompletableFuture().get()

            assertEquals(candidates.single { it.moveId == "icebeam" }.actionId, decision.actionId)
            assertTrue("native_showdown_initial" in decision.tags)
            assertTrue("native_search_completed" in decision.tags)
        }
    }

    private fun stellarDecisionBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(131, 137, 139, 149),
        p1Team = listOf(NativePokemonSet(
            name = "Stellar Actor",
            species = "Mew",
            moves = listOf("thunderbolt", "icebeam"),
            ability = "synchronize",
            teraType = "Stellar",
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Observer",
            species = "Blissey",
            moves = listOf("splash"),
            ability = "naturalcure",
            uuid = OPPONENT.toString(),
        )),
    )

    private fun publicTemplate() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, "mew", setOf("psychic")),
            pokemon(OPPONENT, BattleSide.OPPONENT, "blissey", setOf("normal")),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        species: String,
        types: Set<String>,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "cobblemon:$species",
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
    )

    private fun material(state: BattleStateView): Double {
        val ally = state.pokemon.single { it.side == BattleSide.ALLY }.hpFraction
        val opponent = state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction
        return ally - opponent
    }

    private fun extractBundledShowdown(targetRoot: Path): Path {
        Files.createDirectories(targetRoot)
        val resource = requireNotNull(javaClass.getResourceAsStream("/data/cobblemon/showdown.zip")) {
            "Cobblemon embedded Showdown is missing from the test runtime"
        }
        var extracted = 0L
        ZipInputStream(resource).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = EmbeddedShowdownOracle.entryPath(targetRoot, entry.name)
                if (!entry.isDirectory && (entry.name.endsWith(".js") || entry.name.endsWith(".json"))) {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, CREATE_NEW).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extracted += count
                            require(extracted <= 128L * 1024 * 1024) {
                                "Embedded archive exceeds extraction limit"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return targetRoot.toAbsolutePath().normalize()
    }

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000301")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000302")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000303")
    }
}
