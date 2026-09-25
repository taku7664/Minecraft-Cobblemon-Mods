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
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRankAdapter
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSearchRunner
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconciliation
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchAggregator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeRecursiveSearch
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeMiloticToxicRecoveryTest {
    @Test
    fun `bad poison progresses and late Recover loses to a safe switch`(@TempDir directory: Path) {
        NativeShowdownBranchEngine.open(extractBundledShowdown(directory.resolve("showdown"))).use { engine ->
            var frame = engine.createBattle(battle())
            fun turn(ownMove: String, opposingMove: String): NativeBattleFrame = engine.branch(
                frame.snapshotJson,
                encodedMove(frame, BattleSide.ALLY, ownMove),
                encodedMove(frame, BattleSide.OPPONENT, opposingMove),
            ).also { frame = it }

            turn("mirrorcoat", "toxic")
            assertEquals("tox", frame.p1Active.single().status)
            val consecutiveLosses = (1..3).map {
                val before = frame.p1Active.single().hp
                turn("mirrorcoat", "splash")
                before - frame.p1Active.single().hp
            }
            assertTrue(consecutiveLosses.zipWithNext().all { (before, after) -> after > before },
                "The retained native toxic counter must make each passive tick worse: $consecutiveLosses")

            // Recover delays a late toxic KO but cannot reset the counter; a Poison-type bench can.
            turn("recover", "splash")
            turn("mirrorcoat", "splash")
            turn("recover", "splash")
            turn("mirrorcoat", "splash")
            assertEquals("tox", frame.p1Active.single().status)
            assertTrue(frame.p1Active.single().hp > 0)
            assertTrue(frame.p1Active.single().hp * 4 < frame.p1Active.single().maxHp,
                "The fixture must reach a dangerous late-toxic recovery loop")

            val tree = NativeShowdownSearchTree(engine, frame, publicTemplate())
            val search = NativeRecursiveSearch(
                tree = tree,
                world = NativeSearchWorldKey("milotic-late-toxic", 0),
                evaluate = LocalBoardMaterial::evaluate,
                nodeLimit = 400_000,
            ).evaluate(maxDepth = 3)
            assertEquals(3, search.depthCompleted)
            val recover = search.rootValues.single {
                it.action.moveId == "recover" && it.action.mechanic == null
            }
            val switch = search.rootValues.single { it.action.switchPokemonId == ROSERADE }
            assertTrue(switch.value > recover.value,
                "A safe switch must beat late-toxic Recover: " +
                    search.rootValues.map { (it.action.moveId ?: it.action.switchPokemonId) to it.value })
            val ranked = NativeProductRankAdapter.rank(search.rootValues, LocalBoardMaterial.evaluate(tree.root.state))
            val shortlisted = LocalWeightedActionSelector().shortlist(
                ranked,
                LocalActionMixingContext.balanced(0.5).copy(authoritativeSimulationScores = true),
            ).map { it.outcome.candidate }
            assertTrue(shortlisted.none { it.moveId == "recover" },
                "Recover must not remain in the weighted choice pool: " +
                    ranked.map { (it.outcome.candidate.moveId ?: it.outcome.candidate.switchPokemonId) to it.comparisonValue })

            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            val context = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("native-milotic-toxic-recovery".toByteArray()),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = 5_000L,
            )
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("milotic-late-toxic-product", 0),
                    probability = 1.0,
                    definition = battle(),
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
                planWorlds = { _, _ -> error("The native root must be retained") },
                searchWorlds = NativeProductWorldSearchAggregator(runner::run)::search,
                reconcileSession = { supplied, _, _ -> NativeProductSessionReconciliation(
                    NativeProductSessionReconcileStatus.AVAILABLE, supplied,
                ) },
                nowEpochMillis = { 1_000L },
                nanoTime = { 5_000_000L },
            )
            val evaluation = evaluator.evaluate(
                context, BattleTrainerProfile.boss(), LocalDecisionTuning.CURRENT,
                LocalLookaheadBudget(timeMillis = 2_000L, nodeLimit = 400_000, chanceBranchesPerMove = 1),
                session,
            )
            assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status,
                "Product evaluator must accept the retained toxic native root: $evaluation")
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(3, evaluation.depthCompleted)
            val productSwitch = evaluation.ranked.single {
                it.outcome.candidate.switchPokemonId == ROSERADE
            }
            val productRecover = evaluation.ranked.single {
                it.outcome.candidate.moveId == "recover" && it.outcome.candidate.mechanic == null
            }
            assertTrue(productSwitch.comparisonValue > productRecover.comparisonValue,
                "The product value path must preserve the safe-switch advantage")
            var productShortlist = emptySet<String>()
            val selector = LocalWeightedActionSelector()
            val brain = LocalTacticalBrain(
                actionSelector = LocalActionSelector { productRanks, seed, mixing ->
                    productShortlist = selector.shortlist(productRanks, mixing)
                        .mapNotNull { it.outcome.candidate.moveId }.toSet()
                    selector.choose(productRanks, seed, mixing)
                },
                nativeInitialDecision = { _, _, _, _, _ -> evaluation },
            )
            val brainSession = brain.openSession(BattleBrainOpenContext(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.boss(),
            ))
            val decision = brain.decide(brainSession, context).toCompletableFuture().get()
            assertTrue("recover" !in productShortlist,
                "Product Brain must not sample late-toxic Recover: " +
                    evaluation.ranked.map { (it.outcome.candidate.moveId ?: it.outcome.candidate.switchPokemonId) to it.comparisonValue })
            assertTrue(candidates.single { it.actionId == decision.actionId }.moveId != "recover")
            assertTrue("native_showdown_initial" in decision.tags)
        }
    }

    private fun encodedMove(frame: NativeBattleFrame, side: BattleSide, moveId: String): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame).single {
            it.moveId == moveId && it.mechanic == null
        }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun battle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(439, 443, 449, 457),
        p1Team = listOf(
            NativePokemonSet("Cynthia Milotic", "Milotic",
                listOf("scald", "icebeam", "recover", "mirrorcoat"), "marvelscale", MILOTIC.toString(),
                item = "leftovers", nature = "Bold", teraType = "Water",
                evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 0)),
            NativePokemonSet("Safe bench", "Roserade", listOf("energyball"), "naturalcure",
                ROSERADE.toString()),
        ),
        p2Team = listOf(NativePokemonSet("Toxic source", "Blissey", listOf("toxic", "splash"),
            "naturalcure", BLISSEY.toString())),
    )

    private fun publicTemplate() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(MILOTIC, BattleSide.ALLY, 0, "milotic", setOf("water")),
            pokemon(ROSERADE, BattleSide.ALLY, null, "roserade", setOf("grass", "poison")),
            pokemon(BLISSEY, BattleSide.OPPONENT, 0, "blissey", setOf("normal")),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, species: String, types: Set<String>) =
        BattlePokemonStateView(
            battlePokemonId = id, side = side, activeSlot = slot, speciesId = "cobblemon:$species",
            formId = null, level = 50, hpFraction = 1.0, statusId = null,
            statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null,
            knownHeldItemId = null, fainted = false, knownTypeIds = types,
        )

    private fun extractBundledShowdown(targetRoot: Path): Path {
        Files.createDirectories(targetRoot)
        val resource = requireNotNull(javaClass.getResourceAsStream("/data/cobblemon/showdown.zip"))
        var extracted = 0L
        ZipInputStream(resource).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = EmbeddedShowdownOracle.entryPath(targetRoot, entry.name)
                if (!entry.isDirectory && (entry.name.endsWith(".js") || entry.name.endsWith(".json"))) {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, CREATE_NEW).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extracted += count
                            require(extracted <= 128L * 1024 * 1024)
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
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000001401")
        val MILOTIC: UUID = UUID.fromString("00000000-0000-0000-0000-000000001402")
        val ROSERADE: UUID = UUID.fromString("00000000-0000-0000-0000-000000001403")
        val BLISSEY: UUID = UUID.fromString("00000000-0000-0000-0000-000000001404")
    }
}
