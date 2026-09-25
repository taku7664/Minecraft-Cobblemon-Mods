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

class NativeDigEarthquakeProductBrainIntegrationTest {
    @Test
    fun `Earthquake hits an underground Dig target for double native damage`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle()
            val root = engine.createBattle(definition)
            val underground = engine.branch(
                root.snapshotJson,
                encodedMove(root, BattleSide.ALLY, "splash"),
                encodedMove(root, BattleSide.OPPONENT, "dig"),
            )
            val ordinary = engine.branch(
                root.snapshotJson,
                encodedMove(root, BattleSide.ALLY, "splash"),
                encodedMove(root, BattleSide.OPPONENT, "splash"),
            )

            assertTrue("twoturnmove" in underground.p2Active.single().volatiles)
            assertTrue(
                "twoturnmove" in NativeBattleStateAdapter.adapt(underground, publicTemplate()).pokemon
                    .single { it.side == BattleSide.OPPONENT }.knownVolatileEffectIds,
                "The underground condition must survive native state adaptation",
            )
            assertTrue(underground.p1Active.single().stats.getValue("spe") >
                underground.p2Active.single().stats.getValue("spe"),
                "Earthquake must execute before Dig emerges on the second turn")

            val ordinaryHit = engine.branchWithForcedDamage(
                ordinary.snapshotJson,
                encodedMove(ordinary, BattleSide.ALLY, "earthquake"),
                encodedMove(ordinary, BattleSide.OPPONENT, "splash"),
                listOf(NativeForcedDamageRoll(damageCallIndex = 0, percent = 100)),
            )
            val undergroundHit = engine.branchWithForcedDamage(
                underground.snapshotJson,
                encodedMove(underground, BattleSide.ALLY, "earthquake"),
                encodedMove(underground, BattleSide.OPPONENT, "dig"),
                listOf(
                    NativeForcedDamageRoll(damageCallIndex = 0, percent = 100),
                    NativeForcedDamageRoll(damageCallIndex = 1, percent = 100),
                ),
            )
            val ordinaryDamage = ordinary.p2Active.single().hp - ordinaryHit.p2Team.single().hp
            val undergroundDamage = underground.p2Active.single().hp - undergroundHit.p2Team.single().hp

            assertTrue(ordinaryDamage > 0)
            assertTrue(undergroundHit.p2Team.single().hp > 0,
                "The doubled probe must remain uncapped by a knockout")
            assertTrue(
                kotlin.math.abs(undergroundDamage - ordinaryDamage * 2) <= 1,
                "Showdown's underground Earthquake modifier must stay within one HP of double native damage",
            )
        }
    }

    @Test
    fun `underground native state makes Earthquake drive the product Brain`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle()
            val root = engine.createBattle(definition)
            val underground = engine.branch(
                root.snapshotJson,
                encodedMove(root, BattleSide.ALLY, "splash"),
                encodedMove(root, BattleSide.OPPONENT, "dig"),
            )
            val tree = NativeShowdownSearchTree(engine, underground, publicTemplate())
            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            assertTrue(candidates.any { it.moveId == "earthquake" })
            assertTrue(candidates.any { it.moveId == "splash" })

            val context = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("native-dig-earthquake-product".toByteArray()),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = 5_000L,
            )
            val rootIssues = NativeBattleRootValidator.validate(definition, underground, context.state)
            assertTrue(rootIssues.isEmpty(),
                "The retained underground root must agree with its definition and public state: $rootIssues")
            val sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("dig-earthquake", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, underground),
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
                budget = LocalLookaheadBudget(timeMillis = 2_000L, nodeLimit = 64, chanceBranchesPerMove = 1),
                sessionState = sessionState,
            )

            assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status,
                "Native Dig product evaluation failed: $evaluation")
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(1, evaluation.depthCompleted)
            val bestValueByMove = evaluation.ranked.groupBy { it.outcome.candidate.moveId }
                .mapValues { (_, ranks) -> ranks.maxOf { it.comparisonValue } }
            assertTrue(
                bestValueByMove.getValue("earthquake") > bestValueByMove.getValue("splash"),
                "Earthquake must gain product value from the real underground hit",
            )
            assertEquals("earthquake", evaluation.ranked.first().outcome.candidate.moveId)

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

            assertEquals("earthquake", candidates.single { it.actionId == decision.actionId }.moveId)
            assertTrue("native_showdown_initial" in decision.tags)
            assertTrue("native_search_completed" in decision.tags)
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
        seed = listOf(281, 283, 293, 307),
        p1Team = listOf(NativePokemonSet(
            name = "Earthquake User",
            species = "Garchomp",
            moves = listOf("earthquake", "splash"),
            ability = "sandveil",
            nature = "Jolly",
            evs = mapOf("hp" to 0, "atk" to 0, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 252),
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Underground Target",
            species = "Avalugg",
            moves = listOf("dig", "splash"),
            ability = "owntempo",
            nature = "Impish",
            evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 0),
            uuid = OPPONENT.toString(),
        )),
    )

    private fun publicTemplate() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, "garchomp", setOf("dragon", "ground")),
            pokemon(OPPONENT, BattleSide.OPPONENT, "avalugg", setOf("ice")),
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
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000801")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000802")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000803")
    }
}
