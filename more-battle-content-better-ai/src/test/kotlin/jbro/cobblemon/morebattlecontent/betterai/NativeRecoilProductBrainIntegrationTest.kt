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
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleOpeningState
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonOpeningState
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeRecoilProductBrainIntegrationTest {
    @Test
    fun `damage recoil self knockout lowers native product value and changes the Brain choice`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val ordinary = engine.createBattle(battle())
            val allyMaxHp = ordinary.p1Active.single().maxHp
            val opponentMaxHp = ordinary.p2Active.single().maxHp
            val thirtyHpRoot = engine.createBattle(battle(
                allyHp = allyMaxHp,
                allyMaxHp = allyMaxHp,
                opponentHp = 30,
                opponentMaxHp = opponentMaxHp,
            ))
            val sixtyHpRoot = engine.createBattle(battle(
                allyHp = allyMaxHp,
                allyMaxHp = allyMaxHp,
                opponentHp = 60,
                opponentMaxHp = opponentMaxHp,
            ))
            val thirtyHpResult = engine.branch(
                thirtyHpRoot.snapshotJson,
                encodedMove(thirtyHpRoot, BattleSide.ALLY, "doubleedge"),
                encodedMove(thirtyHpRoot, BattleSide.OPPONENT, "splash"),
            )
            val sixtyHpResult = engine.branch(
                sixtyHpRoot.snapshotJson,
                encodedMove(sixtyHpRoot, BattleSide.ALLY, "doubleedge"),
                encodedMove(sixtyHpRoot, BattleSide.OPPONENT, "splash"),
            )
            assertDamageAndRecoil(thirtyHpRoot, thirtyHpResult, expectedDamage = 30, expectedRecoil = 10)
            assertDamageAndRecoil(sixtyHpRoot, sixtyHpResult, expectedDamage = 60, expectedRecoil = 20)

            val definition = battle(
                allyHp = 1,
                allyMaxHp = allyMaxHp,
                opponentHp = 1,
                opponentMaxHp = opponentMaxHp,
            )
            val prepared = engine.createBattle(definition)
            assertEquals(1, prepared.p1Active.single().hp,
                "The public opening state must expose the minimum one-HP recoil boundary")
            assertEquals(1, prepared.p2Active.single().hp,
                "The comparison target must begin at exactly one public HP")

            val doubleEdgeResult = engine.branch(
                prepared.snapshotJson,
                encodedMove(prepared, BattleSide.ALLY, "doubleedge"),
                encodedMove(prepared, BattleSide.OPPONENT, "splash"),
            )
            val closeCombatResult = engine.branch(
                prepared.snapshotJson,
                encodedMove(prepared, BattleSide.ALLY, "closecombat"),
                encodedMove(prepared, BattleSide.OPPONENT, "splash"),
            )
            assertEquals(0, doubleEdgeResult.p2Team.single().hp)
            assertEquals(0, closeCombatResult.p2Team.single().hp)
            assertEquals(0, doubleEdgeResult.p1Team.single().hp,
                "One point of actual damage must still cause Showdown's minimum one-HP recoil")
            assertEquals(1, closeCombatResult.p1Team.single().hp)
            assertTrue(
                NativeBattleStateAdapter.adapt(doubleEdgeResult, publicTemplate()).pokemon
                    .single { it.side == BattleSide.ALLY }.fainted,
            )
            assertFalse(
                NativeBattleStateAdapter.adapt(closeCombatResult, publicTemplate()).pokemon
                    .single { it.side == BattleSide.ALLY }.fainted,
            )

            val tree = NativeShowdownSearchTree(engine, prepared, publicTemplate())
            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            assertTrue(candidates.any { it.moveId == "doubleedge" })
            assertTrue(candidates.any { it.moveId == "closecombat" })
            val context = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("native-recoil-product".toByteArray()),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = 5_000L,
            )
            val sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("recoil-self-ko", 0),
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
            val rootIssues = NativeBattleRootValidator.validate(definition, prepared, context.state)
            assertTrue(rootIssues.isEmpty(),
                "The retained recoil root must agree with its native definition and public state: $rootIssues")
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
            assertEquals(
                NativeInitialProductDecisionStatus.AVAILABLE,
                evaluation.status,
                "Native recoil product evaluation failed: $evaluation",
            )
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(1, evaluation.depthCompleted)
            val bestValueByMove = evaluation.ranked.groupBy { it.outcome.candidate.moveId }
                .mapValues { (_, ranks) -> ranks.maxOf { it.comparisonValue } }
            assertTrue(
                bestValueByMove.getValue("closecombat") > bestValueByMove.getValue("doubleedge"),
                "The final native state must value a surviving attacker above a recoil self-KO",
            )
            assertEquals("closecombat", evaluation.ranked.first().outcome.candidate.moveId)

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

            assertEquals("closecombat", candidates.single { it.actionId == decision.actionId }.moveId)
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

    private fun assertDamageAndRecoil(
        before: NativeBattleFrame,
        after: NativeBattleFrame,
        expectedDamage: Int,
        expectedRecoil: Int,
    ) {
        assertEquals(expectedDamage, before.p2Team.single().hp - after.p2Team.single().hp)
        assertEquals(expectedRecoil, before.p1Team.single().hp - after.p1Team.single().hp)
        assertTrue(after.p1Team.single().hp > 0, "The proportional recoil probe must not be capped by self-KO")
    }

    private fun battle(
        allyHp: Int? = null,
        allyMaxHp: Int? = null,
        opponentHp: Int? = null,
        opponentMaxHp: Int? = null,
    ) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(223, 227, 229, 233),
        p1Team = listOf(NativePokemonSet(
            name = "Recoil Boundary",
            species = "Staraptor",
            moves = listOf("doubleedge", "closecombat"),
            ability = "intimidate",
            nature = "Adamant",
            evs = mapOf("hp" to 0, "atk" to 252, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 252),
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(NativePokemonSet(
            name = "One HP Target",
            species = "Wailord",
            moves = listOf("splash"),
            ability = "oblivious",
            uuid = OPPONENT.toString(),
        )),
        openingState = when {
            allyHp == null && allyMaxHp == null && opponentHp == null && opponentMaxHp == null -> null
            else -> NativeBattleOpeningState(listOf(
                NativePokemonOpeningState(
                    uuid = ALLY.toString(),
                    hp = requireNotNull(allyHp),
                    maxHp = requireNotNull(allyMaxHp),
                    ability = "intimidate",
                ),
                NativePokemonOpeningState(
                    uuid = OPPONENT.toString(),
                    hp = requireNotNull(opponentHp),
                    maxHp = requireNotNull(opponentMaxHp),
                    ability = "oblivious",
                ),
            ))
        },
    )

    private fun publicTemplate() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, "staraptor", setOf("normal", "flying")),
            pokemon(OPPONENT, BattleSide.OPPONENT, "wailord", setOf("water")),
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
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000501")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000502")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000503")
    }
}
