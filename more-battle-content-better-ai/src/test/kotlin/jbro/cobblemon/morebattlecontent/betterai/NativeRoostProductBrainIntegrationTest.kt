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
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelection
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelector
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSearchRunner
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconciliation
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchAggregator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeForcedDamageRoll
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeRoostProductBrainIntegrationTest {
    @Test
    fun `Roost removes Flying only for its execution turn and then restores it`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle()
            val damaged = burnOnce(engine, definition)
            assertEquals("brn", damaged.p1Active.single().status)
            assertTrue(damaged.p1Active.single().hp < damaged.p1Active.single().maxHp)
            assertEquals(listOf("psychic", "flying"), damaged.p1Active.single().types.map(String::lowercase))
            assertEquals(
                setOf("psychic", "flying"),
                NativeBattleStateAdapter.adapt(damaged, publicTemplate()).pokemon
                    .single { it.side == BattleSide.ALLY }.knownTypeIds,
            )

            val roosted = engine.branchWithForcedDamage(
                damaged.snapshotJson,
                encodedMove(damaged, BattleSide.ALLY, "roost"),
                encodedMove(damaged, BattleSide.OPPONENT, "earthquake"),
                listOf(NativeForcedDamageRoll(0, 100)),
            )
            val recovered = engine.branch(
                damaged.snapshotJson,
                encodedMove(damaged, BattleSide.ALLY, "recover"),
                encodedMove(damaged, BattleSide.OPPONENT, "earthquake"),
            )

            assertEquals(1, earthquakeHits(roosted),
                "Earthquake must hit after Roost temporarily removes Flying")
            assertEquals(0, earthquakeHits(recovered),
                "Recover must retain Flying and remain immune to Earthquake")
            assertTrue(roosted.executedDamageRolls.last { it.moveId == "earthquake" }.actualHpLoss > 0)
            assertEquals(
                listOf("psychic", "flying"),
                roosted.p1Active.single().types.map(String::lowercase),
                "The retained end-of-turn native state must restore Flying",
            )

            val restored = engine.branch(
                roosted.snapshotJson,
                encodedMove(roosted, BattleSide.ALLY, "splash"),
                encodedMove(roosted, BattleSide.OPPONENT, "earthquake"),
            )
            assertEquals(
                0,
                earthquakeHits(restored),
                "Earthquake must be immune again on the turn after Roost",
            )
            assertEquals(listOf("psychic", "flying"), restored.p1Active.single().types.map(String::lowercase))
        }
    }

    @Test
    fun `temporary Roost vulnerability makes Recover drive the product Brain`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle()
            val frame = burnOnce(engine, definition)
            val tree = NativeShowdownSearchTree(engine, frame, publicTemplate())
            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            assertEquals(setOf("roost", "recover", "splash"), candidates.mapNotNull { it.moveId }.toSet())

            val context = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("native-roost-product".toByteArray()),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = 5_000L,
            )
            val rootIssues = NativeBattleRootValidator.validate(definition, frame, context.state)
            assertTrue(rootIssues.isEmpty(), "The post-burn Roost root must remain product-valid: $rootIssues")
            val sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("roost", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, frame),
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
                searchWorlds = NativeProductWorldSearchAggregator(runner::run)::search,
                reconcileSession = { supplied, _, _ ->
                    NativeProductSessionReconciliation(
                        NativeProductSessionReconcileStatus.AVAILABLE,
                        supplied,
                    )
                },
                nowEpochMillis = { 1_000L },
                nanoTime = { 5_000_000L },
            )
            val evaluation = evaluator.evaluate(
                context = context,
                profile = BattleTrainerProfile.balanced(1),
                tuning = LocalDecisionTuning.CURRENT,
                budget = LocalLookaheadBudget(timeMillis = 2_000L, nodeLimit = 96, chanceBranchesPerMove = 1),
                sessionState = sessionState,
            )

            assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status,
                "Native Roost product evaluation failed: $evaluation")
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(1, evaluation.depthCompleted)
            val bestValueByMove = evaluation.ranked.groupBy { it.outcome.candidate.moveId }
                .mapValues { (_, ranks) -> ranks.maxOf { it.comparisonValue } }
            assertTrue(
                bestValueByMove.getValue("recover") > bestValueByMove.getValue("roost"),
                "Recover must outrank Roost because only Roost exposes Lugia to native Earthquake damage",
            )
            assertTrue(bestValueByMove.getValue("recover") > bestValueByMove.getValue("splash"))
            assertEquals("recover", evaluation.ranked.first().outcome.candidate.moveId)

            val brain = LocalTacticalBrain(
                actionSelector = LocalActionSelector { ranked, seed, _ ->
                    assertEquals(evaluation.ranked, ranked)
                    LocalActionSelection(ranked.first(), seed, ranked.size, 1.0)
                },
                nativeInitialDecision = { supplied, _, _, _, previous ->
                    assertEquals(context.candidates.map { it.actionId }, supplied.candidates.map { it.actionId })
                    assertEquals(null, previous)
                    evaluation
                },
            )
            val brainSession = brain.openSession(BattleBrainOpenContext(
                battleId = context.state.battleId,
                format = BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(1),
            ))
            val decision = brain.decide(brainSession, context).toCompletableFuture().get()

            assertEquals("recover", candidates.single { it.actionId == decision.actionId }.moveId)
            assertTrue("native_showdown_initial" in decision.tags)
            assertTrue("native_search_completed" in decision.tags)
        }
    }

    private fun burnOnce(
        engine: NativeShowdownBranchEngine,
        definition: NativeBattleDefinition,
    ): NativeBattleFrame {
        val root = engine.createBattle(definition)
        val activated = engine.branch(
            root.snapshotJson,
            encodedMove(root, BattleSide.ALLY, "splash"),
            encodedMove(root, BattleSide.OPPONENT, "earthquake"),
        )
        assertEquals("brn", activated.p1Active.single().status,
            "Flame Orb activates after the first residual-damage phase")
        val damaged = engine.branch(
            activated.snapshotJson,
            encodedMove(activated, BattleSide.ALLY, "splash"),
            encodedMove(activated, BattleSide.OPPONENT, "earthquake"),
        )
        assertEquals(0, earthquakeHits(damaged), "Flying Lugia must be immune before Roost")
        return damaged
    }

    private fun earthquakeHits(frame: NativeBattleFrame): Int =
        frame.executedDamageRolls.count { it.moveId == "earthquake" }

    private fun encodedMove(frame: NativeBattleFrame, side: BattleSide, moveId: String): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame).single {
            it.moveId == moveId && it.mechanic == null
        }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun battle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(383, 389, 397, 401),
        p1Team = listOf(NativePokemonSet(
            name = "Roost User",
            species = "Lugia",
            moves = listOf("roost", "recover", "splash"),
            ability = "pressure",
            item = "flameorb",
            nature = "Timid",
            evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 4),
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Ground Attacker",
            species = "Snorlax",
            moves = listOf("earthquake"),
            ability = "immunity",
            nature = "Adamant",
            evs = mapOf("hp" to 252, "atk" to 252, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 0),
            uuid = OPPONENT.toString(),
        )),
    )

    private fun publicTemplate() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, "lugia", setOf("psychic", "flying")),
            pokemon(OPPONENT, BattleSide.OPPONENT, "snorlax", setOf("normal")),
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
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000001201")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000001202")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000001203")
    }
}
