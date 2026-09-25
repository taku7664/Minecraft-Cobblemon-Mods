package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
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
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveSetRebinding
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeSuckerPunchProductBrainIntegrationTest {
    @Test
    fun `Sucker Punch branches on attack status and switch again every turn`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle()
            val root = engine.createBattle(definition)
            val suckerPunch = encodedMove(root, BattleSide.ALLY, "suckerpunch")
            val tackle = encodedMove(root, BattleSide.OPPONENT, "tackle")
            val swordsDance = encodedMove(root, BattleSide.OPPONENT, "swordsdance")
            val switch = encodedSwitch(root, BattleSide.OPPONENT, OPPONENT_BENCH)
            val rootMewHp = pokemon(root, OPPONENT_ACTIVE).hp

            val intoAttack = engine.branch(root.snapshotJson, suckerPunch, tackle)
            val intoStatus = engine.branch(root.snapshotJson, suckerPunch, swordsDance)
            val intoSwitch = engine.branch(root.snapshotJson, suckerPunch, switch)
            val rootClefableHp = pokemon(root, OPPONENT_BENCH).hp

            assertFalse(intoAttack.ended)
            assertTrue(pokemon(intoAttack, OPPONENT_ACTIVE).hp in 1 until rootMewHp,
                "Sucker Punch must hit a target whose damaging move is still pending")
            assertEquals(rootMewHp, pokemon(intoStatus, OPPONENT_ACTIVE).hp,
                "Sucker Punch must fail into a status move")
            assertEquals(2, pokemon(intoStatus, OPPONENT_ACTIVE).boosts["atk"])
            assertEquals(OPPONENT_BENCH.toString(), intoSwitch.p2Active.single().uuid)
            assertEquals(rootMewHp, pokemon(intoSwitch, OPPONENT_ACTIVE).hp,
                "Sucker Punch must not hit the switching target")
            assertEquals(rootClefableHp, pokemon(intoSwitch, OPPONENT_BENCH).hp,
                "Sucker Punch must not redirect into and damage the incoming Pokemon")
            assertTrue(intoSwitch.log.any { "|-fail|" in it },
                "The native log must retain Sucker Punch's failure after a switch")
            assertEquals(
                listOf("suckerpunch", "tackle"),
                intoAttack.executedMoveOrder.filter { it.turn == 1 }.map { it.moveId },
            )

            val adaptedStatus = NativeBattleStateAdapter.adapt(intoStatus, publicTemplate())
            assertEquals(2, adaptedStatus.pokemon.single {
                it.battlePokemonId == OPPONENT_ACTIVE
            }.statStages["atk"])
            val adaptedSwitch = NativeBattleStateAdapter.adapt(intoSwitch, publicTemplate())
            assertEquals(0, adaptedSwitch.pokemon.single {
                it.battlePokemonId == OPPONENT_BENCH
            }.activeSlot)

            assertOpponentOptionsRemain(intoAttack)
            val attackThenStatus = engine.branch(
                intoAttack.snapshotJson,
                encodedMove(intoAttack, BattleSide.ALLY, "suckerpunch"),
                encodedMove(intoAttack, BattleSide.OPPONENT, "swordsdance"),
            )
            assertEquals(pokemon(intoAttack, OPPONENT_ACTIVE).hp, pokemon(attackThenStatus, OPPONENT_ACTIVE).hp,
                "A previous successful Sucker Punch must not make the next status response count as an attack")
            assertEquals(2, pokemon(attackThenStatus, OPPONENT_ACTIVE).boosts["atk"])

            assertOpponentOptionsRemain(intoStatus)
            val statusThenAttack = engine.branch(
                intoStatus.snapshotJson,
                encodedMove(intoStatus, BattleSide.ALLY, "suckerpunch"),
                encodedMove(intoStatus, BattleSide.OPPONENT, "tackle"),
            )
            assertTrue(
                pokemon(statusThenAttack, OPPONENT_ACTIVE).hp < pokemon(intoStatus, OPPONENT_ACTIVE).hp,
                "A previous failed Sucker Punch must still succeed against a newly selected attack",
            )
        }
    }

    @Test
    fun `native product search enumerates every Sucker Punch response and drives the Brain`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle()
            val prepared = engine.createBattle(definition)
            val tree = NativeShowdownSearchTree(engine, prepared, publicTemplate())
            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            val suckerAction = candidates.single { it.moveId == "suckerpunch" && it.mechanic == null }
            val suckerChoice = NativeShowdownChoiceEncoder.encode(suckerAction, BattleSide.ALLY, prepared)
            val opponentActions = tree.actions(tree.root, BattleSide.OPPONENT)
            val request = JsonParser.parseString(prepared.p2RequestJson).asJsonObject
            val activeRequest = request.getAsJsonArray("active").single().asJsonObject
            val requestMoveIds = activeRequest.getAsJsonArray("moves").mapTo(linkedSetOf()) {
                it.asJsonObject.get("id").asString
            }
            assertEquals(setOf("tackle", "swordsdance"), requestMoveIds)
            assertTrue(activeRequest.has("canTerastallize") && !activeRequest.get("canTerastallize").isJsonNull)
            assertEquals(1, prepared.p2Team.count { it.activeSlot == null && it.hp > 0 })
            assertEquals(5, opponentActions.size,
                "Two requested moves with base and Tera variants plus one healthy bench produce five responses")
            assertMoveVariants(opponentActions, "tackle")
            assertMoveVariants(opponentActions, "swordsdance")
            assertEquals(
                listOf(OPPONENT_BENCH),
                opponentActions.filter { it.kind == BattleActionKind.SWITCH }.map { it.switchPokemonId },
            )
            val expectedOpponentChoices = opponentActions.mapTo(linkedSetOf()) {
                NativeShowdownChoiceEncoder.encode(it, BattleSide.OPPONENT, prepared)
            }
            val attackChild = engine.branch(
                prepared.snapshotJson,
                suckerChoice,
                encodedMove(prepared, BattleSide.OPPONENT, "tackle"),
            )
            val statusChild = engine.branch(
                prepared.snapshotJson,
                suckerChoice,
                encodedMove(prepared, BattleSide.OPPONENT, "swordsdance"),
            )

            val context = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("native-sucker-punch-product".toByteArray()),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = 5_000L,
            )
            val rootIssues = NativeBattleRootValidator.validate(definition, prepared, context.state)
            assertTrue(rootIssues.isEmpty(),
                "The retained Sucker Punch root must agree with its definition and public state: $rootIssues")
            val sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("sucker-punch-mindgame", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, prepared),
                    publicContext = context,
                )),
                publicTurn = context.state.turn,
                lastObservedEventSequence = null,
            )
            val recording = RecordingWorker(engine)
            val runner = NativeProductSearchRunner(
                nanoTime = { 5_000_000L },
                lease = { _, action -> action(recording) },
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
                profile = BattleTrainerProfile.balanced(2),
                tuning = LocalDecisionTuning.CURRENT,
                budget = LocalLookaheadBudget(timeMillis = 2_000L, nodeLimit = 4_096, chanceBranchesPerMove = 1),
                sessionState = sessionState,
            )

            assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status,
                "Native Sucker Punch product evaluation failed: $evaluation")
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(2, evaluation.depthCompleted)
            assertEquals(
                expectedOpponentChoices,
                recording.choicesAt(prepared.snapshotJson, suckerChoice),
                "The Sucker Punch root must evaluate attack, status, and switch responses",
            )
            assertEquals(
                encodedOpponentChoices(attackChild),
                recording.choicesAt(
                    attackChild.snapshotJson,
                    encodedMove(attackChild, BattleSide.ALLY, "suckerpunch"),
                ),
                "After Sucker Punch succeeds, the next search depth must reopen every current response",
            )
            assertEquals(
                encodedOpponentChoices(statusChild),
                recording.choicesAt(
                    statusChild.snapshotJson,
                    encodedMove(statusChild, BattleSide.ALLY, "suckerpunch"),
                ),
                "After Sucker Punch fails, the next search depth must reopen every current response",
            )
            val bestValueByMove = evaluation.ranked.groupBy { it.outcome.candidate.moveId }
                .mapValues { (_, ranks) -> ranks.maxOf { it.comparisonValue } }
            assertTrue(
                bestValueByMove.getValue("lashout") > bestValueByMove.getValue("suckerpunch"),
                "A reliable attack must beat Sucker Punch when the opponent can status or switch",
            )
            assertEquals("lashout", evaluation.ranked.first().outcome.candidate.moveId)

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
                trainerProfile = BattleTrainerProfile.balanced(2),
            ))

            val decision = brain.decide(brainSession, context).toCompletableFuture().get()

            assertEquals("lashout", candidates.single { it.actionId == decision.actionId }.moveId)
            assertTrue("native_showdown_initial" in decision.tags)
            assertTrue("native_search_completed" in decision.tags)
        }
    }

    private fun assertOpponentOptionsRemain(frame: NativeBattleFrame) {
        val actions = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, frame)
        assertEquals(setOf("tackle", "swordsdance"), actions.mapNotNull { it.moveId }.toSet())
        assertEquals(setOf(OPPONENT_BENCH), actions.mapNotNull { it.switchPokemonId }.toSet())
    }

    private fun assertMoveVariants(actions: List<BattleActionCandidate>, moveId: String) {
        val variants = actions.filter { it.moveId == moveId }
        assertEquals(2, variants.size)
        assertEquals(setOf(null, "tera"), variants.mapTo(linkedSetOf()) { it.mechanic?.mechanicId })
    }

    private fun encodedOpponentChoices(frame: NativeBattleFrame): Set<String> =
        NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, frame).mapTo(linkedSetOf()) {
            NativeShowdownChoiceEncoder.encode(it, BattleSide.OPPONENT, frame)
        }

    private fun encodedMove(frame: NativeBattleFrame, side: BattleSide, moveId: String): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame).single {
            it.moveId == moveId && it.mechanic == null
        }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun encodedSwitch(frame: NativeBattleFrame, side: BattleSide, target: UUID): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame).single {
            it.kind == BattleActionKind.SWITCH && it.switchPokemonId == target
        }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun pokemon(frame: NativeBattleFrame, id: UUID) =
        (frame.p1Team + frame.p2Team).single { it.uuid == id.toString() }

    private fun battle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(263, 269, 271, 277),
        p1Team = listOf(NativePokemonSet(
            name = "Conditional Priority",
            species = "Bisharp",
            moves = listOf("suckerpunch", "lashout"),
            ability = "innerfocus",
            uuid = ALLY.toString(),
        )),
        p2Team = listOf(
            NativePokemonSet(
                name = "Mixed Response",
                species = "Mew",
                moves = listOf("tackle", "swordsdance"),
                ability = "synchronize",
                nature = "Bold",
                evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 0),
                uuid = OPPONENT_ACTIVE.toString(),
            ),
            NativePokemonSet(
                name = "Switch Response",
                species = "Clefable",
                moves = listOf("splash"),
                ability = "magicguard",
                nature = "Bold",
                evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 0),
                uuid = OPPONENT_BENCH.toString(),
            ),
        ),
    )

    private fun publicTemplate() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, 0, "bisharp", setOf("dark", "steel")),
            pokemon(OPPONENT_ACTIVE, BattleSide.OPPONENT, 0, "mew", setOf("psychic")),
            pokemon(OPPONENT_BENCH, BattleSide.OPPONENT, null, "clefable", setOf("fairy")),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        activeSlot: Int?,
        species: String,
        types: Set<String>,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = activeSlot,
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

    private class RecordingWorker(
        private val delegate: NativeBranchWorker,
    ) : NativeBranchWorker {
        override val rulesFingerprint: String = delegate.rulesFingerprint
        val branches = mutableListOf<BranchCall>()

        fun choicesAt(snapshotJson: String, p1Choice: String): Set<String> = branches.asSequence()
            .filter { it.snapshotJson == snapshotJson && it.p1Choice == p1Choice }
            .mapTo(linkedSetOf(), BranchCall::p2Choice)

        override fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame =
            delegate.createBattle(definition)

        override fun rebindMoves(
            snapshotJson: String,
            rebindings: List<NativeMoveSetRebinding>,
        ): NativeBattleFrame = delegate.rebindMoves(snapshotJson, rebindings)

        override fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame {
            branches += BranchCall(snapshotJson, p1Choice, p2Choice)
            return delegate.branch(snapshotJson, p1Choice, p2Choice)
        }

        override fun close() = Unit
    }

    private data class BranchCall(
        val snapshotJson: String,
        val p1Choice: String,
        val p2Choice: String,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000701")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000702")
        val OPPONENT_ACTIVE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000703")
        val OPPONENT_BENCH: UUID = UUID.fromString("00000000-0000-0000-0000-000000000704")
    }
}
