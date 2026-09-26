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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeGrassyTerrainProductBrainIntegrationTest {
    @Test
    fun `Grassy Terrain halves Earthquake only for a grounded target`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val ordinary = engine.createBattle(battle(
                opponentAbility = "overgrow",
                allyMoves = listOf("earthquake", "stompingtantrum"),
            ))
            val grassy = engine.createBattle(battle(
                opponentAbility = "grassysurge",
                allyMoves = listOf("earthquake", "stompingtantrum"),
            ))
            assertNull(ordinary.field.terrain)
            assertEquals("grassyterrain", grassy.field.terrain?.id)
            assertEquals(
                "grassyterrain",
                NativeBattleStateAdapter.adapt(grassy, publicTemplate()).field.terrain?.effectId,
                "The active terrain must survive native state adaptation",
            )

            val ordinaryHit = engine.branchWithForcedDamage(
                ordinary.snapshotJson,
                encodedMove(ordinary, BattleSide.ALLY, "earthquake"),
                encodedMove(ordinary, BattleSide.OPPONENT, "splash"),
                listOf(NativeForcedDamageRoll(0, 100)),
            )
            val grassyHit = engine.branchWithForcedDamage(
                grassy.snapshotJson,
                encodedMove(grassy, BattleSide.ALLY, "earthquake"),
                encodedMove(grassy, BattleSide.OPPONENT, "splash"),
                listOf(NativeForcedDamageRoll(0, 100)),
            )
            val ordinaryDamage = earthquakeDamage(ordinaryHit)
            val grassyDamage = earthquakeDamage(grassyHit)
            assertTrue(ordinaryDamage > 0 && grassyDamage > 0)
            assertTrue(ordinaryHit.p2Team.single().hp > 0 && grassyHit.p2Team.single().hp > 0,
                "The grounded damage comparison must not be truncated by a knockout")
            assertTrue(
                kotlin.math.abs(ordinaryDamage - grassyDamage * 2) <= 1,
                "Grounded Earthquake damage must stay within one HP of the native Grassy Terrain half",
            )

            val ordinaryStomping = engine.branchWithForcedDamage(
                ordinary.snapshotJson,
                encodedMove(ordinary, BattleSide.ALLY, "stompingtantrum"),
                encodedMove(ordinary, BattleSide.OPPONENT, "splash"),
                listOf(NativeForcedDamageRoll(0, 100)),
            )
            val grassyStomping = engine.branchWithForcedDamage(
                grassy.snapshotJson,
                encodedMove(grassy, BattleSide.ALLY, "stompingtantrum"),
                encodedMove(grassy, BattleSide.OPPONENT, "splash"),
                listOf(NativeForcedDamageRoll(0, 100)),
            )
            val ordinaryStompingDamage = moveDamage(ordinaryStomping, "stompingtantrum")
            val grassyStompingDamage = moveDamage(grassyStomping, "stompingtantrum")
            assertTrue(ordinaryDamage > ordinaryStompingDamage,
                "Outside terrain, base-power 100 Earthquake must exceed base-power 75 Stomping Tantrum")
            assertTrue(grassyStompingDamage > grassyDamage,
                "In terrain, unhalved Stomping Tantrum must exceed halved Earthquake")
            assertEquals(
                ordinaryStompingDamage,
                grassyStompingDamage,
                "Grassy Terrain must not halve Stomping Tantrum",
            )

            val ordinaryUnderground = enterDig(engine, battle("overgrow", opponentMoves = listOf("dig", "splash")))
            val grassyUnderground = enterDig(engine, battle("grassysurge", opponentMoves = listOf("dig", "splash")))
            assertTrue("twoturnmove" in ordinaryUnderground.p2Active.single().volatiles)
            assertTrue("twoturnmove" in grassyUnderground.p2Active.single().volatiles)
            assertEquals("grassyterrain", grassyUnderground.field.terrain?.id)

            val ordinaryUndergroundHit = undergroundEarthquake(engine, ordinaryUnderground)
            val grassyUndergroundHit = undergroundEarthquake(engine, grassyUnderground)
            val ordinaryUndergroundDamage = earthquakeDamage(ordinaryUndergroundHit)
            val grassyUndergroundDamage = earthquakeDamage(grassyUndergroundHit)
            assertTrue(
                ordinaryUndergroundHit.p2Team.single().hp > 0 && grassyUndergroundHit.p2Team.single().hp > 0,
                "The underground damage comparison must not be truncated by a knockout",
            )
            assertEquals(
                ordinaryUndergroundDamage,
                grassyUndergroundDamage,
                "A Dig target is not grounded, so Grassy Terrain must not halve its native Earthquake damage",
            )
        }
    }

    @Test
    fun `Grassy Terrain makes an unhalved Ground move drive the product Brain`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle(
                opponentAbility = "grassysurge",
                allyMoves = listOf("earthquake", "stompingtantrum"),
            )
            val frame = engine.createBattle(definition)
            val tree = NativeShowdownSearchTree(engine, frame, publicTemplate())
            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            assertEquals(setOf("earthquake", "stompingtantrum"), candidates.mapNotNull { it.moveId }.toSet())

            val context = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("native-grassy-terrain-product".toByteArray()),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = 5_000L,
            )
            val rootIssues = NativeBattleRootValidator.validate(definition, frame, context.state)
            assertTrue(rootIssues.isEmpty(), "The Grassy Terrain root must remain product-valid: $rootIssues")
            val sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("grassy-terrain", 0),
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
                "Native Grassy Terrain product evaluation failed: $evaluation")
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(1, evaluation.depthCompleted)
            val bestValueByMove = evaluation.ranked.groupBy { it.outcome.candidate.moveId }
                .mapValues { (_, ranks) -> ranks.maxOf { it.comparisonValue } }
            assertTrue(
                bestValueByMove.getValue("stompingtantrum") > bestValueByMove.getValue("earthquake"),
                "The unhalved Ground move must outrank native Grassy Terrain-halved Earthquake",
            )
            assertEquals("stompingtantrum", evaluation.ranked.first().outcome.candidate.moveId)

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

            assertEquals("stompingtantrum", candidates.single { it.actionId == decision.actionId }.moveId)
            assertTrue("native_showdown_initial" in decision.tags)
            assertTrue("native_search_completed" in decision.tags)
        }
    }

    private fun enterDig(
        engine: NativeShowdownBranchEngine,
        definition: NativeBattleDefinition,
    ): NativeBattleFrame {
        val root = engine.createBattle(definition)
        return engine.branch(
            root.snapshotJson,
            encodedMove(root, BattleSide.ALLY, "splash"),
            encodedMove(root, BattleSide.OPPONENT, "dig"),
        )
    }

    private fun undergroundEarthquake(
        engine: NativeShowdownBranchEngine,
        underground: NativeBattleFrame,
    ) = engine.branchWithForcedDamage(
        underground.snapshotJson,
        encodedMove(underground, BattleSide.ALLY, "earthquake"),
        encodedMove(underground, BattleSide.OPPONENT, "dig"),
        listOf(NativeForcedDamageRoll(0, 100), NativeForcedDamageRoll(1, 100)),
    )

    private fun earthquakeDamage(frame: NativeBattleFrame): Int = moveDamage(frame, "earthquake")

    private fun moveDamage(frame: NativeBattleFrame, moveId: String): Int = frame.executedDamageRolls
        .last { it.moveId == moveId }
        .actualHpLoss

    private fun encodedMove(frame: NativeBattleFrame, side: BattleSide, moveId: String): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame).single {
            it.moveId == moveId && it.mechanic == null
        }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun battle(
        opponentAbility: String,
        allyMoves: List<String> = listOf("earthquake", "splash"),
        opponentMoves: List<String> = listOf("splash"),
    ) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(359, 367, 373, 379),
        p1Team = listOf(NativePokemonSet(
            name = "Ground Attacker",
            species = "Garchomp",
            moves = allyMoves,
            ability = "sandveil",
            nature = "Jolly",
            evs = mapOf("hp" to 0, "atk" to 0, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 252),
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Terrain Target",
            species = "Rillaboom",
            moves = opponentMoves,
            ability = opponentAbility,
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
            pokemon(OPPONENT, BattleSide.OPPONENT, "rillaboom", setOf("grass")),
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
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000001101")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000001102")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000001103")
    }
}
