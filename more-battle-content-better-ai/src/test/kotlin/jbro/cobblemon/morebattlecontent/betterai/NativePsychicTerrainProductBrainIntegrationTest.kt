package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
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
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativePsychicTerrainProductBrainIntegrationTest {
    @Test
    fun `Psychic Terrain blocks priority only against a grounded opposing target`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val groundedDefinition = battle(opponentItem = null)
            val grounded = engine.createBattle(groundedDefinition)
            assertEquals("psychicterrain", grounded.field.terrain?.id)
            assertEquals(
                "psychicterrain",
                NativeBattleStateAdapter.adapt(grounded, publicTemplate()).field.terrain?.effectId,
                "The active terrain must survive native state adaptation",
            )

            val blocked = engine.branch(
                grounded.snapshotJson,
                encodedMove(grounded, BattleSide.ALLY, "bulletpunch"),
                encodedMove(grounded, BattleSide.OPPONENT, "splash"),
            )
            val ordinary = engine.branch(
                grounded.snapshotJson,
                encodedMove(grounded, BattleSide.ALLY, "tackle"),
                encodedMove(grounded, BattleSide.OPPONENT, "splash"),
            )

            assertEquals(
                grounded.p2Active.single().hp,
                blocked.p2Team.single().hp,
                "A positive-priority attack must fail against the grounded opposing target",
            )
            assertTrue(
                ordinary.p2Team.single().hp < grounded.p2Active.single().hp,
                "A priority-zero attack must still hit the same grounded target",
            )
            assertTrue(
                blocked.executedMoveOrder.any { it.moveId == "bulletpunch" },
                "The blocked move must have been attempted rather than removed from the request",
            )

            val airborneDefinition = battle(opponentItem = "airballoon")
            val airborne = engine.createBattle(airborneDefinition)
            assertEquals("psychicterrain", airborne.field.terrain?.id)
            val priorityHit = engine.branch(
                airborne.snapshotJson,
                encodedMove(airborne, BattleSide.ALLY, "bulletpunch"),
                encodedMove(airborne, BattleSide.OPPONENT, "splash"),
            )
            assertTrue(
                priorityHit.p2Team.single().hp < airborne.p2Active.single().hp,
                "Psychic Terrain must not protect an opposing target held airborne by Air Balloon",
            )

            val selfTargetDefinition = selfTargetBattle()
            val selfTargetRoot = engine.createBattle(selfTargetDefinition)
            assertEquals("psychicterrain", selfTargetRoot.field.terrain?.id)
            val damaged = engine.branch(
                selfTargetRoot.snapshotJson,
                encodedMove(selfTargetRoot, BattleSide.ALLY, "splash"),
                encodedMove(selfTargetRoot, BattleSide.OPPONENT, "fairywind"),
            )
            assertTrue(damaged.p1Active.single().hp in 1 until damaged.p1Active.single().maxHp)
            val recovered = engine.branch(
                damaged.snapshotJson,
                encodedMove(damaged, BattleSide.ALLY, "recover"),
                encodedMove(damaged, BattleSide.OPPONENT, "splash"),
            )
            assertTrue(
                recovered.p1Team.single().hp > damaged.p1Active.single().hp,
                "Psychic Terrain must not block a Prankster-boosted positive-priority move targeting its user",
            )
            assertEquals(
                listOf("recover", "splash"),
                recovered.executedMoveOrder.takeLast(2).map { it.moveId },
                "Prankster Recover must retain positive priority while remaining legal under Psychic Terrain",
            )
        }
    }

    @Test
    fun `Psychic Terrain failure makes the product Brain prefer a legal damaging move`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle(opponentItem = null)
            val frame = engine.createBattle(definition)
            val tree = NativeShowdownSearchTree(engine, frame, publicTemplate())
            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            assertEquals(setOf("bulletpunch", "tackle"), candidates.mapNotNull { it.moveId }.toSet())

            val context = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("native-psychic-terrain-product".toByteArray()),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = 5_000L,
            )
            val rootIssues = NativeBattleRootValidator.validate(definition, frame, context.state)
            assertTrue(rootIssues.isEmpty(), "The Psychic Terrain root must remain product-valid: $rootIssues")
            val sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("psychic-terrain", 0),
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
                budget = LocalLookaheadBudget(timeMillis = 2_000L, nodeLimit = 64, chanceBranchesPerMove = 1),
                sessionState = sessionState,
            )

            assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status,
                "Native Psychic Terrain product evaluation failed: $evaluation")
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(1, evaluation.depthCompleted)
            val bestValueByMove = evaluation.ranked.groupBy { it.outcome.candidate.moveId }
                .mapValues { (_, ranks) -> ranks.maxOf { it.comparisonValue } }
            assertTrue(
                bestValueByMove.getValue("tackle") > bestValueByMove.getValue("bulletpunch"),
                "The real Psychic Terrain failure must make Tackle more valuable than blocked Bullet Punch",
            )
            assertEquals("tackle", evaluation.ranked.first().outcome.candidate.moveId)

            val brain = productBrain(context, candidates, evaluation)
            val brainSession = brain.openSession(BattleBrainOpenContext(
                battleId = context.state.battleId,
                format = BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(1),
            ))
            val decision = brain.decide(brainSession, context).toCompletableFuture().get()

            assertEquals("tackle", candidates.single { it.actionId == decision.actionId }.moveId)
            assertTrue("native_showdown_initial" in decision.tags)
            assertTrue("native_search_completed" in decision.tags)
        }
    }

    private fun productBrain(
        context: BattleDecisionContext,
        candidates: List<BattleActionCandidate>,
        evaluation: jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluation,
    ) = LocalTacticalBrain(
        actionSelector = LocalActionSelector { ranked, seed, _ ->
            assertEquals(evaluation.ranked, ranked)
            LocalActionSelection(ranked.first(), seed, ranked.size, 1.0)
        },
        nativeInitialDecision = { supplied, _, _, _, previous ->
            assertEquals(candidates.map { it.actionId }, supplied.candidates.map { it.actionId })
            assertEquals(context.state, supplied.state)
            assertEquals(null, previous)
            evaluation
        },
    )

    private fun encodedMove(frame: NativeBattleFrame, side: BattleSide, moveId: String): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame).single {
            it.moveId == moveId && it.mechanic == null
        }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun battle(opponentItem: String?) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(311, 313, 317, 331),
        p1Team = listOf(NativePokemonSet(
            name = "Priority User",
            species = "Scizor",
            moves = listOf("bulletpunch", "tackle"),
            ability = "technician",
            nature = "Adamant",
            evs = mapOf("hp" to 0, "atk" to 252, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 0),
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Terrain Target",
            species = "Tapu Lele",
            moves = listOf("splash"),
            ability = "psychicsurge",
            item = opponentItem.orEmpty(),
            nature = "Timid",
            evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 252),
            uuid = OPPONENT.toString(),
        )),
    )

    private fun selfTargetBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(337, 347, 349, 353),
        p1Team = listOf(NativePokemonSet(
            name = "Self Target",
            species = "Sableye",
            moves = listOf("recover", "splash"),
            ability = "prankster",
            nature = "Careful",
            evs = mapOf("hp" to 252, "atk" to 0, "def" to 0, "spa" to 0, "spd" to 252, "spe" to 0),
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Terrain Setter",
            species = "Tapu Lele",
            moves = listOf("fairywind", "splash"),
            ability = "psychicsurge",
            nature = "Timid",
            uuid = OPPONENT.toString(),
        )),
    )

    private fun publicTemplate() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, "scizor", setOf("bug", "steel")),
            pokemon(OPPONENT, BattleSide.OPPONENT, "tapulele", setOf("psychic", "fairy")),
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
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000001001")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000001002")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000001003")
    }
}
