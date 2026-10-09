package jbro.cobblemon.mcc.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelection
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelector
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.mcc.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.mcc.betterai.search.NativeProductSearchRunner
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionReconciliation
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionState
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.mcc.betterai.search.NativeInformationSetSearch
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchAggregator
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.mcc.betterai.search.NativeRecursiveSearch
import jbro.cobblemon.mcc.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeDefensiveSetupProductBrainIntegrationTest {
    @Test
    fun `Iron Defense gains value only from future physical damage and drives the product Brain`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val physicalDefinition = battle("Machop", listOf("tackle"), PHYSICAL)
            val physical = search(engine, physicalDefinition, engine.createBattle(physicalDefinition))
            val specialDefinition = battle("Slowpoke", listOf("watergun"), SPECIAL)
            val special = search(engine, specialDefinition, engine.createBattle(specialDefinition))

            val physicalIronDefense = bestValue(physical, "irondefense")
            val physicalSplash = bestValue(physical, "splash")
            val specialIronDefense = bestValue(special, "irondefense")
            val specialSplash = bestValue(special, "splash")

            assertTrue(physicalIronDefense > physicalSplash,
                "Iron Defense must preserve more material across two native turns against a physical move")
            assertEquals(specialSplash, specialIronDefense, 1e-12,
                "Iron Defense must not receive a direct setup price when the only incoming move is special")

            val context = BattleDecisionContext(
                requestId = UUID.randomUUID(),
                state = physical.tree.root.state,
                candidates = physical.candidates,
                deadlineEpochMillis = 5_000L,
            )
            val sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("physical-defense", 0),
                    probability = 1.0,
                    definition = physical.definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, physical.frame),
                    publicContext = context,
                )),
                publicTurn = context.state.turn,
                lastObservedEventSequence = null,
            )
            val runner = NativeProductSearchRunner(
                nanoTime = { 5_000_000L },
                lease = { _, action -> action(engine) },
            )
            val evaluator = NativeInitialProductDecisionEvaluator(
                planWorlds = { _, _ -> error("A retained native root must not be replanned") },
                searchWorlds = NativeInformationSetSearch(lease = { _, action -> action(engine) })::search,
                reconcileSession = { supplied, _, _ ->
                    NativeProductSessionReconciliation(
                        NativeProductSessionReconcileStatus.AVAILABLE,
                        supplied,
                    )
                },
                nowEpochMillis = { 1_000L },
                nanoTime = { 5_000_000L },
                leafEvaluator = { state, _, _, _ -> material(state) },
            )
            val evaluation = evaluator.evaluate(
                context = context,
                profile = BattleTrainerProfile.balanced(3),
                tuning = LocalDecisionTuning.CURRENT,
                budget = LocalLookaheadBudget(timeMillis = 2_000L, nodeLimit = 1_024, chanceBranchesPerMove = 1),
                sessionState = sessionState,
            )
            assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status)
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            // Only Advanced and Boss see two turns (STANDARD searches one since 36cb2641).
        assertEquals(2, evaluation.depthCompleted)
            val ranks = evaluation.ranked
            assertEquals("irondefense", ranks.first().outcome.candidate.moveId)
            val brain = LocalTacticalBrain(
                actionSelector = LocalActionSelector { ranked, seed, _ ->
                    assertEquals(ranks, ranked)
                    LocalActionSelection(ranked.first(), seed, ranked.size, 1.0)
                },
                nativeInitialDecision = { supplied, _, _, _, previous, _ ->
                    assertEquals(context.candidates.map { it.actionId }, supplied.candidates.map { it.actionId })
                    assertEquals(null, previous)
                    evaluation
                },
            )
            val brainSession = brain.openSession(BattleBrainOpenContext(
                battleId = context.state.battleId,
                format = BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(3),
            ))

            val decision = brain.decide(brainSession, context).toCompletableFuture().get()

            assertEquals("irondefense", physical.candidates.single { it.actionId == decision.actionId }.moveId)
            assertTrue("native_showdown_initial" in decision.tags)
        }
    }

    @Test
    fun `Iron Defense at maximum Defense has zero additional native value`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle("Machop", listOf("tackle", "splash"), MAXED)
            var frame = engine.createBattle(definition)
            repeat(3) {
                val ironDefense = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame).single {
                    it.moveId == "irondefense" && it.mechanic == null
                }
                val opponentSplash = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, frame).single {
                    it.moveId == "splash" && it.mechanic == null
                }
                frame = engine.branch(
                    frame.snapshotJson,
                    NativeShowdownChoiceEncoder.encode(ironDefense, BattleSide.ALLY, frame),
                    NativeShowdownChoiceEncoder.encode(opponentSplash, BattleSide.OPPONENT, frame),
                )
            }
            assertEquals(6, frame.p1Active.single().boosts["def"])

            val maxed = search(engine, definition, frame, maxDepth = 1)

            assertEquals(bestValue(maxed, "splash"), bestValue(maxed, "irondefense"), 1e-12,
                "A failed +6 Iron Defense must not receive value outside the native result")
        }
    }

    private fun search(
        engine: NativeShowdownBranchEngine,
        definition: NativeBattleDefinition,
        frame: NativeBattleFrame,
        maxDepth: Int = 2,
    ): SearchFixture {
        val tree = NativeShowdownSearchTree(engine, frame, publicTemplate(definition.p2Team.single().species))
        val candidates = tree.actions(tree.root, BattleSide.ALLY)
        assertEquals(setOf("irondefense", "splash"), candidates.mapNotNull { it.moveId }.toSet())
        val attempt = NativeRecursiveSearch(
            tree = tree,
            world = NativeSearchWorldKey("defensive-setup-${definition.seed.last()}", 0),
            evaluate = ::material,
            nodeLimit = 128,
        ).evaluateProduct(candidates, maxDepth = maxDepth)
        assertTrue(attempt.mapping.complete)
        val search = requireNotNull(attempt.result)
        assertEquals(maxDepth, search.depthCompleted)
        return SearchFixture(definition, frame, tree, candidates, search)
    }

    private fun bestValue(fixture: SearchFixture, moveId: String): Double = fixture.search.rootValues
        .filter { it.action.moveId == moveId }
        .maxOf { it.value }

    private fun battle(
        opponentSpecies: String,
        opponentMoves: List<String>,
        seed: List<Int>,
    ) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = seed,
        p1Team = listOf(NativePokemonSet(
            name = "Defender",
            species = "Mew",
            moves = listOf("irondefense", "splash"),
            ability = "synchronize",
            teraType = "Psychic",
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Attacker",
            species = opponentSpecies,
            moves = opponentMoves,
            ability = if (opponentSpecies == "Machop") "noguard" else "oblivious",
            uuid = OPPONENT.toString(),
        )),
    )

    private fun publicTemplate(opponentSpecies: String) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, "mew", setOf("psychic")),
            pokemon(OPPONENT, BattleSide.OPPONENT, opponentSpecies.lowercase(), setOf("normal")),
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

    private data class SearchFixture(
        val definition: NativeBattleDefinition,
        val frame: NativeBattleFrame,
        val tree: NativeShowdownSearchTree,
        val candidates: List<jbro.cobblemon.mcc.internal.ai.BattleActionCandidate>,
        val search: jbro.cobblemon.mcc.betterai.search.NativeRecursiveSearchResult,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000401")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000402")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000403")
        val PHYSICAL = listOf(151, 157, 163, 167)
        val SPECIAL = listOf(173, 179, 181, 191)
        val MAXED = listOf(193, 197, 199, 211)
    }
}
