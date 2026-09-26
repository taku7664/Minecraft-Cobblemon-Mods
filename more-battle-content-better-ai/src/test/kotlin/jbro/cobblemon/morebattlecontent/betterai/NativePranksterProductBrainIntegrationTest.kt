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
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluation
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

class NativePranksterProductBrainIntegrationTest {
    @Test
    fun `Prankster changes status priority but fails only against an opposing Dark target`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val pranksterNonDark = resolve(engine, PRANKSTER, "Dragonite", setOf("dragon", "flying"))
            val ordinaryNonDark = resolve(engine, KEEN_EYE, "Dragonite", setOf("dragon", "flying"))

            assertTrue(pranksterNonDark.allySpeed < pranksterNonDark.opponentSpeed)
            assertEquals(listOf("charm", "dragonclaw"), moveOrder(pranksterNonDark.after))
            assertEquals(listOf("dragonclaw", "charm"), moveOrder(ordinaryNonDark.after))
            assertEquals(-2, opponentAttackStage(pranksterNonDark))
            assertEquals(-2, opponentAttackStage(ordinaryNonDark))
            assertTrue(
                allyHpLoss(pranksterNonDark) < allyHpLoss(ordinaryNonDark),
                "Prankster Charm must lower Attack before the faster physical hit instead of after it",
            )

            val pranksterDark = resolve(engine, PRANKSTER, "Hydreigon", setOf("dark", "dragon"))
            val ordinaryDark = resolve(engine, KEEN_EYE, "Hydreigon", setOf("dark", "dragon"))

            assertEquals(listOf("charm", "dragonclaw"), moveOrder(pranksterDark.after))
            assertEquals(0, opponentAttackStage(pranksterDark),
                "The opposing Dark target must be immune only to the Prankster-boosted status move")
            assertTrue(pranksterDark.after.log.any { "|-immune|" in it },
                "The retained Showdown log must contain the public Dark-type immunity result")
            assertEquals(listOf("dragonclaw", "charm"), moveOrder(ordinaryDark.after))
            assertEquals(-2, opponentAttackStage(ordinaryDark),
                "Dark typing is not an ordinary Charm immunity when Prankster is absent")
        }
    }

    @Test
    fun `native Prankster result changes product value and the final Brain choice`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle(PRANKSTER, "Dragonite")
            val template = publicTemplate("dragonite", setOf("dragon", "flying"))
            val product = evaluateProduct(engine, definition, template, "prankster-priority")
            val candidates = product.candidates
            assertTrue(candidates.any { it.moveId == "charm" })
            assertTrue(candidates.any { it.moveId == "splash" })
            val evaluation = product.evaluation
            assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status,
                "Native Prankster product evaluation failed: $evaluation")
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(1, evaluation.depthCompleted)
            val bestValueByMove = evaluation.ranked.groupBy { it.outcome.candidate.moveId }
                .mapValues { (_, ranks) -> ranks.maxOf { it.comparisonValue } }
            assertTrue(
                bestValueByMove.getValue("charm") > bestValueByMove.getValue("splash"),
                "The real priority Attack drop must preserve more HP than doing nothing",
            )
            assertEquals("charm", evaluation.ranked.first().outcome.candidate.moveId)

            val darkProduct = evaluateProduct(
                engine = engine,
                definition = battle(PRANKSTER, "Hydreigon"),
                template = publicTemplate("hydreigon", setOf("dark", "dragon")),
                hypothesisId = "prankster-dark-immunity",
            )
            val darkValueByMove = darkProduct.evaluation.ranked.groupBy { it.outcome.candidate.moveId }
                .mapValues { (_, ranks) -> ranks.maxOf { it.comparisonValue } }
            assertEquals(
                darkValueByMove.getValue("splash"),
                darkValueByMove.getValue("charm"),
                1e-12,
                "A Prankster status move that Showdown rejects against Dark must gain no product value",
            )

            val brain = LocalTacticalBrain(
                actionSelector = LocalActionSelector { ranked, seed, _ ->
                    assertEquals(evaluation.ranked, ranked)
                    LocalActionSelection(ranked.first(), seed, ranked.size, 1.0)
                },
                nativeInitialDecision = { supplied, _, _, _, previous ->
                    assertEquals(product.context.candidates.map { it.actionId }, supplied.candidates.map { it.actionId })
                    assertEquals(null, previous)
                    evaluation
                },
            )
            val brainSession = brain.openSession(BattleBrainOpenContext(
                battleId = product.context.state.battleId,
                format = BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(1),
            ))

            val decision = brain.decide(brainSession, product.context).toCompletableFuture().get()

            assertEquals("charm", candidates.single { it.actionId == decision.actionId }.moveId)
            assertTrue("native_showdown_initial" in decision.tags)
            assertTrue("native_search_completed" in decision.tags)
        }
    }

    private fun evaluateProduct(
        engine: NativeShowdownBranchEngine,
        definition: NativeBattleDefinition,
        template: BattleStateView,
        hypothesisId: String,
    ): ProductFixture {
        val prepared = engine.createBattle(definition)
        val tree = NativeShowdownSearchTree(engine, prepared, template)
        val candidates = tree.actions(tree.root, BattleSide.ALLY)
        val context = BattleDecisionContext(
            requestId = UUID.nameUUIDFromBytes("native-$hypothesisId-product".toByteArray()),
            state = tree.root.state,
            candidates = candidates,
            deadlineEpochMillis = 5_000L,
        )
        val rootIssues = NativeBattleRootValidator.validate(definition, prepared, context.state)
        assertTrue(rootIssues.isEmpty(),
            "The retained Prankster root must agree with its definition and public state: $rootIssues")
        val sessionState = NativeProductSessionState(
            battleId = context.state.battleId,
            format = context.state.format,
            rulesFingerprint = engine.rulesFingerprint,
            worlds = listOf(NativeProductSessionWorld(
                key = NativeSearchWorldKey(hypothesisId, 0),
                probability = 1.0,
                definition = definition,
                rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, prepared),
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
            "Native Prankster product evaluation failed: $evaluation")
        assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
        assertEquals(1, evaluation.depthCompleted)
        return ProductFixture(context, candidates, evaluation)
    }

    private fun resolve(
        engine: NativeShowdownBranchEngine,
        allyAbility: String,
        opponentSpecies: String,
        opponentTypes: Set<String>,
    ): BranchFixture {
        val definition = battle(allyAbility, opponentSpecies)
        val before = engine.createBattle(definition)
        val after = engine.branchWithForcedDamage(
            before.snapshotJson,
            encodedMove(before, BattleSide.ALLY, "charm"),
            encodedMove(before, BattleSide.OPPONENT, "dragonclaw"),
            listOf(NativeForcedDamageRoll(damageCallIndex = 0, percent = 100)),
        )
        return BranchFixture(
            before = before,
            after = after,
            adapted = NativeBattleStateAdapter.adapt(
                after,
                publicTemplate(opponentSpecies.lowercase(), opponentTypes),
            ),
            allySpeed = before.p1Active.single().stats.getValue("spe"),
            opponentSpeed = before.p2Active.single().stats.getValue("spe"),
        )
    }

    private fun encodedMove(frame: NativeBattleFrame, side: BattleSide, moveId: String): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame).single {
            it.moveId == moveId && it.mechanic == null
        }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun moveOrder(frame: NativeBattleFrame): List<String> = frame.executedMoveOrder
        .filter { it.turn == 1 }
        .map { it.moveId }

    private fun allyHpLoss(fixture: BranchFixture): Int =
        fixture.before.p1Team.single().hp - fixture.after.p1Team.single().hp

    private fun opponentAttackStage(fixture: BranchFixture): Int {
        val native = fixture.after.p2Team.single().boosts["atk"] ?: 0
        val adapted = fixture.adapted.pokemon.single { it.side == BattleSide.OPPONENT }
            .statStages["atk"] ?: 0
        assertEquals(native, adapted, "The native Attack stage must survive state adaptation")
        return native
    }

    private fun battle(
        allyAbility: String,
        opponentSpecies: String,
    ) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(239, 241, 251, 257),
        p1Team = listOf(NativePokemonSet(
            name = "Prankster Control",
            species = "Sableye",
            moves = listOf("charm", "splash"),
            ability = allyAbility,
            nature = "Bold",
            evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 0),
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Faster Physical Attacker",
            species = opponentSpecies,
            moves = listOf("dragonclaw"),
            ability = if (opponentSpecies == "Hydreigon") "levitate" else "innerfocus",
            nature = "Jolly",
            evs = mapOf("hp" to 0, "atk" to 252, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 252),
            uuid = OPPONENT.toString(),
        )),
    )

    private fun publicTemplate(opponentSpecies: String, opponentTypes: Set<String>) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, "sableye", setOf("dark", "ghost")),
            pokemon(OPPONENT, BattleSide.OPPONENT, opponentSpecies, opponentTypes),
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

    private data class BranchFixture(
        val before: NativeBattleFrame,
        val after: NativeBattleFrame,
        val adapted: BattleStateView,
        val allySpeed: Int,
        val opponentSpeed: Int,
    )

    private data class ProductFixture(
        val context: BattleDecisionContext,
        val candidates: List<jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate>,
        val evaluation: NativeInitialProductDecisionEvaluation,
    )

    private companion object {
        const val PRANKSTER = "prankster"
        const val KEEN_EYE = "keeneye"
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000601")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000602")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000603")
    }
}
