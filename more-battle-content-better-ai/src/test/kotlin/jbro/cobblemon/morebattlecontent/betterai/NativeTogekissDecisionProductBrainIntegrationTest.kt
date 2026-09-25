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
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
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

class NativeTogekissDecisionProductBrainIntegrationTest {
    @Test
    fun `boss Togekiss does not mix failed Thunder Wave or high HP Roost against Torterra`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle()
            val root = engine.createBattle(definition)

            val thunderWave = engine.branchWithForcedDamage(
                root.snapshotJson,
                encodedMove(root, BattleSide.ALLY, "thunderwave"),
                encodedMove(root, BattleSide.OPPONENT, "tackle"),
                listOf(NativeForcedDamageRoll(0, 100)),
            )
            assertTrue(thunderWave.p2Active.single().status.isBlank(),
                "Ground-type Torterra must remain unstatused after native Thunder Wave")

            val fullHpRoost = engine.branchWithForcedDamage(
                root.snapshotJson,
                encodedMove(root, BattleSide.ALLY, "roost"),
                encodedMove(root, BattleSide.OPPONENT, "tackle"),
                listOf(NativeForcedDamageRoll(0, 100)),
            )
            assertEquals(thunderWave.p1Active.single().hp, fullHpRoost.p1Active.single().hp,
                "Full-HP Roost must not recover material before the same incoming hit")

            val damaged = engine.branchWithForcedDamage(
                root.snapshotJson,
                encodedMove(root, BattleSide.ALLY, "aurasphere"),
                encodedMove(root, BattleSide.OPPONENT, "tackle"),
                listOf(NativeForcedDamageRoll(0, 100), NativeForcedDamageRoll(1, 100)),
            )
            val hpFraction = damaged.p1Active.single().hp.toDouble() / damaged.p1Active.single().maxHp.toDouble()
            assertTrue(hpFraction in 0.90..<1.0, "Fixture must reproduce high but damaged HP, was $hpFraction")

            val publicState = NativeBattleStateAdapter.adapt(damaged, publicTemplate())
            val tree = NativeShowdownSearchTree(engine, damaged, publicState)
            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            assertEquals(
                setOf("airslash", "aurasphere", "thunderwave", "roost"),
                candidates.mapNotNull { it.moveId }.toSet(),
            )
            val context = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("native-togekiss-ground-counterexample".toByteArray()),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = 5_000L,
            )
            val rootIssues = NativeBattleRootValidator.validate(definition, damaged, context.state)
            assertTrue(rootIssues.isEmpty(), "The retained Togekiss root must be product-valid: $rootIssues")
            val sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("togekiss-ground", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, damaged),
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
                profile = BattleTrainerProfile.boss(),
                tuning = LocalDecisionTuning.CURRENT,
                budget = LocalLookaheadBudget(
                    timeMillis = 2_000L,
                    nodeLimit = 400_000,
                    chanceBranchesPerMove = 1,
                ),
                sessionState = sessionState,
            )

            assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status,
                "Native Togekiss product evaluation failed: $evaluation")
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(3, evaluation.depthCompleted)
            val attacks = setOf("airslash", "aurasphere")
            assertTrue(evaluation.ranked.first().outcome.candidate.moveId in attacks)

            val shippingSelector = LocalWeightedActionSelector()
            var shortlistedMoves: Set<String> = emptySet()
            val brain = LocalTacticalBrain(
                actionSelector = LocalActionSelector { ranked, seed, mixing ->
                    shortlistedMoves = shippingSelector.shortlist(ranked, mixing)
                        .mapNotNull { it.outcome.candidate.moveId }
                        .toSet()
                    shippingSelector.choose(ranked, seed, mixing)
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
                trainerProfile = BattleTrainerProfile.boss(),
            ))

            val decision = brain.decide(brainSession, context).toCompletableFuture().get()

            assertTrue(shortlistedMoves.isNotEmpty())
            assertTrue(shortlistedMoves.all { it in attacks },
                "Failed Thunder Wave and high-HP Roost must not receive shipping probability: $shortlistedMoves")
            assertTrue(candidates.single { it.actionId == decision.actionId }.moveId in attacks)
            assertTrue("native_showdown_initial" in decision.tags)
            assertTrue("native_search_completed" in decision.tags)
        }
    }

    private fun encodedMove(
        frame: jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame,
        side: BattleSide,
        moveId: String,
    ): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame).single {
            it.moveId == moveId && it.mechanic == null
        }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun battle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(409, 419, 421, 431),
        p1Team = listOf(NativePokemonSet(
            name = "Cynthia Togekiss",
            species = "Togekiss",
            moves = listOf("airslash", "aurasphere", "thunderwave", "roost"),
            ability = "serenegrace",
            item = "leftovers",
            nature = "Timid",
            evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 4),
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Ground Target",
            species = "Torterra",
            moves = listOf("tackle"),
            ability = "overgrow",
            nature = "Adamant",
            evs = mapOf("hp" to 252, "atk" to 252, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 4),
            uuid = OPPONENT.toString(),
        )),
    )

    private fun publicTemplate() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, "togekiss", setOf("fairy", "flying")),
            pokemon(OPPONENT, BattleSide.OPPONENT, "torterra", setOf("grass", "ground")),
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
                        val buffer = ByteArray(65_536)
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
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000001301")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000001302")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000001303")
    }
}
